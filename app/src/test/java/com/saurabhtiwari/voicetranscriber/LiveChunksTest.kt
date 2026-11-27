package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChunksTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun headersMatchFfmpegForAac16kMono() {
        // Reference headers produced by FFmpeg's ADTS muxer for 16 kHz mono AAC-LC;
        // the frame length in each header covers the 7-byte header plus the payload.
        val reference = mapOf(
            443 to "fff16040377ffc",
            409 to "fff16040333ffc",
            460 to "fff16040399ffc",
            709 to "fff1604058bffc",
            12 to "fff16040019ffc"
        )
        for ((frameLength, expected) in reference) {
            assertEquals("frame length $frameLength", expected, hex(Adts.header(frameLength - Adts.HEADER_SIZE, 16000, 1)))
        }
    }

    @Test
    fun headerEncodesRateAndChannels() {
        val h = Adts.header(100, 44100, 2)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals(0xF1, h[1].toInt() and 0xFF)
        assertEquals(4, (h[2].toInt() shr 2) and 0xF) // 44.1 kHz
        assertEquals(2, ((h[2].toInt() and 1) shl 2) or ((h[3].toInt() and 0xFF) shr 6)) // stereo
    }

    @Test
    fun headerRejectsImpossibleInput() {
        val bad = listOf({ Adts.header(10, 12345, 1) }, { Adts.header(10, 16000, 0) }, { Adts.header(9000, 16000, 1) })
        for (call in bad) {
            var failed = false
            try { call() } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
        }
    }

    private fun frame(size: Int = 100) = ByteArray(size) { 7 }

    @Test
    fun cutsAChunkEveryThirtySecondsOfAudio() {
        val cutter = ChunkCutter()
        val chunks = ArrayList<LiveChunk>()
        // 30 s at 16 kHz = 480000 samples = 468.75 frames, so the 469th frame completes a chunk
        repeat(468) { assertNull(cutter.addFrame(frame())) }
        cutter.addFrame(frame())?.let { chunks.add(it) }
        assertEquals(1, chunks.size)
        val first = chunks[0]
        assertEquals(0, first.index)
        assertEquals(0.0, first.startSeconds, 0.0)
        assertEquals(469 * 1024 / 16000.0, first.durationSeconds, 1e-9)
        assertEquals(469 * (100 + 7), first.data.size)
        assertEquals(0xFF, first.data[0].toInt() and 0xFF) // starts with an ADTS sync word
    }

    @Test
    fun chunkStartsAreTheSumOfEarlierDurations() {
        val cutter = ChunkCutter()
        val chunks = ArrayList<LiveChunk>()
        repeat(469 * 3) { cutter.addFrame(frame())?.let { chunks.add(it) } }
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertEquals(chunks[0].durationSeconds, chunks[1].startSeconds, 1e-9)
        assertEquals(chunks[0].durationSeconds + chunks[1].durationSeconds, chunks[2].startSeconds, 1e-9)
    }

    @Test
    fun everyFrameLandsInExactlyOneChunk() {
        val cutter = ChunkCutter(chunkSeconds = 2, minTailSeconds = 0.0) // keep even a tiny tail
        var bytes = 0
        val total = 100
        repeat(total) { i ->
            val f = ByteArray(50 + i)
            cutter.addFrame(f)?.let { bytes += it.data.size }
        }
        cutter.finish()?.let { bytes += it.data.size }
        val expected = (0 until total).sumOf { 50 + it + Adts.HEADER_SIZE }
        assertEquals(expected, bytes)
    }

    @Test
    fun finishKeepsAUsableTailAndDropsAScrap() {
        val tail = ChunkCutter(chunkSeconds = 30)
        repeat(40) { tail.addFrame(frame()) } // 40 * 64 ms = 2.56 s
        val last = tail.finish()!!
        assertEquals(40 * 1024 / 16000.0, last.durationSeconds, 1e-9)

        val scrap = ChunkCutter(chunkSeconds = 30)
        repeat(5) { scrap.addFrame(frame()) } // 0.32 s
        assertNull(scrap.finish())
        assertNull(ChunkCutter().finish())
    }
}
