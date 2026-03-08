package com.fortq.wittq

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
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
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.collections.emptyList

class UpdateActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            Log.d("WITTQ_DEBUG", "Refresh button clicked")
            StockWidget().updateAll(context)
            Log.d("WITTQ_DEBUG", "Widget Refresh completed")
        } catch (e: Exception) {
            Log.e("WITTQ_DEBUG", "Widget Refresh failed: ${e.message}", e)
        }
    }
}

class StockWidget : GlanceAppWidget() {

    // 3. SizeMode 적용: 기기별 다양한 4x2 사이즈에 대응
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(300.dp, 100.dp), DpSize(412.dp, 150.dp))
    )

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        StockUpdateWorker.enqueue(context)

        val prefs = context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE)
        val userPosition = prefs.getString("user_position", "TQQQ") ?: "TQQQ"
        val avgPrice = ((prefs.getFloat("user_avg_price", 50.0f)*10).toInt() / 10.0)

        val lastEntryPrice = ((prefs.getFloat("last_entry_price", 0f) * 10).toInt() / 10.0)
        val hadForceExit = prefs.getBoolean("had_force_exit", false)

        val lastRatio = prefs.getInt("last_ratio", 0)
        var signalDesc: String = prefs.getString("last_signal_desc", "-") ?: "-"

        val resultdata = withContext(Dispatchers.IO) {
            try {
                val tqData = StockApiEngine.fetchMarketData("TQQQ") ?: return@withContext null
                val qData = StockApiEngine.fetchMarketData("QQQ") ?: return@withContext null
                val spyData = StockApiEngine.fetchMarketData("SPY") ?: return@withContext null
                val tHis = tqData.history
                val qHis = qData.history
                val spyHis = spyData.history
                val tCur = tqData.currentPrice
                val qCur = qData.currentPrice
                val spyCur = spyData.currentPrice

                if (tHis.isEmpty() || qHis.isEmpty()) {
                    Log.e("WITTQ_DEBUG", "Price data is empty")
                    throw Exception("Data empty")
                }

                val result = TqqqAlgorithm.calculate(
                    qPrices = qHis,
                    tPrices = tHis,
                    spyPrices = spyHis,
                    userPosition,
                    avgPrice,
                    lastEntryPrice,
                    hadForceExit
                )

                val currentRatio = result.targetRatio

                if (lastRatio != currentRatio) {
                    val direction = if (currentRatio > lastRatio) "↑" else "↓"
                    signalDesc = "${lastRatio}% > ${currentRatio}% ${direction}"

                    prefs.edit {
                        putInt("last_ratio", currentRatio)
                        putString("last_signal_desc", signalDesc)
                        // 강제 탈출(ESCAPE, STOP LOSS) 시 상태 저장
                        if (result.actionTitle == "ESCAPE" || result.actionTitle == "STOP LOSS") {
                            putBoolean("had_force_exit", true)
                            putFloat("last_entry_price", 0f)
                        }
                        // 신규 진입 시(100% 비중) 진입가 기록
                        else if (result.targetRatio == 100 && lastEntryPrice == 0.0) {
                            putFloat("last_entry_price", result.currentPrice.toFloat())
                        }

                        // RSI가 43 이상으로 회복되면 강제탈출 기록 해제
                        if (result.rsi >= 43) {
                            putBoolean("had_force_exit", false)
                        }

                        // 현재 포지션 동기화
                        putString("user_position", result.displayPosition)
                    }
                }

                val chartDays = 120
                val tMa200 = calculateMA(tHis, 200, chartDays)
                val qMa3 = calculateMA(qHis, 3, chartDays)
                val qMa161 = calculateMA(qHis, 161, chartDays)

                val tChart = drawSimpleChart(tHis.takeLast(chartDays), tMa200, if (result.isTqqqBullish) Color(0xFF30D158) else Color(0xFFFF453A), 400)
                val qChart = drawSimpleChart(qMa3, qMa161, if (result.isQqqBullish) Color(0xFF30D158) else Color(0xFFFF453A), 400)

                Triple(result, tChart, qChart)
            } catch (e: Exception) {
                Log.e("WITTQ_DEBUG", "Data fetch failed: ${e.message}")
                e.printStackTrace() // 상세 에러 추적
                null
            }
        }

        val lastUpdate =
            SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        Log.d("WITTQ_DEBUG", "Widget updated at: $lastUpdate")

        provideContent {
            val size = LocalSize.current

            if (resultdata != null) {
                val (result, tChart, qChart) = resultdata
                WidgetContent(result, tChart, qChart, lastUpdate, size, signalDesc)
            } else {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Updating...", style = TextStyle(color = ColorProvider(Color.White)))
                        Text(lastUpdate, style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.6f)),
                            fontSize = 10.sp)
                        )
                    }
                }
            }
        }
    }

    // 차트 그리기 로직 (가변 너비 적용)
    private fun drawSimpleChart(
        prices: List<Double>,
        maLine: List<Double>,
        color: Color,
        widgetWidth: Int
    ): Bitmap {
        val width = 400
        val height = 120
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pricePaint = Paint().apply {
            this.color = color.toArgb(); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
        }
        val maPaint = Paint().apply {
            this.color = android.graphics.Color.WHITE; alpha = 70; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL; isAntiAlias = true; shader = LinearGradient(
                0f, 0f, 0f,
                height.toFloat(),
                color.toArgb(),
                android.graphics.Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            ); alpha = 50
        }

        val allValues = prices + maLine
        val max = allValues.maxOrNull() ?: 1.0
        val min = allValues.minOrNull() ?: 0.0
        val range = (max - min).coerceAtLeast(0.1)
        fun getY(v: Double) = height - ((v - min) / range * height).toFloat()

        if (maLine.isNotEmpty()) {
            val maPath = Path()
            maLine.forEachIndexed { i, p ->
                val x = i.toFloat() * (width.toFloat() / (maLine.size - 1))
                if (i == 0) maPath.moveTo(x, getY(p)) else maPath.lineTo(x, getY(p))
            }
            canvas.drawPath(maPath, maPaint)
        }
        if (prices.isNotEmpty()) {
            val pricePath = Path()
            val fillPath = Path()
            prices.forEachIndexed { i, p ->
                val x = i.toFloat() * (width.toFloat() / (prices.size - 1))
                val y = getY(p)
                if (i == 0) {
                    pricePath.moveTo(x, y); fillPath.moveTo(x, y)
                } else {
                    pricePath.lineTo(x, y); fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(width.toFloat(), height.toFloat()); fillPath.lineTo(
                0f,
                height.toFloat()
            ); fillPath.close()
            canvas.drawPath(fillPath, fillPaint); canvas.drawPath(pricePath, pricePaint)
        }
        return bitmap
    }

    @SuppressLint("DefaultLocale", "RestrictedApi")
    @Composable
    private fun WidgetContent(
        res: AlgoResult,
        tChart: Bitmap?,
        qChart: Bitmap?,
        updateTime: String,
        size: DpSize,
        lastSignal: String
    ) {
        val factor = (size.width.value / 410f).coerceIn(0.6f, 1.0f)
        val hpadding = (40 * factor).dp
        val vpadding = (16 * factor).dp

        val isCash = res.userPosition.uppercase() == "CASH"
        val grayColor = Color(0xFF8E8E93)
        val disparity = res.disparity

        // [조정 2] PORTFOLIO 및 ACTION 값 글자 크기 살짝 축소
        val scoreSize = (44 * factor).sp
        val titleSize = (14 * factor).sp
        val contextSize = (20 * factor).sp // 기존 24 -> 22로 축소
        val subactSize = (11 * factor).sp

        val statusPrice = "$${String.format("%.2f", res.currentPrice)}"
        val statusRate = if (isCash) "0.0%" else "${if (res.profitRate >= 0) "+" else ""}${String.format("%.1f", res.profitRate)}%"
        val statusColor = if (isCash) grayColor else Color.White
        val rateColor = if (isCash) grayColor else (if (res.profitRate >= 0) Color(0xFF30D158) else Color(0xFF0A84FF))

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1E))
                .cornerRadius(42.dp)
                .padding(horizontal = hpadding, vertical = vpadding)
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
                ) {
                    // --- [좌측 섹션] ---
                    Column(
                        modifier = GlanceModifier.defaultWeight(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${res.score}",
                                style = TextStyle(
                                    color = ColorProvider(
                                        if (res.score >= 1) Color(0xFF30D158) else Color(
                                            0xFFFF453A
                                        )
                                    ),
                                    fontSize = scoreSize, fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = GlanceModifier.width(6.dp))

                            Column {
                                Spacer(modifier = GlanceModifier.height(4.dp))
                                Text(
                                    "SCORE ($updateTime)",
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF8E8E93)),
                                        fontSize = (12 * factor).sp
                                    )
                                )
                                Text(
                                    res.marketStatus,
                                    style = TextStyle(
                                        color = ColorProvider(Color.White),
                                        fontSize = contextSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height((7 * factor).dp))
                        Text(
                            "TQQQ ( 200MA )",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF8E8E93)),
                                fontSize = (10 * factor).sp
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        tChart?.let {
                            Image(
                                provider = ImageProvider(it),
                                contentDescription = null,
                                modifier = GlanceModifier.fillMaxWidth().height((42 * factor).dp)
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(8.dp))
                        Text(
                            "QQQ ( 3/161 )",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF8E8E93)),
                                fontSize = (10 * factor).sp
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        qChart?.let {
                            Image(
                                provider = ImageProvider(it),
                                contentDescription = null,
                                modifier = GlanceModifier.fillMaxWidth().height((42 * factor).dp)
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.width((16 * factor).dp))

                    // --- [우측 섹션] ---
                    Column(
                        modifier = GlanceModifier.defaultWeight()
                    ) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "ACTION",
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF8E8E93)),
                                        fontSize = titleSize
                                    )
                                )
                                Text(
                                    lastSignal,
                                    style = TextStyle(
                                        color = ColorProvider(Color.White),
                                        fontSize = (14 * factor).sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }

                            Spacer(modifier = GlanceModifier.width((30 * factor).dp))

                            Column {
                                Spacer(modifier = GlanceModifier.height((16 * factor).dp))
                                Text(
                                    "STATUS",
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF8E8E93)),
                                        fontSize = titleSize
                                    )
                                )
                                Text(
                                    res.actionTitle,
                                    style = TextStyle(
                                        color = ColorProvider(Color(res.actionColor)),
                                        fontSize = contextSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = GlanceModifier.height(2.dp))
                                Text(
                                    res.actionDesc,
                                    style = TextStyle(
                                        color = ColorProvider(Color(res.actionColor)),
                                        fontSize = subactSize
                                    )
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height((14 * factor).dp))

                        Text(
                            statusPrice,
                            style = TextStyle(
                                color = ColorProvider(statusColor),
                                fontSize = contextSize,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            statusRate,
                            style = TextStyle(
                                color = ColorProvider(rateColor),
                                fontSize = titleSize,
                                fontWeight = FontWeight.Bold
                            )
                        )

                        Spacer(modifier = GlanceModifier.height((21 * factor).dp))

                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        "DISP",
                                        style = TextStyle(
                                            color = ColorProvider(Color(0xFF8E8E93)),
                                            fontSize = (10 * factor).sp
                                        )
                                    )
                                    Spacer(modifier = GlanceModifier.width((6 * factor).dp))
                                    Text(
                                        text = String.format("%.1f%%", disparity),
                                        style = TextStyle(
                                            color = ColorProvider(Color.White),
                                            fontSize = (14 * factor).sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                Spacer(modifier = GlanceModifier.height((4 * factor).dp))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        "VOL",
                                        style = TextStyle(
                                            color = ColorProvider(Color(0xFF8E8E93)),
                                            fontSize = (10 * factor).sp
                                        )
                                    )
                                    Spacer(modifier = GlanceModifier.width((6 * factor).dp))
                                    Text(
                                        String.format("%.1f", res.vol20),
                                        style = TextStyle(
                                            color = ColorProvider(Color.White),
                                            fontSize = (14 * factor).sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            Box(
                                modifier = GlanceModifier
                                    .size((30 * factor).dp)
                                    .background(Color.White.copy(alpha = 0.15f))
                                    .cornerRadius((15 * factor).dp)
                                    .clickable(actionRunCallback<UpdateActionCallback>()),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    // R.drawable.ic_refresh는 프로젝트에 등록된 이미지 리소스 ID입니다.
                                    provider = ImageProvider(R.drawable.ic_refresh),
                                    contentDescription = "Refresh",
                                    modifier = GlanceModifier.size((16 * factor).dp) // 이미지 크기 조절
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun calculateMA(prices: List<Double>, period: Int, count: Int): List<Double> {
        if (prices.size < period) return emptyList()
        return List(count) { i ->
            val endIdx = prices.size - count + i
            prices.subList((endIdx - period + 1).coerceAtLeast(0), endIdx + 1).average()
        }
    }
}
