package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Test

class PostgrestUrlTest {
    @Test
    fun encodesKeysAndValuesButKeepsPostgrestOperators() {
        val url = PostgrestUrl.build(
            "https://x.supabase.co/rest/v1/transcripts",
            "select" to "meeting_id",
            "owner_id" to "eq.abc-123",
            "content" to "ilike.*budget plan*"
        )
        assertEquals(
            "https://x.supabase.co/rest/v1/transcripts?select=meeting_id&owner_id=eq.abc-123&content=ilike.*budget%20plan*",
            url
        )
    }

    @Test
    fun userTextCannotInjectExtraParameters() {
        val term = HistorySearch.escapeForIlike("a&owner_id=eq.someone-else")
        val url = PostgrestUrl.build("https://x/rest/v1/transcripts", "content" to "ilike.*$term*")
        assertEquals("https://x/rest/v1/transcripts?content=ilike.*a%26owner%5C_id%3Deq.someone-else*", url)
    }

    @Test
    fun escapedWildcardsAndUnicodeSurviveEncoding() {
        val term = HistorySearch.escapeForIlike("100%_réunion")
        assertEquals(
            "https://x?content=ilike.*100%5C%25%5C_r%C3%A9union*",
            PostgrestUrl.build("https://x", "content" to "ilike.*$term*")
        )
        assertEquals("https://x?a=1", PostgrestUrl.build("https://x", "a" to "1"))
        assertEquals("https://x", PostgrestUrl.build("https://x"))
    }
}
