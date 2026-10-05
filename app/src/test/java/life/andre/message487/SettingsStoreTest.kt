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

    @Test fun `deduplication defaults on and opt out survives restart`() {
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context, cipher)
        assertTrue(store.state.value.smsDeduplication)
        assertTrue(store.state.value.notificationDeduplication)
        assertEquals(1, store.state.value.deduplicationWindowSeconds)
        store.update { it.copy(smsDeduplication = false, deduplicationWindowSeconds = 3) }
        val reopened = SettingsStore(context, cipher)
        assertFalse(reopened.state.value.smsDeduplication)
        assertTrue(reopened.state.value.notificationDeduplication)
        assertEquals(3, reopened.state.value.deduplicationWindowSeconds)
        assertThrows(IllegalArgumentException::class.java) { reopened.update { it.copy(deduplicationWindowSeconds = -1) } }
        reopened.update { it.copy(smsDeduplication = true) }
        assertTrue(SettingsStore(context, cipher).state.value.smsDeduplication)
    }

    @Test fun `old shared switch migrates without overriding separate switches`() {
        val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        for (enabled in listOf(false, true)) {
            prefs.edit().clear().putBoolean("deduplication", enabled).commit()
            val store = SettingsStore(context, cipher)
            assertEquals(enabled, store.state.value.smsDeduplication)
            assertEquals(enabled, store.state.value.notificationDeduplication)
            store.update { it.copy(notificationDeduplication = !enabled) }
            val reopened = SettingsStore(context, cipher)
            assertEquals(enabled, reopened.state.value.smsDeduplication)
            assertEquals(!enabled, reopened.state.value.notificationDeduplication)
            assertFalse(prefs.contains("deduplication"))
        }
    }

    @Test fun `token survives restart through injected encryption and is redacted`() {
        val store = SettingsStore(context, cipher)
        store.update { it.copy(authToken = "private-token", url = "https://example.test/hook") }
        assertEquals("private-token", SettingsStore(context, cipher).state.value.authToken)
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
        val store = SettingsStore(context, broken)
        assertFalse(store.state.value.ready())
        store.update { it.copy(paused = true) }
        assertEquals(encrypted, prefs.getString("auth_token_encrypted", null))
        assertThrows(IllegalStateException::class.java) { store.update { it.copy(authToken = "replacement") } }
        assertEquals("", store.state.value.authToken)
        assertEquals(encrypted, prefs.getString("auth_token_encrypted", null))
    }
}
