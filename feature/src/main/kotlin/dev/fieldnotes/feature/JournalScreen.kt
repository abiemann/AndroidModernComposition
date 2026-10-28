package dev.fieldnotes.feature

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fieldnotes.domain.Note
import dev.fieldnotes.domain.SyncState
import java.text.DateFormat
import java.util.Date

@Composable
fun JournalScreen(
    state: JournalUiState,
    onSearch: (String) -> Unit,
    onFilter: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onRetry: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(290.dp),
            modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.field_journal), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.journal_heading), style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.journal_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    SummaryCard(state.totalCount, state.pendingCount)
                    OutlinedTextField(
                        value = state.query, onValueChange = onSearch,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.search_notes)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) IconButton(onClick = { onSearch("") }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.clear_search))
                            }
                        },
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.projectId.isEmpty(), onClick = { onFilter("") },
                            label = { Text(stringResource(R.string.all_projects)) })
                        state.projects.forEach { project ->
                            FilterChip(selected = state.projectId == project.id, onClick = { onFilter(project.id) },
                                label = { Text(project.name) })
                        }
                    }
                }
            }
            if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.loadFailed) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            } else if (state.notes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyJournal(state.query.isNotEmpty() || state.projectId.isNotEmpty(), onCreate)
            }
            items(state.notes, key = { it.id }, contentType = { "note" }) { note ->
                NoteCard(note, state.projects.firstOrNull { it.id == note.projectId }?.name.orEmpty(),
                    state.compactCards, onClick = { onOpen(note.id) })
            }
        }
    }
}

@Composable
private fun SummaryCard(total: Int, pending: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.CloudDone, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.local_first), style = MaterialTheme.typography.titleSmall)
                Text(if (pending > 0) pluralStringResource(R.plurals.pending_count, pending, pending) else stringResource(R.string.all_saved),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(pluralStringResource(R.plurals.notes_count, total, total), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EmptyJournal(filtered: Boolean, onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.Eco, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(if (filtered) R.string.no_matches else R.string.empty_heading),
            style = MaterialTheme.typography.titleLarge)
        Text(stringResource(if (filtered) R.string.no_matches_body else R.string.empty_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!filtered) OutlinedButton(onClick = onCreate) { Text(stringResource(R.string.new_note)) }
    }
}

@Composable
private fun NoteCard(note: Note, project: String, compact: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(project, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.NorthEast, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(note.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!compact && note.body.isNotEmpty()) Text(note.body, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SyncBadge(note)
            Text(formatDate(note.updatedAt), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SyncBadge(note: Note) {
    val (icon, label) = when (note.syncState) {
        SyncState.SYNCED -> Icons.Outlined.CheckCircle to R.string.note_saved
        SyncState.PENDING -> Icons.Outlined.Schedule to R.string.note_pending
        SyncState.CONFLICT -> Icons.Outlined.WarningAmber to if (note.deleted) R.string.deletion_conflict else R.string.note_conflict
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (note.syncState == SyncState.CONFLICT)
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun formatDate(timestamp: Long): String {
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    return remember(timestamp, formatter) { formatter.format(Date(timestamp)) }
}
