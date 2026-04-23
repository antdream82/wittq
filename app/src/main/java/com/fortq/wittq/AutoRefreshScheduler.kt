package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object AutoRefreshScheduler {
    private const val STOCK_WORK_NAME = "stock_auto_refresh"
    private const val AGTQ_WORK_NAME = "agtq_auto_refresh"
    private const val SNOW_WORK_NAME = "snow_auto_refresh"
    private const val LEGACY_MIGRATION_FLAG = "auto_refresh_migrated_v2"
    private const val LEGACY_STOCK_WORK_NAME = "stock_update_work"
    private const val LEGACY_AGTQ_WORK_NAME = "agtq_update_work"
    private const val LEGACY_SNOW_WORK_NAME = "snow_update_work"
    private val newYorkZone: ZoneId = ZoneId.of("America/New_York")

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun migrateLegacySchedules(context: Context) {
        val prefs = context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(LEGACY_MIGRATION_FLAG, false)) return

        WorkManager.getInstance(context).apply {
            cancelUniqueWork(LEGACY_STOCK_WORK_NAME)
            cancelUniqueWork(LEGACY_AGTQ_WORK_NAME)
            cancelUniqueWork(LEGACY_SNOW_WORK_NAME)
        }

        prefs.edit().putBoolean(LEGACY_MIGRATION_FLAG, true).apply()
        Log.d("AUTO_REFRESH", "Migrated legacy periodic schedules to one-time chains")
    }

    private fun nextDelayMillis(now: ZonedDateTime = ZonedDateTime.now(newYorkZone)): Long {
        val time = now.toLocalTime()
        val weekday = now.dayOfWeek != DayOfWeek.SATURDAY && now.dayOfWeek != DayOfWeek.SUNDAY
        val open = LocalTime.of(9, 30)
        val close = LocalTime.of(16, 0)
        val graceEnd = LocalTime.of(17, 0)

        return when {
            !weekday -> {
                val nextMonday = now.toLocalDate().plusDays(
                    when (now.dayOfWeek) {
                        DayOfWeek.SATURDAY -> 2
                        DayOfWeek.SUNDAY -> 1
                        else -> 0
                    }
                )
                val nextOpen = ZonedDateTime.of(nextMonday, open, newYorkZone)
                Duration.between(now, nextOpen).toMillis().coerceAtLeast(0L)
            }
            time < open -> Duration.ofHours(4).toMillis()
            time < close -> Duration.ofHours(1).toMillis()
            time < graceEnd -> Duration.ofMinutes(20).toMillis()
            else -> Duration.ofHours(4).toMillis()
        }
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueueOneTime(
        context: Context,
        workName: String,
        append: Boolean,
        delayMs: Long
    ) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .build()

        val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(workName, policy, request)
        Log.d("AUTO_REFRESH", "Scheduled $workName in ${delayMs / 60000} min")
    }

    fun scheduleStock(context: Context, append: Boolean = false) {
        migrateLegacySchedules(context)
        enqueueOneTime<StockUpdateWorker>(context, STOCK_WORK_NAME, append, nextDelayMillis())
    }

    fun scheduleAgtq(context: Context, append: Boolean = false) {
        migrateLegacySchedules(context)
        enqueueOneTime<AGTQUpdateWorker>(context, AGTQ_WORK_NAME, append, nextDelayMillis())
    }

    fun scheduleSnow(context: Context, append: Boolean = false) {
        migrateLegacySchedules(context)
        enqueueOneTime<SnowUpdateWorker>(context, SNOW_WORK_NAME, append, nextDelayMillis())
    }
}
