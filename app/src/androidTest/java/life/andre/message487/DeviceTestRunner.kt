package life.andre.message487

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

// Keep production workers and crash handlers out of isolated storage/transport tests.
class DeviceTestApplication : MessageApplication() {
    override val payloadCipher: PayloadCipher = object : PayloadCipher {
        private val delegate = KeystorePayloadCipher()
        private fun checkThread() = check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "Keystore must not run on the UI thread"
        }
        override fun encrypt(value: String): ByteArray { checkThread(); return delegate.encrypt(value) }
        override fun decrypt(value: ByteArray): String { checkThread(); return delegate.decrypt(value) }
    }
    override fun onCreate() {}
}

class DeviceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, name: String, context: Context): Application =
        super.newApplication(loader, DeviceTestApplication::class.java.name, context)
}
