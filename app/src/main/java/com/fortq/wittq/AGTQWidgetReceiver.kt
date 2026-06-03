package com.fortq.wittq

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class AGTQWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AGTQWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AutoRefreshScheduler.refreshAgtqNow(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        AutoRefreshScheduler.refreshAgtqNow(context)
    }
}
