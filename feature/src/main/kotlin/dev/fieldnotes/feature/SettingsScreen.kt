package dev.fieldnotes.feature

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.fieldnotes.domain.ExperimentVariant

@Composable
internal fun SettingsRoute(viewModel: SettingsViewModel, environmentName: String, localServer: Boolean = false) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Credentials deliberately are not rememberSaveable: never serialize them into a Bundle.
    var token by remember { mutableStateOf("") }
    var permissionDenied by remember { mutableStateOf(false) }
    var experimentExpanded by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        viewModel.notifications(granted)
    }
    ContentColumn {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Section)) {
            Column(verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Small)) {
                Text(stringResource(R.string.settings_heading), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.settings_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsGroup(stringResource(R.string.appearance)) {
                PreferenceSwitch(stringResource(R.string.compact_title), stringResource(R.string.compact_detail),
                    state.preferences.compactCards, viewModel::compact)
                val disclosureState = stringResource(if (experimentExpanded)
                    R.string.experiment_details_expanded else R.string.experiment_details_collapsed)
                TextButton(
                    onClick = { experimentExpanded = !experimentExpanded },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { stateDescription = disclosureState },
                    // A capsule clips flush-left text when large fonts wrap onto multiple lines.
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(vertical = FieldNotesSpacing.Small),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FieldNotesSpacing.IconGap)) {
                        Text(stringResource(R.string.experiment_details), modifier = Modifier.weight(1f))
                        Icon(if (experimentExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null)
                    }
                }
                if (experimentExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Small)) {
                        val experiment = state.preferences.compactCardsExperiment
                        val variant = stringResource(if (experiment.variant == ExperimentVariant.COMPACT)
                            R.string.experiment_compact else R.string.experiment_control)
                        Text(stringResource(R.string.experiment_assignment, variant),
                            style = MaterialTheme.typography.bodyMedium)
                        if (experiment.isOverridden) {
                            Text(stringResource(R.string.experiment_overridden),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = viewModel::resetCompactExperiment) {
                                Text(stringResource(R.string.experiment_reset))
                            }
                        }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.privacy)) {
                PreferenceSwitch(stringResource(R.string.analytics_title), stringResource(R.string.analytics_detail),
                    state.preferences.analyticsEnabled, viewModel::analytics)
                HorizontalDivider()
                PreferenceSwitch(stringResource(R.string.notifications_title), stringResource(R.string.notifications_detail),
                    state.preferences.notificationsEnabled) { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        permissionDenied = false
                        viewModel.notifications(enabled)
                    }
                }
                if (permissionDenied) Text(stringResource(R.string.notification_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (localServer) {
                SettingsGroup(stringResource(R.string.session)) {
                    Text(stringResource(R.string.environment, environmentName), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(when (state.signedIn) {
                        null -> R.string.session_loading
                        true -> R.string.session_connected
                        false -> R.string.session_disconnected
                    }))
                    if (state.signedIn == true) {
                        OutlinedButton(onClick = viewModel::signOut) { Text(stringResource(R.string.disconnect)) }
                    } else if (state.signedIn == false) {
                        OutlinedTextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.token_label)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            visualTransformation = PasswordVisualTransformation())
                        Text(stringResource(R.string.token_help), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(enabled = token.isNotBlank(), onClick = { viewModel.signIn(token); token = "" }) {
                            Text(stringResource(R.string.connect))
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 37) LocalNetworkPermissionRoute()
            } else {
                SettingsGroup(stringResource(R.string.demo_mode)) {
                    Text(stringResource(R.string.demo_mode_detail), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (failed) Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(stringResource(if (localServer) R.string.privacy_note else R.string.privacy_note_demo),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@RequiresApi(37)
@Composable
private fun LocalNetworkPermissionRoute() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun isGranted() = ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    var granted by remember(context) { mutableStateOf(isGranted()) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        denied = !allowed
    }
    // Recheck revocation/grants when returning from Android's app settings.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = isGranted()
                if (granted) denied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LocalNetworkPermissionCard(granted, denied,
        onRequest = { launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
        onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        })
}

@Composable
internal fun LocalNetworkPermissionCard(
    granted: Boolean, denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit,
) {
    SettingsGroup(stringResource(R.string.local_network_title)) {
        Text(stringResource(R.string.local_network_rationale), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(when {
            granted -> R.string.local_network_granted
            denied -> R.string.local_network_denied
            else -> R.string.local_network_not_granted
        }), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (!granted) {
            Button(onClick = onRequest) { Text(stringResource(R.string.local_network_allow)) }
            if (denied) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.local_network_settings)) }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Medium)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Medium), content = content)
    }
}

@Composable
private fun PreferenceSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics(mergeDescendants = true) {}
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = FieldNotesSpacing.Small), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Medium)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FieldNotesSpacing.Small)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}
