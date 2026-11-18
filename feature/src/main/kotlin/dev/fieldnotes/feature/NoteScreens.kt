package dev.fieldnotes.feature

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
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
    note: Note?, projects: List<Project>, loading: Boolean, loadFailed: Boolean,
    onEdit: () -> Unit, onDelete: () -> Unit, onResolve: (Boolean) -> Unit,
    onRetryLoad: () -> Unit, onRetrySync: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (loading) {
        LoadingScreen()
        return
    }
    if (loadFailed) {
        ContentColumn {
            Text(stringResource(R.string.note_load_failed), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OutlinedButton(onClick = onRetryLoad) { Text(stringResource(R.string.retry)) }
        }
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
            if (note.syncState == SyncState.REJECTED) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(FieldNotesSpacing.Large), verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Medium)) {
                        Text(stringResource(R.string.rejected_heading), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (note.deleted) R.string.rejected_deletion_body else R.string.rejected_note_body))
                        Button(onClick = onRetrySync) { Text(stringResource(R.string.retry_sync)) }
                    }
                }
            }
            if (note.syncState == SyncState.UNRECOGNIZED) {
                Text(stringResource(R.string.note_unrecognized_body), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            HorizontalDivider()
            Text(note.body, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.revision, note.revision), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!note.deleted && note.syncState != SyncState.UNRECOGNIZED) {
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
    state: EditorUiState, title: TextFieldState, body: TextFieldState,
    onProject: (String) -> Unit, onSave: () -> Unit, onRetry: () -> Unit,
    onSaveAsNew: () -> Unit, onReloadLatest: () -> Unit,
) {
    var confirmReload by rememberSaveable { mutableStateOf(false) }
    if (state.loading) { LoadingScreen(); return }
    ContentColumn {
        Text(stringResource(if (state.editing) R.string.edit_note else R.string.new_note), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        if (state.unrecognized) {
            Text(stringResource(R.string.note_unrecognized_body), color = MaterialTheme.colorScheme.error)
        } else if (state.missing) {
            Text(stringResource(R.string.missing_note))
        } else {
            if (state.loadFailed) {
                Text(stringResource(R.string.editor_load_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            if (state.stale) {
                Text(stringResource(R.string.stale_draft), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (!state.latestUnavailable) OutlinedButton(onClick = { confirmReload = true }, enabled = !state.saving && !state.loadFailed) {
                    Text(stringResource(R.string.reload_latest))
                }
            }
            Text(stringResource(R.string.editor_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.project_label), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.projects.forEach { project ->
                    FilterChip(selected = project.id == state.projectId, onClick = { onProject(project.id) },
                        enabled = !state.saving && !state.loadFailed, label = { Text(project.name) })
                }
            }
            OutlinedTextField(state = title, lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title_label)) },
                enabled = !state.saving && !state.loadFailed,
                isError = state.validation == InvalidNote.Reason.TITLE_REQUIRED || state.validation == InvalidNote.Reason.TITLE_TOO_LONG,
                supportingText = { Text("${title.text.length} / 120") })
            OutlinedTextField(state = body,
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 7),
                label = { Text(stringResource(R.string.body_label)) },
                placeholder = { Text(stringResource(R.string.body_placeholder)) }, enabled = !state.saving && !state.loadFailed,
                isError = state.validation == InvalidNote.Reason.BODY_TOO_LONG,
                supportingText = { Text("${body.text.length} / 10,000") })
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
            }
            Button(onClick = if (state.stale) onSaveAsNew else onSave,
                enabled = !state.saving && !state.loadFailed && state.projects.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(when { state.saving -> R.string.saving; state.stale -> R.string.save_as_new; else -> R.string.save }))
            }
        }
    }
    if (confirmReload) AlertDialog(
        onDismissRequest = { confirmReload = false },
        title = { Text(stringResource(R.string.reload_latest)) },
        text = { Text(stringResource(R.string.reload_latest_confirmation)) },
        confirmButton = { TextButton(onClick = { confirmReload = false; onReloadLatest() }) { Text(stringResource(R.string.reload_latest)) } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun ContentColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .imePadding().padding(FieldNotesSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Large), content = content)
    }
}
