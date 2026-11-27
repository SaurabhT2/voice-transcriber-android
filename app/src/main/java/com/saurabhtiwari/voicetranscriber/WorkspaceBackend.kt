package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/**
 * Public client configuration shared with the web application.
 * These values are public project identifiers/keys; RLS and the user's
 * Supabase access token provide authorization. Never place service-role keys here.
 */
object WorkspaceConfig {
    val SUPABASE_URL = BuildConfig.SUPABASE_URL
    val SUPABASE_PUBLISHABLE_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val API_BASE_URL = BuildConfig.API_BASE_URL
    const val AUDIO_BUCKET = "meeting-audio"
    const val AUTH_REDIRECT_URI = "meetingworkspace://auth/callback"
}

data class RecordingItem(
    val id: String,
    val title: String,
    val status: String,
    val source: String,
    val createdAt: String,
    val meetingId: String?,
    val transcriptId: String?,
    val errorCode: String? = null,
    val isLocalOnly: Boolean = false
)

/** Transcript of one finished live chunk. [failed] chunks carry no text. */
data class LiveTextChunk(
    val index: Int,
    val startSeconds: Double,
    val text: String,
    val failed: Boolean
)

data class CloudJob(
    val jobId: String,
    val status: String,
    val meetingId: String?,
    val transcriptId: String?,
    val errorCode: String?
)

data class MeetingDetail(
    val meetingId: String,
    val title: String,
    val source: String,
    val transcript: String,
    val language: String?,
    val model: String?,
    val segments: JSONArray,
    val speakers: JSONArray,
    val createdAt: String
)

/** AES-GCM encryption with a non-exportable Android Keystore key. */
internal class KeystoreBox(private val alias: String) {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) return keyStore.getKey(alias, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** @throws GeneralSecurityException if the key is gone or the data was tampered with. */
    fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= 12) throw GeneralSecurityException("Stored value is too short.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return String(cipher.doFinal(payload.copyOfRange(12, payload.size)), StandardCharsets.UTF_8)
    }
}

class SecureSessionStore(context: Context) : SessionPersistence {
    private val preferences = context.applicationContext.getSharedPreferences(
        "meeting_workspace_session", Context.MODE_PRIVATE
    )
    private val box = KeystoreBox("meeting_workspace_session_key")

    override fun save(session: UserSession) {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("expires_at", session.expiresAtEpochSeconds)
            .put("user_id", session.userId)
            .put("email", session.email)
        preferences.edit().putString("session", box.encrypt(json.toString())).apply()
    }

    /**
     * Returns null when nothing usable is stored. Only data that can never be read again
     * (key invalidated, corrupted ciphertext) is deleted; a transient Keystore hiccup keeps it.
     */
    override fun load(): UserSession? {
        val encoded = preferences.getString("session", null) ?: return null
        return try {
            val json = JSONObject(box.decrypt(encoded))
            UserSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token"),
                expiresAtEpochSeconds = json.optLong("expires_at"),
                userId = json.getString("user_id"),
                email = json.optString("email")
            )
        } catch (_: GeneralSecurityException) {
            clear(); null
        } catch (_: JSONException) {
            clear(); null
        } catch (_: IllegalArgumentException) {
            clear(); null
        } catch (_: Exception) {
            null
        }
    }

    override fun clear() {
        preferences.edit().remove("session").apply()
    }
}

/** Holds the PKCE code verifier between "email me a link" and "link opened". */
class PkceVerifierStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "meeting_workspace_pkce", Context.MODE_PRIVATE
    )
    private val box = KeystoreBox("meeting_workspace_session_key")

    fun save(verifier: String) {
        val json = JSONObject().put("v", verifier).put("t", System.currentTimeMillis())
        preferences.edit().putString("pending", box.encrypt(json.toString())).apply()
    }

    fun load(): String? {
        val encoded = preferences.getString("pending", null) ?: return null
        return try {
            val json = JSONObject(box.decrypt(encoded))
            if (System.currentTimeMillis() - json.getLong("t") > MAX_AGE_MS) { clear(); null }
            else json.getString("v")
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        preferences.edit().remove("pending").apply()
    }

    private companion object {
        const val MAX_AGE_MS = 60L * 60L * 1000L
    }
}

/**
 * Uses the same Supabase Auth project and versioned FastAPI contract as the web client.
 *
 * Authentication is handled centrally: every authenticated call goes through [authed], which
 * refreshes an expiring token first, and on a 401 refreshes once and retries once. The saved
 * session is only discarded when the auth server explicitly rejects the refresh token, never
 * because the device happens to be offline.
 */
