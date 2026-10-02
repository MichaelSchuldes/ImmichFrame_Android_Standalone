package com.immichframe.standalone

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class WidgetConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(R.layout.widget_config)

        val backgroundChoiceGroup: RadioGroup = findViewById(R.id.background_choice)
        val saveButton: Button = findViewById(R.id.save_button)

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val backgroundType = prefs.getString("widgetBackground$appWidgetId", "square")

        val selectedRadioButtonId = when (backgroundType) {
            "square" -> R.id.square_background
            "round" -> R.id.round_background
            "squircle" -> R.id.squircle_background
            else -> R.id.square_background
        }
        backgroundChoiceGroup.check(selectedRadioButtonId)

        saveButton.setOnClickListener {
            val selectedBackgroundType = when (backgroundChoiceGroup.checkedRadioButtonId) {
                R.id.square_background -> "square"
                R.id.round_background -> "round"
                R.id.squircle_background -> "squircle"
                else -> "square"
            }

            prefs.edit().putString("widgetBackground$appWidgetId", selectedBackgroundType).apply()

            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)

            WidgetProvider().updateBackground(this, appWidgetId)

            finish()
        }
    }
}
