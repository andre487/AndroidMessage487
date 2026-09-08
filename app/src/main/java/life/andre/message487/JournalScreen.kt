package life.andre.message487

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun JournalScreen(queue: QueueSnapshot, busy: Boolean, model: ConnectionViewModel) {
    var waitingOnly by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = queue.entries.filter { !waitingOnly || !it.delivered }
    val selected = queue.entries.firstOrNull { it.id == selectedId }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                SupportingText(stringResource(R.string.journal_privacy))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !waitingOnly, onClick = { waitingOnly = false }, label = { Text(stringResource(R.string.all_events)) })
                FilterChip(selected = waitingOnly, onClick = { waitingOnly = true }, label = { Text(stringResource(R.string.waiting_filter, queue.pending)) })
            }
        }
        if (visible.isEmpty()) item {
            Panel {
                IconTile(if (waitingOnly) Icons.Outlined.DoneAll else Icons.Outlined.Inbox)
                Text(stringResource(if (waitingOnly) R.string.queue_clear else R.string.no_events), style = MaterialTheme.typography.titleLarge)
                SupportingText(stringResource(if (waitingOnly) R.string.queue_clear_hint else R.string.empty_journal_hint))
            }
        }
        items(visible, key = { it.id }) { entry ->
            EventRow(entry) { selectedId = entry.id }
        }
    }
    if (selected != null) {
        AlertDialog(onDismissRequest = { selectedId = null },
            title = { Text(selected.sourceName) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatusLabel(selected)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(selected.createdAt)))
                    SelectionContainer { Text(stringResource(R.string.event_id, selected.id), style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.attempts, selected.attempts))
                    selected.httpCode?.let { Text(stringResource(R.string.http_code, it)) }
                    selected.outcome?.let { if (!selected.delivered) SupportingText(stringResource(outcomeLabel(it))) }
                    if (!selected.delivered && selected.state != QueueState.SENDING) {
                        Button(onClick = { model.retry(selected.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
                    }
                    if (selected.state != QueueState.SENDING) {
                        TextButton(onClick = { deleteId = selected.id }, enabled = !busy) {
                            Text(stringResource(R.string.delete_event), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selectedId = null }) { Text(stringResource(R.string.close)) } })
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text(stringResource(R.string.delete_event)) }, text = { Text(stringResource(R.string.delete_confirmation)) },
        confirmButton = { TextButton(onClick = { deleteId?.let(model::delete); deleteId = null; selectedId = null }, enabled = !busy) {
            Text(stringResource(R.string.delete_event), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun EventRow(entry: QueueEntry, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(when (entry.type) { "sms" -> Icons.Outlined.Sms; "notification" -> Icons.Outlined.Notifications; else -> Icons.Outlined.Science })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.sourceName, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when (entry.type) { "sms" -> R.string.sms_type; "notification" -> R.string.notification_type; else -> R.string.test_type }) +
                    " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusLabel(entry)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLabel(entry: QueueEntry) {
    val color = when {
        entry.delivered -> MaterialTheme.colorScheme.primary
        entry.state == QueueState.BLOCKED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(when { entry.delivered -> Icons.Outlined.CheckCircle; entry.state == QueueState.BLOCKED -> Icons.Outlined.ErrorOutline; else -> Icons.Outlined.Schedule },
            null, Modifier.size(14.dp), tint = color)
        Text(stringResource(entry.state.label()), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun QueueState.label(): Int = when (this) {
    QueueState.PENDING -> R.string.pending
    QueueState.SENDING -> R.string.sending
    QueueState.RETRY -> R.string.retry_wait
    QueueState.BLOCKED -> R.string.needs_attention
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
