package life.andre.message487.diagnostics

import android.content.Context
import android.os.Process
import kotlinx.coroutines.flow.asStateFlow
import kotlin.system.exitProcess

internal class CrashHandler(context: Context, private val log: DiagnosticLog) {
    private val preferences by lazy { context.getSharedPreferences("crash_state", Context.MODE_PRIVATE) }
    private val mutablePending = kotlinx.coroutines.flow.MutableStateFlow(false)
    val state = mutablePending.asStateFlow()
    val pending: Boolean get() = mutablePending.value

    fun load() { mutablePending.value = preferences.getBoolean("pending", false) }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { log.writeCrash(thread, error) } catch (_: Throwable) { }
            try { preferences.edit().putBoolean("pending", true).commit() } catch (_: Throwable) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
    }

    fun dismiss() {
        check(preferences.edit().putBoolean("pending", false).commit())
        mutablePending.value = false
    }
}
