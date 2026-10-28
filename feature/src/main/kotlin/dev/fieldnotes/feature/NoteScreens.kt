package dev.fieldnotes.feature

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fieldnotes.domain.*

@Composable
internal fun NoteScreen(
    note: Note?, projects: List<Project>, loading: Boolean,
    onEdit: () -> Unit, onDelete: () -> Unit, onResolve: (Boolean) -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (loading) {
        LoadingScreen()
        return
    }
    ContentColumn {
        if (note == null) {
            Text(stringResource(R.string.missing_note), style = MaterialTheme.typography.titleLarge)
        } else {
            Text(projects.firstOrNull { it.id == note.projectId }?.name.orEmpty(),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(note.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            SyncBadge(note)
            Text(stringResource(R.string.updated, formatDate(note.updatedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.syncState == SyncState.CONFLICT) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.conflict_heading), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.conflict_body))
                        Button(onClick = { onResolve(true) }) { Text(stringResource(R.string.keep_local)) }
                        OutlinedButton(onClick = { onResolve(false) }) { Text(stringResource(R.string.use_server)) }
                    }
                }
            }
            HorizontalDivider()
            Text(note.body, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.revision, note.revision), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!note.deleted) {
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_note))
                }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.delete_note))
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_body)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun EditorScreen(
    state: EditorUiState, onTitle: (String) -> Unit, onBody: (String) -> Unit,
    onProject: (String) -> Unit, onSave: () -> Unit, onRetry: () -> Unit,
) {
    if (state.loading) { LoadingScreen(); return }
    ContentColumn {
        Text(stringResource(if (state.editing) R.string.edit_note else R.string.new_note), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        if (state.missing) {
            Text(stringResource(R.string.missing_note))
        } else {
            Text(stringResource(R.string.editor_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.project_label), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.projects.forEach { project ->
                    FilterChip(selected = project.id == state.projectId, onClick = { onProject(project.id) },
                        enabled = !state.saving, label = { Text(project.name) })
                }
            }
            OutlinedTextField(value = state.title, onValueChange = onTitle, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title_label)) },
                enabled = !state.saving,
                isError = state.validation == InvalidNote.Reason.TITLE_REQUIRED || state.validation == InvalidNote.Reason.TITLE_TOO_LONG,
                supportingText = { Text("${state.title.length} / 120") })
            OutlinedTextField(value = state.body, onValueChange = onBody,
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), minLines = 7,
                label = { Text(stringResource(R.string.body_label)) },
                placeholder = { Text(stringResource(R.string.body_placeholder)) }, enabled = !state.saving,
                isError = state.validation == InvalidNote.Reason.BODY_TOO_LONG,
                supportingText = { Text("${state.body.length} / 10,000") })
            state.validation?.let { reason ->
                Text(stringResource(when (reason) {
                    InvalidNote.Reason.TITLE_REQUIRED -> R.string.title_required
                    InvalidNote.Reason.TITLE_TOO_LONG -> R.string.title_long
                    InvalidNote.Reason.BODY_TOO_LONG -> R.string.body_long
                    InvalidNote.Reason.PROJECT_REQUIRED -> R.string.project_required
                }), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.failed) {
                Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error)
                if (state.projects.isEmpty()) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            Button(onClick = onSave, enabled = !state.saving && state.projects.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.saving) R.string.saving else R.string.save))
            }
        }
    }
}

@Composable
internal fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun ContentColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}
