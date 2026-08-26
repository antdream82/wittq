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
    override val sizeMode = SizeMode.Exact

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

        val width = 600
        val height = 320
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val validSma = sma.mapNotNull { it }
        val values = prices + validSma
        val min = values.minOrNull() ?: return null
        val max = values.maxOrNull() ?: return null
        val range = (max - min).coerceAtLeast(0.01)

        val plotLeft = 6f
        val plotRight = width - 6f
        val plotTop = 10f
        val plotBottom = height - 10f
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        fun y(value: Double): Float =
            plotBottom - ((value - min) / range * plotHeight).toFloat()

        fun x(index: Int, count: Int): Float =
            plotLeft + index.toFloat() * plotWidth / (count - 1).coerceAtLeast(1)

        val priceColor = if (prices.last() >= prices.first()) Color(0xFF30D158) else Color(0xFFFF453A)
        val pricePaint = Paint().apply {
            color = priceColor.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val smaPaint = Paint().apply {
            color = Color(0xFFFFA400).toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
            shader = LinearGradient(
                0f,
                plotTop,
                0f,
                plotBottom,
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
        fillPath.lineTo(plotRight, plotBottom)
        fillPath.lineTo(plotLeft, plotBottom)
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

private data class TargetHeadline(
    val label: String,
    val value: String,
)

@SuppressLint("RestrictedApi")
@Composable
private fun SoftRunner17dContent(
    snapshot: SoftRunner17dWidgetSnapshot,
    chart: Bitmap?,
    lastUpdate: String,
    size: DpSize,
) {
    val factor = minOf(
        size.width.value / 380f,
        size.height.value / 290f,
    ).coerceIn(0.80f, 1.12f)
    val footerFactor = factor.coerceAtMost(1.0f)
    val rightWidth = (size.width.value * 0.31f).coerceIn(104f, 138f)

    // Keep the visual block compact so the four-line footer reads as one section.
    val topHeight = (size.height.value * 0.38f).coerceIn(90f, 122f)

    val previewDiffers = abs(snapshot.previewTarget - snapshot.officialTarget) >= 0.01
    val officialColor = targetColor(snapshot.officialTarget)
    val previewColor = if (previewDiffers) Color(0xFFFFA400) else officialColor
    val officialHeadline = targetHeadline("OFFICIAL", snapshot.officialTarget)
    val previewHeadline = targetHeadline("PREVIEW", snapshot.previewTarget)
    val statusColor = if (snapshot.stale) Color(0xFFFF453A) else Color(0xFF8E8E93)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E))
            .cornerRadius(28.dp)
            .padding(horizontal = (16 * factor).dp, vertical = (10 * factor).dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Text(
                "17d SOFT RUNNER",
                style = TextStyle(
                    color = ColorProvider(Color.LightGray),
                    fontSize = (14 * factor).sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height((3 * factor).dp))

            Row(modifier = GlanceModifier.fillMaxWidth().height(topHeight.dp)) {
                if (chart != null) {
                    Image(
                        provider = ImageProvider(chart),
                        contentDescription = "TQQQ and SMA290 chart",
                        contentScale = ContentScale.FillBounds,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    )
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                }

                Spacer(GlanceModifier.width((10 * factor).dp))

                Column(
                    modifier = GlanceModifier.width(rightWidth.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        officialHeadline.label,
                        style = TextStyle(
                            color = ColorProvider(officialColor),
                            fontSize = (12.0f * factor).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        officialHeadline.value,
                        style = TextStyle(
                            color = ColorProvider(officialColor),
                            fontSize = (17 * factor).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(GlanceModifier.height((5 * factor).dp))
                    Text(
                        previewHeadline.label,
                        style = TextStyle(
                            color = ColorProvider(previewColor),
                            fontSize = (11.5f * factor).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        previewHeadline.value,
                        style = TextStyle(
                            color = ColorProvider(previewColor),
                            fontSize = (14.5f * factor).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }

            Spacer(GlanceModifier.height((3 * footerFactor).dp))

            // Glance app-widget Row/Column containers support at most 10 direct
            // children. Keep all footer lines inside one nested Column so the
            // outer Column stays well below that hard limit.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    "Reason ${compactReason(snapshot.reason)} · Runner ${runnerStatusLabel(snapshot.runnerStatus)} · Release ${releaseStatusLabel(snapshot.releaseStatus)} · Contra ${onOff(snapshot.contrarianActive)} · Risk ${onOff(snapshot.hardRisk)}",
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(bottom = (1.0f * footerFactor).dp),
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFB0B0B5)),
                        fontSize = (8.0f * footerFactor).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )

                Text(
                    "TQQQ ${money(snapshot.tqqqClose)} · SMA290 ${snapshot.tqqqSma290?.let(::money) ?: "-"} · Disp ${formatDisp(snapshot.tqqqSma290Ratio)}",
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(bottom = (1.0f * footerFactor).dp),
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = (8.5f * footerFactor).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )

                Text(
                    "1Y ${formatReturn(snapshot.trailingReturn1y)} · 6M ${formatReturn(snapshot.trailingReturn6m)} · 3M ${formatReturn(snapshot.trailingReturn3m)}",
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(bottom = (1.0f * footerFactor).dp),
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = (8.5f * footerFactor).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )

                Text(
                    "Cheap ${onOff(snapshot.contrarianCheap)} · Reclaim ${onOff(snapshot.contrarianReclaim)} · ${compactStatusMessage(snapshot.statusMessage)} · Upd $lastUpdate",
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(end = (22 * footerFactor).dp),
                    style = TextStyle(
                        color = ColorProvider(statusColor),
                        fontSize = (7.2f * footerFactor).sp,
                    ),
                )
            }
        }

        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd,
        ) {
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

private fun targetHeadline(prefix: String, target: Double): TargetHeadline {
    val label = SoftRunner17dNotifier.ratioLabel(target)
    return when {
        label.startsWith("TQQQ ") -> TargetHeadline("$prefix TQQQ", label.removePrefix("TQQQ "))
        label == "CASH" -> TargetHeadline(prefix, "CASH")
        else -> TargetHeadline(prefix, label)
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

private fun formatDisp(value: Double?): String = when {
    value == null || !value.isFinite() -> "-"
    else -> String.format(Locale.US, "%.1f%%", value * 100)
}

private fun compactStatusMessage(status: String): String = when {
    status.startsWith("STALE: official ") -> "STALE ${status.removePrefix("STALE: official ")}"
    status.startsWith("LIVE PREVIEW ") -> status.removePrefix("LIVE ").replace(" / ", " · ")
    else -> status
}

private fun money(value: Double): String = String.format(Locale.US, "\$%.2f", value)
private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

private fun targetColor(target: Double): Color = when {
    target <= 0.01 -> Color(0xFFFF453A)
    target < 50.0 -> Color(0xFFFFA400)
    target < 90.0 -> Color(0xFF0A84FF)
    else -> Color(0xFF30D158)
}
