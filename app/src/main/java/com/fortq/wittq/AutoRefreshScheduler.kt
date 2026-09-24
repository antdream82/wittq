package com.fortq.wittq

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class RefreshDiagnostics(
    val lastNetworkFetchAtMillis: Long,
    val lastAlarmPlannedAtMillis: Long,
    val lastAlarmFiredAtMillis: Long,
    val lastWorkerStartAtMillis: Long,
    val lastCalcCompleteAtMillis: Long,
    val lastWidgetUpdateAtMillis: Long,
    val nextPlannedAtMillis: Long,
    val lastWorkKind: String,
    val lastTriggerSource: String,
    val schedulerMode: String,
    val exactAlarmAllowed: Boolean,
)

object AutoRefreshScheduler {
    private const val STOCK_FALLBACK_WORK_NAME = "stock_auto_refresh_v5"
    private const val STOCK_IMMEDIATE_WORK_NAME = "stock_immediate_refresh_v5"
    private const val STOCK_ALARM_WORK_NAME = "stock_alarm_refresh_v5"

    private const val OLD_V4_STOCK_WORK_NAME = "stock_auto_refresh_v4"
    private const val OLD_V4_IMMEDIATE_WORK_NAME = "stock_immediate_refresh_v4"
    private const val OLD_V4_REPAIR_WORK_NAME = "stock_canonical_repair_retry_v4"
    private const val OLD_SHARED_STOCK_WORK_NAME = "stock_auto_refresh"
    private const val OLD_SHARED_IMMEDIATE_WORK_NAME = "stock_immediate_refresh_v2"
    private const val OLD_REPAIR_WORK_NAME = "stock_canonical_repair_retry_v3"
    private const val OLD_AGTQ_WORK_NAME = "agtq_auto_refresh"
    private const val OLD_SNOW_WORK_NAME = "snow_auto_refresh"
    private const val LEGACY_STOCK_WORK_NAME = "stock_update_work"
    private const val LEGACY_AGTQ_WORK_NAME = "agtq_update_work"
    private const val LEGACY_SNOW_WORK_NAME = "snow_update_work"
    private const val SHARED_MARKET_MIGRATION_FLAG = "market_sync_migrated_v5"

    private const val SCHEDULER_PREFS = "MarketSyncScheduler"
    private const val KEY_LAST_IMMEDIATE_REQUEST_MS = "last_immediate_request_ms"
    private const val KEY_LAST_ALARM_PLANNED_MS = "last_alarm_planned_ms"
    private const val KEY_LAST_ALARM_FIRED_MS = "last_alarm_fired_ms"
    private const val KEY_LAST_WORKER_START_MS = "last_worker_start_ms"
    private const val KEY_LAST_CALC_COMPLETE_MS = "last_calc_complete_ms"
    private const val KEY_LAST_WIDGET_UPDATE_MS = "last_widget_update_ms"
    private const val KEY_NEXT_PLANNED_MS = "next_planned_ms"
    private const val KEY_LAST_WORK_KIND = "last_work_kind"
    private const val KEY_LAST_TRIGGER_SOURCE = "last_trigger_source"
    private const val KEY_SCHEDULER_MODE = "scheduler_mode"
    private const val ERROR_BOOTSTRAP_ENSURE_INTERVAL_MS = 5 * 60 * 1000L
    private const val MARKET_ALARM_REQUEST_CODE = 17017

