package dev.fieldnotes.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun StudyScreen(onOpenLegacy: () -> Unit) {
    val chapters = listOf(
        R.string.study_arch_title to R.string.study_arch_body,
        R.string.study_offline_title to R.string.study_offline_body,
        R.string.study_conflict_title to R.string.study_conflict_body,
        R.string.study_testing_title to R.string.study_testing_body,
        R.string.study_lifecycle_title to R.string.study_lifecycle_body,
        R.string.study_production_title to R.string.study_production_body,
    )
    ContentColumn {
        Text(stringResource(R.string.study_heading), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.study_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        chapters.forEachIndexed { index, (title, body) ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                    Text(stringResource(body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (index == 4) OutlinedButton(onClick = onOpenLegacy) { Text(stringResource(R.string.open_legacy)) }
                }
            }
        }
        Text(stringResource(R.string.study_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
