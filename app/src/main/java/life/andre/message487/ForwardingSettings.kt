package life.andre.message487

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

data class ForwardingSettings(
    val url: String = BuildConfig.DEFAULT_WEBHOOK_URL,
    val deviceId: String = "",
    val deviceCode: String = "android-device",
    val requireAck: Boolean = true,
    val notifications: Boolean = false,
    val sms: Boolean = false,
    val paused: Boolean = false,
    val packages: Set<String> = emptySet(),
    val captureFailed: Boolean = false,
) {
    fun ready(): Boolean = deviceCode.isNotBlank() && validWebhookUrl(url, BuildConfig.DEBUG)
    fun acceptsPackage(packageName: String, ownPackage: String): Boolean =
        !paused && notifications && packageName != ownPackage && packageName in packages && ready()
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    private fun read() = ForwardingSettings(
        url = preferences.getString("url", BuildConfig.DEFAULT_WEBHOOK_URL).orEmpty(),
        deviceId = preferences.getString("device_id", "").orEmpty(),
        deviceCode = preferences.getString("device_code", "android-device").orEmpty(),
        requireAck = preferences.getBoolean("require_ack", true),
        notifications = preferences.getBoolean("notifications", false),
        sms = preferences.getBoolean("sms", false),
        paused = preferences.getBoolean("paused", false),
        packages = preferences.getStringSet("packages", emptySet()).orEmpty().toSet(),
        captureFailed = preferences.getBoolean("capture_failed", false),
    )

    @Synchronized
    fun update(transform: (ForwardingSettings) -> ForwardingSettings): ForwardingSettings {
        val next = transform(mutableState.value).let {
            if (it.deviceId.isBlank()) it.copy(deviceId = UUID.randomUUID().toString()) else it
        }
        if (!preferences.edit()
                .putString("url", next.url).putString("device_id", next.deviceId)
                .putString("device_code", next.deviceCode).putBoolean("require_ack", next.requireAck)
                .putBoolean("notifications", next.notifications).putBoolean("sms", next.sms)
                .putBoolean("paused", next.paused).putStringSet("packages", next.packages)
                .putBoolean("capture_failed", next.captureFailed).commit()
        ) throw IOException("Could not save settings")
        mutableState.value = next
        return next
    }
}
