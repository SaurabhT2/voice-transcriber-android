package com.saurabhtiwari.voicetranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Canvas = Color(0xFFF7F9FC)
private val Ink = Color(0xFF18212F)
private val Muted = Color(0xFF7B8798)
private val Border = Color(0xFFE8EDF3)
private val Blue = Color(0xFF3269E8)
private val BlueTint = Color(0xFFEDF2FF)
private val Violet = Color(0xFF7458D8)
private val VioletTint = Color(0xFFF2EFFF)
private val Green = Color(0xFF16845D)
private val GreenTint = Color(0xFFEAF7F1)
private val Amber = Color(0xFFAD761D)
private val AmberTint = Color(0xFFFFF7E8)

private val WorkspaceColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Violet,
    onSecondary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3F6FA),
    onSurfaceVariant = Muted,
    outline = Border,
    error = Color(0xFFB33C46)
)

class MainActivity : ComponentActivity() {
    private val auth: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WorkspaceTheme {
                val signedIn = auth.session
                when {
                    auth.restoring -> LoadingScreen("Opening your workspace…")
                    signedIn == null -> SignInScreen(
                        busy = auth.busy,
                        message = auth.message,
                        error = auth.error,
                        onSendLink = auth::sendMagicLink
                    )
                    else -> MeetingWorkspaceScreen(
                        session = signedIn,
                        onSignOut = auth::signOut,
                        onSessionExpired = auth::onSessionExpired
                    )
                }
            }
        }
        // Only a fresh launch may consume a sign-in link. A recreated activity still carries the
        // original intent and must not replay a one-time code.
        val handled = savedInstanceState == null && handleAuthLink(intent)
        if (!handled) auth.restore()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthLink(intent)
    }

    private fun handleAuthLink(source: Intent?): Boolean {
        val link = source?.data ?: return false
        if (link.scheme != "meetingworkspace" || link.host != "auth") return false
        setIntent(Intent(source).setData(null)) // consume so it can never be replayed
        auth.completeSignIn(link)
        return true
    }
}

@Composable
private fun WorkspaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WorkspaceColors, content = content)
}

