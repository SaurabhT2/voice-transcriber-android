package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray
import java.util.Locale

/** One uninterrupted stretch of speech by the same speaker (or by nobody known). */
data class TranscriptTurn(
    val speakerId: String?,
    val label: String?,
    /** Index into the colour palette, in order of first appearance; -1 for unknown speakers. */
    val colorIndex: Int,
    val startSeconds: Double,
    val text: String
)

data class SpeakerStat(
    val speakerId: String,
    val label: String,
    val colorIndex: Int,
    val seconds: Double,
    /** 0..1 share of all attributed speaking time. */
    val share: Double
)

data class SpeakerTranscript(val turns: List<TranscriptTurn>, val speakers: List<SpeakerStat>) {
    /** False when the server returned no speaker information, so callers show the plain transcript. */
    val hasSpeakers: Boolean get() = speakers.isNotEmpty()
}

/**
 * Turns the server's `segments` (`start`, `end`, `text`, `speaker_id`) and `speakers`
 * (`speaker_id`, `label`, `speaking_seconds`) into speaker turns for display.
 * Tolerant by design: missing fields, blank text and unknown speakers never throw.
 */
object SpeakerTranscripts {
    fun build(segments: JSONArray, speakers: JSONArray): SpeakerTranscript {
        val labels = LinkedHashMap<String, String>()
        val reportedSeconds = HashMap<String, Double>()
        for (i in 0 until speakers.length()) {
            val row = speakers.optJSONObject(i) ?: continue
            val id = row.optNullableString("speaker_id") ?: continue
            labels[id] = row.optNullableString("label") ?: defaultLabel(id)
            reportedSeconds[id] = row.optDouble("speaking_seconds", 0.0).coerceAtLeast(0.0)
        }

        val pieces = readPieces(segments)

        // Colours follow first appearance in the conversation, not the order of the speakers array.
        val colorOf = LinkedHashMap<String, Int>()
        for (piece in pieces) {
            val id = piece.speakerId ?: continue
            colorOf.getOrPut(id) { colorOf.size }
            labels.getOrPut(id) { defaultLabel(id) }
        }
        for (id in labels.keys) colorOf.getOrPut(id) { colorOf.size }

        val turns = ArrayList<TranscriptTurn>()
        for (piece in pieces) {
            val last = turns.lastOrNull()
            if (last != null && last.speakerId == piece.speakerId) {
                turns[turns.lastIndex] = last.copy(text = last.text + " " + piece.text)
            } else {
                turns.add(
                    TranscriptTurn(
                        speakerId = piece.speakerId,
                        label = piece.speakerId?.let { labels[it] },
                        colorIndex = piece.speakerId?.let { colorOf[it] } ?: -1,
                        startSeconds = piece.start,
                        text = piece.text
                    )
                )
            }
        }

        // Prefer the server's totals; otherwise add up the segment durations.
        val computed = HashMap<String, Double>()
        for (piece in pieces) {
            val id = piece.speakerId ?: continue
            computed[id] = (computed[id] ?: 0.0) + (piece.end - piece.start)
        }
        val ids = colorOf.keys.sortedBy { colorOf[it] }
        val seconds = ids.associateWith { id -> reportedSeconds[id]?.takeIf { it > 0.0 } ?: computed[id] ?: 0.0 }
        val total = seconds.values.sum()
        val stats = ids.map { id ->
            SpeakerStat(
                speakerId = id,
                label = labels[id] ?: defaultLabel(id),
                colorIndex = colorOf.getValue(id),
                seconds = seconds.getValue(id),
                share = if (total > 0.0) seconds.getValue(id) / total else 0.0
            )
        }
        return SpeakerTranscript(turns, stats)
    }

    private class Piece(val speakerId: String?, val start: Double, val end: Double, val text: String)

    /** Tolerant segment parsing: blank text is dropped, bad or negative times are clamped. */
    private fun readPieces(segments: JSONArray): List<Piece> {
        val pieces = ArrayList<Piece>()
        for (i in 0 until segments.length()) {
            val row = segments.optJSONObject(i) ?: continue
            val text = row.optString("text").trim()
            if (text.isEmpty()) continue
            val start = row.optDouble("start", 0.0).let { if (it.isNaN()) 0.0 else it.coerceAtLeast(0.0) }
            val end = row.optDouble("end", start).let { if (it.isNaN()) start else it.coerceAtLeast(start) }
            pieces.add(Piece(row.optNullableString("speaker_id"), start, end, text))
        }
        return pieces
    }

    /**
     * One timestamped line per segment, never merged. Shown when the transcript has no speaker
     * labels, so every line can still be tapped to jump to that moment in the audio.
     */
    fun segmentLines(segments: JSONArray): List<TranscriptTurn> =
        readPieces(segments).map { TranscriptTurn(null, null, -1, it.start, it.text) }

    /** `speaker_3` -> "Speaker 3"; anything else is shown as it came. */
    fun defaultLabel(speakerId: String): String {
        val number = Regex("^speaker[_ -]?(\\d+)$", RegexOption.IGNORE_CASE).find(speakerId)?.groupValues?.get(1)
        return if (number != null) "Speaker $number" else speakerId
    }

    /** 75.4 -> "1:15", 3725 -> "1:02:05". */
    fun formatTimestamp(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        else String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
