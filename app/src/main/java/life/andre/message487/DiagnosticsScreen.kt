package life.andre.message487

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.andre.message487.diagnostics.FeedbackEmail
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun DiagnosticsScreen(application: MessageApplication, settings: ForwardingSettings) {
    val context = LocalContext.current
    val log = application.diagnostics
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    suspend fun refresh() {
        text = withContext(Dispatchers.IO) {
            val crash = ByteArrayOutputStream().also(log::copyCrashTo).toString("UTF-8")
            log.readTail() + if (crash.isEmpty()) "" else "\n--- Last crash ---\n$crash"
        }
        failed = log.ioFailed
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        busy = true
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel {
            Text(stringResource(R.string.diagnostic_report), style = MaterialTheme.typography.titleMedium)
            SupportingText(stringResource(R.string.diagnostics_description))
            Text(FeedbackEmail.ADDRESS, style = MaterialTheme.typography.bodyMedium)
            Button(enabled = !busy, onClick = { runAction {
                val intent = withContext(Dispatchers.IO) { FeedbackEmail.createIntent(context, log, settings) }
                context.startActivity(intent)
                refresh()
            } }) { Text(stringResource(R.string.send_diagnostics)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.diagnostics_error), color = MaterialTheme.colorScheme.error)
        Panel {
            Text(stringResource(R.string.local_log), style = MaterialTheme.typography.titleMedium)
            SupportingText(stringResource(R.string.diagnostics_limits))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { runAction { refresh() } }) {
                    Text(stringResource(R.string.refresh_log))
                }
                TextButton(enabled = !busy, onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.clear_log))
                }
            }
            SelectionContainer {
                Text(text.ifEmpty { stringResource(R.string.log_empty) },
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.clear_log)) },
        text = { Text(stringResource(R.string.clear_log_description)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            runAction {
                withContext(Dispatchers.IO) {
                    log.clear()
                    File(application.cacheDir, "feedback").listFiles().orEmpty().forEach {
                        if (!it.delete()) throw IOException("Could not delete report")
                    }
                    application.crashHandler.dismiss()
                }
                refresh()
            }
        }) { Text(stringResource(R.string.clear_log)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
}
