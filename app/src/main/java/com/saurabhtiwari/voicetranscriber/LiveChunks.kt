package com.saurabhtiwari.voicetranscriber

import java.io.ByteArrayOutputStream

/** One self-contained piece of a live recording: a run of ADTS AAC frames. */
class LiveChunk(
    val index: Int,
    val startSeconds: Double,
    val durationSeconds: Double,
    val data: ByteArray
)

/** ADTS framing so raw AAC access units can be stored and decoded as a plain `.aac` file. */
object Adts {
    const val HEADER_SIZE = 7
    const val SAMPLES_PER_FRAME = 1024

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    /** Header for an AAC-LC frame whose payload is [payloadSize] bytes (no CRC, MPEG-4). */
    fun header(payloadSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val rateIndex = SAMPLE_RATES.indexOf(sampleRate)
        require(rateIndex >= 0) { "Unsupported AAC sample rate $sampleRate" }
        require(channels in 1..7) { "Unsupported channel count $channels" }
        val frameLength = payloadSize + HEADER_SIZE
        require(frameLength <= 0x1FFF) { "AAC frame too large for ADTS: $frameLength bytes" }
        val profile = 1 // AAC-LC (audio object type 2) minus one
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            ((profile shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (frameLength shr 11)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte() // buffer fullness (VBR) + one raw data block
        )
    }
}

/**
 * Collects encoded AAC frames and emits a [LiveChunk] every [chunkSeconds] of audio.
 * Time is counted in encoded samples, so paused time never shifts timestamps and every
 * chunk's start equals the sum of the durations before it.
 */
class ChunkCutter(
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    chunkSeconds: Int = 30,
    minTailSeconds: Double = 1.0
) {
    private val chunkSamples = sampleRate.toLong() * chunkSeconds
    private val minTailSamples = (sampleRate * minTailSeconds).toLong()
    private var buffer = ByteArrayOutputStream()
    private var samplesInChunk = 0L
    private var startSamples = 0L
    private var nextIndex = 0

    /** Returns a finished chunk when this frame completes one, otherwise null. */
    fun addFrame(aacFrame: ByteArray): LiveChunk? {
        buffer.write(Adts.header(aacFrame.size, sampleRate, channels))
        buffer.write(aacFrame)
        samplesInChunk += Adts.SAMPLES_PER_FRAME
        return if (samplesInChunk >= chunkSamples) cut() else null
    }

    /** The final partial chunk, or null when what is left is too short to be worth transcribing. */
    fun finish(): LiveChunk? = if (samplesInChunk >= minTailSamples) cut() else null

    private fun cut(): LiveChunk {
        val chunk = LiveChunk(
            index = nextIndex++,
            startSeconds = startSamples.toDouble() / sampleRate,
            durationSeconds = samplesInChunk.toDouble() / sampleRate,
            data = buffer.toByteArray()
        )
        startSamples += samplesInChunk
        samplesInChunk = 0
        buffer = ByteArrayOutputStream()
        return chunk
    }
}
