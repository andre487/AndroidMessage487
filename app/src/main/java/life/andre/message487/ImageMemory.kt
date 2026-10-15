package life.andre.message487

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal class ImageMemoryException : IOException("Insufficient memory for image processing")
// ponytail: serialize bitmap allocations; use budgeted parallelism only if capture throughput requires it.
private val imageMemoryLock = ReentrantLock(true)

/** Share the allocation budget across capture, test images and application-icon rendering. */
internal fun <T> withImageMemory(checkActive: () -> Unit = {}, block: () -> T): T {
    while (!imageMemoryLock.tryLock(100, TimeUnit.MILLISECONDS)) checkActive()
    try {
        checkActive()
        return try { block() } catch (_: OutOfMemoryError) { throw ImageMemoryException() }
    } finally { imageMemoryLock.unlock() }
}

internal fun workingPixelLimit(): Long {
    val runtime = Runtime.getRuntime()
    val available = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0)
    // Reserve room for decoding, a software source, the output bitmap and codec scratch space.
    return minOf(16_777_216L, runtime.maxMemory() / 32, available / 16).coerceAtLeast(1)
}
