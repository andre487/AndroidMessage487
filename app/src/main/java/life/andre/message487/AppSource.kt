package life.andre.message487

import android.content.pm.PackageManager
import android.os.Build

data class AppSource(val packageName: String, val name: String)

class AppSourceResolver(private val packageManager: PackageManager) {
    fun resolve(packageName: String): AppSource {
        val name = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() } ?: packageName
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: SecurityException) {
            packageName
        }
        return AppSource(packageName, name)
    }
}
