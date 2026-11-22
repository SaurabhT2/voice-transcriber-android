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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
        mutableStateOf(
            if (modelFile.exists()) "Model downloaded. Load it to prepare offline transcription."
            else "No model downloaded yet."
        )
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

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            transcriptionJob?.cancel()
            transcriptionJob = null
            isTranscribing = false
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers do not grant persistable URI permissions.
            }
            player?.release()
            player = null
            selectedUri = uri
            selectedName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Selected audio"
            isPreparing = false
            isPlaying = false
            durationMs = 0
            playbackError = null
            transcript = ""
            transcriptionStatus = "Audio selected. Load the model to transcribe offline."
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Voice Transcriber", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Private, on-device audio transcription", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Choose an audio file", style = MaterialTheme.typography.titleLarge)
                    Text("Select a recording from your phone. The file stays on your device.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Browse audio files")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Offline speech model", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Download the multilingual Whisper tiny model (quantized, approximately 31 MB). " +
                            "It is stored in this app's private storage and can be reused offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLoadingModel) {
                        LinearProgressIndicator(
                            progress = { modelProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(modelStatus, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            scope.launch {
                                isLoadingModel = true
                                modelProgress = 0f
                                try {
                                    val file = WhisperModelManager.download(context) { downloaded, total ->
                                        withContext(Dispatchers.Main) {
                                            modelProgress = if (total > 0) {
                                                (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                            } else 0f
                                            modelStatus = if (total > 0) {
                                                "Downloading model: ${(modelProgress * 100).toInt()}%"
                                            } else {
                                                "Downloading model: ${downloaded / (1024 * 1024)} MB"
                                            }
                                        }
                                    }
                                    modelStatus = "Loading native Whisper model into memory…"
                                    val loaded = withContext(Dispatchers.IO) {
                                        WhisperContext.createContextFromFile(file.absolutePath)
                                    }
                                    whisperContext = loaded
                                    modelStatus = "Whisper model loaded. It is ready for offline transcription."
                                } catch (error: Exception) {
                                    modelStatus = "Model setup failed: ${error.localizedMessage ?: "Unknown error"}"
                                } catch (error: LinkageError) {
                                    modelStatus = "Native Whisper runtime could not load: ${error.localizedMessage ?: "ABI or library error"}"
                                } finally {
                                    isLoadingModel = false
                                }
                            }
                        },
                        enabled = !isLoadingModel && whisperContext == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                isLoadingModel -> "Preparing model…"
                                whisperContext != null -> "Offline model loaded"
                                modelFile.exists() -> "Load offline model"
                                else -> "Download and load model"
                            }
                        )
                    }
                }
            }
            if (selectedUri != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Selected recording", style = MaterialTheme.typography.titleMedium)
                        Text(selectedName ?: "Audio file", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                val uri = selectedUri ?: return@OutlinedButton
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
                            }, enabled = !isPreparing) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(when {
                                    isPreparing -> "Loading…"
                                    isPlaying -> "Pause"
                                    else -> "Play preview"
                                })
                            }
                        }
                        if (isPreparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (durationMs > 0) {
                            Text("Duration: ${formatDuration(durationMs)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("On-device transcription", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Audio is decoded on the phone into mono 16 kHz PCM and processed in 30-second chunks. " +
                            "Whisper automatically detects the spoken language. No audio leaves this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            if (isTranscribing) {
                                transcriptionJob?.cancel()
                            } else {
                                val uri = selectedUri ?: return@Button
                                val engine = whisperContext ?: return@Button
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
                                                    transcriptionStatus =
                                                        "Transcribing audio chunk $chunkIndex (around ${offsetSeconds}s)…"
                                                }
                                                val recognized = engine.transcribeData(
                                                    samples,
                                                    printTimestamp = false
                                                ).trim()
                                                if (recognized.isNotBlank()) {
                                                    withContext(Dispatchers.Main) {
                                                        transcript = listOf(transcript, recognized)
                                                            .filter { it.isNotBlank() }
                                                            .joinToString(" ")
                                                    }
                                                }
                                            }
                                        )
                                        transcriptionStatus = if (transcript.isBlank()) {
                                            "Processing complete. No speech was detected."
                                        } else {
                                            "Transcription complete — $chunkIndex audio chunk(s) processed."
                                        }
                                    } catch (cancelled: CancellationException) {
                                        transcriptionStatus = "Transcription cancelled."
                                        throw cancelled
                                    } catch (error: Exception) {
                                        transcriptionStatus =
                                            "Transcription failed: ${error.localizedMessage ?: "Unsupported or unreadable audio"}"
                                    } finally {
                                        isTranscribing = false
                                        transcriptionJob = null
                                    }
                                }
                            }
                        },
                        enabled = isTranscribing || (selectedUri != null && whisperContext != null),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isTranscribing) "Cancel transcription" else "Transcribe offline")
                    }
                    Text(transcriptionStatus, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isTranscribing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (transcript.isNotBlank()) {
                        Text("Transcript", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(transcript, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Status: M0.3 — offline transcription",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Supported formats depend on Android's audio decoder and the selected file provider.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
