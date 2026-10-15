package life.andre.message487

import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android disk/network detection, including first construction of the UI models. */
@RunWith(AndroidJUnit4::class)
class UiThreadDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previousPolicy: StrictMode.ThreadPolicy? = null
    private val app get() = compose.activity.application as MessageApplication
    private lateinit var model: ConnectionViewModel

    private fun node(id: Int) = compose.onNodeWithText(app.getString(id))
    private fun icon(id: Int) = compose.onNodeWithContentDescription(app.getString(id))

    private fun show() {
        // This test never sends a message or changes the user's emulator: run on disposable devices.
        app.getSharedPreferences("connection", 0).edit().clear().commit()
        app.getSharedPreferences("updates", 0).edit().clear().commit()
        // Exercise real persisted-token decryption when the ViewModel starts.
        SettingsStore(app).update { it.copy(url = "https://example.test/hook", authToken = "synthetic-ui-token") }
        compose.runOnUiThread {
            previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().penaltyDeath().build())
            model = androidx.lifecycle.ViewModelProvider(compose.activity)[ConnectionViewModel::class.java]
        }
        compose.setContent { MessageTheme {
            MessageScreen(model)
            UpdateAvailableDialog(compose.activity, 0) {}
        } }
        compose.waitUntil(10_000) { model.settingsLoaded.value && !model.state.value.busy }
    }

    @After fun restore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            previousPolicy?.let(StrictMode::setThreadPolicy)
        }
    }

    @Test fun initializationAndUpdateFailuresKeepIoAndDiagnosticsOffMainThread() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val formatted = java.util.concurrent.CountDownLatch(1)
        val formattedOnMain = java.util.concurrent.atomic.AtomicBoolean()
        val failure = object : java.io.IOException("injected storage failure") {
            override fun getStackTrace(): Array<StackTraceElement> {
                formattedOnMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                formatted.countDown()
                return super.getStackTrace()
            }
        }
        compose.runOnUiThread {
            previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().penaltyDeath().build())
            model = ConnectionViewModel(app) {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                // Real disk I/O on both the failing read and its successful retry.
                java.io.File(app.cacheDir, "settings-read-probe").writeText("probe")
                if (reads.incrementAndGet() == 1) throw failure
                app.graph.settings.load()
            }
            app.reportLocalFailure(failure)
        }
        compose.setContent { MessageTheme { MessageScreen(model) } }
        compose.waitUntil(10_000) { model.state.value.initializationFailed && !model.state.value.busy }
        node(R.string.retry).performClick()
        compose.waitUntil(10_000) { !model.state.value.initializationFailed && !model.state.value.busy }
        assertEquals(2, reads.get())
        assertTrue(formatted.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(formattedOnMain.get())
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                var failed = false
                try {
                    // Creates/cleans a real temporary APK, then fails before any network request.
                    AppUpdates(app).download(AppUpdate(UpdateSource.GITHUB, "99.0.0"))
                } catch (_: IllegalArgumentException) { failed = true }
                assertTrue(failed)
            }
        }
    }

    @Test fun navigateEditAndRefreshWithoutMainThreadIo() {
        show()
        node(R.string.connection_nav).performClick()
        node(R.string.webhook_url).assertIsDisplayed()
        icon(R.string.back).performClick()
        compose.onNode(hasText(app.getString(R.string.sources_tab)) and hasClickAction()).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(app.getString(R.string.image_max_edge)))
        node(R.string.image_max_edge).performScrollTo().performTextReplacement("2")
        node(R.string.image_max_edge).performTextInput("048")
        compose.waitUntil(10_000) { model.settings.value.imageMaxEdge == 2048 }
        node(R.string.image_max_edge).assertIsEnabled()
        icon(R.string.back).performClick()
        node(R.string.journal).performClick()
        icon(R.string.back).performClick()
        icon(R.string.diagnostics).performClick()
        node(R.string.refresh_log).performClick()
        icon(R.string.back).performClick()
        icon(R.string.updates).performClick()
        node(R.string.update_github).performScrollTo().performClick()
        compose.waitUntil(10_000) { AppUpdates(app).source() == UpdateSource.GITHUB }
        node(R.string.update_automatic).performScrollTo()
        compose.onNodeWithContentDescription(app.getString(R.string.update_automatic)).performClick()
        compose.waitUntil(10_000) { !UpdatePreferences(app).automatic }
        UpdatePreferences(app).detected(UpdateSource.GITHUB, AppUpdate(UpdateSource.GITHUB, "99.0.0"))
        // App settings, permission checks and the update dialog refresh on ON_RESUME.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.update_remind_later)).fetchSemanticsNodes().isNotEmpty()
        }
        node(R.string.update_remind_later).performClick()
        compose.waitUntil(10_000) { UpdatePreferences(app).pending() == null }
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        app.getSharedPreferences("crash_state", 0).edit().putBoolean("pending", true).commit()
        app.crashHandler.load()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.crash_title)).fetchSemanticsNodes().isNotEmpty()
        }
        node(R.string.close).performClick()
        compose.waitUntil(10_000) { !app.crashHandler.pending }
        compose.runOnUiThread { app.graph.captureFailed(IllegalStateException("synthetic failure")) }
        compose.waitUntil(10_000) { model.settings.value.captureFailed }
    }

}
