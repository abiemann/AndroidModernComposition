package dev.fieldnotes.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.fieldnotes.domain.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    @Test fun transientNetworkFailureRetries() = runBlocking {
        assertEquals(ListenableWorker.Result.retry(), worker(SyncResult.RetryableFailure).doWork())
    }

    @Test fun authenticationRequirementIsARealWorkFailure() = runBlocking {
        assertEquals(failed(WorkDecision.AUTHENTICATION_REQUIRED), worker(SyncResult.AuthenticationRequired).doWork())
    }

    @Test fun conflictIsDurableAndWorkerCompletes() = runBlocking {
        assertEquals(completed(WorkDecision.CONFLICTS), worker(SyncResult.Success(0, 1)).doWork())
    }

    @Test fun unexpectedFailuresRetryThenReportFailure() = runBlocking {
        val fail: suspend () -> SyncResult = { error("Room failure") }
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 4, sync = fail).doWork())
        assertEquals(failed(WorkDecision.UNEXPECTED_FAILURE), worker(attempt = 5, sync = fail).doWork())
    }

    @Test fun boundedPassPersistsAContinuationBeforeCompleting() = runBlocking {
        var requests = 0
        val worker = worker(scheduler = SyncScheduler { requests++ }) {
            SyncResult.Success(8, 0, morePending = true)
        }
        assertEquals(completed(WorkDecision.MORE_PENDING), worker.doWork())
        assertEquals(1, requests)
    }

    @Test fun workManagerCoalescesSavesAndPreservesASignalDuringARunningDrain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Keep the production instance separate from WorkManager's test delegate.
        WorkManager.getInstance(context)
        val scheduler = WorkSyncScheduler(context, demo = true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val passes = AtomicInteger()
        val repository = repository {
            if (passes.incrementAndGet() == 1) {
                started.complete(Unit)
                release.await()
                SyncResult.AuthenticationRequired
            } else {
                SyncResult.Success(0, 0)
            }
        }
        val factory = SyncWorkerFactory({ repository }, { preferences }, { telemetry }, { scheduler })
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setWorkerFactory(factory).build(),
        )
        val manager = WorkManager.getInstance(context)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))

        suspend fun waitForWork(name: String, predicate: (WorkInfo) -> Boolean): WorkInfo =
            withTimeout(15_000) {
                manager.getWorkInfosForUniqueWorkFlow(name).first { work -> work.singleOrNull()?.let(predicate) == true }
                    .single()
            }

        try {
            scheduler.requestSync()
            val original = waitForWork(WorkSyncScheduler.WAKE_WORK) { it.state == WorkInfo.State.ENQUEUED }
            repeat(10) { scheduler.requestSync() }
            val coalesced = waitForWork(WorkSyncScheduler.WAKE_WORK) { it.state == WorkInfo.State.ENQUEUED }
            assertNotEquals(original.id, coalesced.id)
            driver.setInitialDelayMet(coalesced.id)
            val drain = waitForWork(WorkSyncScheduler.DRAIN_WORK) { it.state == WorkInfo.State.ENQUEUED }
            driver.setAllConstraintsMet(drain.id)
            withTimeout(15_000) { started.await() }

            scheduler.requestSync()
            val followUp = waitForWork(WorkSyncScheduler.WAKE_WORK) { it.state == WorkInfo.State.ENQUEUED }
            val wake = TestListenableWorkerBuilder<SyncWakeWorker>(context).setWorkerFactory(factory).build()
            assertEquals(ListenableWorker.Result.retry(), wake.doWork())
            assertEquals(drain.id, waitForWork(WorkSyncScheduler.DRAIN_WORK) { it.state == WorkInfo.State.RUNNING }.id)
            assertEquals(1, passes.get())

            release.complete(Unit)
            waitForWork(WorkSyncScheduler.DRAIN_WORK) { it.state == WorkInfo.State.FAILED }
            assertEquals(true, withTimeout(15_000) { scheduler.backgroundSyncFailed.first() })
            driver.setInitialDelayMet(followUp.id)
            val next = waitForWork(WorkSyncScheduler.DRAIN_WORK) { it.state == WorkInfo.State.ENQUEUED }
            assertNotEquals(drain.id, next.id)
            driver.setAllConstraintsMet(next.id)
            waitForWork(WorkSyncScheduler.DRAIN_WORK) { it.state == WorkInfo.State.SUCCEEDED }
            assertEquals(false, withTimeout(15_000) { scheduler.backgroundSyncFailed.first() })
            assertEquals(2, passes.get())
        } finally {
            release.complete(Unit)
            withTimeout(15_000) {
                manager.cancelUniqueWork(WorkSyncScheduler.WAKE_WORK).await()
                manager.cancelUniqueWork(WorkSyncScheduler.DRAIN_WORK).await()
            }
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    private fun completed(decision: WorkDecision) =
        ListenableWorker.Result.success(workDataOf(SyncWorker.OUTCOME_KEY to decision.name))

    private fun failed(decision: WorkDecision) =
        ListenableWorker.Result.failure(workDataOf(SyncWorker.OUTCOME_KEY to decision.name))

    private fun worker(outcome: SyncResult) = worker { outcome }

    private fun worker(
        attempt: Int = 0,
        scheduler: SyncScheduler = SyncScheduler {},
        sync: suspend () -> SyncResult,
    ): SyncWorker = TestListenableWorkerBuilder<SyncWorker>(ApplicationProvider.getApplicationContext())
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncWorker(appContext, workerParameters, repository(sync), preferences, telemetry, scheduler)
        }).build()

    private fun repository(runSync: suspend () -> SyncResult) = object : NoteRepository {
        override fun observeNotes() = flowOf(emptyList<Note>())
        override fun observeNote(id: String) = flowOf<Note?>(null)
        override fun observeProjects() = flowOf(emptyList<Project>())
        override suspend fun save(draft: NoteDraft) = "test"
        override suspend fun delete(id: String) = Unit
        override suspend fun resolveConflict(id: String, keepLocal: Boolean) = Unit
        override suspend fun retryRejected(id: String) = Unit
        override suspend fun sync() = runSync()
    }

    private val preferences = object : PreferencesRepository {
        override val preferences = flowOf(Preferences())
        override suspend fun setCompactCards(enabled: Boolean) = Unit
        override suspend fun resetCompactCardsOverride() = Unit
        override suspend fun setAnalyticsEnabled(enabled: Boolean) = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    }

    private val telemetry = object : Telemetry { override suspend fun record(event: AnalyticsEvent) = Unit }
}
