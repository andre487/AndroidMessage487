package life.andre.message487

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun Modifier.switchLabel(label: String) = semantics { contentDescription = label }

@Composable
internal fun SourcesScreen(settings: ForwardingSettings, permissions: PermissionState, connected: Boolean,
    apps: List<AppSource>, busy: Boolean, model: ConnectionViewModel) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var addPackage by rememberSaveable { mutableStateOf(false) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refreshPermissions()
        model.sms(granted)
    }
    fun openNotificationSettings() {
        try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) { model.showSettingsError() }
    }
    val displayedApps = remember(apps, settings.packages, query, selectedOnly) {
        (apps + settings.packages.filter { pkg -> apps.none { it.packageName == pkg } }.map { AppSource(it, it) })
            .filter { (!selectedOnly || it.packageName in settings.packages) &&
                (it.name.contains(query, true) || it.packageName.contains(query, true)) }
            .sortedBy { it.name.lowercase() }
    }
    val allSelected = apps.isNotEmpty() && apps.all { it.packageName in settings.packages }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SupportingText(stringResource(R.string.sources_disclosure), Modifier.padding(bottom = 8.dp))
        }
        item {
            Panel {
                SourceSwitch(Icons.Outlined.Notifications, R.string.notifications_enabled, R.string.notifications_hint,
                    settings.notifications, !busy) {
                    model.notifications(it)
                    if (it && !permissions.notifications) openNotificationSettings()
                }
                if (settings.notifications) {
                    if (!permissions.notifications) {
                        Text(stringResource(R.string.access_needed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { openNotificationSettings() }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.notification_access))
                    }
                    if (permissions.notifications && !connected) OutlinedButton(onClick = model::rebind) {
                        Text(stringResource(R.string.reconnect_listener))
                    }
                }
            }
        }
        item {
            Panel {
                SourceSwitch(Icons.Outlined.Sms, R.string.sms_enabled, R.string.sms_hint, settings.sms, !busy) {
                    if (it && !permissions.sms) smsPermission.launch(Manifest.permission.RECEIVE_SMS) else model.sms(it)
                }
                if (settings.sms && !permissions.sms) {
                    Text(stringResource(R.string.sms_not_granted), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")))
                    }) { Text(stringResource(R.string.open_settings)) }
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.selected_apps, settings.packages.size), stringResource(R.string.add_package)) { addPackage = true }
            SupportingText(stringResource(R.string.app_selection_short))
            OutlinedButton(
                onClick = { if (allSelected) model.clearPackageSelection() else model.selectAllPackages() },
                enabled = !busy && apps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(if (allSelected) Icons.Outlined.RemoveDone else Icons.Outlined.DoneAll, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (allSelected) R.string.clear_app_selection else R.string.select_all_apps))
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_apps)) }, label = { Text(stringResource(R.string.search_apps)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } })
            FilterChip(selected = selectedOnly, onClick = { selectedOnly = !selectedOnly }, label = { Text(stringResource(R.string.selected_only)) },
                leadingIcon = { if (selectedOnly) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) })
        }
        if (displayedApps.isEmpty()) item {
            Panel { SupportingText(stringResource(R.string.no_matching_apps)) }
        }
        items(displayedApps, key = { it.packageName }) { app ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Row(Modifier.fillMaxWidth().toggleable(value = app.packageName in settings.packages,
                    enabled = !busy, role = Role.Checkbox, onValueChange = { model.selectPackage(app.packageName, it) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ApplicationIcon(app.packageName)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked = app.packageName in settings.packages, onCheckedChange = null, enabled = !busy)
                }
            }
        }
    }
    if (addPackage) {
        var name by rememberSaveable { mutableStateOf("") }
        val valid = name.trim().matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")) && name.trim() != context.packageName
        AlertDialog(onDismissRequest = { addPackage = false }, icon = { Icon(Icons.Outlined.Add, null) },
            title = { Text(stringResource(R.string.add_package)) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SupportingText(stringResource(R.string.manual_package_hint))
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text(stringResource(R.string.package_name)) }, isError = name.isNotEmpty() && !valid)
                }
            }, confirmButton = { TextButton(onClick = { model.selectPackage(name.trim(), true); addPackage = false }, enabled = valid && !busy) {
                Text(stringResource(R.string.add_package))
            } }, dismissButton = { TextButton(onClick = { addPackage = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ApplicationIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            catch (_: SecurityException) { null }
        }
    }
    bitmap?.let { Image(it, null, Modifier.size(44.dp)) } ?: IconTile(Icons.Outlined.Apps)
}

@Composable
private fun SourceSwitch(icon: ImageVector, title: Int, hint: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
                modifier = Modifier.switchLabel(stringResource(title)))
        }
        SupportingText(stringResource(hint))
    }
}
