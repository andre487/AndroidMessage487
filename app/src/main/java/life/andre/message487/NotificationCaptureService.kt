package life.andre.message487

import life.andre.message487.diagnostics.DiagnosticEvent
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val images: List<NotificationImage> = emptyList(),
)

object ListenerState {
    internal val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
}

class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerConnected() {
        MessageGraph.get(this).diagnostics.record(DiagnosticEvent.LISTENER_CONNECTED)
        ListenerState.mutableConnected.value = true
        val graph = MessageGraph.get(this)
        graph.submitCapture {
            try { graph.recover() } catch (error: Exception) { graph.captureFailed(error) }
        }
    }

    override fun onListenerDisconnected() {
        MessageGraph.get(this).diagnostics.record(DiagnosticEvent.LISTENER_DISCONNECTED)
        ListenerState.mutableConnected.value = false
    }

    override fun onDestroy() {
        ListenerState.mutableConnected.value = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val graph = MessageGraph.get(this)
        if (!graph.settings.loaded.value) {
            graph.submitCapture {
                try { graph.settings.load(); capturePosted(sbn) }
                catch (error: Exception) { graph.captureFailed(error) }
            }
        } else capturePosted(sbn)
    }

    private fun capturePosted(sbn: StatusBarNotification) {
        val graph = MessageGraph.get(this)
        val config = graph.settings.state.value
        if (!config.acceptsPackage(sbn.packageName, packageName)) return
        if (!sbn.isClearable || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
                ?: ""
            val captured = CapturedNotification(sbn.key, sbn.packageName, title, text, sbn.postTime)
            val hasImages = config.forwardAvatars || config.forwardAppIcons || extras.containsKey(Notification.EXTRA_PICTURE) ||
                extras.containsKey(Notification.EXTRA_MESSAGES) ||
                (android.os.Build.VERSION.SDK_INT >= 31 && extras.containsKey(Notification.EXTRA_PICTURE_ICON))
            if (!hasImages) {
                if (title.isNotBlank() || text.isNotBlank()) graph.submitCapture {
                    try { graph.captureNotification(captured) } catch (error: Exception) { graph.captureFailed(error) }
                }
                return
            }
            graph.imageCaptureQueue.submit(captured.key,
                capture = { cancellation ->
                    if (!graph.settings.state.value.acceptsPackage(captured.packageName, packageName)) emptyList()
                    else {
                        val current = getActiveNotifications(arrayOf(captured.key)).firstOrNull()
                        if (current == null || current.postTime != captured.postedAt) emptyList()
                        else notificationImages(this, current.notification, config, captured.packageName, cancellation) { graph.captureFailed(it) }
                    }
                }, complete = { images ->
                    if (title.isNotBlank() || text.isNotBlank() || images.isNotEmpty()) {
                        if (images.isEmpty()) graph.submitCapture {
                            try { graph.captureNotification(captured) } catch (error: Exception) { graph.captureFailed(error) }
                        } else {
                            try { graph.captureNotification(captured.copy(images = images)) }
                            catch (error: Exception) { graph.captureFailed(error) }
                        }
                    }
                }, onFailure = graph::captureFailed)

        } catch (error: Exception) { graph.captureFailed(error) }
    }

}
