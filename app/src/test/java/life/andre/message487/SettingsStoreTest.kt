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
        assertTrue(store.state.value.deduplication)
        store.update { it.copy(deduplication = false) }
        val reopened = SettingsStore(context, cipher)
        assertFalse(reopened.state.value.deduplication)
        reopened.update { it.copy(deduplication = true) }
        assertTrue(SettingsStore(context, cipher).state.value.deduplication)
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
