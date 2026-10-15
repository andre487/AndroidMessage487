package life.andre.message487

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

internal const val MAX_NOTIFICATION_IMAGES = 3
internal const val MAX_NOTIFICATION_IMAGE_BYTES = 256 * 1024
internal const val MAX_NOTIFICATION_IMAGES_BYTES = 512 * 1024
private const val MAX_IMAGE_EDGE = 1024
private const val MAX_IMAGE_INPUT_BYTES = 8 * 1024 * 1024

enum class NotificationImageFormat { JPEG, PNG }

data class NotificationImage(val mimeType: String, val width: Int, val height: Int, val file: File,
    val kind: String = "attachment", val checksum: String = imageFileHash(file), val presentation: String = "file") {
    fun toJson(): JSONObject = JSONObject().put("mime_type", mimeType).put("width", width)
        .put("height", height).put("kind", kind).put("presentation", presentation)
    override fun toString(): String = "NotificationImage(redacted)"
}

internal fun imageFileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return Base64.getEncoder().encodeToString(digest.digest())
}

internal fun notificationImagesChecksum(images: List<NotificationImage>): String = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(images.map { it.kind + ":" + it.presentation + ":" + it.checksum }
        .sorted().joinToString("\n").toByteArray(Charsets.UTF_8)))

internal fun discardImages(images: List<NotificationImage>) {
    images.forEach { it.file.delete() }
    images.mapNotNull { it.file.parentFile }.distinct().forEach { if (it.name.startsWith("capture-")) it.delete() }
}

private fun workingPixelLimit(): Long = minOf(16_777_216L, Runtime.getRuntime().maxMemory() / 32)

internal fun encodeNotificationImage(bitmap: Bitmap, format: NotificationImageFormat, quality: Int,
    directory: File, maxBytes: Long = MAX_NOTIFICATION_IMAGE_BYTES.toLong(), maxEdge: Int = MAX_IMAGE_EDGE,
    orientation: Int = ExifInterface.ORIENTATION_NORMAL, checkActive: () -> Unit = {}): NotificationImage {
    require(quality in 0..100 && maxBytes > 0 && maxEdge > 0)
    val ratio = minOf(1.0, maxEdge.toDouble() / maxOf(bitmap.width, bitmap.height))
    var width = maxOf(1, (bitmap.width * ratio).toInt())
    var height = maxOf(1, (bitmap.height * ratio).toInt())
    require(width.toLong() * height <= workingPixelLimit()) { "Image exceeds working memory budget" }
    directory.mkdirs()
    val file = File.createTempFile("image-", if (format == NotificationImageFormat.JPEG) ".jpg" else ".png", directory)
    var complete = false
    try {
        while (true) {
            checkActive()
            // Re-render from the source: no simultaneous old/new scaling buffers, no accumulated resampling loss.
            var software: Bitmap? = null
            var working: Bitmap? = null
            try {
                val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
                    require(bitmap.width.toLong() * bitmap.height <= workingPixelLimit()) { "Hardware image exceeds working memory budget" }
                    bitmap.copy(Bitmap.Config.ARGB_8888, false).also { software = it }
                } else bitmap
                val transform = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(90f); postScale(1f, -1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                    }
                }
                val bounds = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
                transform.mapRect(bounds)
                transform.postTranslate(-bounds.left, -bounds.top)
                working = Bitmap.createBitmap(bounds.width().roundToInt(), bounds.height().roundToInt(), Bitmap.Config.ARGB_8888)
                Canvas(working).apply {
                    if (format == NotificationImageFormat.JPEG) drawColor(Color.WHITE)
                    concat(transform)
                    drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                file.outputStream().use { output ->
                    check(working.compress(if (format == NotificationImageFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                        if (format == NotificationImageFormat.JPEG) quality else 100, output)) { "Image encoding failed" }
                }
                checkActive()
                if (file.length() <= maxBytes) {
                    java.io.FileOutputStream(file, true).use { it.fd.sync() }
                    checkActive()
                    val image = NotificationImage(if (format == NotificationImageFormat.JPEG) "image/jpeg" else "image/png",
                        working.width, working.height, file)
                    complete = true
                    return image
                }
            } finally {
                working?.recycle()
                software?.recycle()
            }
            check(width > 1 || height > 1) { "Image cannot fit the size limit" }
            width = maxOf(1, width * 3 / 4)
            height = maxOf(1, height * 3 / 4)
        }
    } finally { if (!complete) file.delete() }
}

