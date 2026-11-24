package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Decodes the first audio track using Android's platform codecs and emits
 * mono 16 kHz float PCM in chunks of at most 30 seconds. Chunk boundaries are placed in
 * the quietest moment near the end of each window so words are not cut in half.
 * [onChunk] receives the chunk and the offset of its first sample, in whole seconds.
 */
object AndroidAudioDecoder {
    private const val TARGET_SAMPLE_RATE = WHISPER_SAMPLE_RATE
    private const val CHUNK_SECONDS = 30
    private const val MIN_TAIL_SAMPLES = TARGET_SAMPLE_RATE / 2
    private const val OUTPUT_TIMEOUT_US = 10_000L

    suspend fun decodeToChunks(
        context: Context,
        uri: Uri,
        onDuration: suspend (Long) -> Unit,
        onChunk: suspend (samples: FloatArray, startSeconds: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    audioFormat = format
                    break
                }
            }
            val format = audioFormat ?: throw IllegalArgumentException("No audio track was found in this file.")
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Audio format is missing its MIME type.")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            onDuration(durationUs / 1_000L)

            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var inputEnded = false
            var outputEnded = false
            var sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamingResampler(sourceRate)
            val chunker = SilenceAwareChunker(maxSamples = TARGET_SAMPLE_RATE * CHUNK_SECONDS)
            var emittedSamples = 0L

            suspend fun emit(chunks: List<FloatArray>) {
                for (chunk in chunks) {
                    coroutine.ensureActive()
                    onChunk(chunk, emittedSamples / TARGET_SAMPLE_RATE)
                    emittedSamples += chunk.size
                }
            }

            while (!outputEnded) {
                coroutine.ensureActive()

                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(OUTPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Decoder input buffer was unavailable.")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val info = MediaCodec.BufferInfo()
                when (val outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sourceRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        emit(chunker.append(resampler.finish()))
                        resampler = StreamingResampler(sourceRate)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val mono = pcmToMonoFloat(outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding)
                            emit(chunker.append(resampler.process(mono)))
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEnded = true
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            emit(chunker.append(resampler.finish()))
            chunker.finish()?.let { remaining ->
                if (remaining.size >= MIN_TAIL_SAMPLES) {
                    coroutine.ensureActive()
                    onChunk(remaining, emittedSamples / TARGET_SAMPLE_RATE)
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release()
            extractor.release()
        }
    }

    private fun pcmToMonoFloat(buffer: java.nio.ByteBuffer, channels: Int, encoding: Int): FloatArray {
        require(channels > 0) { "Invalid audio channel count." }
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalArgumentException("Unsupported decoded PCM encoding: $encoding")
        }
        val frameSize = bytesPerSample * channels
        val frameCount = buffer.remaining() / frameSize
        val result = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0f
            repeat(channels) { channel ->
                val sample = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val b0 = buffer.get().toInt() and 0xff
                        val b1 = buffer.get().toInt() and 0xff
                        val b2 = buffer.get().toInt()
                        var value = b0 or (b1 shl 8) or (b2 shl 16)
                        if ((value and 0x800000) != 0) value = value or -0x1000000
                        value / 8_388_608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> (buffer.int / 2_147_483_648.0).toFloat()
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                    else -> 0f
                }
                sum += sample
            }
            result[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        return result
    }
}
