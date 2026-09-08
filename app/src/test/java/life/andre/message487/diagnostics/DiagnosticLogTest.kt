package life.andre.message487.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream

class DiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `rotation stays bounded and persists across restart`() {
        val directory = temporary.newFolder()
        DiagnosticLog(directory, 1024).use { log ->
            repeat(150) { log.record(DiagnosticEvent.RECOVERY, count = it); log.flush() }
        }
        assertEquals(3, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.all { it.length() <= 1024 })
        DiagnosticLog(directory, 1024).use { log ->
            val text = log.readTail()
            assertTrue(text.contains("count=149"))
            assertFalse(text.contains("count=0\n"))
            log.record(DiagnosticEvent.APP_STARTED)
            assertTrue(log.readTail().contains("APP_STARTED"))
        }
    }

    @Test fun `exception messages causes suppressed and untrusted fields never enter logs`() {
        val secret = "https://secret.example/path?token=private SMS 123456"
        val error = IllegalStateException(secret, IOExceptionForTest(secret))
        error.addSuppressed(RuntimeException(secret))
        error.stackTrace = arrayOf(StackTraceElement("life.andre.message487.Worker", "send", "Worker.kt", 42),
            StackTraceElement(secret, secret, secret, 1))
        DiagnosticLog(temporary.newFolder()).use { log ->
            log.record(DiagnosticEvent.CAPTURE_FAILED, type = secret, outcome = secret, error = error)
            log.writeCrash(Thread(secret), error)
            val content = log.readTail() + ByteArrayOutputStream().also(log::copyCrashTo).toString("UTF-8")
            assertFalse(content.contains(secret))
            assertFalse(content.contains("123456"))
            assertTrue(content.contains("Worker.send(Worker.kt:42)"))
            assertTrue(content.contains("UNCAUGHT_EXCEPTION"))
            assertTrue(content.contains("suppressed=java.lang.RuntimeException"))
        }
    }

    @Test fun `crash is synchronous and survives rolling log rotation until cleared`() {
        val directory = temporary.newFolder()
        DiagnosticLog(directory, 1024).use { log ->
            assertTrue(log.writeCrash(Thread.currentThread(), IllegalArgumentException("private")))
            assertTrue(File(directory, "crash-latest.log").readText().contains("UNCAUGHT_EXCEPTION"))
            repeat(100) { log.record(DiagnosticEvent.RECOVERY, count = it); log.flush() }
            assertFalse(log.readTail().contains("UNCAUGHT_EXCEPTION"))
            assertTrue(File(directory, "crash-latest.log").exists())
            log.clear()
            assertFalse(File(directory, "crash-latest.log").exists())
            assertTrue(log.readTail().contains("LOG_CLEARED"))
        }
    }

    @Test fun `storage failure does not escape normal logging or crash writing`() {
        DiagnosticLog(temporary.newFile()).use { log ->
            log.record(DiagnosticEvent.APP_STARTED)
            log.flush()
            assertTrue(log.ioFailed)
            assertFalse(log.writeCrash(Thread.currentThread(), IllegalStateException()))
        }
    }

    private class IOExceptionForTest(message: String) : java.io.IOException(message)
}
