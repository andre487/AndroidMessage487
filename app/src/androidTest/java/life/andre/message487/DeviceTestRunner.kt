package life.andre.message487

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

// Keep production workers and crash handlers out of isolated storage/transport tests.
class DeviceTestApplication : MessageApplication() {
    override fun onCreate() {}
}

class DeviceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, name: String, context: Context): Application =
        super.newApplication(loader, DeviceTestApplication::class.java.name, context)
}
