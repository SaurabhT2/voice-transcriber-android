package com.saurabhtiwari.voicetranscriber

import java.io.File

/**
 * Immutable snapshot of the recorder, published by [RecordingController].
 * Elapsed time is derived from a monotonic clock reading so it keeps counting
 * correctly while the UI is in the background or recreated.
 */
data class RecordingState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val accumulatedMs: Long = 0L,
    val resumedAtMs: Long = 0L,
    val file: File? = null,
    val completedFile: File? = null,
    val error: String = ""
) {
    fun elapsedAt(nowMs: Long): Long =
        if (active && !paused) accumulatedMs + (nowMs - resumedAtMs).coerceAtLeast(0L) else accumulatedMs
}
