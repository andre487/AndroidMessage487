package life.andre.message487

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class SettingsStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val cipher = object : PayloadCipher {
        override fun encrypt(value: String) = value.reversed().toByteArray()
        override fun decrypt(value: ByteArray) = String(value).reversed()
    }

    @Test fun `image limits are validated and survive disabling and restart`() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context, cipher).also { it.load() }
        assertTrue(store.state.value.imageLimitsEnabled)
        store.update { it.copy(imageMaxCount = 7, imageMaxEdge = 2048, imageMaxKiB = 1024, imageTotalKiB = 4096,
            imageLimitsEnabled = false) }
        val restored = SettingsStore(context, cipher).load()
        assertFalse(restored.imageLimitsEnabled)
        assertEquals(7, restored.imageMaxCount)
        assertEquals(2048, restored.imageMaxEdge)
        assertEquals(1024, restored.imageMaxKiB)
        assertEquals(4096, restored.imageTotalKiB)
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(imageMaxCount = 0) } }
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(imageMaxEdge = -1) } }
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(imageMaxKiB = 0) } }
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(imageTotalKiB = -1) } }
    }

    @Test fun `image format defaults to JPEG and quality settings survive restart`() {
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context, cipher).also { it.load() }
        assertEquals(NotificationImageFormat.JPEG, store.state.value.imageFormat)
        assertEquals(80, store.state.value.imageQuality)
        assertFalse(store.state.value.forwardAvatars)
        assertFalse(store.state.value.forwardAppIcons)
        store.update { it.copy(imageFormat = NotificationImageFormat.PNG, imageQuality = 35, forwardAvatars = true, forwardAppIcons = true) }
        assertEquals(NotificationImageFormat.PNG, SettingsStore(context, cipher).load().imageFormat)
        assertEquals(35, SettingsStore(context, cipher).load().imageQuality)
        assertTrue(SettingsStore(context, cipher).load().forwardAvatars)
        assertTrue(SettingsStore(context, cipher).load().forwardAppIcons)
        store.update { it.copy(forwardAvatars = false) }
        assertFalse(SettingsStore(context, cipher).load().forwardAvatars)
        assertTrue(SettingsStore(context, cipher).load().forwardAppIcons)
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(imageQuality = -1) } }
        assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(imageQuality = 101) } }
    }

    @Test fun `new app selection is opt in persistent and respects manual exclusions`() {
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context, cipher).also { it.load() }
        assertFalse(store.state.value.autoSelectNewApps)
        store.discoverPackages(setOf("existing.app", "own.app"), "own.app")
        assertTrue(store.state.value.packages.isEmpty())
        store.update { it.copy(autoSelectNewApps = true, packages = setOf("manual.hidden")) }
        store.discoverPackages(setOf("existing.app", "new.app", "own.app"), "own.app")
        assertEquals(setOf("manual.hidden", "new.app"), store.state.value.packages)
        assertFalse("own.app" in store.state.value.knownPackages)
        store.update { it.copy(packages = it.packages - "new.app") }
        val reopened = SettingsStore(context, cipher).also { it.load() }
        assertTrue(reopened.state.value.autoSelectNewApps)
        reopened.discoverPackages(setOf("existing.app", "new.app"), "own.app")
        assertEquals(setOf("manual.hidden"), reopened.state.value.packages)
        // Clearing selection must not make known apps new again, even after reinstall.
        reopened.update { it.copy(packages = emptySet()) }
        reopened.discoverPackages(emptySet(), "own.app")
        reopened.discoverPackages(setOf("new.app"), "own.app")
        assertTrue(reopened.state.value.packages.isEmpty())
        reopened.update { it.copy(autoSelectNewApps = false) }
        reopened.discoverPackages(setOf("while.off"), "own.app")
        assertTrue(reopened.state.value.packages.isEmpty())
        reopened.update { it.copy(autoSelectNewApps = true) }
        reopened.discoverPackages(setOf("while.off", "later.app"), "own.app")
        assertEquals(setOf("later.app"), SettingsStore(context, cipher).load().packages)
        assertFalse(reopened.state.value.notifications)
        assertFalse(reopened.state.value.acceptsPackage("later.app", "own.app"))
    }

    @Test fun `deduplication defaults on and opt out survives restart`() {
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context, cipher).also { it.load() }
        assertTrue(store.state.value.smsDeduplication)
        assertTrue(store.state.value.notificationDeduplication)
        assertEquals(1, store.state.value.deduplicationWindowSeconds)
        store.update { it.copy(smsDeduplication = false, deduplicationWindowSeconds = 3) }
        val reopened = SettingsStore(context, cipher).also { it.load() }
        assertFalse(reopened.state.value.smsDeduplication)
        assertTrue(reopened.state.value.notificationDeduplication)
        assertEquals(3, reopened.state.value.deduplicationWindowSeconds)
        assertThrows(IllegalArgumentException::class.java) { reopened.update { it.copy(deduplicationWindowSeconds = -1) } }
        reopened.update { it.copy(smsDeduplication = true) }
        assertTrue(SettingsStore(context, cipher).load().smsDeduplication)
    }

    @Test fun `old shared switch migrates without overriding separate switches`() {
        val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        for (enabled in listOf(false, true)) {
            prefs.edit().clear().putBoolean("deduplication", enabled).commit()
            val store = SettingsStore(context, cipher).also { it.load() }
            assertEquals(enabled, store.state.value.smsDeduplication)
            assertEquals(enabled, store.state.value.notificationDeduplication)
            store.update { it.copy(notificationDeduplication = !enabled) }
            val reopened = SettingsStore(context, cipher).also { it.load() }
            assertEquals(enabled, reopened.state.value.smsDeduplication)
            assertEquals(!enabled, reopened.state.value.notificationDeduplication)
            assertFalse(prefs.contains("deduplication"))
        }
    }

    @Test fun `token survives restart through injected encryption and is redacted`() {
        val store = SettingsStore(context, cipher).also { it.load() }
        store.update { it.copy(authToken = "private-token", url = "https://example.test/hook") }
        assertEquals("private-token", SettingsStore(context, cipher).load().authToken)
        val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        assertFalse(prefs.all.toString().contains("private-token"))
        assertFalse(store.state.value.toString().contains("private-token"))
        assertFalse(ConnectionState(authToken = "private-token").toString().contains("private-token"))
        assertTrue(store.state.value.ready())
        assertFalse(store.state.value.copy(authToken = "").ready())
    }

    @Test fun `unreadable token disables capture and unrelated updates preserve ciphertext`() {
        SettingsStore(context, cipher).update { it.copy(authToken = "private-token") }
        val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        val encrypted = prefs.getString("auth_token_encrypted", null)
        val broken = object : PayloadCipher {
            override fun encrypt(value: String): ByteArray = error("Key unavailable")
            override fun decrypt(value: ByteArray): String = error("Key unavailable")
        }
        val store = SettingsStore(context, broken).also { it.load() }
        assertFalse(store.state.value.ready())
        store.update { it.copy(paused = true) }
        assertEquals(encrypted, prefs.getString("auth_token_encrypted", null))
        assertThrows(IllegalStateException::class.java) { store.update { it.copy(authToken = "replacement") } }
        assertEquals("", store.state.value.authToken)
        assertEquals(encrypted, prefs.getString("auth_token_encrypted", null))
    }
    @Test fun `construction does not decrypt and first update preserves unloaded settings`() {
        SettingsStore(context, cipher).update {
            it.copy(url = "https://example.test/preserved", authToken = "preserved-token", deviceCode = "preserved-device")
        }
        var decryptions = 0
        val recordingCipher = object : PayloadCipher {
            override fun encrypt(value: String) = cipher.encrypt(value)
            override fun decrypt(value: ByteArray): String { decryptions++; return cipher.decrypt(value) }
        }
        val deferred = SettingsStore(context, recordingCipher)
        assertFalse(deferred.loaded.value)
        assertEquals(0, decryptions)
        deferred.update { it.copy(imageMaxEdge = 2048) }
        assertTrue(deferred.loaded.value)
        assertEquals(1, decryptions)
        assertEquals("preserved-token", deferred.state.value.authToken)
        assertEquals("preserved-device", deferred.state.value.deviceCode)
        assertEquals("https://example.test/preserved", deferred.state.value.url)
        assertEquals(2048, SettingsStore(context, cipher).load().imageMaxEdge)
        deferred.load()
        assertEquals(1, decryptions)
    }

}
