package com.fortq.wittq

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MarketRefreshAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            AutoRefreshScheduler.ACTION_MARKET_REFRESH_ALARM -> {
                AutoRefreshScheduler.recordAlarmFired(appContext)
                AutoRefreshScheduler.enqueueAutoFromAlarm(appContext)
                Log.d("AUTO_REFRESH", "Exact market alarm fired")
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                AutoRefreshScheduler.scheduleStock(appContext)
                Log.d("AUTO_REFRESH", "Market schedule restored after ${intent.action}")
            }
        }
    }
}
