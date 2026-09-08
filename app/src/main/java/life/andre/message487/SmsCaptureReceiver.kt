package life.andre.message487

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val graph = MessageGraph.get(context)
        val config = graph.settings.state.value
        if (!config.sms || config.paused || !config.ready()) return
        val pending = goAsync()
        graph.captureExecutor.execute {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
                if (messages.isNotEmpty()) {
                    val sender = messages.first().originatingAddress.orEmpty()
                    graph.captureSms(sender, messages.joinToString("") { it.messageBody.orEmpty() }, messages.first().timestampMillis)
                }
            } catch (error: Exception) {
                graph.captureFailed(error)
            } finally {
                pending.finish()
            }
        }
    }
}
