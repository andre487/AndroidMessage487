package life.andre.message487.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal enum class DiagnosticEvent {
    APP_STARTED, LISTENER_CONNECTED, LISTENER_DISCONNECTED, EVENT_QUEUED, DUPLICATE_SKIPPED,
    CAPTURE_FAILED, DELIVERY_STARTED, DELIVERY_FINISHED, DELIVERY_FAILED, PAYLOAD_UNREADABLE,
    RECOVERY, RECOVERY_FAILED, SETTINGS_SAVED, LOCAL_OPERATION_FAILED, MANUAL_RETRY, EVENT_DELETED,
    REPORT_PREPARED, LOG_CLEARED,
}

internal class DiagnosticLog(
    private val directory: File,
    private val segmentBytes: Int = 256 * 1024,
) : AutoCloseable {
    private val lock = Any()
    private val dropped = AtomicLong()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { task -> Thread(task, "message487-diagnostics").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile var ioFailed = false
        private set

    init { require(segmentBytes >= 1024) }

    fun record(event: DiagnosticEvent, type: String? = null, outcome: String? = null,
        http: Int? = null, count: Int? = null, error: Throwable? = null) {
        val line = buildString {
            append("${Instant.now()} event=${event.name}")
            type?.let { append(" type=${if (it in TYPES) it else "unknown"}") }
            outcome?.let { append(" outcome=${if (it in OUTCOMES) it else "unknown"}") }
            http?.let { append(" http=$it") }
            count?.let { append(" count=$it") }
            appendLine()
            error?.let { append(safeStackTrace(it)) }
        }
        try {
            executor.execute {
                safely {
                    synchronized(lock) {
                        val missed = dropped.getAndSet(0)
                        appendLocked(if (missed > 0) "${Instant.now()} dropped=$missed\n$line" else line)
                    }
                }
            }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    // No queue on the fatal path: the runtime terminates the process immediately afterwards.
    fun writeCrash(thread: Thread, error: Throwable) = safely {
        synchronized(lock) {
            val report = "${Instant.now()} event=UNCAUGHT_EXCEPTION thread_id=${thread.id}\n${safeStackTrace(error)}"
            ensureDirectory()
            FileOutputStream(File(directory, CRASH_FILE)).use {
                it.write(report.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, segmentBytes)) })
                it.fd.sync()
            }
            appendLocked(report, sync = true)
        }
    }

    fun readTail(maxBytes: Int = 48 * 1024): String {
        flush()
        return synchronized(lock) {
            ensureDirectory()
            val chunks = ArrayDeque<ByteArray>()
            var remaining = maxBytes.coerceAtLeast(1)
            for (file in logFiles().asReversed()) {
                if (!file.isFile || remaining == 0) continue
                val count = minOf(file.length(), remaining.toLong()).toInt()
                RandomAccessFile(file, "r").use {
                    it.seek(file.length() - count)
                    val bytes = ByteArray(count)
                    it.readFully(bytes)
                    chunks.addFirst(bytes)
                }
                remaining -= count
            }
            val text = chunks.fold(ByteArray(0)) { bytes, chunk -> bytes + chunk }.toString(Charsets.UTF_8)
            if (remaining == 0) text.substringAfter('\n', "") else text
        }
    }

    fun copyLogsTo(output: OutputStream) {
        flush()
        synchronized(lock) {
            ensureDirectory()
            logFiles().filter(File::isFile).forEach { it.inputStream().use { input -> input.copyTo(output) } }
        }
    }

    fun copyCrashTo(output: OutputStream) = synchronized(lock) {
        File(directory, CRASH_FILE).takeIf(File::isFile)?.inputStream()?.use { it.copyTo(output) }
        Unit
    }

    fun clear() {
        flush()
        synchronized(lock) {
            (logFiles() + File(directory, CRASH_FILE)).filter(File::exists).forEach {
                if (!it.delete()) throw IOException("Could not remove diagnostic file")
            }
        }
        record(DiagnosticEvent.LOG_CLEARED)
    }

    fun flush() {
        try { executor.submit {}.get(5, TimeUnit.SECONDS) }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Diagnostic flush interrupted", error) }
        catch (error: Exception) { throw IOException("Could not flush diagnostics", error) }
    }

    private fun appendLocked(text: String, sync: Boolean = false) {
        ensureDirectory()
        val bytes = text.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, segmentBytes)) }
        val current = File(directory, "diagnostic.log")
        if (current.length() + bytes.size > segmentBytes) {
            val oldest = File(directory, "diagnostic.2.log")
            if (oldest.exists() && !oldest.delete()) throw IOException("Could not rotate diagnostics")
            for (index in 1 downTo 0) {
                val from = File(directory, if (index == 0) "diagnostic.log" else "diagnostic.1.log")
                if (from.exists() && !from.renameTo(File(directory, "diagnostic.${index + 1}.log"))) {
                    throw IOException("Could not rotate diagnostics")
                }
            }
        }
        FileOutputStream(current, true).use { it.write(bytes); if (sync) it.fd.sync() }
    }

    private fun logFiles() = listOf("diagnostic.2.log", "diagnostic.1.log", "diagnostic.log").map { File(directory, it) }
    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic storage unavailable")
    }
    private fun safely(block: () -> Unit): Boolean = try {
        block(); ioFailed = false; true
    } catch (_: IOException) { ioFailed = true; false }
      catch (_: SecurityException) { ioFailed = true; false }

    override fun close() { executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS) }

    companion object {
        private const val CRASH_FILE = "crash-latest.log"
        private val TYPES = setOf("test", "sms", "notification")
        private val OUTCOMES = setOf("ACCEPTED", "HTTP_SUCCESS", "HTTP_ERROR", "INVALID_ACK", "TIMEOUT", "NETWORK_ERROR")
    }
}

internal fun safeStackTrace(error: Throwable): String = buildString {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    fun visit(current: Throwable, label: String, depth: Int) {
        if (depth >= 8 || seen.size >= 8 || length >= 16 * 1024 || !seen.add(current)) return
        appendLine("$label=${safeClass(current.javaClass.name)}")
        current.stackTrace.take(40).forEach { frame ->
            if (length >= 16 * 1024) return@forEach
            appendLine("  at ${safeClass(frame.className)}.${safeIdentifier(frame.methodName)}(${safeIdentifier(frame.fileName ?: "unknown")}:${frame.lineNumber})")
        }
        current.cause?.let { visit(it, "cause", depth + 1) }
        current.suppressed.take(2).forEach { visit(it, "suppressed", depth + 1) }
    }
    visit(error, "exception", 0)
}.take(16 * 1024)

private fun safeClass(value: String): String = if (listOf("life.andre.message487.", "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.")
        .any(value::startsWith)) safeIdentifier(value) else "[external]"
private fun safeIdentifier(value: String): String = if (value.length <= 200 && value.matches(Regex("[A-Za-z0-9_.$<>-]+"))) value else "[redacted]"
