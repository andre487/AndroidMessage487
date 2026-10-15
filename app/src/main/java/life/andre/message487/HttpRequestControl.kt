package life.andre.message487

import android.os.CancellationSignal
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Awaiting cancellation closes blocking HTTP I/O on a background thread, including during headers. */
internal suspend fun <T> cancellableHttp(block: (CancellationSignal) -> T): T = coroutineScope {
    val cancellation = CancellationSignal()
    val operation = async(Dispatchers.IO) { block(cancellation) }
    try { operation.await() }
    finally { withContext(NonCancellable + Dispatchers.IO) { cancellation.cancel() } }
}

internal class HttpRequestControl(
    private val cancellation: CancellationSignal,
    timeoutMs: Long = 120_000,
) : AutoCloseable {
    @Volatile private var connection: HttpURLConnection? = null
    private val expired = AtomicBoolean()
    private val deadline: java.util.concurrent.ScheduledFuture<*>

    init {
        require(timeoutMs > 0)
        cancellation.setOnCancelListener { connection?.disconnect() }
        deadline = deadlines.schedule({ expired.set(true); cancellation.cancel() }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun attach(value: HttpURLConnection) {
        connection = value
        checkActive()
    }

    fun checkActive() {
        if (expired.get()) throw SocketTimeoutException("HTTP request deadline exceeded")
        if (cancellation.isCanceled) throw CancellationException("HTTP request cancelled")
    }

    override fun close() {
        deadline.cancel(false)
        cancellation.setOnCancelListener(null)
        connection = null
    }

    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(2) { task ->
            Thread(task, "message487-http-deadlines").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
