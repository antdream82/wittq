package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class RefreshDiagnostics(
    val lastNetworkFetchAtMillis: Long,
    val lastWorkerStartAtMillis: Long,
    val lastWidgetUpdateAtMillis: Long,
    val nextPlannedAtMillis: Long,
    val lastWorkKind: String,
)

object AutoRefreshScheduler {
    private const val STOCK_WORK_NAME = "stock_auto_refresh_v4"
    private const val STOCK_IMMEDIATE_WORK_NAME = "stock_immediate_refresh_v4"

    private const val OLD_SHARED_STOCK_WORK_NAME = "stock_auto_refresh"
    private const val OLD_SHARED_IMMEDIATE_WORK_NAME = "stock_immediate_refresh_v2"
    private const val OLD_REPAIR_WORK_NAME = "stock_canonical_repair_retry_v3"
    private const val OLD_AGTQ_WORK_NAME = "agtq_auto_refresh"
    private const val OLD_SNOW_WORK_NAME = "snow_auto_refresh"
    private const val LEGACY_STOCK_WORK_NAME = "stock_update_work"
    private const val LEGACY_AGTQ_WORK_NAME = "agtq_update_work"
    private const val LEGACY_SNOW_WORK_NAME = "snow_update_work"
    private const val SHARED_MARKET_MIGRATION_FLAG = "market_sync_migrated_v4"

    private const val SCHEDULER_PREFS = "MarketSyncScheduler"
    private const val KEY_LAST_IMMEDIATE_REQUEST_MS = "last_immediate_request_ms"
    private const val KEY_LAST_WORKER_START_MS = "last_worker_start_ms"
    private const val KEY_LAST_WIDGET_UPDATE_MS = "last_widget_update_ms"
    private const val KEY_NEXT_PLANNED_MS = "next_planned_ms"
    private const val KEY_LAST_WORK_KIND = "last_work_kind"
    private const val ERROR_BOOTSTRAP_ENSURE_INTERVAL_MS = 5 * 60 * 1000L

    internal const val KEY_WORK_KIND = "refresh_work_kind"
    internal const val WORK_KIND_AUTO = "AUTO"
    internal const val WORK_KIND_MANUAL = "MANUAL"
    internal const val WORK_KIND_REPAIR = "REPAIR"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun schedulerPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)

    private fun migrateToSharedMarketSchedule(context: Context) {
        val prefs = context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(SHARED_MARKET_MIGRATION_FLAG, false)) return

        WorkManager.getInstance(context).apply {
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
        Log.d("AUTO_REFRESH", "Migrated shared market scheduler to fixed-slot v4")
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueueOneTime(
        context: Context,
        workName: String,
        append: Boolean,
        delayMs: Long,
        workKind: String,
    ) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_WORK_KIND to workKind))
            .build()

        val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(workName, policy, request)
        Log.d("AUTO_REFRESH", "Scheduled $workName/$workKind in ${delayMs / 60000.0} min")
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueueImmediateKeep(
        context: Context,
        workName: String,
        workKind: String,
    ) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_WORK_KIND to workKind))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.d("AUTO_REFRESH", "Ensured immediate $workName/$workKind")
    }

    fun scheduleStock(
        context: Context,
        append: Boolean = false,
        closeFinalized: Boolean = false,
    ) {
        migrateToSharedMarketSchedule(context)
        val now = ZonedDateTime.now(MarketRefreshSchedule.newYorkZone)
        val next = MarketRefreshSchedule.nextSlot(now, closeFinalized)
        val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
        schedulerPrefs(context).edit {
            putLong(KEY_NEXT_PLANNED_MS, next.toInstant().toEpochMilli())
        }
        enqueueOneTime<StockUpdateWorker>(
            context = context,
            workName = STOCK_WORK_NAME,
            append = append,
            delayMs = delayMs,
            workKind = WORK_KIND_AUTO,
        )
    }

    fun refreshStockNow(context: Context) {
        migrateToSharedMarketSchedule(context)
        schedulerPrefs(context).edit {
            putLong(KEY_LAST_IMMEDIATE_REQUEST_MS, System.currentTimeMillis())
        }

        scheduleStock(context, append = false)
        enqueueImmediateKeep<StockUpdateWorker>(
            context,
            STOCK_IMMEDIATE_WORK_NAME,
            WORK_KIND_MANUAL,
        )
    }

    fun ensureStockNow(context: Context) {
        val prefs = schedulerPrefs(context)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_IMMEDIATE_REQUEST_MS, 0L)
        if (now - last < ERROR_BOOTSTRAP_ENSURE_INTERVAL_MS) return
        refreshStockNow(context)
    }

    internal fun recordWorkerStart(context: Context, workKind: String) {
        schedulerPrefs(context).edit {
            putLong(KEY_LAST_WORKER_START_MS, System.currentTimeMillis())
            putString(KEY_LAST_WORK_KIND, workKind)
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
            lastWorkerStartAtMillis = prefs.getLong(KEY_LAST_WORKER_START_MS, 0L),
            lastWidgetUpdateAtMillis = prefs.getLong(KEY_LAST_WIDGET_UPDATE_MS, 0L),
            nextPlannedAtMillis = prefs.getLong(KEY_NEXT_PLANNED_MS, 0L),
            lastWorkKind = prefs.getString(KEY_LAST_WORK_KIND, "") ?: "",
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
