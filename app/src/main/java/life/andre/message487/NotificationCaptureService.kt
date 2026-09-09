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
        graph.captureExecutor.execute {
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
                try { graph.captureNotification(captured) } catch (error: Exception) { graph.captureFailed(error) }
            }
        } catch (error: Exception) { graph.captureFailed(error) }
    }

}
