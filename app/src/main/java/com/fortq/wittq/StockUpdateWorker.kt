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
            val detail = StockApiEngine.getLastError(context)
                ?: e.message
                ?: "shared market refresh failed"
            SoftRunner17dSnapshotStore.setError(context, detail)
            Log.e("WITTQ_WORKER", "Shared market refresh failed: $detail", e)

            // Keep any last-known local widget state visible even if the newest
            // Yahoo refresh failed. The next scheduled run retries missing data.
            StockWidget().updateAll(context)
            AGTQWidget().updateAll(context)
            SnowWidget().updateAll(context)
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
