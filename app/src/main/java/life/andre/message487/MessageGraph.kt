package life.andre.message487

import android.app.Application
import android.content.Context
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

class MessageApplication : Application() {
    val graph by lazy { MessageGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.start()
    }
}

class MessageGraph internal constructor(private val context: Application) {
    val settings = SettingsStore(context)
    val outbox = Outbox(context, KeystorePayloadCipher())
    val scheduler by lazy { DeliveryScheduler(context) }
    val captureExecutor = Executors.newSingleThreadExecutor()
    private val sources = AppSourceResolver(context.packageManager)

    fun start() {
        captureExecutor.execute {
            try {
                settings.update { it }
                scheduler.startRecovery()
                recover()
            } catch (_: Exception) { captureFailed() }
        }
    }

    fun recover() {
        if (!settings.state.value.paused) outbox.pendingIds().forEach { scheduler.schedule(it) }
    }

    fun enqueueTest() {
        val config = settings.state.value
        require(config.ready())
        val event = MessageEvent(config.deviceId, config.deviceCode, sources.resolve(context.packageName))
        enqueue(event, config)
    }

    fun captureNotification(notification: CapturedNotification) {
        val config = settings.state.value
        if (!config.acceptsPackage(notification.packageName, context.packageName)) return
        val event = MessageEvent(
            config.deviceId, config.deviceCode, sources.resolve(notification.packageName),
            occurredAt = Instant.ofEpochMilli(notification.postedAt).toString(),
            messageType = "notification", text = notification.text, title = notification.title,
        )
        enqueue(event, config, digest(notification.key), digest(notification.title + "\u0000" + notification.text))
    }

    fun captureSms(sender: String, text: String, timestamp: Long) {
        val config = settings.state.value
        if (config.paused || !config.sms || !config.ready()) return
        val identity = "${config.deviceId}\u0000$sender\u0000$timestamp\u0000$text"
        val event = MessageEvent(
            config.deviceId, config.deviceCode, sources.resolve("android"),
            eventId = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString(),
            occurredAt = Instant.ofEpochMilli(timestamp).toString(),
            messageType = "sms", text = text, sender = sender,
        )
        enqueue(event, config)
    }

    private fun enqueue(event: MessageEvent, config: ForwardingSettings, key: String? = null, fingerprint: String? = null) {
        if (outbox.enqueue(event, config, key, fingerprint)) scheduler.schedule(event.eventId)
    }

    fun captureFailed() {
        try { settings.update { it.copy(captureFailed = true) } } catch (_: Exception) { }
    }

    companion object {
        fun get(context: Context): MessageGraph = (context.applicationContext as MessageApplication).graph
    }
}

fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
