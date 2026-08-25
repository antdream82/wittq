package com.fortq.wittq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("WITTQ_UPDATE", "Package replaced; scheduling immediate 17d refresh")
            AutoRefreshScheduler.refreshStockNow(context.applicationContext)
        }
    }
}
