package life.andre.message487

import android.app.Notification
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, sdk = [26, 35])
class DeliveryToolsTest {
    private val source = ForwardingSettings(url = "https://receiver.example/webhook", authToken = "export-token",
        deviceId = "source-installation", deviceCode = "my-phone", notifications = true, sms = true,
        packages = setOf("example.chat", "example.bank"), autoSelectNewApps = true,
        imageFormat = NotificationImageFormat.PNG, imageMaxCount = 6, imageMaxEdge = 2048,
        imageMaxKiB = 1024, imageTotalKiB = 4096, imageQuality = 43, imageLimitsEnabled = false,
        smsDeduplication = false, notificationDeduplication = false, deduplicationWindowSeconds = 4,
        forwardAvatars = true, forwardAppIcons = true, requireAck = false)

    @Test fun `transfer defaults to token included but opt out clears target token and preserves identity`() {
        val current = ForwardingSettings(deviceId = "target-installation", authToken = "old-token",
            knownPackages = setOf("example.excluded"), captureFailed = true)
        val exported = exportSettings(source)
        assertEquals("export-token", JSONObject(exported).getString("auth_token"))
        assertFalse(JSONObject(exported).has("device_id"))
        assertFalse(JSONObject(exported).has("known_packages"))
        val imported = importSettings(exported, current)
        assertEquals(source.copy(deviceId = current.deviceId, knownPackages = current.knownPackages,
            captureFailed = true, paused = true), imported)
        val withoutToken = exportSettings(source, includeToken = false)
        assertFalse(JSONObject(withoutToken).has("auth_token"))
        assertEquals("", importSettings(withoutToken, current).authToken)
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        app.graph.settings.update { source }
        app.graph.settings.update { importSettings(withoutToken, it) }
        assertEquals("", SettingsStore(app, app.payloadCipher).load().authToken)
    }

    @Test fun `invalid imports are rejected before any settings are changed`() {
        val invalid = listOf(
            "version" to 2, "sms" to "true", "image_quality" to 101,
            "image_max_count" to 0, "image_max_edge" to 2.5,
            "image_max_kib" to 2147483648L, "image_format" to "GIF",
            "url" to "http://untrusted.example/", "auth_token" to "secret\nheader",
            "packages" to org.json.JSONArray(listOf("../invalid")),
        )
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        val store = app.graph.settings
        val original = store.update { source }
        for ((key, value) in invalid) {
            val data = JSONObject(exportSettings(source)).put(key, value)
            assertThrows(Exception::class.java) { store.update { importSettings(data.toString(), it) } }
            assertEquals(original, store.load())
        }
        assertThrows(Exception::class.java) { importSettings("{", original) }
        assertThrows(Exception::class.java) { readSettingsFile(ByteArray(MAX_SETTINGS_FILE_BYTES + 1).inputStream()) }
        assertThrows(Exception::class.java) { readSettingsFile(byteArrayOf(0xc3.toByte(), 0x28).inputStream()) }
    }

    @Test fun `channel ids survive Android capture persistence and retry and have sms and test defaults`() {
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        val notification = Notification.Builder(app, "bank-alerts").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Payment").setContentText("Synthetic amount").build()
        val sbn = StatusBarNotification("example.bank", "example.bank", 1, null, 1000, 0, 0,
            notification, android.os.Process.myUserHandle(), 1234)
        val captured = notificationContent(sbn)
        assertEquals("bank-alerts", captured.channelId)
        val event = app.graph.notificationEvent(captured, source)
        assertEquals("sms", JSONObject(event.copy(messageType = "sms").toJson()).getString("channel_id"))
        assertEquals("test", JSONObject(event.copy(messageType = "test").toJson()).getString("channel_id"))
        val queue = app.graph.outbox
        assertTrue(queue.enqueue(event, source))
        assertFalse(app.graph.sendPreview(event, source.copy(notificationDeduplication = true)))
        val first = requireNotNull(queue.beginAttempt(event.eventId))
        assertEquals("bank-alerts", JSONObject(first.request.json).getString("channel_id"))
        queue.finish(event.eventId, first.token, DeliveryResult(event.eventId, DeliveryStatus.NETWORK_ERROR))
        val retry = requireNotNull(queue.beginAttempt(event.eventId))
        assertEquals(first.request.json, retry.request.json)
        assertEquals("sms", JSONObject(MessageEvent("id", "code", AppSource("android", "Android"),
            messageType = "sms").toJson()).getString("channel_id"))
        assertEquals("test", JSONObject(MessageEvent("id", "code", AppSource("example", "Example")).toJson()).getString("channel_id"))
    }

