package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTranscriptTest {
    private fun segs(vararg rows: String) = JSONArray("[" + rows.joinToString(",") + "]")
    private fun seg(start: Double, end: Double, text: String, speaker: String?) =
        """{"start":$start,"end":$end,"text":"$text","speaker_id":${speaker?.let { "\"$it\"" } ?: "null"}}"""

    private val speakers = JSONArray(
        """[{"speaker_id":"speaker_1","label":"Speaker 1","speaking_seconds":30.0},
            {"speaker_id":"speaker_2","label":"Speaker 2","speaking_seconds":10.0}]"""
    )

    @Test
    fun mergesConsecutiveSegmentsOfTheSameSpeaker() {
        val t = SpeakerTranscripts.build(
            segs(
                seg(0.0, 2.0, "Hello everyone.", "speaker_1"),
                seg(2.0, 4.0, "Thanks for joining.", "speaker_1"),
                seg(4.5, 6.0, "Happy to be here.", "speaker_2"),
                seg(6.0, 8.0, "Let's start.", "speaker_1")
            ),
            speakers
        )
        assertEquals(3, t.turns.size)
        assertEquals("Hello everyone. Thanks for joining.", t.turns[0].text)
        assertEquals(0.0, t.turns[0].startSeconds, 0.0)
        assertEquals("Speaker 2", t.turns[1].label)
        assertEquals("speaker_1", t.turns[2].speakerId)
        assertTrue(t.hasSpeakers)
    }

    @Test
    fun withoutSpeakerInfoThereIsNothingToShowAsSpeakers() {
        val t = SpeakerTranscripts.build(segs(seg(0.0, 1.0, "Plain.", null), seg(1.0, 2.0, "Text.", null)), JSONArray())
        assertFalse(t.hasSpeakers)
        assertEquals(1, t.turns.size)
        assertEquals(-1, t.turns[0].colorIndex)
        assertNull(t.turns[0].label)
    }

    @Test
    fun emptyInputsNeverThrow() {
        val t = SpeakerTranscripts.build(JSONArray(), JSONArray())
        assertTrue(t.turns.isEmpty() && t.speakers.isEmpty() && !t.hasSpeakers)
    }

    @Test
    fun toleratesMalformedRows() {
        val t = SpeakerTranscripts.build(
            JSONArray("""[null, 5, {"text":"  "}, {"text":"ok"}, {"start":"x","text":"bad start","speaker_id":"speaker_1"}]"""),
            JSONArray("""["junk", {"label":"no id"}]""")
        )
        assertEquals(listOf("ok", "bad start"), t.turns.map { it.text })
        assertEquals("Speaker 1", t.turns[1].label)
    }

    @Test
    fun colorsFollowFirstAppearanceNotArrayOrder() {
        val reversed = JSONArray(
            """[{"speaker_id":"speaker_2","label":"Speaker 2","speaking_seconds":10},
                {"speaker_id":"speaker_1","label":"Speaker 1","speaking_seconds":30}]"""
        )
        val t = SpeakerTranscripts.build(
            segs(seg(0.0, 1.0, "a", "speaker_1"), seg(1.0, 2.0, "b", "speaker_2")), reversed
        )
        assertEquals(0, t.turns[0].colorIndex)
        assertEquals(1, t.turns[1].colorIndex)
        assertEquals(listOf("speaker_1", "speaker_2"), t.speakers.map { it.speakerId })
    }

    @Test
    fun sharesUseTheServersTotalsAndSumToOne() {
        val t = SpeakerTranscripts.build(
            segs(seg(0.0, 1.0, "a", "speaker_1"), seg(1.0, 2.0, "b", "speaker_2")), speakers
        )
        assertEquals(0.75, t.speakers[0].share, 1e-9)
        assertEquals(0.25, t.speakers[1].share, 1e-9)
        assertEquals(1.0, t.speakers.sumOf { it.share }, 1e-9)
    }

    @Test
    fun sharesFallBackToSegmentDurationsWhenTotalsAreMissing() {
        val t = SpeakerTranscripts.build(
            segs(seg(0.0, 6.0, "a", "speaker_1"), seg(6.0, 8.0, "b", "speaker_2")), JSONArray()
        )
        assertEquals(0.75, t.speakers[0].share, 1e-9)
        assertEquals("Speaker 2", t.speakers[1].label)
    }

    @Test
    fun speakerWithNoSpeechIsStillListed() {
        val t = SpeakerTranscripts.build(segs(seg(0.0, 1.0, "a", "speaker_1")), speakers)
        assertEquals(listOf("speaker_1", "speaker_2"), t.speakers.map { it.speakerId })
        assertEquals(0.25, t.speakers[1].share, 1e-9)
    }

    @Test
    fun labelsAndTimestamps() {
        assertEquals("Speaker 12", SpeakerTranscripts.defaultLabel("speaker_12"))
        assertEquals("Alice", SpeakerTranscripts.defaultLabel("Alice"))
        assertEquals("0:00", SpeakerTranscripts.formatTimestamp(0.0))
        assertEquals("1:15", SpeakerTranscripts.formatTimestamp(75.4))
        assertEquals("1:02:05", SpeakerTranscripts.formatTimestamp(3725.0))
        assertEquals("0:00", SpeakerTranscripts.formatTimestamp(-4.0))
    }
}
