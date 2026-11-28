package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray

/** What the transcript view and the exporters show: timestamped turns plus per-speaker totals. */
data class DisplayTranscript(val turns: List<TranscriptTurn>, val speakers: List<SpeakerStat>) {
    val hasSpeakers: Boolean get() = speakers.isNotEmpty()
    val isEmpty: Boolean get() = turns.isEmpty()
}

object DisplayTranscripts {
    /**
     * Speaker turns (with on-device name overrides applied) when the server labelled speakers;
     * otherwise one timestamped line per segment. Empty when the transcript has no segments.
     */
    fun build(segments: JSONArray, speakers: JSONArray, aliases: Map<String, String>): DisplayTranscript {
        val parsed = SpeakerTranscripts.build(segments, speakers)
        if (!parsed.hasSpeakers) return DisplayTranscript(SpeakerTranscripts.segmentLines(segments), emptyList())
        val renamed = parsed.withAliases(aliases)
        return DisplayTranscript(renamed.turns, renamed.speakers)
    }
}
