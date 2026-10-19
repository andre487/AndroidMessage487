package life.andre.message487

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsTransferPanel(model: ConnectionViewModel, busy: Boolean) {
    var includeToken by rememberSaveable { mutableStateOf(true) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportDocument(uri, includeToken)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importDocument(uri)
    }
    val pending by model.pendingImport.collectAsState()
    Panel {
        SectionTitle(stringResource(R.string.settings_transfer))
        SupportingText(stringResource(R.string.settings_transfer_hint))
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = includeToken, enabled = !busy, role = Role.Switch, onValueChange = { includeToken = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.export_include_token), Modifier.weight(1f))
                    Switch(checked = includeToken, onCheckedChange = null, enabled = !busy)
                }
                Text(stringResource(if (includeToken) R.string.export_token_warning else R.string.export_without_token),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = { export.launch("message487-settings.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_settings))
        }
        OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_settings)) }
    }
    pending?.let { settings ->
        AlertDialog(onDismissRequest = { if (!busy) model.cancelImport() },
            title = { Text(stringResource(R.string.import_settings)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_confirmation))
                Text(stringResource(R.string.import_destination, settings.url))
                Text(stringResource(R.string.import_device, settings.deviceCode))
                Text(stringResource(R.string.import_sources, settings.packages.size))
                Text(stringResource(if (settings.notifications) R.string.import_notifications_on else R.string.import_notifications_off))
                Text(stringResource(if (settings.sms) R.string.import_sms_on else R.string.import_sms_off))
                Text(stringResource(if (settings.authToken.isEmpty()) R.string.import_token_missing else R.string.import_token_present))
            } },
            confirmButton = { TextButton(onClick = model::confirmImport, enabled = !busy) { Text(stringResource(R.string.apply_settings)) } },
            dismissButton = { TextButton(onClick = model::cancelImport, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    }
}
