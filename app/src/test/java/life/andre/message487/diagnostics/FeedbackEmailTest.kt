package life.andre.message487.diagnostics

import android.app.Application
import android.content.Intent
import android.net.Uri
import life.andre.message487.ForwardingSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FeedbackEmailTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `archives contain logs crash and environment and retain only three distinct files`() {
        val cache = temporary.newFolder()
        DiagnosticLog(temporary.newFolder()).use { log ->
            log.record(DiagnosticEvent.EVENT_QUEUED, type = "sms")
            log.writeCrash(Thread.currentThread(), IllegalStateException("private body"))
            val names = (1..5).map {
                val report = FeedbackEmail.createArchive(cache, log, "App: test")
                ZipFile(report).use { zip ->
                    assertEquals(3, zip.size())
                    assertTrue(zip.getInputStream(zip.getEntry("diagnostic.log")).reader().readText().contains("EVENT_QUEUED"))
                    val crash = zip.getInputStream(zip.getEntry("crash-latest.log")).reader().readText()
                    assertTrue(crash.contains("UNCAUGHT_EXCEPTION"))
                    assertFalse(crash.contains("private body"))
                    assertEquals("App: test", zip.getInputStream(zip.getEntry("environment.txt")).reader().readText())
                }
                report.name
            }
            assertEquals(5, names.toSet().size)
            assertEquals(3, File(cache, "feedback").listFiles()!!.size)
        }
    }

    @Test fun `email uses correct recipient read-only content attachment and excludes settings secrets`() {
        val context = RuntimeEnvironment.getApplication()
        DiagnosticLog(temporary.newFolder()).use { log ->
            val chooser = FeedbackEmail.createIntent(context, log, ForwardingSettings(
                url = "https://secret.example/token", deviceId = "secret-id", deviceCode = "secret-code",
                packages = setOf("secret.package")))
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertArrayEquals(arrayOf("der-morgenstern@yandex.ru"), send.getStringArrayExtra(Intent.EXTRA_EMAIL))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            val report = File(context.cacheDir, "feedback").listFiles()!!.single()
            ZipFile(report).use { zip ->
                val environment = zip.getInputStream(zip.getEntry("environment.txt")).reader().readText()
                assertFalse(environment.contains("secret"))
            }
            File(context.cacheDir, "feedback").deleteRecursively()
        }
    }

    @Test fun `crash handler persists marker and delegates to previous handler`() {
        val context = RuntimeEnvironment.getApplication()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val error = IllegalStateException("private")
        var delegated = false
        DiagnosticLog(temporary.newFolder()).use { log ->
            try {
                Thread.setDefaultUncaughtExceptionHandler { _, received -> delegated = received === error }
                val handler = CrashHandler(context, log)
                handler.install()
                Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
                assertTrue(delegated)
                assertTrue(CrashHandler(context, log).pending)
                handler.dismiss()
                assertFalse(handler.pending)
            } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
        }
    }
}
