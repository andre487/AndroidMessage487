package life.andre.message487

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdatesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun node(id: Int) = compose.onNodeWithText(compose.activity.getString(id))

    @Before fun reset() {
        compose.activity.getSharedPreferences("updates", 0).edit().clear().commit()
    }

    @Test fun unknownSourceRequiresChoiceAndDownloadRequiresConsent() {
        var downloads = 0
        val model = UpdatesViewModel(compose.activity.application,
            checkUpdate = { AppUpdate(it, "0.0.6") },
            downloadUpdate = { downloads++; error("Test failure") })
        compose.setContent { MessageTheme { UpdatesScreen(compose.activity, model) } }
        node(R.string.update_check).assertIsNotEnabled()
        node(R.string.update_github).performScrollTo().performClick()
        node(R.string.update_check).performScrollTo().performClick()
        node(R.string.update_download).performScrollTo().performClick()
        node(R.string.update_github_consent).assertIsDisplayed()
        node(R.string.cancel).performClick()
        compose.runOnIdle { assertEquals(0, downloads) }
        node(R.string.update_download).performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.update_download)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        node(R.string.update_failed).assertExists()
        compose.runOnIdle { assertEquals(1, downloads); assertNull(model.apk) }
    }

    @Test fun fdroidOpensStoreAndFailureDoesNotFallBackToGithub() {
        var fail = false
        val model = UpdatesViewModel(compose.activity.application,
            checkUpdate = { if (fail) throw UpdateException(R.string.update_not_published) else AppUpdate(it, "0.0.6") },
            downloadUpdate = { error("F-Droid must never download an APK here") })
        model.select(UpdateSource.FDROID)
        compose.setContent { MessageTheme { UpdatesScreen(compose.activity, model) } }
        node(R.string.update_check).performScrollTo().performClick()
        node(R.string.update_in_fdroid).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(FDROID_APP_URL, shadowOf(compose.activity).nextStartedActivity.dataString)
            model.download()
            assertNull(model.apk)
            fail = true
        }
        node(R.string.update_check).performScrollTo().performClick()
        node(R.string.update_not_published).assertExists()
        node(R.string.update_download).assertDoesNotExist()
        assertEquals(UpdateSource.FDROID, model.source)
    }

    @Test fun launchDialogOffersGithubNavigationAndSnoozesWithoutDownloading() {
        AppUpdates(compose.activity).select(UpdateSource.GITHUB)
        val prefs = UpdatePreferences(compose.activity)
        prefs.detected(UpdateSource.GITHUB, AppUpdate(UpdateSource.GITHUB, "99.0.0"))
        var opened = 0
        compose.setContent { MessageTheme { UpdateAvailableDialog(compose.activity, 0) { opened++ } } }
        node(R.string.update_skip).assertIsDisplayed()
        node(R.string.update_remind_later).assertIsDisplayed()
        node(R.string.update_notification_open).performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertNull(prefs.pending())
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun launchDialogFdroidOpensPackagePage() {
        AppUpdates(compose.activity).select(UpdateSource.FDROID)
        val prefs = UpdatePreferences(compose.activity)
        val update = AppUpdate(UpdateSource.FDROID, "99.0.0")
        prefs.detected(update.source, update)
        compose.setContent { MessageTheme { UpdateAvailableDialog(compose.activity, 0) { error("F-Droid must not download") } } }
        node(R.string.update_in_fdroid).performClick()
        compose.runOnIdle {
            assertEquals(FDROID_APP_URL, shadowOf(compose.activity).nextStartedActivity.data.toString())
            assertNull(prefs.pending())
        }
    }

    @Test fun launchDialogSkipSuppressesVersionAcrossRecreation() {
        AppUpdates(compose.activity).select(UpdateSource.GITHUB)
        val prefs = UpdatePreferences(compose.activity)
        prefs.detected(UpdateSource.GITHUB, AppUpdate(UpdateSource.GITHUB, "99.0.0"))
        compose.setContent { MessageTheme { UpdateAvailableDialog(compose.activity, 0) {} } }
        node(R.string.update_skip).performClick()
        compose.runOnIdle { assertNull(UpdatePreferences(compose.activity).pending(Long.MAX_VALUE)) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun backgroundDiagnosticIsVisibleAndManualCheckDoesNotOverwriteIt() {
        AppUpdates(compose.activity).select(UpdateSource.GITHUB)
        val prefs = UpdatePreferences(compose.activity)
        prefs.detected(UpdateSource.GITHUB, AppUpdate(UpdateSource.GITHUB, "99.0.0"))
        prefs.backgroundStarted(UpdateSource.GITHUB, 1000)
        prefs.backgroundFinished("network_error")
        val model = UpdatesViewModel(compose.activity.application, checkUpdate = { null })
        compose.setContent { MessageTheme { UpdatesScreen(compose.activity, model) } }
        node(R.string.update_background_retry).performScrollTo().assertIsDisplayed()
        node(R.string.update_check).performScrollTo().performClick()
        node(R.string.update_current).assertExists()
        compose.runOnIdle {
            assertEquals(1000, prefs.lastBackgroundTime)
            assertEquals("network_error", prefs.lastBackgroundResult)
            assertNull(prefs.pending())
        }
    }

    @Test fun notificationEntrySuppressesDuplicateDialog() {
        AppUpdates(compose.activity).select(UpdateSource.GITHUB)
        val prefs = UpdatePreferences(compose.activity)
        prefs.detected(UpdateSource.GITHUB, AppUpdate(UpdateSource.GITHUB, "99.0.0"))
        compose.setContent { MessageTheme { UpdateAvailableDialog(compose.activity, 1) {} } }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertNotNull(prefs.pending())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNode(isDialog()).assertExists()
    }

    @Test fun remindLaterSuppressesBothDialogAndNotification() {
        AppUpdates(compose.activity).select(UpdateSource.GITHUB)
        val prefs = UpdatePreferences(compose.activity)
        val update = AppUpdate(UpdateSource.GITHUB, "99.0.0")
        prefs.detected(update.source, update)
        compose.setContent { MessageTheme { UpdateAvailableDialog(compose.activity, 0) {} } }
        node(R.string.update_remind_later).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertNull(UpdatePreferences(compose.activity).pending())
        assertFalse(prefs.shouldNotify(update, System.currentTimeMillis()))
    }

    @Test fun manualCheckStillShowsSkippedVersion() {
        AppUpdates(compose.activity).select(UpdateSource.GITHUB)
        val update = AppUpdate(UpdateSource.GITHUB, "99.0.0")
        UpdatePreferences(compose.activity).skip(UpdatePreferences.key(update))
        val model = UpdatesViewModel(compose.activity.application, checkUpdate = { update })
        compose.setContent { MessageTheme { UpdatesScreen(compose.activity, model) } }
        node(R.string.update_check).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.update_available, update.version)).assertExists()
        compose.runOnIdle { assertNull(UpdatePreferences(compose.activity).pending()) }
    }
}
