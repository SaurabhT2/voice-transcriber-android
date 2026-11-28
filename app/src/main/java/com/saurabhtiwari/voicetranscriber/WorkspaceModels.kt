package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray

/** One row of "My recordings": a transcription job, a synced meeting, or a transcript only on this device. */
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
