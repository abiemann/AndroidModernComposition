package dev.fieldnotes.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fieldnotes.domain.*
import kotlinx.coroutines.CancellationException
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

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            try {
                val projects = repository.observeProjects().first()
                val existing = noteId?.let { repository.observeNote(it).first() }
                if (noteId != null && (existing == null || existing.deleted)) {
                    _state.update { it.copy(loading = false, missing = true) }
                    return@launch
                }
                if (savedStateHandle.get<Boolean>("initialized") != true) {
                    savedStateHandle["title"] = existing?.title ?: ""
                    savedStateHandle["body"] = existing?.body ?: ""
                    savedStateHandle["projectId"] = existing?.projectId ?: projects.firstOrNull()?.id.orEmpty()
                    savedStateHandle["initialized"] = true
                }
                _state.update { it.copy(
                    title = savedStateHandle["title"] ?: "",
                    body = savedStateHandle["body"] ?: "",
                    projectId = savedStateHandle["projectId"] ?: "",
                    projects = projects, loading = false,
                ) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(loading = false, failed = true) }
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
    fun save() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.saving || snapshot.missing) return
        _state.update { it.copy(saving = true, validation = null, failed = false) }
        viewModelScope.launch {
            try {
                val id = saveNote(NoteDraft(noteId, snapshot.projectId, snapshot.title, snapshot.body))
                _state.update { it.copy(saving = false, savedId = id) }
            } catch (error: InvalidNote) {
                _state.update { it.copy(saving = false, validation = error.reason) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(saving = false, failed = true) }
            }
        }
    }
}
