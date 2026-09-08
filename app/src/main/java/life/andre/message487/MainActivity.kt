package life.andre.message487

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ConnectionScreen()
            }
        }
    }
}

@Composable
private fun ConnectionScreen(model: ConnectionViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleMedium)
            Card {
                Text(stringResource(R.string.development_status), Modifier.padding(16.dp))
            }
            Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.deviceCode,
                onValueChange = model::setDeviceCode,
                label = { Text(stringResource(R.string.device_code)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                singleLine = true,
                isError = state.invalidDeviceCode,
                supportingText = {
                    Text(stringResource(if (state.invalidDeviceCode) R.string.invalid_device_code else R.string.device_code_hint))
                },
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = model::setUrl,
                label = { Text(stringResource(R.string.webhook_url)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                isError = state.invalidUrl,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text(stringResource(if (state.invalidUrl) R.string.invalid_url else R.string.url_hint))
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.n8n_mode), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (state.requireAck) R.string.n8n_hint else R.string.raw_hint))
                }
                Switch(checked = state.requireAck, onCheckedChange = model::setRequireAck, enabled = !state.busy)
            }
            OutlinedButton(onClick = { model.save(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save))
            }
            Button(onClick = { model.save(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_test))
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.sending))
            }
            if (state.saved) Text(stringResource(R.string.saved))
            if (state.saveFailed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.journal), style = MaterialTheme.typography.titleLarge)
            if (state.results.isEmpty()) Text(stringResource(R.string.no_events))
            state.results.forEach { result ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(result.status.label()), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.event_id, result.eventId), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.duration, result.durationMs))
                        result.httpCode?.let { Text(stringResource(R.string.http_code, it)) }
                    }
                }
            }
            Text(stringResource(R.string.delivery_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun DeliveryStatus.label(): Int = when (this) {
    DeliveryStatus.ACCEPTED -> R.string.accepted
    DeliveryStatus.HTTP_SUCCESS -> R.string.http_success
    DeliveryStatus.HTTP_ERROR -> R.string.http_error
    DeliveryStatus.INVALID_ACK -> R.string.invalid_ack
    DeliveryStatus.TIMEOUT -> R.string.timeout
    DeliveryStatus.NETWORK_ERROR -> R.string.network_error
}
