package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

data class UserSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String
)

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

class SecureSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "meeting_workspace_session", Context.MODE_PRIVATE
    )
    private val alias = "meeting_workspace_session_key"

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as SecretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(session: UserSession) {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("expires_at", session.expiresAtEpochSeconds)
            .put("user_id", session.userId)
            .put("email", session.email)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit()
            .putString("session", Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    fun load(): UserSession? {
        val encoded = preferences.getString("session", null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= 12) return null
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
            UserSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token"),
                expiresAtEpochSeconds = json.optLong("expires_at"),
                userId = json.getString("user_id"),
                email = json.optString("email")
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        preferences.edit().remove("session").apply()
    }
}

/**
 * Uses the same Supabase Auth project and versioned FastAPI contract as the web client.
 * Network calls are dispatched to IO; session credentials are encrypted with Android Keystore.
 */
class WorkspaceBackend(context: Context) {
    private val appContext = context.applicationContext
    private val sessionStore = SecureSessionStore(appContext)
    private val devicePreferences = appContext.getSharedPreferences("meeting_workspace_device", Context.MODE_PRIVATE)

    suspend fun sendMagicLink(email: String) = withContext(Dispatchers.IO) {
        val redirect = URLEncoder.encode(WorkspaceConfig.AUTH_REDIRECT_URI, "UTF-8")
        val body = JSONObject().put("email", email.trim()).put("create_user", true)
        requestJson(
            "POST",
            WorkspaceConfig.SUPABASE_URL + "/auth/v1/otp?redirect_to=" + redirect,
            headers = mapOf("apikey" to WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY),
            body = body
        )
        Unit
    }

    suspend fun completeMagicLink(callback: Uri): UserSession = withContext(Dispatchers.IO) {
        val parameters = linkedMapOf<String, String>()
        callback.queryParameterNames.forEach { name ->
            callback.getQueryParameter(name)?.let { parameters[name] = it }
        }
        val fragment = callback.fragment.orEmpty().removePrefix("#")
        if (fragment.isNotBlank()) {
            val fragmentUri = Uri.parse("https://callback.invalid/?" + fragment)
            fragmentUri.queryParameterNames.forEach { name ->
                fragmentUri.getQueryParameter(name)?.let { parameters[name] = it }
            }
        }
        val authError = parameters["error_description"] ?: parameters["error"]
        if (!authError.isNullOrBlank()) throw IOException(authError.replace('+', ' '))
        val accessToken = parameters["access_token"]
            ?: throw IOException(
                if (parameters.containsKey("code")) {
                    "The sign-in link returned an authorization code instead of a session. Check the mobile email-link flow configuration."
                } else {
                    "The sign-in link did not contain a session. Open the newest link from your email."
                }
            )
        val refreshToken = parameters["refresh_token"].orEmpty()
        val expiresIn = parameters["expires_in"]?.toLongOrNull() ?: 3600L
        val expiresAt = parameters["expires_at"]?.toLongOrNull()
            ?: (System.currentTimeMillis() / 1000L) + expiresIn
        val user = requestJson(
            "GET",
            WorkspaceConfig.SUPABASE_URL + "/auth/v1/user",
            headers = mapOf("apikey" to WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY),
            bearerToken = accessToken
        )
        val session = UserSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = expiresAt,
            userId = user.getString("id"),
            email = user.optString("email")
        )
        sessionStore.save(session)
        session
    }

    suspend fun restoreSession(): UserSession? = withContext(Dispatchers.IO) {
        val current = sessionStore.load() ?: return@withContext null
        val now = System.currentTimeMillis() / 1000L
        if (current.expiresAtEpochSeconds > now + 90L) {
            // The signed-in API validates the token. Keep the saved session usable offline
            // instead of forcing a sign-in just because this device has no network.
            return@withContext current
        }
        refreshSession(current)
    }

    private fun refreshSession(current: UserSession): UserSession? {
        if (current.refreshToken.isBlank()) {
            sessionStore.clear()
            return null
        }
        return try {
            val token = requestJson(
                "POST",
                WorkspaceConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token",
                headers = mapOf("apikey" to WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY),
                body = JSONObject().put("refresh_token", current.refreshToken)
            )
            val accessToken = token.getString("access_token")
            val refreshToken = token.optString("refresh_token", current.refreshToken)
            val expiresAt = token.optLong(
                "expires_at",
                (System.currentTimeMillis() / 1000L) + token.optLong("expires_in", 3600L)
            )
            val user = requestJson(
                "GET",
                WorkspaceConfig.SUPABASE_URL + "/auth/v1/user",
                headers = mapOf("apikey" to WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY),
                bearerToken = accessToken
            )
            UserSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSeconds = expiresAt,
                userId = user.getString("id"),
                email = user.optString("email")
            ).also(sessionStore::save)
        } catch (_: Exception) {
            sessionStore.clear()
            null
        }
    }

    suspend fun signOut(session: UserSession?) = withContext(Dispatchers.IO) {
        try {
            if (session != null) {
                requestText(
                    "POST",
                    WorkspaceConfig.SUPABASE_URL + "/auth/v1/logout",
                    headers = mapOf(
                        "apikey" to WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY,
                        "Authorization" to "Bearer " + session.accessToken
                    )
                )
            }
        } finally {
            sessionStore.clear()
        }
    }

    suspend fun loadRecordings(session: UserSession): List<RecordingItem> = withContext(Dispatchers.IO) {
        val jobsUrl = Uri.parse(WorkspaceConfig.SUPABASE_URL + "/rest/v1/transcription_jobs")
            .buildUpon()
            .appendQueryParameter(
                "select",
                "id,status,source,filename,created_at,meeting_id,transcript_id,error_code"
            )
            .appendQueryParameter("owner_id", "eq." + session.userId)
            .appendQueryParameter("order", "created_at.desc")
            .appendQueryParameter("limit", "100")
            .build().toString()
        val meetingsUrl = Uri.parse(WorkspaceConfig.SUPABASE_URL + "/rest/v1/meetings")
            .buildUpon()
            .appendQueryParameter("select", "id,title,source,created_at")
            .appendQueryParameter("owner_id", "eq." + session.userId)
            .appendQueryParameter("order", "created_at.desc")
            .appendQueryParameter("limit", "100")
            .build().toString()

        val headers = mapOf("apikey" to WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY)
        val jobs = JSONArray(requestText("GET", jobsUrl, headers, bearerToken = session.accessToken))
        val meetings = JSONArray(requestText("GET", meetingsUrl, headers, bearerToken = session.accessToken))
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
                    id = "meeting:" + meetingId,
                    title = row.optString("title", "Untitled meeting"),
                    status = "succeeded",
                    source = row.optString("source"),
                    createdAt = row.optString("created_at"),
                    meetingId = meetingId,
                    transcriptId = null
                )
            }
        }
        result.sortedByDescending { it.createdAt }
    }

    suspend fun createCloudUpload(
        session: UserSession,
        file: File,
        fileName: String,
        contentType: String,
        language: String?
    ): CloudJob = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "The selected recording is empty or unavailable." }
        require(file.length() <= 50L * 1024L * 1024L) { "This workspace accepts audio files up to 50 MiB." }
        val createBody = JSONObject()
            .put("filename", fileName)
            .put("content_type", contentType)
            .put("source", "android_upload")
            .put("language", language ?: JSONObject.NULL)
        val uploadSession = requestJson(
            "POST",
            WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/upload",
            bearerToken = session.accessToken,
            body = createBody
        )
        val jobId = uploadSession.getJSONObject("job").getString("job_id")
        val uploadUrl = uploadSession.getString("upload_url")
        uploadSignedFile(uploadUrl, file, contentType, session.accessToken)

        requestJson(
            "POST",
            WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/" + jobId + "/complete-upload",
            bearerToken = session.accessToken,
            body = JSONObject()
        ).toCloudJob()
    }

    suspend fun getCloudJob(session: UserSession, jobId: String): CloudJob = withContext(Dispatchers.IO) {
        requestJson(
            "GET",
            WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/" + jobId,
            bearerToken = session.accessToken
        ).toCloudJob()
    }

    suspend fun cancelCloudJob(session: UserSession, jobId: String): CloudJob = withContext(Dispatchers.IO) {
        requestJson(
            "POST",
            WorkspaceConfig.API_BASE_URL + "/api/v1/transcriptions/" + jobId + "/cancel",
            bearerToken = session.accessToken,
            body = JSONObject()
        ).toCloudJob()
    }

    suspend fun getMeeting(session: UserSession, meetingId: String): MeetingDetail = withContext(Dispatchers.IO) {
        requestJson(
            "GET",
            WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/" + meetingId,
            bearerToken = session.accessToken
        ).toMeetingDetail()
    }

    suspend fun syncOfflineTranscript(
        session: UserSession,
        eventId: String,
        title: String,
        startedAt: String,
        endedAt: String,
        transcript: String,
        language: String?
    ): String = withContext(Dispatchers.IO) {
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
            .put("model", "ggml-tiny-q5_1")
        requestJson(
            "POST",
            WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/sync",
            bearerToken = session.accessToken,
            body = body
        ).getString("meeting_id")
    }

    suspend fun getMeetingNotes(session: UserSession, meetingId: String): JSONObject = withContext(Dispatchers.IO) {
        requestJson(
            "GET",
            WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/" + meetingId + "/notes",
            bearerToken = session.accessToken
        )
    }

    suspend fun generateMeetingNotes(
        session: UserSession,
        meetingId: String,
        regenerate: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        requestJson(
            "POST",
            WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/" + meetingId + "/notes",
            bearerToken = session.accessToken,
            body = JSONObject().put("regenerate", regenerate)
        )
    }

    suspend fun getMeetingNotesStatus(session: UserSession, meetingId: String): JSONObject = withContext(Dispatchers.IO) {
        requestJson(
            "GET",
            WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/" + meetingId + "/notes/status",
            bearerToken = session.accessToken
        )
    }

    suspend fun getMeetingNotesJob(
        session: UserSession,
        meetingId: String,
        jobId: String
    ): JSONObject = withContext(Dispatchers.IO) {
        requestJson(
            "GET",
            WorkspaceConfig.API_BASE_URL + "/api/v1/meetings/" + meetingId + "/notes/jobs/" + jobId,
            bearerToken = session.accessToken
        )
    }

    private fun uploadSignedFile(
        signedUrl: String,
        file: File,
        contentType: String,
        accessToken: String
    ) {
        val target = if (signedUrl.startsWith("/")) {
            WorkspaceConfig.SUPABASE_URL + "/storage/v1" + signedUrl
        } else signedUrl
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("apikey", WorkspaceConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer " + accessToken)
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Cache-Control", "max-age=3600")
            setRequestProperty("x-upsert", "false")
        }
        try {
            file.inputStream().buffered().use { input ->
                connection.outputStream.buffered().use { output -> input.copyTo(output) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("Audio upload failed (HTTP " + status + "): " + response.take(300))
            }
        } finally {
            connection.disconnect()
        }
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
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body) }
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
                throw IOException(message)
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

internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }
