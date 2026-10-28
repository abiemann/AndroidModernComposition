package dev.fieldnotes.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesPersistenceTest {
    @Test fun assignmentAndExplicitOverrideSurviveDataStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "experiment-${UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        try {
            val originalStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
            )
            val original = PreferenceDataStoreRepository(originalStore) { "installation-123" }
            val assigned = original.preferences.first()
            original.setCompactCards(!assigned.compactCards)
            job.cancelAndJoin()

            job = SupervisorJob()
            val reopenedStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
            )
            val reopened = PreferenceDataStoreRepository(reopenedStore) { error("Persisted identity must be reused") }
            val restored = reopened.preferences.first()
            assertEquals(assigned.compactCardsExperiment.variant, restored.compactCardsExperiment.variant)
            assertEquals(!assigned.compactCards, restored.compactCards)
            assertTrue(restored.compactCardsExperiment.isOverridden)
            reopened.resetCompactCardsOverride()
            assertEquals(assigned, reopened.preferences.first())
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
