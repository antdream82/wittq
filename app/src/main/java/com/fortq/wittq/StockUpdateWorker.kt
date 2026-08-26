package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class StockUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = marketSyncMutex.withLock {
        try {
            // One shared market sync supplies 17d, AGTQ and Snow. Network work is
            // outside Glance; widgets reuse the durable SQLite history locally.
            val snapshot = SoftRunner17dDataSource.load(context)
            SoftRunner17dNotifier.process(context, snapshot)
            SoftRunner17dSnapshotStore.save(context, snapshot)

            StockWidget().updateAll(context)
            AGTQWidget().updateAll(context)
            SnowWidget().updateAll(context)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val primary = e.message
                ?: StockApiEngine.getLastError(context)
                ?: "shared market refresh failed"
            val yahoo = StockApiEngine.getLastError(context)
            val diagnostic = marketDiagnostics(context)
            val detail = buildString {
                append(primary)
                if (!yahoo.isNullOrBlank() && yahoo != primary) {
                    append(" | ")
                    append(yahoo)
                }
                append(" | ")
                append(diagnostic)
            }.take(180)

            SoftRunner17dSnapshotStore.setError(context, detail)
            Log.e("WITTQ_WORKER", "Shared market refresh failed: $detail", e)

            // Keep last-known values visible, but the snapshot reader now marks
            // them REPAIR/stale so a failed canonical rebuild cannot look current.
            StockWidget().updateAll(context)
            AGTQWidget().updateAll(context)
            SnowWidget().updateAll(context)

            scheduleRepairRetry(context)
            Result.success()
        } finally {
            AutoRefreshScheduler.scheduleStock(context, append = true)
        }
    }

    private fun marketDiagnostics(context: Context): String {
        val store = SoftRunner17dHistoryStore.get(context)
        return listOf(
            "TQ" to "TQQQ",
            "Q" to "QQQ",
            "S" to "SPY",
            "V" to "^VIX",
        ).joinToString(" ") { (label, symbol) ->
            val stats = store.stats(symbol)
            val cadence = if (store.hasDailyCadence(symbol)) "d" else "x"
            val canonical = if (store.isBootstrapComplete(symbol)) "✓" else "!"
            "$label=${stats.rowCount}$cadence$canonical"
        }
    }

    private fun scheduleRepairRetry(context: Context) {
        val request = OneTimeWorkRequestBuilder<StockUpdateWorker>()
            .setInitialDelay(REPAIR_RETRY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            REPAIR_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d("WITTQ_WORKER", "Scheduled canonical repair retry in $REPAIR_RETRY_MINUTES min")
    }

    companion object {
        private const val REPAIR_WORK_NAME = "stock_canonical_repair_retry_v3"
        private const val REPAIR_RETRY_MINUTES = 5L
        private val marketSyncMutex = Mutex()

        fun enqueue(context: Context) {
            AutoRefreshScheduler.scheduleStock(context, append = false)
        }
    }
}
