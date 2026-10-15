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
import kotlinx.coroutines.ensureActive
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
        catch (_: SecurityException) { model.showSettingsError() }
    }
    val displayedApps by produceState<List<AppSource>>(emptyList(), apps, settings.packages, query, selectedOnly) {
        value = withContext(Dispatchers.Default) { displayedSources(apps, settings.packages, query, selectedOnly) }
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
                        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}"))) }
                        catch (_: android.content.ActivityNotFoundException) { model.showSettingsError() }
                        catch (_: SecurityException) { model.showSettingsError() }
                    }) { Text(stringResource(R.string.open_settings)) }
                }
            }
        }
        item {
            Panel {
                SourceSwitch(Icons.Outlined.Sms, R.string.sms_deduplication, R.string.deduplication_hint,
                    settings.smsDeduplication, !busy, model::smsDeduplication)
                SourceSwitch(Icons.Outlined.Notifications, R.string.notification_deduplication, R.string.deduplication_hint,
                    settings.notificationDeduplication, !busy, model::notificationDeduplication)
                var window by rememberSaveable {
                    mutableStateOf(settings.deduplicationWindowSeconds.toString())
                }
                val seconds = window.toIntOrNull()?.takeIf { it >= 0 }
                OutlinedTextField(
                    value = window,
                    onValueChange = { value ->
                        window = value
                        value.toIntOrNull()?.takeIf { it >= 0 }?.let(model::deduplicationWindow)
                    },
                    label = { Text(stringResource(R.string.deduplication_window)) },
                    supportingText = { Text(stringResource(R.string.deduplication_window_hint)) },
                    isError = seconds == null,
                    enabled = !busy && (settings.smsDeduplication || settings.notificationDeduplication),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Panel {
                Text(stringResource(R.string.notification_images), style = MaterialTheme.typography.titleMedium)
                SupportingText(stringResource(R.string.notification_images_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NotificationImageFormat.entries.forEach { format ->
                        FilterChip(selected = settings.imageFormat == format, enabled = !busy,
                            onClick = { model.imageFormat(format) }, label = { Text(format.name) })
                    }
                }
                var quality by remember(settings.imageQuality) { mutableFloatStateOf(settings.imageQuality.toFloat()) }
                Text(stringResource(R.string.image_quality, quality.toInt()))
                Slider(value = quality, onValueChange = { quality = it },
                    onValueChangeFinished = { model.imageQuality(quality.toInt()) }, valueRange = 0f..100f, steps = 99,
                    enabled = !busy && settings.imageFormat == NotificationImageFormat.JPEG,
                    modifier = Modifier.switchLabel(stringResource(R.string.image_quality_label)))
                if (settings.imageFormat == NotificationImageFormat.PNG) SupportingText(stringResource(R.string.png_quality_hint))
                HorizontalDivider()
                SourceSwitch(Icons.Outlined.Person, R.string.forward_avatars, R.string.forward_avatars_hint,
                    settings.forwardAvatars, !busy, model::forwardAvatars)
                SourceSwitch(Icons.Outlined.Apps, R.string.forward_app_icons, R.string.forward_app_icons_hint,
                    settings.forwardAppIcons, !busy, model::forwardAppIcons)
                HorizontalDivider()
                SourceSwitch(Icons.Outlined.Image, R.string.image_limits_enabled, R.string.image_limits_hint,
                    settings.imageLimitsEnabled, !busy, model::imageLimits)
                if (settings.imageLimitsEnabled) {
                    ImageLimitField(R.string.image_max_count, settings.imageMaxCount, !busy, model::imageMaxCount)
                    ImageLimitField(R.string.image_max_edge, settings.imageMaxEdge, !busy, model::imageMaxEdge)
                    ImageLimitField(R.string.image_max_kib, settings.imageMaxKiB, !busy, model::imageMaxKiB)
                    ImageLimitField(R.string.image_total_kib, settings.imageTotalKiB, !busy, model::imageTotalKiB)
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.selected_apps, settings.packages.size), stringResource(R.string.add_package)) { addPackage = true }
            SupportingText(stringResource(R.string.app_selection_short))
            SourceSwitch(Icons.Outlined.Apps, R.string.auto_select_new_apps, R.string.auto_select_new_apps_hint,
                settings.autoSelectNewApps, !busy, model::autoSelectNewApps)
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
            val active = coroutineContext
            try { withImageMemory({ active.ensureActive() }) {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
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

@Composable
private fun ImageLimitField(label: Int, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by rememberSaveable(label) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { input ->
        text = input
        input.toIntOrNull()?.takeIf { it > 0 }?.let(onChange)
    }, label = { Text(stringResource(label)) },
        isError = text.toIntOrNull()?.let { it > 0 } != true,
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

internal fun displayedSources(apps: List<AppSource>, selected: Set<String>, query: String, selectedOnly: Boolean): List<AppSource> {
    val known = apps.mapTo(HashSet()) { it.packageName }
    return (apps + (selected - known).map { AppSource(it, it) })
        .filter { (!selectedOnly || it.packageName in selected) &&
            (it.name.contains(query, true) || it.packageName.contains(query, true)) }
        .sortedBy { it.name.lowercase() }
}
