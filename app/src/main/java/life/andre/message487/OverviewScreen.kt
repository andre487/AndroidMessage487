package life.andre.message487

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
internal fun OverviewScreen(settings: ForwardingSettings, permissions: PermissionState, connected: Boolean,
    queue: QueueSnapshot, busy: Boolean, model: ConnectionViewModel,
    onConnection: () -> Unit, onSources: () -> Unit, onJournal: () -> Unit) {
    val notificationReady = settings.notifications && permissions.notifications && connected && settings.packages.isNotEmpty()
    val smsReady = settings.sms && permissions.sms
    val needsAccess = (settings.notifications && (!permissions.notifications || !connected)) || (settings.sms && !permissions.sms)
    val title = when {
        settings.paused -> R.string.forwarding_paused
        !settings.ready() -> R.string.setup_connection
        needsAccess -> R.string.needs_setup
        !notificationReady && !smsReady -> R.string.choose_sources
        else -> R.string.forwarding_ready
    }
    val host = runCatching { URI(settings.url).host }.getOrNull().orEmpty()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary) {
                            Text(stringResource(if (settings.requireAck) R.string.n8n_destination else R.string.webhook_destination),
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(if (settings.paused) Icons.Outlined.PauseCircle else Icons.Outlined.SyncAlt, null, Modifier.size(32.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(if (settings.paused) R.string.paused_description else R.string.overview_description),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .15f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Smartphone, null, Modifier.size(20.dp))
                        Text(settings.deviceCode, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onConnection) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit_connection)) }
                    }
                    when {
                        !settings.ready() -> Button(onClick = onConnection, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_connection)) }
                        !notificationReady && !smsReady && !settings.paused -> Button(onClick = onSources, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_sources)) }
                        else -> FilledTonalButton(onClick = { model.pause(!settings.paused) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(if (settings.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (settings.paused) R.string.resume_forwarding else R.string.pause))
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(queue.pending.toString(), stringResource(R.string.in_queue), Icons.Outlined.Schedule, Modifier.weight(1f), onJournal)
                MetricCard(settings.packages.size.toString(), stringResource(R.string.apps_selected), Icons.Outlined.Apps, Modifier.weight(1f), onSources)
            }
        }
        if (settings.captureFailed) item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.capture_error))
                    TextButton(onClick = model::clearError, enabled = !busy) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.sources_tab), stringResource(R.string.manage), onSources)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column {
                        SourceStatus(Icons.Outlined.Notifications, stringResource(R.string.notification_type),
                            stringResource(if (!settings.notifications) R.string.source_off else if (notificationReady) R.string.source_on else R.string.needs_setup), onSources)
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SourceStatus(Icons.Outlined.Sms, stringResource(R.string.sms_type),
                            stringResource(if (!settings.sms) R.string.source_off else if (smsReady) R.string.source_on else R.string.needs_setup), onSources)
                    }
                }
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Outlined.Link)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleMedium)
                        SupportingText(host.ifEmpty { stringResource(R.string.not_configured) })
                    }
                }
                Button(onClick = model::sendTest, enabled = !busy && settings.ready() && !settings.paused,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.test_connection))
                }
            }
        }
    }
}

@Composable
private fun MetricCard(value: String, label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            SupportingText(label)
        }
    }
}

@Composable
private fun SourceStatus(icon: ImageVector, title: String, status: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                SupportingText(status)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
