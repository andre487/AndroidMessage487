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

    @Test fun `deduplication uses app timestamp and text and survives delivery deletion and restart`() {
        val original = event().copy(occurredAt = "2026-09-09T10:00:00Z")
        assertTrue(outbox.enqueue(original, settings))
        val attempt = outbox.beginAttempt(original.eventId)!!
        outbox.finish(original.eventId, attempt.token, DeliveryResult(original.eventId, DeliveryStatus.ACCEPTED, 200))
        outbox.delete(original.eventId)
        outbox.close()
        outbox = Outbox(context, codec)
        val duplicate = original.copy(eventId = "duplicate", title = "Different title", sender = "Different sender")
        assertFalse(outbox.enqueue(duplicate, settings))
        assertFalse(outbox.enqueue(duplicate.copy(occurredAt = "2026-09-09T10:00:00.000Z"), settings))
        assertTrue(outbox.enqueue(duplicate.copy(eventId = "app", source = AppSource("other.app", "Chat")), settings))
        assertTrue(outbox.enqueue(duplicate.copy(eventId = "time", occurredAt = "2026-09-09T10:00:00.001Z"), settings))
        assertTrue(outbox.enqueue(duplicate.copy(eventId = "text", text = original.text + " "), settings))
        assertEquals(3, outbox.pendingCount())
    }

    @Test fun `opt out allows sms and notification duplicates but preserves event id idempotency`() {
        for (type in listOf("sms", "notification")) {
            val original = event().copy(messageType = type)
            assertTrue(outbox.enqueue(original, settings))
            val duplicate = original.copy(eventId = "duplicate-$type")
            assertFalse(outbox.enqueue(duplicate, settings))
            assertTrue(outbox.enqueue(duplicate, settings.copy(deduplication = false)))
            assertFalse(outbox.enqueue(duplicate, settings.copy(deduplication = false)))
            assertFalse(outbox.enqueue(original.copy(eventId = "reenabled-$type"), settings))
        }
    }

    @Test fun `events captured while opted out are remembered when reenabled and tests are exempt`() {
        val original = event()
        assertTrue(outbox.enqueue(original, settings.copy(deduplication = false)))
        assertFalse(outbox.enqueue(original.copy(eventId = "reenabled"), settings))
        val test = original.copy(eventId = "test-1", messageType = "test")
        assertTrue(outbox.enqueue(test, settings))
        assertTrue(outbox.enqueue(test.copy(eventId = "test-2"), settings))
    }

    @Test fun `version one migration preserves queued payloads`() {
        val original = event()
        outbox.enqueue(original, settings)
        outbox.writableDatabase.apply {
            execSQL("DROP TABLE message_fingerprints")
            execSQL("CREATE TABLE notifications (notification_key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL)")
            version = 1
        }
        outbox.close()
        outbox = Outbox(context, codec)
        assertEquals(original.text, JSONObject(outbox.beginAttempt(original.eventId)!!.request.json).getString("text"))
        assertTrue(outbox.enqueue(event(), settings))
        assertEquals(2, outbox.readableDatabase.version)
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

    @Test fun `failed persistence does not advance message deduplication`() {
        val original = event()
        val broken = Outbox(context, object : PayloadCipher {
            override fun encrypt(value: String): ByteArray = throw java.io.IOException("Storage failure")
            override fun decrypt(value: ByteArray): String = error("Unused")
        })
        broken.use {
            assertThrows(java.io.IOException::class.java) { it.enqueue(original, settings) }
        }
        assertTrue(outbox.enqueue(original, settings))
        assertEquals(1, outbox.pendingCount())
    }
}
