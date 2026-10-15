package life.andre.message487

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35], manifest = Config.NONE)
class NotificationImagesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun bitmap(size: Int = 128, seed: Long = 487) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        val random = java.util.Random(seed)
        setPixels(IntArray(size * size) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) },
            0, size, 0, 0, size, size)
    }
    private val inputFiles = mutableMapOf<String, java.io.File>()
    private fun registerImage(uri: Uri, data: ByteArray) {
        val file = java.io.File.createTempFile("input-", ".image", context.cacheDir).apply { writeBytes(data) }
        inputFiles[uri.toString()] = file
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = null
            override fun insert(uri: Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String) = android.os.ParcelFileDescriptor.open(
                requireNotNull(inputFiles[uri.toString()]), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = uri.authority })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(requireNotNull(uri.authority), provider)
    }
    private fun bytes(image: NotificationImage) = image.file.readBytes()
    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private fun extract(notification: Notification, format: NotificationImageFormat = NotificationImageFormat.JPEG,
        failures: MutableList<Exception> = mutableListOf()) = notificationImages(context, notification,
        ForwardingSettings(imageFormat = format), onFailure = failures::add)

    @Test fun `collection checksum uses the SHA256 reference empty hash`() {
        assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", notificationImagesChecksum(emptyList()))
    }

    @Test fun `JPEG quality changes encoded size while PNG keeps transparency and ignores JPEG quality`() {
        val picture = bitmap()
        val low = encodeNotificationImage(picture, NotificationImageFormat.JPEG, 10, context.cacheDir)
        val high = encodeNotificationImage(picture, NotificationImageFormat.JPEG, 100, context.cacheDir)
        assertEquals("image/jpeg", low.mimeType)
        assertEquals(0xff, bytes(low)[0].toInt() and 255)
        assertEquals(0xd8, bytes(low)[1].toInt() and 255)
        assertTrue(bytes(low).size < bytes(high).size)
        assertEquals(128, BitmapFactory.decodeByteArray(bytes(high), 0, bytes(high).size).width)
        picture.eraseColor(Color.TRANSPARENT)
        val first = encodeNotificationImage(picture, NotificationImageFormat.PNG, 0, context.cacheDir)
        val second = encodeNotificationImage(picture, NotificationImageFormat.PNG, 100, context.cacheDir)
        assertEquals(first.checksum, second.checksum)
        assertEquals("image/png", first.mimeType)
        assertEquals(Color.TRANSPARENT, BitmapFactory.decodeByteArray(bytes(first), 0, bytes(first).size).getPixel(0, 0))
        val jpeg = encodeNotificationImage(picture, NotificationImageFormat.JPEG, 80, context.cacheDir)
        assertEquals(Color.WHITE, BitmapFactory.decodeByteArray(bytes(jpeg), 0, bytes(jpeg).size).getPixel(0, 0))
        assertFalse(picture.isRecycled)
        assertFalse(first.toString().contains(imageBase64(first)))
        assertThrows(IllegalArgumentException::class.java) { encodeNotificationImage(picture, NotificationImageFormat.JPEG, 101, context.cacheDir) }
        picture.recycle()
    }

    @Test fun `large PNG is resized to bound dimensions and payload without changing the original bitmap`() {
        val picture = bitmap(1600)
        val image = encodeNotificationImage(picture, NotificationImageFormat.PNG, 80, context.cacheDir)
        assertTrue(image.width <= 1024 && image.height <= 1024)
        assertTrue(bytes(image).size <= MAX_NOTIFICATION_IMAGE_BYTES)
        assertEquals(image.width, BitmapFactory.decodeByteArray(bytes(image), 0, bytes(image).size).width)
        assertEquals(1600, picture.width)
        assertFalse(picture.isRecycled)
        picture.recycle()
    }

    @Test fun `BigPicture is forwarded but avatars are excluded`() {
        val picture = bitmap()
        val notification = Notification().apply {
            extras = Bundle().apply { putParcelable(Notification.EXTRA_PICTURE, picture) }
        }
        assertEquals("big_picture", extract(notification).single().presentation)
        notification.extras = Bundle().apply { putParcelable(Notification.EXTRA_LARGE_ICON, picture) }
        assertTrue(extract(notification).isEmpty())
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            notification.extras = Bundle().apply { putParcelable(Notification.EXTRA_PICTURE_ICON, Icon.createWithBitmap(picture)) }
            assertEquals("image/png", extract(notification, NotificationImageFormat.PNG).single().mimeType)
        }
        picture.recycle()
    }

    @Test fun `avatars and application icons require independent opt ins`() {
        val picture = bitmap()
        val source = "example.sender"
        shadowOf(context.packageManager).setApplicationIcon(source, android.graphics.drawable.BitmapDrawable(context.resources, picture))
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setLargeIcon(picture).build()
        val failures = mutableListOf<Exception>()
        fun images(avatars: Boolean = false, icons: Boolean = false) = notificationImages(
            context, notification, ForwardingSettings(forwardAvatars = avatars, forwardAppIcons = icons), source,
            onFailure = failures::add)
        assertTrue(images().isEmpty())
        assertEquals(listOf("avatar"), images(avatars = true).map { it.kind })
        assertEquals(listOf("app_icon"), images(icons = true).map { it.kind })
        val both = images(avatars = true, icons = true)
        assertEquals(listOf("avatar", "app_icon"), both.map { it.kind })
        both.forEach {
            assertTrue(bytes(it).size <= MAX_NOTIFICATION_IMAGE_BYTES)
            assertEquals(it.kind, it.toJson().getString("kind"))
        }
        assertTrue(failures.toString(), failures.isEmpty())
        assertFalse(picture.isRecycled)
        picture.recycle()
    }

    @Test fun `icons retain source resolution when they fit the configured image limits`() {
        val picture = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val source = "example.sender"
        shadowOf(context.packageManager).setApplicationIcon(source,
            android.graphics.drawable.BitmapDrawable(context.resources, picture))
        val notification = Notification().apply {
            extras = Bundle().apply {
                putParcelable(Notification.EXTRA_PICTURE, picture)
                putParcelable(Notification.EXTRA_LARGE_ICON, picture)
            }
        }
        val failures = mutableListOf<Exception>()
        try {
            for (limited in listOf(true, false)) {
                val images = notificationImages(context, notification, ForwardingSettings(
                    forwardAvatars = true, forwardAppIcons = true,
                    imageFormat = NotificationImageFormat.PNG, imageLimitsEnabled = limited), source,
                    onFailure = failures::add)
                try {
                    assertEquals(listOf("attachment", "avatar", "app_icon"), images.map { it.kind })
                    for (image in images) {
                        val expectedWidth = 800
                        assertEquals(expectedWidth, image.width)
                        assertEquals(expectedWidth / 2, image.height)
                        val decoded = BitmapFactory.decodeFile(image.file.path)
                        try {
                            assertEquals(expectedWidth, decoded.width)
                            assertEquals(expectedWidth / 2, decoded.height)
                        } finally { decoded.recycle() }
                    }
                } finally { discardImages(images) }
            }
            assertTrue(failures.toString(), failures.isEmpty())
            assertFalse(picture.isRecycled)
        } finally { picture.recycle() }
    }

    @Test fun `sender avatar takes priority over large icon`() {
        val picture = bitmap(32)
        val sender = androidx.core.app.Person.Builder().setName("sender")
            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(picture)).build()
        val notification = androidx.core.app.NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(androidx.core.app.NotificationCompat.MessagingStyle("self")
                .addMessage(androidx.core.app.NotificationCompat.MessagingStyle.Message("hello", 1L, sender)))
            .build()
        val failures = mutableListOf<Exception>()
        val images = notificationImages(context, notification, ForwardingSettings(forwardAvatars = true), onFailure = failures::add)
        assertEquals("avatar", images.single().kind)
        assertEquals(32, images.single().width)
        assertTrue(failures.toString(), failures.isEmpty())
        picture.recycle()
    }

    @Test fun `configurable limits bound output and can be disabled`() {
        val picture = bitmap(1200)
        val notification = Notification().apply {
            extras = Bundle().apply { putParcelable(Notification.EXTRA_PICTURE, picture) }
        }
        val failures = mutableListOf<Exception>()
        val limits = ForwardingSettings(imageMaxEdge = 64, imageMaxKiB = 1, imageTotalKiB = 1)
        val limited = notificationImages(context, notification, limits, onFailure = failures::add).single()
        assertTrue(limited.width <= 64 && bytes(limited).size <= 1024)
        val unlimited = notificationImages(context, notification, limits.copy(imageLimitsEnabled = false),
            onFailure = failures::add).single()
        assertEquals(1200, unlimited.width)
        assertTrue(bytes(unlimited).size > 1024)
        val messages = (0..3).map { index ->
            val uri = Uri.parse("content://example.test/distinct/$index")
            val small = bitmap(32).apply { eraseColor(Color.rgb(index * 50, 30, 80)) }
            registerImage(uri, png(small))
            small.recycle()
            Notification.MessagingStyle.Message("", 1L, "sender").setData("image/png", uri)
        }
        val grouped = messaging(messages)
        val bounded = notificationImages(context, grouped, limits.copy(imageMaxCount = 2, imageTotalKiB = 4), onFailure = failures::add)
        assertEquals(failures.joinToString("\n") { it.stackTraceToString() }, 2, bounded.size)
        // Register fresh streams because content providers may expose a single stream per read in this test.
        messages.forEachIndexed { index, message ->
            val small = bitmap(32).apply { eraseColor(Color.rgb(index * 50, 30, 80)) }
            registerImage(message.dataUri!!, png(small))
            small.recycle()
        }
        assertEquals(4, notificationImages(context, grouped, limits.copy(imageLimitsEnabled = false), onFailure = failures::add).size)
        assertTrue(failures.toString(), failures.isEmpty())
        picture.recycle()
    }

    @Test fun `size budgets are order independent and duplicates or broken images do not consume count`() {
        val a = bitmap(128, 1).let { png(it).also { _ -> it.recycle() } }
        val b = bitmap(128, 2).let { png(it).also { _ -> it.recycle() } }
        val settings = ForwardingSettings(imageFormat = NotificationImageFormat.PNG, imageMaxKiB = 8, imageTotalKiB = 6)
        val failures = mutableListOf<Exception>()
        fun extract(order: List<Pair<String, ByteArray>>): List<NotificationImage> {
            val messages = order.map { (name, data) ->
                val uri = Uri.parse("content://example.test/review/$name")
                registerImage(uri, data)
                Notification.MessagingStyle.Message("", 1L, "sender").setData("image/png", uri)
            }
            return notificationImages(context, messaging(messages), settings, onFailure = failures::add)
        }
        val first = extract(listOf("a" to a, "b" to b))
        val reversed = extract(listOf("b" to b, "a" to a))
        assertEquals(2, first.size)
        assertEquals(notificationImagesChecksum(first), notificationImagesChecksum(reversed))
        val duplicate = extract(listOf("a" to a, "b" to b, "a-copy" to a))
        assertEquals(2, duplicate.size)
        assertEquals(notificationImagesChecksum(first), notificationImagesChecksum(duplicate))
        assertTrue(first.sumOf { it.file.length() } <= 6 * 1024)
        val afterBroken = extract(listOf("valid" to a, "bad1" to byteArrayOf(1), "bad2" to byteArrayOf(2), "bad3" to byteArrayOf(3)))
        assertEquals(1, afterBroken.size)
        assertEquals(3, failures.size)
    }

    @Test fun `all EXIF rotations and mirrors are applied without mutating source`() {
        val palette = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val picture = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(64 * 48) { index -> palette[(if (index / 64 >= 24) 2 else 0) + (if (index % 64 >= 32) 1 else 0)] },
                0, 64, 0, 0, 64, 48)
        }
        val expected = listOf(listOf(0,1,2,3), listOf(1,0,3,2), listOf(3,2,1,0), listOf(2,3,0,1),
            listOf(0,2,1,3), listOf(2,0,3,1), listOf(3,1,2,0), listOf(1,3,0,2))
        for (orientation in 1..8) {
            val image = encodeNotificationImage(picture, NotificationImageFormat.PNG, 80, context.cacheDir, orientation = orientation)
            val decoded = BitmapFactory.decodeFile(image.file.path)
            assertEquals(if (orientation >= 5) 48 else 64, decoded.width)
            assertEquals(if (orientation >= 5) 64 else 48, decoded.height)
            val corners = listOf(decoded.width/4 to decoded.height/4, decoded.width*3/4 to decoded.height/4,
                decoded.width/4 to decoded.height*3/4, decoded.width*3/4 to decoded.height*3/4)
            assertEquals("orientation=$orientation", expected[orientation-1], corners.map { (x,y) -> palette.indexOf(decoded.getPixel(x,y)) })
            decoded.recycle()
        }
        assertFalse(picture.isRecycled)
        picture.recycle()
    }

    private fun messaging(messages: List<Notification.MessagingStyle.Message>): Notification =
        Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(Notification.MessagingStyle("self").also { style -> messages.forEach(style::addMessage) }).build()

    @Test fun `messaging attachments are decoded from bounded content URIs and invalid sources do not abort valid images`() {
        val picture = bitmap(32)
        val data = png(picture)
        val uris = (0..3).map { Uri.parse("content://example.test/image/$it") }
        uris.forEach { registerImage(it, data) }
        val messages = uris.map { Notification.MessagingStyle.Message("", 1L, "sender")
            .setData("image/png", it) }
        var notification = messaging(messages)
        // Identical image content is included only once.
        assertEquals("gallery", extract(notification).single().presentation)
        val failures = mutableListOf<Exception>()
        notification = messaging(listOf(
                Notification.MessagingStyle.Message("", 1L, "sender").setData("image/png", Uri.parse("https://example.test/image")),
                Notification.MessagingStyle.Message("", 1L, "sender").setData("image/png", Uri.parse("file:///private/image.png"))))
        assertTrue(extract(notification, failures = failures).isEmpty())
        assertEquals(2, failures.size)
        val oversized = Uri.parse("content://example.test/large")
        registerImage(oversized, ByteArray(8 * 1024 * 1024 + 1))
        notification = messaging(listOf(
                Notification.MessagingStyle.Message("", 1L, "sender").setData("image/png", oversized)))
        assertTrue(extract(notification, failures = failures).isEmpty())
        assertTrue(failures.last().message!!.contains("large"))
        picture.recycle()
    }
}
