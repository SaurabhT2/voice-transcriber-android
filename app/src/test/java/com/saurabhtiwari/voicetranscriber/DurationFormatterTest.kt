package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {
    @Test fun durationFormattingUsesMinutesAndSeconds() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("61:01", formatDuration(3_661_000))
    }
}
