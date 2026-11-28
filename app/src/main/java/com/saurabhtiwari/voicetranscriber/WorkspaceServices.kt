package com.saurabhtiwari.voicetranscriber

import android.content.Context
import java.io.File

/**
 * Process-wide dependencies shared by the UI, background workers and services.
 * (No DI framework: five singletons do not justify one.)
 */
class WorkspaceServices private constructor(context: Context) {
    val backend = WorkspaceBackend(context)
    val store = LocalMeetingStore(File(context.filesDir, "local_meetings"))

    /** Where in-app recordings are written (see RecordingController and LiveCaptureRecorder). */
    val recordingsDir = File(context.filesDir, "recordings")
    val audioLinks = AudioLinkStore(File(context.filesDir, "audio_links.json"))
    val speakerAliases = SpeakerAliasStore(File(context.filesDir, "speaker_aliases.json"))
    val sync = SyncCoordinator(backend, store)
    val transcriber = LocalTranscriber(context, store)
    val live = LiveStreamer(context, backend)

    companion object {
        @Volatile
        private var instance: WorkspaceServices? = null

        fun from(context: Context): WorkspaceServices =
            instance ?: synchronized(this) {
                instance ?: WorkspaceServices(context.applicationContext).also { instance = it }
            }
    }
}
