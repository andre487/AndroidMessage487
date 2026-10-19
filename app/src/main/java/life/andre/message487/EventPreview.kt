package life.andre.message487

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class EventPreview(val event: MessageEvent, val settings: ForwardingSettings, val imageWarning: Boolean = false) {
    override fun toString() = "EventPreview(redacted)"
}

@Composable
internal fun EventPreviewDialogs(model: ConnectionViewModel, busy: Boolean, paused: Boolean) {
    val preview by model.preview.collectAsState()
    val candidates by model.previewCandidates.collectAsState()
    candidates?.let { notifications ->
        AlertDialog(onDismissRequest = { if (!busy) model.dismissCandidates() },
            title = { Text(stringResource(R.string.preview_notifications)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SupportingText(stringResource(R.string.preview_capture_hint))
                if (notifications.isEmpty()) Text(stringResource(R.string.preview_no_notifications))
                notifications.forEach { notification ->
                    OutlinedButton(onClick = { model.previewNotification(notification.key) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(notification.packageName + "\n" + notification.title, maxLines = 3)
                    }
                }
            } },
            confirmButton = { TextButton(onClick = model::dismissCandidates, enabled = !busy) { Text(stringResource(R.string.close)) } })
    }
    preview?.let { value ->
        AlertDialog(onDismissRequest = { if (!busy) model.clearPreview() },
            title = { Text(stringResource(R.string.preview_event)) },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SupportingText(stringResource(R.string.preview_not_sent))
                    if (paused) Text(stringResource(if (value.event.messageType == "test") R.string.preview_test_paused else R.string.preview_paused))
                    if (value.imageWarning) SupportingText(stringResource(R.string.preview_image_warning))
                    Text(value.event.source.name, style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(org.json.JSONObject(value.event.toJson()).toString(2), style = MaterialTheme.typography.bodySmall) }
                }
                items(value.event.images, key = { it.file.path }) { image ->
                    Column {
                        PreviewImage(image)
                        Text("${image.kind} · ${image.presentation} · ${image.width}×${image.height} · ${image.mimeType}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    SupportingText(stringResource(R.string.preview_destination, value.settings.url))
                    SupportingText(stringResource(R.string.preview_images_hint))
                }
            } },
            confirmButton = { TextButton(onClick = model::sendPreview, enabled = !busy && !pauseBlocksDelivery(paused, value.event.messageType == "test") && value.settings.ready()) {
                Text(stringResource(R.string.send_preview))
            } },
            dismissButton = { TextButton(onClick = model::clearPreview, enabled = !busy) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
private fun PreviewImage(image: NotificationImage) {
    val bitmap by produceState<ImageBitmap?>(null, image.file) {
        value = withContext(Dispatchers.IO) {
            runCatching { withImageMemory {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.file.path, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 256) inSampleSize *= 2
                }
                BitmapFactory.decodeFile(image.file.path, options)?.asImageBitmap()
            } }.getOrNull()
        }
    }
    bitmap?.let { Image(it, contentDescription = stringResource(R.string.preview_image),
        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
}
