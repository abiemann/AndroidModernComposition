package dev.fieldnotes.monitoring

import android.app.Application
import android.content.Context
import dev.fieldnotes.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
        val telemetry = LocalTelemetry(context, settings, backgroundScope)
        val compact = ExperimentAssignment(ExperimentVariant.COMPACT)
        telemetry.recordExposure(compact)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        assertTrue(stored.all.isEmpty())

        settings.preferences.value = Preferences(analyticsEnabled = true, compactCardsExperiment = compact)
        // No reliance on the collector having run: exposure reads durable/current consent.
        telemetry.recordExposure(compact)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        val prefix = "${compact.experimentId}.COMPACT."
        assertEquals(1, stored.getInt(prefix + "JOURNAL_EXPOSED", 0))
        assertEquals(1, stored.getInt(prefix + "NOTE_SAVED", 0))

        val overridden = compact.copy(isOverridden = true)
        settings.preferences.value = settings.preferences.value.copy(compactCardsExperiment = overridden)
        telemetry.recordExposure(overridden)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        assertEquals(1, stored.getInt(prefix + "NOTE_SAVED", 0))
        assertEquals(2, stored.getInt("NOTE_SAVED", 0))

        settings.preferences.value = Preferences()
        runCurrent()
        telemetry.recordExposure(compact)
        telemetry.record(AnalyticsEvent.NOTE_SAVED)
        assertTrue(stored.all.isEmpty())
    }

    private class TestPreferences : PreferencesRepository {
        override val preferences = MutableStateFlow(Preferences())
        override suspend fun setCompactCards(enabled: Boolean) { preferences.value = preferences.value.copy(compactCards = enabled) }
        override suspend fun resetCompactCardsOverride() = Unit
        override suspend fun setAnalyticsEnabled(enabled: Boolean) { preferences.value = preferences.value.copy(analyticsEnabled = enabled) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    }
}
