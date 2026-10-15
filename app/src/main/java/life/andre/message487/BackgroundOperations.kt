package life.andre.message487

import android.content.BroadcastReceiver
import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import life.andre.message487.diagnostics.DiagnosticEvent

internal fun boundedExecutor(name: String, capacity: Int) = ThreadPoolExecutor(
    1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(capacity),
    { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy(),
)

internal fun Context.reportLocalFailure(error: Exception) {
    try { (applicationContext as? MessageApplication)?.diagnostics?.record(DiagnosticEvent.LOCAL_OPERATION_FAILED, error = error) }
    catch (_: Exception) { /* Reporting must not turn a recoverable failure into a process crash. */ }
}

internal fun Executor.submitSafely(onFailure: (Exception) -> Unit, block: () -> Unit): Boolean = try {
    execute { try { block() } catch (error: Exception) { onFailure(error) } }
    true
} catch (error: RejectedExecutionException) { onFailure(error); false }

private val broadcastDeadlines = ScheduledThreadPoolExecutor(1) { task ->
    Thread(task, "message487-broadcast-deadlines").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

internal fun runBroadcast(context: Context, pending: BroadcastReceiver.PendingResult?, executor: Executor, onFailure: (Exception) -> Unit = context::reportLocalFailure, block: () -> Unit) {
    val finished = AtomicBoolean()
    fun finish(): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        try { pending?.finish() } catch (error: Exception) { context.reportLocalFailure(error) }
        return true
    }
    val deadline = broadcastDeadlines.schedule({
        if (finish()) onFailure(java.io.IOException("Broadcast processing exceeded its deadline"))
    }, 8, TimeUnit.SECONDS)
    val accepted = executor.submitSafely(onFailure) {
        try { if (!finished.get()) block() }
        finally { deadline.cancel(false); finish() }
    }
    if (!accepted) { deadline.cancel(false); finish() }
}
