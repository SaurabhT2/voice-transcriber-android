package com.saurabhtiwari.voicetranscriber

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.filled.Logout
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Locale

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
    private lateinit var backend: WorkspaceBackend
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionState by mutableStateOf<UserSession?>(null)
    private var restoringSession by mutableStateOf(true)
    private var authBusy by mutableStateOf(false)
    private var authMessage by mutableStateOf("")
    private var authError by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = WorkspaceBackend(this)
        setContent {
            WorkspaceTheme {
                when {
                    restoringSession -> LoadingScreen("Opening your workspace…")
                    sessionState == null -> SignInScreen(
                        busy = authBusy,
                        message = authMessage,
                        error = authError,
                        onSendLink = ::sendMagicLink
                    )
                    else -> MeetingWorkspaceScreen(
                        session = sessionState!!,
                        backend = backend,
                        onSignOut = ::signOut
                    )
                }
            }
        }

        val callback = intent?.data
        if (callback?.scheme == "meetingworkspace") {
            completeSignIn(callback)
        } else {
            activityScope.launch {
                sessionState = withContext(Dispatchers.IO) { backend.restoreSession() }
                restoringSession = false
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val callback = intent.data
        if (callback?.scheme == "meetingworkspace") completeSignIn(callback)
    }

    private fun sendMagicLink(email: String) {
        activityScope.launch {
            authBusy = true
            authError = ""
            authMessage = ""
            try {
                backend.sendMagicLink(email)
                authMessage = "A secure sign-in link has been sent. Open it on this device to continue."
            } catch (error: Exception) {
                authError = error.message ?: "Unable to send the sign-in link."
            } finally {
                authBusy = false
            }
        }
    }

    private fun completeSignIn(callback: Uri) {
        restoringSession = true
        authError = ""
        activityScope.launch {
            try {
                sessionState = backend.completeMagicLink(callback)
                authMessage = ""
            } catch (error: Exception) {
                sessionState = null
                authError = error.message ?: "The sign-in link could not be verified."
            } finally {
                restoringSession = false
            }
        }
    }

    private fun signOut() {
        activityScope.launch {
            try {
                backend.signOut(sessionState)
            } finally {
                sessionState = null
                authMessage = ""
                authError = ""
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
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

private enum class WorkspaceTab { OVERVIEW, TRANSCRIBE, HISTORY }
private enum class TranscriptionMode { LOCAL, SHARED_GPU }
private enum class HistoryFilter { ALL, PROCESSING, COMPLETED, ATTENTION }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingWorkspaceScreen(
    session: UserSession,
    backend: WorkspaceBackend,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localStore = remember(context) { LocalMeetingStore(context) }
    val recorder = remember(context) { MeetingAudioRecorder(context) }
    val modelFile = remember(context) { WhisperModelManager.modelFile(context) }

    var tab by remember { mutableStateOf(WorkspaceTab.OVERVIEW) }
    var history by remember { mutableStateOf(emptyList<RecordingItem>()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf("") }
    var historyFilter by remember { mutableStateOf(HistoryFilter.ALL) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var selectedContentType by remember { mutableStateOf("audio/mp4") }
    var selectedStartedAt by remember { mutableStateOf(Instant.now().toString()) }

    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var elapsedRecordingMs by remember { mutableLongStateOf(0L) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingError by remember { mutableStateOf("") }

    var selectedMode by remember { mutableStateOf(TranscriptionMode.LOCAL) }
    var language by remember { mutableStateOf("auto") }
    var languageExpanded by remember { mutableStateOf(false) }

    var whisperContext by remember { mutableStateOf<WhisperContext?>(null) }
    var modelLoading by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableFloatStateOf(0f) }
    var modelStatus by remember {
        mutableStateOf(if (modelFile.exists()) "Offline model downloaded. Load it when you are ready."
            else "Download a compact multilingual model for private on-device transcription.")
    }

    var localTranscribing by remember { mutableStateOf(false) }
    var localTranscript by remember { mutableStateOf("") }
    var transcriptionStatus by remember { mutableStateOf("Select a recording and choose where to transcribe it.") }
    var transcriptionProgress by remember { mutableFloatStateOf(0f) }
    var transcriptionJob by remember { mutableStateOf<Job?>(null) }

    var cloudBusy by remember { mutableStateOf(false) }
    var cloudStatus by remember { mutableStateOf("") }
    var cloudJob by remember { mutableStateOf<CloudJob?>(null) }
    var currentMeeting by remember { mutableStateOf<MeetingDetail?>(null) }
    var currentLocalRecord by remember { mutableStateOf<LocalMeetingRecord?>(null) }
    var meetingNotes by remember { mutableStateOf<JSONObject?>(null) }
    var notesBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            selectedUri = uri
            selectedFile = null
            selectedName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Selected recording"
            selectedContentType = resolveContentType(context, uri, selectedName)
            selectedStartedAt = Instant.now().toString()
            localTranscript = ""
            transcriptionStatus = "Recording selected. Choose Local or Shared GPU."
            currentMeeting = null
            currentLocalRecord = null
            cloudJob = null
            cloudStatus = ""
        }
    }

    suspend fun refreshHistory() {
        historyLoading = true
        historyError = ""
        try {
            val remote = backend.loadRecordings(session)
            val localOnly = localStore.pendingSync().map { record ->
                RecordingItem(
                    id = record.eventId,
                    title = record.title,
                    status = "local_saved",
                    source = "android_offline",
                    createdAt = record.createdAt,
                    meetingId = null,
                    transcriptId = null,
                    isLocalOnly = true
                )
            }
            history = (remote + localOnly).sortedByDescending { it.createdAt }
        } catch (error: Exception) {
            historyError = error.message ?: "Unable to refresh recordings."
            val localOnly = localStore.list().mapNotNull { record ->
                if (record.meetingId != null) null
                else RecordingItem(
                    id = record.eventId,
                    title = record.title,
                    status = "local_saved",
                    source = "android_offline",
                    createdAt = record.createdAt,
                    meetingId = null,
                    transcriptId = null,
                    isLocalOnly = true
                )
            }
            history = localOnly
        } finally {
            historyLoading = false
        }
    }

    LaunchedEffect(session.userId) {
        localStore.pendingSync().forEach { record ->
            try {
                val meetingId = backend.syncOfflineTranscript(
                    session = session,
                    eventId = record.eventId,
                    title = record.title,
                    startedAt = record.startedAt,
                    endedAt = record.endedAt,
                    transcript = record.transcript,
                    language = record.language
                )
                localStore.markSynced(record.eventId, meetingId)
            } catch (_: Exception) {
                // Keep the device copy and retry on a later app launch or explicit refresh.
            }
        }
        refreshHistory()
    }

    LaunchedEffect(isRecording, isPaused) {
        while (isRecording) {
            delay(1_000)
            if (!isPaused) elapsedRecordingMs += 1_000L
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) recorder.release()
            val modelToRelease = whisperContext
            modelToRelease?.let { loaded ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loaded.release() }
            }
        }
    }

    fun clearSelectedRecording() {
        selectedUri = null
        selectedFile = null
        selectedName = ""
        localTranscript = ""
        transcriptionStatus = "Select a recording to begin."
        currentMeeting = null
        currentLocalRecord = null
        cloudJob = null
        cloudStatus = ""
    }

    fun startRecordingNow() {
        try {
            val file = recorder.start()
            recordingFile = file
            selectedFile = file
            selectedUri = Uri.fromFile(file)
            selectedName = file.name
            selectedContentType = "audio/mp4"
            selectedStartedAt = Instant.now().toString()
            elapsedRecordingMs = 0L
            isRecording = true
            isPaused = false
            recordingError = ""
            localTranscript = ""
            transcriptionStatus = "Recording in progress."
            tab = WorkspaceTab.TRANSCRIBE
        } catch (error: Exception) {
            recordingError = error.message ?: "Unable to start recording. Check microphone access."
        }
    }

    fun stopRecordingNow() {
        val file = recorder.stop()
        isRecording = false
        isPaused = false
        if (file == null) {
            recordingError = "The recording was too short or could not be finalized. Please record again."
            recordingFile = null
            return
        }
        recordingFile = file
        selectedFile = file
        selectedUri = Uri.fromFile(file)
        selectedName = file.name
        selectedContentType = "audio/mp4"
        transcriptionStatus = "Recording saved on this device. Choose Local or Shared GPU."
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecordingNow()
        else recordingError = "Microphone access is required to record a meeting."
    }

    fun requestRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startRecordingNow()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun loadLocalModel() {
        if (modelLoading || whisperContext != null) return
        scope.launch {
            modelLoading = true
            modelProgress = 0f
            errorMessage = ""
            try {
                val file = WhisperModelManager.download(context) { downloaded, total ->
                    withContext(Dispatchers.Main) {
                        modelProgress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        modelStatus = if (total > 0) {
                            "Downloading offline model: " + (modelProgress * 100).toInt() + "%"
                        } else {
                            "Downloading offline model: " + (downloaded / (1024L * 1024L)) + " MB"
                        }
                    }
                }
                modelStatus = "Loading local language model…"
                val loaded = withContext(Dispatchers.IO) {
                    WhisperContext.createContextFromFile(file.absolutePath)
                }
                whisperContext = loaded
                modelStatus = "Local model is ready. Transcription stays on this phone."
            } catch (error: Exception) {
                modelStatus = error.message ?: "Unable to prepare the local model."
            } catch (error: LinkageError) {
                modelStatus = error.message ?: "Native model runtime is unavailable on this device."
            } finally {
                modelLoading = false
            }
        }
    }

    fun startLocalTranscription() {
        val uri = selectedUri ?: return
        val engine = whisperContext ?: return
        if (localTranscribing) {
            transcriptionJob?.cancel()
            return
        }
        localTranscript = ""
        transcriptionStatus = "Preparing audio…"
        transcriptionProgress = 0f
        localTranscribing = true
        currentMeeting = null
        currentLocalRecord = null
        transcriptionJob = scope.launch {
            val output = StringBuilder()
            var chunksProcessed = 0
            var totalDurationMs = 0L
            try {
                AndroidAudioDecoder.decodeToChunks(
                    context = context,
                    uri = uri,
                    onDuration = { duration ->
                        totalDurationMs = duration
                        withContext(Dispatchers.Main) {
                            transcriptionStatus = "Preparing " + formatDuration(duration.toInt()) + " of audio…"
                        }
                    },
                    onChunk = { samples, offsetSeconds ->
                        chunksProcessed += 1
                        withContext(Dispatchers.Main) {
                            transcriptionStatus = "Transcribing audio section " + chunksProcessed + "…"
                            if (totalDurationMs > 0L) {
                                transcriptionProgress = (offsetSeconds.toFloat() / (totalDurationMs / 1000f)).coerceIn(0f, 0.98f)
                            }
                        }
                        val recognized = withContext(Dispatchers.Default) {
                            engine.transcribeData(samples, printTimestamp = false).trim()
                        }
                        if (recognized.isNotBlank()) {
                            if (output.isNotEmpty()) output.append("\n\n")
                            output.append(recognized)
                            withContext(Dispatchers.Main) { localTranscript = output.toString() }
                        }
                    }
                )
                val transcriptText = output.toString().trim()
                if (transcriptText.isBlank()) {
                    transcriptionStatus = "Processing complete. No speech was detected."
                } else {
                    val endedAt = Instant.now().toString()
                    val saved = localStore.save(
                        title = selectedName.ifBlank { "Local recording" },
                        transcript = transcriptText,
                        startedAt = selectedStartedAt,
                        endedAt = endedAt,
                        language = if (language == "auto") null else language
                    )
                    currentLocalRecord = saved
                    transcriptionProgress = 1f
                    transcriptionStatus = "Saved on this device. Syncing transcript to your workspace…"
                    try {
                        val meetingId = backend.syncOfflineTranscript(
                            session = session,
                            eventId = saved.eventId,
                            title = saved.title,
                            startedAt = saved.startedAt,
                            endedAt = saved.endedAt,
                            transcript = saved.transcript,
                            language = saved.language
                        )
                        localStore.markSynced(saved.eventId, meetingId)
                        currentMeeting = backend.getMeeting(session, meetingId)
                        currentLocalRecord = null
                        transcriptionStatus = "Transcription complete. Transcript synced to your workspace."
                        tab = WorkspaceTab.HISTORY
                    } catch (syncError: Exception) {
                        transcriptionStatus = "Transcript saved locally. Sync will retry when you are online."
                        errorMessage = syncError.message ?: ""
                    }
                    refreshHistory()
                }
            } catch (cancelled: CancellationException) {
                transcriptionStatus = "Transcription cancelled."
                throw cancelled
            } catch (error: Exception) {
                transcriptionStatus = error.message ?: "Unable to transcribe this recording locally."
            } finally {
                localTranscribing = false
                transcriptionJob = null
            }
        }
    }

    fun startSharedGpuTranscription() {
        val uri = selectedUri ?: return
        if (cloudBusy) return
        scope.launch {
            cloudBusy = true
            cloudStatus = "Preparing secure upload…"
            cloudJob = null
            errorMessage = ""
            var stagedFile: File? = null
            try {
                val file = selectedFile ?: copyRecordingToCache(context, uri, selectedName)
                    .also { stagedFile = it }
                val contentType = resolveContentType(context, uri, selectedName)
                var job = backend.createCloudUpload(
                    session = session,
                    file = file,
                    fileName = selectedName.ifBlank { file.name },
                    contentType = contentType,
                    language = if (language == "auto") null else language
                )
                cloudJob = job
                cloudStatus = "Upload complete. Waiting for the shared GPU worker…"
                refreshHistory()

                while (job.status == "queued" || job.status == "processing" || job.status == "upload_pending") {
                    delay(4_000)
                    job = backend.getCloudJob(session, job.jobId)
                    cloudJob = job
                    cloudStatus = when (job.status) {
                        "queued" -> "Queued for transcription…"
                        "processing" -> "Transcribing on the shared GPU worker…"
                        "succeeded" -> "Transcription complete."
                        else -> job.status.replace('_', ' ')
                    }
                }
                if (job.status == "succeeded" && !job.meetingId.isNullOrBlank()) {
                    currentMeeting = backend.getMeeting(session, job.meetingId)
                    currentLocalRecord = null
                    meetingNotes = null
                    tab = WorkspaceTab.HISTORY
                    refreshHistory()
                } else if (job.status == "failed") {
                    errorMessage = job.errorCode ?: "Transcription failed. Your original audio remains on this device."
                } else {
                    errorMessage = "Transcription ended with status: " + job.status
                }
            } catch (error: Exception) {
                cloudStatus = "Upload or transcription could not be completed."
                errorMessage = error.message ?: "Unable to send this recording to the shared GPU worker."
                refreshHistory()
            } finally {
                stagedFile?.delete()
                cloudBusy = false
            }
        }
    }

    fun openRecording(item: RecordingItem) {
        if (item.isLocalOnly) {
            currentLocalRecord = localStore.list().firstOrNull { it.eventId == item.id }
            currentMeeting = null
            meetingNotes = null
            tab = WorkspaceTab.HISTORY
            return
        }
        val meetingId = item.meetingId ?: return
        scope.launch {
            errorMessage = ""
            try {
                currentMeeting = backend.getMeeting(session, meetingId)
                currentLocalRecord = null
                meetingNotes = null
                tab = WorkspaceTab.HISTORY
            } catch (error: Exception) {
                errorMessage = error.message ?: "Unable to open this transcript."
            }
        }
    }

    fun refreshNow() {
        scope.launch { refreshHistory() }
    }

    fun runNotes(generate: Boolean) {
        val meetingId = currentMeeting?.meetingId ?: return
        scope.launch {
            notesBusy = true
            errorMessage = ""
            try {
                val response = if (generate) backend.generateMeetingNotes(session, meetingId)
                    else backend.getMeetingNotes(session, meetingId)
                meetingNotes = response.optJSONObject("notes")
                    ?: throw IOException("No saved meeting notes were returned.")
            } catch (error: Exception) {
                errorMessage = error.message ?: "Meeting notes could not be loaded."
            } finally {
                notesBusy = false
            }
        }
    }

    val completedCount = history.count { it.status == "succeeded" }
    val processingCount = history.count { it.status == "queued" || it.status == "processing" }
    val attentionCount = history.count { it.status == "failed" || it.status == "upload_pending" || it.isLocalOnly }

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
                        androidx.compose.material3.Icon(Icons.Default.Logout, contentDescription = "Sign out", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = tab == WorkspaceTab.OVERVIEW,
                    onClick = { tab = WorkspaceTab.OVERVIEW },
                    icon = { androidx.compose.material3.Icon(Icons.Default.GraphicEq, null) },
                    label = { Text("Overview") }
                )
                NavigationBarItem(
                    selected = tab == WorkspaceTab.TRANSCRIBE,
                    onClick = { tab = WorkspaceTab.TRANSCRIBE },
                    icon = { androidx.compose.material3.Icon(Icons.Default.UploadFile, null) },
                    label = { Text("Transcribe") }
                )
                NavigationBarItem(
                    selected = tab == WorkspaceTab.HISTORY,
                    onClick = { tab = WorkspaceTab.HISTORY; refreshNow() },
                    icon = { androidx.compose.material3.Icon(Icons.Default.LibraryMusic, null) },
                    label = { Text("My recordings") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (errorMessage.isNotBlank()) {
                InfoMessage(errorMessage, Color(0xFFFFEEEE), Color(0xFFB33C46), onDismiss = { errorMessage = "" })
            }
            when (tab) {
                WorkspaceTab.OVERVIEW -> OverviewContent(
                    email = session.email,
                    recordingCount = history.size,
                    completedCount = completedCount,
                    processingCount = processingCount,
                    attentionCount = attentionCount,
                    onRecord = { requestRecording() },
                    onUpload = { tab = WorkspaceTab.TRANSCRIBE; audioPicker.launch(arrayOf("audio/*", "video/mp4")) },
                    onTranscribe = { tab = WorkspaceTab.TRANSCRIBE },
                    onHistory = { tab = WorkspaceTab.HISTORY; refreshNow() }
                )
                WorkspaceTab.TRANSCRIBE -> TranscribeContent(
                    selectedName = selectedName,
                    selectedContentType = selectedContentType,
                    selectedMode = selectedMode,
                    onModeChange = { selectedMode = it },
                    isRecording = isRecording,
                    isPaused = isPaused,
                    elapsedRecordingMs = elapsedRecordingMs,
                    recordingError = recordingError,
                    onRecord = { requestRecording() },
                    onPauseResume = {
                        try {
                            if (isPaused) recorder.resume() else recorder.pause()
                            isPaused = !isPaused
                        } catch (error: Exception) {
                            recordingError = error.message ?: "Unable to update the recording."
                        }
                    },
                    onStopRecording = { stopRecordingNow() },
                    onChooseFile = { audioPicker.launch(arrayOf("audio/*", "video/mp4")) },
                    onClearFile = { clearSelectedRecording() },
                    language = language,
                    languageExpanded = languageExpanded,
                    onLanguageExpanded = { languageExpanded = it },
                    onLanguageChange = { language = it },
                    modelStatus = modelStatus,
                    modelLoading = modelLoading,
                    modelProgress = modelProgress,
                    modelReady = whisperContext != null,
                    onPrepareModel = { loadLocalModel() },
                    localTranscribing = localTranscribing,
                    transcriptionStatus = transcriptionStatus,
                    transcriptionProgress = transcriptionProgress,
                    localTranscript = localTranscript,
                    cloudBusy = cloudBusy,
                    cloudStatus = cloudStatus,
                    cloudJob = cloudJob,
                    onLocalTranscribe = { startLocalTranscription() },
                    onCloudTranscribe = { startSharedGpuTranscription() },
                    onCancelLocal = { transcriptionJob?.cancel() },
                    canTranscribe = selectedUri != null
                )
                WorkspaceTab.HISTORY -> HistoryContent(
                    items = history,
                    filter = historyFilter,
                    onFilterChange = { historyFilter = it },
                    loading = historyLoading,
                    error = historyError,
                    onRefresh = { refreshNow() },
                    onOpen = ::openRecording,
                    currentMeeting = currentMeeting,
                    currentLocalRecord = currentLocalRecord,
                    meetingNotes = meetingNotes,
                    notesBusy = notesBusy,
                    onLoadNotes = { runNotes(false) },
                    onGenerateNotes = { runNotes(true) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Footer()
        }
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

@Composable
private fun HistoryContent(
    items: List<RecordingItem>,
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
    onLoadNotes: () -> Unit,
    onGenerateNotes: () -> Unit
) {
    val completed = items.count { it.status == "succeeded" }
    val processing = items.count { it.status == "queued" || it.status == "processing" }
    val attention = items.count { it.status == "failed" || it.status == "upload_pending" || it.isLocalOnly }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("All", items.size.toString(), Icons.Default.LibraryMusic, Modifier.weight(1f))
            StatCard("Ready", completed.toString(), Icons.Default.CheckCircle, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Processing", processing.toString(), Icons.Default.AccessTime, Modifier.weight(1f))
            StatCard("Attention", attention.toString(), Icons.Default.Refresh, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryFilter.entries.forEach { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { onFilterChange(item) },
                    label = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        if (error.isNotBlank()) InfoMessage(error, AmberTint, Amber)
        if (loading && items.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Blue)
            }
        } else {
            val filtered = items.filter { item ->
                when (filter) {
                    HistoryFilter.ALL -> true
                    HistoryFilter.PROCESSING -> item.status == "queued" || item.status == "processing"
                    HistoryFilter.COMPLETED -> item.status == "succeeded"
                    HistoryFilter.ATTENTION -> item.status == "failed" || item.status == "upload_pending" || item.isLocalOnly
                }
            }
            if (filtered.isEmpty()) {
                WorkspaceCard {
                    androidx.compose.material3.Icon(Icons.Default.AudioFile, null, tint = Muted, modifier = Modifier.size(25.dp))
                    Text(if (items.isEmpty()) "No recordings yet" else "No recordings in this view", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (items.isEmpty()) "Record or upload audio from the Transcribe tab. It will appear here with its status and transcript."
                        else "Choose another filter to view your recordings.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                filtered.forEach { item ->
                    RecordingRow(item = item, onClick = { onOpen(item) })
                }
            }
        }

        if (currentMeeting != null) {
            WorkspaceCard {
                PanelHeading(Icons.Default.AudioFile, currentMeeting.title, "Transcript and meeting intelligence.", "MEETING DETAIL")
                Text(
                    currentMeeting.transcript.ifBlank { "Transcript unavailable." },
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium
                )
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
                        else Text("Generate notes")
                    }
                }
                meetingNotes?.let { NotesContent(it) }
            }
        } else if (currentLocalRecord != null) {
            WorkspaceCard {
                PanelHeading(Icons.Default.Lock, currentLocalRecord.title, "Saved privately on this device.", "LOCAL TRANSCRIPT")
                Text(currentLocalRecord.transcript, color = Ink, style = MaterialTheme.typography.bodyMedium)
                InfoMessage("This transcript has not synced to your online workspace yet. It will retry when connectivity is available.", AmberTint, Amber)
            }
        }
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
                    OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) {
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
private fun WorkspaceCard(content: @Composable Column.() -> Unit) {
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

private fun formatDuration(durationMs: Int): String {
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

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private fun resolveContentType(context: Context, uri: Uri, name: String): String {
    val reported = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT).orEmpty()
    if (reported.startsWith("audio/")) return reported
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "mp3" -> "audio/mpeg"
        "mp4", "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "3gp" -> "audio/3gpp"
        else -> "application/octet-stream"
    }
}
