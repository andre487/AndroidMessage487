package life.andre.message487

import android.os.CancellationSignal
import java.util.concurrent.ThreadPoolExecutor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

// A provider may ignore cancellation. Bound its threads and let the caller stop waiting independently.
internal val documentIo = boundedExecutor("message487-documents", 1)
internal val previewIo = boundedExecutor("message487-preview", 1)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun <T> ThreadPoolExecutor.providerOperation(
    discard: (T) -> Unit = {},
    read: (CancellationSignal) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    val task = Runnable {
        if (!continuation.isActive) return@Runnable
        try {
            val value = read(signal)
            continuation.resume(value) { _, lost, _ ->
                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { discard(lost) })
            }
        } catch (error: Exception) { continuation.resumeWithException(error) }
    }
    continuation.invokeOnCancellation {
        remove(task)
        Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { signal.cancel() })
    }
    try { execute(task) }
    catch (error: java.util.concurrent.RejectedExecutionException) { continuation.resumeWithException(error) }
}
