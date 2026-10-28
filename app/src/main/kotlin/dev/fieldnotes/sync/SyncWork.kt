package dev.fieldnotes.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.fieldnotes.MainActivity
import dev.fieldnotes.R
import dev.fieldnotes.domain.AnalyticsEvent
import dev.fieldnotes.domain.NoteRepository
import dev.fieldnotes.domain.PreferencesRepository
import dev.fieldnotes.domain.SyncResult
import dev.fieldnotes.domain.SyncScheduler
import dev.fieldnotes.domain.Telemetry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class WorkSyncScheduler(private val context: Context, private val demo: Boolean) : SyncScheduler {
    private val constraints get() = Constraints.Builder()
        .setRequiredNetworkType(if (demo) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()

    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // APPEND_OR_REPLACE closes the 'edit arrives as worker finishes' gap in KEEP.
        WorkManager.getInstance(context).enqueueUniqueWork("notes-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("notes-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class SyncWorker(
    context: Context, parameters: WorkerParameters,
    private val repository: NoteRepository,
    private val preferences: PreferencesRepository,
    private val telemetry: Telemetry,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val outcome = repository.sync()
        telemetry.record(if (outcome is SyncResult.Success) AnalyticsEvent.SYNC_SUCCEEDED else AnalyticsEvent.SYNC_FAILED)
        if (outcome is SyncResult.Success && outcome.uploaded > 0 && outcome.conflicts == 0 && preferences.preferences.first().notificationsEnabled) {
            notifySuccess()
        }
        return when (outcome.workDecision(runAttemptCount)) {
            WorkDecision.SUCCESS -> Result.success()
            WorkDecision.RETRY -> Result.retry()
            WorkDecision.FAILURE -> Result.failure()
        }
    }

    private fun notifySuccess() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sync", "Synchronization", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "sync")
            .setSmallIcon(R.drawable.ic_field_notes).setContentTitle(applicationContext.getString(R.string.sync_notification_title))
            .setContentText(applicationContext.getString(R.string.sync_notification_body)).setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        manager.notify(42, notification)
    }
}

/** Explicit factory avoids a service locator inside the worker and enables test fakes. */
class SyncWorkerFactory(
    private val repository: () -> NoteRepository,
    private val preferences: () -> PreferencesRepository,
    private val telemetry: () -> Telemetry,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters, repository(), preferences(), telemetry()) else null
}
