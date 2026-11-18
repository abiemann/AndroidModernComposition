package dev.fieldnotes.feature

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fieldnotes.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

data class JournalUiState(
    val notes: List<Note> = emptyList(),
    val allNotes: List<Note> = emptyList(),
    val projects: List<Project> = emptyList(),
    val projectNames: Map<String, String> = emptyMap(),
    val notesById: Map<String, Note> = emptyMap(),
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
    val backgroundSyncFailed: Boolean = false,
)

enum class Feedback {
    SYNCED, CONFLICTS, SIGN_IN, LOCAL_NETWORK, OFFLINE, REJECTED, PAUSED, FAILED, DELETED,
    RESOLVED, RETRY_QUEUED, CONTINUING, DEFERRED, RETRY_NOT_SCHEDULED, TIMED_OUT, TIMED_OUT_QUEUED,
}

/** The screen sends actions here; only repositories produce durable application state. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class JournalViewModel(
    private val repository: NoteRepository,
    private val preferences: PreferencesRepository,
    private val scheduler: SyncScheduler,
    private val savedStateHandle: SavedStateHandle,
    private val telemetry: Telemetry,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val searchQuery = savedStateHandle.textFieldState("searchBuffer", "query")
    private val query = snapshotFlow { searchQuery.text }
        .debounce { if (it.isEmpty()) 0L else 250L }
        .map { it.toString() }.distinctUntilChanged()
    private val projectId = savedStateHandle.getStateFlow("project", "")
    private val _feedback = MutableStateFlow<Feedback?>(null)
    val feedback = _feedback.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()
    private val reload = MutableStateFlow(0)

    private fun observeState(): Flow<JournalUiState> {
        // Rebuild metadata only when stored notes change, not for each query or preference.
        val indexed = repository.observeNotes().distinctUntilChanged().mapLatest { notes ->
            withContext(computationDispatcher) {
                val visible = ArrayList<Note>(notes.size)
                val byId = HashMap<String, Note>(notes.size)
                var pending = 0
                var attention = 0
                notes.forEachIndexed { index, note ->
                    if (index % 64 == 0) { currentCoroutineContext().ensureActive(); yield() }
                    if (note.syncState == SyncState.PENDING) pending++
                    val needsReview = note.syncState == SyncState.CONFLICT || note.syncState == SyncState.REJECTED ||
                        note.syncState == SyncState.UNRECOGNIZED
                    if (needsReview) attention++
                    if (!note.deleted || needsReview) { visible.add(note); byId[note.id] = note }
                }
                NoteIndex(visible, byId, pending, attention)
            }
        }
        val matches = combine(indexed, query, projectId, ::SearchInput).mapLatest { input ->
            withContext(computationDispatcher) {
                val filtered = if (input.query.isEmpty() && input.project.isEmpty()) input.index.notes else {
                    val found = ArrayList<Note>()
                    input.index.notes.forEachIndexed { index, note ->
                        if (index % 64 == 0) { currentCoroutineContext().ensureActive(); yield() }
                        if ((input.project.isEmpty() || note.projectId == input.project) &&
                            (note.title.contains(input.query, ignoreCase = true) || note.body.contains(input.query, ignoreCase = true))) {
                            found.add(note)
                        }
                    }
                    found
                }
                SearchResult(input, filtered)
            }
        }
        val projects = repository.observeProjects().distinctUntilChanged()
            .map { ProjectIndex(it, it.associate { project -> project.id to project.name }) }
        return combine(matches, projects, preferences.preferences, scheduler.backgroundSyncFailed.onStart { emit(false) }) { result, projectIndex, settings, backgroundFailed ->
            val input = result.input
            JournalUiState(
                notes = result.notes, allNotes = input.index.notes, notesById = input.index.byId,
                projects = projectIndex.projects, projectNames = projectIndex.names,
                query = input.query, projectId = input.project,
                compactCards = settings.compactCards, compactCardsExperiment = settings.compactCardsExperiment,
                analyticsEnabled = settings.analyticsEnabled, loading = false,
                pendingCount = input.index.pending, attentionCount = input.index.attention,
                totalCount = input.index.notes.size, backgroundSyncFailed = backgroundFailed && input.index.pending > 0,
            )
        }
    }

    private data class NoteIndex(val notes: List<Note>, val byId: Map<String, Note>, val pending: Int, val attention: Int)
    private data class ProjectIndex(val projects: List<Project>, val names: Map<String, String>)
    private data class SearchInput(val index: NoteIndex, val query: String, val project: String)
    private data class SearchResult(val input: SearchInput, val notes: List<Note>)

    val state: StateFlow<JournalUiState> = reload.flatMapLatest { observeState().catch { error ->
        if (error is CancellationException) throw error
        _feedback.value = Feedback.FAILED
        emit(JournalUiState(loading = false, loadFailed = true))
    } }
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
    fun filter(project: String) { savedStateHandle["project"] = project }
    fun acknowledgeFeedback() { _feedback.value = null }

    fun sync() {
        if (!_syncing.compareAndSet(false, true)) return
        // Enter try/finally immediately so cancellation cannot strand the busy flag.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                _feedback.value = when (val result = withTimeoutOrNull(45_000) { repository.sync() }) {
                    null -> if (scheduleContinuation()) Feedback.TIMED_OUT_QUEUED else Feedback.TIMED_OUT
                    is SyncResult.Success -> {
                        val continuing = result.morePending || result.deferred > 0
                        val scheduled = !continuing || scheduleContinuation()
                        when {
                            result.rejected > 0 -> Feedback.PAUSED
                            result.conflicts > 0 -> Feedback.CONFLICTS
                            !scheduled -> Feedback.RETRY_NOT_SCHEDULED
                            result.deferred > 0 -> Feedback.DEFERRED
                            result.morePending -> Feedback.CONTINUING
                            else -> Feedback.SYNCED
                        }
                    }
                    SyncResult.AuthenticationRequired -> Feedback.SIGN_IN
                    SyncResult.PermissionRequired -> Feedback.LOCAL_NETWORK
                    SyncResult.RetryableFailure -> {
                        if (scheduleContinuation()) Feedback.OFFLINE else Feedback.RETRY_NOT_SCHEDULED
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

    private suspend fun scheduleContinuation(): Boolean = try {
        withTimeoutOrNull(2_000) { scheduler.requestSync(); true } ?: false
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

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
            scheduleContinuation()
            _feedback.value = success
        }
    }
}
