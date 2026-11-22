package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceTranscriberScreen()
                }
            }
        }
    }
}

@Composable
private fun VoiceTranscriberScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelFile = remember(context) { WhisperModelManager.modelFile(context) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableStateOf(0) }
    var whisperContext by remember { mutableStateOf<WhisperContext?>(null) }
    var isLoadingModel by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableFloatStateOf(0f) }
    var modelStatus by remember {
        mutableStateOf(if (modelFile.exists()) "Model downloaded. Load it to prepare offline transcription."
            else "No model downloaded yet.")
    }
    var isTranscribing by remember { mutableStateOf(false) }
    var transcriptionStatus by remember { mutableStateOf("Select audio and load the model to begin.") }
    var transcript by remember { mutableStateOf("") }
    var transcriptionJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }
    DisposableEffect(whisperContext) {
        val contextToRelease = whisperContext
        onDispose {
            contextToRelease?.let { loaded ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loaded.release() }
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            transcriptionJob?.cancel()
            transcriptionJob = null
            isTranscribing = false
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            player?.release()
            player = null
            selectedUri = uri
            selectedName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Selected audio"
            isPreparing = false
            isPlaying = false
            durationMs = 0
            playbackError = null
            transcript = ""
            transcriptionStatus = "Audio selected. Ready when the model is loaded."
        }
    }

    fun togglePlayback() {
        val uri = selectedUri ?: return
        playbackError = null
        try {
            if (player?.isPlaying == true) {
                player?.pause()
                isPlaying = false
            } else if (player != null && !isPreparing) {
                player?.start()
                isPlaying = true
            } else {
                player?.release()
                isPreparing = true
                val newPlayer = MediaPlayer()
                player = newPlayer
                newPlayer.setOnPreparedListener { prepared ->
                    durationMs = prepared.duration
                    isPreparing = false
                    isPlaying = true
                    prepared.start()
                }
                newPlayer.setOnCompletionListener { isPlaying = false }
                newPlayer.setOnErrorListener { _, what, extra ->
                    isPreparing = false
                    isPlaying = false
                    playbackError = "Unable to play audio (error $what/$extra)."
                    true
                }
                newPlayer.setDataSource(context, uri)
                newPlayer.prepareAsync()
            }
        } catch (error: Exception) {
            isPreparing = false
            isPlaying = false
            playbackError = error.localizedMessage ?: "Unable to play this audio."
        }
    }

    fun startOrCancelTranscription() {
        if (isTranscribing) {
            transcriptionJob?.cancel()
            return
        }
        val uri = selectedUri ?: return
        val engine = whisperContext ?: return
        transcript = ""
        isTranscribing = true
        transcriptionStatus = "Preparing audio decoder…"
        transcriptionJob = scope.launch {
            try {
                var chunkIndex = 0
                AndroidAudioDecoder.decodeToChunks(
                    context = context,
                    uri = uri,
                    onDuration = { decodedDuration ->
                        withContext(Dispatchers.Main) {
                            if (decodedDuration > 0) durationMs = decodedDuration.toInt()
                        }
                    },
                    onChunk = { samples, offsetSeconds ->
                        chunkIndex += 1
                        withContext(Dispatchers.Main) {
                            transcriptionStatus = "Transcribing chunk $chunkIndex (around ${offsetSeconds}s)…"
                        }
                        val recognized = engine.transcribeData(samples, printTimestamp = false).trim()
                        if (recognized.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                transcript = listOf(transcript, recognized).filter { it.isNotBlank() }.joinToString(" ")
                            }
                        }
                    }
                )
                transcriptionStatus = if (transcript.isBlank()) "Processing complete. No speech was detected."
                    else "Transcription complete — $chunkIndex audio chunk(s) processed."
            } catch (cancelled: CancellationException) {
                transcriptionStatus = "Transcription cancelled."
                throw cancelled
            } catch (error: Exception) {
                transcriptionStatus = "Transcription failed: ${error.localizedMessage ?: "Unsupported or unreadable audio"}"
            } finally {
                isTranscribing = false
                transcriptionJob = null
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Fixed control zone: all four primary actions remain visible without scrolling.
            Column(
                modifier = Modifier.weight(0.44f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Voice Transcriber", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Private, on-device transcription", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { audioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Choose audio")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isLoadingModel = true
                                modelProgress = 0f
                                try {
                                    val file = WhisperModelManager.download(context) { downloaded, total ->
                                        withContext(Dispatchers.Main) {
                                            modelProgress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                                            modelStatus = if (total > 0) "Downloading model: ${(modelProgress * 100).toInt()}%"
                                                else "Downloading model: ${downloaded / (1024 * 1024)} MB"
                                        }
                                    }
                                    modelStatus = "Loading Whisper model into memory…"
                                    val loaded = withContext(Dispatchers.IO) {
                                        WhisperContext.createContextFromFile(file.absolutePath)
                                    }
                                    whisperContext = loaded
                                    modelStatus = "Whisper model loaded and ready offline."
                                } catch (error: Exception) {
                                    modelStatus = "Model setup failed: ${error.localizedMessage ?: "Unknown error"}"
                                } catch (error: LinkageError) {
                                    modelStatus = "Native runtime error: ${error.localizedMessage ?: "ABI or library error"}"
                                } finally {
                                    isLoadingModel = false
                                }
                            }
                        },
                        enabled = !isLoadingModel && whisperContext == null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(when {
                            isLoadingModel -> "Loading model…"
                            whisperContext != null -> "Model ready"
                            modelFile.exists() -> "Load model"
                            else -> "Get model"
                        })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { togglePlayback() },
                        enabled = selectedUri != null && !isPreparing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(when { isPreparing -> "Preparing…"; isPlaying -> "Pause"; else -> "Play preview" })
                    }
                    Button(
                        onClick = { startOrCancelTranscription() },
                        enabled = isTranscribing || (selectedUri != null && whisperContext != null),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isTranscribing) "Cancel" else "Transcribe")
                    }
                }
                Text(
                    when {
                        isTranscribing -> "Transcription running"
                        whisperContext != null -> "Model ready"
                        else -> "Choose a recording, then load the model"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Output zone: independently scrollable, so long transcripts never hide the controls.
            Card(
                modifier = Modifier.weight(0.56f).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Activity & output", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Selected recording", style = MaterialTheme.typography.labelLarge)
                    Text(selectedName ?: "No audio selected", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (durationMs > 0) Text("Duration: ${formatDuration(durationMs)}", style = MaterialTheme.typography.bodySmall)
                    playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Offline model", style = MaterialTheme.typography.labelLarge)
                    Text(modelStatus, style = MaterialTheme.typography.bodySmall)
                    if (isLoadingModel) LinearProgressIndicator(progress = { modelProgress }, modifier = Modifier.fillMaxWidth())
                    Text("Transcription status", style = MaterialTheme.typography.labelLarge)
                    Text(transcriptionStatus, style = MaterialTheme.typography.bodySmall)
                    if (isTranscribing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (transcript.isNotBlank()) {
                        Text("Transcript", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(transcript, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Your transcript will appear here as audio is processed.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("M0.4 — compact controls and dedicated output panel",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

internal fun formatDuration(durationMs: Int): String {
    val totalSeconds = durationMs / 1_000
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
