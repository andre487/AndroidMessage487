package life.andre.message487

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
)

object ListenerState {
    internal val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
}

class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerConnected() {
        ListenerState.mutableConnected.value = true
        val graph = MessageGraph.get(this)
        graph.captureExecutor.execute {
            try { graph.recover() } catch (_: Exception) { graph.captureFailed() }
        }
    }

    override fun onListenerDisconnected() { ListenerState.mutableConnected.value = false }

    override fun onDestroy() {
        ListenerState.mutableConnected.value = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val graph = MessageGraph.get(this)
        if (!graph.settings.state.value.acceptsPackage(sbn.packageName, packageName)) return
        if (!sbn.isClearable || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
                ?: ""
            if (title.isBlank() && text.isBlank()) return
            val captured = CapturedNotification(sbn.key, sbn.packageName, title, text, sbn.postTime)
            graph.captureExecutor.execute {
                try { graph.captureNotification(captured) } catch (_: Exception) { graph.captureFailed() }
            }
        } catch (_: Exception) { graph.captureFailed() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val graph = MessageGraph.get(this)
        graph.captureExecutor.execute {
            try { graph.outbox.forgetNotification(digest(sbn.key)) } catch (_: Exception) { graph.captureFailed() }
        }
    }
}
