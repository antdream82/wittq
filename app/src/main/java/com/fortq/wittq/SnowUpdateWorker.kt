package com.fortq.wittq

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*
import java.util.concurrent.TimeUnit

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
            // 에러 발생 시 재시도하지 않고 성공으로 처리 (무한 루프 방지)
            Result.success() // 실패 시 네트워크 상황 등에 따라 재시도
        }
    }

    companion object {
        private const val WORK_NAME = "snow_update_work"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // 인터넷 연결 시에만 작동
                .build()

            val request = PeriodicWorkRequestBuilder<SnowUpdateWorker>(
                60, TimeUnit.MINUTES // 시스템 최소 주기인 15분 설정
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // 이미 작동 중이면 새로 만들지 않고 유지
                request
            )
        }
    }
}