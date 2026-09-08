package life.andre.message487.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import life.andre.message487.BuildConfig
import life.andre.message487.ForwardingSettings
import life.andre.message487.R
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object FeedbackEmail {
    const val ADDRESS = "der-morgenstern@yandex.ru"

    fun createIntent(context: Context, log: DiagnosticLog, settings: ForwardingSettings): Intent {
        val archive = createArchive(context.cacheDir, log, environment(settings))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, "Message487 ${BuildConfig.VERSION_NAME} — diagnostics")
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.feedback_body))
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.diagnostics), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        val targets = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ADDRESS")), 0)
            .map { it.activityInfo.packageName }.distinct()
            .mapNotNull { pkg -> Intent(send).setPackage(pkg).takeIf { it.resolveActivity(context.packageManager) != null } }
        return Intent.createChooser(targets.firstOrNull() ?: send, context.getString(R.string.send_diagnostics)).apply {
            if (targets.size > 1) putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        }
    }

    internal fun createArchive(cacheDir: File, log: DiagnosticLog, environment: String): File {
        val directory = File(cacheDir, "feedback")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create report directory")
        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "zip" }
            .sortedByDescending(File::lastModified).drop(2).forEach {
                if (!it.delete()) throw IOException("Could not remove old diagnostic archive")
            }
        // A previously granted URI must never reveal a report generated later.
        val archive = File(directory, "message487-diagnostics-${UUID.randomUUID()}.zip")
        try {
            log.record(DiagnosticEvent.REPORT_PREPARED)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostic.log"))
                log.copyLogsTo(zip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("crash-latest.log"))
                log.copyCrashTo(zip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("environment.txt"))
                zip.write(environment.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } catch (error: Exception) {
            archive.delete()
            throw error
        }
        return archive
    }

    private fun environment(settings: ForwardingSettings) = buildString {
        appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Build: ${BuildConfig.BUILD_TYPE}")
        appendLine("Android: ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        appendLine("Configured: ${settings.ready()}; ACK: ${settings.requireAck}; paused: ${settings.paused}")
        appendLine("Notifications: ${settings.notifications}; SMS: ${settings.sms}; selected apps: ${settings.packages.size}")
        appendLine("Exception messages, message content, source packages, recipients, URLs and installation IDs are excluded.")
    }
}
