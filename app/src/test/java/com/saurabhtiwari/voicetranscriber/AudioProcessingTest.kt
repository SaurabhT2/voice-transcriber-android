package com.saurabhtiwari.voicetranscriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioProcessingTest {
    private fun tone(freq: Double, rate: Int, seconds: Double): FloatArray =
        FloatArray((rate * seconds).toInt()) { sin(2 * PI * freq * it / rate).toFloat() }

    private fun rmsOfMiddle(samples: FloatArray): Double {
        val from = samples.size / 4
        val to = samples.size * 3 / 4
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }

    private fun resampleAll(input: FloatArray, rate: Int, split: Int = input.size): FloatArray {
        val r = StreamingResampler(rate)
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val end = minOf(input.size, i + split)
            r.process(input.copyOfRange(i, end)).forEach { out.add(it) }
            i = end
        }
        r.finish().forEach { out.add(it) }
        return out.toFloatArray()
    }

    @Test
    fun passthroughWhenRatesMatch() {
        val input = tone(440.0, 16_000, 0.5)
        val r = StreamingResampler(16_000)
        assertTrue(r.process(input) === input)
        assertEquals(0, r.finish().size)
    }

    @Test
    fun downsampledLengthMatchesRatio() {
        val out = resampleAll(tone(440.0, 48_000, 2.0), 48_000)
        assertTrue("length ${out.size}", abs(out.size - 32_000) <= 4)
        val out441 = resampleAll(tone(440.0, 44_100, 2.0), 44_100)
        assertTrue("length ${out441.size}", abs(out441.size - 32_000) <= 4)
    }

    @Test
    fun speechBandToneKeepsItsAmplitude() {
        val out = resampleAll(tone(1_000.0, 48_000, 1.0), 48_000)
        val expected = 1.0 / sqrt(2.0)
        assertEquals(expected, rmsOfMiddle(out), 0.03)
    }

    @Test
    fun toneAboveOutputNyquistIsRejectedNotAliased() {
        // 12 kHz would alias to 4 kHz with naive interpolation.
        val out = resampleAll(tone(12_000.0, 48_000, 1.0), 48_000)
        assertTrue("residual ${rmsOfMiddle(out)}", rmsOfMiddle(out) < 0.02)
    }

    @Test
    fun constantSignalKeepsItsLevel() {
        val out = resampleAll(FloatArray(48_000) { 0.5f }, 48_000)
        for (i in out.size / 4 until out.size * 3 / 4) assertEquals(0.5, out[i].toDouble(), 0.01)
    }

    @Test
    fun streamingMatchesSingleBufferRegardlessOfSplit() {
        val input = tone(700.0, 44_100, 1.5)
        val whole = resampleAll(input, 44_100)
        for (split in listOf(1, 7, 1000, 4410, 20_000)) {
            val parts = resampleAll(input, 44_100, split)
            assertEquals("split $split", whole.size, parts.size)
            for (i in whole.indices) assertEquals(whole[i].toDouble(), parts[i].toDouble(), 1e-6)
        }
    }

    @Test
    fun upsamplingWorks() {
        val out = resampleAll(tone(500.0, 8_000, 1.0), 8_000)
        assertTrue(abs(out.size - 16_000) <= 4)
        assertEquals(1.0 / sqrt(2.0), rmsOfMiddle(out), 0.03)
    }

    @Test
    fun chunkerNeverLosesOrDuplicatesSamples() {
        val chunker = SilenceAwareChunker(maxSamples = 1_000, searchSamples = 300, frameSamples = 50)
        val input = FloatArray(4_321) { it.toFloat() }
        val collected = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val end = minOf(input.size, i + 137)
            chunker.append(input.copyOfRange(i, end)).forEach { c ->
                assertTrue(c.size in 1..1_000)
                c.forEach { collected.add(it) }
            }
            i = end
        }
        chunker.finish()?.forEach { collected.add(it) }
        assertEquals(input.size, collected.size)
        for (k in input.indices) assertEquals(input[k], collected[k], 0f)
    }

    @Test
    fun chunkerCutsInsideTheQuietGap() {
        val chunker = SilenceAwareChunker(maxSamples = 1_000, searchSamples = 400, frameSamples = 50)
        // loud everywhere except a silent gap at 700..800
        val input = FloatArray(1_000) { if (it in 700 until 800) 0f else 0.5f }
        val chunks = chunker.append(input)
        assertEquals(1, chunks.size)
        assertTrue("cut at ${chunks[0].size}", chunks[0].size in 700..800)
        assertEquals(1_000 - chunks[0].size, chunker.finish()!!.size)
    }

    @Test
    fun silenceDetection() {
        assertTrue(AudioStats.isSilent(FloatArray(16_000)))
        assertTrue(AudioStats.isSilent(FloatArray(16_000) { 0.0005f }))
        assertTrue(!AudioStats.isSilent(FloatArray(16_000) { if (it % 2 == 0) 0.05f else -0.05f }))
        assertEquals(0f, AudioStats.rms(FloatArray(0)), 0f)
    }
}
