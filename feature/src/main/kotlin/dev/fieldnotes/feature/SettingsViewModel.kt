package dev.fieldnotes.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fieldnotes.domain.Preferences
import dev.fieldnotes.domain.PreferencesRepository
import dev.fieldnotes.domain.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(val preferences: Preferences = Preferences(), val signedIn: Boolean? = null)

class SettingsViewModel(
    private val preferences: PreferencesRepository,
    private val session: SessionRepository,
) : ViewModel() {
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    val state = combine(preferences.preferences, session.signedIn, ::SettingsUiState)
        .catch { error ->
            if (error is CancellationException) throw error
            _failed.value = true
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun compact(enabled: Boolean) = update { preferences.setCompactCards(enabled) }
    fun resetCompactExperiment() = update { preferences.resetCompactCardsOverride() }
    fun analytics(enabled: Boolean) = update { preferences.setAnalyticsEnabled(enabled) }
    fun notifications(enabled: Boolean) = update { preferences.setNotificationsEnabled(enabled) }
    fun signIn(token: String) = update { session.signIn(token.trim()) }
    fun signOut() = update { session.signOut() }
    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch {
            _failed.value = false
            try { action() } catch (error: Exception) {
                if (error is CancellationException) throw error
                _failed.value = true
            }
        }
    }
}
