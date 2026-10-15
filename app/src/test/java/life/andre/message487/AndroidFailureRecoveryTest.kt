package life.andre.message487

import android.app.Application
import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35], manifest = Config.NONE)
class AndroidFailureRecoveryTest {
    @Test fun `bounded notification backlog rejects overflow and leaves SMS independent`() {
        val notifications = boundedExecutor("test-notifications", 1)
        val sms = boundedExecutor("test-sms", 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val smsDone = CountDownLatch(1)
        val failures = AtomicInteger()
        try {
            assertTrue(notifications.submitSafely({ failures.incrementAndGet() }) {
                started.countDown(); release.await(3, TimeUnit.SECONDS)
            })
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(notifications.submitSafely({ failures.incrementAndGet() }) { throw IOException("storage") })
            assertFalse(notifications.submitSafely({ failures.incrementAndGet() }) { fail("overflow ran") })
            assertTrue(sms.submitSafely({ throw it }) { smsDone.countDown() })
            assertTrue(smsDone.await(1, TimeUnit.SECONDS))
            release.countDown()
            notifications.shutdown()
            assertTrue(notifications.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(2, failures.get())
        } finally { release.countDown(); notifications.shutdownNow(); sms.shutdownNow() }
    }

    @Test fun `image allocation failure delivers text and releases shared memory lock`() {
        val delivered = CountDownLatch(2)
        val failures = AtomicInteger()
        NotificationImageQueue().use { queue ->
            queue.submit("oom", { withImageMemory<List<NotificationImage>> { throw OutOfMemoryError("injected") } },
                { assertTrue(it.isEmpty()); delivered.countDown() },
                { assertTrue(it is ImageMemoryException); failures.incrementAndGet() })
            queue.submit("next", { withImageMemory { emptyList() } },
                { delivered.countDown() }, { throw it })
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(1, failures.get())
        }
    }

    @Test fun `corrupt preference fields preserve valid credentials and update checks recover`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val cipher = object : PayloadCipher {
            override fun encrypt(value: String) = value.toByteArray()
            override fun decrypt(value: ByteArray) = String(value)
        }
        val store = SettingsStore(app, cipher)
        store.update { it.copy(url = "https://example.test/webhook", authToken = "secret", deviceId = "device") }
        app.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("image_quality", "broken").putInt("notifications", 1)
            .putInt("deduplication_window_seconds", -100).putString("packages", "broken").commit()
        val restored = SettingsStore(app, cipher).load()
        assertEquals("https://example.test/webhook", restored.url)
        assertEquals("secret", restored.authToken)
        assertEquals("device", restored.deviceId)
        assertEquals(80, restored.imageQuality)
        assertEquals(1, restored.deduplicationWindowSeconds)
        assertFalse(restored.notifications)
        assertTrue(restored.packages.isEmpty())
        app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
            .putInt("source", 1).putString("automatic", "broken").commit()
        // Both the failing request and the final source/preferences checks are contained.
        runBackgroundUpdateCheck(app, UpdateSource.GITHUB) { throw IOException("network") }
        assertEquals("network_error", UpdatePreferences(app).lastBackgroundResult)
    }

    @Test fun `APK cache keeps recent installers and removes abandoned partial downloads only`() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "cleanup-test").apply { mkdirs() }
        fun file(name: String, modified: Long) = File(dir, name).apply { writeText("x"); setLastModified(modified) }
        try {
            val old = file("update-old.apk", 1)
            val previous = file("update-previous.apk", 2)
            val latest = file("update-latest.apk", 3)
            val abandoned = file("update-old.part", 1)
            val active = file("update-active.part", TimeUnit.DAYS.toMillis(2))
            val unrelated = file("other.apk", 1)
            cleanupUpdateFiles(dir, latest, TimeUnit.DAYS.toMillis(2))
            assertFalse(old.exists()); assertFalse(abandoned.exists())
            listOf(previous, latest, active, unrelated).forEach { assertTrue(it.exists()) }
        } finally { dir.deleteRecursively() }
    }
}
