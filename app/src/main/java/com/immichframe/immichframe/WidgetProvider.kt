package com.immichframe.immichframe

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    fun updateBackground(context: Context, appWidgetId: Int) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val backgroundType =
                prefs.getString("widgetBackground$appWidgetId", "square") ?: "square"

            val views = RemoteViews(context.packageName, R.layout.widget_view)
            val backgroundRes = when (backgroundType) {
                "square" -> R.drawable.widget_background_square
                "round" -> R.drawable.widget_background_circle
                "squircle" -> R.drawable.widget_background_squircle
                else -> R.drawable.widget_background_square
            }
            views.setInt(R.id.widget_frame, "setBackgroundResource", backgroundRes)

            val intent = Intent(context, WidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(
                R.id.widgetImageView,
                pendingIntent
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)

            try {
                val manager = ImmichManager(context)
                val settings = manager.getSettings()

                if (settings.serverUrl.isNotBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val display = manager.getNextImage()
                        if (display != null) {
                            val reduced = Helpers.reduceBitmapQuality(display.bitmap, 1000)
                            withContext(Dispatchers.Main) {
                                views.setImageViewBitmap(
                                    R.id.widgetImageView,
                                    reduced
                                )
                                appWidgetManager.updateAppWidget(appWidgetId, views)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WidgetDebug", "Error updating widget: ${e.message}")
            }
        }
    }
}
