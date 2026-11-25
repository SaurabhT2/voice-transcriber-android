package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.net.Uri
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class ModelState(
    val status: String,
    val progress: Float = 0f,
    val loading: Boolean = false,
    val ready: Boolean = false
)

data class TranscriptionState(
    val running: Boolean = false,
    val status: String = "",
    val progress: Float = 0f,
    val transcript: String = ""
)

/**
 * On-device transcription that does not depend on any screen. It runs in an application-scoped
 * coroutine under a foreground service, so rotating the phone, leaving the app or swiping it
 * away does not cancel a long transcription, and the result is saved and synced by itself.
 */
class LocalTranscriber(context: Context, private val store: LocalMeetingStore) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _model = MutableStateFlow(initialModelState())
    val model: StateFlow<ModelState> = _model.asStateFlow()

    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _saved = MutableSharedFlow<LocalMeetingRecord>(extraBufferCapacity = 4)
    /** Emits each transcript right after it was saved on the device. */
    val saved: SharedFlow<LocalMeetingRecord> = _saved.asSharedFlow()

    @Volatile
    private var whisper: WhisperContext? = null
    private var modelJob: Job? = null
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    private fun initialModelState() = ModelState(
        if (WhisperModelManager.modelFile(appContext).exists()) {
            "Offline model downloaded. Load it when you are ready."
        } else {
            "Download a compact multilingual model for private on-device transcription."
        }
    )

    /** Downloads (resuming if needed), verifies and loads the model. */
    fun prepareModel() {
        if (whisper != null || modelJob?.isActive == true) return
        modelJob = scope.launch {
            _model.value = ModelState("Preparing the offline model…", loading = true)
            try {
                var lastPublished = 0L
                val file = WhisperModelManager.download(appContext) { downloaded, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastPublished >= 200L || downloaded == total) {
                        lastPublished = now
                        val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        _model.value = ModelState(
                            status = if (total > 0) {
                                "Downloading offline model: " + (progress * 100).toInt() + "%"
                            } else {
                                "Downloading offline model: " + (downloaded / (1024L * 1024L)) + " MB"
                            },
                            progress = progress,
                            loading = true
                        )
                    }
                }
                _model.update { it.copy(status = "Loading local language model…", progress = 1f) }
                whisper = withContext(Dispatchers.IO) { WhisperContext.createContextFromFile(file.absolutePath) }
                _model.value = ModelState("Local model is ready. Transcription stays on this phone.", progress = 1f, ready = true)
            } catch (error: CancellationException) {
                _model.value = initialModelState()
                throw error
            } catch (error: Exception) {
                _model.value = ModelState(error.message ?: "Unable to prepare the local model.")
            } catch (error: LinkageError) {
                _model.value = ModelState(error.message ?: "Native model runtime is unavailable on this device.")
            }
        }
    }

    /** Frees the native model (tens of MB) when nobody is using it. */
    fun releaseModelIfIdle() {
        if (isRunning || _state.value.running || modelJob?.isActive == true) return
        val loaded = whisper ?: return
        whisper = null
        _model.value = initialModelState()
        scope.launch { try { loaded.release() } catch (_: Exception) { } }
    }

    fun start(uri: Uri, ownerId: String, title: String, startedAt: String, language: String?) {
        val engine = whisper ?: return
        if (isRunning || _state.value.running) return
        _state.value = TranscriptionState(running = true, status = "Preparing audio…")
        KeepAlive.acquire(appContext, "Transcribing on this device")
        job = scope.launch {
            try {
                transcribe(engine, uri, ownerId, title, startedAt, language)
            } catch (error: CancellationException) {
                _state.update { it.copy(status = "Transcription cancelled.") }
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(status = error.message ?: "Unable to transcribe this recording locally.") }
            } finally {
                _state.update { it.copy(running = false) }
                KeepAlive.release(appContext)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears the previous result when a different recording is selected. */
    fun reset() {
        if (!isRunning) _state.value = TranscriptionState()
    }

    fun postStatus(text: String) {
        _state.update { it.copy(status = text) }
    }

    private suspend fun transcribe(
        engine: WhisperContext,
        uri: Uri,
        ownerId: String,
        title: String,
        startedAt: String,
        language: String?
    ) {
        val output = StringBuilder()
        var section = 0
        var totalDurationMs = 0L
        AndroidAudioDecoder.decodeToChunks(
            context = appContext,
            uri = uri,
            onDuration = { duration ->
                totalDurationMs = duration
                _state.update { it.copy(status = "Preparing " + formatDuration(duration.toInt()) + " of audio…") }
            },
            onChunk = { samples, startSeconds ->
                section += 1
                val endSeconds = startSeconds + samples.size / WHISPER_SAMPLE_RATE
                _state.update {
                    it.copy(
                        status = "Transcribing audio section $section…",
                        progress = if (totalDurationMs > 0L) {
                            (endSeconds * 1000f / totalDurationMs).coerceIn(0f, 0.98f)
                        } else it.progress
                    )
                }
                // Whisper tends to invent text for silence, so silent sections are skipped.
                if (!AudioStats.isSilent(samples)) {
                    val recognized = engine.transcribeData(samples, printTimestamp = false).trim()
                    if (recognized.isNotBlank()) {
                        if (output.isNotEmpty()) output.append("\n\n")
                        output.append(recognized)
                        _state.update { it.copy(transcript = output.toString()) }
                    }
                }
            }
        )

        val transcriptText = output.toString().trim()
        if (transcriptText.isBlank()) {
            _state.update { it.copy(status = "Processing complete. No speech was detected.", progress = 1f) }
            return
        }
        val saved = withContext(Dispatchers.IO) {
            store.save(
                ownerId = ownerId,
                title = title.ifBlank { "Local recording" },
                transcript = transcriptText,
                startedAt = startedAt,
                endedAt = Instant.now().toString(),
                language = language
            )
        }
        _state.update { it.copy(status = "Saved on this device. Syncing transcript to your workspace…", progress = 1f) }
        SyncWorker.enqueue(appContext) // guarantees an upload even if no screen is open
        _saved.tryEmit(saved)
    }
}
