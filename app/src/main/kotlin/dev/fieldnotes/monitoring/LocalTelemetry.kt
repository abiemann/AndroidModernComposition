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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A consent-gated, local-only analytics sink. No titles, text, tokens, or user IDs.
 * Replace this boundary with a vetted provider only after configuring consent/retention. */
class LocalTelemetry(context: Context, private val preferences: PreferencesRepository, scope: CoroutineScope) : Telemetry {
    @Volatile private var configuration = Preferences()
    private val exposed = mutableSetOf<ExperimentVariant>()
    private val storage = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    init {
        scope.launch {
            preferences.preferences.collect {
                synchronized(this@LocalTelemetry) {
                    configuration = it
                    if (!it.analyticsEnabled) {
                        exposed.clear()
                        storage.edit { clear() }
                    }
                }
            }
        }
    }

    @Synchronized override fun record(event: AnalyticsEvent) {
        if (!configuration.analyticsEnabled) return
        increment(event.name)
        val assignment = configuration.compactCardsExperiment
        if (event == AnalyticsEvent.NOTE_SAVED && !assignment.isOverridden && assignment.variant in exposed) {
            increment("${assignment.experimentId}.${assignment.variant}.${event.name}")
        }
        Log.i("FieldNotesMetrics", event.name)
    }

    override suspend fun recordExposure(assignment: ExperimentAssignment) = withContext(Dispatchers.IO) {
        // Read persisted consent for this actual frame, rather than racing the background collector.
        val current = preferences.preferences.first()
        synchronized(this@LocalTelemetry) {
            configuration = current
            if (current.analyticsEnabled) {
                increment(AnalyticsEvent.JOURNAL_EXPOSED.name)
                if (!assignment.isOverridden) {
                    exposed += assignment.variant
                    increment("${assignment.experimentId}.${assignment.variant}.JOURNAL_EXPOSED")
                }
            } else {
                exposed.clear()
                storage.edit { clear() }
            }
        }
    }

    private fun increment(key: String) {
        storage.edit { putInt(key, (storage.getInt(key, 0) + 1).coerceAtMost(1_000_000)) }
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (configuration.analyticsEnabled) {
                    // Exception messages and stack arguments can contain private data. Save only type.
                    storage.edit(commit = true) { putString("lastCrashType", error.javaClass.name.take(200)) }
                }
            } finally { previous?.uncaughtException(thread, error) }
        }
    }
}
