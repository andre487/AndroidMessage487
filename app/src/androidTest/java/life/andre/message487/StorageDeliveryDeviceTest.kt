package life.andre.message487

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageDeliveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = AppSource("synthetic.source", "Synthetic source")
    private val token = "synthetic-device-token"

    @Before fun clearStorage() {
        context.deleteDatabase("outbox.db")
        File(context.noBackupFilesDir, "outbox").deleteRecursively()
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanStorage() = clearStorage()

    @Test fun keystoreEncryptsSettingsAndRejectsTampering() {
        val cipher = KeystorePayloadCipher()
        val encrypted = cipher.encrypt(token)
        assertEquals(token, KeystorePayloadCipher().decrypt(encrypted))
        assertFalse(encrypted.contentEquals(cipher.encrypt(token)))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt(encrypted) }
        SettingsStore(context).update { it.copy(authToken = token, url = "https://example.test/hook") }
        val reopened = SettingsStore(context).load()
        assertEquals(token, reopened.authToken)
        assertTrue(reopened.ready())
        assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).all.toString().contains(token))
    }

    @Test fun encryptedQueueSurvivesReopenAndRetriesWithOriginalIdentity() {
        val event = MessageEvent("device", "emulator", source, messageType = "sms", text = "Synthetic SMS Привет")
        val settings = ForwardingSettings(url = "https://example.test/hook", authToken = token)
        // SQLiteOpenHelper implements AutoCloseable only from API 29.
        val outbox = Outbox(context, KeystorePayloadCipher())
        try {
            assertTrue(outbox.enqueue(event, settings))
            outbox.readableDatabase.rawQuery("SELECT payload,payload_file FROM events", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertTrue(rows.isNull(0))
                val raw = File(outbox.files, rows.getString(1) + "/request.enc").readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(raw.contains(token))
                assertFalse(raw.contains("Synthetic SMS"))
            }
            val attempt = outbox.beginAttempt(event.eventId)!!
            outbox.finish(event.eventId, attempt.token, DeliveryResult(event.eventId, DeliveryStatus.TIMEOUT))
        } finally {
            outbox.close()
        }
        val reopened = Outbox(context, KeystorePayloadCipher())
        try {
            assertFalse(reopened.enqueue(event.copy(eventId = "duplicate"), settings))
            val retry = reopened.beginAttempt(event.eventId)!!
            assertEquals(token, retry.request.authToken)
            assertEquals(settings.url, retry.request.url)
            assertEquals(event.text, JSONObject(retry.request.json).getString("text"))
            assertEquals(2, reopened.entries().single().attempts)
            reopened.finish(event.eventId, retry.token, DeliveryResult(event.eventId, DeliveryStatus.ACCEPTED, 200))
            assertEquals(0, reopened.pendingCount())
            reopened.readableDatabase.rawQuery("SELECT payload FROM events", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertTrue(rows.isNull(0))
            }
        } finally {
            reopened.close()
        }
    }

    @Test fun androidHttpTransportRequiresMatchingAckAndDoesNotFollowRedirects() {
        MockWebServer().use { server ->
            server.start()
            val event = MessageEvent("device", "emulator", source)
            val client = WebhookClient()
            val url = server.url("/receive").toString()
            server.enqueue(MockResponse().setBody("""{"status":"accepted","event_id":"${event.eventId}"}"""))
            assertEquals(DeliveryStatus.ACCEPTED, client.send(url, event, true, token).status)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertEquals(event.eventId, JSONObject(request.body.readUtf8()).getString("event_id"))
            server.enqueue(MockResponse().setBody("""{"status":"accepted","event_id":"wrong"}"""))
            assertEquals(DeliveryStatus.INVALID_ACK, client.send(url, event, true, token).status)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url))
            assertEquals(DeliveryStatus.HTTP_ERROR, client.send(url, event, true, token).status)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun largeImageFilesStreamWithoutCursorWindowAndAreRemovedOnlyAfterAck() {
        val file = File(context.cacheDir, "large-image-fixture").apply { outputStream().use { output ->
            repeat(384) { output.write(ByteArray(8192) { (it % 251).toByte() }) }
        } }
        val image = NotificationImage("image/png", 1024, 1024, file)
        val event = MessageEvent("device", "emulator", source, messageType = "notification", images = listOf(image))
        val outbox = Outbox(context, KeystorePayloadCipher())
        try {
            MockWebServer().use { server ->
                server.start()
                val settings = ForwardingSettings(url = server.url("/receive").toString(), authToken = token)
                assertTrue(outbox.enqueue(event, settings))
                assertEquals(1, outbox.entries().size)
                outbox.readableDatabase.rawQuery("SELECT payload FROM events", null).use {
                    assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
                }
                val attempt = outbox.beginAttempt(event.eventId)!!
                val saved = attempt.request.images.single().file
                assertArrayEquals(file.readBytes(), saved.readBytes())
                server.enqueue(MockResponse().setBody("{\"status\":\"accepted\",\"event_id\":\"${event.eventId}\"}"))
                val result = WebhookClient().sendRequest(attempt.request, event.eventId)
                assertEquals(DeliveryStatus.ACCEPTED, result.status)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("chunked", request.getHeader("Transfer-Encoding"))
                val encoded = JSONObject(request.body.readUtf8()).getJSONArray("images").getJSONObject(0).getString("data_base64")
                assertArrayEquals(file.readBytes(), java.util.Base64.getDecoder().decode(encoded))
                outbox.finish(event.eventId, attempt.token, result)
                assertFalse(saved.exists())
            }
        } finally { outbox.close(); file.delete() }
    }

    @Test fun contentUriExifOrientationIsAppliedBeforeForwarding() {
        val input = File(context.cacheDir, "updates/exif-fixture.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        input.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        // Inject a standard EXIF APP1 segment; API 26's writer rejects some otherwise readable encoder output.
        val jpeg = input.readBytes()
        val exif = "4578696600004d4d002a00000008000101120003000000010006000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        input.writeBytes(jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte()) + exif + jpeg.copyOfRange(2, jpeg.size))
        assertEquals(6, androidx.exifinterface.media.ExifInterface(input.path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", input)
        val style = android.app.Notification.MessagingStyle("self").addMessage(
            android.app.Notification.MessagingStyle.Message("", 1L, "sender").setData("image/jpeg", uri))
        val notification = android.app.Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build()
        val images = notificationImages(context, notification, ForwardingSettings(imageFormat = NotificationImageFormat.PNG)) { throw it }
        try { assertEquals(48, images.single().width); assertEquals(64, images.single().height) }
        finally { discardImages(images); input.delete() }
    }

    @Test fun blockedContentPipeExpiresAndStillCompletesTextCallback() {
        val uri = android.net.Uri.parse("content://life.andre.message487.test.images/slow")
        context.contentResolver.call(uri, "release", null, null)
        val style = android.app.Notification.MessagingStyle("self").addMessage(
            android.app.Notification.MessagingStyle.Message("", 1L, "sender").setData("image/png", uri))
        val notification = android.app.Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build()
        val delivered = java.util.concurrent.CountDownLatch(1)
        val callbackCount = java.util.concurrent.atomic.AtomicInteger()
        val queue = NotificationImageQueue(timeoutMs = 500)
        try {
            queue.submit("slow", { signal -> notificationImages(context, notification, ForwardingSettings(), cancellation = signal) { } },
                { images -> if (images.isEmpty()) { callbackCount.incrementAndGet(); delivered.countDown() } }, { })
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            assertEquals(1, callbackCount.get())
            val opened = context.contentResolver.call(uri, "opened", null, null)!!.getInt("count")
            assertTrue("Provider must have opened a blocking pipe", opened > 0)
        } finally { context.contentResolver.call(uri, "release", null, null); queue.close() }
    }

    @Test fun fileProviderSharesApkButRejectsPrivateSettings() {
        val apk = File(context.cacheDir, "updates/device-test.apk")
        apk.parentFile!!.mkdirs()
        try {
            apk.writeText("Synthetic APK fixture")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            assertEquals("content", uri.scheme)
            assertEquals("Synthetic APK fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(context.filesDir, "connection.xml"))
            }
        } finally {
            apk.delete()
        }
    }
}
