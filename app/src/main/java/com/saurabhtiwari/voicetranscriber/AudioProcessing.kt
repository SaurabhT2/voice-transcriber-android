package com.saurabhtiwari.voicetranscriber

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal const val WHISPER_SAMPLE_RATE = 16_000

/**
 * Streaming band-limited (windowed-sinc) resampler for mono float PCM.
 *
 * Compared with plain linear interpolation this low-passes below the output Nyquist
 * frequency, so content above 8 kHz in 44.1/48 kHz recordings is removed instead of
 * aliasing back into the speech band. Output is preallocated primitive arrays (no boxing).
 * Splitting the input into arbitrary buffers yields the same output as one big buffer.
 */
internal class StreamingResampler(
    private val inputRate: Int,
    private val outputRate: Int = WHISPER_SAMPLE_RATE
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "Sample rates must be positive." }
    }

    private val passthrough = inputRate == outputRate
    private val step = inputRate.toDouble() / outputRate
    private val cutoff = min(1.0, CUTOFF_GUARD * outputRate / inputRate)
    private val reach = ceil(ZERO_CROSSINGS / cutoff).toInt()
    private val kernel = buildKernel()

    private var history = FloatArray(0)
    private var historyStart = 0L
    private var nextPosition = 0.0
    private var finished = false

    fun process(input: FloatArray): FloatArray {
        if (passthrough || input.isEmpty()) return input
        val buffer = history + input
        val bufferEnd = historyStart + buffer.size
        val capacity = max(0, ((bufferEnd - nextPosition) / step).toInt() + 2)
        val output = FloatArray(capacity)
        var count = 0
        while (nextPosition + reach < bufferEnd) {
            output[count++] = interpolate(buffer, nextPosition)
            nextPosition += step
        }
        val keepFrom = max(historyStart, floor(nextPosition).toLong() - reach)
        history = buffer.copyOfRange((keepFrom - historyStart).toInt(), buffer.size)
        historyStart = keepFrom
        return if (count == output.size) output else output.copyOf(count)
    }

    /** Emits the tail that was waiting for look-ahead samples. Call once at end of stream. */
    fun finish(): FloatArray {
        if (passthrough || finished) return FloatArray(0)
        finished = true
        return process(FloatArray(reach + 1))
    }

    private fun interpolate(buffer: FloatArray, position: Double): Float {
        var i = max(ceil(position - reach).toLong(), historyStart)
        val last = min(floor(position + reach).toLong(), historyStart + buffer.size - 1)
        var acc = 0.0
        while (i <= last) {
            val tableIndex = ((position - i + reach) * TABLE_RESOLUTION + 0.5).toInt()
            acc += buffer[(i - historyStart).toInt()] * kernel[tableIndex]
            i++
        }
        return acc.toFloat()
    }

    private fun buildKernel(): FloatArray {
        val size = 2 * reach * TABLE_RESOLUTION + 1
        val table = DoubleArray(size)
        for (k in 0 until size) {
            val t = k.toDouble() / TABLE_RESOLUTION - reach
            val x = cutoff * t
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val window = 0.5 * (1.0 + cos(PI * t / reach))
            table[k] = cutoff * sinc * window
        }
        // Normalise so a constant (DC) signal keeps its amplitude.
        var sum = 0.0
        for (j in -reach..reach) sum += table[(j + reach) * TABLE_RESOLUTION]
        return FloatArray(size) { (table[it] / sum).toFloat() }
    }

    private companion object {
        const val ZERO_CROSSINGS = 8.0
        const val CUTOFF_GUARD = 0.95
        const val TABLE_RESOLUTION = 256
    }
}

/**
 * Splits a continuous sample stream into chunks of at most [maxSamples], preferring to cut in
 * the quietest 100 ms of the last [searchSamples] so words are not sliced in half.
 * Every input sample ends up in exactly one chunk (no loss, no duplication).
 */
internal class SilenceAwareChunker(
    private val maxSamples: Int = WHISPER_SAMPLE_RATE * 30,
    private val searchSamples: Int = WHISPER_SAMPLE_RATE * 4,
    private val frameSamples: Int = WHISPER_SAMPLE_RATE / 10
) {
    init {
        require(searchSamples in frameSamples..maxSamples) { "Search window must fit inside a chunk." }
    }

    private val buffer = FloatArray(maxSamples)
    private var size = 0

    fun append(samples: FloatArray): List<FloatArray> {
        val chunks = ArrayList<FloatArray>()
        var offset = 0
        while (offset < samples.size) {
            val count = min(maxSamples - size, samples.size - offset)
            samples.copyInto(buffer, size, offset, offset + count)
            size += count
            offset += count
            if (size == maxSamples) chunks.add(emitChunk())
        }
        return chunks
    }

    /** Remaining samples, or null when nothing is buffered. */
    fun finish(): FloatArray? {
        if (size == 0) return null
        val rest = buffer.copyOf(size)
        size = 0
        return rest
    }

    private fun emitChunk(): FloatArray {
        val cut = quietestCut()
        val chunk = buffer.copyOf(cut)
        buffer.copyInto(buffer, 0, cut, size)
        size -= cut
        return chunk
    }

    private fun quietestCut(): Int {
        val hop = max(1, frameSamples / 2)
        var bestStart = size - frameSamples
        var bestEnergy = Double.MAX_VALUE
        var start = size - searchSamples
        while (start + frameSamples <= size) {
            var energy = 0.0
            for (i in start until start + frameSamples) energy += buffer[i].toDouble() * buffer[i]
            if (energy <= bestEnergy) { // ties favour the later cut, giving longer chunks
                bestEnergy = energy
                bestStart = start
            }
            start += hop
        }
        return bestStart + frameSamples / 2
    }
}

internal object AudioStats {
    const val SILENCE_RMS = 0.002f

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    /** Near-digital silence makes Whisper hallucinate text, so such chunks are skipped. */
    fun isSilent(samples: FloatArray): Boolean = rms(samples) < SILENCE_RMS
}