    @Test fun `preparing a preview does not enqueue and paused delivery blocks real notifications`() {
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        val graph = app.graph
        val before = graph.outbox.entries()
        val preview = graph.previewTest(source, TestMessageOptions())
        assertEquals(before, graph.outbox.entries())
        assertEquals("test", JSONObject(preview.toJson()).getString("channel_id"))
        assertFalse(preview.toJson().contains(source.authToken))
        assertThrows(IllegalArgumentException::class.java) {
            graph.settings.update { source.copy(paused = true) }
            graph.sendPreview(preview.copy(messageType = "notification"), source)
        }
        assertEquals(before, graph.outbox.entries())
    }

    @Test fun `diagnosis distinguishes auth acknowledgement temporary and local failures`() {
        val entry = QueueEntry("id", "notification", "Example", 0, QueueState.BLOCKED, 1, DeliveryStatus.HTTP_ERROR.name, 401)
        assertEquals(R.string.check_auth_hint, deliveryAdvice(entry))
        assertEquals(R.string.check_ack_hint, deliveryAdvice(entry.copy(httpCode = 200, outcome = DeliveryStatus.INVALID_ACK.name)))
        assertEquals(R.string.check_retry_hint, deliveryAdvice(entry.copy(httpCode = 503, state = QueueState.RETRY)))
        assertEquals(R.string.check_blocked_hint, deliveryAdvice(entry.copy(httpCode = null, outcome = "LOCAL_ERROR")))
    }
    @Test fun `recovery preserves active preview files and removes them after release`() {
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        val queue = app.graph.outbox
        val directory = java.io.File(queue.files, "capture-preview-check").apply { mkdirs() }
        java.io.File(directory, "image.jpg").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(directory.setLastModified(1))
        queue.previewDirectories.add(directory)
        queue.cleanupFiles()
        assertTrue(directory.exists())
        queue.previewDirectories.remove(directory)
        queue.cleanupFiles()
        assertFalse(directory.exists())
    }

    @Test fun `token excluded import also removes unreadable ciphertext`() {
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        app.graph.settings.update { source }
        val unavailableKey = object : PayloadCipher {
            override fun encrypt(value: String): ByteArray = error("Key unavailable")
            override fun decrypt(value: ByteArray): String = error("Key unavailable")
        }
        val store = SettingsStore(app, unavailableKey)
        assertEquals("", store.load().authToken)
        val imported = importSettings(exportSettings(source, includeToken = false), store.load())
        store.update(clearToken = true) { imported }
        assertFalse(app.getSharedPreferences("connection", android.content.Context.MODE_PRIVATE).contains("auth_token_encrypted"))
        assertEquals("", SettingsStore(app, app.payloadCipher).load().authToken)
    }

    @Test fun `explicit test previews bypass pause without resuming capture or ordinary delivery`() {
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        val tasks = java.util.concurrent.Executors.newSingleThreadExecutor()
        androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder()
            .setExecutor(java.util.concurrent.Executor { }).setTaskExecutor(tasks).build())
        val graph = app.graph
        try {
            val config = graph.settings.update { source.copy(paused = true) }
            val preview = graph.previewTest(config, TestMessageOptions())
            assertTrue(graph.sendPreview(preview, config))
            assertTrue(graph.settings.load().paused)
            val manager = androidx.work.WorkManager.getInstance(app) as androidx.work.impl.WorkManagerImpl
            val work = manager.getWorkInfosForUniqueWork("event-${preview.eventId}").get(5, java.util.concurrent.TimeUnit.SECONDS).single()
            // Room is a runtime dependency of WorkManager, not part of its public compile classpath.
            val database = manager.javaClass.getMethod("getWorkDatabase").invoke(manager)
            val dao = database.javaClass.getMethod("workSpecDao").invoke(database) as androidx.work.impl.model.WorkSpecDao
            val input = tasks.submit<androidx.work.Data> { requireNotNull(dao.getWorkSpec(work.id.toString())).input }
                .get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(input.getBoolean("manual_test", false))
            assertFalse(pauseBlocksDelivery(true, input.getBoolean("manual_test", false)))
            assertTrue(pauseBlocksDelivery(true, false))
            assertFalse(pauseBlocksDelivery(false, false))
            assertEquals(preview.toJson(), requireNotNull(graph.outbox.beginAttempt(preview.eventId)).request.json)
            graph.captureSms("sender", "not captured during pause", 1234)
            assertEquals(1, graph.outbox.entries().size)
        } finally {
            tasks.shutdownNow()
        }
    }

}
