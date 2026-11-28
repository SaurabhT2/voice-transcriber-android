package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchTest {
    private fun item(id: String, title: String, meetingId: String? = null, local: Boolean = false) = RecordingItem(
        id = id, title = title, status = "succeeded", source = "web", createdAt = "2026-11-01T10:00:00Z",
        meetingId = meetingId, transcriptId = null, isLocalOnly = local
    )

    private val weekly = item("j1", "Weekly sync", meetingId = "m1")
    private val budget = item("j2", "Budget review", meetingId = "m2")
    private val local = item("e1", "meeting_1700000.m4a", local = true)
    private val all = listOf(weekly, budget, local)

    @Test
    fun blankQueryKeepsEverything() {
        assertEquals(all, HistorySearch.filter(all, "   ", emptyMap(), emptySet()))
    }

    @Test
    fun matchesTitleIgnoringCase() {
        assertEquals(listOf(budget), HistorySearch.filter(all, "BUDGET", emptyMap(), emptySet()))
    }

    @Test
    fun matchesDeviceTranscriptByEventIdAndByMeetingId() {
        val text = mapOf("e1" to "we agreed to hire two engineers", "m1" to "the roadmap slipped a week")
        assertEquals(listOf(local), HistorySearch.filter(all, "engineers", text, emptySet()))
        assertEquals(listOf(weekly), HistorySearch.filter(all, "roadmap", text, emptySet()))
    }

    @Test
    fun matchesServerReportedMeetings() {
        assertEquals(listOf(budget), HistorySearch.filter(all, "anything", emptyMap(), setOf("m2")))
    }

    @Test
    fun nothingMatchesWhenNothingContainsTheQuery() {
        assertTrue(HistorySearch.filter(all, "zebra", mapOf("m1" to "hello"), setOf("m9")).isEmpty())
        assertFalse(HistorySearch.matches(local, "zebra", emptyMap(), emptySet()))
    }

    @Test
    fun snippetShowsContextAroundTheFirstHit() {
        val text = "Intro text.\n\nThe launch date is   Friday and everyone agreed on it. " + "More words ".repeat(20)
        val snippet = HistorySearch.snippet(item("e1", "x"), "launch date", mapOf("e1" to text), radius = 12)!!
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("launch date is Friday"))
        assertFalse(snippet.contains("\n"))
    }

    @Test
    fun snippetIsNullWhenTheHitIsOnlyInTheTitleOrThereIsNoText() {
        assertNull(HistorySearch.snippet(weekly, "weekly", mapOf("m1" to "nothing relevant"), 10))
        assertNull(HistorySearch.snippet(weekly, "weekly", emptyMap(), 10))
        assertNull(HistorySearch.snippet(weekly, "  ", mapOf("m1" to "text"), 10))
    }

    @Test
    fun ilikeEscapingNeutralisesWildcards() {
        assertEquals("100\\% sure\\_ok", HistorySearch.escapeForIlike("  100% sure_ok "))
        assertEquals("ab", HistorySearch.escapeForIlike("a*b"))
        assertEquals("a\\\\b", HistorySearch.escapeForIlike("a\\b"))
    }
}
