package com.immichframe.immichframe

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormatSymbols
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat() {
    private lateinit var immichManager: ImmichManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_view, rootKey)
        immichManager = ImmichManager(requireContext())

        val chkActiveTimes = findPreference<SwitchPreferenceCompat>("activeTimes")
        val editActiveSchedule = findPreference<Preference>("active_schedule_edit")
        val adminActiveSchedule = findPreference<Preference>("active_schedule_admin")

        // Obfuscate API key in settings
        val apiKeyPref = findPreference<EditTextPreference>("immich_api_key")
        apiKeyPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // Test connection button
        val btnTest = findPreference<Preference>("test_connection")
        btnTest?.setOnPreferenceClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val url = prefs.getString("immich_server_url", "")?.trim() ?: ""
            val key = prefs.getString("immich_api_key", "")?.trim() ?: ""

            if (url.isBlank()) {
                Toast.makeText(requireContext(), "Please enter an Immich Server URL first.", Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }

            Toast.makeText(requireContext(), "Testing connection to Immich...", Toast.LENGTH_SHORT).show()
            immichManager.testConnection(url, key) { success, message ->
                activity?.runOnUiThread {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(if (success) "Connection Succeeded" else "Connection Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            true
        }

        val activeTimes = chkActiveTimes?.isChecked ?: false
        editActiveSchedule?.isVisible = activeTimes
        adminActiveSchedule?.isVisible = activeTimes
        updateAdminSummary(adminActiveSchedule)
        updateScheduleSummary(editActiveSchedule)

        chkActiveTimes?.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue as Boolean
            editActiveSchedule?.isVisible = value
            adminActiveSchedule?.isVisible = value
            true
        }

        editActiveSchedule?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ActiveScheduleActivity::class.java))
            true
        }

        adminActiveSchedule?.setOnPreferenceClickListener {
            val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = FrameDeviceAdminReceiver.componentName(requireContext())
            if (dpm.isAdminActive(component)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Screen-Off Permission")
                    .setMessage("ImmichFrame can already turn the screen off. Disable this permission?")
                    .setPositiveButton("Disable") { _, _ ->
                        dpm.removeActiveAdmin(component)
                        Handler(Looper.getMainLooper()).postDelayed({
                            updateAdminSummary(adminActiveSchedule)
                        }, 500)
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_description),
                    )
                }
                startActivity(intent)
            }
            true
        }

        val chkSettingsLock = findPreference<SwitchPreferenceCompat>("settingsLock")
        chkSettingsLock?.setOnPreferenceChangeListener { _, newValue ->
            val enabling = newValue as Boolean
            if (enabling) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Confirm Action")
                    .setMessage(
                        "This will disable access to the settings screen, the only way back is via RPC commands (or uninstall/reinstall).\n" +
                                "Are you absolutely sure?"
                    )
                    .setPositiveButton("Yes", null)
                    .setNegativeButton("No") { dialog, _ ->
                        chkSettingsLock.isChecked = false
                        dialog.dismiss()
                    }
                    .show()
            }
            true
        }

        val btnClose = findPreference<Preference>("closeSettings")
        btnClose?.setOnPreferenceClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val url = prefs.getString("immich_server_url", "")
                ?.takeIf { it.isNotBlank() }
                ?: prefs.getString("webview_url", "") ?: ""

            if (url.isBlank()) {
                Toast.makeText(requireContext(), "Please enter an Immich Server URL.", Toast.LENGTH_LONG).show()
                false
            } else {
                activity?.setResult(Activity.RESULT_OK)
                activity?.finish()
                true
            }
        }

        val btnAndroidSettings = findPreference<Preference>("androidSettings")
        btnAndroidSettings?.setOnPreferenceClickListener {
            val context = requireContext()

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                Toast.makeText(context, "Returning to app in 2 minutes…", Toast.LENGTH_LONG).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    val returnIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    returnIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(returnIntent)
                }, 2 * 60 * 1000)
            }

            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateAdminSummary(findPreference("active_schedule_admin"))
        updateScheduleSummary(findPreference("active_schedule_edit"))
    }

    private fun updateScheduleSummary(preference: Preference?) {
        val pref = preference ?: return
        val json = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("activeSchedule", null)
        pref.summary = summarizeSchedule(json)
    }

    private fun summarizeSchedule(json: String?): String {
        val schedule = Helpers.parseActiveSchedule(json)
        if (schedule.rules.isEmpty()) return getString(R.string.active_schedule_always)
        return schedule.rules.joinToString("\n") { rule ->
            val days = summarizeDays(rule.days)
            val times = rule.ranges.joinToString(", ") { "${it.start}–${it.end}" }
            if (days.isEmpty()) times else "$days: $times"
        }
    }

    private fun summarizeDays(days: Set<Int>): String {
        val order = intArrayOf(2, 3, 4, 5, 6, 7, 1)
        val indices = order.indices.filter { days.contains(order[it]) }
        if (indices.isEmpty()) return ""
        val names = DateFormatSymbols(Locale.getDefault()).shortWeekdays
        fun name(dayInt: Int) = names.getOrNull(dayInt)?.takeIf { it.isNotBlank() } ?: dayInt.toString()
        val parts = mutableListOf<String>()
        var start = 0
        while (start < indices.size) {
            var end = start
            while (end + 1 < indices.size && indices[end + 1] == indices[end] + 1) end++
            if (end - start >= 2) {
                parts.add("${name(order[indices[start]])}–${name(order[indices[end]])}")
            } else {
                for (k in start..end) parts.add(name(order[indices[k]]))
            }
            start = end + 1
        }
        return parts.joinToString(", ")
    }

    private fun updateAdminSummary(preference: Preference?) {
        val pref = preference ?: return
        val dpm = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enabled = dpm.isAdminActive(FrameDeviceAdminReceiver.componentName(requireContext()))
        pref.summary = if (enabled) {
            "Enabled — the frame can turn off the screen and sleep the device. Tap to disable."
        } else {
            "Allow the frame to turn off the screen and sleep the device during inactive hours"
        }
    }
}