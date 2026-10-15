package life.andre.message487

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

internal fun imageFixture(value: String, mime: String = "image/jpeg", width: Int = 2, height: Int = 2): NotificationImage {
    val file = File.createTempFile("message487-image-fixture-", ".image").apply { writeText(value); deleteOnExit() }
    return NotificationImage(mime, width, height, file)
}

internal fun imageBase64(image: NotificationImage): String = Base64.getEncoder().encodeToString(image.file.readBytes())
internal fun requestJson(request: QueuedRequest): String = ByteArrayOutputStream().also { request.writeTo(it) }.toString("UTF-8")
