package com.fortq.wittq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.core.graphics.createBitmap
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import java.text.SimpleDateFormat
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import kotlin.Double


class UpdatefgiCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            Log.d("WITTQ_FGI_DEBUG", "Refresh button clicked")
            FGIWidget().updateAll(context)
            Log.d("WITTQ_FGI_DEBUG", "Widget Refresh completed")
        } catch (e: Exception) {
            Log.e("WITTQ_FGI_DEBUG", "Widget Refresh failed: ${e.message}", e)
        }
    }
}

class FGIWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(300.dp, 100.dp), DpSize(412.dp, 150.dp))
    )

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Log.d("WITTQ_FGI_DEBUG", "Starting FGI widget update...")
        FGIUpdateWorker.enqueue(context)

        val resultData = withContext(Dispatchers.IO) {
            try {
                val result = FGApiEngine.fetchAll()
                val lastUpdate =
                    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
                Log.d("WITTQ_DEBUG", "Widget updated at: $lastUpdate")

                if (result != null) {
                    // 타입 불일치 해결: 90일 히스토리(List), 현재가(Double), 색상 등을 정확히 전달
                    val fgChart = drawFearGreedChart(result.fgHistory)
                    val fgGauge = drawFearGreedGauge(result.fgData.score, 200)
                    val pcGauge = drawPutCallGauge(result.pcRatio, 200)

                    Log.d("WITTQ_FGI_DEBUG", "Charts created successfully")
                    FGResult(result.fgData, result.pcRatio, fgChart, fgGauge, pcGauge, lastUpdate)
                } else {
                    Log.e("WITTQ_FGI_DEBUG", "Data incomplete - FG: $result.fgData, PC: $result.pcRatio, History: ${result?.fgHistory?.size}")
                    null
                }
            } catch (e: Exception) {
                Log.e("WITTQ_FGI_DEBUG", "Widget update failed", e)
                null
            }
        }

        provideContent {
            val size = LocalSize.current
            if (resultData != null) {
                FGWidgetUI(
                    resultData.fgData,
                    resultData.pcRatio,
                    resultData.fgChart,
                    resultData.fgGauge,
                    resultData.pcGauge,
                    resultData.refreshTime,
                    size
                )
            } else {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Loading F&G Index...", style = TextStyle(color = ColorProvider(Color.White)))
                        val reftime =
                            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
                        Text(
                            reftime, style = TextStyle(
                                color = ColorProvider(Color.White.copy(alpha = 0.6f)),
                                fontSize = 14.sp
                            )
                        )
                    }
                }
            }
        }
    }

    data class FGResult(
        val fgData: FearGreedData,
        val pcRatio: Double,
        val fgChart: Bitmap,
        val fgGauge: Bitmap,
        val pcGauge: Bitmap,
        val refreshTime: String
    )
    private fun drawFearGreedGauge(score: Double, size: Int): Bitmap {
        val bitmap = createBitmap(size, size / 2 + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val centerX = size / 2f
        val centerY = size / 2f
        val radius = size / 2f - 40f

        val rectF = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        val bgPaint = Paint().apply {
            color = 0xFF2C2C2E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 35f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        bgPaint.color = 0xFFFF453A.toInt()
        canvas.drawArc(rectF, 180f, 90f, false, bgPaint)

        bgPaint.color = 0xFF008A42.toInt()
        canvas.drawArc(rectF, 270F, 90f, false, bgPaint)

        // 구간별 색상 그리기
        val segments = listOf(
            Triple(0f, 25f, 0xFFFF453A.toInt()),      // Extreme Fear (빨강)
            Triple(25f, 45f, 0xFFFF9500.toInt()),     // Fear (주황)
            Triple(45f, 55f, 0xFFFFCC00.toInt()),     // Neutral (노랑)
            Triple(55f, 75f,0xFF32D74B.toInt()),     // Greed (초록)
            Triple(75f, 100f, 0xFF008A42.toInt())     // Extreme Greed (진한 초록)
        )

        val segmentPaint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 35f
            isAntiAlias = true
            strokeCap = Paint.Cap.BUTT
        }

        segments.forEach { (start, end, color) ->
            segmentPaint.color = color
            val startAngle = 180f + (start * 1.8f)
            val sweepAngle = (end - start) * 1.8f

            canvas.drawArc(rectF, startAngle, sweepAngle + 0.5f, false, segmentPaint)
        }


        // 바늘 그리기
        val needleAngle = 180f + (score * 1.8f).toFloat()
        val needlePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
            strokeWidth = 6f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(12f, 0f, 0f, android.graphics.Color.BLACK)
        }

        val needleLength = radius - 10f
        val needleEndX = centerX + needleLength * Math.cos(Math.toRadians(needleAngle.toDouble())).toFloat()
        val needleEndY = centerY + needleLength * Math.sin(Math.toRadians(needleAngle.toDouble())).toFloat()

        canvas.drawLine(centerX, centerY, needleEndX, needleEndY, needlePaint)
        canvas.drawCircle(centerX, centerY, 8f, Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true })


        // 텍스트 레이블
        /*val textPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 8f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("EXTREME\nFEAR", centerX - radius + 60, centerY + 40, textPaint)
        canvas.drawText("NEUTRAL", centerX, centerY - radius - 10, textPaint)
        canvas.drawText("EXTREME\nGREED", centerX + radius - 60, centerY + 40, textPaint)*/

        return bitmap
    }

    private fun drawPutCallGauge(
        ratio: Double,
        size: Int
    ): Bitmap {
        val bitmap = createBitmap(size, size / 2 + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerX = size / 2f
        val centerY = size / 2f
        val radius = size / 2f - 40f

        val rectF = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val bgPaint = Paint().apply {
            color = 0xFF2C2C2E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 35f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(rectF, 180f, 180f, false, bgPaint)

        val anglePercent = ((ratio - 0.4) / 1.0).coerceIn(0.0, 1.0)
        val actualAngle = 180f + (anglePercent * 180f).toFloat()

        val colorPaint = Paint().apply {
            this.color = getPCColor(ratio).toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 35f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        canvas.drawArc(rectF, 180f, (anglePercent * 180f).toFloat() , false, colorPaint)

        val needlePaint = Paint().apply {
            color = android.graphics.Color.WHITE
            strokeWidth = 6f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(10f, 0f, 0f, android.graphics.Color.BLACK)
        }

        val needleLength = radius - 10f
        val needleEndX = centerX + needleLength * Math.cos(Math.toRadians(actualAngle.toDouble())).toFloat()
        val needleEndY = centerY + needleLength * Math.sin(Math.toRadians(actualAngle.toDouble())).toFloat()

        canvas.drawLine(centerX, centerY, needleEndX, needleEndY, needlePaint)
        canvas.drawCircle(centerX, centerY, 8f, Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true })

        return bitmap
    }

    private fun drawFearGreedChart(
        fgindex: List<Double>,
    ): Bitmap {
        val width = 420
        val height = 320
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paddingLeft = 45f
        val chartWidth = width - paddingLeft - 10f

        val chartColors = intArrayOf(
            0xDE30D158.toInt(), // Extreme Greed (Top)
            0xDE32D74B.toInt(), // Greed
            0xDEc9c9c9.toInt(), // Neutral0xFFFFCC00.toInt()
            0xDEFF9500.toInt(), // Fear
            0xDEFF453A.toInt()
        )
        val colorPositions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        val indexPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.3f
            isAntiAlias = true
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                chartColors, colorPositions, Shader.TileMode.CLAMP
            )
        }
        val guidePaint = Paint().apply {
            this.color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.7f; alpha = 80
        }
        val textPaint = Paint().apply {
            this.color = android.graphics.Color.WHITE; textSize = 20f; isAntiAlias = true; alpha = 120
        }

        val pointPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // 3. 마지막 최신 점 강조 Paint (선택 사항)

        if (fgindex.size < 2 ) return bitmap

        fun getY(v: Double) = (height - (v / 100.0 * height)).toFloat()

        val guideValues = listOf(20.0, 50.0, 80.0)
        guideValues.forEach {v ->
            val y = getY(v)
            canvas.drawLine(0f, y, width.toFloat(), y, guidePaint)
            canvas.drawText(v.toInt().toString(), 10f, y - 5f, textPaint)
        }
        val path = Path()
        val stepX = chartWidth / (fgindex.size - 1)

        val points = fgindex.mapIndexed { i, score ->
            val x = paddingLeft + (i.toFloat() * stepX)
            val y = getY(score)
            x to y
        }
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, indexPaint)

        points.forEachIndexed { i, (x, y) ->
            if (i == points.lastIndex) {
                canvas.drawCircle(x, y, 4f, pointPaint)
            }
        }
        return bitmap
    }


    @SuppressLint("RestrictedApi", "DefaultLocale")
    @Composable
    fun FGWidgetUI(
        fgData: FearGreedData,
        pcRatio: Double,
        fgChart: Bitmap,
        fgGauge: Bitmap,
        pcGauge: Bitmap,
        refreshTime: String,
        size: DpSize
    ) {
        val factor = (size.width.value / 400f).coerceIn(0.6f, 1.0f)

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1E))
                .cornerRadius(34.dp)
                .padding((20 * factor).dp)
        ) {
            Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.Start,
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row (verticalAlignment = Alignment.Bottom) {
                            // Fear & Greed Index
                            Text(
                                "Fear & Greed Index",
                                style = TextStyle(
                                    fontSize = (14 * factor).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ColorProvider(Color(0xFFc1c1c1))
                                )
                            )
                            Spacer(modifier = GlanceModifier.width((8 * factor).dp))
                            Text(
                                "(Updated $refreshTime)",
                                style = TextStyle(
                                    fontSize = (8 * factor).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ColorProvider(Color.Gray)
                                )
                            )
                            Spacer(modifier = GlanceModifier.width((8 * factor).dp))
                            Image(
                                provider = ImageProvider(R.drawable.ic_refresh),
                                contentDescription = "Refresh",
                                modifier = GlanceModifier.size((14 * factor).dp)
                                    .clickable(actionRunCallback<UpdatefgiCallback>())
                            )
                        }
                        Spacer(modifier = GlanceModifier.height((6 * factor).dp))

                        Image(
                            provider = ImageProvider(fgChart),
                            contentDescription = "Fear Greed Chart",
                            modifier = GlanceModifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width((8 * factor).dp))

                Column( modifier = GlanceModifier.width((110*factor).dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally)
                        {
                            Image(
                                provider = ImageProvider(pcGauge),
                                contentDescription = "Put Call Gauge",
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height((60 * factor).dp)
                            )
                            Text(
                                String.format("%.2f", pcRatio),
                                style = TextStyle(
                                    fontSize = (16 * factor).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ColorProvider(getPCColor(pcRatio))
                                )
                            )

                            Text(
                                getPCLabel(pcRatio),
                                style = TextStyle(
                                    fontSize = (12 * factor).sp,
                                    color = ColorProvider(Color.Gray)
                                )
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.height((6 * factor).dp))

                    Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally)
                        {
                            Image(
                                provider = ImageProvider(fgGauge),
                                contentDescription = "Fear Greed Gauge",
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height((60 * factor).dp)
                            )

                            Text(
                                fgData.score.toInt().toString(),
                                style = TextStyle(
                                    fontSize = (16 * factor).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ColorProvider(getFGColor(fgData.score))
                                )
                            )

                            Text(
                                fgData.rating,
                                style = TextStyle(
                                    fontSize = (12 * factor).sp,
                                    color = ColorProvider(Color.Gray))
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getPCLabel(ratio: Double): String {
        return when {
            ratio < 0.63 -> "Caution"
            ratio < 0.72 -> "Positive"
            ratio < 0.79 -> "neutral"
            ratio < 0.86 -> "Negative"
            else -> "Caution"
        }
    }
    private fun getFGColor(score: Double): Color {
        return when {
            score < 25 -> Color(0xFFFF453A)    // Extreme Fear
            score < 45 -> Color(0xFFFF9500)    // Fear
            score < 55 -> Color(0xFFFFCC00)    // Neutral
            score < 75 -> Color(0xFF32D74B)    // Greed
            else -> Color(0xFF30D158)          // Extreme Greed
        }
    }

    private fun getPCColor(ratio: Double): Color {
        return when {
            ratio < 0.63 -> Color.Gray  // 초낙관 (주의)
            ratio < 0.72 -> Color(0xFF32D74B)   // 낙관적 (초록)
            ratio < 0.79 -> Color(0xFFFFCC00)   // 중립 (노랑)
            ratio < 0.86 -> Color(0xFFFF9500)   // 비관적 (빨강)
            else -> Color.Gray          // 극단적 (주의)
        }
    }
}
