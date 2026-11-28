package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExportTest {
    private fun seg(start: Double, end: Double, text: String, speaker: String?) = JSONObject()
        .put("start", start).put("end", end).put("text", text).put("speaker_id", speaker ?: JSONObject.NULL)

    private val speakers = JSONArray()
        .put(JSONObject().put("speaker_id", "speaker_1").put("label", "Speaker 1").put("speaking_seconds", 10))
        .put(JSONObject().put("speaker_id", "speaker_2").put("label", "Speaker 2").put("speaking_seconds", 5))

    private val segments = JSONArray()
        .put(seg(0.0, 5.0, "Let's ship on Friday.", "speaker_1"))
        .put(seg(5.0, 9.0, "Agreed.", "speaker_2"))
        .put(seg(9.0, 12.0, "I will send the deck.", "speaker_1"))

    private val notes = JSONObject()
        .put("executive_summary", "The team agreed to ship on Friday.")
        .put("key_points", JSONArray().put(JSONObject().put("text", "Ship Friday").put("start_seconds", 65.0)))
        .put("decisions", JSONArray().put(JSONObject().put("text", "Ship on Friday").put("rationale", "Customer deadline")))
        .put(
            "action_items",
            JSONArray()
                .put(JSONObject().put("text", "Send the deck").put("owner", "Asha").put("due_date", "2026-12-01").put("start_seconds", 9.0))
                .put(JSONObject().put("task", "Book the room").put("owner", JSONObject.NULL))
        )
        .put("open_questions", JSONArray().put(JSONObject().put("text", "Who owns QA?")))
        .put("participants", JSONArray().put("Asha").put("Ben"))

    private fun meeting(segs: JSONArray = segments, spk: JSONArray = speakers, transcript: String = "plain text") = MeetingDetail(
        meetingId = "m1", title = "Weekly sync", source = "android_upload", transcript = transcript,
        language = "en", model = "large-v3", segments = segs, speakers = spk, createdAt = "2026-11-28T09:30:00Z"
    )

    @Test
    fun markdownContainsNotesAndSpeakerTranscript() {
        val md = TranscriptExport.markdown(TranscriptExport.documentFor(meeting(), mapOf("speaker_1" to "Asha"), notes))
        assertTrue(md.startsWith("# Weekly sync\n"))
        assertTrue(md.contains("*2026-11-28 · android_upload · en · large-v3*"))
        assertTrue(md.contains("## Summary\n\nThe team agreed to ship on Friday."))
        assertTrue(md.contains("- [1:05] Ship Friday"))
        assertTrue(md.contains("- Ship on Friday — Customer deadline"))
        assertTrue(md.contains("- [0:09] [ ] Send the deck (owner: Asha, due: 2026-12-01)"))
        assertTrue(md.contains("- [ ] Book the room"))
        assertTrue(md.contains("- Who owns QA?"))
        assertTrue(md.contains("## Participants\n\n- Asha\n- Ben"))
        // alias applied to the transcript; the unaliased speaker keeps its server label
        assertTrue(md.contains("**Asha** · 0:00\nLet's ship on Friday."))
        assertTrue(md.contains("**Speaker 2** · 0:05\nAgreed."))
        assertTrue(md.endsWith("I will send the deck.\n"))
    }

    @Test
    fun plainTextHasNoMarkdownSyntax() {
        val text = TranscriptExport.plainText(TranscriptExport.documentFor(meeting(), emptyMap(), notes))
        assertFalse(text.contains("##"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("[ ]"))
        assertTrue(text.startsWith("Weekly sync\n===========\n"))
        assertTrue(text.contains("SUMMARY\n-------\n"))
        assertTrue(text.contains("• [0:09] Send the deck (owner: Asha, due: 2026-12-01)"))
        assertTrue(text.contains("Speaker 1 [0:00]\nLet's ship on Friday."))
    }

    @Test
    fun segmentsWithoutSpeakersBecomeATimestampedTimeline() {
        val noSpeakers = JSONArray().put(seg(0.0, 4.0, "First.", null)).put(seg(4.0, 8.0, "Second.", null))
        val md = TranscriptExport.markdown(TranscriptExport.documentFor(meeting(noSpeakers, JSONArray()), emptyMap(), null))
        assertTrue(md.contains("[0:00]\nFirst.\n\n[0:04]\nSecond."))
        assertFalse(md.contains("## Summary"))
    }

    @Test
    fun fallsBackToPlainTextWhenThereAreNoSegments() {
        val md = TranscriptExport.markdown(TranscriptExport.documentFor(meeting(JSONArray(), JSONArray(), "Just text."), emptyMap(), null))
        assertTrue(md.contains("## Transcript\n\nJust text.\n"))
        val empty = TranscriptExport.markdown(TranscriptExport.documentFor(meeting(JSONArray(), JSONArray(), "  "), emptyMap(), null))
        assertTrue(empty.contains("Transcript unavailable."))
    }

    @Test
    fun localRecordExportsItsText() {
        val record = LocalMeetingRecord("e1", "u1", "Voice note", "Remember the milk.", "2026-11-27T08:00:00Z", "", "", "en", null)
        val md = TranscriptExport.markdown(TranscriptExport.documentFor(record))
        assertTrue(md.startsWith("# Voice note\n\n*2026-11-27 · en · on-device transcript*"))
        assertTrue(md.endsWith("Remember the milk.\n"))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Weekly sync.md", TranscriptExport.fileName("Weekly sync", "md"))
        assertEquals("Q4_Plan_ v2.txt", TranscriptExport.fileName("Q4/Plan: v2?", "txt"))
        assertEquals("Réunion équipe.md", TranscriptExport.fileName("Réunion équipe", "md"))
        assertEquals("meeting.md", TranscriptExport.fileName("???", "md"))
        assertEquals("meeting.md", TranscriptExport.fileName("   ", "md"))
        assertTrue(TranscriptExport.fileName("a".repeat(200), "md").length <= 83)
        assertFalse(TranscriptExport.fileName("../../etc/passwd", "md").contains("/"))
    }

    @Test
    fun jsonKeepsTheEarlierKeysAndAddsSpeakerTurns() {
        val doc = TranscriptExport.documentFor(meeting(), mapOf("speaker_1" to "Asha"), notes)
        val parsed = JSONObject(TranscriptExport.json(doc))
        // keys of the earlier plain JSON export
        assertEquals("m1", parsed.getString("meeting_id"))
        assertEquals("Weekly sync", parsed.getString("title"))
        assertEquals("plain text", parsed.getString("transcript"))
        assertEquals("2026-11-28T09:30:00Z", parsed.getString("created_at"))
        assertEquals("The team agreed to ship on Friday.", parsed.getJSONObject("notes").getString("executive_summary"))
        // additions
        assertEquals(1, parsed.getInt("schema_version"))
        val turns = parsed.getJSONArray("turns")
        assertEquals(3, turns.length())
        assertEquals("Asha", turns.getJSONObject(0).getString("speaker"))
        assertEquals("speaker_1", turns.getJSONObject(0).getString("speaker_id"))
        assertEquals(5.0, turns.getJSONObject(1).getDouble("start_seconds"), 0.0001)
        assertEquals("Speaker 2", turns.getJSONObject(1).getString("speaker"))
        assertEquals(listOf("Asha", "Speaker 2"), (0 until 2).map { parsed.getJSONArray("speakers").getJSONObject(it).getString("label") })
    }

    @Test
    fun jsonWithoutNotesOrSegmentsIsStillValid() {
        val parsed = JSONObject(TranscriptExport.json(TranscriptExport.documentFor(meeting(JSONArray(), JSONArray(), "Just text."), emptyMap(), null)))
        assertTrue(parsed.isNull("notes"))
        assertEquals(0, parsed.getJSONArray("turns").length())
        assertEquals("Just text.", parsed.getString("transcript"))
        val local = JSONObject(TranscriptExport.json(TranscriptExport.documentFor(
            LocalMeetingRecord("e1", "u1", "Voice note", "Remember the milk.", "2026-11-27T08:00:00Z", "", "", "en", null)
        )))
        assertEquals("e1", local.getString("meeting_id"))
    }

    @Test
    fun risksAndNextStepsAreExportedEvenWithLegacyKeyAndPlainStringItems() {
        val extra = JSONObject()
            .put("executive_summary", "S")
            .put("risks", JSONArray().put("Vendor may slip").put("  ").put(JSONObject.NULL))        // legacy key, strings, junk
            .put("next_steps", JSONArray().put(JSONObject().put("text", "Book review").put("start_seconds", 30.0)))
        val md = TranscriptExport.markdown(TranscriptExport.documentFor(meeting(), emptyMap(), extra))
        assertTrue(md.contains("## Risks and blockers\n\n- Vendor may slip\n\n"))
        assertTrue(md.contains("## Next steps\n\n- [0:30] Book review"))
        val modern = JSONObject().put("risks_and_blockers", JSONArray().put(JSONObject().put("text", "New key")))
            .put("risks", JSONArray().put("Old key"))
        assertTrue(TranscriptExport.markdown(TranscriptExport.documentFor(meeting(), emptyMap(), modern)).contains("- New key"))
    }

    @Test
    fun noteItemParsingToleratesObjectsStringsAndGarbage() {
        val values = JSONArray().put(JSONObject().put("task", "Do it")).put("plain").put("  ").put(JSONObject.NULL).put(5)
        assertEquals("Do it", NoteItems.text(NoteItems.at(values, 0)!!))
        assertEquals("plain", NoteItems.text(NoteItems.at(values, 1)!!))
        assertTrue(NoteItems.at(values, 2) == null)
        assertTrue(NoteItems.at(values, 3) == null)
        assertEquals("5", NoteItems.text(NoteItems.at(values, 4)!!))
        assertTrue(NoteItems.startSeconds(JSONObject().put("start_seconds", JSONObject.NULL)) == null)
        assertEquals(12.5, NoteItems.startSeconds(JSONObject().put("start_seconds", 12.5))!!, 0.0)
    }
}
