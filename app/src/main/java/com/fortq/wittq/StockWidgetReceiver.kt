package com.fortq.wittq

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class StockWidgetReceiver : GlanceAppWidgetReceiver() {
    // 시스템이 어떤 위젯을 띄울지 물어볼 때 우리 위젯을 알려줍니다.
    override val glanceAppWidget: GlanceAppWidget = StockWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AutoRefreshScheduler.refreshStockNow(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        AutoRefreshScheduler.refreshStockNow(context)
    }
}
