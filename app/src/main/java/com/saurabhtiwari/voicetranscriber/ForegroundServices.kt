package com.saurabhtiwari.voicetranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

private const val CHANNEL_RECORDING = "recording"
private const val CHANNEL_WORK = "background_work"

private fun ensureChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a meeting is being recorded."
        }
    )
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_WORK, "Background work", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while audio is transcribed or uploaded."
        }
    )
}

private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/**
 * Keeps the process in the foreground while a meeting is recorded, so recording continues
 * when the screen locks or the user switches apps. The microphone is only ever opened while
 * this service shows its notification.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RecordingController.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                try {
                    ensureChannels(this)
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                    if (!RecordingController.start(applicationContext)) shutDown()
                } catch (error: Exception) {
                    RecordingController.reportError(error.message ?: "Unable to start the recording service.")
                    shutDown()
                }
            }
            else -> stopSelf() // restarted by the system without an intent: nothing to resume
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // If the service is torn down while recording, finalise the file instead of leaving a corrupt one.
        if (RecordingController.state.value.active) RecordingController.stop()
        super.onDestroy()
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_meeting_workspace)
            .setContentTitle("Recording meeting")
            .setContentText("Tap to return to Meeting Workspace")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(this))
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.saurabhtiwari.voicetranscriber.action.START_RECORDING"
        const val ACTION_STOP = "com.saurabhtiwari.voicetranscriber.action.STOP_RECORDING"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            RecordingController.stop()
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

/** Process keep-alive for long uploads and on-device transcription. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ensureChannels(this)
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(intent.getStringExtra(EXTRA_TEXT) ?: "Working…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps dataSync services at about six hours; stop cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        KeepAlive.reset()
        stopSelf()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_meeting_workspace)
            .setContentTitle("Meeting Workspace")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(this))
            .build()

    companion object {
        const val EXTRA_TEXT = "text"
        private const val NOTIFICATION_ID = 4102
    }
}

/**
 * Reference-counted wrapper so overlapping jobs (an upload and a local transcription) share one
 * foreground service that stops only when the last job finishes.
 * Every [acquire] must be paired with exactly one [release] (use try/finally).
 */
object KeepAlive {
    private val holders = AtomicInteger(0)

    fun acquire(context: Context, text: String) {
        val app = context.applicationContext
        if (holders.getAndIncrement() == 0) {
            try {
                ContextCompat.startForegroundService(
                    app, Intent(app, KeepAliveService::class.java).putExtra(KeepAliveService.EXTRA_TEXT, text)
                )
            } catch (_: Exception) {
                // Not allowed to start from here (e.g. background restrictions): keep working anyway.
            }
        }
    }

    fun release(context: Context) {
        val app = context.applicationContext
        if (holders.updateAndGet { if (it > 0) it - 1 else 0 } == 0) {
            app.stopService(Intent(app, KeepAliveService::class.java))
        }
    }

    internal fun reset() {
        holders.set(0)
    }
}
