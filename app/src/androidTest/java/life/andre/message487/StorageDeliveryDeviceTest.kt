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
        val reopened = SettingsStore(context).state.value
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
            outbox.readableDatabase.rawQuery("SELECT payload FROM events", null).use { rows ->
                assertTrue(rows.moveToFirst())
                val raw = String(rows.getBlob(0), Charsets.ISO_8859_1)
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
