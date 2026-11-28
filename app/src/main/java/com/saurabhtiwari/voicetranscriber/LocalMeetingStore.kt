package com.saurabhtiwari.voicetranscriber

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

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

/**
 * Offline-first transcript storage, one JSON file per transcript.
 *
 * - Files are parsed once and served from memory afterwards, so listing is O(1) disk reads.
 * - Writes are atomic (temp file + rename); a crash can never truncate an existing transcript.
 * - Unreadable files are renamed to `*.corrupt` instead of silently vanishing.
 *
 * All methods perform blocking disk I/O; call them from Dispatchers.IO.
 */
class LocalMeetingStore(private val directory: File) {
    private var cache: MutableList<LocalMeetingRecord>? = null

    init {
        directory.mkdirs()
    }

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
        val all = records() // load existing files first so the new one is not read back from disk
        write(record)
        all.add(record)
        return record
    }

    @Synchronized
    fun list(ownerId: String): List<LocalMeetingRecord> =
        records().filter { it.ownerId == ownerId }.sortedByDescending { it.createdAt }

    @Synchronized
    fun markSynced(ownerId: String, eventId: String, meetingId: String) {
        val all = records()
        val index = all.indexOfFirst { it.ownerId == ownerId && it.eventId == eventId }
        if (index < 0) return
        val updated = all[index].copy(meetingId = meetingId)
        write(updated)
        all[index] = updated
    }

    @Synchronized
    fun pendingSync(ownerId: String): List<LocalMeetingRecord> =
        list(ownerId).filter { it.meetingId == null }

    @Synchronized
    fun find(ownerId: String, eventId: String): LocalMeetingRecord? =
        records().firstOrNull { it.ownerId == ownerId && it.eventId == eventId }

    /** Renames a stored transcript. Returns false when it does not exist or [title] is blank. */
    @Synchronized
    fun rename(ownerId: String, eventId: String, title: String): Boolean {
        val clean = title.trim()
        if (clean.isEmpty()) return false
        return replace(ownerId, eventId) { it.copy(title = clean) }
    }

    /**
     * Corrects the text of a transcript that has not synced yet, so the corrected text is what gets
     * uploaded. Once synced the server copy cannot be updated from here, so edits are refused.
     */
    @Synchronized
    fun updateTranscript(ownerId: String, eventId: String, transcript: String): Boolean {
        if (transcript.isBlank()) return false
        return replace(ownerId, eventId) { if (it.meetingId == null) it.copy(transcript = transcript) else null }
    }

    /** Deletes one stored transcript. Returns false when it does not exist. */
    @Synchronized
    fun delete(ownerId: String, eventId: String): Boolean {
        val all = records()
        val index = all.indexOfFirst { it.ownerId == ownerId && it.eventId == eventId }
        if (index < 0) return false
        File(directory, eventId + ".json").delete()
        all.removeAt(index)
        return true
    }

    /** Deletes the on-device copies of a synced meeting; returns them so callers can clean up linked audio. */
    @Synchronized
    fun deleteByMeetingId(ownerId: String, meetingId: String): List<LocalMeetingRecord> {
        val matches = records().filter { it.ownerId == ownerId && it.meetingId == meetingId }
        matches.forEach { delete(ownerId, it.eventId) }
        return matches
    }

    /** Applies [change] to one record and persists it; [change] returns null to refuse. */
    private fun replace(ownerId: String, eventId: String, change: (LocalMeetingRecord) -> LocalMeetingRecord?): Boolean {
        val all = records()
        val index = all.indexOfFirst { it.ownerId == ownerId && it.eventId == eventId }
        if (index < 0) return false
        val updated = change(all[index]) ?: return false
        write(updated)
        all[index] = updated
        return true
    }

    private fun records(): MutableList<LocalMeetingRecord> {
        cache?.let { return it }
        val loaded = directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                try {
                    parse(JSONObject(file.readText()))
                } catch (_: Exception) {
                    file.renameTo(File(file.parentFile, file.name + ".corrupt"))
                    null
                }
            }
            .toMutableList()
        cache = loaded
        return loaded
    }

    private fun parse(json: JSONObject) = LocalMeetingRecord(
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
        writeTextAtomically(File(directory, record.eventId + ".json"), json.toString())
    }
}
