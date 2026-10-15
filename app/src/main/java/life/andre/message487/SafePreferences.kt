package life.andre.message487

import android.content.SharedPreferences
import java.io.IOException

// A malformed field must not discard unrelated connection details or credentials.
internal fun SharedPreferences.readString(key: String, default: String? = null): String? =
    try { getString(key, default) } catch (_: ClassCastException) { default }
internal fun SharedPreferences.readBoolean(key: String, default: Boolean): Boolean =
    try { getBoolean(key, default) } catch (_: ClassCastException) { default }
internal fun SharedPreferences.readInt(key: String, default: Int): Int =
    try { getInt(key, default) } catch (_: ClassCastException) { default }
internal fun SharedPreferences.readLong(key: String, default: Long): Long =
    try { getLong(key, default) } catch (_: ClassCastException) { default }
internal fun SharedPreferences.readStrings(key: String): Set<String> =
    try { getStringSet(key, emptySet()).orEmpty().toSet() } catch (_: ClassCastException) { emptySet() }
internal fun SharedPreferences.Editor.save() {
    if (!commit()) throw IOException("Could not save preferences")
}
