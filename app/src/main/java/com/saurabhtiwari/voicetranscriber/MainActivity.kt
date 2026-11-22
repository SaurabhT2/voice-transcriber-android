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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreparing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
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
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 28.dp),
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
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transcription", style = MaterialTheme.typography.titleMedium)
                    Text("Whisper on-device transcription is the next milestone. No audio is uploaded in this version.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Status: M0.1 — file selection and playback", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
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
