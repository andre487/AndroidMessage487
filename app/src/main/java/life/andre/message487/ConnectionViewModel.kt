package life.andre.message487

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ConnectionState(
    val url: String = "",
    val deviceCode: String = "",
    val requireAck: Boolean = true,
    val busy: Boolean = false,
    val invalidUrl: Boolean = false,
    val invalidDeviceCode: Boolean = false,
    val notice: Int? = null,
)

data class QueueSnapshot(val entries: List<QueueEntry> = emptyList(), val pending: Int = 0)
data class PermissionState(val notifications: Boolean = false, val sms: Boolean = false)

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = MessageGraph.get(application)
    val settings = graph.settings.state
    val listenerConnected = ListenerState.connected
    private val mutableState = MutableStateFlow(settings.value.let {
        ConnectionState(it.url, it.deviceCode, it.requireAck)
    })
    val state = mutableState.asStateFlow()
    private val mutableQueue = MutableStateFlow(QueueSnapshot())
    val queue = mutableQueue.asStateFlow()
    private val mutablePermissions = MutableStateFlow(PermissionState())
    val permissions = mutablePermissions.asStateFlow()
    private val mutableApps = MutableStateFlow<List<AppSource>>(emptyList())
    val apps = mutableApps.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            graph.outbox.revision.collect {
                try {
                    mutableQueue.value = withContext(Dispatchers.IO) {
                        QueueSnapshot(graph.outbox.entries(), graph.outbox.pendingCount())
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = state.value.copy(notice = R.string.local_error)
                }
            }
        }
        viewModelScope.launch {
            mutableApps.value = withContext(Dispatchers.IO) {
                val pm = application.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val packages = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
                packages.distinct().filter { it != application.packageName }
                    .map { AppSourceResolver(pm).resolve(it) }.sortedBy { it.name.lowercase() }
            }
        }
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        mutablePermissions.value = PermissionState(
            notifications = app.packageName in NotificationManagerCompat.getEnabledListenerPackages(app),
            sms = ContextCompat.checkSelfPermission(app, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    fun setUrl(url: String) { mutableState.value = state.value.copy(url = url, invalidUrl = false, notice = null) }
    fun setDeviceCode(code: String) { mutableState.value = state.value.copy(deviceCode = code, invalidDeviceCode = false, notice = null) }
    fun setRequireAck(value: Boolean) { mutableState.value = state.value.copy(requireAck = value, notice = null) }

    fun save(sendTest: Boolean) {
        val draft = state.value
        val url = draft.url.trim()
        val code = draft.deviceCode.trim()
        if (code.isBlank()) { mutableState.value = draft.copy(invalidDeviceCode = true); return }
        if (!validWebhookUrl(url, BuildConfig.DEBUG)) { mutableState.value = draft.copy(invalidUrl = true); return }
        action(if (sendTest) R.string.test_queued else R.string.saved) {
            graph.settings.update { it.copy(url = url, deviceCode = code, requireAck = draft.requireAck) }
            if (sendTest) graph.enqueueTest()
        }
    }

    fun sendTest() = action(R.string.test_queued) { graph.enqueueTest() }
    fun notifications(enabled: Boolean) = action { graph.settings.update { it.copy(notifications = enabled) } }
    fun sms(enabled: Boolean) = action { graph.settings.update { it.copy(sms = enabled) } }
    fun selectPackage(packageName: String, selected: Boolean) = action {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")))
        require(packageName != getApplication<Application>().packageName)
        graph.settings.update { it.copy(packages = if (selected) it.packages + packageName else it.packages - packageName) }
    }
    fun pause(paused: Boolean) = action {
        graph.settings.update { it.copy(paused = paused) }
        if (!paused) graph.recover()
    }
    fun retry(id: String) = action {
        if (graph.outbox.retry(id)) graph.scheduler.schedule(id, replace = true)
    }
    fun delete(id: String) = action { graph.outbox.delete(id) }
    fun clearError() = action { graph.settings.update { it.copy(captureFailed = false) } }
    fun rebind() {
        NotificationListenerService.requestRebind(ComponentName(getApplication(), NotificationCaptureService::class.java))
    }
    fun showSettingsError() { mutableState.value = state.value.copy(notice = R.string.settings_unavailable) }

    private fun action(notice: Int? = null, block: () -> Unit) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { block() }
                notice
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                R.string.local_error
            }
            mutableState.value = state.value.copy(busy = false, notice = result)
        }
    }
}
