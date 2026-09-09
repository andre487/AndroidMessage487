package life.andre.message487

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectionScreen(state: ConnectionState, model: ConnectionViewModel) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connection_heading), style = MaterialTheme.typography.headlineSmall)
                SupportingText(stringResource(R.string.connection_description))
            }
        }
        item {
            Panel {
                SectionTitle(stringResource(R.string.recipient))
                OutlinedTextField(value = state.url, onValueChange = model::setUrl,
                    label = { Text(stringResource(R.string.webhook_url)) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    isError = state.invalidUrl, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = MaterialTheme.shapes.small,
                    supportingText = { Text(stringResource(if (state.invalidUrl) R.string.invalid_url else R.string.url_hint)) })
                OutlinedTextField(value = state.authToken, onValueChange = model::setAuthToken,
                    label = { Text(stringResource(R.string.auth_token)) }, modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy, singleLine = true, isError = state.invalidToken,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text(stringResource(if (state.invalidToken) R.string.invalid_token else R.string.token_hint)) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.n8n_mode), style = MaterialTheme.typography.titleSmall)
                        SupportingText(stringResource(if (state.requireAck) R.string.n8n_hint else R.string.raw_hint))
                    }
                    Switch(checked = state.requireAck, onCheckedChange = model::setRequireAck, enabled = !state.busy,
                        modifier = Modifier.switchLabel(stringResource(R.string.n8n_mode)))
                }
            }
        }
        item {
            Panel {
                SectionTitle(stringResource(R.string.this_device))
                OutlinedTextField(value = state.deviceCode, onValueChange = model::setDeviceCode,
                    label = { Text(stringResource(R.string.device_code)) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                    enabled = !state.busy, singleLine = true, isError = state.invalidDeviceCode,
                    supportingText = { Text(stringResource(if (state.invalidDeviceCode) R.string.invalid_device_code else R.string.device_code_hint)) })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.save(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.save))
                }
                OutlinedButton(onClick = { model.save(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.send_test))
                }
            }
        }
        item { SupportingText(stringResource(R.string.destination_note)) }
    }
}
