package com.saurabhtiwari.voicetranscriber

/** Pure helpers for transcript-driven playback. */
object Playback {
    /** Start a little before the tapped line so its first words are not clipped. */
    const val LEAD_IN_MS = 1_500

    /** Where to seek for a line that starts at [startSeconds]; clamped to the audio length when it is known. */
    fun seekTargetMs(startSeconds: Double, durationMs: Int, leadInMs: Int = LEAD_IN_MS): Int {
        val raw = if (startSeconds.isNaN()) 0L else (startSeconds * 1000.0).toLong() - leadInMs
        val upper = if (durationMs > 0) durationMs.toLong() else Int.MAX_VALUE.toLong()
        return raw.coerceIn(0L, upper).toInt()
    }

    /**
     * Index of the line being spoken at [positionMs]: the last line starting at or before it, or -1
     * before the first line. [startsSeconds] must be in ascending order.
     */
    fun activeIndex(startsSeconds: List<Double>, positionMs: Int): Int {
        val positionSeconds = positionMs / 1000.0
        var low = 0
        var high = startsSeconds.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (startsSeconds[mid] <= positionSeconds) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
