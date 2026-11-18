package dev.fieldnotes.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.fieldnotes.domain.Preferences
import dev.fieldnotes.domain.PreferencesRepository
import dev.fieldnotes.domain.CompactCardsExperiment
import dev.fieldnotes.domain.ExperimentAssignment
import dev.fieldnotes.domain.ExperimentVariant
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private val Context.fieldNotesPreferences by preferencesDataStore(name = "field-notes-settings")

class PreferenceDataStoreRepository(
    private val store: DataStore<StoredPreferences>,
    private val installationIds: () -> String = { UUID.randomUUID().toString() },
) : PreferencesRepository {
    constructor(context: Context) : this(context.applicationContext.fieldNotesPreferences)

    override val preferences = flow {
        // DataStore serializes this transaction, even when multiple screens collect concurrently.
        store.edit { if (it[INSTALLATION] == null) it[INSTALLATION] = installationIds() }
        emitAll(store.data)
    }.retryWhen { error, attempt ->
        if (error is IOException) {
            // Fail closed while storage is unavailable, then keep the same collectors live.
            // A terminal catch would leave Settings/telemetry stuck on defaults after recovery.
            emit(emptyPreferences())
            delay((1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L))
            true
        } else {
            false // Includes cancellation and programming errors; neither should be retried.
        }
    }.map {
        val installation = it[INSTALLATION]
        val variant = installation?.let(CompactCardsExperiment::assign) ?: ExperimentVariant.CONTROL
        val override = it[COMPACT]
        Preferences(
            compactCards = override ?: (variant == ExperimentVariant.COMPACT),
            analyticsEnabled = it[ANALYTICS] ?: false,
            notificationsEnabled = it[NOTIFICATIONS] ?: false,
            // Storage failure falls back safely and must not pollute experiment measurements.
            compactCardsExperiment = ExperimentAssignment(variant, isOverridden = override != null || installation == null),
        )
    }

    override suspend fun setCompactCards(enabled: Boolean) { store.edit { it[COMPACT] = enabled } }
    override suspend fun resetCompactCardsOverride() { store.edit { it.remove(COMPACT) } }
    override suspend fun setAnalyticsEnabled(enabled: Boolean) { store.edit { it[ANALYTICS] = enabled } }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { store.edit { it[NOTIFICATIONS] = enabled } }

    private companion object {
        val COMPACT = booleanPreferencesKey("compact_cards")
        val ANALYTICS = booleanPreferencesKey("analytics_enabled")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val INSTALLATION = stringPreferencesKey("experiment_installation_id")
    }
}
