package com.fortq.wittq

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sends one local notification whenever the latest calculated target changes. */
object PositionChangeNotifier {
    private const val CHANNEL_ID = "tqqq_position_changes"
    private const val NOTIFICATION_ID = 1001
    private const val KEY_HAS_LIVE_BASELINE = "has_live_position_notification_baseline"
    private const val KEY_LAST_LIVE_RATIO = "last_live_position_notification_ratio"

    @Synchronized
    fun notifyIfChanged(
        context: Context,
        prefs: SharedPreferences,
        currentRatio: Double,
        actionTitle: String,
        actionDesc: String
    ) {
        if (!currentRatio.isFinite() || actionDesc == "Loading") return

        val hasBaseline = prefs.getBoolean(KEY_HAS_LIVE_BASELINE, false)
        val previousRatio = prefs.getFloat(KEY_LAST_LIVE_RATIO, 0f).toDouble()

        // Fresh installs establish a baseline without announcing the current position as a change.
        if (!hasBaseline) {
            saveBaseline(prefs, currentRatio)
            return
        }
        if (sameRatio(previousRatio, currentRatio)) return
        saveBaseline(prefs, currentRatio)

        createChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "TQQQ 포지션 변경"
        val body = "${ratioLabel(previousRatio)} -> ${ratioLabel(currentRatio)}\n$actionTitle / $actionDesc"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(title)
            .setContentText("${ratioLabel(previousRatio)} -> ${ratioLabel(currentRatio)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun saveBaseline(prefs: SharedPreferences, ratio: Double) {
        prefs.edit()
            .putBoolean(KEY_HAS_LIVE_BASELINE, true)
            .putFloat(KEY_LAST_LIVE_RATIO, ratio.toFloat())
            .commit()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TQQQ 포지션 변경",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "실시간 TQQQ 목표 포지션 변경 알림"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun sameRatio(a: Double, b: Double): Boolean = abs(a - b) < 0.01

    private fun ratioLabel(ratio: Double): String = when {
        sameRatio(ratio, 100.0) -> "TQQQ 100%"
        sameRatio(ratio, 66.67) -> "TQQQ 2/3"
        sameRatio(ratio, 10.0) -> "TQQQ Soft 10%"
        sameRatio(ratio, 2.5) -> "TQQQ Runner 2.5%"
        sameRatio(ratio, 0.0) -> "CASH"
        sameRatio(ratio, ratio.roundToInt().toDouble()) -> "TQQQ ${ratio.roundToInt()}%"
        else -> "TQQQ ${String.format(java.util.Locale.US, "%.2f", ratio).trimEnd('0').trimEnd('.')}%"
    }
}
