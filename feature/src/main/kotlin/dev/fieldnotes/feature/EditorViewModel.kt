package dev.fieldnotes.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fieldnotes.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class EditorUiState(
    val editing: Boolean = false,
    val title: String = "",
    val body: String = "",
    val projectId: String = "",
    val projects: List<Project> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val validation: InvalidNote.Reason? = null,
    val failed: Boolean = false,
    val loadFailed: Boolean = false,
    val stale: Boolean = false,
    val latestUnavailable: Boolean = false,
    val missing: Boolean = false,
    val savedId: String? = null,
)

class EditorViewModel(
    private val repository: NoteRepository,
    private val saveNote: SaveNote,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val noteId = savedStateHandle.get<String>("noteId")?.takeUnless { it == "new" }
    private val _state = MutableStateFlow(EditorUiState(
        editing = noteId != null,
        title = savedStateHandle["title"] ?: "",
        body = savedStateHandle["body"] ?: "",
        projectId = savedStateHandle["projectId"] ?: "",
    ))
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun load() = loadDraft(replaceDraft = false)

    /** Called only after the user confirms replacing their unsaved draft. */
    fun reloadLatest() = loadDraft(replaceDraft = true)

    private fun loadDraft(replaceDraft: Boolean) {
        if (_state.value.saving) return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val projects = repository.observeProjects().first()
                val existing = noteId?.let { repository.observeNote(it).first() }
                val hasDraft = savedStateHandle.get<Boolean>("initialized") == true ||
                    savedStateHandle.contains("title") || savedStateHandle.contains("body")
                if (noteId != null && (existing == null || existing.deleted)) {
                    _state.update { it.copy(loading = false, projects = projects,
                        missing = !hasDraft, stale = hasDraft, latestUnavailable = true) }
                    return@launch
                }
                if (replaceDraft || !hasDraft) {
                    savedStateHandle["title"] = existing?.title ?: ""
                    savedStateHandle["body"] = existing?.body ?: ""
                    savedStateHandle["projectId"] = existing?.projectId ?: projects.firstOrNull()?.id.orEmpty()
                    savedStateHandle["expectedRevision"] = existing?.revision
                } else if (!savedStateHandle.contains("projectId")) {
                    savedStateHandle["projectId"] = existing?.projectId ?: projects.firstOrNull()?.id.orEmpty()
                }
                savedStateHandle["initialized"] = true
                // Older restored drafts have no base revision. Never attach today's revision
                // to yesterday's text; require an explicit reload or save-as-new decision.
                val stale = noteId != null && savedStateHandle.get<Long>("expectedRevision") != existing?.revision
                _state.update { it.copy(
                    title = savedStateHandle["title"] ?: "",
                    body = savedStateHandle["body"] ?: "",
                    projectId = savedStateHandle["projectId"] ?: "",
                    projects = projects, loading = false, loadFailed = false, missing = false,
                    stale = stale, failed = false, latestUnavailable = false,
                ) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun title(value: String) {
        savedStateHandle["title"] = value
        _state.update { it.copy(title = value, validation = null, failed = false) }
    }
    fun body(value: String) {
        savedStateHandle["body"] = value
        _state.update { it.copy(body = value, validation = null, failed = false) }
    }
    fun project(id: String) {
        savedStateHandle["projectId"] = id
        _state.update { it.copy(projectId = id, validation = null) }
    }
    fun save() = saveDraft(asNew = false)
    fun saveAsNew() = saveDraft(asNew = true)

    private fun saveDraft(asNew: Boolean) {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.loadFailed || snapshot.saving || snapshot.missing ||
            snapshot.savedId != null || (snapshot.stale && !asNew)) return
        _state.update { it.copy(saving = true, validation = null, failed = false) }
        viewModelScope.launch {
            try {
                val id = saveNote(NoteDraft(if (asNew) null else noteId, snapshot.projectId, snapshot.title, snapshot.body,
                    expectedRevision = if (asNew) null else savedStateHandle["expectedRevision"]))
                _state.update { it.copy(saving = false, savedId = id) }
            } catch (_: StaleNoteEdit) {
                _state.update { it.copy(saving = false, stale = true) }
            } catch (error: InvalidNote) {
                _state.update { it.copy(saving = false, validation = error.reason) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(saving = false, failed = true) }
            }
        }
    }
}