@Composable
private fun LoadingScreen(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Canvas),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Blue)
            Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SignInScreen(
    busy: Boolean,
    message: String,
    error: String,
    onSendLink: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(18.dp))
                .background(Color.White).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BrandMark()
            Text("PRIVATE MEETING WORKSPACE", color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(
                "Every conversation,\nmade useful.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Ink
            )
            Text(
                "Sign in to keep your recordings, transcripts and meeting notes together across devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Work email") },
                singleLine = true
            )
            Button(
                onClick = { onSendLink(email.trim()) },
                enabled = !busy && email.contains("@") && email.contains("."),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Email me a sign-in link", fontWeight = FontWeight.SemiBold)
            }
            if (message.isNotBlank()) InfoMessage(message, GreenTint, Green)
            if (error.isNotBlank()) InfoMessage(error, Color(0xFFFFEEEE), Color(0xFFB33C46))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                androidx.compose.material3.Icon(Icons.Default.Lock, null, tint = Green, modifier = Modifier.size(15.dp))
                Text("Authenticated access · Private audio · Your own workspace", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingWorkspaceScreen(
    session: UserSession,
    onSignOut: () -> Unit,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val vm: MeetingViewModel = viewModel(key = session.userId, factory = MeetingViewModel.factory(session.userId))
    val recording by RecordingController.state.collectAsStateWithLifecycle()
    val model by vm.modelState.collectAsStateWithLifecycle()
    val transcription by vm.transcriptionState.collectAsStateWithLifecycle()

    LaunchedEffect(session.userId) { vm.onScreenEntered() }
    LaunchedEffect(vm.requiresSignIn) {
        if (vm.requiresSignIn) {
            vm.acknowledgeSignInRequired()
            onSessionExpired()
        }
    }

    // The recording clock is derived from a monotonic timestamp, so it is correct after the
    // screen was away or recreated; this state only drives redraws.
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(recording.active, recording.paused) {
        while (recording.active && !recording.paused) {
            nowMs = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val elapsedRecordingMs = recording.elapsedAt(nowMs)

    val completedCount = remember(vm.history) { vm.history.count { it.status == "succeeded" } }
    val processingCount = remember(vm.history) { vm.history.count { it.status == "queued" || it.status == "processing" } }
    val attentionCount = remember(vm.history) {
        vm.history.count { it.status == "failed" || it.status == "upload_pending" || it.isLocalOnly }
    }

    // Jobs keep running on the server; follow them while the history is on screen.
    val hasProcessing = processingCount > 0
    LaunchedEffect(vm.tab, hasProcessing) {
        while (vm.tab == WorkspaceTab.HISTORY && hasProcessing) {
            delay(8_000)
            vm.refreshHistory()
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onAudioPicked(uri)
    }
    val recordingPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Notifications are optional (the foreground service runs without them); the microphone is not.
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) vm.startRecording()
        else vm.reportRecordingError("Microphone access is required to record a meeting.")
    }

    fun requestRecording() {
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (micGranted && !needsNotifications) {
            vm.startRecording()
        } else {
            val missing = buildList {
                if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
                if (needsNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            recordingPermissions.launch(missing.toTypedArray())
        }
    }

    val transcriptionStatus = transcription.status.ifBlank { vm.hint }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrandMark()
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            Text("Meeting intelligence", fontWeight = FontWeight.Bold, color = Ink)
                            Text("YOUR WORKSPACE", style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 8.dp).clip(CircleShape)
                            .background(BlueTint).size(34.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            session.email.firstOrNull()?.uppercase() ?: "U",
                            color = Blue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = onSignOut) {
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = vm.tab == WorkspaceTab.OVERVIEW,
                    onClick = { vm.tab = WorkspaceTab.OVERVIEW },
                    icon = { androidx.compose.material3.Icon(Icons.Default.GraphicEq, null) },
                    label = { Text("Overview") }
                )
                NavigationBarItem(
                    selected = vm.tab == WorkspaceTab.TRANSCRIBE,
                    onClick = { vm.tab = WorkspaceTab.TRANSCRIBE },
                    icon = { androidx.compose.material3.Icon(Icons.Default.UploadFile, null) },
                    label = { Text("Transcribe") }
                )
                NavigationBarItem(
                    selected = vm.tab == WorkspaceTab.HISTORY,
                    onClick = { vm.tab = WorkspaceTab.HISTORY; vm.refreshHistory() },
                    icon = { androidx.compose.material3.Icon(Icons.Default.LibraryMusic, null) },
                    label = { Text("My recordings") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (vm.errorMessage.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                InfoMessage(vm.errorMessage, Color(0xFFFFEEEE), Color(0xFFB33C46), onDismiss = { vm.dismissError() })
            }
            when (vm.tab) {
                WorkspaceTab.OVERVIEW -> ScrollingTab {
                    OverviewContent(
                        email = session.email,
                        recordingCount = vm.history.size,
                        completedCount = completedCount,
                        processingCount = processingCount,
                        attentionCount = attentionCount,
                        onRecord = { requestRecording() },
                        onUpload = {
                            vm.tab = WorkspaceTab.TRANSCRIBE
                            audioPicker.launch(arrayOf("audio/*", "video/mp4"))
                        },
                        onTranscribe = { vm.tab = WorkspaceTab.TRANSCRIBE },
                        onHistory = { vm.tab = WorkspaceTab.HISTORY; vm.refreshHistory() }
                    )
                }
                WorkspaceTab.TRANSCRIBE -> ScrollingTab {
                    TranscribeContent(
                        selectedName = vm.selectedName,
                        selectedContentType = vm.selectedContentType,
                        selectedMode = vm.selectedMode,
                        onModeChange = { vm.selectedMode = it },
                        isRecording = recording.active,
                        isPaused = recording.paused,
                        elapsedRecordingMs = elapsedRecordingMs,
                        recordingError = vm.recordingError,
                        onRecord = { requestRecording() },
                        onPauseResume = { vm.pauseOrResumeRecording() },
                        onStopRecording = { vm.stopRecording() },
                        onChooseFile = { audioPicker.launch(arrayOf("audio/*", "video/mp4")) },
                        onClearFile = { vm.clearSelectedRecording() },
                        language = vm.language,
                        languageExpanded = vm.languageExpanded,
                        onLanguageExpanded = { vm.languageExpanded = it },
                        onLanguageChange = { vm.language = it },
                        modelStatus = model.status,
                        modelLoading = model.loading,
                        modelProgress = model.progress,
                        modelReady = model.ready,
                        onPrepareModel = { vm.prepareModel() },
                        localTranscribing = transcription.running,
                        transcriptionStatus = transcriptionStatus,
                        transcriptionProgress = transcription.progress,
                        localTranscript = transcription.transcript,
                        cloudBusy = vm.cloudBusy,
                        cloudStatus = vm.cloudStatus,
                        cloudJob = vm.cloudJob,
                        onLocalTranscribe = { vm.startLocalTranscription() },
                        onCloudTranscribe = { vm.startSharedGpuTranscription() },
                        onCancelLocal = { vm.cancelLocalTranscription() },
                        canTranscribe = vm.selectedUri != null && !recording.active
                    )
                }
                WorkspaceTab.HISTORY -> HistoryContent(
                    modifier = Modifier.weight(1f),
                    recordings = vm.history,
                    filter = vm.historyFilter,
                    onFilterChange = { vm.historyFilter = it },
                    loading = vm.historyLoading,
                    error = vm.historyError,
                    onRefresh = { vm.refreshHistory() },
                    onOpen = vm::openRecording,
                    currentMeeting = vm.currentMeeting,
                    currentLocalRecord = vm.currentLocalRecord,
                    meetingNotes = vm.meetingNotes,
                    notesBusy = vm.notesBusy,
                    notesStatus = vm.meetingNotesStatus,
                    onLoadNotes = { vm.loadNotes() },
                    onGenerateNotes = { vm.generateNotes() }
                )
            }
        }
    }
}

/** Scrollable tab body that fills the remaining height; History uses a LazyColumn instead. */
@Composable
private fun ColumnScope.ScrollingTab(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        content()
        Spacer(Modifier.height(8.dp))
        Footer()
    }
}

@Composable
private fun OverviewContent(
    email: String,
    recordingCount: Int,
    completedCount: Int,
    processingCount: Int,
    attentionCount: Int,
    onRecord: () -> Unit,
    onUpload: () -> Unit,
    onTranscribe: () -> Unit,
    onHistory: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Good to see you.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "Record a meeting or bring an existing audio file into your private workspace.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Text(email, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Recordings", recordingCount.toString(), Icons.Default.AudioFile, Modifier.weight(1f))
            StatCard("Transcripts", completedCount.toString(), Icons.Default.CheckCircle, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Processing", processingCount.toString(), Icons.Default.AccessTime, Modifier.weight(1f))
            StatCard("Needs attention", attentionCount.toString(), Icons.Default.Refresh, Modifier.weight(1f))
        }
        WorkspaceCard {
            PanelHeading(
                icon = Icons.Default.Mic,
                title = "Record a meeting",
                subtitle = "Capture audio directly on this device.",
                tag = "01 / RECORD"
            )
            Text(
                "Your recording is saved on this phone. Choose local transcription or explicitly send it to your shared GPU worker.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Button(
                onClick = onRecord,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(9.dp)
            ) {
                androidx.compose.material3.Icon(Icons.Default.Mic, null)
                Spacer(Modifier.width(8.dp))
                Text("Start recording")
            }
        }
        WorkspaceCard {
            PanelHeading(
                icon = Icons.Default.CloudUpload,
                title = "Transcribe a recording",
                subtitle = "Upload a file or choose where transcription runs.",
                tag = "02 / AUDIO"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUpload, modifier = Modifier.weight(1f)) {
                    androidx.compose.material3.Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Choose audio")
                }
                Button(onClick = onTranscribe, modifier = Modifier.weight(1f)) {
                    Text("Transcription options")
                }
            }
        }
        WorkspaceCard {
            PanelHeading(
                icon = Icons.Default.History,
                title = "My recordings",
                subtitle = "See uploads, processing status and transcripts across devices.",
                tag = "03 / REVIEW"
            )
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Icon(Icons.Default.LibraryMusic, null)
                Spacer(Modifier.width(7.dp))
                Text("Open recording history")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            androidx.compose.material3.Icon(Icons.Default.Lock, null, tint = Green, modifier = Modifier.size(15.dp))
            Text("Private by design · Local audio stays on your device until you choose upload.", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
private fun TranscribeContent(
    selectedName: String,
    selectedContentType: String,
    selectedMode: TranscriptionMode,
    onModeChange: (TranscriptionMode) -> Unit,
    isRecording: Boolean,
    isPaused: Boolean,
    elapsedRecordingMs: Long,
    recordingError: String,
    onRecord: () -> Unit,
    onPauseResume: () -> Unit,
    onStopRecording: () -> Unit,
    onChooseFile: () -> Unit,
    onClearFile: () -> Unit,
    language: String,
    languageExpanded: Boolean,
    onLanguageExpanded: (Boolean) -> Unit,
    onLanguageChange: (String) -> Unit,
    modelStatus: String,
    modelLoading: Boolean,
    modelProgress: Float,
    modelReady: Boolean,
    onPrepareModel: () -> Unit,
    localTranscribing: Boolean,
    transcriptionStatus: String,
    transcriptionProgress: Float,
    localTranscript: String,
    cloudBusy: Boolean,
    cloudStatus: String,
    cloudJob: CloudJob?,
    onLocalTranscribe: () -> Unit,
    onCloudTranscribe: () -> Unit,
    onCancelLocal: () -> Unit,
    canTranscribe: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WorkspaceCard {
            PanelHeading(
                icon = Icons.Default.Mic,
                title = "Record a meeting",
                subtitle = "Use the phone microphone to capture a conversation.",
                tag = "01 / RECORD"
            )
            if (isRecording) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFF3F3))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFD94848)))
                            Text(if (isPaused) "Recording paused" else "Recording in progress", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(formatDuration(elapsedRecordingMs.toInt()), color = Ink, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onPauseResume, modifier = Modifier.weight(1f)) {
                                androidx.compose.material3.Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    null
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(if (isPaused) "Resume" else "Pause")
                            }
                            Button(
                                onClick = onStopRecording,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB33C46))
                            ) {
                                androidx.compose.material3.Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(5.dp))
                                Text("Stop")
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRecord, modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Record")
                    }
                    OutlinedButton(onClick = onChooseFile, modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Choose file")
                    }
                }
            }
            if (recordingError.isNotBlank()) InfoMessage(recordingError, Color(0xFFFFEEEE), Color(0xFFB33C46))
        }

        WorkspaceCard {
            PanelHeading(
                icon = Icons.Default.UploadFile,
                title = "Selected recording",
                subtitle = "Choose an audio file or use the recording you just made.",
                tag = "02 / SOURCE"
            )
            if (selectedName.isBlank()) {
                Text("No recording selected yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(7.dp))
                    Text("Browse audio files")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BlueTint),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(Icons.Default.AudioFile, null, tint = Blue)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(selectedName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(selectedContentType, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    androidx.compose.material3.IconButton(onClick = onClearFile) {
                        Text("×", color = Muted, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        WorkspaceCard {
            PanelHeading(
                icon = Icons.Default.AutoAwesome,
                title = "Transcription",
                subtitle = "Choose explicitly where your audio is processed.",
                tag = "03 / PROCESS"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedMode == TranscriptionMode.LOCAL,
                    onClick = { onModeChange(TranscriptionMode.LOCAL) },
                    label = { Text("Local on device") },
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedMode == TranscriptionMode.SHARED_GPU,
                    onClick = { onModeChange(TranscriptionMode.SHARED_GPU) },
                    label = { Text("Shared GPU") },
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                if (selectedMode == TranscriptionMode.LOCAL)
                    "Local mode: audio is processed on this phone. Only the resulting transcript is synced to your workspace."
                else
                    "Shared GPU mode: audio is uploaded to your private workspace and processed by your configured GPU worker.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Language", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(languageLabel(language), fontWeight = FontWeight.Medium)
                }
                Box {
                    OutlinedButton(onClick = { onLanguageExpanded(true) }) { Text("Change") }
                    DropdownMenu(expanded = languageExpanded, onDismissRequest = { onLanguageExpanded(false) }) {
                        listOf("auto", "en", "hi", "zh", "ja", "ko").forEach { code ->
                            DropdownMenuItem(
                                text = { Text(languageLabel(code)) },
                                onClick = { onLanguageChange(code); onLanguageExpanded(false) }
                            )
                        }
                    }
                }
            }
            if (selectedMode == TranscriptionMode.LOCAL) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF8FAFD)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Offline model", fontWeight = FontWeight.SemiBold)
                    Text(modelStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (modelLoading) LinearProgressIndicator(progress = { modelProgress }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(
                        onClick = onPrepareModel,
                        enabled = !modelLoading && !modelReady,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (modelLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(if (modelReady) "Model ready" else if (modelStatus.startsWith("Offline model downloaded")) "Load local model" else "Download local model")
                    }
                }
                Button(
                    onClick = if (localTranscribing) onCancelLocal else onLocalTranscribe,
                    enabled = canTranscribe && (modelReady || localTranscribing),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(if (localTranscribing) "Cancel transcription" else "Transcribe on this device")
                }
            } else {
                Button(
                    onClick = onCloudTranscribe,
                    enabled = canTranscribe && !cloudBusy,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    if (cloudBusy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else androidx.compose.material3.Icon(Icons.Default.CloudUpload, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (cloudBusy) "Uploading and processing…" else "Upload and transcribe")
                }
                Text("Audio is sent only after you choose Shared GPU and start the upload.", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (localTranscribing) LinearProgressIndicator(progress = { transcriptionProgress }, modifier = Modifier.fillMaxWidth())
            if (transcriptionStatus.isNotBlank()) Text(transcriptionStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
            if (cloudStatus.isNotBlank()) Text(cloudStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
            cloudJob?.let { job ->
                StatusPill(job.status)
                if (!job.errorCode.isNullOrBlank()) Text(job.errorCode, color = Color(0xFFB33C46), style = MaterialTheme.typography.labelSmall)
            }
        }

        if (localTranscript.isNotBlank()) {
            WorkspaceCard {
                PanelHeading(Icons.Default.AudioFile, "Transcript", "Text created with on-device processing.", "LOCAL OUTPUT")
                Text(localTranscript, style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
    }
}

private const val HISTORY_DETAIL_INDEX = 3 // header, stats, filters, then the opened transcript

/**
 * Lazily composed list: only the rows on screen are built, so hundreds of recordings stay smooth.
 * An opened transcript sits above the list and is scrolled into view.
 */
@Composable
private fun HistoryContent(
    modifier: Modifier,
    recordings: List<RecordingItem>,
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    loading: Boolean,
    error: String,
    onRefresh: () -> Unit,
    onOpen: (RecordingItem) -> Unit,
    currentMeeting: MeetingDetail?,
    currentLocalRecord: LocalMeetingRecord?,
    meetingNotes: JSONObject?,
    notesBusy: Boolean,
    notesStatus: String,
    onLoadNotes: () -> Unit,
    onGenerateNotes: () -> Unit
) {
    val completed = recordings.count { it.status == "succeeded" }
    val processing = recordings.count { it.status == "queued" || it.status == "processing" }
    val attention = recordings.count { it.status == "failed" || it.status == "upload_pending" || it.isLocalOnly }
    val filtered = remember(recordings, filter) {
        recordings.filter { entry ->
            when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.PROCESSING -> entry.status == "queued" || entry.status == "processing"
                HistoryFilter.COMPLETED -> entry.status == "succeeded"
                HistoryFilter.ATTENTION -> entry.status == "failed" || entry.status == "upload_pending" || entry.isLocalOnly
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentMeeting?.meetingId, currentLocalRecord?.eventId) {
        if (currentMeeting != null || currentLocalRecord != null) listState.animateScrollToItem(HISTORY_DETAIL_INDEX)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("My recordings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Your uploads and locally transcribed meetings.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onRefresh, enabled = !loading) {
                    androidx.compose.material3.Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Refresh")
                }
            }
        }
        item(key = "stats") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("All", recordings.size.toString(), Icons.Default.LibraryMusic, Modifier.weight(1f))
                    StatCard("Ready", completed.toString(), Icons.Default.CheckCircle, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Processing", processing.toString(), Icons.Default.AccessTime, Modifier.weight(1f))
                    StatCard("Attention", attention.toString(), Icons.Default.Refresh, Modifier.weight(1f))
                }
            }
        }
        item(key = "filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilterChange(option) },
                        label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }
        if (currentMeeting != null) {
            item(key = "meeting") {
                WorkspaceCard {
                    PanelHeading(Icons.Default.AudioFile, currentMeeting.title, "Transcript and meeting intelligence.", "MEETING DETAIL")
                    TranscriptBody(currentMeeting)
                    Text(
                        listOfNotNull(currentMeeting.source, currentMeeting.language, currentMeeting.model).joinToString(" · "),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onLoadNotes, enabled = !notesBusy, modifier = Modifier.weight(1f)) {
                            Text("Load notes")
                        }
                        Button(onClick = onGenerateNotes, enabled = !notesBusy, modifier = Modifier.weight(1f)) {
                            if (notesBusy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text(if (meetingNotes != null) "Regenerate notes" else "Generate notes")
                        }
                    }
                    if (notesStatus.isNotBlank()) {
                        Text(notesStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (notesBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
                    }
                    meetingNotes?.let { NotesContent(it) }
                }
            }
        } else if (currentLocalRecord != null) {
            item(key = "local") {
                WorkspaceCard {
                    PanelHeading(Icons.Default.Lock, currentLocalRecord.title, "Saved privately on this device.", "LOCAL TRANSCRIPT")
                    Text(currentLocalRecord.transcript, color = Ink, style = MaterialTheme.typography.bodyMedium)
                    InfoMessage("This transcript has not synced to your online workspace yet. It will retry when connectivity is available.", AmberTint, Amber)
                }
            }
        }
        if (error.isNotBlank()) {
            item(key = "error") { InfoMessage(error, AmberTint, Amber) }
        }
        if (loading && recordings.isEmpty()) {
            item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Blue)
                }
            }
        } else if (filtered.isEmpty()) {
            item(key = "empty") {
                WorkspaceCard {
                    androidx.compose.material3.Icon(Icons.Default.AudioFile, null, tint = Muted, modifier = Modifier.size(25.dp))
                    Text(if (recordings.isEmpty()) "No recordings yet" else "No recordings in this view", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (recordings.isEmpty()) "Record or upload audio from the Transcribe tab. It will appear here with its status and transcript."
                        else "Choose another filter to view your recordings.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { entry ->
                RecordingRow(item = entry, onClick = { onOpen(entry) })
            }
        }
        item(key = "footer") { Footer() }
    }
}

@Composable
private fun RecordingRow(item: RecordingItem, onClick: () -> Unit) {
    WorkspaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(BlueTint),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(Icons.Default.AudioFile, null, tint = Blue)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    sourceLabel(item) + " · " + formatDate(item.createdAt),
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall
                )
                if (item.isLocalOnly) {
                    Text("On this device · waiting to sync", color = Amber, style = MaterialTheme.typography.labelSmall)
                }
                if (!item.errorCode.isNullOrBlank()) {
                    Text(item.errorCode, color = Color(0xFFB33C46), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusPill(if (item.isLocalOnly) "local" else item.status)
                if (item.status == "succeeded" || item.isLocalOnly) {
                    OutlinedButton(onClick = onClick) {
                        Text("Open")
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesContent(notes: JSONObject) {
    val summary = notes.optString("executive_summary")
    if (summary.isNotBlank()) {
        Text("Executive summary", fontWeight = FontWeight.SemiBold)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    val groups = listOf(
        "Key points" to notes.optJSONArray("key_points"),
        "Decisions" to notes.optJSONArray("decisions"),
        "Action items" to notes.optJSONArray("action_items")
    )
    groups.forEach { (title, values) ->
        if (values != null && values.length() > 0) {
            Text(title, fontWeight = FontWeight.SemiBold)
            for (index in 0 until minOf(values.length(), 8)) {
                val item = values.optJSONObject(index) ?: continue
                val text = item.optString("text").ifBlank { item.optString("task") }
                if (text.isNotBlank()) Text("• " + text, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Border)
    ) {
        Row(
            Modifier.padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(31.dp).clip(RoundedCornerShape(9.dp)).background(BlueTint), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, null, tint = Blue, modifier = Modifier.size(16.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WorkspaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content
        )
    }
}

@Composable
private fun PanelHeading(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tag: String
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(35.dp).clip(RoundedCornerShape(9.dp)).background(BlueTint), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, tint = Blue, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(tag, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(BlueTint),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(Icons.Default.GraphicEq, null, tint = Blue, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StatusPill(status: String) {
    val (label, color, background) = when (status) {
        "succeeded", "local" -> Triple(if (status == "local") "On device" else "Completed", Green, GreenTint)
        "processing", "queued" -> Triple(if (status == "queued") "Queued" else "Processing", Blue, BlueTint)
        "upload_pending", "local_saved" -> Triple(if (status == "local_saved") "Sync pending" else "Upload incomplete", Amber, AmberTint)
        "failed", "cancelled" -> Triple(status.replaceFirstChar { it.uppercase() }, Color(0xFFB33C46), Color(0xFFFFEEEE))
        else -> Triple(status.replace('_', ' ').replaceFirstChar { it.uppercase() }, Muted, Color(0xFFF2F4F7))
    }
    Surface(shape = RoundedCornerShape(50), color = background) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoMessage(
    message: String,
    background: Color,
    foreground: Color,
    onDismiss: (() -> Unit)? = null
) {
    Surface(shape = RoundedCornerShape(9.dp), color = background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(message, modifier = Modifier.weight(1f), color = foreground, style = MaterialTheme.typography.bodySmall)
            if (onDismiss != null) {
                Text("Dismiss", modifier = Modifier.clickable(onClick = onDismiss), color = foreground, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Footer() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("MEETING INTELLIGENCE WORKSPACE", color = Muted, style = MaterialTheme.typography.labelSmall)
        Text("Private by design", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun sourceLabel(item: RecordingItem): String = when {
    item.isLocalOnly -> "Android · Local"
    item.source == "web_upload" || item.source == "web" -> "Web"
    item.source == "android_upload" || item.source == "android" -> "Android"
    item.source == "android_offline" -> "Android · Local"
    else -> "External device"
}

private fun formatDate(value: String): String = try {
    val parsed = Instant.parse(value)
    java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(java.util.Date.from(parsed))
} catch (_: Exception) {
    value.take(16).replace('T', ' ')
}

internal fun formatDuration(durationMs: Int): String {
    val totalSeconds = (durationMs / 1_000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun languageLabel(code: String): String = when (code) {
    "auto" -> "Auto detect"
    "en" -> "English"
    "hi" -> "Hindi"
    "zh" -> "Chinese"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    else -> code
}

