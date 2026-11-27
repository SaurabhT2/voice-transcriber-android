package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class LiveLine(val index: Int, val startSeconds: Double, val text: String)

data class LiveState(
    val active: Boolean = false,
    val lines: List<LiveLine> = emptyList(),
    /** Chunks recorded but not yet confirmed as transcribed. */
    val pendingChunks: Int = 0,
    val status: String = ""
)

/**
 * Streams a recording to the worker in ~30 s chunks and collects the live text.
 *
 * Chunks are written to disk first and uploaded in order, so a flaky connection only delays the
 * live text; nothing is lost. The finished recording is transcribed in full afterwards, so live
 * text is a convenience, never the source of truth, and every failure here is non-fatal.
 */
class LiveStreamer(context: Context, private val backend: WorkspaceBackend) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    @Volatile
    private var current: Run? = null

    private inner class Run(val language: String?) {
        val dir = File(appContext.filesDir, "live/" + UUID.randomUUID()).apply { mkdirs() }
        val wake = Channel<Unit>(Channel.CONFLATED)
        val recorded = AtomicInteger(0)
        @Volatile var recording = true
        @Volatile var aborted = false
        @Volatile var stoppedAtMs = 0L
        @Volatile var serverId: String? = null
        /** Replaced, never mutated, so the capture thread can publish it safely. */
        @Volatile var lines: List<LiveLine> = emptyList()
        var polledUpTo = -1
        @Volatile var resultsSeen = 0
        @Volatile var skipped = 0
    }

    /** Called when a recording starts. A previous run keeps draining in the background. */
    fun begin(language: String?) {
        deleteStaleFolders()
        val run = Run(language)
        current = run
        _state.value = LiveState(active = true, status = "Live transcript starting…")
        scope.launch { pipeline(run) }
    }

    /** Called from the capture thread for every finished chunk. */
    fun onChunk(chunk: LiveChunk) {
        val run = current ?: return
        val name = String.format(Locale.ROOT, "%05d_%.3f_%.3f.aac", chunk.index, chunk.startSeconds, chunk.durationSeconds)
        File(run.dir, name).writeBytes(chunk.data)
        run.recorded.incrementAndGet()
        run.wake.trySend(Unit)
        publish(run)
    }

    /** Called when recording stops: let the pipeline finish uploading and close the session. */
    fun end() {
        val run = current ?: return
        run.stoppedAtMs = SystemClock.elapsedRealtime()
        run.recording = false
        run.wake.trySend(Unit)
    }

    /** Recording could not start; throw the run away. */
    fun cancel() {
        val run = current ?: return
        current = null
        run.aborted = true
        run.recording = false
        run.dir.deleteRecursively()
        run.wake.trySend(Unit)
        _state.value = LiveState()
    }

    private suspend fun pipeline(run: Run) {
        KeepAlive.acquire(appContext, "Sending live transcript")
        var closeSession = true
        try {
            var pause = 2_000L
            while (run.serverId == null) {
                if (cancelled(run)) return
                try {
                    run.serverId = backend.createLiveSession(run.language)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SessionExpiredException) {
                    fail(run, "Live transcript stopped: please sign in again.")
                    closeSession = false
                    return
                } catch (error: Exception) {
                    if (drainTimedOut(run)) {
                        closeSession = false
                        return
                    }
                    publish(run, "Waiting for a connection…")
                    waitFor(run, pause)
                    pause = minOf(pause * 2, 15_000L)
                }
            }
            val sessionId = checkNotNull(run.serverId)
            var uploadPause = 2_000L
            while (true) {
                if (cancelled(run)) return

                val next = pendingFiles(run).firstOrNull()
                if (next != null) {
                    val (index, start, duration) = parse(next)
                    try {
                        backend.uploadLiveChunk(sessionId, index, start, duration, next.readBytes())
                        next.delete()
                        uploadPause = 2_000L
                        continue
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: SessionExpiredException) {
                        fail(run, "Live transcript stopped: please sign in again.")
                        closeSession = false
                        return
                    } catch (error: Exception) {
                        if (classifyFailure(error) == FailureKind.PERMANENT) {
                            next.delete() // this chunk can never be accepted; the final pass covers it
                            run.skipped++
                            continue
                        }
                        publish(run, "Waiting for a connection…")
                    }
                }

                pollResults(run, sessionId)
                if (!run.recording) {
                    val drained = pendingFiles(run).isEmpty() && run.resultsSeen + run.skipped >= run.recorded.get()
                    if (drained || drainTimedOut(run)) break
                }
                waitFor(run, if (next != null) uploadPause else POLL_MS)
                if (next != null) uploadPause = minOf(uploadPause * 2, 15_000L)
            }
        } finally {
            val sessionId = run.serverId
            if (closeSession && sessionId != null) {
                try { backend.closeLiveSession(sessionId) } catch (_: Exception) { }
            }
            run.dir.deleteRecursively()
            KeepAlive.release(appContext)
            if (current === run) {
                _state.update { it.copy(active = false, pendingChunks = 0, status = "") }
            }
        }
    }

    private suspend fun pollResults(run: Run, sessionId: String) {
        val results = try {
            backend.fetchLiveChunkResults(sessionId, run.polledUpTo)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return // try again next round
        }
        for (result in results) {
            run.polledUpTo = result.index
            run.resultsSeen++
            if (result.text.isNotBlank()) {
                run.lines = run.lines + LiveLine(result.index, result.startSeconds, result.text)
            }
        }
        if (results.isNotEmpty()) publish(run)
    }

    private fun publish(run: Run, status: String? = null) {
        if (current !== run) return
        val pending = (run.recorded.get() - run.resultsSeen - run.skipped).coerceAtLeast(0)
        _state.update {
            it.copy(
                active = true,
                lines = run.lines,
                pendingChunks = pending,
                status = status ?: if (run.recording) "Live transcript" else "Finishing live transcript…"
            )
        }
    }

    private fun fail(run: Run, message: String) {
        if (current === run) _state.update { it.copy(active = false, status = message) }
    }

    private fun cancelled(run: Run) = run.aborted

    /** Recording stopped long ago and the worker still has not caught up (e.g. no connection). */
    private fun drainTimedOut(run: Run): Boolean =
        !run.recording && run.stoppedAtMs != 0L &&
            SystemClock.elapsedRealtime() - run.stoppedAtMs > DRAIN_TIMEOUT_MS

    private suspend fun waitFor(run: Run, millis: Long) {
        withTimeoutOrNull(millis) { run.wake.receive() }
    }

    private fun pendingFiles(run: Run): List<File> =
        run.dir.listFiles { f -> f.isFile && f.extension == "aac" }.orEmpty().sortedBy { it.name }

    private fun parse(file: File): Triple<Int, Double, Double> {
        val parts = file.nameWithoutExtension.split('_')
        return Triple(parts[0].toInt(), parts[1].toDouble(), parts[2].toDouble())
    }

    /** Folders left behind by a crash or a long outage are of no use any more. */
    private fun deleteStaleFolders() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        File(appContext.filesDir, "live").listFiles().orEmpty()
            .filter { it.isDirectory && it.lastModified() < cutoff }
            .forEach { it.deleteRecursively() }
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val DRAIN_TIMEOUT_MS = 3L * 60L * 1000L
    }
}
