package life.andre.message487

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebhookClientTest {
    private val source = AppSource("example.source", "Source app")
    @Test
    fun `release rejects cleartext and embedded credentials`() {
        assertTrue(validWebhookUrl("https://example.com/webhook/test", false))
        assertFalse(validWebhookUrl("http://10.0.2.2:5678/test", false))
        assertFalse(validWebhookUrl("https://user:password@example.com/test", true))
        assertFalse(validWebhookUrl("https://example.com/test#fragment", true))
        assertFalse(validWebhookUrl("https://example.com:99999/test", true))
        assertFalse(validWebhookUrl("file:///tmp/test", true))
    }

    @Test
    fun `debug permits only local HTTP hosts`() {
        assertTrue(validWebhookUrl("http://10.0.2.2:5678/test", true))
        assertTrue(validWebhookUrl("http://127.0.0.1:5678/test", true))
        assertFalse(validWebhookUrl("http://example.com/test", true))
        assertFalse(validWebhookUrl("http://10.0.2.2.example.com/test", true))
    }

    @Test
    fun `ACK must match status and event id`() {
        assertEquals(DeliveryStatus.ACCEPTED, WebhookClient.validateAck("""{"status":"accepted","event_id":"a"}""", "a"))
        for (body in listOf("", "<html>OK</html>", "{}", """{"status":"accepted","event_id":"b"}""", """{"status":"rejected","event_id":"a"}""")) {
            assertEquals(DeliveryStatus.INVALID_ACK, WebhookClient.validateAck(body, "a"))
        }
    }

    @Test
    fun `HTTP transport sends event and validates confirmation`() = withServer { server ->
        val event = TestEvent(deviceId = "installation", deviceCode = "test-device", source = source)
        server.enqueue(MockResponse().setBody("""{"status":"accepted","event_id":"${event.eventId}"}"""))
        val result = WebhookClient().send(server.url("/receive").toString(), event, true)
        assertEquals(DeliveryStatus.ACCEPTED, result.status)
        assertEquals(200, result.httpCode)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(event.eventId, body.getString("event_id"))
        assertEquals("installation", body.getString("device_id"))
        assertEquals("test-device", body.getString("device_code"))
        assertEquals("example.source", body.getString("source"))
        assertEquals("Source app", body.getString("source_name"))
        assertEquals("test", body.getString("message_type"))
    }

    @Test
    fun `errors redirects and invalid ACK never count as accepted`() = withServer { server ->
        val client = WebhookClient()
        val url = server.url("/receive").toString()
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals(DeliveryStatus.INVALID_ACK, client.send(url, TestEvent("d", "test-device", source), true).status)
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(DeliveryStatus.HTTP_SUCCESS, client.send(url, TestEvent("d", "test-device", source), false).status)
        for (code in listOf(302, 500)) {
            server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", url))
            assertEquals(DeliveryStatus.HTTP_ERROR, client.send(url, TestEvent("d", "test-device", source), true).status)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `oversized ACK is rejected`() = withServer { server ->
        server.enqueue(MockResponse().setBody(" ".repeat(70_000)))
        assertEquals(DeliveryStatus.INVALID_ACK, WebhookClient().send(server.url("/").toString(), TestEvent("d", "test-device", source), true).status)
    }

    @Test
    fun `slow server produces timeout`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(300, TimeUnit.MILLISECONDS))
        assertEquals(DeliveryStatus.TIMEOUT, WebhookClient(50).send(server.url("/").toString(), TestEvent("d", "test-device", source), true).status)
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server)
        }
    }
}
