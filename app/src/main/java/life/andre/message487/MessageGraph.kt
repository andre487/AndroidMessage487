package life.andre.message487

import android.app.Application
import android.content.Context
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.io.File
import life.andre.message487.diagnostics.CrashHandler
import life.andre.message487.diagnostics.DiagnosticLog
import life.andre.message487.diagnostics.DiagnosticEvent

open class MessageApplication : Application() {
    internal val diagnostics by lazy { DiagnosticLog(File(filesDir, "logs")) }
    internal val crashHandler by lazy { CrashHandler(this, diagnostics) }
    val graph by lazy { MessageGraph(this) }

    override fun onCreate() {
        super.onCreate()
        crashHandler.install()
        diagnostics.record(DiagnosticEvent.APP_STARTED)
        graph.start()
    }
}

class MessageGraph internal constructor(private val context: Application) {
    internal val diagnostics get() = (context as MessageApplication).diagnostics
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
            } catch (error: Exception) { captureFailed(error) }
        }
    }

    fun recover() {
        if (!settings.state.value.paused) {
            val pending = outbox.pendingIds()
            pending.forEach { scheduler.schedule(it) }
            diagnostics.record(DiagnosticEvent.RECOVERY, count = pending.size)
        }
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
        if (outbox.enqueue(event, config, key, fingerprint)) {
            diagnostics.record(DiagnosticEvent.EVENT_QUEUED, type = event.messageType)
            scheduler.schedule(event.eventId)
        } else diagnostics.record(DiagnosticEvent.DUPLICATE_SKIPPED, type = event.messageType)
    }

    fun captureFailed(error: Throwable? = null) {
        diagnostics.record(DiagnosticEvent.CAPTURE_FAILED, error = error)
        try { settings.update { it.copy(captureFailed = true) } } catch (_: Exception) { }
    }

    companion object {
        fun get(context: Context): MessageGraph = (context.applicationContext as MessageApplication).graph
    }
}

fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
