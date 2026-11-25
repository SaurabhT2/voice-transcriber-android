package com.saurabhtiwari.voicetranscriber

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class SyncResult(val synced: Int, val retryableFailures: Int, val permanentFailures: Int) {
    companion object {
        val NOTHING = SyncResult(0, 0, 0)
    }
}

/**
 * Uploads transcripts that were produced offline. One mutex serialises every caller (the
 * screen and the background worker), so a transcript is never sent twice concurrently.
 * Re-sending is also safe server-side because each record carries a stable client_event_id.
 */
class SyncCoordinator(
    private val backend: WorkspaceBackend,
    private val store: LocalMeetingStore
) {
    private val mutex = Mutex()

    suspend fun syncPending(): SyncResult = mutex.withLock {
        val ownerId = backend.savedUserId() ?: return@withLock SyncResult.NOTHING
        val pending = withContext(Dispatchers.IO) { store.pendingSync(ownerId) }
        var synced = 0
        var retryable = 0
        var permanent = 0
        for (record in pending) {
            try {
                val meetingId = backend.syncOfflineTranscript(
                    eventId = record.eventId,
                    title = record.title,
                    startedAt = record.startedAt,
                    endedAt = record.endedAt,
                    transcript = record.transcript,
                    language = record.language
                )
                withContext(Dispatchers.IO) { store.markSynced(ownerId, record.eventId, meetingId) }
                synced++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val kind = classifyFailure(error)
                if (kind == FailureKind.RETRYABLE) retryable++ else permanent++
                // No point waiting on one timeout per record when the device is offline,
                // and no point continuing once the session is gone.
                if (error is SessionExpiredException || (kind == FailureKind.RETRYABLE && error !is HttpException)) break
            }
        }
        SyncResult(synced, retryable, permanent)
    }
}

/** Retries pending syncs whenever the network is available, with exponential backoff. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = WorkspaceServices.from(applicationContext).sync.syncPending()
        return when {
            result.retryableFailures == 0 -> Result.success()
            runAttemptCount >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "sync-local-meetings"
        private const val MAX_ATTEMPTS = 8

        /**
         * @param keepIfPending true when a retry is already queued and another one would only
         * pile up behind it (used for opportunistic, UI-triggered retries).
         */
        fun enqueue(context: Context, keepIfPending: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_NAME,
                    if (keepIfPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
        }
    }
}
