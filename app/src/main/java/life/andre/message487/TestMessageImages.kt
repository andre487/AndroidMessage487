package life.andre.message487

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.util.UUID

data class TestMessageOptions(
    val bigPicture: Boolean = false,
    val gallery: Boolean = false,
    val avatar: Boolean = false,
    val appIcon: Boolean = false,
)

internal fun testMessageImages(context: Context, settings: ForwardingSettings,
    options: TestMessageOptions): List<NotificationImage> {
    if (options == TestMessageOptions()) return emptyList()
    val directory = File(context.noBackupFilesDir, "outbox/capture-${UUID.randomUUID()}").apply { mkdirs() }
    val images = mutableListOf<NotificationImage>()
    val limited = settings.imageLimitsEnabled
    var remaining = if (limited) settings.imageTotalKiB.toLong() * 1024 else Long.MAX_VALUE
    fun add(kind: String, presentation: String = "file", label: String = "", color: Int = Color.BLUE) {
        if (remaining <= 0 || (limited && kind == "attachment" &&
                images.count { it.kind == "attachment" } >= settings.imageMaxCount)) return
        val bitmap = Bitmap.createBitmap(if (kind == "attachment") 640 else 512,
            if (kind == "attachment") 400 else 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            if (kind == "attachment") {
                canvas.drawColor(color)
                canvas.drawText(label, 32f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE; textSize = 56f
                })
            } else {
                val icon = requireNotNull(context.getDrawable(context.applicationInfo.icon))
                icon.setBounds(0, 0, bitmap.width, bitmap.height)
                icon.draw(canvas)
            }
            val image = encodeNotificationImage(bitmap, settings.imageFormat, settings.imageQuality, directory,
                maxBytes = minOf(remaining, if (limited) settings.imageMaxKiB.toLong() * 1024 else Long.MAX_VALUE),
                maxEdge = if (limited) settings.imageMaxEdge else Int.MAX_VALUE)
                .copy(kind = kind, presentation = presentation)
            images += image
            remaining -= image.file.length()
        } finally { bitmap.recycle() }
    }
    try {
        if (options.bigPicture) add("attachment", "big_picture", "Big Picture", Color.rgb(40, 90, 190))
        if (options.gallery) {
            add("attachment", "gallery", "Gallery 1", Color.rgb(40, 140, 90))
            add("attachment", "gallery", "Gallery 2", Color.rgb(150, 60, 160))
        }
        if (options.avatar) add("avatar")
        if (options.appIcon) add("app_icon")
        if (images.isEmpty()) directory.delete()
        return images
    } catch (error: Exception) {
        directory.deleteRecursively()
        throw error
    }
}
