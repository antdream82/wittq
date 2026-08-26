package com.fortq.wittq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class RefreshSoftRunner17dCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AutoRefreshScheduler.refreshStockNow(context)
    }
}

class StockWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(300.dp, 100.dp), DpSize(412.dp, 150.dp)),
    )

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = SoftRunner17dSnapshotStore.read(context)
        if (snapshot == null) {
            AutoRefreshScheduler.ensureStockNow(context)
        }

        val lastError = SoftRunner17dSnapshotStore.getError(context)
        val actualAttemptAt = if (snapshot != null) {
            snapshot.updatedAtMillis
        } else {
            SoftRunner17dSnapshotStore.getErrorAt(context)
        }
        val lastUpdate = if (actualAttemptAt > 0L) {
            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(actualAttemptAt))
        } else {
            "waiting"
        }

        provideContent {
            if (snapshot == null) {
                ErrorContent(lastError ?: "Initializing local 17d history...", lastUpdate)
            } else {
                SoftRunner17dContent(
                    snapshot = snapshot,
                    chart = drawChart(snapshot.priceHistory, snapshot.sma290History),
                    lastUpdate = lastUpdate,
                    size = LocalSize.current,
                )
            }
        }
    }

    private fun drawChart(prices: List<Double>, sma: List<Double?>): Bitmap? {
        if (prices.size < 2) return null
        val width = 420
        val height = 180
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val validSma = sma.mapNotNull { it }
        val values = prices + validSma
        val min = values.minOrNull() ?: return null
        val max = values.maxOrNull() ?: return null
        val range = (max - min).coerceAtLeast(0.01)
        fun y(value: Double): Float = height - ((value - min) / range * height).toFloat()
        fun x(index: Int, count: Int): Float = index.toFloat() * width / (count - 1).coerceAtLeast(1)

        val priceColor = if (prices.last() >= prices.first()) Color(0xFF30D158) else Color(0xFFFF453A)
        val pricePaint = Paint().apply {
            color = priceColor.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        val smaPaint = Paint().apply {
            color = Color(0xFFFFA400).toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                priceColor.toArgb(),
                android.graphics.Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            alpha = 55
        }

        val pricePath = Path()
        val fillPath = Path()
        prices.forEachIndexed { index, value ->
            val px = x(index, prices.size)
            val py = y(value)
            if (index == 0) {
                pricePath.moveTo(px, py)
                fillPath.moveTo(px, py)
            } else {
                pricePath.lineTo(px, py)
                fillPath.lineTo(px, py)
            }
        }
        fillPath.lineTo(width.toFloat(), height.toFloat())
        fillPath.lineTo(0f, height.toFloat())
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(pricePath, pricePaint)

        val smaPath = Path()
        var started = false
        sma.forEachIndexed { index, value ->
            if (value != null) {
                val px = x(index, sma.size)
                val py = y(value)
                if (!started) {
                    smaPath.moveTo(px, py)
                    started = true
                } else {
                    smaPath.lineTo(px, py)
                }
            }
        }
        if (started) canvas.drawPath(smaPath, smaPaint)
        return bitmap
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun SoftRunner17dContent(
    snapshot: SoftRunner17dWidgetSnapshot,
    chart: Bitmap?,
    lastUpdate: String,
    size: DpSize,
) {
    val factor = (size.width.value / 410f).coerceIn(0.65f, 1.0f)
    val previewDiffers = abs(snapshot.previewTarget - snapshot.officialTarget) >= 0.01
    val officialColor = targetColor(snapshot.officialTarget)
    val previewColor = if (previewDiffers) Color(0xFFFFA400) else officialColor

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E))
            .cornerRadius(28.dp)
            .padding(horizontal = (18 * factor).dp, vertical = (12 * factor).dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Text(
                "17d SOFT RUNNER",
                style = TextStyle(
                    color = ColorProvider(Color.LightGray),
                    fontSize = (13 * factor).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height((3 * factor).dp))

            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (chart != null) {
                    Image(
                        provider = ImageProvider(chart),
                        contentDescription = "TQQQ and SMA290 chart",
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    )
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                }

                Spacer(GlanceModifier.width((12 * factor).dp))

                Column(
                    modifier = GlanceModifier.width((168 * factor).dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "OFFICIAL ${SoftRunner17dNotifier.ratioLabel(snapshot.officialTarget)}",
                        style = TextStyle(
                            color = ColorProvider(officialColor),
                            fontSize = (16 * factor).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(GlanceModifier.height((2 * factor).dp))
                    Text(
                        "PREVIEW ${SoftRunner17dNotifier.ratioLabel(snapshot.previewTarget)}",
                        style = TextStyle(
                            color = ColorProvider(previewColor),
                            fontSize = (13 * factor).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }

            Spacer(GlanceModifier.height((2 * factor).dp))
            Text(
                "Reason ${compactReason(snapshot.reason)} · Runner ${runnerStatusLabel(snapshot.runnerStatus)} · Release ${releaseStatusLabel(snapshot.releaseStatus)} · Contra ${onOff(snapshot.contrarianActive)} · Risk ${onOff(snapshot.hardRisk)}",
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(
                    color = ColorProvider(Color(0xFFB0B0B5)),
                    fontSize = (7.5 * factor).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                "1Y ${formatReturn(snapshot.trailingReturn1y)} · 6M ${formatReturn(snapshot.trailingReturn6m)} · 3M ${formatReturn(snapshot.trailingReturn3m)} · TQQQ ${money(snapshot.tqqqClose)} · SMA290 ${snapshot.tqqqSma290?.let(::money) ?: "-"}",
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = (7.5 * factor).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ratio ${snapshot.tqqqSma290Ratio?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "-"} · C/R ${flag(snapshot.contrarianCheap)}/${flag(snapshot.contrarianReclaim)} · ${snapshot.statusMessage.take(30)} · Upd $lastUpdate",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF8E8E93)),
                        fontSize = (7 * factor).sp,
                    ),
                )
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Refresh 17d",
                    modifier = GlanceModifier
                        .size((13 * factor).dp)
                        .clickable(actionRunCallback<RefreshSoftRunner17dCallback>()),
                )
            }
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun ErrorContent(message: String, lastUpdate: String) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E))
            .clickable(actionRunCallback<RefreshSoftRunner17dCallback>()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("17d updating...", style = TextStyle(color = ColorProvider(Color.White)))
            Text(message.take(150), style = TextStyle(color = ColorProvider(Color(0xFFFF453A)), fontSize = 9.sp))
            Text("Last attempt $lastUpdate", style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = 9.sp))
            Text("Tap to retry", style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = 8.sp))
        }
    }
}

