package dev.fieldnotes.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fieldnotes.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class JournalUiState(
    val notes: List<Note> = emptyList(),
    val allNotes: List<Note> = emptyList(),
    val projects: List<Project> = emptyList(),
    val query: String = "",
    val projectId: String = "",
    val compactCards: Boolean = false,
    val loading: Boolean = true,
    val pendingCount: Int = 0,
    val attentionCount: Int = 0,
    val totalCount: Int = 0,
    val loadFailed: Boolean = false,
    val compactCardsExperiment: ExperimentAssignment = ExperimentAssignment(),
    val analyticsEnabled: Boolean = false,
)

enum class Feedback { SYNCED, CONFLICTS, SIGN_IN, LOCAL_NETWORK, OFFLINE, REJECTED, PAUSED, FAILED, DELETED, RESOLVED, RETRY_QUEUED }

/** The screen sends actions here; only repositories produce durable application state. */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(
    private val repository: NoteRepository,
    private val preferences: PreferencesRepository,
    private val scheduler: SyncScheduler,
    private val savedStateHandle: SavedStateHandle,
    private val telemetry: Telemetry,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val query = savedStateHandle.getStateFlow("query", "")
    private val projectId = savedStateHandle.getStateFlow("project", "")
    private val _feedback = MutableStateFlow<Feedback?>(null)
    val feedback = _feedback.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()
    private val reload = MutableStateFlow(0)

    private fun observeState(): Flow<JournalUiState> = combine(
        repository.observeNotes(), repository.observeProjects(), preferences.preferences, query, projectId,
    ) { notes, projects, settings, search, selected ->
        val visible = notes.filter { !it.deleted || it.syncState == SyncState.CONFLICT || it.syncState == SyncState.REJECTED }
        JournalUiState(
            notes = visible.filter {
                (selected.isEmpty() || it.projectId == selected) &&
                    (it.title.contains(search, ignoreCase = true) || it.body.contains(search, ignoreCase = true))
            },
            allNotes = visible,
            projects = projects,
            query = search,
            projectId = selected,
            compactCards = settings.compactCards,
            compactCardsExperiment = settings.compactCardsExperiment,
            analyticsEnabled = settings.analyticsEnabled,
            loading = false,
            pendingCount = notes.count { it.syncState == SyncState.PENDING },
            attentionCount = notes.count { it.syncState == SyncState.CONFLICT || it.syncState == SyncState.REJECTED },
            totalCount = visible.size,
        )
    }.catch { error ->
        if (error is CancellationException) throw error
        _feedback.value = Feedback.FAILED
        emit(JournalUiState(loading = false, loadFailed = true))
    }
    val state: StateFlow<JournalUiState> = reload.flatMapLatest { observeState() }
        .flowOn(computationDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalUiState())

    fun retry() { reload.update { it + 1 } }
    suspend fun recordExposure(assignment: ExperimentAssignment): Boolean = try {
        telemetry.recordExposure(assignment)
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        // An optional diagnostic sink must never break navigation or note taking.
        false
    }
    fun search(value: String) { savedStateHandle["query"] = value }
    fun filter(project: String) { savedStateHandle["project"] = project }
    fun acknowledgeFeedback() { _feedback.value = null }

    fun sync() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                _feedback.value = when (val result = repository.sync()) {
                    is SyncResult.Success -> when {
                        result.rejected > 0 -> Feedback.PAUSED
                        result.conflicts > 0 -> Feedback.CONFLICTS
                        else -> Feedback.SYNCED
                    }
                    SyncResult.AuthenticationRequired -> Feedback.SIGN_IN
                    SyncResult.PermissionRequired -> Feedback.LOCAL_NETWORK
                    SyncResult.RetryableFailure -> {
                        scheduler.requestSync()
                        Feedback.OFFLINE
                    }
                    SyncResult.Rejected -> Feedback.REJECTED
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _feedback.value = Feedback.FAILED
            } finally { _syncing.value = false }
        }
    }

    fun delete(id: String) = perform(Feedback.DELETED) { repository.delete(id) }
    fun resolveConflict(id: String, keepLocal: Boolean) = perform(Feedback.RESOLVED) {
        repository.resolveConflict(id, keepLocal)
    }
    fun retryRejected(id: String) = perform(Feedback.RETRY_QUEUED) { repository.retryRejected(id) }

    private fun perform(success: Feedback, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _feedback.value = Feedback.FAILED
                return@launch
            }
            // A durable mutation already succeeded. Periodic/startup sync can recover
            // scheduling failure; reporting the mutation as failed would invite duplicates.
            try { scheduler.requestSync() } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
            _feedback.value = success
        }
    }
}