class WorkspaceBackend(context: Context) {
    private val appContext = context.applicationContext
    private val sessionStore = SecureSessionStore(appContext)
    private val pkceStore = PkceVerifierStore(appContext)
    private val devicePreferences = appContext.getSharedPreferences("meeting_workspace_device", Context.MODE_PRIVATE)
    private val sessions = SessionManager(
        persistence = sessionStore,
        refresher = { current ->
            withContext(Dispatchers.IO) {
                requestJson(
                    "POST",
                    WorkspaceConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token",
                    headers = publicHeaders,
                    body = JSONObject().put("refresh_token", current.refreshToken)
                )
            }.toSession(previous = current)
        }
    )

    private val publicHeaders get() = mapOf("apikey" to WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY)

    /** The saved user id without touching the network, or null when signed out. */
    suspend fun savedUserId(): String? = sessions.active()?.userId

    suspend fun sendMagicLink(email: String) = withContext(Dispatchers.IO) {
        val pkce = Pkce.generate()
        val redirect = URLEncoder.encode(WorkspaceConfig.AUTH_REDIRECT_URI, "UTF-8")
        val body = JSONObject()
            .put("email", email.trim())
            .put("create_user", true)
            .put("code_challenge", pkce.challenge)
            .put("code_challenge_method", "s256")
        requestJson(
            "POST",
            WorkspaceConfig.SUPABASE_URL + "/auth/v1/otp?redirect_to=" + redirect,
            headers = publicHeaders,
            body = body
        )
        // Saved only after the request succeeded so a failure cannot clobber a pending link.
        pkceStore.save(pkce.verifier)
        Unit
    }

    /**
     * Completes the PKCE flow: the emailed link carries a one-time `code` that is useless without
     * the verifier that only this device holds, so another app that intercepts the custom-scheme
     * link cannot sign anyone in with it.
     */
    suspend fun completeMagicLink(callback: Uri): UserSession = withContext(Dispatchers.IO) {
        val parameters = linkedMapOf<String, String>()
        callback.queryParameterNames.forEach { name ->
            callback.getQueryParameter(name)?.let { parameters[name] = it }
        }
        val fragment = callback.fragment.orEmpty().removePrefix("#")
        if (fragment.isNotBlank()) {
            // Auth errors (expired link etc.) arrive in the fragment. Never read tokens from here.
            val fragmentUri = Uri.parse("https://callback.invalid/?" + fragment)
            fragmentUri.queryParameterNames.forEach { name ->
                if (name.startsWith("error")) fragmentUri.getQueryParameter(name)?.let { parameters[name] = it }
            }
        }
        val authError = parameters["error_description"] ?: parameters["error"]
        if (!authError.isNullOrBlank()) throw IOException(authError.replace('+', ' '))

        val code = parameters["code"]?.takeIf { it.isNotBlank() }
            ?: throw IOException("The sign-in link did not contain a valid code. Open the newest link from your email on this phone.")
        val verifier = pkceStore.load()
            ?: throw IOException("This sign-in link was requested on another device or has expired. Request a new link on this phone.")

        val token = try {
            requestJson(
                "POST",
                WorkspaceConfig.SUPABASE_URL + "/auth/v1/token?grant_type=pkce",
                headers = publicHeaders,
                body = JSONObject().put("auth_code", code).put("code_verifier", verifier)
            )
        } catch (error: HttpException) {
            if (error.code in 400..499) pkceStore.clear() // the code is single-use; start over
            throw error
        }
        pkceStore.clear()
        token.toSession(previous = null).also { saved -> sessions.adopt(saved) }
    }

    /**
     * Loads the saved session. If its token is (nearly) expired it is refreshed; when that fails
     * only because the device is offline the saved session is still returned so local features
     * keep working. Returns null only when signed out or the refresh token was rejected.
     */
    suspend fun restoreSession(): UserSession? = sessions.restore()

