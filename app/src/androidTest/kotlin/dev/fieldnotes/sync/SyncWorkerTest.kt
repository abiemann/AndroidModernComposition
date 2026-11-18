package dev.fieldnotes.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.fieldnotes.domain.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    @Test fun transientNetworkFailureRetries() = runBlocking {
        assertEquals(ListenableWorker.Result.retry(), worker(SyncResult.RetryableFailure).doWork())
    }

    @Test fun authenticationOutcomeLetsAnAppendedRecoveryRun() = runBlocking {
        assertEquals(completed(WorkDecision.AUTHENTICATION_REQUIRED), worker(SyncResult.AuthenticationRequired).doWork())
    }

    @Test fun conflictIsDurableAndWorkerCompletes() = runBlocking {
        assertEquals(completed(WorkDecision.CONFLICTS), worker(SyncResult.Success(0, 1)).doWork())
    }

    private fun completed(decision: WorkDecision) =
        ListenableWorker.Result.success(workDataOf(SyncWorker.OUTCOME_KEY to decision.name))

    private fun worker(outcome: SyncResult): SyncWorker {
        val repository = object : NoteRepository {
            override fun observeNotes() = flowOf(emptyList<Note>())
            override fun observeNote(id: String) = flowOf<Note?>(null)
            override fun observeProjects() = flowOf(emptyList<Project>())
            override suspend fun save(draft: NoteDraft) = "test"
            override suspend fun delete(id: String) = Unit
            override suspend fun resolveConflict(id: String, keepLocal: Boolean) = Unit
            override suspend fun retryRejected(id: String) = Unit
            override suspend fun sync() = outcome
        }
        val preferences = object : PreferencesRepository {
            override val preferences = flowOf(Preferences())
            override suspend fun setCompactCards(enabled: Boolean) = Unit
            override suspend fun resetCompactCardsOverride() = Unit
            override suspend fun setAnalyticsEnabled(enabled: Boolean) = Unit
            override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
        }
        val telemetry = object : Telemetry { override fun record(event: AnalyticsEvent) = Unit }
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(SyncWorkerFactory({ repository }, { preferences }, { telemetry })).build()
    }
}