private fun compactReason(reason: String): String = when (reason) {
    "HOLD" -> "HOLD"
    "BASE_TARGET_CHANGE" -> "BASE CHANGE"
    "BASE_TRIGGER_CONFIRMING" -> "RUNNER CONFIRM"
    "BASE_TRIGGER_COMPLETE" -> "RUNNER ON"
    "BASE_HARD_RISK_TERMINATION" -> "HARD EXIT"
    "BASE_ANCHOR_MUTATION_COMPLETE" -> "RELEASE DONE"
    "CONTRARIAN_ENTER" -> "CONTRA ENTER"
    "CONTRARIAN_ACTIVE_HOLD" -> "CONTRA HOLD"
    "CONTRARIAN_PANIC_OVERRIDE_ACTIVE" -> "PANIC OVERRIDE"
    else -> reason.replace('_', ' ').take(18)
}

private fun runnerStatusLabel(status: String): String = when (status) {
    "ACTIVE" -> "20% ON"
    "TRIGGER_CONFIRMING" -> "CONFIRM"
    else -> "OFF"
}

private fun releaseStatusLabel(status: String): String = when (status) {
    "PENDING" -> "PENDING"
    else -> "NONE"
}

private fun formatReturn(value: Double?): String = when {
    value == null || !value.isFinite() -> "-"
    else -> String.format(Locale.US, "%+.1f%%", value)
}

private fun money(value: Double): String = String.format(Locale.US, "\$%.2f", value)
private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"
private fun flag(value: Boolean): String = if (value) "T" else "F"

private fun targetColor(target: Double): Color = when {
    target <= 0.01 -> Color(0xFFFF453A)
    target < 50.0 -> Color(0xFFFFA400)
    target < 90.0 -> Color(0xFF0A84FF)
    else -> Color(0xFF30D158)
}
