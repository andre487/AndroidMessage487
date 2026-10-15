package life.andre.message487

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.io.OutputStream
import java.io.IOException
import java.util.Base64
import java.io.FilterOutputStream
import java.security.MessageDigest

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
    val images: List<NotificationImage> = emptyList(),
) {
    /** Image bytes are appended separately by QueuedRequest.writeTo to keep the body streaming. */
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

class WebhookClient(private val timeoutMs: Int = 10_000, private val totalTimeoutMs: Long = 120_000) {
    fun send(url: String, event: MessageEvent, requireAck: Boolean, authToken: String): DeliveryResult =
        sendRequest(QueuedRequest(url, requireAck, event.toJson(), authToken, event.images), event.eventId)

    fun sendRequest(request: QueuedRequest, eventId: String, cancellation: android.os.CancellationSignal = android.os.CancellationSignal()): DeliveryResult =
        if (request.images.isEmpty()) sendJson(request.url, eventId, request.json, request.requireAck, request.authToken, cancellation)
        else sendBody(request.url, eventId, request.requireAck, request.authToken, null, cancellation, request::writeTo)

    internal suspend fun sendCancellable(request: QueuedRequest, eventId: String): DeliveryResult =
        cancellableHttp { sendRequest(request, eventId, it) }

    fun sendJson(url: String, eventId: String, json: String, requireAck: Boolean, authToken: String,
        cancellation: android.os.CancellationSignal = android.os.CancellationSignal()): DeliveryResult {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        return sendBody(url, eventId, requireAck, authToken, payload.size.toLong(), cancellation) { it.write(payload) }
    }

    private fun sendBody(url: String, eventId: String, requireAck: Boolean, authToken: String, length: Long?,
        cancellation: android.os.CancellationSignal, write: (OutputStream) -> Unit): DeliveryResult {
        require(validAuthToken(authToken)) { "Invalid authentication token" }
        val start = System.nanoTime()
        var connection: HttpURLConnection? = null
        var httpCode: Int? = null
        val control = HttpRequestControl(cancellation, totalTimeoutMs)
        val status = try {
            control.checkActive()
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            control.attach(connection)
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
            if (length == null) connection.setChunkedStreamingMode(8192) else connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use { output ->
                write(object : FilterOutputStream(output) {
                    override fun write(value: Int) { control.checkActive(); out.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        control.checkActive(); out.write(bytes, offset, length)
                    }
                })
            }
            control.checkActive()
            httpCode = connection.responseCode
            control.checkActive()
            when {
                httpCode !in 200..299 -> DeliveryStatus.HTTP_ERROR
                !requireAck -> DeliveryStatus.HTTP_SUCCESS
                else -> {
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (output.size() <= MAX_ACK_BYTES) {
                            control.checkActive()
                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_ACK_BYTES + 1 - output.size()))
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    control.checkActive()
                    if (bytes.size > MAX_ACK_BYTES) DeliveryStatus.INVALID_ACK
                    else validateAck(String(bytes, StandardCharsets.UTF_8), eventId)
                }
            }
        } catch (error: UnreadableImageException) {
            throw error
        } catch (_: SocketTimeoutException) {
            DeliveryStatus.TIMEOUT
        } catch (_: IOException) {
            try { control.checkActive(); DeliveryStatus.NETWORK_ERROR }
            catch (_: SocketTimeoutException) { DeliveryStatus.TIMEOUT }
        } finally {
            control.close()
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

internal class UnreadableImageException(cause: Exception? = null) : IOException("Queued image is unavailable or changed", cause)

internal fun QueuedRequest.writeTo(output: OutputStream) {
    if (images.isEmpty()) { output.write(json.toByteArray(Charsets.UTF_8)); return }
    require(json.endsWith("}"))
    output.write(json.dropLast(1).toByteArray(Charsets.UTF_8))
    output.write(",\"images\":[".toByteArray())
    images.forEachIndexed { index, image ->
        if (index != 0) output.write(','.code)
        output.write(image.toJson().toString().dropLast(1).toByteArray(Charsets.UTF_8))
        output.write(",\"data_base64\":\"".toByteArray())
        val digest = MessageDigest.getInstance("SHA-256")
        val input = try { image.file.inputStream() } catch (error: IOException) { throw UnreadableImageException(error) }
        input.use {
            Base64.getEncoder().wrap(object : FilterOutputStream(output) {
                override fun write(buffer: ByteArray, offset: Int, length: Int) { output.write(buffer, offset, length) }
                override fun close() { flush() }
            }).use { encoded ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = try { input.read(buffer) } catch (error: IOException) { throw UnreadableImageException(error) }
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    encoded.write(buffer, 0, count)
                }
            }
        }
        if (Base64.getEncoder().encodeToString(digest.digest()) != image.checksum) throw UnreadableImageException()
        output.write("\"}".toByteArray())
    }
    output.write("]}".toByteArray())
}
