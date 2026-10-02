package com.immichframe.standalone

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Persists and restores all SharedPreferences across reinstalls, updates, and package name changes.
 *
 * Saves settings as JSON to persistent external storage (/sdcard/immichframe_settings.json)
 * which survives `adb uninstall` and reinstalls.
 *
 * Also automatically migrates existing settings from the legacy package
 * (com.immichframe.immichframe) if found.
 */
object SettingsBackupHelper {
    private const val TAG = "SettingsBackupHelper"
    private const val BACKUP_FILENAME = "immichframe_settings.json"

    // Strong reference to prevent garbage collection of the listener
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun getBackupFiles(context: Context): List<File> {
        val files = mutableListOf<File>()
        try {
            files.add(File("/sdcard/$BACKUP_FILENAME"))
            files.add(File(Environment.getExternalStorageDirectory(), BACKUP_FILENAME))
            files.add(File(Environment.getExternalStorageDirectory(), "ImmichFrame/$BACKUP_FILENAME"))
            context.getExternalFilesDir(null)?.let {
                files.add(File(it, BACKUP_FILENAME))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving backup paths: ${e.message}")
        }
        return files.distinctBy { it.absolutePath }
    }

    /**
     * Initializes persistence: restores settings if SharedPreferences is currently empty,
     * registers auto-backup on change, and ensures an initial backup is written.
     */
    fun init(context: Context) {
        try {
            val restored = restoreIfNeeded(context)
            if (!restored) {
                // If we already have preferences, make sure the backup file is up to date
                backup(context)
            }
            registerAutoBackup(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SettingsBackupHelper: ${e.message}", e)
        }
    }

    /**
     * Registers a listener to automatically back up settings to /sdcard whenever any preference changes.
     */
    fun registerAutoBackup(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        if (preferenceChangeListener == null) {
            preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                backup(context.applicationContext)
            }
            prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
            Log.d(TAG, "Auto-backup listener registered")
        }
    }

    /**
     * Backs up all current SharedPreferences to external persistent storage.
     */
    fun backup(context: Context) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val allEntries = prefs.all
            if (allEntries.isEmpty()) return

            val json = JSONObject()
            for ((key, value) in allEntries) {
                when (value) {
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is Float -> json.put(key, value.toDouble())
                    is String -> json.put(key, value)
                    is Set<*> -> {
                        val array = JSONArray()
                        value.forEach { if (it is String) array.put(it) }
                        json.put(key, array)
                    }
                }
            }

            val targets = getBackupFiles(context)
            for (target in targets) {
                try {
                    target.parentFile?.mkdirs()
                    target.writeText(json.toString(2))
                    Log.i(TAG, "Successfully backed up ${allEntries.size} settings to ${target.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write backup to ${target.absolutePath}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to back up settings: ${e.message}", e)
        }
    }

    /**
     * Restores settings from persistent backup or legacy package if current preferences are empty.
     * Returns true if settings were restored, false otherwise.
     */
    fun restoreIfNeeded(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val currentUrl = prefs.getString("immich_server_url", "")
            ?.takeIf { it.isNotBlank() }
            ?: prefs.getString("webview_url", "") ?: ""

        // If server URL is already present, settings are intact
        if (currentUrl.isNotBlank()) {
            return false
        }

        // 1. Try restoring from persistent JSON backup files
        for (file in getBackupFiles(context)) {
            if (file.exists() && file.canRead()) {
                val success = restoreFromJsonFile(file, prefs)
                if (success) {
                    Log.i(TAG, "Restored preferences from ${file.absolutePath}")
                    return true
                }
            }
        }

        // 2. Try migrating from legacy com.immichframe.immichframe preferences
        val legacyMigrated = migrateLegacyPreferences(context, prefs)
        if (legacyMigrated) {
            Log.i(TAG, "Successfully migrated preferences from legacy package com.immichframe.immichframe")
            backup(context)
            return true
        }

        return false
    }

    private fun restoreFromJsonFile(file: File, prefs: SharedPreferences): Boolean {
        return try {
            val content = file.readText().trim()
            if (content.isBlank()) return false
            val json = JSONObject(content)
            val editor = prefs.edit()
            val keys = json.keys()
            var count = 0
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is String -> editor.putString(key, value)
                    is JSONArray -> {
                        val set = mutableSetOf<String>()
                        for (i in 0 until value.length()) {
                            set.add(value.getString(i))
                        }
                        editor.putStringSet(key, set)
                    }
                }
                count++
            }
            editor.apply()
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading backup file ${file.absolutePath}: ${e.message}", e)
            false
        }
    }

    /**
     * Attempts to read settings from the previous package (com.immichframe.immichframe).
     */
    private fun migrateLegacyPreferences(context: Context, prefs: SharedPreferences): Boolean {
        try {
            // First check if direct package context can read it
            try {
                val legacyContext = context.createPackageContext(
                    "com.immichframe.immichframe",
                    Context.CONTEXT_IGNORE_SECURITY
                )
                val legacyPrefs = PreferenceManager.getDefaultSharedPreferences(legacyContext)
                val allLegacy = legacyPrefs.all
                if (allLegacy.isNotEmpty()) {
                    applyMapToPrefs(allLegacy, prefs)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "createPackageContext for legacy app not accessible directly: ${e.message}")
            }

            // Fallback: Check if file can be read via su on rooted frames
            val legacyPrefPath = "/data/data/com.immichframe.immichframe/shared_prefs/com.immichframe.immichframe_preferences.xml"
            val legacyXml = readFileWithSu(legacyPrefPath)
            if (legacyXml != null && legacyXml.isNotBlank()) {
                val parsed = parseLegacyXml(legacyXml)
                if (parsed.isNotEmpty()) {
                    applyMapToPrefs(parsed, prefs)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration check failed: ${e.message}")
        }
        return false
    }

    private fun readFileWithSu(path: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLegacyXml(xml: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (line in xml.lines()) {
            val trimmed = line.trim()
            val nameMatch = Regex("name=\"([^\"]+)\"").find(trimmed) ?: continue
            val key = nameMatch.groupValues[1]

            when {
                trimmed.startsWith("<string") -> {
                    val contentMatch = Regex(">([^<]*)<").find(trimmed)
                    val value = contentMatch?.groupValues?.get(1) ?: ""
                    result[key] = value
                }
                trimmed.startsWith("<boolean") -> {
                    val valMatch = Regex("value=\"(true|false)\"").find(trimmed)
                    if (valMatch != null) {
                        result[key] = valMatch.groupValues[1] == "true"
                    }
                }
                trimmed.startsWith("<int") -> {
                    val valMatch = Regex("value=\"([0-9-]+)\"").find(trimmed)
                    valMatch?.groupValues?.get(1)?.toIntOrNull()?.let { result[key] = it }
                }
                trimmed.startsWith("<float") -> {
                    val valMatch = Regex("value=\"([0-9.-]+)\"").find(trimmed)
                    valMatch?.groupValues?.get(1)?.toFloatOrNull()?.let { result[key] = it }
                }
                trimmed.startsWith("<long") -> {
                    val valMatch = Regex("value=\"([0-9-]+)\"").find(trimmed)
                    valMatch?.groupValues?.get(1)?.toLongOrNull()?.let { result[key] = it }
                }
            }
        }
        return result
    }

    private fun applyMapToPrefs(map: Map<String, *>, prefs: SharedPreferences) {
        val editor = prefs.edit()
        for ((key, value) in map) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    val set = mutableSetOf<String>()
                    value.forEach { if (it is String) set.add(it) }
                    editor.putStringSet(key, set)
                }
            }
        }
        // If webview_url is present but immich_server_url is not, initialize immich_server_url
        val webviewUrl = map["webview_url"] as? String
        val immichUrl = map["immich_server_url"] as? String
        if (immichUrl.isNullOrBlank() && !webviewUrl.isNullOrBlank()) {
            editor.putString("immich_server_url", webviewUrl)
        }
        val authSecret = map["authSecret"] as? String
        val apiKey = map["immich_api_key"] as? String
        if (apiKey.isNullOrBlank() && !authSecret.isNullOrBlank()) {
            editor.putString("immich_api_key", authSecret)
        }
        editor.apply()
    }
}
