package com.saurabhtiwari.voicetranscriber

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val error: String = ""
)

/**
 * Plays one recorded audio file at a time with the platform [MediaPlayer] (no extra dependency;
 * recordings are local AAC files). Must be used from the main thread; [scope] should be a main-thread
 * scope such as `viewModelScope`.
 */
class AudioPlayerController(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private var player: MediaPlayer? = null
    private var loadedPath: String? = null
    private var prepared = false
    private var finished = false
    private var ticker: Job? = null

    /** Loads [file] (no-op if it is already loaded). Playback state updates once the file is prepared. */
    fun load(file: File) {
        if (player != null && loadedPath == file.absolutePath) return
        release()
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                prepared = true
                mutableState.value = PlayerState(loaded = true, durationMs = ready.duration.coerceAtLeast(0))
            }
            mediaPlayer.setOnCompletionListener {
                finished = true
                stopTicker()
                mutableState.update { it.copy(playing = false, positionMs = it.durationMs) }
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                fail("This recording could not be played.")
                true
            }
            player = mediaPlayer
            loadedPath = file.absolutePath
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            mediaPlayer.release()
            player = null
            loadedPath = null
            mutableState.value = PlayerState(error = "This recording could not be played.")
        }
    }

    fun toggle() {
        val active = player ?: return
        if (!prepared) return
        try {
            if (active.isPlaying) {
                active.pause()
                stopTicker()
                mutableState.update { it.copy(playing = false) }
            } else {
                if (finished) {
                    active.seekTo(0)
                    finished = false
                }
                active.start()
                startTicker()
                mutableState.update { it.copy(playing = true) }
            }
        } catch (_: IllegalStateException) {
            fail("This recording could not be played.")
        }
    }

    /** Jumps to [positionMs] without changing whether audio is playing. */
    fun seekTo(positionMs: Int) {
        val active = player ?: return
        if (!prepared) return
        try {
            val target = positionMs.coerceIn(0, mutableState.value.durationMs.coerceAtLeast(0))
            active.seekTo(target)
            finished = false
            mutableState.update { it.copy(positionMs = target) }
        } catch (_: IllegalStateException) {
            fail("This recording could not be played.")
        }
    }

    /** Starts playing at the moment a transcript line begins (see [Playback.seekTargetMs]). */
    fun playFrom(startSeconds: Double) {
        if (!prepared) return
        seekTo(Playback.seekTargetMs(startSeconds, mutableState.value.durationMs))
        if (!mutableState.value.playing) toggle()
    }

    fun pause() {
        val active = player ?: return
        if (!prepared) return
        try {
            if (active.isPlaying) active.pause()
        } catch (_: IllegalStateException) {
        }
        stopTicker()
        mutableState.update { it.copy(playing = false) }
    }

    fun release() {
        stopTicker()
        player?.let {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        player = null
        loadedPath = null
        prepared = false
        finished = false
        mutableState.value = PlayerState()
    }

    private fun fail(message: String) {
        stopTicker()
        mutableState.update { it.copy(playing = false, error = message) }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val active = player
                if (active != null && prepared) {
                    try {
                        val position = active.currentPosition
                        mutableState.update { it.copy(positionMs = position) }
                    } catch (_: IllegalStateException) {
                        return@launch
                    }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
    }
}
