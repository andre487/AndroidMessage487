package life.andre.message487

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource

@Composable
internal fun UpdateAvailableDialog(activity: Activity, openRequest: Int, onOpen: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current
    val preferences = remember { UpdatePreferences(activity) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var openFailed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, openRequest) {
        var suppressNextRefresh = openRequest > 0
        fun refresh() {
            update = if (suppressNextRefresh) null else preferences.pending()
            suppressNextRefresh = false
            openFailed = false
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        // Registering replays the current lifecycle, including ON_RESUME.
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val candidate = update ?: return
    fun later() {
        preferences.remindLater(candidate)
        UpdateNotifications.cancel(activity)
        update = null
    }
    AlertDialog(
        onDismissRequest = ::later,
        title = { Text(stringResource(R.string.update_available, candidate.version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val label = stringResource(if (candidate.source == UpdateSource.FDROID) R.string.update_fdroid else R.string.update_github)
                Text(stringResource(R.string.update_notification_text, label))
                if (openFailed) Text(stringResource(R.string.update_open_error))
                TextButton(onClick = ::later) { Text(stringResource(R.string.update_remind_later)) }
                TextButton(onClick = {
                    preferences.skip(UpdatePreferences.key(candidate))
                    UpdateNotifications.cancel(activity)
                    update = null
                }) { Text(stringResource(R.string.update_skip)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (candidate.source == UpdateSource.FDROID) {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_APP_URL)))
                        later()
                    } catch (_: Exception) { openFailed = true }
                } else {
                    later()
                    onOpen()
                }
            }) { Text(stringResource(if (candidate.source == UpdateSource.FDROID) R.string.update_in_fdroid else R.string.update_notification_open)) }
        },
    )
}
