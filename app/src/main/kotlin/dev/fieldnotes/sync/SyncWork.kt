package dev.fieldnotes.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dev.fieldnotes.MainActivity
import dev.fieldnotes.R
import dev.fieldnotes.domain.AnalyticsEvent
import dev.fieldnotes.domain.NoteRepository
import dev.fieldnotes.domain.PreferencesRepository
import dev.fieldnotes.domain.SyncResult
import dev.fieldnotes.domain.SyncScheduler
import dev.fieldnotes.domain.Telemetry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FieldNotesSync"

class WorkSyncScheduler(private val context: Context, demo: Boolean) : SyncScheduler {
    private val workManager by lazy { WorkManager.getInstance(context) }
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(if (demo) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    override val backgroundSyncFailed = flow {
        emitAll(combine(
            workManager.getWorkInfosForUniqueWorkFlow(DRAIN_WORK),
            workManager.getWorkInfosForUniqueWorkFlow(WAKE_WORK),
        ) { drains, wakeups ->
            drains.all { it.state.isFinished } &&
                (drains.any { it.state == WorkInfo.State.FAILED } || wakeups.any { it.state == WorkInfo.State.FAILED })
        })
    }.catch { error ->
        Log.w(TAG, "Could not observe sync scheduling: ${error.javaClass.simpleName}")
        emit(true)
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun requestSync() = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<SyncWakeWorker>()
            .setInitialDelay(1, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        // Replace the signal only. A transfer may be waiting for a mutation acknowledgement.
        workManager.enqueueUniqueWork(WAKE_WORK, ExistingWorkPolicy.REPLACE, request).await()
        Unit
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork("notes-sync-now").await()
        workManager.cancelUniqueWork("notes-sync-periodic").await()
        val request = PeriodicWorkRequestBuilder<SyncWakeWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(HOURLY_WORK, ExistingPeriodicWorkPolicy.KEEP, request).await()
        requestSync()
    }

    internal suspend fun ensureDrainScheduled(): Boolean = withTimeoutOrNull(DRAIN_HANDOFF_TIMEOUT_MILLIS) {
        val active = workManager.getWorkInfosForUniqueWorkFlow(DRAIN_WORK).first()
        // A queued worker will take its outbox watermark after this signal.
        if (active.any { it.state == WorkInfo.State.ENQUEUED }) return@withTimeoutOrNull true
        val running = active.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            // KEEP would ignore a request while this drain still owns the unique name.
            // Observe its transition instead of waiting until WorkManager's next backoff window.
            val next = workManager.getWorkInfoByIdFlow(running.id).first { it?.state != WorkInfo.State.RUNNING }
            if (next?.state == WorkInfo.State.ENQUEUED) return@withTimeoutOrNull true
        }

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // A new drain racing this enqueue will also take its watermark after the signal.
        workManager.enqueueUniqueWork(DRAIN_WORK, ExistingWorkPolicy.KEEP, request).await()
        true
    } ?: false

    internal companion object {
        const val WAKE_WORK = "notes-sync-wake"
        const val DRAIN_WORK = "notes-sync-drain"
        private const val HOURLY_WORK = "notes-sync-hourly"
        // A production drain allows 120 seconds. Bound waiting too, with WorkManager retries
        // available for unusually delayed transitions and transient scheduler failures.
        private const val DRAIN_HANDOFF_TIMEOUT_MILLIS = 150_000L
    }
}

class SyncWakeWorker(
    context: Context, parameters: WorkerParameters,
    private val scheduler: WorkSyncScheduler,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val failure = try {
            if (scheduler.ensureDrainScheduled()) return Result.success()
            "Timed out waiting to schedule a drain"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            "Could not schedule drain: ${error.javaClass.simpleName}"
        }
        Log.w(TAG, failure)
        return if (unexpectedFailureDecision(runAttemptCount) == WorkDecision.RETRY) Result.retry() else Result.failure()
    }
}

class SyncWorker(
    context: Context, parameters: WorkerParameters,
    private val repository: NoteRepository,
    private val preferences: PreferencesRepository,
    private val telemetry: Telemetry,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val outcome = try {
            repository.sync().also {
                if (it is SyncResult.Success && it.morePending) scheduler.requestSync()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Sync failed: ${error.javaClass.simpleName}")
            val decision = unexpectedFailureDecision(runAttemptCount)
            publishFeedback(decision)
            return finish(decision)
        }
        val decision = outcome.workDecision(runAttemptCount)
        publishFeedback(decision, outcome)
        return finish(decision)
    }

    private suspend fun publishFeedback(decision: WorkDecision, outcome: SyncResult? = null) {
        try {
            when (decision) {
                WorkDecision.MORE_PENDING -> return
                WorkDecision.SYNCED, WorkDecision.CONFLICTS -> telemetry.record(AnalyticsEvent.SYNC_SUCCEEDED)
                else -> telemetry.record(AnalyticsEvent.SYNC_FAILED)
            }
            if (decision == WorkDecision.SYNCED && outcome is SyncResult.Success && outcome.uploaded > 0 &&
                preferences.preferences.first().notificationsEnabled) {
                notifySuccess()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Sync feedback failed: ${error.javaClass.simpleName}")
        }
    }

    private fun finish(decision: WorkDecision): Result = when (decision) {
        WorkDecision.RETRY -> Result.retry()
        WorkDecision.SYNCED, WorkDecision.CONFLICTS, WorkDecision.MUTATIONS_REJECTED, WorkDecision.MORE_PENDING ->
            Result.success(workDataOf(OUTCOME_KEY to decision.name))
        else -> Result.failure(workDataOf(OUTCOME_KEY to decision.name))
    }

    private fun notifySuccess() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(SYNC_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(SYNC_CHANNEL, "Synchronization", NotificationManager.IMPORTANCE_LOW))
        }
        val pending = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_field_notes).setContentTitle(applicationContext.getString(R.string.sync_notification_title))
            .setContentText(applicationContext.getString(R.string.sync_notification_body)).setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        // Keep a single current sync notification in the shade.
        manager.notify(SYNC_NOTIFICATION_ID, notification)
    }

    internal companion object {
        const val OUTCOME_KEY = "sync_outcome"
        private const val SYNC_CHANNEL = "sync"
        private const val SYNC_NOTIFICATION_ID = 42
    }
}

/** Explicit factory avoids a service locator inside the worker and enables test fakes. */
class SyncWorkerFactory(
    private val repository: () -> NoteRepository,
    private val preferences: () -> PreferencesRepository,
    private val telemetry: () -> Telemetry,
    private val scheduler: () -> WorkSyncScheduler,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        when (workerClassName) {
            SyncWorker::class.java.name ->
                SyncWorker(appContext, workerParameters, repository(), preferences(), telemetry(), scheduler())
            SyncWakeWorker::class.java.name -> SyncWakeWorker(appContext, workerParameters, scheduler())
            else -> null
        }
}
