package dev.fieldnotes.feature

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.fieldnotes.domain.ExperimentAssignment
import org.koin.androidx.compose.koinViewModel

private data class Destination(val route: String, val label: Int, val icon: ImageVector)
private val destinations = listOf(
    Destination("journal", R.string.journal, Icons.Outlined.Book),
    Destination("study", R.string.study, Icons.Outlined.School),
    Destination("settings", R.string.settings, Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldNotesApp(environmentName: String = "Demo", onOpenLegacy: () -> Unit = {}, localServer: Boolean = false) {
    FieldNotesTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "journal"
        val journal: JournalViewModel = koinViewModel()
        val state by journal.state.collectAsStateWithLifecycle()
        val syncing by journal.syncing.collectAsStateWithLifecycle()
        val feedback by journal.feedback.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val feedbackText = feedback?.let { stringResource(it.messageResource()) }
        LaunchedEffect(feedbackText) {
            if (feedbackText != null) {
                snackbar.showSnackbar(feedbackText)
                journal.acknowledgeFeedback()
            }
        }
        fun navigate(destination: String) {
            nav.navigate(destination) {
                popUpTo("journal") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                if (expanded) {
                    NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Spacer(Modifier.height(32.dp))
                        Icon(Icons.Outlined.Forest, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(40.dp))
                        destinations.forEach { destination ->
                            NavigationRailItem(
                                selected = route == destination.route,
                                onClick = { navigate(destination.route) },
                                icon = { Icon(destination.icon, null) },
                                label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_title), style = MaterialTheme.typography.titleLarge) },
                            navigationIcon = {
                                if (route.startsWith("note/") || route.startsWith("edit/")) {
                                    IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = journal::sync, enabled = !syncing) {
                                    Icon(Icons.Outlined.Sync, stringResource(R.string.sync))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        if (!expanded && !route.startsWith("edit/")) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                destinations.forEach { destination ->
                                    NavigationBarItem(selected = route == destination.route,
                                        onClick = { navigate(destination.route) },
                                        icon = { Icon(destination.icon, null) },
                                        label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        if (route == "journal" && !state.loading && !state.loadFailed &&
                            (state.totalCount > 0 || state.query.isNotEmpty() || state.projectId.isNotEmpty())) ExtendedFloatingActionButton(
                            onClick = { nav.navigate("edit/new") },
                            icon = { Icon(Icons.Outlined.Add, null) },
                            text = { Text(stringResource(R.string.new_note)) },
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { insets ->
                    Column(Modifier.padding(insets).fillMaxSize()) {
                        AnimatedVisibility(syncing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        NavHost(navController = nav, startDestination = "journal", modifier = Modifier.fillMaxSize()) {
                            composable("journal") {
                                // Count visible visits, not unique users. Re-entering after enabling
                                // consent produces a new exposure; ordinary recomposition does not.
                                var exposedAssignment by remember { mutableStateOf<ExperimentAssignment?>(null) }
                                LaunchedEffect(state.loading, state.loadFailed, state.compactCardsExperiment, state.analyticsEnabled) {
                                    val renderedAssignment = state.compactCardsExperiment
                                    if (!state.analyticsEnabled) {
                                        exposedAssignment = null
                                    } else if (!state.loading && !state.loadFailed && exposedAssignment != renderedAssignment) {
                                        if (journal.recordExposure(renderedAssignment)) {
                                            exposedAssignment = renderedAssignment
                                        }
                                    }
                                }
                                JournalScreen(state, journal::search, journal::filter,
                                    onOpen = { nav.navigate("note/${Uri.encode(it)}") },
                                    onCreate = { nav.navigate("edit/new") }, onRetry = journal::retry)
                            }
                            composable("note/{noteId}", arguments = listOf(navArgument("noteId") { type = NavType.StringType })) { noteEntry ->
                                val id = noteEntry.arguments?.getString("noteId").orEmpty()
                                NoteScreen(
                                    note = state.allNotes.firstOrNull { it.id == id }, projects = state.projects,
                                    loading = state.loading, loadFailed = state.loadFailed,
                                    onEdit = { nav.navigate("edit/${Uri.encode(id)}") },
                                    onDelete = { journal.delete(id); nav.popBackStack() },
                                    onResolve = { keepLocal -> journal.resolveConflict(id, keepLocal) },
                                    onRetryLoad = journal::retry, onRetrySync = { journal.retryRejected(id) },
                                )
                            }
                            composable("edit/{noteId}", arguments = listOf(navArgument("noteId") { type = NavType.StringType })) { editorEntry ->
                                val editor: EditorViewModel = koinViewModel()
                                val editorState by editor.state.collectAsStateWithLifecycle()
                                LaunchedEffect(editorState.savedId) {
                                    val savedId = editorState.savedId
                                    if (savedId != null) {
                                        val originalId = editorEntry.arguments?.getString("noteId")
                                        if (originalId != "new" && originalId != savedId) {
                                            nav.navigate("note/${Uri.encode(savedId)}") {
                                                popUpTo("journal")
                                            }
                                        } else nav.popBackStack()
                                    }
                                }
                                EditorScreen(editorState, editor::title, editor::body, editor::project,
                                    editor::save, editor::load, editor::saveAsNew, editor::reloadLatest)
                            }
                            composable("settings") {
                                val settings: SettingsViewModel = koinViewModel()
                                SettingsRoute(settings, environmentName, localServer)
                            }
                            composable("study") { StudyScreen(onOpenLegacy) }
                        }
                    }
                }
            }
        }
    }
}

private fun Feedback.messageResource() = when (this) {
    Feedback.SYNCED -> R.string.sync_success
    Feedback.CONFLICTS -> R.string.sync_conflicts
    Feedback.SIGN_IN -> R.string.sync_auth
    Feedback.LOCAL_NETWORK -> R.string.sync_local_network
    Feedback.OFFLINE -> R.string.sync_offline
    Feedback.REJECTED -> R.string.sync_rejected
    Feedback.PAUSED -> R.string.sync_paused
    Feedback.RETRY_QUEUED -> R.string.retry_queued
    Feedback.FAILED -> R.string.operation_failed
    Feedback.DELETED -> R.string.note_deleted
    Feedback.RESOLVED -> R.string.conflict_resolved
}
