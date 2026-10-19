package life.andre.message487

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_SETTINGS_FILE_BYTES = 1024 * 1024

/** Deliberately excludes installation identity, permissions, queue, history and diagnostics. */
internal fun exportSettings(settings: ForwardingSettings, includeToken: Boolean = true): String = JSONObject().apply {
    put("format", "message487-settings")
    put("version", 1)
    put("url", settings.url)
    put("device_code", settings.deviceCode)
    if (includeToken) put("auth_token", settings.authToken)
    put("packages", JSONArray(settings.packages.sorted()))
    put("require_ack", settings.requireAck)
    put("notifications", settings.notifications)
    put("sms", settings.sms)
    put("auto_select_new_apps", settings.autoSelectNewApps)
    put("sms_deduplication", settings.smsDeduplication)
    put("notification_deduplication", settings.notificationDeduplication)
    put("deduplication_window_seconds", settings.deduplicationWindowSeconds)
    put("image_format", settings.imageFormat.name)
    put("image_quality", settings.imageQuality)
    put("image_limits_enabled", settings.imageLimitsEnabled)
    put("image_max_count", settings.imageMaxCount)
    put("image_max_edge", settings.imageMaxEdge)
    put("image_max_kib", settings.imageMaxKiB)
    put("image_total_kib", settings.imageTotalKiB)
    put("forward_avatars", settings.forwardAvatars)
    put("forward_app_icons", settings.forwardAppIcons)
}.toString(2)

internal fun readSettingsFile(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_SETTINGS_FILE_BYTES)
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
}

/** Parse everything before changing the store. Never reuse an old token for a new destination. */
internal fun importSettings(json: String, current: ForwardingSettings, allowLocalHttp: Boolean = BuildConfig.DEBUG): ForwardingSettings {
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_SETTINGS_FILE_BYTES)
    val data = JSONObject(json)
    fun string(key: String): String = (data.get(key) as? String) ?: error("Invalid string field")
    fun boolean(key: String): Boolean = (data.get(key) as? Boolean) ?: error("Invalid boolean field")
    fun number(key: String): Int {
        val value = data.get(key)
        require(value is Int || value is Long)
        val long = (value as Number).toLong()
        require(long in 0..Int.MAX_VALUE.toLong())
        return long.toInt()
    }
    require(string("format") == "message487-settings" && number("version") == 1)
    val url = string("url")
    require(url.isEmpty() || validWebhookUrl(url, allowLocalHttp))
    val code = string("device_code")
    require(code.isNotBlank() && code.length <= 4096)
    val token = if (data.has("auth_token")) string("auth_token") else ""
    require(token.isEmpty() || validAuthToken(token))
    val packages = data.getJSONArray("packages")
    require(packages.length() <= 10_000)
    val selected = (0 until packages.length()).map {
        val pkg = packages.get(it) as? String ?: error("Invalid package")
        require(pkg.length <= 255 && pkg.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")))
        require(pkg != "life.andre.message487")
        pkg
    }.toSet()
    return current.copy(
        url = url, deviceCode = code, authToken = token, paused = true,
        packages = selected, requireAck = boolean("require_ack"),
        notifications = boolean("notifications"), sms = boolean("sms"),
        autoSelectNewApps = boolean("auto_select_new_apps"),
        smsDeduplication = boolean("sms_deduplication"), notificationDeduplication = boolean("notification_deduplication"),
        deduplicationWindowSeconds = number("deduplication_window_seconds"),
        imageFormat = NotificationImageFormat.valueOf(string("image_format")), imageQuality = number("image_quality"),
        imageLimitsEnabled = boolean("image_limits_enabled"), imageMaxCount = number("image_max_count"),
        imageMaxEdge = number("image_max_edge"), imageMaxKiB = number("image_max_kib"), imageTotalKiB = number("image_total_kib"),
        forwardAvatars = boolean("forward_avatars"), forwardAppIcons = boolean("forward_app_icons"),
    )
}
