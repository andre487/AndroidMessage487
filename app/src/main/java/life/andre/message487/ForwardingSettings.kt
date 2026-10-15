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
    val autoSelectNewApps: Boolean = false,
    internal val knownPackages: Set<String> = emptySet(),
    val captureFailed: Boolean = false,
    val authToken: String = "",
    val smsDeduplication: Boolean = true,
    val notificationDeduplication: Boolean = true,
    val deduplicationWindowSeconds: Int = 1,
    val imageFormat: NotificationImageFormat = NotificationImageFormat.JPEG,
    val imageQuality: Int = 80,
    val imageLimitsEnabled: Boolean = true,
    val imageMaxCount: Int = MAX_NOTIFICATION_IMAGES,
    val imageMaxEdge: Int = 1024,
    val imageMaxKiB: Int = MAX_NOTIFICATION_IMAGE_BYTES / 1024,
    val imageTotalKiB: Int = MAX_NOTIFICATION_IMAGES_BYTES / 1024,
    val forwardAvatars: Boolean = false,
    val forwardAppIcons: Boolean = false,
) {
    init {
        require(deduplicationWindowSeconds >= 0 && imageQuality in 0..100)
        require(imageMaxCount > 0 && imageMaxEdge > 0 && imageMaxKiB > 0 && imageTotalKiB > 0)
    }

    override fun toString(): String = "ForwardingSettings(redacted)"

    fun ready(): Boolean = deviceCode.isNotBlank() && validWebhookUrl(url, BuildConfig.DEBUG) && validAuthToken(authToken)
    fun acceptsPackage(packageName: String, ownPackage: String): Boolean =
        !paused && notifications && packageName != ownPackage && packageName in packages && ready()
}

class SettingsStore(
    context: Context,
    private val cipher: PayloadCipher = (context.applicationContext as? MessageApplication)?.payloadCipher ?: KeystorePayloadCipher(),
) {
    private val diagnostics = (context.applicationContext as? MessageApplication)?.diagnostics
    private val preferences by lazy { context.getSharedPreferences("connection", Context.MODE_PRIVATE) }
    private val mutableState = MutableStateFlow(ForwardingSettings())
    private val mutableLoaded = MutableStateFlow(false)
    val loaded = mutableLoaded.asStateFlow()

    @Synchronized
    fun load(): ForwardingSettings {
        if (!mutableLoaded.value) {
            mutableState.value = read()
            mutableLoaded.value = true
        }
        return mutableState.value
    }
    val state = mutableState.asStateFlow()

    private fun read() = ForwardingSettings(
        authToken = runCatching {
            preferences.readString("auth_token_encrypted", null)?.let {
                cipher.decrypt(android.util.Base64.decode(it, android.util.Base64.NO_WRAP))
            }.orEmpty()
        }.getOrDefault(""),
        smsDeduplication = preferences.readBoolean("sms_deduplication", preferences.readBoolean("deduplication", true)),
        notificationDeduplication = preferences.readBoolean("notification_deduplication", preferences.readBoolean("deduplication", true)),
        deduplicationWindowSeconds = preferences.readInt("deduplication_window_seconds", 1).takeIf { it >= 0 } ?: 1,
        url = preferences.readString("url", BuildConfig.DEFAULT_WEBHOOK_URL).orEmpty(),
        deviceId = preferences.readString("device_id", "").orEmpty(),
        deviceCode = preferences.readString("device_code", "android-device").orEmpty(),
        requireAck = preferences.readBoolean("require_ack", true),
        notifications = preferences.readBoolean("notifications", false),
        sms = preferences.readBoolean("sms", false),
        paused = preferences.readBoolean("paused", false),
        packages = preferences.readStrings("packages"),
        autoSelectNewApps = preferences.readBoolean("auto_select_new_apps", false),
        knownPackages = preferences.readStrings("known_packages"),
        captureFailed = preferences.readBoolean("capture_failed", false),
        imageFormat = NotificationImageFormat.entries.firstOrNull { it.name == preferences.readString("image_format", null) } ?: NotificationImageFormat.JPEG,
        imageQuality = preferences.readInt("image_quality", 80).coerceIn(0, 100),
        imageLimitsEnabled = preferences.readBoolean("image_limits_enabled", true),
        imageMaxCount = preferences.readInt("image_max_count", 3).coerceAtLeast(1),
        imageMaxEdge = preferences.readInt("image_max_edge", 1024).coerceAtLeast(1),
        imageMaxKiB = preferences.readInt("image_max_kib", 256).coerceAtLeast(1),
        imageTotalKiB = preferences.readInt("image_total_kib", 512).coerceAtLeast(1),
        forwardAvatars = preferences.readBoolean("forward_avatars", false),
        forwardAppIcons = preferences.readBoolean("forward_app_icons", false),
    )

    @Synchronized
    fun discoverPackages(packages: Set<String>, ownPackage: String) {
        load()
        val discovered = packages - ownPackage
        if (state.value.knownPackages.containsAll(discovered)) return
        update {
            it.copy(
                knownPackages = it.knownPackages + discovered,
                packages = if (it.autoSelectNewApps) it.packages + (discovered - it.knownPackages) else it.packages,
            )
        }
    }

    @Synchronized
    fun update(transform: (ForwardingSettings) -> ForwardingSettings): ForwardingSettings {
        load()
        val next = transform(mutableState.value).let {
            if (it.deviceId.isBlank()) it.copy(deviceId = UUID.randomUUID().toString()) else it
        }
        if (next == mutableState.value) return next
        val editor = preferences.edit()
        if (next.authToken != mutableState.value.authToken) {
            require(validAuthToken(next.authToken))
            editor.putString("auth_token_encrypted", android.util.Base64.encodeToString(
                cipher.encrypt(next.authToken), android.util.Base64.NO_WRAP))
        }
        if (!editor
                .putString("url", next.url).putString("device_id", next.deviceId)
                .putString("device_code", next.deviceCode).putBoolean("require_ack", next.requireAck)
                .putBoolean("notifications", next.notifications).putBoolean("sms", next.sms)
                .putBoolean("sms_deduplication", next.smsDeduplication)
                .putBoolean("notification_deduplication", next.notificationDeduplication)
                .remove("deduplication")
                .putInt("deduplication_window_seconds", next.deduplicationWindowSeconds)
                .putBoolean("image_limits_enabled", next.imageLimitsEnabled)
                .putInt("image_max_count", next.imageMaxCount).putInt("image_max_edge", next.imageMaxEdge)
                .putInt("image_max_kib", next.imageMaxKiB).putInt("image_total_kib", next.imageTotalKiB)
                .putBoolean("forward_avatars", next.forwardAvatars).putBoolean("forward_app_icons", next.forwardAppIcons)
                .putString("image_format", next.imageFormat.name).putInt("image_quality", next.imageQuality)
                .putBoolean("auto_select_new_apps", next.autoSelectNewApps)
                .putStringSet("known_packages", next.knownPackages)
                .putBoolean("paused", next.paused).putStringSet("packages", next.packages)
                .putBoolean("capture_failed", next.captureFailed).commit()
        ) throw IOException("Could not save settings")
        diagnostics?.record(life.andre.message487.diagnostics.DiagnosticEvent.SETTINGS_SAVED)
        mutableState.value = next
        return next
    }
}
