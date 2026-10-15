package life.andre.message487

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import life.andre.message487.diagnostics.DiagnosticEvent
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

// UI tests retain real stores/screens but do not install a process crash handler or start workers.
class UiTestApplication : MessageApplication() {
    override val payloadCipher = object : PayloadCipher {
        override fun encrypt(value: String) = value.reversed().toByteArray()
        override fun decrypt(value: ByteArray) = String(value).reversed()
    }
    override fun onCreate() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        for (pkg in listOf("example.alpha", "example.beta", packageName)) {
            val info = android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply { packageName = pkg; name = "$pkg.MainActivity" }
            }
            org.robolectric.Shadows.shadowOf(packageManager).addResolveInfoForIntent(intent, info)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, sdk = [35], qualifiers = "en-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val application get() = compose.activity.applicationContext as UiTestApplication
    private fun node(id: Int) = compose.onNodeWithText(compose.activity.getString(id))
    private fun icon(id: Int) = compose.onNodeWithContentDescription(compose.activity.getString(id))

    private lateinit var model: ConnectionViewModel

    @Before fun showScreen() {
        compose.runOnIdle {
            model = androidx.lifecycle.ViewModelProvider(compose.activity, object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ConnectionViewModel(application) as T
                }
            })[ConnectionViewModel::class.java]
        }
        compose.setContent { MessageTheme { MessageScreen(model) } }
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
    }

    @After fun close() {
        application.graph.captureExecutor.shutdownNow()
        application.graph.smsExecutor.shutdownNow()
        application.graph.imageCaptureQueue.close()
        application.diagnostics.close()
    }

    @Test fun `duplicate filter can be disabled and reenabled from sources`() {
        compose.onNode(hasText(compose.activity.getString(R.string.sources_tab)) and hasClickAction()).performClick()
        icon(R.string.sms_deduplication).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertFalse(SettingsStore(application).load().smsDeduplication)
        icon(R.string.sms_deduplication).assertIsOff().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertTrue(SettingsStore(application).load().smsDeduplication)
        icon(R.string.sms_deduplication).assertIsOn()
        icon(R.string.notification_deduplication).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertFalse(SettingsStore(application).load().notificationDeduplication)
        assertTrue(SettingsStore(application).load().smsDeduplication)
        node(R.string.deduplication_window).performScrollTo().performTextReplacement("3")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            SettingsStore(application).load().deduplicationWindowSeconds == 3
        }
    }

    @Test fun `tabs navigate and diagnostics back returns to previous screen`() {
        for (destination in listOf(R.string.sources_tab, R.string.journal, R.string.connection_nav)) {
            compose.onNode(hasText(compose.activity.getString(destination)) and hasClickAction()).performClick()
            icon(R.string.back).performClick()
            node(R.string.app_name).assertIsDisplayed()
        }
        node(R.string.connection_nav).performClick()
        icon(R.string.diagnostics).performClick()
        node(R.string.diagnostic_report).assertIsDisplayed()
        icon(R.string.back).performClick()
        node(R.string.connection_heading).assertIsDisplayed()
    }

    @Test fun `overview and connection show version and open GitHub and privacy policy`() {
        for (connection in listOf(false, true)) {
            if (connection) compose.onNode(hasText(application.getString(R.string.connection_nav)) and hasClickAction()).performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(application.getString(R.string.privacy_policy)))
            compose.onNodeWithText(application.getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT_HASH)).assertIsDisplayed()
            node(R.string.github).performClick()
            compose.runOnIdle {
                val intent = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
                assertNotNull(intent)
                assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
                assertEquals("https://github.com/andre487/AndroidMessage487", intent.dataString)
            }
            node(R.string.privacy_policy).performClick()
            compose.runOnIdle {
                val intent = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
                assertNotNull(intent)
                assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
                assertEquals("https://github.com/andre487/AndroidMessage487/blob/main/PRIVACY.md", intent.dataString)
            }
        }
    }

    @Test fun `invalid URL is rejected then valid connection persists across store recreation`() {
        node(R.string.connection_nav).performClick()
        node(R.string.webhook_url).performTextReplacement("not a URL")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(application.getString(R.string.save)))
        node(R.string.save).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(application.getString(R.string.invalid_url)))
        node(R.string.invalid_url).performScrollTo().assertIsDisplayed()
        assertNotEquals("not a URL", application.graph.settings.state.value.url)
        node(R.string.webhook_url).performTextReplacement("https://example.test/webhook")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(application.getString(R.string.save)))
        node(R.string.save).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(application.getString(R.string.invalid_token)))
        node(R.string.invalid_token).assertIsDisplayed()
        assertEquals("", application.graph.settings.state.value.authToken)
        node(R.string.auth_token).performScrollTo().performTextReplacement("test-token")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(application.getString(R.string.device_code)))
        node(R.string.device_code).performScrollTo().performTextReplacement("test-phone")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(application.getString(R.string.save)))
        node(R.string.save).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertSame(application.graph.settings.state, model.settings)
        assertEquals("test-phone", model.state.value.deviceCode)
        assertEquals("https://example.test/webhook", model.state.value.url)
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertNull("Save failed", model.state.value.notice?.takeIf { it == R.string.local_error })
        assertEquals("State: ${model.state.value}", "test-phone", application.graph.settings.state.value.deviceCode)
        compose.waitForIdle()
        val restored = SettingsStore(application).load()
        assertEquals("https://example.test/webhook", restored.url)
        assertEquals("test-phone", restored.deviceCode)
        assertEquals("test-token", restored.authToken)
        assertTrue(restored.deviceId.isNotBlank())
    }

    @Test fun `diagnostic clear requires confirmation and removes crash and archives`() {
        application.diagnostics.writeCrash(Thread.currentThread(), IllegalStateException("private fixture"))
        val archive = java.io.File(application.cacheDir, "feedback/test.zip")
        archive.parentFile!!.mkdirs()
        archive.writeText("fixture")
        icon(R.string.diagnostics).performClick()
        compose.waitUntil(10_000) { node(R.string.clear_log).isEnabled() }
        node(R.string.clear_log).performScrollTo().performClick()
        node(R.string.cancel).performClick()
        assertTrue(archive.exists())
        node(R.string.clear_log).performClick()
        compose.onAllNodesWithText(compose.activity.getString(R.string.clear_log)).onLast().performClick()
        compose.waitUntil(10_000) { !archive.exists() }
        compose.waitUntil(10_000) { node(R.string.clear_log).isEnabled() }
        assertFalse(java.io.File(application.filesDir, "logs/crash-latest.log").exists())
        assertTrue(application.diagnostics.readTail().contains(DiagnosticEvent.LOG_CLEARED.name))
    }

    @Test fun `connection test switches are independent from notification capture settings`() {
        node(R.string.connection_nav).performClick()
        val list = compose.onNode(hasScrollToIndexAction())
        for (title in listOf(R.string.test_big_picture, R.string.test_gallery,
                R.string.test_avatar, R.string.test_app_icon)) {
            list.performScrollToNode(hasText(application.getString(title)))
            node(title).assertIsOff().performClick().assertIsOn()
        }
        assertEquals(TestMessageOptions(true, true, true, true), model.state.value.testOptions)
        assertFalse(application.graph.settings.state.value.forwardAvatars)
        assertFalse(application.graph.settings.state.value.forwardAppIcons)
        list.performScrollToNode(hasText(application.getString(R.string.test_avatar)))
        node(R.string.test_avatar).performClick().assertIsOff()
        assertFalse(model.state.value.testOptions.avatar)
        assertTrue(model.state.value.testOptions.appIcon)
        node(R.string.send_test).performScrollTo().assertExists()
    }

    @Test fun `image format and JPEG quality can be configured from sources`() {
        compose.onNode(hasText(application.getString(R.string.sources_tab)) and hasClickAction()).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(application.getString(R.string.notification_images)))
        compose.onNodeWithText("JPEG").assertIsSelected()
        icon(R.string.image_quality_label).performScrollTo().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(35f) }
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertEquals(35, SettingsStore(application).load().imageQuality)
        compose.onNodeWithText("PNG").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertEquals(NotificationImageFormat.PNG, SettingsStore(application).load().imageFormat)
        icon(R.string.image_quality_label).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("JPEG").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertEquals(35, SettingsStore(application).load().imageQuality)
        icon(R.string.image_quality_label).performScrollTo().assertIsEnabled()
    }

    @Test fun `graphics limits can be changed and disabled without losing values`() {
        compose.onNode(hasText(application.getString(R.string.sources_tab)) and hasClickAction()).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(
            hasContentDescription(application.getString(R.string.image_limits_enabled)))
        icon(R.string.image_limits_enabled).performScrollTo().assertIsOn()
        node(R.string.image_max_count).performScrollTo().performTextReplacement("7")
        compose.waitUntil(10_000) { compose.waitForIdle(); model.settings.value.imageMaxCount == 7 }
        assertEquals(7, SettingsStore(application).load().imageMaxCount)
        node(R.string.image_max_edge).performScrollTo().performTextReplacement("2048")
        compose.waitUntil(10_000) { compose.waitForIdle(); model.settings.value.imageMaxEdge == 2048 }
        assertEquals(2048, SettingsStore(application).load().imageMaxEdge)
        node(R.string.image_max_kib).performScrollTo().performTextReplacement("1024")
        compose.waitUntil(10_000) { compose.waitForIdle(); model.settings.value.imageMaxKiB == 1024 }
        assertEquals(1024, SettingsStore(application).load().imageMaxKiB)
        node(R.string.image_total_kib).performScrollTo().performTextReplacement("4096")
        compose.waitUntil(10_000) { compose.waitForIdle(); model.settings.value.imageTotalKiB == 4096 }
        assertEquals(4096, SettingsStore(application).load().imageTotalKiB)
        icon(R.string.image_limits_enabled).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertFalse(SettingsStore(application).load().imageLimitsEnabled)
        node(R.string.image_max_count).assertDoesNotExist()
        icon(R.string.image_limits_enabled).performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        node(R.string.image_max_count).performScrollTo()
        compose.onNode(hasSetTextAction() and hasText("7")).assertExists()
    }

    @Test fun `avatar and app icon switches are independent and off by default`() {
        compose.onNode(hasText(application.getString(R.string.sources_tab)) and hasClickAction()).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(
            hasContentDescription(application.getString(R.string.forward_avatars)))
        icon(R.string.forward_avatars).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertTrue(SettingsStore(application).load().forwardAvatars)
        assertFalse(SettingsStore(application).load().forwardAppIcons)
        icon(R.string.forward_app_icons).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertTrue(SettingsStore(application).load().forwardAppIcons)
        icon(R.string.forward_avatars).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertFalse(SettingsStore(application).load().forwardAvatars)
        assertTrue(SettingsStore(application).load().forwardAppIcons)
    }

    @Test fun `auto select switch preserves existing choices and selects newly discovered launcher apps`() {
        compose.waitUntil(10_000) { compose.waitForIdle(); model.apps.value.size == 2 }
        compose.onNode(hasText(application.getString(R.string.sources_tab)) and hasClickAction()).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(
            hasContentDescription(application.getString(R.string.auto_select_new_apps)))
        icon(R.string.auto_select_new_apps).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertTrue(SettingsStore(application).load().autoSelectNewApps)
        assertTrue(model.settings.value.packages.isEmpty())
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "example.new"; name = "example.new.MainActivity" }
        }
        org.robolectric.Shadows.shadowOf(application.packageManager).addResolveInfoForIntent(intent, info)
        compose.runOnIdle { model.refreshPermissions() }
        compose.waitUntil(10_000) { compose.waitForIdle(); model.apps.value.any { it.packageName == "example.new" } }
        assertEquals(setOf("example.new"), model.settings.value.packages)
        compose.runOnIdle { model.selectPackage("example.new", false) }
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        compose.runOnIdle { model.refreshPermissions() }
        compose.waitForIdle()
        assertTrue(model.settings.value.packages.isEmpty())
        icon(R.string.auto_select_new_apps).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertFalse(SettingsStore(application).load().autoSelectNewApps)
    }

    @Test fun `select all preserves manual packages excludes self and clear removes selection`() {
        compose.waitForIdle()
        assertSame(application.graph.settings.state, model.settings)
        compose.waitUntil(10_000) { compose.waitForIdle(); model.apps.value.isNotEmpty() }
        assertEquals(2, model.apps.value.size)
        compose.runOnIdle { model.selectPackage("manual.hidden", true) }
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertEquals(setOf("manual.hidden"), SettingsStore(application).load().packages)
        compose.onNode(hasText(compose.activity.getString(R.string.sources_tab)) and hasClickAction()).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(
            hasText(compose.activity.getString(R.string.select_all_apps)))
        node(R.string.select_all_apps).performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertEquals(setOf("manual.hidden", "example.alpha", "example.beta"), SettingsStore(application).load().packages)
        node(R.string.clear_app_selection).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); !model.state.value.busy }
        assertEquals(emptySet<String>(), SettingsStore(application).load().packages)
    }

    private fun SemanticsNodeInteraction.isEnabled(): Boolean =
        !fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
}
