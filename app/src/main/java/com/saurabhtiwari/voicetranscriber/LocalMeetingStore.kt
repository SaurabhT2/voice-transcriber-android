package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalMeetingRecord(
    val eventId: String,
    val ownerId: String,
    val title: String,
    val transcript: String,
    val createdAt: String,
    val startedAt: String,
    val endedAt: String,
    val language: String?,
    val meetingId: String?
)

class LocalMeetingStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "local_meetings").apply { mkdirs() }

    @Synchronized
    fun save(
        ownerId: String,
        title: String,
        transcript: String,
        startedAt: String,
        endedAt: String,
        language: String?
    ): LocalMeetingRecord {
        require(ownerId.isNotBlank()) { "A signed-in owner is required to save a local transcript." }
        val record = LocalMeetingRecord(
            eventId = UUID.randomUUID().toString(),
            ownerId = ownerId,
            title = title,
            transcript = transcript,
            createdAt = Instant.now().toString(),
            startedAt = startedAt,
            endedAt = endedAt,
            language = language,
            meetingId = null
        )
        write(record)
        return record
    }

    @Synchronized
    fun list(ownerId: String): List<LocalMeetingRecord> =
        directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    LocalMeetingRecord(
                        eventId = json.getString("event_id"),
                        ownerId = json.getString("owner_id"),
                        title = json.optString("title", "Local recording"),
                        transcript = json.optString("transcript"),
                        createdAt = json.optString("created_at"),
                        startedAt = json.optString("started_at"),
                        endedAt = json.optString("ended_at"),
                        language = json.optNullableString("language"),
                        meetingId = json.optNullableString("meeting_id")
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .filter { it.ownerId == ownerId }
            .sortedByDescending { it.createdAt }

    @Synchronized
    fun markSynced(ownerId: String, eventId: String, meetingId: String) {
        val existing = list(ownerId).firstOrNull { it.eventId == eventId } ?: return
        write(existing.copy(meetingId = meetingId))
    }

    fun pendingSync(ownerId: String): List<LocalMeetingRecord> =
        list(ownerId).filter { it.meetingId == null }

    private fun write(record: LocalMeetingRecord) {
        val json = JSONObject()
            .put("event_id", record.eventId)
            .put("owner_id", record.ownerId)
            .put("title", record.title)
            .put("transcript", record.transcript)
            .put("created_at", record.createdAt)
            .put("started_at", record.startedAt)
            .put("ended_at", record.endedAt)
            .put("language", record.language ?: JSONObject.NULL)
            .put("meeting_id", record.meetingId ?: JSONObject.NULL)
        File(directory, record.eventId + ".json").writeText(json.toString())
    }
}

class MeetingAudioRecorder(context: Context) {
    private val appContext = context.applicationContext
    private var recorder: android.media.MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): File {
        check(recorder == null) { "Recording is already active." }
        val directory = File(appContext.filesDir, "recordings").apply { mkdirs() }
        val target = File(directory, "meeting_" + System.currentTimeMillis() + ".m4a")
        val instance = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.media.MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaRecorder()
        }
        try {
            instance.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            instance.setAudioChannels(1)
            instance.setAudioSamplingRate(48_000)
            instance.setAudioEncodingBitRate(96_000)
            instance.setOutputFile(target.absolutePath)
            instance.prepare()
            instance.start()
            recorder = instance
            outputFile = target
            return target
        } catch (error: Exception) {
            try { instance.reset() } catch (_: Exception) { }
            instance.release()
            target.delete()
            throw error
        }
    }

    fun pause() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) recorder?.pause()
    }

    fun resume() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) recorder?.resume()
    }

    fun stop(): File? {
        val active = recorder ?: return null
        recorder = null
        val target = outputFile
        outputFile = null
        try {
            active.stop()
        } catch (_: RuntimeException) {
            target?.delete()
            return null
        } finally {
            try { active.reset() } catch (_: Exception) { }
            active.release()
        }
        return target?.takeIf { it.isFile && it.length() > 1024L }
    }

    fun release() {
        val active = recorder ?: return
        recorder = null
        try { active.stop() } catch (_: Exception) { }
        try { active.reset() } catch (_: Exception) { }
        active.release()
        outputFile?.delete()
        outputFile = null
    }
}

suspend fun copyRecordingToCache(context: Context, uri: Uri, suggestedName: String): File {
    val directory = File(context.cacheDir, "upload_staging").apply { mkdirs() }
    val safeName = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
    val destination = File(directory, System.currentTimeMillis().toString() + "_" + safeName)
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("Android could not read the selected audio file.")
    }
    return destination
}
