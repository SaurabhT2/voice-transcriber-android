package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFindTest {
    @Test
    fun findsEveryCaseInsensitiveMatchWithoutOverlap() {
        assertEquals(listOf(0..2, 8..10), TranscriptFind.ranges("Cat and CAT", "cat").map { it.first..it.last })
        assertEquals(listOf(0..1, 2..3), TranscriptFind.ranges("aaaa", "aa").map { it.first..it.last })
    }

    @Test
    fun blankQueriesMatchNothing() {
        assertTrue(TranscriptFind.ranges("text", "").isEmpty())
        assertTrue(TranscriptFind.ranges("text", "   ").isEmpty())
        assertFalse(TranscriptFind.contains("text", " "))
        assertTrue(TranscriptFind.matchingSentences("text", "").isEmpty())
    }

    @Test
    fun queryLongerThanTextIsSafe() {
        assertTrue(TranscriptFind.ranges("hi", "hello world").isEmpty())
        assertTrue(TranscriptFind.ranges("", "x").isEmpty())
    }

    @Test
    fun countsAcrossTurns() {
        val turns = listOf(
            TranscriptTurn("s1", "A", 0, 0.0, "Budget is fine. The budget slipped."),
            TranscriptTurn("s2", "B", 1, 5.0, "Nothing here."),
            TranscriptTurn("s1", "A", 0, 9.0, "BUDGET again")
        )
        assertEquals(3, TranscriptFind.count(turns, "budget"))
        assertEquals(0, TranscriptFind.count(turns, "zebra"))
    }

    @Test
    fun plainTranscriptsKeepOnlyMatchingSentencesAndLines() {
        val text = "We met today. The budget is approved! Any questions?\nBudget owner is Asha.\nLunch was good."
        assertEquals(
            listOf("The budget is approved!", "Budget owner is Asha."),
            TranscriptFind.matchingSentences(text, "budget")
        )
    }
}
