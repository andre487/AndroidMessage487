package life.andre.message487

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MessageScreen()
            }
        }
    }
}

@Composable
private fun MessageScreen(model: ConnectionViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val permissions by model.permissions.collectAsStateWithLifecycle()
    val connected by model.listenerConnected.collectAsStateWithLifecycle()
    val queue by model.queue.collectAsStateWithLifecycle()
    val apps by model.apps.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshPermissions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.app_name), Modifier.padding(16.dp), style = MaterialTheme.typography.headlineMedium)
            TabRow(selectedTabIndex = tab) {
                listOf(R.string.status_tab, R.string.connection, R.string.sources_tab, R.string.journal).forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.notice?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            key(tab) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (tab) {
                        0 -> {
                            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleLarge)
                            SettingSwitch(R.string.pause, R.string.pause_hint, settings.paused, !state.busy, model::pause)
                            Text(stringResource(R.string.queue_count, queue.pending), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(if (settings.ready()) R.string.connection_ready else R.string.configure_connection))
                            Text(stringResource(if (permissions.notifications && connected) R.string.listener_connected else R.string.listener_disconnected))
                            Text(stringResource(if (permissions.sms) R.string.sms_granted else R.string.sms_not_granted))
                            if (settings.captureFailed) {
                                Text(stringResource(R.string.capture_error), color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = model::clearError, enabled = !state.busy) { Text(stringResource(R.string.dismiss)) }
                            }
                            Button(onClick = model::sendTest, enabled = !state.busy && settings.ready() && !settings.paused) {
                                Text(stringResource(R.string.test_saved_connection))
                            }
                            Text(stringResource(R.string.delivery_note))
                        }
                        1 -> ConnectionContent(state, model)
                        2 -> SourcesContent(settings, permissions, connected, apps, state.busy, model)
                        3 -> JournalContent(queue, state.busy, model)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionContent(state: ConnectionState, model: ConnectionViewModel) {
    OutlinedTextField(value = state.deviceCode, onValueChange = model::setDeviceCode,
        label = { Text(stringResource(R.string.device_code)) }, modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy, singleLine = true, isError = state.invalidDeviceCode,
        supportingText = { Text(stringResource(if (state.invalidDeviceCode) R.string.invalid_device_code else R.string.device_code_hint)) })
    OutlinedTextField(value = state.url, onValueChange = model::setUrl,
        label = { Text(stringResource(R.string.webhook_url)) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
        isError = state.invalidUrl, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        supportingText = { Text(stringResource(if (state.invalidUrl) R.string.invalid_url else R.string.url_hint)) })
    SettingSwitch(R.string.n8n_mode, if (state.requireAck) R.string.n8n_hint else R.string.raw_hint,
        state.requireAck, !state.busy, model::setRequireAck)
    Text(stringResource(R.string.destination_note))
    OutlinedButton(onClick = { model.save(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    Button(onClick = { model.save(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.send_test)) }
}

@Composable
private fun SourcesContent(settings: ForwardingSettings, permissions: PermissionState, connected: Boolean,
    apps: List<AppSource>, busy: Boolean, model: ConnectionViewModel) {
    val context = LocalContext.current
    var manualPackage by remember { mutableStateOf("") }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refreshPermissions()
        model.sms(granted)
    }
    fun openNotificationSettings() {
        try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) { model.showSettingsError() }
    }
    Text(stringResource(R.string.sources_disclosure))
    SettingSwitch(R.string.notifications_enabled, R.string.notifications_hint, settings.notifications, !busy) {
        model.notifications(it)
        if (it && !permissions.notifications) openNotificationSettings()
    }
    OutlinedButton(onClick = { openNotificationSettings() }) { Text(stringResource(R.string.notification_access)) }
    if (permissions.notifications && !connected) {
        OutlinedButton(onClick = model::rebind) { Text(stringResource(R.string.reconnect_listener)) }
    }
    SettingSwitch(R.string.sms_enabled, R.string.sms_hint, settings.sms, !busy) {
        if (it && !permissions.sms) smsPermission.launch(Manifest.permission.RECEIVE_SMS) else model.sms(it)
    }
    if (settings.sms && !permissions.sms) Text(stringResource(R.string.sms_not_granted), color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.selected_apps, settings.packages.size), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.app_selection_hint))
    val displayedApps = (apps + settings.packages.filter { pkg -> apps.none { it.packageName == pkg } }.map { AppSource(it, it) })
        .sortedBy { it.name.lowercase() }
    displayedApps.forEach { app ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = app.packageName in settings.packages,
                onCheckedChange = { model.selectPackage(app.packageName, it) }, enabled = !busy)
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(app.name)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    OutlinedTextField(value = manualPackage, onValueChange = { manualPackage = it },
        label = { Text(stringResource(R.string.package_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedButton(onClick = { model.selectPackage(manualPackage.trim(), true) }, enabled = !busy && manualPackage.isNotBlank()) {
        Text(stringResource(R.string.add_package))
    }
}

@Composable
private fun SettingSwitch(title: Int, description: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(title)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun JournalContent(queue: QueueSnapshot, busy: Boolean, model: ConnectionViewModel) {
    var deleteTarget by remember { mutableStateOf<QueueEntry?>(null) }
    Text(stringResource(R.string.journal_hint))
    if (queue.entries.isEmpty()) Text(stringResource(R.string.no_events))
    queue.entries.forEach { entry ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.sourceName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (entry.type) { "sms" -> R.string.sms_type; "notification" -> R.string.notification_type; else -> R.string.test_type }))
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(entry.createdAt)))
                Text(stringResource(entry.state.label()))
                entry.outcome?.let { outcome ->
                    if (!entry.delivered) Text(stringResource(outcomeLabel(outcome)))
                }
                Text(stringResource(R.string.event_id, entry.id), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.attempts, entry.attempts))
                entry.httpCode?.let { Text(stringResource(R.string.http_code, it)) }
                if (!entry.delivered && entry.state != QueueState.SENDING) {
                    OutlinedButton(onClick = { model.retry(entry.id) }, enabled = !busy) { Text(stringResource(R.string.retry)) }
                }
                if (entry.state != QueueState.SENDING) {
                    TextButton(onClick = { deleteTarget = entry }, enabled = !busy) { Text(stringResource(R.string.delete_event)) }
                }
            }
        }
    }
    deleteTarget?.let { entry ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text(stringResource(R.string.delete_event)) },
            text = { Text(stringResource(R.string.delete_confirmation)) },
            confirmButton = { TextButton(onClick = { model.delete(entry.id); deleteTarget = null }) { Text(stringResource(R.string.delete_event)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

private fun QueueState.label(): Int = when (this) {
    QueueState.PENDING -> R.string.pending
    QueueState.SENDING -> R.string.sending
    QueueState.RETRY -> R.string.retry_wait
    QueueState.BLOCKED -> R.string.blocked
    QueueState.ACCEPTED -> R.string.accepted
    QueueState.HTTP_SUCCESS -> R.string.http_success
}

private fun outcomeLabel(outcome: String): Int = when (outcome) {
    DeliveryStatus.HTTP_ERROR.name -> R.string.http_error
    DeliveryStatus.INVALID_ACK.name -> R.string.invalid_ack
    DeliveryStatus.TIMEOUT.name -> R.string.timeout
    DeliveryStatus.NETWORK_ERROR.name -> R.string.network_error
    else -> R.string.local_error
}
