package life.andre.message487

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ConnectionState(
    val url: String = "",
    val deviceCode: String = "android-device",
    val requireAck: Boolean = true,
    val busy: Boolean = false,
    val invalidUrl: Boolean = false,
    val invalidDeviceCode: Boolean = false,
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
    val results: List<DeliveryResult> = emptyList(),
)

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("connection", 0)
    private val mutableState = MutableStateFlow(
        ConnectionState(
            url = preferences.getString("url", BuildConfig.DEFAULT_WEBHOOK_URL).orEmpty(),
            deviceCode = preferences.getString("device_code", null) ?: ConnectionState().deviceCode,
            requireAck = preferences.getBoolean("require_ack", true),
        )
    )
    val state = mutableState.asStateFlow()

    fun setUrl(url: String) {
        if (!state.value.busy) mutableState.value = state.value.copy(url = url, invalidUrl = false, saved = false)
    }

    fun setRequireAck(value: Boolean) {
        if (!state.value.busy) mutableState.value = state.value.copy(requireAck = value, saved = false)
    }

    fun setDeviceCode(value: String) {
        if (!state.value.busy) {
            mutableState.value = state.value.copy(deviceCode = value, invalidDeviceCode = false, saved = false)
        }
    }

    fun save(sendTest: Boolean) {
        val current = state.value
        if (current.busy) return
        val url = current.url.trim()
        val deviceCode = current.deviceCode.trim()
        if (deviceCode.isEmpty()) {
            mutableState.value = current.copy(invalidDeviceCode = true)
            return
        }
        if (!validWebhookUrl(url, BuildConfig.DEBUG)) {
            mutableState.value = current.copy(invalidUrl = true)
            return
        }
        mutableState.value = current.copy(url = url, deviceCode = deviceCode, busy = true, saveFailed = false, saved = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString()
                val committed = preferences.edit()
                    .putString("url", url)
                    .putBoolean("require_ack", current.requireAck)
                    .putString("device_id", deviceId)
                    .putString("device_code", deviceCode)
                    .commit()
                if (!committed) false to null
                else true to if (sendTest) {
                    val application = getApplication<Application>()
                    val source = AppSourceResolver(application.packageManager).resolve(application.packageName)
                    WebhookClient().send(url, TestEvent(deviceId, deviceCode, source), current.requireAck)
                } else null
            }
            mutableState.value = state.value.copy(
                busy = false,
                saved = result.first,
                saveFailed = !result.first,
                results = (listOfNotNull(result.second) + state.value.results).take(20),
            )
        }
    }
}
