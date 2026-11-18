package dev.fieldnotes.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private data class StudyChapter(val title: Int, val body: Int, val opensLegacy: Boolean = false)

private val studyChapters = listOf(
    StudyChapter(R.string.study_arch_title, R.string.study_arch_body),
    StudyChapter(R.string.study_offline_title, R.string.study_offline_body),
    StudyChapter(R.string.study_conflict_title, R.string.study_conflict_body),
    StudyChapter(R.string.study_testing_title, R.string.study_testing_body),
    StudyChapter(R.string.study_lifecycle_title, R.string.study_lifecycle_body, opensLegacy = true),
    StudyChapter(R.string.study_production_title, R.string.study_production_body),
)

@Composable
internal fun StudyScreen(onOpenLegacy: () -> Unit) {
    ContentColumn {
        Text(stringResource(R.string.study_heading), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.study_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        studyChapters.forEach { chapter ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(chapter.title), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                    Text(stringResource(chapter.body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (chapter.opensLegacy) OutlinedButton(onClick = onOpenLegacy) { Text(stringResource(R.string.open_legacy)) }
                }
            }
        }
        Text(stringResource(R.string.study_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
