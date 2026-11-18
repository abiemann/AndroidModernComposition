package dev.fieldnotes.monitoring

import android.app.Application
import android.content.Context
import dev.fieldnotes.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class LocalTelemetryTest {
    @Test fun `consent gates counters and overrides never enter experimental results`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val stored = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        val settings = TestPreferences()
        val telemetry = LocalTelemetry(context, settings, backgroundScope, StandardTestDispatcher(testScheduler))
        val compact = ExperimentAssignment(ExperimentVariant.COMPACT)
        telemetry.recordExposure(compact)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        assertTrue(stored.all.isEmpty())

        settings.values.value = Preferences(analyticsEnabled = true, compactCardsExperiment = compact)
        // No reliance on the collector having run: exposure reads durable/current consent.
        telemetry.recordExposure(compact)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        val prefix = "${compact.experimentId}.COMPACT."
        assertEquals(1, stored.getInt(prefix + "JOURNAL_EXPOSED", 0))
        assertEquals(1, stored.getInt(prefix + "NOTE_SAVED", 0))

        val overridden = compact.copy(isOverridden = true)
        settings.values.value = settings.values.value.copy(compactCardsExperiment = overridden)
        telemetry.recordExposure(overridden)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        assertEquals(1, stored.getInt(prefix + "NOTE_SAVED", 0))
        assertEquals(2, stored.getInt("NOTE_SAVED", 0))

        settings.values.value = Preferences()
        runCurrent()
        telemetry.recordExposure(compact)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        assertTrue(stored.all.isEmpty())
    }

    @Test fun `an exposure read suspended before opt-out cannot restore consent`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val stored = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        val settings = TestPreferences()
        val compact = ExperimentAssignment(ExperimentVariant.COMPACT)
        settings.values.value = Preferences(analyticsEnabled = true, compactCardsExperiment = compact)
        val telemetry = LocalTelemetry(context, settings, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        telemetry.record(AnalyticsEvent.SYNC_SUCCEEDED)
        assertEquals(1, stored.getInt("SYNC_SUCCEEDED", 0))

        val releaseSnapshot = CompletableDeferred<Unit>()
        settings.beforeNextSnapshot = { releaseSnapshot.await() }
        val exposure = launch { telemetry.recordExposure(compact) }
        runCurrent()
        assertFalse(exposure.isCompleted)

        settings.setAnalyticsEnabled(false)
        runCurrent()
        assertTrue(stored.all.isEmpty())
        releaseSnapshot.complete(Unit)
        exposure.join()

        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        assertTrue(stored.all.isEmpty())
    }

    private class TestPreferences : PreferencesRepository {
        val values = MutableStateFlow(Preferences())
        var beforeNextSnapshot: (suspend () -> Unit)? = null
        override val preferences = flow {
            val beforeSnapshot = beforeNextSnapshot
            if (beforeSnapshot != null) {
                beforeNextSnapshot = null
                val snapshot = values.value
                beforeSnapshot()
                emit(snapshot)
            }
            emitAll(values)
        }
        override suspend fun setCompactCards(enabled: Boolean) { values.value = values.value.copy(compactCards = enabled) }
        override suspend fun resetCompactCardsOverride() = Unit
        override suspend fun setAnalyticsEnabled(enabled: Boolean) { values.value = values.value.copy(analyticsEnabled = enabled) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    }
}
