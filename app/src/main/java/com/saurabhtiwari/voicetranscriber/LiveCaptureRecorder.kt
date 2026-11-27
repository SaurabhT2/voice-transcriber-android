package com.saurabhtiwari.voicetranscriber

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Records 16 kHz mono speech through AudioRecord, encodes it once with MediaCodec, and uses the
 * same AAC frames twice: they are muxed into a normal `.m4a` (the file the final transcription
 * uses, identical to what [MeetingAudioRecorder] produces) and framed as ADTS chunks that are
 * handed to [onChunk] every 30 s for live transcription.
 *
 * Time is counted in recorded samples, so pausing never skews timestamps.
 */
class LiveCaptureRecorder(
    context: Context,
    private val onChunk: (LiveChunk) -> Unit
) : MeetingRecorder {
    private val appContext = context.applicationContext

    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var outputFile: File? = null
    @Volatile private var failure: Throwable? = null

    override fun start(): File {
        check(thread == null) { "Recording is already active." }
        // Permission is checked at the recorder boundary as well as in the UI: Android
        // permissions can be revoked between the user's tap and this call.
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission is required for live recording.")
        }
        val directory = File(appContext.filesDir, "recordings").apply { mkdirs() }
        val target = File(directory, "meeting_" + System.currentTimeMillis() + ".m4a")

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "This device cannot record 16 kHz audio." }
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer * 2, SAMPLE_RATE * 2)) // at least one second
            .build()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened." }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            record.startRecording()
        } catch (error: Exception) {
            try { record.release() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            target.delete()
            throw error
        }

        outputFile = target
        stopRequested = false
        paused = false
        val activeCodec = codec!!
        val activeMuxer = muxer!!
        thread = Thread({ pump(record, activeCodec, activeMuxer) }, "live-capture").also { it.start() }
        return target
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    /** Finalises the file. Returns null (and removes it) if it is too short or encoding failed. */
    override fun stop(): File? {
        stopRequested = true
        val worker = thread
        worker?.join(10_000)
        if (worker?.isAlive == true) {
            // Never hand a still-being-written MP4 to the transcription pipeline. The pump's
            // finally block removes the partial file if it eventually exits.
            failure = failure ?: IllegalStateException("Audio encoder did not stop in time.")
            return null
        }
        thread = null
        val target = outputFile
        outputFile = null
        if (failure != null) {
            target?.delete()
            return null
        }
        return target?.takeIf { it.isFile && it.length() > 1024L }
    }

    private fun pump(record: AudioRecord, codec: MediaCodec, muxer: MediaMuxer) {
        val cutter = ChunkCutter(sampleRate = SAMPLE_RATE, channels = 1)
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(PCM_READ_BYTES)
        var track = -1
        var muxerStarted = false
        var samplesFed = 0L
        var inputDone = false
        var outputDone = false
        var wasPaused = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    if (paused && !stopRequested) {
                        if (!wasPaused) {
                            record.stop() // stops capturing so the gap is not recorded
                            wasPaused = true
                        }
                        Thread.sleep(40)
                    } else {
                        if (wasPaused) {
                            record.startRecording()
                            wasPaused = false
                        }
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val timestampUs = samplesFed * 1_000_000L / SAMPLE_RATE
                            if (stopRequested) {
                                codec.queueInputBuffer(index, 0, 0, timestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val read = record.read(pcm, 0, min(pcm.size, buffer.capacity()))
                                if (read > 0) {
                                    buffer.clear()
                                    buffer.put(pcm, 0, read)
                                    codec.queueInputBuffer(index, 0, read, timestampUs, 0)
                                    samplesFed += read / 2
                                } else {
                                    codec.queueInputBuffer(index, 0, 0, timestampUs, 0) // hand the buffer back
                                    if (read < 0) throw IllegalStateException("Microphone read failed ($read).")
                                }
                            }
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(info, 0)
                while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            val frame = ByteArray(info.size)
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            encoded.get(frame)
                            encoded.position(info.offset)
                            muxer.writeSampleData(track, encoded, info)
                            cutter.addFrame(frame)?.let { chunk ->
                                // Live delivery is best-effort; a disk/network callback must never
                                // invalidate the primary recording.
                                try { onChunk(chunk) } catch (_: Exception) { }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
            cutter.finish()?.let { chunk ->
                try { onChunk(chunk) } catch (_: Exception) { }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try { record.stop() } catch (_: Exception) { }
            try { record.release() } catch (_: Exception) { }
            try { codec.stop() } catch (_: Exception) { }
            try { codec.release() } catch (_: Exception) { }
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) { failure = failure ?: IllegalStateException("File finalise failed") }
            try { muxer.release() } catch (_: Exception) { }
            if (failure != null) outputFile?.delete()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 64_000
        const val PCM_READ_BYTES = 4096
    }
}