    internal const val ACTION_MARKET_REFRESH_ALARM =
        "com.fortq.wittq.action.MARKET_REFRESH_ALARM"
    internal const val KEY_WORK_KIND = "refresh_work_kind"
    internal const val KEY_TRIGGER_SOURCE = "refresh_trigger_source"
    internal const val WORK_KIND_AUTO = "AUTO"
    internal const val WORK_KIND_MANUAL = "MANUAL"
    internal const val WORK_KIND_REPAIR = "REPAIR"
    internal const val TRIGGER_EXACT_ALARM = "EXACT_ALARM"
    internal const val TRIGGER_WORK_FALLBACK = "WORK_FALLBACK"
    internal const val TRIGGER_MANUAL = "MANUAL"
    internal const val TRIGGER_REPAIR = "REPAIR"
    internal const val MODE_EXACT_ALARM = "EXACT"
    internal const val MODE_WORK_FALLBACK = "FALLBACK"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun schedulerPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)

    private fun migrateToExactWakeSchedule(context: Context) {
        val prefs = context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(SHARED_MARKET_MIGRATION_FLAG, false)) return

        WorkManager.getInstance(context).apply {
            cancelUniqueWork(OLD_V4_STOCK_WORK_NAME)
            cancelUniqueWork(OLD_V4_IMMEDIATE_WORK_NAME)
            cancelUniqueWork(OLD_V4_REPAIR_WORK_NAME)
            cancelUniqueWork(OLD_SHARED_STOCK_WORK_NAME)
            cancelUniqueWork(OLD_SHARED_IMMEDIATE_WORK_NAME)
            cancelUniqueWork(OLD_REPAIR_WORK_NAME)
            cancelUniqueWork(OLD_AGTQ_WORK_NAME)
            cancelUniqueWork(OLD_SNOW_WORK_NAME)
            cancelUniqueWork(LEGACY_STOCK_WORK_NAME)
            cancelUniqueWork(LEGACY_AGTQ_WORK_NAME)
            cancelUniqueWork(LEGACY_SNOW_WORK_NAME)
        }

        prefs.edit { putBoolean(SHARED_MARKET_MIGRATION_FLAG, true) }
        Log.d("AUTO_REFRESH", "Migrated market scheduler to exact-wake v5")
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    fun canUseExactAlarms(context: Context): Boolean {
        val manager = alarmManager(context)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    private fun marketAlarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            MARKET_ALARM_REQUEST_CODE,
            Intent(context, MarketRefreshAlarmReceiver::class.java).apply {
                action = ACTION_MARKET_REFRESH_ALARM
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelMarketAlarm(context: Context) {
        alarmManager(context).cancel(marketAlarmIntent(context))
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueueFallback(
        context: Context,
        delayMs: Long,
        append: Boolean,
    ) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .setInputData(
                workDataOf(
                    KEY_WORK_KIND to WORK_KIND_AUTO,
                    KEY_TRIGGER_SOURCE to TRIGGER_WORK_FALLBACK,
                ),
            )
            .build()

        val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(
            STOCK_FALLBACK_WORK_NAME,
            policy,
            request,
        )
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueueImmediate(
        context: Context,
        workName: String,
        workKind: String,
        triggerSource: String,
    ) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setConstraints(constraints())
            .setInputData(
                workDataOf(
                    KEY_WORK_KIND to workKind,
                    KEY_TRIGGER_SOURCE to triggerSource,
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleStock(
        context: Context,
        append: Boolean = false,
        closeFinalized: Boolean = false,
    ) {
        migrateToExactWakeSchedule(context)
        val appContext = context.applicationContext
        val now = ZonedDateTime.now(MarketRefreshSchedule.newYorkZone)
        val next = MarketRefreshSchedule.nextSlot(now, closeFinalized)
        val nextMillis = next.toInstant().toEpochMilli()
        val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
        val prefs = schedulerPrefs(appContext)

        prefs.edit {
            putLong(KEY_NEXT_PLANNED_MS, nextMillis)
        }

        if (canUseExactAlarms(appContext)) {
            try {
                if (!append) {
                    WorkManager.getInstance(appContext).cancelUniqueWork(STOCK_FALLBACK_WORK_NAME)
                }
                val pending = marketAlarmIntent(appContext)
                alarmManager(appContext).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextMillis,
                    pending,
                )
                prefs.edit { putString(KEY_SCHEDULER_MODE, MODE_EXACT_ALARM) }
                Log.d("AUTO_REFRESH", "Exact wake scheduled for $next")
                return
            } catch (e: SecurityException) {
                Log.w("AUTO_REFRESH", "Exact alarm denied despite capability check; falling back", e)
            }
        }

        cancelMarketAlarm(appContext)
        prefs.edit { putString(KEY_SCHEDULER_MODE, MODE_WORK_FALLBACK) }
        enqueueFallback<StockUpdateWorker>(appContext, delayMs, append)
        Log.w("AUTO_REFRESH", "WorkManager fallback scheduled for $next")
    }

    fun enqueueAutoFromAlarm(context: Context) {
        enqueueImmediate<StockUpdateWorker>(
            context.applicationContext,
            STOCK_ALARM_WORK_NAME,
            WORK_KIND_AUTO,
            TRIGGER_EXACT_ALARM,
        )
    }

    fun refreshStockNow(context: Context) {
        migrateToExactWakeSchedule(context)
        val appContext = context.applicationContext
        schedulerPrefs(appContext).edit {
            putLong(KEY_LAST_IMMEDIATE_REQUEST_MS, System.currentTimeMillis())
        }

        scheduleStock(appContext, append = false)
        enqueueImmediate<StockUpdateWorker>(
            appContext,
            STOCK_IMMEDIATE_WORK_NAME,
            WORK_KIND_MANUAL,
            TRIGGER_MANUAL,
        )
    }

    fun ensureStockNow(context: Context) {
        val prefs = schedulerPrefs(context)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_IMMEDIATE_REQUEST_MS, 0L)
        if (now - last < ERROR_BOOTSTRAP_ENSURE_INTERVAL_MS) return
        refreshStockNow(context)
    }

    internal fun recordAlarmFired(context: Context) {
        val prefs = schedulerPrefs(context)
        val now = System.currentTimeMillis()
        prefs.edit {
            putLong(KEY_LAST_ALARM_PLANNED_MS, prefs.getLong(KEY_NEXT_PLANNED_MS, 0L))
            putLong(KEY_LAST_ALARM_FIRED_MS, now)
            putString(KEY_LAST_TRIGGER_SOURCE, TRIGGER_EXACT_ALARM)
        }
    }

    internal fun recordWorkerStart(
        context: Context,
        workKind: String,
        triggerSource: String,
    ) {
        schedulerPrefs(context).edit {
            putLong(KEY_LAST_WORKER_START_MS, System.currentTimeMillis())
            putString(KEY_LAST_WORK_KIND, workKind)
            putString(KEY_LAST_TRIGGER_SOURCE, triggerSource)
        }
    }

    internal fun recordCalcComplete(context: Context) {
        schedulerPrefs(context).edit {
            putLong(KEY_LAST_CALC_COMPLETE_MS, System.currentTimeMillis())
        }
    }

    internal fun recordWidgetUpdate(context: Context) {
        schedulerPrefs(context).edit {
            putLong(KEY_LAST_WIDGET_UPDATE_MS, System.currentTimeMillis())
        }
    }

    fun diagnostics(context: Context): RefreshDiagnostics {
        val prefs = schedulerPrefs(context)
        return RefreshDiagnostics(
            lastNetworkFetchAtMillis = StockApiEngine.getLastNetworkFetchAt(context),
            lastAlarmPlannedAtMillis = prefs.getLong(KEY_LAST_ALARM_PLANNED_MS, 0L),
            lastAlarmFiredAtMillis = prefs.getLong(KEY_LAST_ALARM_FIRED_MS, 0L),
            lastWorkerStartAtMillis = prefs.getLong(KEY_LAST_WORKER_START_MS, 0L),
            lastCalcCompleteAtMillis = prefs.getLong(KEY_LAST_CALC_COMPLETE_MS, 0L),
            lastWidgetUpdateAtMillis = prefs.getLong(KEY_LAST_WIDGET_UPDATE_MS, 0L),
            nextPlannedAtMillis = prefs.getLong(KEY_NEXT_PLANNED_MS, 0L),
            lastWorkKind = prefs.getString(KEY_LAST_WORK_KIND, "") ?: "",
            lastTriggerSource = prefs.getString(KEY_LAST_TRIGGER_SOURCE, "") ?: "",
            schedulerMode = prefs.getString(KEY_SCHEDULER_MODE, "") ?: "",
            exactAlarmAllowed = canUseExactAlarms(context),
        )
    }

    fun scheduleAgtq(context: Context, append: Boolean = false) =
        scheduleStock(context, append)

    fun refreshAgtqNow(context: Context) =
        refreshStockNow(context)

    fun scheduleSnow(context: Context, append: Boolean = false) =
        scheduleStock(context, append)

    fun refreshSnowNow(context: Context) =
        refreshStockNow(context)
}
