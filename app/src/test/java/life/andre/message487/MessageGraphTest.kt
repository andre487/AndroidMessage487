package life.andre.message487

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, sdk = [26, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageGraphTest {
    @Test fun `overview tests are text only and connection tests use explicitly selected image features`() {
        val app = RuntimeEnvironment.getApplication() as UiTestApplication
        // Queue requests without running delivery workers while inspecting payloads.
        val idle = java.util.concurrent.Executor { }
        val tasks = java.util.concurrent.Executors.newSingleThreadExecutor()
        androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder()
            .setExecutor(idle).setTaskExecutor(tasks).build())
        org.robolectric.Shadows.shadowOf(app.packageManager).setApplicationIcon(
            app.packageName, requireNotNull(app.getDrawable(app.applicationInfo.icon)))
        val graph = app.graph
        try {
            for ((avatars, icons) in listOf(false to false, true to false, false to true, true to true)) {
                graph.settings.update { it.copy(url = "https://example.test/receive", authToken = "test-token",
                    deviceCode = "test", forwardAvatars = avatars, forwardAppIcons = icons,
                    imageFormat = NotificationImageFormat.PNG) }
                val previous = graph.outbox.pendingIds().toSet()
                graph.enqueueTest()
                val id = graph.outbox.pendingIds().single { it !in previous }
                val event = JSONObject(requestJson(graph.outbox.beginAttempt(id)!!.request))
                assertEquals("test", event.getString("message_type"))
                assertFalse(event.has("images"))
            }
            // Test options are independent from the capture opt-ins above.
            graph.settings.update { it.copy(forwardAvatars = false, forwardAppIcons = false) }
            for (mask in 0..15) {
                val options = TestMessageOptions(mask and 1 != 0, mask and 2 != 0,
                    mask and 4 != 0, mask and 8 != 0)
                val previous = graph.outbox.pendingIds().toSet()
                graph.enqueueTest(options)
                val id = graph.outbox.pendingIds().single { it !in previous }
                val event = JSONObject(requestJson(graph.outbox.beginAttempt(id)!!.request))
                val expected = buildList {
                    if (options.bigPicture) add("attachment:big_picture")
                    if (options.gallery) { add("attachment:gallery"); add("attachment:gallery") }
                    if (options.avatar) add("avatar:file")
                    if (options.appIcon) add("app_icon:file")
                }
                val images = event.optJSONArray("images")
                assertEquals(expected, (0 until (images?.length() ?: 0)).map {
                    val image = images!!.getJSONObject(it)
                    assertEquals("image/png", image.getString("mime_type"))
                    assertTrue(image.getString("data_base64").isNotEmpty())
                    image.getString("kind") + ":" + image.getString("presentation")
                })
            }
            graph.settings.update { it.copy(imageMaxCount = 1) }
            val limited = testMessageImages(app, graph.settings.state.value,
                TestMessageOptions(bigPicture = true, gallery = true))
            try {
                assertEquals(listOf("big_picture"), limited.map { it.presentation })
            } finally { discardImages(limited) }
        } finally {
            tasks.shutdownNow()
            graph.captureExecutor.shutdownNow()
            graph.smsExecutor.shutdownNow()
            graph.imageCaptureQueue.close()
            graph.outbox.close()
            app.diagnostics.close()
        }
    }
}
