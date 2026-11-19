package dev.fieldnotes.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.fieldnotes.domain.CompactCardsExperiment
import dev.fieldnotes.domain.ExperimentVariant
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
        assertEquals(1, store.writeAttempts)
    }

    @Test fun initializedReadsNeedNoUpdateAndSeeTheLatestConsent() = runTest {
        val store = MemoryStore()
        val repository = PreferenceDataStoreRepository(store) { "installation-123" }
        repository.preferences.first()
        repository.setAnalyticsEnabled(true)
        val writes = store.writeAttempts

        store.writeFailures = 1
        repeat(3) { assertTrue(repository.preferences.first().analyticsEnabled) }
        assertEquals(writes, store.writeAttempts)

        store.writeFailures = 0
        repository.setAnalyticsEnabled(false)
        assertFalse(repository.preferences.first().analyticsEnabled)
        assertEquals(writes + 1, store.writeAttempts)
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

    @Test fun initializationFailureRecoversWithoutReplacingTheCollector() = runTest {
        val store = MemoryStore().apply { writeFailures = 1 }
        val repository = PreferenceDataStoreRepository(store) { "installation-123" }
        val observed = mutableListOf<dev.fieldnotes.domain.Preferences>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.preferences.collect { observed += it }
        }
        assertTrue(observed.single().compactCardsExperiment.isOverridden)
        assertFalse(observed.single().analyticsEnabled)
        repository.setAnalyticsEnabled(true)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(observed.last().analyticsEnabled)
        assertFalse(observed.last().compactCardsExperiment.isOverridden)
        repository.setNotificationsEnabled(true)
        runCurrent()
        assertTrue(observed.last().notificationsEnabled)
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test fun readFailureAfterEmissionRecoversAndContinuesObservingEdits() = runTest {
        val store = MemoryStore()
        val repository = PreferenceDataStoreRepository(store) { "installation-123" }
        val observed = mutableListOf<dev.fieldnotes.domain.Preferences>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.preferences.collect { observed += it }
        }
        assertFalse(observed.last().compactCardsExperiment.isOverridden)
        store.readFailures = 1
        repository.setAnalyticsEnabled(true)
        runCurrent()
        assertFalse(observed.last().analyticsEnabled)
        assertTrue(observed.last().compactCardsExperiment.isOverridden)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(observed.last().analyticsEnabled)
        repository.setCompactCards(false)
        runCurrent()
        assertFalse(observed.last().compactCards)
        assertTrue(observed.last().compactCardsExperiment.isOverridden)
        assertTrue(collector.isActive)
        collector.cancelAndJoin()
    }

    @Test fun cancellationStopsStorageRetryWork() = runTest {
        val store = MemoryStore().apply { writeFailures = Int.MAX_VALUE }
        val repository = PreferenceDataStoreRepository(store) { "installation-123" }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.preferences.collect { }
        }
        assertEquals(1, store.writeAttempts)
        collector.cancelAndJoin()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, store.writeAttempts)
    }

    private class MemoryStore : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        var writeFailures = 0
        var readFailures = 0
        var writeAttempts = 0
        override val data: Flow<Preferences> = flow {
            emitAll(values.map { value ->
                if (readFailures > 0) { readFailures--; throw IOException("Temporary read failure") }
                value
            })
        }
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = lock.withLock {
            writeAttempts++
            if (writeFailures > 0) { writeFailures--; throw IOException("Temporary write failure") }
            transform(values.value).also { values.value = it }
        }
    }
}
