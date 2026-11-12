package com.reianix.reianix_local

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Derive the previous storage identity from the unchanged Android application ID.
 * There are no duplicate brand strings in the sources or public UI.
 */
internal object ReiAnixLegacyIdentity {
    val previousPrefix: String
        get() = BuildConfig.APPLICATION_ID.substringAfter("com.").substringBefore('.')

    val previousBackupFormat: String
        get() = "rei-" + previousPrefix.removePrefix("rei") + "-backup-v1"

    val previousSettingsFormat: String
        get() = previousPrefix + "-settings"

    fun rename(oldName: String): String {
        val original = previousPrefix
        if (original.isBlank() || original.equals("reianix", ignoreCase = true)) return oldName
        val previousForms = listOf(original, original.take(3) + "-" + original.drop(3))
        return previousForms.fold(oldName) { current, previous ->
            Regex(Regex.escape(previous), RegexOption.IGNORE_CASE).replace(current) { match ->
                when {
                    match.value == match.value.uppercase(Locale.ROOT) -> "REIANIX"
                    match.value.first().isUpperCase() -> "ReiAnix"
                    else -> "reianix"
                }
            }
        }
    }
}

/**
 * Safe and synchronous migration of small, app-private metadata before any
 * repository opens it. The canonical SQLite library, downloads and WebView data
 * remain in their original Android application sandbox.
 */
internal object ReiAnixLegacyStorageMigration {
    fun migrate(context: Context) {
        val legacy = ReiAnixLegacyIdentity.previousPrefix
        if (legacy.isBlank() || legacy.equals("reianix", ignoreCase = true)) return
        val marker = context.getSharedPreferences("reianix_migration", Context.MODE_PRIVATE)
        if (marker.getBoolean("legacy_storage_v1", false)) return
        var success = true
        val data = File(context.filesDir, "data")
        val targets = listOf(
            File(data, legacy + "-native-index.json") to File(data, "reianix-native-index.json"),
            File(data, legacy + "-native-events") to File(data, "reianix-native-events"),
            File(data, legacy + "-native-commands") to File(data, "reianix-native-commands"),
            File(context.cacheDir, legacy) to File(context.cacheDir, "reianix"),
        )
        targets.forEach { (previous, updated) ->
            if (!previous.exists() || updated.exists()) return@forEach
            if (!previous.renameTo(updated)) {
                success = false
                Log.w("ReiAnixMigration", "Could not migrate private metadata: " + previous.name)
            }
        }
        if (!migratePreferences(context)) success = false
        if (success) marker.edit().putBoolean("legacy_storage_v1", true).commit()
    }

    private fun migratePreferences(context: Context): Boolean {
        var success = true
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        val xmlFiles = directory.listFiles()?.filter { it.isFile && it.extension == "xml" }.orEmpty()
        xmlFiles.forEach { xml ->
            val oldName = xml.name.removeSuffix(".xml")
            val newName = ReiAnixLegacyIdentity.rename(oldName)
            val oldPrefs = context.getSharedPreferences(oldName, Context.MODE_PRIVATE)
            val newPrefs = context.getSharedPreferences(newName, Context.MODE_PRIVATE)
            val existing = newPrefs.all
            val edits = newPrefs.edit()
            var hasChanges = false
            oldPrefs.all.forEach { (oldKey, value) ->
                val key = ReiAnixLegacyIdentity.rename(oldKey)
                if (existing.containsKey(key)) return@forEach
                when (value) {
                    is String -> edits.putString(key, value)
                    is Boolean -> edits.putBoolean(key, value)
                    is Int -> edits.putInt(key, value)
                    is Long -> edits.putLong(key, value)
                    is Float -> edits.putFloat(key, value)
                    is Set<*> -> edits.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> return@forEach
                }
                hasChanges = true
            }
            if (hasChanges && !edits.commit()) {
                success = false
                Log.w("ReiAnixMigration", "Could not preserve previous private preferences")
            }
        }
        return success
    }
}
