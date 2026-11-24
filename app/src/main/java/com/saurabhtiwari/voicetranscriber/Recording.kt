package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Records mono AAC at 16 kHz, the rate Whisper consumes, so on-device transcription needs no
 * resampling and files and uploads stay small (about 0.5 MB per minute).
 */
class MeetingAudioRecorder(context: Context) {
    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): File {
        check(recorder == null) { "Recording is already active." }
        val directory = File(appContext.filesDir, "recordings").apply { mkdirs() }
        val target = File(directory, "meeting_" + System.currentTimeMillis() + ".m4a")
        val instance = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            instance.setAudioSource(MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setAudioChannels(1)
            instance.setAudioSamplingRate(SAMPLE_RATE)
            instance.setAudioEncodingBitRate(BIT_RATE)
            instance.setOutputFile(target.absolutePath)
            instance.prepare()
            instance.start()
            recorder = instance
            outputFile = target
            return target
        } catch (error: Exception) {
            try { instance.reset() } catch (_: Exception) { }
            instance.release()
            target.delete()
            throw error
        }
    }

    fun pause() {
        recorder?.pause()
    }

    fun resume() {
        recorder?.resume()
    }

    /** Finalises the file. Returns null (and removes it) if it is too short to be valid. */
    fun stop(): File? {
        val active = recorder ?: return null
        recorder = null
        val target = outputFile
        outputFile = null
        try {
            active.stop()
        } catch (_: RuntimeException) {
            target?.delete()
            return null
        } finally {
            try { active.reset() } catch (_: Exception) { }
            active.release()
        }
        return target?.takeIf { it.isFile && it.length() > 1024L }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 64_000
    }
}

/**
 * Process-wide owner of the active recording. The recorder lives here (driven by
 * [RecordingService]) instead of in a composable, so rotating the screen, leaving the app or
 * locking the phone never interrupts or deletes a recording.
 */
object RecordingController {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val lock = Any()
    private var recorder: MeetingAudioRecorder? = null

    fun start(context: Context): Boolean {
        synchronized(lock) {
            if (recorder != null) return true
            val instance = MeetingAudioRecorder(context)
            return try {
                val file = instance.start()
                recorder = instance
                _state.value = RecordingState(
                    active = true,
                    file = file,
                    resumedAtMs = SystemClock.elapsedRealtime()
                )
                true
            } catch (error: Exception) {
                _state.value = RecordingState(
                    error = error.message ?: "Unable to start recording. Check microphone access."
                )
                false
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            val active = recorder ?: return
            try {
                active.pause()
                val now = SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(paused = true, accumulatedMs = it.accumulatedMs + (now - it.resumedAtMs).coerceAtLeast(0L))
                }
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Unable to pause the recording.") }
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            val active = recorder ?: return
            try {
                active.resume()
                _state.update { it.copy(paused = false, resumedAtMs = SystemClock.elapsedRealtime()) }
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "Unable to resume the recording.") }
            }
        }
    }

    /** Stops and publishes the result via [RecordingState.completedFile]. */
    fun stop(): File? {
        synchronized(lock) {
            val active = recorder ?: return null
            recorder = null
            val file = active.stop()
            _state.value = if (file == null) {
                RecordingState(error = "The recording was too short or could not be finalized. Please record again.")
            } else {
                RecordingState(completedFile = file)
            }
            return file
        }
    }

    fun reportError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun clearError() {
        _state.update { if (it.error.isEmpty()) it else it.copy(error = "") }
    }

    /** Returns the finished recording once, then forgets it. */
    fun takeCompleted(): File? {
        synchronized(lock) {
            val file = _state.value.completedFile ?: return null
            _state.update { it.copy(completedFile = null) }
            return file
        }
    }
}

suspend fun copyRecordingToCache(context: Context, uri: Uri, suggestedName: String): File {
    val directory = File(context.cacheDir, "upload_staging").apply { mkdirs() }
    val safeName = suggestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
    val destination = File(directory, System.currentTimeMillis().toString() + "_" + safeName)
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: throw IOException("Android could not read the selected audio file.")
    }
    return destination
}
