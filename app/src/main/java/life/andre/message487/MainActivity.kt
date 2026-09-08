package life.andre.message487

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MessageTheme { MessageScreen() } }
    }
}

private enum class Destination(val label: Int, val icon: ImageVector) {
    OVERVIEW(R.string.overview, Icons.Outlined.Dashboard),
    SOURCES(R.string.sources_tab, Icons.Outlined.Notifications),
    JOURNAL(R.string.journal, Icons.Outlined.History),
    CONNECTION(R.string.connection_nav, Icons.Outlined.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageScreen(model: ConnectionViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val permissions by model.permissions.collectAsStateWithLifecycle()
    val connected by model.listenerConnected.collectAsStateWithLifecycle()
    val queue by model.queue.collectAsStateWithLifecycle()
    val apps by model.apps.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.OVERVIEW) }
    var help by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let { stringResource(it) }
    LaunchedEffect(noticeText) { noticeText?.let { snackbar.showSnackbar(it) } }
    BackHandler(destination != Destination.OVERVIEW) { destination = Destination.OVERVIEW }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshPermissions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.height(24.dp))
                    Destination.entries.forEach { item ->
                        NavigationRailItem(selected = destination == item, onClick = { destination = item },
                            icon = { Icon(item.icon, null) }, label = { Text(stringResource(item.label)) })
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f).imePadding(),
                topBar = {
                    TopAppBar(title = {
                        Text(stringResource(if (destination == Destination.OVERVIEW) R.string.app_name
                            else if (destination == Destination.CONNECTION) R.string.connection else destination.label),
                            style = MaterialTheme.typography.titleLarge)
                    }, navigationIcon = {
                        if (destination != Destination.OVERVIEW) {
                            IconButton(onClick = { destination = Destination.OVERVIEW }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    }, actions = {
                        IconButton(onClick = { help = true }) { Icon(Icons.Outlined.HelpOutline, stringResource(R.string.help)) }
                    })
                },
                bottomBar = {
                    if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(selected = destination == item, onClick = { destination = item },
                                icon = { Icon(item.icon, null) }, label = { Text(stringResource(item.label)) })
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    key(destination) {
                        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                            when (destination) {
                                Destination.OVERVIEW -> OverviewScreen(settings, permissions, connected, queue, state.busy, model,
                                    onConnection = { destination = Destination.CONNECTION },
                                    onSources = { destination = Destination.SOURCES }, onJournal = { destination = Destination.JOURNAL })
                                Destination.SOURCES -> SourcesScreen(settings, permissions, connected, apps, state.busy, model)
                                Destination.JOURNAL -> JournalScreen(queue, state.busy, model)
                                Destination.CONNECTION -> ConnectionScreen(state, model)
                            }
                        }
                    }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false },
        icon = { Icon(Icons.Outlined.PrivacyTip, null) }, title = { Text(stringResource(R.string.delivery_help)) },
        text = { HelpContent() },
        confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.close)) } })
}