private fun decodeImageFile(file: File, maxEdge: Int, limited: Boolean): Bitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 1 }
    BitmapFactory.decodeFile(file.path, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Invalid image" }
    fun pixels() = ((options.outWidth.toLong() + options.inSampleSize - 1) / options.inSampleSize) *
        ((options.outHeight.toLong() + options.inSampleSize - 1) / options.inSampleSize)
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge ||
        (limited && pixels() > workingPixelLimit())) options.inSampleSize *= 2
    require(pixels() <= workingPixelLimit()) { "Image exceeds working memory budget" }
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.path, options) ?: error("Cannot decode image")
}

@Suppress("DEPRECATION")
internal fun notificationImages(context: Context, notification: Notification, settings: ForwardingSettings,
    sourcePackage: String = context.packageName, cancellation: CancellationSignal = CancellationSignal(),
    onFailure: (Exception) -> Unit): List<NotificationImage> {
    val directory = File(context.noBackupFilesDir, "outbox/capture-${UUID.randomUUID()}").apply { mkdirs() }
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
    fun checkActive() {
        cancellation.throwIfCanceled()
        if (System.nanoTime() >= deadline) throw InterruptedIOException("Image capture timed out")
    }
    val candidates = mutableListOf<Pair<NotificationImage, Long>>()
    val selected = mutableListOf<NotificationImage>()
    val limited = settings.imageLimitsEnabled
    val edge = if (limited) settings.imageMaxEdge else Int.MAX_VALUE
    val imageBytes = if (limited) settings.imageMaxKiB.toLong() * 1024 else Long.MAX_VALUE
    fun add(bitmap: Bitmap, kind: String = "attachment", time: Long = Long.MAX_VALUE, orientation: Int = ExifInterface.ORIENTATION_NORMAL, presentation: String = "file") {
        checkActive()
        val image = encodeNotificationImage(bitmap, settings.imageFormat, settings.imageQuality, directory,
            imageBytes, edge, orientation, ::checkActive).copy(kind = kind, presentation = presentation)
        if (candidates.any { it.first.kind == kind && it.first.presentation == presentation && it.first.checksum == image.checksum }) image.file.delete()
        else candidates += image to time
    }
    fun addUri(uri: Uri, kind: String = "attachment", time: Long = Long.MAX_VALUE, presentation: String = "file") {
        require(uri.scheme == "content") { "Only content image URIs are supported" }
        checkActive()
        val source = File.createTempFile("source-", ".image", directory)
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)?.use { asset ->
                cancellation.setOnCancelListener { runCatching { asset.close() } }
                try {
                    asset.createInputStream().use { input -> source.outputStream().use { output ->
                        val regular = asset.parcelFileDescriptor.statSize >= 0
                        val poll = StructPollfd().apply { fd = asset.parcelFileDescriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            checkActive()
                            if (!regular && Os.poll(arrayOf(poll), 100) == 0) continue
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMAGE_INPUT_BYTES) { "Image input too large" }
                            output.write(buffer, 0, count)
                        }
                    } }
                } finally { cancellation.setOnCancelListener(null) }
            } ?: error("Image is unavailable")
            val orientation = runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val bitmap = decodeImageFile(source, edge, limited)
            try { add(bitmap, kind, time, orientation, presentation) } finally { bitmap.recycle() }
        } finally { source.delete() }
    }
    fun addDrawable(drawable: Drawable, kind: String, presentation: String = "file") {
        if (drawable is BitmapDrawable) { add(drawable.bitmap, kind, presentation = presentation); return }
        val ratio = minOf(1.0, edge.toDouble() / maxOf(1, drawable.intrinsicWidth, drawable.intrinsicHeight))
        val width = maxOf(1, (drawable.intrinsicWidth * ratio).toInt())
        val height = maxOf(1, (drawable.intrinsicHeight * ratio).toInt())
        require(width.toLong() * height <= workingPixelLimit()) { "Icon exceeds working memory budget" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bounds = drawable.copyBounds()
        try { drawable.setBounds(0, 0, width, height); drawable.draw(Canvas(bitmap)); add(bitmap, kind, presentation = presentation) }
        finally { drawable.bounds = bounds; bitmap.recycle() }
    }
    fun addIcon(icon: IconCompat, kind: String, presentation: String = "file") {
        checkActive()
        if (icon.type in setOf(IconCompat.TYPE_URI, IconCompat.TYPE_URI_ADAPTIVE_BITMAP)) addUri(icon.uri, kind, presentation = presentation)
        else {
            require(icon.type in setOf(IconCompat.TYPE_BITMAP, IconCompat.TYPE_ADAPTIVE_BITMAP, IconCompat.TYPE_RESOURCE)) { "Unsupported picture icon" }
            addDrawable(icon.loadDrawable(context) ?: error("Icon unavailable"), kind, presentation)
        }
    }
    fun attempt(block: () -> Unit) { try { checkActive(); block() } catch (error: Exception) { onFailure(error) } }
    var transferred = false
    try {
        val extras = notification.extras
        val messages = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages.orEmpty() }
            catch (error: Exception) { onFailure(error); emptyList() }
        attempt { (extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)?.let { add(it, presentation = "big_picture") } }
        if (candidates.none { it.first.kind == "attachment" } && Build.VERSION.SDK_INT >= 31) attempt {
            (extras.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon)?.let { addIcon(requireNotNull(IconCompat.createFromIcon(context, it)), "attachment", "big_picture") }
        }
        for (message in messages.filter { it.dataMimeType?.startsWith("image/") == true }
            .sortedByDescending { it.timestamp }.distinctBy { it.dataUri }) {
            val attachments = candidates.filter { it.first.kind == "attachment" }
            // Only timestamp ties need additional hashes once enough successful, distinct attachments are ready.
            if (limited && attachments.size >= settings.imageMaxCount && message.timestamp < attachments.minOf { it.second }) break
            message.dataUri?.let { uri -> attempt { addUri(uri, time = message.timestamp, presentation = "gallery") } }
        }
        if (settings.forwardAvatars) attempt {
            val sender = messages.filter { it.person?.icon != null }.sortedWith(
                compareByDescending<NotificationCompat.MessagingStyle.Message> { it.timestamp }
                    .thenBy { it.person?.key ?: it.person?.name?.toString().orEmpty() }).firstOrNull()?.person?.icon
            if (sender != null) addIcon(sender, "avatar")
            else notification.getLargeIcon()?.let { addIcon(requireNotNull(IconCompat.createFromIcon(context, it)), "avatar") }
                ?: (extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let { add(it, "avatar") }
        }
        if (settings.forwardAppIcons) attempt { addDrawable(context.packageManager.getApplicationIcon(sourcePackage), "app_icon") }
        var remaining = if (limited) settings.imageTotalKiB.toLong() * 1024 else Long.MAX_VALUE
        // Canonical order precedes count/size limits so input permutations cannot change encoding or selection.
        for ((candidate, _) in candidates.sortedWith(compareBy<Pair<NotificationImage, Long>> {
            when (it.first.kind) { "attachment" -> 0; "avatar" -> 1; else -> 2 }
        }.thenByDescending { it.second }.thenBy { it.first.checksum })) {
            if (limited && candidate.kind == "attachment" && selected.count { it.kind == "attachment" } >= settings.imageMaxCount) continue
            if (remaining <= 0) break
            attempt {
                val image = if (candidate.file.length() <= remaining) candidate else {
                    val bitmap = decodeImageFile(candidate.file, edge, limited)
                    try { encodeNotificationImage(bitmap, settings.imageFormat, settings.imageQuality, directory,
                        remaining, edge, checkActive = ::checkActive).copy(kind = candidate.kind, presentation = candidate.presentation) } finally { bitmap.recycle() }
                }
                selected += image
                remaining -= image.file.length()
            }
        }
        checkActive()
        val keep = selected.map { it.file }.toSet()
        directory.listFiles()?.filter { it !in keep }?.forEach { it.delete() }
        if (selected.isEmpty()) directory.delete()
        transferred = true
        return selected
    } finally { if (!transferred) directory.deleteRecursively() }
}
