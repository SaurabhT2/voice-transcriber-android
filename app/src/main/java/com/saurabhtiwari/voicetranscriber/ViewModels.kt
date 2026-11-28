package com.saurabhtiwari.voicetranscriber

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

enum class WorkspaceTab { OVERVIEW, TRANSCRIBE, HISTORY }
enum class TranscriptionMode { LOCAL, SHARED_GPU }
enum class HistoryFilter { ALL, PROCESSING, COMPLETED, ATTENTION }

/** Sign-in state. Survives rotation; the saved session itself lives in the encrypted store. */
class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val backend = WorkspaceServices.from(app).backend

    var session by mutableStateOf<UserSession?>(null)
        private set
    var restoring by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set

    private var started = false

    /** Idempotent: recreating the activity does not restore twice. */
    fun restore() {
        if (started) return
        started = true
        viewModelScope.launch {
            session = try {
                backend.restoreSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            restoring = false
        }
    }

    fun sendMagicLink(email: String) {
        viewModelScope.launch {
            busy = true
            error = ""
            message = ""
            try {
                backend.sendMagicLink(email)
                message = "A secure sign-in link has been sent. Open it on this phone to continue."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Unable to send the sign-in link."
            } finally {
                busy = false
            }
        }
    }

    fun completeSignIn(callback: Uri) {
        started = true
        restoring = true
        error = ""
        viewModelScope.launch {
            try {
                session = backend.completeMagicLink(callback)
                message = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The sign-in link could not be verified."
            } finally {
                restoring = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            backend.signOut()
            session = null
            message = ""
            error = ""
        }
    }

    fun onSessionExpired() {
        session = null
        error = "Your session expired. Please sign in again."
    }
}

/**
 * All workspace state and operations for one signed-in user. It outlives configuration
 * changes; long-running work that must survive the screen (recording, local transcription,
 * sync) lives in application-scoped components it merely observes.
 */
class MeetingViewModel(app: Application, private val ownerId: String) : AndroidViewModel(app) {
    private val appContext = app
    private val services = WorkspaceServices.from(app)
    private val backend = services.backend
    private val store = services.store
    private val transcriber = services.transcriber

    val modelState: StateFlow<ModelState> = transcriber.model
    val transcriptionState: StateFlow<TranscriptionState> = transcriber.state
    val liveState: StateFlow<LiveState> = services.live.state

    private val audioLinks = services.audioLinks
    private val speakerAliasStore = services.speakerAliases
    private val player = AudioPlayerController(viewModelScope)
    val playerState: StateFlow<PlayerState> = player.state

    var tab by mutableStateOf(WorkspaceTab.OVERVIEW)

    /** Stream the recording to the worker for live text (beta). Chosen before recording starts. */
    var liveTranscript by mutableStateOf(false)

    var history by mutableStateOf(emptyList<RecordingItem>())
        private set
    var historyLoading by mutableStateOf(false)
        private set
    var historyError by mutableStateOf("")
        private set
    var historyFilter by mutableStateOf(HistoryFilter.ALL)

    var searchQuery by mutableStateOf("")
        private set
    var searching by mutableStateOf(false)
        private set

    /** Meeting ids whose transcripts matched the current query on the server. */
    var remoteMatchIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Transcript text stored on this device, keyed by local event id and by meeting id. */
    var deviceText by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var selectedUri by mutableStateOf<Uri?>(null)
        private set
    var selectedFile by mutableStateOf<File?>(null)
        private set
    var selectedName by mutableStateOf("")
        private set
    var selectedContentType by mutableStateOf("audio/mp4")
        private set
    private var selectedStartedAt = Instant.now().toString()

    var recordingError by mutableStateOf("")
        private set
    var selectedMode by mutableStateOf(TranscriptionMode.LOCAL)
    var language by mutableStateOf("auto")
    var languageExpanded by mutableStateOf(false)
    var hint by mutableStateOf("Select a recording and choose where to transcribe it.")
        private set

    var cloudBusy by mutableStateOf(false)
        private set
    var cloudStatus by mutableStateOf("")
        private set
    var cloudJob by mutableStateOf<CloudJob?>(null)
        private set
    private var cloudRun: Job? = null
    private var activeCloudJobId: String? = null
    private var pendingLocalAudio: File? = null

    var currentMeeting by mutableStateOf<MeetingDetail?>(null)
        private set
    var currentLocalRecord by mutableStateOf<LocalMeetingRecord?>(null)
        private set
    var meetingNotes by mutableStateOf<JSONObject?>(null)
        private set

    /** Speaker names chosen on this device for the open meeting (server labels are never changed). */
    var speakerAliases by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var notesBusy by mutableStateOf(false)
        private set
    var meetingNotesStatus by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf("")
        private set

    /** Set when the session can no longer be refreshed; the screen reacts by signing out. */
    var requiresSignIn by mutableStateOf(false)
        private set

    private var remoteItems: List<RecordingItem> = emptyList()
    private var historyJob: Job? = null
    private var syncJob: Job? = null
    private var searchJob: Job? = null
    private var detailRequest = 0

    init {
        viewModelScope.launch { RecordingController.state.collect { onRecordingState(it) } }
        viewModelScope.launch { transcriber.saved.collect { onLocalTranscriptSaved(it) } }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    fun onScreenEntered() {
        refreshHistory()
        triggerSync()
    }

    fun acknowledgeSignInRequired() {
        requiresSignIn = false
    }

    fun dismissError() {
        errorMessage = ""
    }

    override fun onCleared() {
        player.release()
        transcriber.releaseModelIfIdle()
        super.onCleared()
    }

    // ---- recording -------------------------------------------------------------------------

    fun startRecording() {
        recordingError = ""
        tab = WorkspaceTab.TRANSCRIBE
        try {
            RecordingService.start(appContext, liveTranscript, if (language == "auto") null else language)
        } catch (error: Exception) {
            recordingError = error.message ?: "Unable to start recording. Check microphone access."
        }
    }

    fun stopRecording() = RecordingService.stop(appContext)

    fun pauseOrResumeRecording() {
        if (RecordingController.state.value.paused) RecordingController.resume() else RecordingController.pause()
    }

    fun reportRecordingError(message: String) {
        recordingError = message
    }

    private fun onRecordingState(state: RecordingState) {
        if (state.error.isNotBlank()) {
            recordingError = state.error
            RecordingController.clearError()
        }
        if (state.active) player.pause()
        val active = state.file
        if (state.active && active != null && selectedFile != active) {
            selectRecording(active, "Recording in progress.")
        }
        RecordingController.takeCompleted()?.let { finished ->
            selectRecording(finished, "Recording saved on this device. Choose Local or Shared GPU.")
        }
    }

    private fun selectRecording(file: File, message: String) {
        selectedFile = file
        selectedUri = Uri.fromFile(file)
        selectedName = file.name
        selectedContentType = "audio/mp4"
        selectedStartedAt = file.name.removePrefix("meeting_").removeSuffix(".m4a").toLongOrNull()
            ?.let { Instant.ofEpochMilli(it).toString() } ?: Instant.now().toString()
        resetResults()
        hint = message
    }

    // ---- selecting a file ------------------------------------------------------------------

    fun onAudioPicked(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        selectedUri = uri
        selectedFile = null
        selectedName = queryDisplayName(appContext, uri) ?: uri.lastPathSegment ?: "Selected recording"
        selectedContentType = resolveContentType(appContext, uri, selectedName)
        selectedStartedAt = Instant.now().toString()
        resetResults()
        hint = "Recording selected. Choose Local or Shared GPU."
    }

    fun clearSelectedRecording() {
        selectedUri = null
        selectedFile = null
        selectedName = ""
        resetResults()
        hint = "Select a recording to begin."
    }

    private fun resetResults() {
        transcriber.reset()
        currentMeeting = null
        currentLocalRecord = null
        cloudJob = null
        cloudStatus = ""
    }

    // ---- on-device transcription -----------------------------------------------------------

    fun prepareModel() {
        errorMessage = ""
        transcriber.prepareModel()
    }

    fun startLocalTranscription() {
        val uri = selectedUri ?: return
        if (transcriptionState.value.running) {
            transcriber.cancel()
            return
        }
        currentMeeting = null
        currentLocalRecord = null
        pendingLocalAudio = selectedFile
        transcriber.reset()
        transcriber.start(
            uri = uri,
            ownerId = ownerId,
            title = selectedName.ifBlank { "Local recording" },
            startedAt = selectedStartedAt,
            language = if (language == "auto") null else language
        )
    }

    fun cancelLocalTranscription() = transcriber.cancel()

    private fun onLocalTranscriptSaved(record: LocalMeetingRecord) {
        if (record.ownerId != ownerId) return
        val audio = pendingLocalAudio
        viewModelScope.launch {
            if (audio != null) {
                withContext(Dispatchers.IO) { audioLinks.link(AudioKeys.event(record.eventId), audio.name) }
            }
            showLocal(record)
            try {
                services.sync.syncPending()
                val meetingId = withContext(Dispatchers.IO) {
                    store.list(ownerId).firstOrNull { it.eventId == record.eventId }?.meetingId
                }
                if (meetingId != null) {
                    val meeting = backend.getMeeting(meetingId)
                    if (audio != null) {
                        withContext(Dispatchers.IO) { audioLinks.link(AudioKeys.meeting(meetingId), audio.name) }
                    }
                    showMeeting(meeting)
                    transcriber.postStatus("Transcription complete. Transcript synced to your workspace.")
                    tab = WorkspaceTab.HISTORY
                } else {
                    transcriber.postStatus("Transcript saved locally. Sync will retry when you are online.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                transcriber.postStatus("Transcript saved locally. Sync will retry when you are online.")
                errorMessage = describe(error, "")
            }
            refreshHistory()
        }
    }

    // ---- shared GPU transcription ----------------------------------------------------------

    fun startSharedGpuTranscription() {
        val uri = selectedUri ?: return
        if (cloudBusy) return
        val name = selectedName
        val existingFile = selectedFile
        val requestedLanguage = if (language == "auto") null else language
        cloudRun = viewModelScope.launch {
            cloudBusy = true
            activeCloudJobId = null
            cloudStatus = "Preparing secure upload…"
            cloudJob = null
            errorMessage = ""
            KeepAlive.acquire(appContext, "Uploading and transcribing")
            var stagedFile: File? = null
            try {
                val file = existingFile ?: copyRecordingToCache(appContext, uri, name).also { stagedFile = it }
                val contentType = resolveContentType(appContext, uri, name)
                var job = backend.createCloudUpload(
                    file = file,
                    fileName = name.ifBlank { file.name },
                    contentType = contentType,
                    language = requestedLanguage,
                    onJobCreated = { jobId ->
                        activeCloudJobId = jobId
                        // Link early: the job may finish while the app is closed, and its audio should still play.
                        existingFile?.let { recorded ->
                            viewModelScope.launch(Dispatchers.IO) { audioLinks.link(AudioKeys.job(jobId), recorded.name) }
                        }
                    }
                ) { sent, total ->
                    cloudStatus = "Uploading audio… " + (sent * 100L / total.coerceAtLeast(1L)) + "%"
                }
                cloudJob = job
                cloudStatus = "Upload complete. Waiting for the shared GPU worker…"
                refreshHistory()

                val backoff = PollBackoff()
                val deadline = SystemClock.elapsedRealtime() + MAX_CLOUD_WAIT_MS
                var failures = 0
                while (job.status in ACTIVE_JOB_STATUSES) {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        cloudStatus = "Still processing. You can leave this screen; the transcript will appear in My recordings when it is ready."
                        refreshHistory()
                        return@launch
                    }
                    delay(backoff.next())
                    try {
                        job = backend.getCloudJob(job.jobId)
                        failures = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (expired: SessionExpiredException) {
                        throw expired
                    } catch (network: IOException) {
                        // A network blip must not abandon a job that keeps running server-side.
                        if (++failures >= MAX_POLL_FAILURES) throw network
                        continue
                    }
                    cloudJob = job
                    cloudStatus = when (job.status) {
                        "queued" -> "Queued for transcription…"
                        "processing" -> "Transcribing on the shared GPU worker…"
                        "succeeded" -> "Transcription complete."
                        else -> job.status.replace('_', ' ')
                    }
                }
                val meetingId = job.meetingId
                if (job.status == "succeeded" && !meetingId.isNullOrBlank()) {
                    val meeting = backend.getMeeting(meetingId)
                    existingFile?.let { recorded ->
                        withContext(Dispatchers.IO) { audioLinks.link(AudioKeys.meeting(meetingId), recorded.name) }
                    }
                    meetingNotes = null
                    showMeeting(meeting, extraKeys = listOf(AudioKeys.job(job.jobId)))
                    tab = WorkspaceTab.HISTORY
                    refreshHistory()
                } else if (job.status == "cancelled") {
                    cloudStatus = "This transcription was cancelled. Your original audio remains on this device."
                    refreshHistory()
                } else if (job.status == "failed") {
                    errorMessage = job.errorCode ?: "Transcription failed. Your original audio remains on this device."
                } else {
                    errorMessage = "Transcription ended with status: " + job.status
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                cloudStatus = "Upload or transcription could not be completed."
                errorMessage = describe(error, "Unable to send this recording to the shared GPU worker.")
                refreshHistory()
            } finally {
                stagedFile?.delete()
                cloudBusy = false
                KeepAlive.release(appContext)
            }
        }
    }

    // ---- history ---------------------------------------------------------------------------

    fun refreshHistory() {
        if (historyJob?.isActive == true) return
        historyJob = viewModelScope.launch {
            historyLoading = true
            historyError = ""
            try {
                try {
                    remoteItems = backend.loadRecordings()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Keep showing the last known recordings plus anything still only on this device.
                    historyError = describe(error, "Unable to refresh recordings.")
                }
                val localOnly = localOnlyItems()
                deviceText = loadDeviceText()
                history = (remoteItems + localOnly).sortedByDescending { it.createdAt }
                if (localOnly.isNotEmpty()) triggerSync()
            } finally {
                historyLoading = false
            }
        }
    }

    private suspend fun localOnlyItems(): List<RecordingItem> = withContext(Dispatchers.IO) {
        store.pendingSync(ownerId).map { record ->
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
    }

    /** Uploads offline transcripts in the background; refreshes the list if any went through. */
    private fun triggerSync() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            val result = try {
                services.sync.syncPending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            if (result.retryableFailures > 0) SyncWorker.enqueue(appContext, keepIfPending = true)
            if (result.synced > 0) refreshHistory()
        }
    }

    fun openRecording(item: RecordingItem) {
        if (item.isLocalOnly) {
            viewModelScope.launch {
                val record = withContext(Dispatchers.IO) { store.list(ownerId).firstOrNull { it.eventId == item.id } }
                if (record != null) showLocal(record)
                meetingNotes = null
                meetingNotesStatus = ""
                tab = WorkspaceTab.HISTORY
            }
            return
        }
        val meetingId = item.meetingId ?: return
        viewModelScope.launch {
            errorMessage = ""
            try {
                val meeting = backend.getMeeting(meetingId)
                meetingNotes = null
                meetingNotesStatus = ""
                showMeeting(meeting, extraKeys = listOf(AudioKeys.job(item.id)))
                tab = WorkspaceTab.HISTORY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = describe(error, "Unable to open this transcript.")
            }
        }
    }

    // ---- open recording: audio, speaker names, close -----------------------------------------

    private fun showMeeting(meeting: MeetingDetail, extraKeys: List<String> = emptyList()) {
        currentMeeting = meeting
        currentLocalRecord = null
        onDetailChanged(AudioKeys.meeting(meeting.meetingId), meeting.title, extraKeys)
    }

    private fun showLocal(record: LocalMeetingRecord) {
        currentLocalRecord = record
        currentMeeting = null
        onDetailChanged(
            AudioKeys.event(record.eventId), record.title,
            listOfNotNull(record.meetingId?.let(AudioKeys::meeting))
        )
    }

    /** Loads the speaker names and, when the recording is still on this phone, its audio. */
    private fun onDetailChanged(key: String, title: String, extraKeys: List<String>) {
        val request = ++detailRequest
        speakerAliases = emptyMap()
        player.release()
        viewModelScope.launch {
            val (aliases, audio) = withContext(Dispatchers.IO) {
                speakerAliasStore.get(key) to audioLinks.resolve(services.recordingsDir, listOf(key) + extraKeys, title)
            }
            if (request != detailRequest) return@launch // another recording was opened meanwhile
            speakerAliases = aliases
            if (audio != null) player.load(audio)
        }
    }

    private fun currentKey(): String? =
        currentMeeting?.let { AudioKeys.meeting(it.meetingId) } ?: currentLocalRecord?.let { AudioKeys.event(it.eventId) }

    fun closeDetail() {
        detailRequest++
        player.release()
        currentMeeting = null
        currentLocalRecord = null
        meetingNotes = null
        meetingNotesStatus = ""
        speakerAliases = emptyMap()
    }

    // ---- playback --------------------------------------------------------------------------

    fun togglePlayback() {
        if (!RecordingController.state.value.active) player.toggle()
    }

    fun seekPlayback(positionMs: Int) = player.seekTo(positionMs)

    fun playFrom(startSeconds: Double) {
        if (!RecordingController.state.value.active) player.playFrom(startSeconds)
    }

    fun pausePlayback() = player.pause()

    // ---- search ----------------------------------------------------------------------------

    fun updateSearch(query: String) {
        searchQuery = query
        searchJob?.cancel()
        val term = query.trim()
        if (term.length < HistorySearch.MIN_REMOTE_QUERY_LENGTH) {
            remoteMatchIds = emptySet()
            searching = false
            return
        }
        searchJob = viewModelScope.launch {
            searching = true
            try {
                delay(SEARCH_DEBOUNCE_MS)
                remoteMatchIds = try {
                    backend.searchTranscripts(term)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    describe(error, "") // a lapsed session still routes the user to sign-in
                    emptySet()
                }
            } finally {
                if (isActive) searching = false // a newer search that replaced this one owns the flag
            }
        }
    }

    private suspend fun loadDeviceText(): Map<String, String> = withContext(Dispatchers.IO) {
        buildMap<String, String> {
            store.list(ownerId).forEach { record ->
                put(record.eventId, record.transcript)
                record.meetingId?.let { put(it, record.transcript) }
            }
        }
    }

    // ---- rename, edit, delete, export ------------------------------------------------------

    /** Runs an edit that changes stored data, reporting failures and refreshing the list afterwards. */
    private fun launchEdit(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            errorMessage = ""
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = describe(error, failure)
            }
            refreshHistory()
        }
    }

    fun renameCurrent(newTitle: String) {
        val title = newTitle.trim()
        val meeting = currentMeeting
        val local = currentLocalRecord
        if (title.isEmpty() || (meeting == null && local == null)) return
        launchEdit("Unable to rename this recording.") {
            if (meeting != null) {
                backend.renameMeeting(meeting.meetingId, title)
                if (currentMeeting?.meetingId == meeting.meetingId) currentMeeting = meeting.copy(title = title)
                history = history.map { if (it.meetingId == meeting.meetingId) it.copy(title = title) else it }
            } else if (local != null) {
                val renamed = withContext(Dispatchers.IO) { store.rename(ownerId, local.eventId, title) }
                if (renamed && currentLocalRecord?.eventId == local.eventId) currentLocalRecord = local.copy(title = title)
                history = history.map { if (it.id == local.eventId) it.copy(title = title) else it }
            }
        }
    }

    /** Corrects an on-device transcript that has not synced yet; the corrected text is what syncs. */
    fun editLocalTranscript(newText: String) {
        val local = currentLocalRecord ?: return
        val text = newText.trim()
        if (text.isEmpty()) return
        launchEdit("Unable to save the corrected transcript.") {
            val saved = withContext(Dispatchers.IO) { store.updateTranscript(ownerId, local.eventId, text) }
            if (saved) {
                if (currentLocalRecord?.eventId == local.eventId) currentLocalRecord = local.copy(transcript = text)
            } else {
                errorMessage = "This transcript has already synced to your workspace and can no longer be edited here."
            }
        }
    }

    fun renameSpeaker(speakerId: String, name: String) {
        val key = currentKey() ?: return
        val meeting = currentMeeting
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                speakerAliasStore.set(key, speakerId, name)
                speakerAliasStore.get(key)
            }
            if (currentKey() == key) speakerAliases = updated
            if (meeting != null) {
                try {
                    backend.setSpeakerDisplayName(meeting.meetingId, speakerId, name)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    errorMessage = "Name saved on this phone, but workspace sync failed: " +
                        (error.message ?: "Please try again.")
                }
            }
        }
    }

    fun deleteCurrent() {
        val meeting = currentMeeting
        val local = currentLocalRecord
        when {
            meeting != null -> deleteMeeting(meeting.meetingId, meeting.title, jobId = null)
            local != null -> deleteLocalRecord(local)
        }
    }

    fun deleteItem(item: RecordingItem) {
        val meetingId = item.meetingId
        when {
            item.isLocalOnly -> viewModelScope.launch {
                withContext(Dispatchers.IO) { store.find(ownerId, item.id) }?.let { deleteLocalRecord(it) }
            }
            meetingId != null -> deleteMeeting(meetingId, item.title, jobId = item.id)
            else -> launchEdit("Unable to remove this job.") {
                backend.deleteJob(item.id)
                // Removing a failed job never deletes the user's own recording; only the link goes.
                withContext(Dispatchers.IO) { audioLinks.unlink(AudioKeys.job(item.id)) }
            }
        }
    }

    private fun deleteMeeting(meetingId: String, title: String, jobId: String?) {
        launchEdit("Unable to delete this recording.") {
            backend.deleteMeeting(meetingId)
            if (currentMeeting?.meetingId == meetingId) closeDetail()
            val removedLocal = withContext(Dispatchers.IO) { store.deleteByMeetingId(ownerId, meetingId) }
            val keys = listOf(AudioKeys.meeting(meetingId)) +
                removedLocal.map { AudioKeys.event(it.eventId) } +
                listOfNotNull(jobId?.let(AudioKeys::job))
            purgeLocalCopies(keys, title)
            history = history.filterNot { it.meetingId == meetingId }
        }
    }

    private fun deleteLocalRecord(record: LocalMeetingRecord) {
        launchEdit("Unable to delete this transcript.") {
            if (currentLocalRecord?.eventId == record.eventId) closeDetail()
            withContext(Dispatchers.IO) { store.delete(ownerId, record.eventId) }
            purgeLocalCopies(listOf(AudioKeys.event(record.eventId)), record.title)
            history = history.filterNot { it.id == record.eventId }
        }
    }

    /** Removes speaker names and links for deleted transcripts, and audio that nothing else uses. */
    private suspend fun purgeLocalCopies(keys: List<String>, title: String?) = withContext(Dispatchers.IO) {
        keys.forEach(speakerAliasStore::clear)
        audioLinks.unlinkAndCollectOrphans(keys, title).forEach { name ->
            File(services.recordingsDir, name).takeIf { it.isFile }?.delete()
        }
    }

    /** The open recording as shareable text; null when nothing is open. */
    fun exportDocument(): ExportDocument? =
        currentMeeting?.let { TranscriptExport.documentFor(it, speakerAliases, meetingNotes) }
            ?: currentLocalRecord?.let { TranscriptExport.documentFor(it) }

    // ---- cancelling shared-GPU jobs --------------------------------------------------------

    /** Stops the upload or wait that is in progress and cancels the job on the server if it exists. */
    fun cancelSharedGpuTranscription() {
        if (!cloudBusy) return
        val jobId = activeCloudJobId
        cloudRun?.cancel()
        cloudStatus = "Cancelling…"
        launchEdit("Unable to cancel the job.") {
            cloudStatus = if (jobId == null) {
                "Cancelled before the upload finished."
            } else {
                backend.cancelCloudJob(jobId)
                "Cancelled. Your original audio remains on this device."
            }
        }
    }

    /** Cancels a queued, uploading or processing job listed in My recordings. */
    fun cancelJob(item: RecordingItem) {
        launchEdit("Unable to cancel this job.") {
            val job = backend.cancelCloudJob(item.id)
            if (cloudJob?.jobId == item.id) {
                cloudRun?.cancel()
                cloudJob = job
                cloudStatus = "Cancelled. Your original audio remains on this device."
            }
        }
    }

    // ---- meeting notes ---------------------------------------------------------------------

    fun loadNotes() = runNotes(generate = false)

    fun generateNotes() = runNotes(generate = true)

    private fun runNotes(generate: Boolean) {
        val meetingId = currentMeeting?.meetingId ?: return
        if (notesBusy) return
        viewModelScope.launch {
            notesBusy = true
            errorMessage = ""
            meetingNotesStatus = if (generate) "Queuing meeting notes on your PC…" else "Checking saved meeting notes…"
            try {
                var response = if (generate) {
                    backend.generateMeetingNotes(meetingId, regenerate = meetingNotes != null)
                } else {
                    val latest = backend.getMeetingNotesStatus(meetingId)
                    if (latest.optString("status") == "queued" || latest.optString("status") == "processing") {
                        latest
                    } else {
                        backend.getMeetingNotes(meetingId)
                    }
                }

                var notes = response.optJSONObject("notes")
                if (notes != null) {
                    meetingNotes = notes
                    meetingNotesStatus = "Saved notes are available across Android and web."
                } else {
                    val jobId = response.optString("job_id").takeIf { it.isNotBlank() && it != "null" }
                    var jobStatus = response.optString("status")
                    if (jobStatus == "succeeded") {
                        notes = backend.getMeetingNotes(meetingId).optJSONObject("notes")
                    } else if (jobId == null) {
                        throw IOException(
                            if (jobStatus == "not_started") "No saved notes exist yet. Generate notes to start."
                            else "The meeting-notes job was not returned by the server."
                        )
                    } else {
                        meetingNotesStatus = if (jobStatus == "queued") {
                            "Meeting notes are queued for your PC…"
                        } else {
                            "Your PC is generating meeting notes…"
                        }
                        for (attempt in 0 until NOTES_POLL_ATTEMPTS) {
                            delay(2_500)
                            response = backend.getMeetingNotesJob(meetingId, jobId)
                            jobStatus = response.optString("status")
                            when (jobStatus) {
                                "succeeded" -> {
                                    notes = backend.getMeetingNotes(meetingId).optJSONObject("notes")
                                    break
                                }
                                "failed" -> throw IOException(
                                    response.optString("error_code").ifBlank { "Meeting notes generation failed on the local PC." }
                                )
                                "queued" -> meetingNotesStatus = "Meeting notes are queued for your PC…"
                                "processing" -> meetingNotesStatus = "Your PC is generating meeting notes…"
                            }
                        }
                    }
                    meetingNotes = notes
                        ?: throw IOException("Meeting notes are still processing. Refresh this meeting to check again.")
                    meetingNotesStatus = "Notes saved to your shared workspace. They are available on web as well."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = describe(error, "Meeting notes could not be loaded.")
                meetingNotesStatus = ""
            } finally {
                notesBusy = false
            }
        }
    }

    private fun describe(error: Throwable, fallback: String): String {
        if (error is SessionExpiredException) requiresSignIn = true
        return error.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    companion object {
        private val ACTIVE_JOB_STATUSES = setOf("queued", "processing", "upload_pending")
        private const val MAX_CLOUD_WAIT_MS = 45L * 60L * 1000L
        private const val MAX_POLL_FAILURES = 5
        private const val NOTES_POLL_ATTEMPTS = 180
        private const val SEARCH_DEBOUNCE_MS = 350L

        fun factory(ownerId: String) = viewModelFactory {
            initializer { MeetingViewModel(this[APPLICATION_KEY] as Application, ownerId) }
        }
    }
}
