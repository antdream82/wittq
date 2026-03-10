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
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import kotlin.Double
import kotlin.collections.emptyList


class UpdateAcCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            Log.d("WITTQ_AGTQ_DEBUG", "Refresh button clicked")
            AGTQWidget().updateAll(context)
            Log.d("WITTQ_AGTQ_DEBUG", "Widget Refresh completed")
        } catch (e: Exception) {
            Log.e("WITTQ_AGTQ_DEBUG", "Widget Refresh failed: ${e.message}", e)
        }
    }
}

data class WidgetState(
    val res: AGTResult,
    val marketData: MarketData,
    val lastUpdate: String,
    val savedPrice: Double
)


class AGTQWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(300.dp, 100.dp), DpSize(412.dp, 150.dp))
    )


    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AGTQUpdateWorker.enqueue(context)

        val prefs = context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE)
        Log.d("WITTQ_DEBUG", "Prefs Path: " + prefs.all)
        val SavedPrice = ((prefs.getFloat("user_avg_price", 0.0f) * 10).toInt() / 10.0)
        Log.d("WITTQ_DEBUG", "User_avg_price: $SavedPrice")
        val userPos = prefs.getString("user_position", "TQQQ") ?: "TQQQ"

        val resultData = withContext(Dispatchers.IO) {
            try {
                val marketData = StockApiEngine.fetchMarketData("TQQQ") ?: return@withContext null
                val history = marketData.history
                val entryPrice = ((prefs.getFloat("agt_entry_price", 0.0f) * 10).toInt() / 10.0)
                val entryTime = prefs.getLong("agt_entry_time", 0L)
                val entryDays = if (entryTime > 0L) ((System.currentTimeMillis() - entryTime) / (1000 * 60 * 60 * 24)).toInt() else 0
                val res = AGTQStrategy.calc(
                    tqPrice = history, // [중요] 전략은 종가 기준
                    entryPrice = entryPrice,
                    entryDays = entryDays,
                    avgPrice = SavedPrice,
                    userPos = userPos
                )
                val currentPrice = marketData.currentPrice
                val chartDays = 90
                val tqMa200 = calculateMA(history+currentPrice, 200, chartDays)
                val tmChart = drawChart((history+currentPrice).takeLast(chartDays), tqMa200,
                    if (res.isbull) Color(0xFF30D158) else Color(0xFFFF453A), 400
                )

                prefs.edit {
                            if (res.agtscore == 2 && entryPrice == 0.0) {
                                // 신규 진입 조건 달성 시: 현재가와 현재 시간 저장
                                putFloat("agt_entry_price", res.tqqqPrice.toFloat())
                                putLong("agt_entry_time", System.currentTimeMillis())
                            } else if (res.isbear) {
                                // 200일선 이탈(스탑로스) 시: 진입 정보 초기화
                                putFloat("agt_entry_price", 0f)
                                putLong("agt_entry_time", 0L)
                            }
                        }
                Triple(res, tmChart, currentPrice)
            } catch (e: Exception) { null }
            }

        provideContent {
            val size = LocalSize.current
            val lastUpdate =
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            resultData?.let { (res, tmChart, currentPrice) ->
                // 여기서 tmChart와 currentPrice를 UI 함수에 전달합니다.
                AGTQWidgetUI(
                    res = res,
                    refreshTime = lastUpdate,
                    size = size,
                    SavedPrice = SavedPrice,
                    tmChart = tmChart,
                    currentPrice = currentPrice
                )
            } ?: run {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("updating...", style = TextStyle(color = ColorProvider(Color.White)))
                        val reftime =
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        Text(
                            reftime, style = TextStyle(
                                color = ColorProvider(Color.White.copy(alpha = 0.6f)),
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
        }
    }

    private fun drawChart(
        prices: List<Double>,
        ma2Line: List<Double>,
        color: Color,
        widgetWidth: Int,
        entryPrice: Double = 0.0,
        isStopLoss: Boolean = false
    ): Bitmap {
        val width = 400
        val height = 300
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pricePaint = Paint().apply {
            this.color = color.toArgb(); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
        }
        val ma2Paint = Paint().apply {
            this.color = 0xFFFFA400.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL; isAntiAlias = true; shader = LinearGradient(
            0f, 0f, 0f,
            height.toFloat(),
            color.toArgb(),
            android.graphics.Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        ); alpha = 65
        }

        val entryPaint = Paint().apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            isAntiAlias = true
        }

        val stopLossPaint = Paint().apply {
            this.color = 0xFFFF453A.toInt()
            this.textSize = 14f
            this.textAlign = Paint.Align.CENTER
            this.isFakeBoldText = true
        }

        val allValues = prices + ma2Line + if (entryPrice > 0) listOf(entryPrice) else emptyList()
        val max = allValues.maxOrNull() ?: 1.0
        val min = allValues.minOrNull() ?: 0.0
        val range = (max - min).coerceAtLeast(0.1)
        fun getY(v: Double) = height - ((v - min) / range * height).toFloat()

        if (entryPrice > 0) {
            val y = getY(entryPrice)
            canvas.drawLine(0f, y, width.toFloat(), y, entryPaint)

            val textPaint = Paint().apply {
                this.color = 0xFFFFFFFF.toInt()
                textSize = 20f
                isAntiAlias = true
            }
            canvas.drawText("\uD83D\uDE80", 0f, y + 20f, textPaint)
        }

        if (ma2Line.isNotEmpty()) {
            val ma2Path = Path()
            ma2Line.forEachIndexed { i, p ->
                val x = i.toFloat() * (width.toFloat() / (ma2Line.size - 1))
                if (i == 0) ma2Path.moveTo(x, getY(p)) else ma2Path.lineTo(x, getY(p))
            }
            canvas.drawPath(ma2Path, ma2Paint)
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

        if (isStopLoss && prices.isNotEmpty()) {
            canvas.drawText("❌", width / 2f, 50f, stopLossPaint)
        }

        return bitmap
    }

    @SuppressLint("DefaultLocale", "RestrictedApi")
    @Composable
    fun AGTQWidgetUI(
        res: AGTResult,
        refreshTime: String,
        size: DpSize,
        SavedPrice: Double,
        tmChart: Bitmap?,
        entryPrice: Double = 0.0,
        entryDays: Int = 0,
        currentPrice: Double
    ) {
        val factor = (size.width.value / 410f).coerceIn(0.6f, 1.0f)
        val hpadding = (30 * factor).dp
        val vpadding = (24 * factor).dp
        val isCash = res.userPos.uppercase() == "CASH"
        val usProfit = if (SavedPrice > 0) ((currentPrice - SavedPrice) / SavedPrice) * 100 else 0.0
        val userRate = if (isCash) "-" else "${if (usProfit >= 0) "+" else ""}${String.format("%.1f", usProfit)}%"
        val dis200Ma = (res.tqClose+currentPrice).takeLast(200).average()



        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1E))
                .cornerRadius(34.dp)
                .padding(horizontal = hpadding, vertical = vpadding)
        ){
            Row(modifier = GlanceModifier.fillMaxSize().padding(4.dp)
            ) {
                // 1. 왼쪽 2/3: 차트 영역
                Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    tmChart?.let {
                        Image(
                            provider = ImageProvider(it),
                            contentDescription = "TQ",
                            modifier = GlanceModifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width((15 * factor ).dp))

                // 2. 오른쪽 1/3: 정보 영역
                Column(modifier = GlanceModifier.width((130 * factor).dp).fillMaxHeight(), verticalAlignment = Alignment.Bottom) {
                    // 시그널 (강조)
                    Text(
                        text = "\uD83D\uDCCA 200MA AGiTQ",
                        style = TextStyle(
                            fontSize = (15 * factor).sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorProvider(Color.LightGray))
                        )

                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Column {
                        Text(
                            res.agtsignal,
                            style = TextStyle(
                                fontSize = (17 * factor).sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(Color(res.agtColor))
                            )
                        )
                        Text(
                            res.agtaction,
                            style = TextStyle(
                                fontSize = (14 * factor).sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(Color.White)
                            )
                        )
                        Text(
                            "\uD83D\uDECE\uFE0F $${String.format("%.2f", entryPrice)} / ${entryDays}",
                            style = TextStyle(
                                fontSize = (13 * factor).sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(Color.Gray)
                            )
                        )
                    }


                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        "$${SavedPrice} / ${userRate}",
                        style = TextStyle(fontSize = (13 * factor).sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color(0xFF0A84FF)))
                    )

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    // 수치 정보
                    Column {
                        InfoRow("TQ PRICE ", currentPrice)
                        InfoRow("MA200 ", dis200Ma)
                    }
                    // 새로고침 버튼
                    Row (modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Bottom) {
                        Text(
                            "Updated $refreshTime",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF8E8E93)),
                                fontSize = (11 * factor).sp
                            )
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Box(
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_refresh),
                                contentDescription = "Refresh",
                                modifier = GlanceModifier.size((15 * factor).dp)
                                    .clickable(actionRunCallback<UpdateAcCallback>())
                            )
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi", "DefaultLocale")
    @Composable
    fun InfoRow(label: String, value: Double) {
        val size = LocalSize.current
        val factor = (size.width.value / 410f).coerceIn(0.6f, 1.0f)
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(label, style = TextStyle(fontSize = (13 * factor).sp, color = ColorProvider(Color.Gray)))
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(String.format("%.2f", value), style = TextStyle(fontSize = (14 * factor).sp, color = ColorProvider(Color.White)))
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