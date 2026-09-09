package life.andre.message487

import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

enum class DeliveryStatus { ACCEPTED, HTTP_SUCCESS, HTTP_ERROR, INVALID_ACK, TIMEOUT, NETWORK_ERROR }

data class DeliveryResult(
    val eventId: String,
    val status: DeliveryStatus,
    val httpCode: Int? = null,
    val durationMs: Long = 0,
)

data class MessageEvent(
    val deviceId: String,
    val deviceCode: String,
    val source: AppSource,
    val eventId: String = UUID.randomUUID().toString(),
    val occurredAt: String = Instant.now().toString(),
    val messageType: String = "test",
    val text: String = "Message487 connection test",
    val title: String? = null,
    val sender: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("schema_version", 1)
        .put("event_id", eventId)
        .put("device_id", deviceId)
        .put("device_code", deviceCode)
        .put("message_type", messageType)
        .put("occurred_at", occurredAt)
        .put("source", source.packageName)
        .put("source_name", source.name)
        .put("text", text)
        .put("title", title)
        .put("sender", sender)
        .toString()
}

fun validWebhookUrl(value: String, allowLocalHttp: Boolean): Boolean = runCatching {
    val uri = URI(value)
    val local = uri.host?.lowercase() in setOf("10.0.2.2", "127.0.0.1", "localhost")
    uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) &&
        (uri.scheme == "https" || (allowLocalHttp && local && uri.scheme == "http"))
}.getOrDefault(false)

fun validAuthToken(value: String): Boolean = value.length in 1..4096 &&
    value.matches(Regex("[A-Za-z0-9._~+/-]+=*"))

class WebhookClient(private val timeoutMs: Int = 10_000) {
    fun send(url: String, event: MessageEvent, requireAck: Boolean, authToken: String): DeliveryResult =
        sendJson(url, event.eventId, event.toJson(), requireAck, authToken)

    fun sendJson(url: String, eventId: String, json: String, requireAck: Boolean, authToken: String): DeliveryResult {
        require(validAuthToken(authToken)) { "Invalid authentication token" }
        val start = System.nanoTime()
        var connection: HttpURLConnection? = null
        var httpCode: Int? = null
        val status = try {
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $authToken")
            }
            val payload = json.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            httpCode = connection.responseCode
            when {
                httpCode !in 200..299 -> DeliveryStatus.HTTP_ERROR
                !requireAck -> DeliveryStatus.HTTP_SUCCESS
                else -> {
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (output.size() <= MAX_ACK_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_ACK_BYTES + 1 - output.size()))
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > MAX_ACK_BYTES) DeliveryStatus.INVALID_ACK
                    else validateAck(String(bytes, StandardCharsets.UTF_8), eventId)
                }
            }
        } catch (_: SocketTimeoutException) {
            DeliveryStatus.TIMEOUT
        } catch (_: IOException) {
            DeliveryStatus.NETWORK_ERROR
        } finally {
            connection?.disconnect()
        }
        return DeliveryResult(eventId, status, httpCode, (System.nanoTime() - start) / 1_000_000)
    }

    companion object {
        private const val MAX_ACK_BYTES = 65_536

        fun validateAck(body: String, eventId: String): DeliveryStatus = runCatching {
            val json = JSONObject(body)
            if (json.opt("status") == "accepted" && json.opt("event_id") == eventId) {
                DeliveryStatus.ACCEPTED
            } else DeliveryStatus.INVALID_ACK
        }.getOrDefault(DeliveryStatus.INVALID_ACK)
    }
}
