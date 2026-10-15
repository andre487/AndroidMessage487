package life.andre.message487

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, sdk = [35], qualifiers = "en-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsLoadRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun `failed initialization offers retry and keeps saved credentials`() {
        val app = compose.activity.application as UiTestApplication
        app.graph.settings.update { it.copy(url = "https://example.test/hook", authToken = "secret") }
        val reads = AtomicInteger()
        lateinit var model: ConnectionViewModel
        try {
            compose.runOnIdle {
                model = ConnectionViewModel(app) {
                    if (reads.incrementAndGet() == 1) throw IOException("storage unavailable")
                    app.graph.settings.load()
                }
            }
            compose.setContent { MessageTheme { MessageScreen(model) } }
            compose.waitUntil(10_000) { compose.waitForIdle(); model.state.value.initializationFailed }
            assertFalse(model.state.value.busy)
            compose.onNodeWithText(compose.activity.getString(R.string.retry)).performClick()
            compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy && !model.state.value.initializationFailed }
            assertEquals("https://example.test/hook", model.state.value.url)
            assertEquals("secret", model.state.value.authToken)
            assertEquals(2, reads.get())
        } finally {
            app.graph.captureExecutor.shutdownNow()
            app.graph.smsExecutor.shutdownNow()
            app.graph.imageCaptureQueue.close()
            app.diagnostics.close()
        }
    }
}
