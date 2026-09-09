package life.andre.message487

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class OutboxTest {
    private val context get() = RuntimeEnvironment.getApplication()
    // Database tests inject a codec; Android Keystore is verified on the emulator.
    private val codec = object : PayloadCipher {
        override fun encrypt(value: String) = value.reversed().toByteArray()
        override fun decrypt(value: ByteArray) = String(value).reversed()
    }
    private lateinit var outbox: Outbox
    private val settings = ForwardingSettings(authToken = "test-token", url = "https://original.example/receive", deviceId = "device")
    private fun event(id: String = java.util.UUID.randomUUID().toString()) = MessageEvent(
        "device", "phone", AppSource("example.chat", "Chat"), eventId = id,
        messageType = "notification", title = "Заголовок", text = "Text\nСообщение",
    )

    @Before fun open() {
        context.deleteDatabase("outbox.db")
        outbox = Outbox(context, codec)
    }

    @After fun close() { outbox.close(); context.deleteDatabase("outbox.db") }

    @Test fun `restart preserves payload destination and identity across retries`() {
        val event = event()
        assertTrue(outbox.enqueue(event, settings))
        outbox.close()
        outbox = Outbox(context, codec)
        val first = outbox.beginAttempt(event.eventId)!!
        assertEquals(settings.url, first.request.url)
        assertEquals(settings.authToken, first.request.authToken)
        assertFalse(first.request.toString().contains(settings.authToken))
        assertTrue(first.request.requireAck)
        assertEquals(event.text, JSONObject(first.request.json).getString("text"))
        outbox.finish(event.eventId, first.token, DeliveryResult(event.eventId, DeliveryStatus.TIMEOUT))
        val second = outbox.beginAttempt(event.eventId)!!
        assertEquals(first.request, second.request)
        assertNotEquals(first.token, second.token)
        assertEquals(2, outbox.entries().single().attempts)
    }

    @Test fun `legacy payload without authentication cannot start delivery`() {
        val event = event()
        outbox.enqueue(event, settings)
        val envelope = JSONObject().put("url", settings.url).put("require_ack", true)
            .put("event", JSONObject(event.toJson()))
        val values = android.content.ContentValues().apply { put("payload", codec.encrypt(envelope.toString())) }
        outbox.writableDatabase.update("events", values, "id = ?", arrayOf(event.eventId))
        assertThrows(org.json.JSONException::class.java) { outbox.beginAttempt(event.eventId) }
    }

    @Test fun `success removes payload but retains journal and rejects stale completion`() {
        val event = event()
        outbox.enqueue(event, settings)
        val abandoned = outbox.beginAttempt(event.eventId)!!
        val active = outbox.beginAttempt(event.eventId)!!
        outbox.finish(event.eventId, abandoned.token, DeliveryResult(event.eventId, DeliveryStatus.ACCEPTED))
        assertEquals(QueueState.SENDING, outbox.entries().single().state)
        outbox.finish(event.eventId, active.token, DeliveryResult(event.eventId, DeliveryStatus.ACCEPTED, 200))
        assertEquals(0, outbox.pendingCount())
        assertEquals(QueueState.ACCEPTED, outbox.entries().single().state)
        assertNull(outbox.beginAttempt(event.eventId))
        outbox.readableDatabase.rawQuery("SELECT payload FROM events", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
        }
    }

    @Test fun `notification duplicates survive restart while changed or reposted content is captured`() {
        assertTrue(outbox.enqueue(event(), settings, "key", "content"))
        outbox.close()
        outbox = Outbox(context, codec)
        assertFalse(outbox.enqueue(event(), settings, "key", "content"))
        assertTrue(outbox.enqueue(event(), settings, "key", "changed"))
        outbox.forgetNotification("key")
        assertTrue(outbox.enqueue(event(), settings, "key", "changed"))
        assertEquals(3, outbox.pendingCount())
    }

    @Test fun `invalid acknowledgement blocks automatic delivery until manual retry`() {
        val event = event()
        outbox.enqueue(event, settings)
        val attempt = outbox.beginAttempt(event.eventId)!!
        outbox.finish(event.eventId, attempt.token, DeliveryResult(event.eventId, DeliveryStatus.INVALID_ACK, 200))
        assertTrue(outbox.pendingIds().isEmpty())
        assertEquals(1, outbox.pendingCount())
        assertNull(outbox.beginAttempt(event.eventId))
        assertTrue(outbox.retry(event.eventId))
        assertEquals(attempt.request, outbox.beginAttempt(event.eventId)!!.request)
    }

    @Test fun `cleanup never removes unsent events`() {
        val waiting = event("waiting")
        outbox.enqueue(waiting, settings)
        repeat(105) {
            val event = event()
            outbox.enqueue(event, settings)
            val attempt = outbox.beginAttempt(event.eventId)!!
            outbox.finish(event.eventId, attempt.token, DeliveryResult(event.eventId, DeliveryStatus.HTTP_SUCCESS, 204))
        }
        assertEquals(listOf(waiting.eventId), outbox.pendingIds())
        assertEquals(101, outbox.entries().size)
        assertEquals(waiting.eventId, outbox.entries().first().id)
        assertFalse(outbox.enqueue(waiting, settings))
    }

    @Test fun `failed persistence does not advance notification deduplication`() {
        val broken = Outbox(context, object : PayloadCipher {
            override fun encrypt(value: String): ByteArray = throw java.io.IOException("Storage failure")
            override fun decrypt(value: ByteArray): String = error("Unused")
        })
        broken.use {
            assertThrows(java.io.IOException::class.java) { it.enqueue(event(), settings, "key", "content") }
        }
        assertTrue(outbox.enqueue(event(), settings, "key", "content"))
        assertEquals(1, outbox.pendingCount())
    }
}
