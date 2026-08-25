package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.CancellationException

class StockUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // All Yahoo/network work lives here, outside the Glance rendering
            // lifecycle. The widget itself only reads the last durable snapshot.
            val snapshot = SoftRunner17dDataSource.load(context)
            SoftRunner17dNotifier.process(context, snapshot)
            SoftRunner17dSnapshotStore.save(context, snapshot)
            StockWidget().updateAll(context)
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager owns cancellation semantics. Never persist cancellation
            // as a Yahoo/data error.
            throw e
        } catch (e: Exception) {
            val detail = StockApiEngine.getLastError(context)
                ?: e.message
                ?: "17d refresh failed"
            SoftRunner17dSnapshotStore.setError(context, detail)
            Log.e("WITTQ_WORKER", "17d refresh failed: $detail", e)
            // Keep the last good snapshot visible. If none exists yet, the widget
            // shows an initialization/error message and the next scheduled refresh
            // retries the missing bootstrap symbol.
            StockWidget().updateAll(context)
            Result.success()
        } finally {
            AutoRefreshScheduler.scheduleStock(context, append = true)
        }
    }

    companion object {
        fun enqueue(context: Context) {
            AutoRefreshScheduler.scheduleStock(context, append = false)
        }
    }
}
