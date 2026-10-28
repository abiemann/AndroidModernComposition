package dev.fieldnotes.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.fieldnotes.domain.CompactCardsExperiment
import dev.fieldnotes.domain.ExperimentVariant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreferenceDataStoreRepositoryTest {
    @Test fun concurrentCollectorsCreateOnePersistedAssignment() = runTest {
        val store = MemoryStore()
        var creations = 0
        val repository = PreferenceDataStoreRepository(store) { creations++; "installation-123" }
        val results = List(10) { async { repository.preferences.first() } }.awaitAll()
        assertEquals(1, creations)
        assertTrue(results.all { it.compactCardsExperiment == results.first().compactCardsExperiment })
        val recreated = PreferenceDataStoreRepository(store) { error("An identity already exists") }
        assertEquals(results.first(), recreated.preferences.first())
    }

    @Test fun overrideIsExplicitAndResetRestoresOriginalAssignment() = runTest {
        val store = MemoryStore()
        val repository = PreferenceDataStoreRepository(store) { "installation-123" }
        val assigned = repository.preferences.first()
        assertFalse(assigned.compactCardsExperiment.isOverridden)
        repository.setCompactCards(!assigned.compactCards)
        val overridden = repository.preferences.first()
        assertEquals(!assigned.compactCards, overridden.compactCards)
        assertTrue(overridden.compactCardsExperiment.isOverridden)
        assertEquals(assigned.compactCardsExperiment.variant, overridden.compactCardsExperiment.variant)
        repository.resetCompactCardsOverride()
        assertEquals(assigned, repository.preferences.first())
    }

    @Test fun assignedArmControlsEffectiveFlagWithoutOptingIntoAnalytics() = runTest {
        val repository = PreferenceDataStoreRepository(MemoryStore()) { "installation-123" }
        val state = repository.preferences.first()
        assertEquals(CompactCardsExperiment.assign("installation-123") == ExperimentVariant.COMPACT, state.compactCards)
        assertFalse(state.analyticsEnabled)
        assertFalse(state.notificationsEnabled)
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = lock.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
