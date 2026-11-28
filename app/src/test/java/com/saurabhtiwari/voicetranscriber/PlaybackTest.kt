package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTest {
    @Test
    fun seekStartsSlightlyBeforeTheLine() {
        assertEquals(8_500, Playback.seekTargetMs(10.0, durationMs = 60_000))
        assertEquals(0, Playback.seekTargetMs(0.5, durationMs = 60_000))
        assertEquals(0, Playback.seekTargetMs(-4.0, durationMs = 60_000))
    }

    @Test
    fun seekIsClampedToTheAudioLength() {
        assertEquals(60_000, Playback.seekTargetMs(500.0, durationMs = 60_000))
        assertEquals(498_500, Playback.seekTargetMs(500.0, durationMs = 0)) // duration not known yet
        assertEquals(0, Playback.seekTargetMs(Double.NaN, durationMs = 60_000))
    }

    @Test
    fun activeLineIsTheLastOneThatHasStarted() {
        val starts = listOf(0.0, 5.0, 9.5, 20.0)
        assertEquals(0, Playback.activeIndex(starts, 0))
        assertEquals(0, Playback.activeIndex(starts, 4_999))
        assertEquals(1, Playback.activeIndex(starts, 5_000))
        assertEquals(2, Playback.activeIndex(starts, 19_999))
        assertEquals(3, Playback.activeIndex(starts, 600_000))
    }

    @Test
    fun noActiveLineBeforeTheFirstOrWithoutLines() {
        assertEquals(-1, Playback.activeIndex(listOf(3.0, 6.0), 1_000))
        assertEquals(-1, Playback.activeIndex(emptyList(), 1_000))
    }
}
