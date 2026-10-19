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
    val channelId: String? = null,
    val imageWarning: Boolean = false,
)

object ListenerState {
    internal val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
}

class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerConnected() {
        activeListener = java.lang.ref.WeakReference(this)
        MessageGraph.get(this).diagnostics.record(DiagnosticEvent.LISTENER_CONNECTED)
        ListenerState.mutableConnected.value = true
        val graph = MessageGraph.get(this)
        graph.submitCapture {
            try { graph.recover() } catch (error: Exception) { graph.captureFailed(error) }
        }
    }

    override fun onListenerDisconnected() {
        if (activeListener.get() === this) activeListener.clear()
        MessageGraph.get(this).diagnostics.record(DiagnosticEvent.LISTENER_DISCONNECTED)
        ListenerState.mutableConnected.value = false
    }

    override fun onDestroy() {
        if (activeListener.get() === this) activeListener.clear()
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
            val captured = notificationContent(sbn)
            val title = captured.title
            val text = captured.text
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

    companion object {
        @Volatile private var activeListener = java.lang.ref.WeakReference<NotificationCaptureService>(null)

        internal fun previewCandidates(packages: Set<String>): List<CapturedNotification> =
            requireNotNull(activeListener.get()) { "Notification listener disconnected" }.activeNotifications
                .filter { it.packageName in packages && it.isClearable &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .sortedByDescending { it.postTime }.take(20).map(::notificationContent)

        internal fun preview(key: String, config: ForwardingSettings): CapturedNotification {
            val listener = requireNotNull(activeListener.get()) { "Notification listener disconnected" }
            val current = listener.getActiveNotifications(arrayOf(key)).firstOrNull()
                ?: error("Notification is no longer available")
            require(current.packageName in config.packages && current.packageName != listener.packageName)
            var imageWarning = false
            val images = notificationImages(listener, current.notification, config, current.packageName) { imageWarning = true }
            return notificationContent(current).copy(images = images, imageWarning = imageWarning)
        }
    }

}

internal fun notificationContent(sbn: StatusBarNotification): CapturedNotification {
    val extras = sbn.notification.extras
    val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") ?: ""
    return CapturedNotification(sbn.key, sbn.packageName,
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), text, sbn.postTime,
        channelId = sbn.notification.channelId)
}
