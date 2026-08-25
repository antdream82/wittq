package com.fortq.wittq

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object SoftRunner17dNotifier {
    private const val PREFS = "SoftRunner17dNotifications"
    private const val OFFICIAL_CHANNEL = "soft_runner_17d_official"
    private const val PREVIEW_CHANNEL = "soft_runner_17d_preview"
    private const val OFFICIAL_NOTIFICATION_ID = 1701
    private const val PREVIEW_NOTIFICATION_ID = 1702

    private const val KEY_OFFICIAL_BASELINE = "official_baseline"
    private const val KEY_OFFICIAL_DATE = "official_date"
    private const val KEY_PREVIEW_BASELINE = "preview_baseline"
    private const val KEY_PREVIEW_DATE = "preview_date"
    private const val KEY_HAS_OFFICIAL_BASELINE = "has_official_baseline"

    @Synchronized
    fun process(context: Context, snapshot: SoftRunner17dAppSnapshot) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val officialDate = snapshot.officialDate.toString()
        val priorOfficialDate = prefs.getString(KEY_OFFICIAL_DATE, null)
        val hasOfficialBaseline = prefs.getBoolean(KEY_HAS_OFFICIAL_BASELINE, false)
        val priorOfficial = prefs.getFloat(KEY_OFFICIAL_BASELINE, snapshot.official.finalTarget.toFloat()).toDouble()

        if (!hasOfficialBaseline) {
            prefs.edit(commit = true) {
                putBoolean(KEY_HAS_OFFICIAL_BASELINE, true)
                putFloat(KEY_OFFICIAL_BASELINE, snapshot.official.finalTarget.toFloat())
                putString(KEY_OFFICIAL_DATE, officialDate)
                putFloat(KEY_PREVIEW_BASELINE, snapshot.official.finalTarget.toFloat())
                putString(KEY_PREVIEW_DATE, snapshot.previewDate.toString())
            }
            return
        }

        if (priorOfficialDate != officialDate) {
            if (!sameRatio(priorOfficial, snapshot.official.finalTarget)) {
                notify(
                    context = context,
                    channelId = OFFICIAL_CHANNEL,
                    notificationId = OFFICIAL_NOTIFICATION_ID,
                    title = "17d 공식 포지션 변경",
                    shortText = "${ratioLabel(priorOfficial)} → ${ratioLabel(snapshot.official.finalTarget)}",
                    body = buildString {
                        append("${ratioLabel(priorOfficial)} → ${ratioLabel(snapshot.official.finalTarget)}")
                        append("\n${snapshot.official.reason.label}")
                        append("\n공식일: ${snapshot.officialDate}")
                    },
                    highPriority = true,
                )
            }
            prefs.edit(commit = true) {
                putFloat(KEY_OFFICIAL_BASELINE, snapshot.official.finalTarget.toFloat())
                putString(KEY_OFFICIAL_DATE, officialDate)
                putFloat(KEY_PREVIEW_BASELINE, snapshot.official.finalTarget.toFloat())
                putString(KEY_PREVIEW_DATE, snapshot.previewDate.toString())
            }
        }

        if (snapshot.previewDate > snapshot.officialDate) {
            val previewDate = snapshot.previewDate.toString()
            val storedPreviewDate = prefs.getString(KEY_PREVIEW_DATE, null)
            val priorPreview = if (storedPreviewDate == previewDate) {
                prefs.getFloat(KEY_PREVIEW_BASELINE, snapshot.official.finalTarget.toFloat()).toDouble()
            } else {
                snapshot.official.finalTarget
            }
            if (!sameRatio(priorPreview, snapshot.preview.finalTarget)) {
                notify(
                    context = context,
                    channelId = PREVIEW_CHANNEL,
                    notificationId = PREVIEW_NOTIFICATION_ID,
                    title = "17d 장중 예상 포지션",
                    shortText = "${ratioLabel(priorPreview)} → ${ratioLabel(snapshot.preview.finalTarget)}",
                    body = buildString {
                        append("PREVIEW · 장마감 전 변경 가능")
                        append("\n${ratioLabel(priorPreview)} → ${ratioLabel(snapshot.preview.finalTarget)}")
                        append("\n${snapshot.preview.reason.label}")
                        append("\nHardRisk=${snapshot.preview.hardRisk}, Contrarian=${snapshot.preview.contrarianActive}")
                    },
                    highPriority = true,
                )
            }
            prefs.edit(commit = true) {
                putFloat(KEY_PREVIEW_BASELINE, snapshot.preview.finalTarget.toFloat())
                putString(KEY_PREVIEW_DATE, previewDate)
            }
        }
    }

    private fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        shortText: String,
        body: String,
        highPriority: Boolean,
    ) {
        createChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                OFFICIAL_CHANNEL,
                "17d 공식 포지션",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "장후 확정된 17d 목표 포지션 변경" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PREVIEW_CHANNEL,
                "17d 장중 예상 포지션",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "장중 데이터 기반 17d 예상 포지션 변경" },
        )
    }

    private fun sameRatio(a: Double, b: Double): Boolean = abs(a - b) < 0.01

    fun ratioLabel(ratio: Double): String = when {
        sameRatio(ratio, 97.5) -> "TQQQ 97.5%"
        sameRatio(ratio, 100.0) -> "TQQQ 100%"
        sameRatio(ratio, 66.67) -> "TQQQ 66.67%"
        sameRatio(ratio, 20.0) -> "TQQQ Runner 20%"
        sameRatio(ratio, 2.5) -> "TQQQ 2.5%"
        sameRatio(ratio, 0.0) -> "CASH"
        sameRatio(ratio, ratio.roundToInt().toDouble()) -> "TQQQ ${ratio.roundToInt()}%"
        else -> "TQQQ ${String.format(Locale.US, "%.2f", ratio).trimEnd('0').trimEnd('.')}%"
    }
}