    suspend fun signOut() {
        val token = sessions.current()?.accessToken
        try {
            if (token != null) {
                withContext(Dispatchers.IO) {
                    requestText("POST", WorkspaceConfig.SUPABASE_URL + "/auth/v1/logout", publicHeaders, bearerToken = token)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Best effort: signing out must work offline too.
        } finally {
            sessions.invalidate()
        }
    }

    /** Runs an authenticated request; see [SessionManager.authed]. */
    private suspend fun <T> authed(block: suspend (accessToken: String) -> T): T = sessions.authed(block)

    suspend fun loadRecordings(): List<RecordingItem> {
        val userId = sessions.active()?.userId ?: throw SessionExpiredException()
        val jobsUrl = Uri.parse(WorkspaceConfig.SUPABASE_URL + "/rest/v1/transcription_jobs")
            .buildUpon()
            .appendQueryParameter(
                "select",
                "id,status,source,filename,created_at,meeting_id,transcript_id,error_code"
            )
            .appendQueryParameter("owner_id", "eq.$userId")
            .appendQueryParameter("order", "created_at.desc")
            .appendQueryParameter("limit", "100")
            .build().toString()
        val meetingsUrl = Uri.parse(WorkspaceConfig.SUPABASE_URL + "/rest/v1/meetings")
            .buildUpon()
            .appendQueryParameter("select", "id,title,source,created_at")
            .appendQueryParameter("owner_id", "eq.$userId")
            .appendQueryParameter("order", "created_at.desc")
            .appendQueryParameter("limit", "100")
            .build().toString()

        val (jobsText, meetingsText) = coroutineScope {
            val jobsRequest = async { authed { token -> requestText("GET", jobsUrl, publicHeaders, bearerToken = token) } }
            val meetingsRequest = async { authed { token -> requestText("GET", meetingsUrl, publicHeaders, bearerToken = token) } }
            jobsRequest.await() to meetingsRequest.await()
        }
        val jobs = JSONArray(jobsText)
        val meetings = JSONArray(meetingsText)
        val meetingById = mutableMapOf<String, JSONObject>()
        for (index in 0 until meetings.length()) {
            val row = meetings.getJSONObject(index)
            meetingById[row.getString("id")] = row
        }
        val linkedMeetingIds = mutableSetOf<String>()
        val result = mutableListOf<RecordingItem>()
        for (index in 0 until jobs.length()) {
            val row = jobs.getJSONObject(index)
            val meetingId = row.optNullableString("meeting_id")
            if (meetingId != null) linkedMeetingIds.add(meetingId)
            val linkedMeeting = meetingId?.let(meetingById::get)
            result += RecordingItem(
                id = row.getString("id"),
                title = linkedMeeting?.optString("title")?.takeIf { !it.isNullOrBlank() }
                    ?: row.optString("filename", "Audio recording"),
                status = row.optString("status", "queued"),
                source = row.optString("source"),
                createdAt = row.optString("created_at"),
                meetingId = meetingId,
                transcriptId = row.optNullableString("transcript_id"),
                errorCode = row.optNullableString("error_code")
            )
        }
        for (index in 0 until meetings.length()) {
            val row = meetings.getJSONObject(index)
            val meetingId = row.getString("id")
            if (meetingId !in linkedMeetingIds) {
                result += RecordingItem(
                    id = "meeting:$meetingId",
                    title = row.optString("title", "Untitled meeting"),
                    status = "succeeded",
                    source = row.optString("source"),
                    createdAt = row.optString("created_at"),
                    meetingId = meetingId,
                    transcriptId = null
                )
            }
        }
        return result.sortedByDescending { it.createdAt }
    }

    /**
     * Creates the job, uploads the audio and completes the upload. Each step is its own
     * authenticated call, so a token refresh never re-creates the job.
     */
    suspend fun createCloudUpload(
        file: File,
        fileName: String,
        contentType: String,
        language: String?,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): CloudJob {
        require(file.isFile && file.length() > 0L) { "The selected recording is empty or unavailable." }
        require(file.length() <= 50L * 1024L * 1024L) { "This workspace accepts audio files up to 50 MiB." }
        val createBody = JSONObject()
            .put("filename", fileName)
            .put("content_type", contentType)
            .put("source", "android_upload")
            .put("language", language ?: JSONObject.NULL)
        val uploadSession = authed { token ->
            requestJson("POST", WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/upload", bearerToken = token, body = createBody)
        }
        val jobId = uploadSession.getJSONObject("job").getString("job_id")
        val uploadUrl = uploadSession.getString("upload_url")
        authed { token -> uploadSignedFile(uploadUrl, file, contentType, token, onProgress) }
        return authed { token ->
            requestJson(
                "POST",
                WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/$jobId/complete-upload",
                bearerToken = token,
                body = JSONObject()
            )
        }.toCloudJob()
    }

    suspend fun getCloudJob(jobId: String): CloudJob = authed { token ->
        requestJson("GET", WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/$jobId", bearerToken = token)
    }.toCloudJob()

    suspend fun cancelCloudJob(jobId: String): CloudJob = authed { token ->
        requestJson(
            "POST", WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/$jobId/cancel",
            bearerToken = token, body = JSONObject()
        )
    }.toCloudJob()

    suspend fun getMeeting(meetingId: String): MeetingDetail = authed { token ->
        requestJson("GET", WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/$meetingId", bearerToken = token)
    }.toMeetingDetail()

    suspend fun syncOfflineTranscript(
        eventId: String,
        title: String,
        startedAt: String,
        endedAt: String,
        transcript: String,
        language: String?
    ): String {
        val deviceId = devicePreferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            devicePreferences.edit().putString("device_id", it).apply()
        }
        val body = JSONObject()
            .put("client_event_id", eventId)
            .put("device_id", deviceId)
            .put("title", title)
            .put("started_at", startedAt)
            .put("ended_at", endedAt)
            .put("transcript", transcript)
            .put("language", language ?: JSONObject.NULL)
            .put("model", WhisperModelManager.MODEL_ID)
        return authed { token ->
            requestJson("POST", WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/sync", bearerToken = token, body = body)
        }.getString("meeting_id")
    }

    suspend fun getMeetingNotes(meetingId: String): JSONObject = authed { token ->
        requestJson("GET", WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/$meetingId/notes", bearerToken = token)
    }

    suspend fun generateMeetingNotes(meetingId: String, regenerate: Boolean = false): JSONObject = authed { token ->
        requestJson(
            "POST", WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/$meetingId/notes",
            bearerToken = token, body = JSONObject().put("regenerate", regenerate)
        )
    }

    suspend fun getMeetingNotesStatus(meetingId: String): JSONObject = authed { token ->
        requestJson("GET", WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/$meetingId/notes/status", bearerToken = token)
    }

    suspend fun getMeetingNotesJob(meetingId: String, jobId: String): JSONObject = authed { token ->
        requestJson("GET", WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/$meetingId/notes/jobs/$jobId", bearerToken = token)
    }

    // ---- live transcription (see sql/001_live_transcription.sql in the agent bundle) -----------

    /** Creates the server-side session that streamed chunks are attached to. */
    suspend fun createLiveSession(language: String?): String {
        val body = JSONObject().put("language", language ?: JSONObject.NULL)
        val response = authed { token ->
            requestText(
                "POST", WorkspaceConfig.SUPABASE_URL + "/rest/v1/live_sessions",
                publicHeaders + ("Prefer" to "return=representation"), token, body.toString()
            )
        }
        return JSONArray(response).getJSONObject(0).getString("id")
    }

    /**
     * Uploads one audio chunk and registers it for transcription. Safe to repeat after a failure:
     * an already-uploaded file or already-registered chunk counts as success.
     */
    suspend fun uploadLiveChunk(
        sessionId: String,
        index: Int,
        startSeconds: Double,
        durationSeconds: Double,
        audio: ByteArray
    ) {
        val userId = sessions.active()?.userId ?: throw SessionExpiredException()
        val objectPath = "live/$userId/$sessionId/$index.aac"
        authed { token ->
            try {
                send(
                    "POST",
                    WorkspaceConfig.SUPABASE_URL + "/storage/v1/object/" + WorkspaceConfig.AUDIO_BUCKET + "/" + objectPath,
                    publicHeaders + ("x-upsert" to "false"), token, audio, "audio/aac"
                )
            } catch (error: HttpException) {
                if (!isDuplicate(error)) throw error
            }
            val row = JSONObject()
                .put("session_id", sessionId)
                .put("chunk_index", index)
                .put("audio_object_path", objectPath)
                .put("start_seconds", startSeconds)
                .put("duration_seconds", durationSeconds)
            try {
                requestText(
                    "POST", WorkspaceConfig.SUPABASE_URL + "/rest/v1/live_chunks",
                    publicHeaders + ("Prefer" to "resolution=ignore-duplicates,return=minimal"), token, row.toString()
                )
            } catch (error: HttpException) {
                if (error.code != 409) throw error
            }
        }
    }

    /** Chunks after [afterIndex] that the worker has finished (or given up on), oldest first. */
    suspend fun fetchLiveChunkResults(sessionId: String, afterIndex: Int): List<LiveTextChunk> {
        val url = Uri.parse(WorkspaceConfig.SUPABASE_URL + "/rest/v1/live_chunks").buildUpon()
            .appendQueryParameter("select", "chunk_index,start_seconds,text,status")
            .appendQueryParameter("session_id", "eq.$sessionId")
            .appendQueryParameter("chunk_index", "gt.$afterIndex")
            .appendQueryParameter("status", "in.(succeeded,failed)")
            .appendQueryParameter("order", "chunk_index.asc")
            .build().toString()
        val rows = JSONArray(authed { token -> requestText("GET", url, publicHeaders, token) })
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            LiveTextChunk(
                index = row.getInt("chunk_index"),
                startSeconds = row.optDouble("start_seconds", 0.0),
                text = row.optString("text").takeIf { !row.isNull("text") }.orEmpty().trim(),
                failed = row.optString("status") == "failed"
            )
        }
    }

    suspend fun closeLiveSession(sessionId: String) {
        val body = JSONObject().put("status", "closed").put("closed_at", java.time.Instant.now().toString())
        authed { token ->
            requestText(
                "PATCH", WorkspaceConfig.SUPABASE_URL + "/rest/v1/live_sessions?id=eq.$sessionId",
                publicHeaders + ("Prefer" to "return=minimal"), token, body.toString()
            )
        }
    }

    private fun isDuplicate(error: HttpException) =
        error.code == 409 || error.message.orEmpty().contains("already exists", ignoreCase = true)

    private suspend fun uploadSignedFile(
        signedUrl: String,
        file: File,
        contentType: String,
        accessToken: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ) {
        val target = if (signedUrl.startsWith("/")) {
            WorkspaceConfig.SUPABASE_URL + "/storage/v1" + signedUrl
        } else signedUrl
        val total = file.length()
        val coroutine = currentCoroutineContext()
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setFixedLengthStreamingMode(total)
            setRequestProperty("apikey", WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Cache-Control", "max-age=3600")
            setRequestProperty("x-upsert", "false")
        }
        try {
            file.inputStream().buffered().use { input ->
                connection.outputStream.buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutine.ensureActive() // cancelling the coroutine aborts the upload
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        if (sent - lastReport >= 256 * 1024 || sent == total) {
                            lastReport = sent
                            onProgress(sent, total)
                        }
                    }
                }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw HttpException(status, "Audio upload failed (HTTP $status): " + response.take(300))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun JSONObject.toSession(previous: UserSession?): UserSession {
        val user = optJSONObject("user")
        val userId = user?.optString("id")?.takeIf { it.isNotBlank() }
            ?: previous?.userId
            ?: throw IOException("The sign-in response did not include a user.")
        val expiresAt = optLong("expires_at", 0L).takeIf { it > 0L }
            ?: (nowSeconds() + optLong("expires_in", 3600L))
        return UserSession(
            accessToken = getString("access_token"),
            refreshToken = optString("refresh_token", previous?.refreshToken.orEmpty()),
            expiresAtEpochSeconds = expiresAt,
            userId = userId,
            email = user?.optString("email")?.takeIf { it.isNotBlank() } ?: previous?.email.orEmpty()
        )
    }

    private fun requestJson(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        bearerToken: String? = null,
        body: JSONObject? = null
    ): JSONObject {
        val text = requestText(method, url, headers, bearerToken, body?.toString())
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requestText(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        bearerToken: String? = null,
        body: String? = null
    ): String = send(method, url, headers, bearerToken, body?.toByteArray(StandardCharsets.UTF_8), "application/json")

    private fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        bearerToken: String?,
        body: ByteArray?,
        contentType: String
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (bearerToken != null) setRequestProperty("Authorization", "Bearer " + bearerToken)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = try {
                    val json = JSONObject(response)
                    val detail = json.opt("detail")
                    when (detail) {
                        is JSONObject -> detail.optString("message", detail.optString("code", response))
                        is String -> detail
                        else -> json.optString("message", response)
                    }
                } catch (_: Exception) {
                    response.ifBlank { "Request failed (HTTP " + status + ")." }
                }
                throw HttpException(status, message)
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toCloudJob() = CloudJob(
        jobId = optString("job_id"),
        status = optString("status"),
        meetingId = optNullableString("meeting_id"),
        transcriptId = optNullableString("transcript_id"),
        errorCode = optNullableString("error_code")
    )

    private fun JSONObject.toMeetingDetail() = MeetingDetail(
        meetingId = optString("meeting_id"),
        title = optString("title", "Untitled meeting"),
        source = optString("source"),
        transcript = optString("transcript"),
        language = optNullableString("language"),
        model = optNullableString("model"),
        segments = optJSONArray("segments") ?: JSONArray(),
        speakers = optJSONArray("speakers") ?: JSONArray(),
        createdAt = optString("created_at")
    )
}
