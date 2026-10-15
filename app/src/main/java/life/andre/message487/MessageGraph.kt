package life.andre.message487

import android.app.Application
import android.content.Context
import java.security.MessageDigest
import java.time.Instant
import java.io.File
import life.andre.message487.diagnostics.CrashHandler
import life.andre.message487.diagnostics.DiagnosticLog
import life.andre.message487.diagnostics.DiagnosticEvent

open class MessageApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    internal open val payloadCipher: PayloadCipher by lazy { KeystorePayloadCipher() }
    internal val diagnostics by lazy { DiagnosticLog({ File(filesDir, "logs") }) }
    internal val crashHandler by lazy { CrashHandler(this, diagnostics) }
    val graph by lazy { MessageGraph(this) }

    internal val backgroundExecutor = boundedExecutor("message487-background", 32)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        crashHandler.install()
        backgroundExecutor.submitSafely(::reportLocalFailure) {
            try { crashHandler.load() } catch (error: Exception) { reportLocalFailure(error) }
            diagnostics.record(DiagnosticEvent.APP_STARTED)
            try { cleanupUpdateFiles(File(cacheDir, "updates")) } catch (error: Exception) { reportLocalFailure(error) }
            graph.start()
            UpdateNotifications.schedule(this)
        }
    }
}

class MessageGraph internal constructor(private val context: Application) {
    internal val diagnostics get() = (context as MessageApplication).diagnostics
    val settings = SettingsStore(context)
    val outbox = Outbox(context, (context as MessageApplication).payloadCipher)
    val scheduler by lazy { DeliveryScheduler(context) }
    val captureExecutor = boundedExecutor("message487-notifications", 32)
    internal val smsExecutor = boundedExecutor("message487-sms", 16)
    internal val imageCaptureQueue = NotificationImageQueue()
    private val sources = AppSourceResolver(context.packageManager)

    fun start() {
        submitCapture {
            settings.update { it }
            scheduler.startRecovery()
            recover()
        }
    }

    internal fun submitCapture(block: () -> Unit): Boolean = captureExecutor.submitSafely(::captureFailed, block)

    @Synchronized
    fun recover() {
        settings.load()
        if (settings.state.value.paused) return
        outbox.cleanupFiles()
        var after: String? = null
        var count = 0
        while (!settings.state.value.paused) {
            val pending = outbox.pendingIds(after)
            if (pending.isEmpty()) break
            pending.forEach { scheduler.schedule(it).result.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            count += pending.size
            after = pending.last()
        }
        diagnostics.record(DiagnosticEvent.RECOVERY, count = count)
    }

    fun enqueueTest(options: TestMessageOptions = TestMessageOptions()) {
        val config = settings.load()
        require(config.ready())
        val images = try { testMessageImages(context, config, options) }
            catch (error: ImageMemoryException) { captureFailed(error); emptyList() }
        val event = MessageEvent(config.deviceId, config.deviceCode, sources.resolve(context.packageName), images = images)
        try { enqueue(event, config) } finally { discardImages(images) }
    }

    fun captureNotification(notification: CapturedNotification) {
        val config = settings.load()
        if (!config.acceptsPackage(notification.packageName, context.packageName)) return
        val event = MessageEvent(
            config.deviceId, config.deviceCode, sources.resolve(notification.packageName),
            occurredAt = Instant.ofEpochMilli(notification.postedAt).toString(),
            messageType = "notification", text = notification.text, title = notification.title, images = notification.images,
        )
        enqueue(event, config)
    }

    fun captureSms(sender: String, text: String, timestamp: Long) {
        val config = settings.load()
        if (config.paused || !config.sms || !config.ready()) return
        val event = MessageEvent(
            config.deviceId, config.deviceCode, sources.resolve("android"),
            occurredAt = Instant.ofEpochMilli(timestamp).toString(),
            messageType = "sms", text = text, sender = sender,
        )
        enqueue(event, config)
    }

    private fun enqueue(event: MessageEvent, config: ForwardingSettings) {
        if (outbox.enqueue(event, config)) {
            diagnostics.record(DiagnosticEvent.EVENT_QUEUED, type = event.messageType)
            scheduler.schedule(event.eventId).result.get(30, java.util.concurrent.TimeUnit.SECONDS)
        } else diagnostics.record(DiagnosticEvent.DUPLICATE_SKIPPED, type = event.messageType)
    }

    private val failurePending = java.util.concurrent.atomic.AtomicBoolean()
    fun captureFailed(error: Throwable? = null) {
        if (!failurePending.compareAndSet(false, true)) return
        val accepted = (context as MessageApplication).backgroundExecutor.submitSafely(context::reportLocalFailure) {
            try {
                diagnostics.record(DiagnosticEvent.CAPTURE_FAILED, error = error)
                if (!settings.load().captureFailed) settings.update { it.copy(captureFailed = true) }
            } catch (_: Exception) { }
            finally { failurePending.set(false) }
        }
        if (!accepted) failurePending.set(false)
    }

    companion object {
        fun get(context: Context): MessageGraph = (context.applicationContext as MessageApplication).graph
    }
}

fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
