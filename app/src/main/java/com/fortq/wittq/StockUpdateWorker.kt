package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class StockUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = marketSyncMutex.withLock {
        val workKind = inputData.getString(AutoRefreshScheduler.KEY_WORK_KIND)
            ?: AutoRefreshScheduler.WORK_KIND_MANUAL
        AutoRefreshScheduler.recordWorkerStart(context, workKind)
        var snapshot: SoftRunner17dAppSnapshot? = null

        try {
            val loaded = SoftRunner17dDataSource.load(context)
            snapshot = loaded
            SoftRunner17dNotifier.process(context, loaded)
            SoftRunner17dSnapshotStore.save(context, loaded)

            StockWidget().updateAll(context)
            AGTQWidget().updateAll(context)
            SnowWidget().updateAll(context)
            AutoRefreshScheduler.recordWidgetUpdate(context)
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

            StockWidget().updateAll(context)
            AGTQWidget().updateAll(context)
            SnowWidget().updateAll(context)
            AutoRefreshScheduler.recordWidgetUpdate(context)

            scheduleRepairRetry(context)
            Result.success()
        } finally {
            if (workKind == AutoRefreshScheduler.WORK_KIND_AUTO) {
                val todayNy = LocalDate.now(MarketRefreshSchedule.newYorkZone)
                AutoRefreshScheduler.scheduleStock(
                    context = context,
                    append = true,
                    closeFinalized = snapshot?.officialDate == todayNy,
                )
            }
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
            .setInputData(
                workDataOf(
                    AutoRefreshScheduler.KEY_WORK_KIND to AutoRefreshScheduler.WORK_KIND_REPAIR,
                )
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            REPAIR_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Log.d("WITTQ_WORKER", "Scheduled canonical repair retry in $REPAIR_RETRY_MINUTES min")
    }

    companion object {
        private const val REPAIR_WORK_NAME = "stock_canonical_repair_retry_v4"
        private const val REPAIR_RETRY_MINUTES = 5L
        private val marketSyncMutex = Mutex()

        fun enqueue(context: Context) {
            AutoRefreshScheduler.scheduleStock(context, append = false)
        }
    }
}
