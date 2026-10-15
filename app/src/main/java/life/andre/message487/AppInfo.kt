package life.andre.message487

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppInfo() {
    val context = LocalContext.current
    var browserError by remember { mutableStateOf(false) }
    fun openLink(url: String) {
        browserError = false
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            browserError = true
        } catch (_: SecurityException) {
            browserError = true
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(horizontalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT_HASH),
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { openLink("https://github.com/andre487/AndroidMessage487") }) {
                Text(stringResource(R.string.github), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = { openLink("https://github.com/andre487/AndroidMessage487/blob/main/PRIVACY.md") },
            modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.privacy_policy))
        }
        if (browserError) Text(stringResource(R.string.no_browser), color = MaterialTheme.colorScheme.error)
    }
}
