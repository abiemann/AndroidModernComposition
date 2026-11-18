package dev.fieldnotes.monitoring

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dev.fieldnotes.domain.AnalyticsEvent
import dev.fieldnotes.domain.ExperimentAssignment
import dev.fieldnotes.domain.ExperimentVariant
import dev.fieldnotes.domain.Preferences
import dev.fieldnotes.domain.PreferencesRepository
import dev.fieldnotes.domain.Telemetry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A consent-gated, local-only analytics sink. No titles, text, tokens, or user IDs.
 * Replace this boundary with a vetted provider only after configuring consent/retention. */
class LocalTelemetry(
    context: Context,
    private val preferences: PreferencesRepository,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Telemetry {
    private var configuration = Preferences()
    private var settingsEpoch = 0L
    private val exposed = mutableSetOf<ExperimentVariant>()
    private val applicationContext = context.applicationContext
    // Construction happens in the composition root, which may run on main.
    // Resolve/load preferences only from the IO work below.
    private val storage by lazy { applicationContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE) }
    init {
        scope.launch(ioDispatcher) {
            preferences.preferences.collect {
                synchronized(this@LocalTelemetry) {
                    applyConfiguration(it)
                }
            }
        }
    }

    override suspend fun record(event: AnalyticsEvent) = withConsent { current ->
        val assignment = current.compactCardsExperiment
        val experimentEvent = if (event == AnalyticsEvent.NOTE_SAVED && !assignment.isOverridden && assignment.variant in exposed)
            "${assignment.experimentId}.${assignment.variant}.${event.name}" else null
        increment(event.name, experimentEvent)
        Log.i("FieldNotesMetrics", event.name)
    }

    override suspend fun recordExposure(assignment: ExperimentAssignment) = withConsent {
        val experimentEvent = if (!assignment.isOverridden)
            "${assignment.experimentId}.${assignment.variant}.JOURNAL_EXPOSED" else null
        if (!assignment.isOverridden) {
            exposed += assignment.variant
        }
        increment(AnalyticsEvent.JOURNAL_EXPOSED.name, experimentEvent)
    }

    private suspend fun withConsent(record: (Preferences) -> Unit) = withContext(ioDispatcher) {
        val epoch = synchronized(this@LocalTelemetry) { settingsEpoch }
        val current = preferences.preferences.first()
        synchronized(this@LocalTelemetry) {
            // A settings change may have cleared diagnostics while this read was
            // suspended. Drop this optional event; never spin or restore old consent.
            if (settingsEpoch != epoch) return@synchronized
            if (!current.analyticsEnabled) {
                applyConfiguration(current)
            } else {
                record(current)
            }
        }
    }

    private fun applyConfiguration(current: Preferences) {
        configuration = current
        settingsEpoch++
        if (!current.analyticsEnabled) {
            exposed.clear()
            storage.edit(commit = true) { clear() }
        }
    }

    private fun increment(key: String, experimentKey: String?) {
        // Persist this small batch on IO instead of enqueuing apply() work that
        // Android may later flush synchronously during a main-thread lifecycle event.
        storage.edit(commit = true) {
            putInt(key, (storage.getInt(key, 0).coerceIn(0, 999_999) + 1))
            if (experimentKey != null) {
                putInt(experimentKey, (storage.getInt(experimentKey, 0).coerceIn(0, 999_999) + 1))
            }
        }
    }

    fun installCrashHandler() {
        // Call from IO during startup. Only a terminal crash uses synchronous
        // commit below, before the platform handler can terminate the process.
        storage.all
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                synchronized(this@LocalTelemetry) {
                    if (configuration.analyticsEnabled) {
                        // Exception messages and stack arguments can contain private data. Save only type.
                        storage.edit(commit = true) { putString("lastCrashType", error.javaClass.name.take(200)) }
                    }
                }
            } finally { previous?.uncaughtException(thread, error) }
        }
    }
}
