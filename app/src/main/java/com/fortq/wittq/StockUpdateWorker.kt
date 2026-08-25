package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

            // Keep any last-known local widget state visible even if the newest
            // Yahoo refresh failed. The error widget itself safely ensures another
            // non-cancelling bootstrap attempt after the throttle window.
            StockWidget().updateAll(context)
            AGTQWidget().updateAll(context)
            SnowWidget().updateAll(context)
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
            "$label=${stats.rowCount}$cadence"
        }
    }

    companion object {
        private val marketSyncMutex = Mutex()

        fun enqueue(context: Context) {
            AutoRefreshScheduler.scheduleStock(context, append = false)
        }
    }
}
