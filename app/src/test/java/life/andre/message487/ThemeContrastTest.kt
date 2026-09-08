package life.andre.message487

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ThemeContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "notnight")
    fun `light theme has legible body and action text`() = checkTheme(dark = false)

    @Test @Config(qualifiers = "night")
    fun `dark theme follows system and has legible body and action text`() = checkTheme(dark = true)

    private fun checkTheme(dark: Boolean) {
        lateinit var colors: ColorScheme
        compose.setContent { MessageTheme { colors = MaterialTheme.colorScheme } }
        compose.runOnIdle {
            val surfaceLuminance = ColorUtils.calculateLuminance(colors.surface.toArgb())
            assertTrue("Wrong system theme", if (dark) surfaceLuminance < 0.1 else surfaceLuminance > 0.8)
            for ((foreground, background) in listOf(
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceContainerLowest,
                colors.onPrimary to colors.primary,
                colors.error to colors.surface,
            )) {
                assertTrue("Text contrast below WCAG AA", ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
            }
        }
    }
}
