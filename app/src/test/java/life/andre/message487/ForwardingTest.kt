package life.andre.message487

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForwardingTest {
    @Test fun `capture requires opt in and selected package and excludes self`() {
        val settings = ForwardingSettings(url = "https://example.com/receive", packages = setOf("example.chat", "self"))
        assertFalse(settings.acceptsPackage("example.chat", "self"))
        val enabled = settings.copy(notifications = true)
        assertTrue(enabled.acceptsPackage("example.chat", "self"))
        assertFalse(enabled.acceptsPackage("other.chat", "self"))
        assertFalse(enabled.acceptsPackage("self", "self"))
        assertFalse(enabled.copy(paused = true).acceptsPackage("example.chat", "self"))
        assertFalse(enabled.copy(url = "").acceptsPackage("example.chat", "self"))
        assertFalse(enabled.copy(deviceCode = " ").acceptsPackage("example.chat", "self"))
        assertFalse(settings.sms)
    }

    @Test fun `only transient transport outcomes retry automatically`() {
        for (status in listOf(DeliveryStatus.TIMEOUT, DeliveryStatus.NETWORK_ERROR)) {
            assertEquals(QueueState.RETRY, deliveryQueueState(DeliveryResult("event", status)))
        }
        for (code in listOf(408, 425, 429, 500, 503, 599)) {
            assertEquals(QueueState.RETRY, deliveryQueueState(DeliveryResult("event", DeliveryStatus.HTTP_ERROR, code)))
        }
        for (code in listOf(301, 400, 401, 403, 404, 422)) {
            assertEquals(QueueState.BLOCKED, deliveryQueueState(DeliveryResult("event", DeliveryStatus.HTTP_ERROR, code)))
        }
        assertEquals(QueueState.BLOCKED, deliveryQueueState(DeliveryResult("event", DeliveryStatus.INVALID_ACK, 200)))
    }

    @Test fun `sms serializes sender and Unicode without notification title`() {
        val event = MessageEvent("device", "phone", AppSource("android", "Android System"),
            messageType = "sms", sender = "+15551234567", text = "Привет\nSecond part")
        val json = JSONObject(event.toJson())
        assertEquals("sms", json.getString("message_type"))
        assertEquals(event.sender, json.getString("sender"))
        assertEquals(event.text, json.getString("text"))
        assertFalse(json.has("title"))
    }
}
