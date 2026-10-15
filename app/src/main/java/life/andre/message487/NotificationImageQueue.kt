package life.andre.message487

import android.os.CancellationSignal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Queued tasks retain only notification keys/text; bitmaps are fetched when a task actually starts. */
internal class NotificationImageQueue(private val timeoutMs: Long = 10_000, capacity: Int = 32) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(capacity))
    private val timer = ScheduledThreadPoolExecutor(2).apply { removeOnCancelPolicy = true }
    private var closed = false
    private val pending = mutableMapOf<String, Task>()
    private val tasks = mutableSetOf<Task>()

    private inner class Task(val key: String, val capture: (CancellationSignal) -> List<NotificationImage>,
        val complete: (List<NotificationImage>) -> Unit, val onFailure: (Exception) -> Unit) : Runnable {
        val cancellation = CancellationSignal()
        val done = AtomicBoolean()
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        fun finish(images: List<NotificationImage>) {
            if (done.compareAndSet(false, true)) {
                timeout?.cancel(false)
                synchronized(this@NotificationImageQueue) { pending.remove(key, this); tasks.remove(this) }
                try { complete(images) } finally { discardImages(images) }
            } else discardImages(images)
        }
        fun cancel() {
            try { finish(emptyList()) }
            finally { cancellation.cancel(); worker.remove(this) }
        }
        override fun run() {
            synchronized(this@NotificationImageQueue) { pending.remove(key, this) }
            if (done.get()) return
            try { finish(capture(cancellation)) }
            catch (_: OutOfMemoryError) {
                if (!done.get()) onFailure(ImageMemoryException())
                finish(emptyList())
            }
            catch (error: Exception) {
                if (!done.get()) onFailure(error)
                finish(emptyList())
            }
        }
    }

    @Synchronized
    fun submit(key: String, capture: (CancellationSignal) -> List<NotificationImage>,
        complete: (List<NotificationImage>) -> Unit, onFailure: (Exception) -> Unit) {
        if (closed) { complete(emptyList()); return }
        pending[key]?.cancel()
        val task = Task(key, capture, complete, onFailure)
        pending[key] = task
        tasks += task
        task.timeout = timer.schedule({ task.cancel() }, timeoutMs, TimeUnit.MILLISECONDS)
        try { worker.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { task.cancel() }
    }

    override fun close() {
        val snapshot = synchronized(this) { closed = true; tasks.toList() }
        snapshot.forEach { it.cancel() }
        worker.shutdownNow()
        timer.shutdownNow()
    }
}
