package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*

class SnowUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // 위젯의 provideGlance를 다시 실행시켜서 데이터를 새로 가져옵니다.
            SnowWidget().updateAll(context)
            Result.success()
        } catch (e: Exception) {
            Log.e("WITTQ_WORKER", "Update failed: ${e.message}", e)
            Result.success()
        } finally {
            AutoRefreshScheduler.scheduleSnow(context, append = true)
        }
    }

    companion object {
        private const val WORK_NAME = "snow_update_work"

        fun enqueue(context: Context) {
            AutoRefreshScheduler.scheduleSnow(context, append = false)
        }
    }
}
