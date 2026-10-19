package life.andre.message487

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal fun deliveryAdvice(entry: QueueEntry): Int = when {
    entry.delivered -> R.string.check_accepted_hint
    entry.httpCode == 401 || entry.httpCode == 403 -> R.string.check_auth_hint
    entry.outcome == DeliveryStatus.INVALID_ACK.name -> R.string.check_ack_hint
    entry.state == QueueState.BLOCKED -> R.string.check_blocked_hint
    entry.state == QueueState.RETRY -> R.string.check_retry_hint
    entry.state == QueueState.SENDING -> R.string.check_sending_hint
    else -> R.string.check_waiting_hint
}

@Composable
internal fun DeliveryChecks(settings: ForwardingSettings, permissions: PermissionState, connected: Boolean,
    queue: QueueSnapshot, model: ConnectionViewModel, busy: Boolean,
    onConnection: () -> Unit, onSources: () -> Unit, onJournal: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Panel {
        TextButton(onClick = { expanded = !expanded; if (expanded) model.refreshPermissions() }) {
            Text(stringResource(R.string.delivery_checks))
        }
        if (expanded) {
            SupportingText(stringResource(R.string.check_limitations))
            CheckRow(R.string.connection, if (settings.ready()) R.string.check_connection_ok else R.string.check_connection_missing,
                R.string.edit_connection, onConnection)
            if (settings.paused) CheckRow(R.string.pause, R.string.paused_description, R.string.resume_forwarding) { if (!busy) model.pause(false) }
            CheckRow(R.string.notification_type, when {
                !settings.notifications -> R.string.check_source_disabled
                !permissions.notifications -> R.string.access_needed
                settings.packages.isEmpty() -> R.string.check_no_apps
                !connected -> R.string.check_listener_disconnected
                else -> R.string.check_notification_ready
            }, R.string.sources_tab, onSources)
            if (settings.notifications && permissions.notifications && !connected) {
                OutlinedButton(onClick = model::rebind, enabled = !busy) { Text(stringResource(R.string.reconnect_listener)) }
            }
            CheckRow(R.string.sms_type, when {
                !settings.sms -> R.string.check_source_disabled
                !permissions.sms -> R.string.sms_not_granted
                else -> R.string.check_sms_ready
            }, R.string.sources_tab, onSources)
            CheckRow(R.string.check_network, if (permissions.network) R.string.check_network_available else R.string.check_network_missing,
                R.string.check_refresh) { model.refreshPermissions() }
            CheckRow(R.string.check_background, if (permissions.backgroundRestricted) R.string.check_background_restricted else R.string.check_background_hint,
                R.string.open_settings) {
                try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}"))) }
                catch (_: android.content.ActivityNotFoundException) { model.showSettingsError() }
                catch (_: SecurityException) { model.showSettingsError() }
            }
            Text(stringResource(R.string.waiting_filter, queue.pending), style = MaterialTheme.typography.titleSmall)
            queue.entries.firstOrNull { !it.delivered }?.let { entry ->
                SupportingText(stringResource(deliveryAdvice(entry)))
                entry.httpCode?.let { Text(stringResource(R.string.http_code, it)) }
            }
            queue.entries.firstOrNull { it.state == QueueState.BLOCKED }?.let {
                SupportingText(stringResource(deliveryAdvice(it)))
            }
            if (settings.captureFailed) SupportingText(stringResource(R.string.capture_error))
            TextButton(onClick = onJournal) { Text(stringResource(R.string.journal)) }
            OutlinedButton(onClick = model::sendTest, enabled = !busy && settings.ready()) {
                Text(stringResource(R.string.test_connection))
            }
        }
    }
}

@Composable
private fun CheckRow(title: Int, detail: Int, action: Int, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        SupportingText(stringResource(detail))
        TextButton(onClick = onClick) { Text(stringResource(action)) }
    }
}
