package com.fortq.wittq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        StockUpdateWorker.enqueue(context)
        val snapshot = withContext(Dispatchers.IO) {
            try {
                SoftRunner17dDataSource.load(context)
            } catch (e: CancellationException) {
                // A Glance/WorkManager lifecycle cancellation is not a Yahoo or
                // calculation failure. Propagate it so the previous widget UI
                // remains intact instead of replacing it with a false error.
                throw e
            } catch (e: Exception) {
                Log.e("SOFT_RUNNER_17D", "17d calculation failed: ${e.message}", e)
                null
            }
        }
        if (snapshot != null) SoftRunner17dNotifier.process(context, snapshot)
        val lastUpdate = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
        val lastError = StockApiEngine.getLastError(context)

        provideContent {
            if (snapshot == null) {
                ErrorContent(lastError ?: "17d data/calculation unavailable", lastUpdate)
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
    snapshot: SoftRunner17dAppSnapshot,
    chart: Bitmap?,
    lastUpdate: String,
    size: DpSize,
) {
    val factor = (size.width.value / 410f).coerceIn(0.65f, 1.0f)
    val official = snapshot.official
    val preview = snapshot.preview
    val previewDiffers = abs(preview.finalTarget - official.finalTarget) >= 0.01
    val officialColor = targetColor(official.finalTarget)
    val previewColor = if (previewDiffers) Color(0xFFFFA400) else officialColor

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E))
            .cornerRadius(28.dp)
            .padding(horizontal = (18 * factor).dp, vertical = (14 * factor).dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                Text(
                    "17d SOFT RUNNER",
                    style = TextStyle(
                        color = ColorProvider(Color.LightGray),
                        fontSize = (13 * factor).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.height((4 * factor).dp))
                chart?.let {
                    Image(
                        provider = ImageProvider(it),
                        contentDescription = "TQQQ and SMA290 chart",
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    )
                }
                Text(
                    snapshot.statusMessage,
                    style = TextStyle(
                        color = ColorProvider(if (snapshot.stale) Color(0xFFFF453A) else Color(0xFF8E8E93)),
                        fontSize = (9 * factor).sp,
                    ),
                )
            }

            Spacer(GlanceModifier.width((14 * factor).dp))

            Column(
                modifier = GlanceModifier.width((170 * factor).dp).fillMaxHeight(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "OFFICIAL ${SoftRunner17dNotifier.ratioLabel(official.finalTarget)}",
                    style = TextStyle(
                        color = ColorProvider(officialColor),
                        fontSize = (18 * factor).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    "PREVIEW ${SoftRunner17dNotifier.ratioLabel(preview.finalTarget)}",
                    style = TextStyle(
                        color = ColorProvider(previewColor),
                        fontSize = (15 * factor).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.height((5 * factor).dp))
                InfoLine("Reason", preview.reason.label, factor)
                InfoLine("Runner", "${preview.runnerStatus.name}/${preview.releaseStatus.name}", factor)
                InfoLine("Contrarian", if (preview.contrarianActive) "ACTIVE" else "OFF", factor)
                InfoLine("HardRisk", if (preview.hardRisk) "ON" else "OFF", factor)
                InfoLine("Cheap/Reclaim", "${flag(preview.contrarianCheap)}/${flag(preview.contrarianReclaim)}", factor)
                InfoLine("TQQQ", String.format(Locale.US, "\$%.2f", preview.tqqqClose), factor)
                InfoLine("SMA290", preview.tqqqSma290?.let { String.format(Locale.US, "\$%.2f", it) } ?: "-", factor)
                InfoLine("Ratio", preview.tqqqSma290Ratio?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: "-", factor)
                Spacer(GlanceModifier.defaultWeight())
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        "Upd $lastUpdate",
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = (8 * factor).sp),
                    )
                    Image(
                        provider = ImageProvider(R.drawable.ic_refresh),
                        contentDescription = "Refresh 17d",
                        modifier = GlanceModifier
                            .size((16 * factor).dp)
                            .clickable(actionRunCallback<RefreshSoftRunner17dCallback>()),
                    )
                }
            }
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun InfoLine(label: String, value: String, factor: Float) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            label,
            style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = (10 * factor).sp),
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            value.take(28),
            style = TextStyle(color = ColorProvider(Color.White), fontSize = (10 * factor).sp, fontWeight = FontWeight.Bold),
        )
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun ErrorContent(message: String, lastUpdate: String) {
    Box(modifier = GlanceModifier.fillMaxSize().background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("17d updating...", style = TextStyle(color = ColorProvider(Color.White)))
            Text(message.take(120), style = TextStyle(color = ColorProvider(Color(0xFFFF453A)), fontSize = 9.sp))
            Text(lastUpdate, style = TextStyle(color = ColorProvider(Color(0xFF8E8E93)), fontSize = 9.sp))
        }
    }
}

private fun flag(value: Boolean): String = if (value) "T" else "F"

private fun targetColor(target: Double): Color = when {
    target <= 0.01 -> Color(0xFFFF453A)
    target < 50.0 -> Color(0xFFFFA400)
    target < 90.0 -> Color(0xFF0A84FF)
    else -> Color(0xFF30D158)
}
