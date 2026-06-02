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
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale
import kotlin.Double
import kotlin.collections.emptyList


class UpdateSnowCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            Log.d("WITTQ_Snow_DEBUG", "Refresh button clicked")
            SnowWidget().updateAll(context)
            Log.d("WITTQ_Snow_DEBUG", "Widget Refresh completed")
        } catch (e: Exception) {
            Log.e("WITTQ_Snow_DEBUG", "Widget Refresh failed: ${e.message}", e)
        }
    }
}

class SnowWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(300.dp, 100.dp), DpSize(412.dp, 150.dp))
    )

    private data class RecoveredSnowState(
        val signalPrice: Double,
        val entryTime: Long,
        val slPrice: Double,
        val slTime: Long,
        val dipPrice: Double,
        val dipTime: Long,
        val dip2Price: Double,
        val dip2Time: Long
    )


    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        SnowUpdateWorker.enqueue(context)

        val prefs = context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE)
        Log.d("WITTQ_DEBUG", "Prefs Path: " + prefs.all)
        val SavedPrice = ((prefs.getFloat("user_avg_price", 0.0f)*10).toInt() / 10.0)
        Log.d("WITTQ_DEBUG", "User_avg_price: $SavedPrice")
        val userPos = prefs.getString("user_position", "TQQQ") ?: "TQQQ"
        val persistedSignalPrice = ((prefs.getFloat("snow_entry_price", 0f) * 10).toInt() / 10.0)
        val persistedEntryTime = prefs.getLong("snow_entry_time", 0L)
        val persistedSlTime = prefs.getLong("snow_stop_time", 0L)
        val persistedSlPrice = ((prefs.getFloat("snow_stop_price", 0f) * 10).toInt() / 10.0)
        val persistedDipTime = prefs.getLong("snow_dip_time", 0L)
        val persistedDipPrice = ((prefs.getFloat("snow_dip_price", 0f) * 10).toInt() / 10.0)
        val persistedDip2Time = prefs.getLong("snow_dip2_time", 0L)
        val persistedDip2Price = ((prefs.getFloat("snow_dip2_price", 0f) * 10).toInt() / 10.0)

        var signalPrice = persistedSignalPrice
        var entryTime = persistedEntryTime
        val entryDays = if (entryTime > 0L) ((System.currentTimeMillis() - entryTime) / (1000 * 60 * 60 * 24)).toInt() else 0

        var slTime = persistedSlTime
        var slPrice = persistedSlPrice
        val daysSinceSl = if (slTime >0L) ((System.currentTimeMillis() - slTime) / (1000 * 60 * 60 * 24)).toInt() else 5
        var cooldownDays = maxOf(0, 5 - daysSinceSl)

        var dipPrice = persistedDipPrice
        var dip2Price = persistedDip2Price
        val dipAvgPrice = if (dipPrice > 0 || dip2Price > 0) {
            val ddipRatio = (if (dipPrice > 0) 20 else 0) + (if (dip2Price > 0) 50 else 0)
            ((dipPrice * 20) + (dip2Price * 50)) / ddipRatio.coerceAtLeast(1)
        } else 0.0


        val resultData = withContext(Dispatchers.IO) {
            try {
                val tqData = StockApiEngine.fetchMarketData(context, "TQQQ") ?: return@withContext null
                val qqData = StockApiEngine.fetchMarketData(context, "QQQ") ?: return@withContext null
                val tHistory = tqData.safeHistory()
                val qHistory = qqData.safeHistory()
                val recoveredState = if (persistedSignalPrice <= 0.0) {
                    recoverSnowState(
                        tHistory = tHistory,
                        tTimes = tqData.safeTimestamps(),
                        qHistory = qHistory,
                        qTimes = qqData.safeTimestamps()
                    )
                } else {
                    null
                }

                if (persistedSignalPrice <= 0.0 && recoveredState != null && recoveredState.signalPrice > 0) {
                    signalPrice = recoveredState.signalPrice
                    entryTime = recoveredState.entryTime
                }
                if (persistedSlTime <= 0L && recoveredState != null && recoveredState.slPrice > 0) {
                    slPrice = recoveredState.slPrice
                    slTime = recoveredState.slTime
                }
                if (persistedDipTime <= 0L && recoveredState != null && recoveredState.dipPrice > 0) {
                    dipPrice = recoveredState.dipPrice
                }
                if (persistedDip2Time <= 0L && recoveredState != null && recoveredState.dip2Price > 0) {
                    dip2Price = recoveredState.dip2Price
                }

                val effectiveEntryDays = if (entryTime > 0L) ((System.currentTimeMillis() - entryTime) / (1000 * 60 * 60 * 24)).toInt() else 0
                val effectiveCooldownDays = if (slTime > 0L) {
                    val daysSinceEffectiveSl = ((System.currentTimeMillis() - slTime) / (1000 * 60 * 60 * 24)).toInt()
                    maxOf(0, 5 - daysSinceEffectiveSl)
                } else {
                    0
                }

                val res = SnowStrategy.calc(
                    tHistory = tHistory,
                    qHistory = qHistory,
                    tqCurrent = tqData.currentPrice,
                    qqCurrent = qqData.currentPrice,
                    signalPrice = signalPrice,
                    entryDays = effectiveEntryDays,
                    avgPrice = SavedPrice,
                    usPos = userPos,
                    cooldownDays = effectiveCooldownDays,
                    slPrice = slPrice,
                    dipPrice = dipPrice,
                    dip2Price = dip2Price,
                    dipAvgPrice = dipAvgPrice,

                )
                val tqCurrent = tqData.currentPrice
                val qqCurrent = qqData.currentPrice
                val chartDays = 90
                val tqMa220 = calculateMA((tHistory+tqCurrent), 220, chartDays)
                val tqMa5 = calculateMA((tHistory+tqCurrent), 5, chartDays)


                val tmChart = drawChart(
                    prices = (tHistory+tqCurrent).takeLast(chartDays),
                    ma5Line = tqMa5,
                    ma220Line = tqMa220,
                    color = Color(res.snowColor),
                    entryPrice = signalPrice,
                    dipPrice = dipPrice,
                    dip2Price = dip2Price,
                    isStopLoss = res.isbear,
                    slPrice = slPrice
                )

                prefs.edit {
                            if (persistedSignalPrice <= 0.0 && recoveredState != null && recoveredState.signalPrice > 0) {
                                putFloat("snow_entry_price", recoveredState.signalPrice.toFloat())
                                putLong("snow_entry_time", recoveredState.entryTime)
                            }
                            if (persistedSlTime <= 0L && recoveredState != null && recoveredState.slPrice > 0) {
                                putFloat("snow_stop_price", recoveredState.slPrice.toFloat())
                                putLong("snow_stop_time", recoveredState.slTime)
                            }
                            if (persistedDipTime <= 0L && recoveredState != null && recoveredState.dipPrice > 0) {
                                putFloat("snow_dip_price", recoveredState.dipPrice.toFloat())
                                putLong("snow_dip_time", recoveredState.dipTime)
                            }
                            if (persistedDip2Time <= 0L && recoveredState != null && recoveredState.dip2Price > 0) {
                                putFloat("snow_dip2_price", recoveredState.dip2Price.toFloat())
                                putLong("snow_dip2_time", recoveredState.dip2Time)
                            }
                            if (res.isgc && effectiveCooldownDays == 0) {
                                if (signalPrice == 0.0) {
                                    putFloat("snow_entry_price", tHistory.last().toFloat())
                                    putLong("snow_entry_time", System.currentTimeMillis())
                                }
                            } else if (res.dip2Price > 0) {
                                putFloat("snow_dip2_price", tHistory.last().toFloat())
                                putLong("snow_dip2_time", System.currentTimeMillis())
                            } else if (res.dipPrice > 0){
                                putFloat("snow_dip_price", tHistory.last().toFloat())
                                putLong("snow_dip_time", System.currentTimeMillis())
                            } else if (res.slPrice > 0) {
                                // 200일선 이탈(스탑로스) 시: 진입 정보 초기화
                                putFloat("snow_entry_price", 0f)
                                putLong("snow_entry_time", 0L)
                                putFloat("snow_stop_price", tHistory.last().toFloat())
                                putLong("snow_stop_time", System.currentTimeMillis())
                            }
                        }
                Triple(res, tmChart, tqCurrent)
            } catch (e: Exception) { null }
            }

        provideContent {
            val size = LocalSize.current
            val lastUpdate = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())

            resultData?.let { (res, tmChart, tqCurrent) ->
                // 여기서 tmChart와 currentPrice를 UI 함수에 전달합니다.
                SnowWidgetUI(res, lastUpdate, size, SavedPrice, tmChart, signalPrice, entryDays, tqCurrent)
            } ?: run {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("updating...", style = TextStyle(color = ColorProvider(Color.White)))
                        val reftime =
                            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
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
        ma5Line: List<Double>,
        ma220Line: List<Double>,
        color: Color,
        entryPrice : Double = 0.0,
        dipPrice: Double = 0.0,
        dip2Price: Double = 0.0,
        dipAvgPrice: Double = 0.0,
        isStopLoss: Boolean = false,
        slPrice: Double = 0.0
    ): Bitmap {
        val width = 400
        val height = 200
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val pricePaint = Paint().apply {
            this.color = color.toArgb(); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
        }
        val ma5Paint = Paint().apply {
            this.color = 0xFFa4d2fe.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true
        }
        val ma220Paint = Paint().apply {
            this.color = 0xFFFFA400.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f; isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL; isAntiAlias = true
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), color.toArgb(), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
            alpha = 65
        }
        val linePaint = Paint().apply {
            this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
        }
        val iconPaint = Paint().apply {
            this.color = 0xFFFFFFFF.toInt(); textSize = 20f; isAntiAlias = true
        }

        val allValues = prices + ma220Line +ma5Line + listOfNotNull(
            if (entryPrice > 0) entryPrice else null,
            if (dipPrice > 0) dipPrice else null,
            if (dip2Price > 0) dip2Price else null,
            if (dipPrice > 0 && dip2Price > 0) dipAvgPrice else null,
            if (slPrice > 0) slPrice else null
        )
        val max = allValues.maxOrNull() ?: 1.0
        val min = allValues.minOrNull() ?: 0.0
        val range = (max - min).coerceAtLeast(0.1)
        fun getY(v: Double) = height - ((v - min) / range * height).toFloat()

        if (entryPrice > 0) {
            val y = getY(entryPrice)
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            canvas.drawText("\uD83D\uDCA1", 0f, y - 5f, iconPaint)
        }
        if (dipPrice > 0) {
            val y = getY(dipPrice)
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            canvas.drawText("❄1\uFE0F⃣", 0f, y - 5f, iconPaint)
        }
        if (dip2Price > 0) {
            val y = getY(dip2Price)
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            canvas.drawText("❄2\uFE0F⃣", 20f, y - 5f, iconPaint) // 겹치지 않게 옆으로 이동
        }
        if (dipPrice > 0 && dip2Price > 0) {
            val y = getY(dipAvgPrice)
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            canvas.drawText("❄\uFE0F⃣", 20f, y - 5f, iconPaint) // 겹치지 않게 옆으로 이동
        }

        if (isStopLoss) {
            val y = getY(slPrice)
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            canvas.drawText("\uD83D\uDEA8", 0f, y - 5f, iconPaint)
        }

        if (ma220Line.isNotEmpty()) {
            val ma220Path = Path()
            ma220Line.forEachIndexed { i, p ->
                val x = i.toFloat() * (width.toFloat() / (ma220Line.size - 1))
                if (i == 0) ma220Path.moveTo(x, getY(p)) else ma220Path.lineTo(x, getY(p))
            }
            canvas.drawPath(ma220Path, ma220Paint)
        }

        if (ma5Line.isNotEmpty()) {
            val ma5Path = Path()
            ma5Line.forEachIndexed { i, p ->
                val x = i.toFloat() * (width.toFloat() / (ma5Line.size - 1))
                if (i == 0) ma5Path.moveTo(x, getY(p)) else ma5Path.lineTo(x, getY(p))
            }
            canvas.drawPath(ma5Path, ma5Paint)
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
    fun SnowWidgetUI(
        res: SnowResult,
        refreshTime: String,
        size: DpSize,
        SavedPrice: Double,
        tmChart: Bitmap?,
        entryPrice: Double,
        entryDays: Int,
        currentPrice: Double,
    ) {
        val factor = (size.width.value / 410f).coerceIn(0.6f, 1.0f)
        val hpadding = (30 * factor).dp
        val vpadding = (24 * factor).dp

        val isCash = res.usPos.uppercase() == "CASH"
        val userRate = if (isCash) "-" else "${if (res.usProfit >= 0) "+" else ""}${String.format("%.1f", res.usProfit)}%"

        val dipAvgPrice = if (res.dipPrice > 0 || res.dip2Price > 0) {
            val totalRatio = (if (res.dipPrice > 0) 20 else 0) + (if (res.dip2Price > 0) 50 else 0)
            ((res.dipPrice * 20) + (res.dip2Price * 50)) / totalRatio.coerceAtLeast(1)
        } else 0.0

        val dif5ma = (res.tqPrice.takeLast(4)+currentPrice).average()
        val dif220ma = (res.tqPrice.takeLast(219)+currentPrice).average()
        val diff220 = if (dif5ma > 0) (dif5ma - dif220ma) / dif220ma * 100 else 0.0
        val q52w = (res.qqPrice.takeLast(251)+currentPrice).maxOrNull() ?: 0.0
        val diffq = if (q52w > 0) (res.qqCurrent - q52w) / q52w * 100 else 0.0
        val c220ma = (res.tqPrice.takeLast(219)+currentPrice).average()

        val activeText = when {
            entryPrice > 0 -> {
                val entryRate = (currentPrice - entryPrice) / entryPrice * 100
                "$${String.format("%.2f", entryPrice)} / ${if(entryRate >= 0) "+" else ""}${String.format("%.1f", entryRate)}% / ${entryDays}d"
            }
            dipAvgPrice > 0 -> {
                val dipRate = (currentPrice - dipAvgPrice) / dipAvgPrice * 100
                "DIP: $${String.format("%.2f", dipAvgPrice)} / ${if(dipRate >= 0) "+" else ""}${String.format("%.1f", dipRate)}%"
            }
            else -> ""
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1E))
                .cornerRadius(34.dp)
                .padding(horizontal = hpadding, vertical = vpadding)
        ){
            Row(modifier = GlanceModifier.fillMaxSize().padding(4.dp)
            ) {
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                // 1. 왼쪽 2/3: 차트 영역
                    Box(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                        tmChart?.let {
                            Image(
                                provider = ImageProvider(it),
                                contentDescription = "TQ",
                                modifier = GlanceModifier.fillMaxSize()
                            )
                        }
                    }
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Price: ${String.format("%.2f", currentPrice)}",
                            style = TextStyle(fontSize = (11 * factor).sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color.White))
                        )
                        Spacer(modifier = GlanceModifier.defaultWeight())
                        Text(
                            text = "220MA: ${String.format("%.2f", c220ma)}",
                            style = TextStyle(fontSize = (11 * factor).sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color(0xFFFFA400))) // MA 오렌지 색상 매칭
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width((15 * factor ).dp))

                // 2. 오른쪽 1/3: 정보 영역
                Column(modifier = GlanceModifier.width((130 * factor).dp).fillMaxHeight(), verticalAlignment = Alignment.Bottom) {
                    // 시그널 (강조)
                    Text(
                        text = "☃\uFE0FSnowball TQQQ",
                        style = TextStyle(
                            fontSize = (15 * factor).sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorProvider(Color.LightGray))
                        )

                    Spacer(modifier = GlanceModifier.defaultWeight())
                    val actionStr = if (res.cooldownDays > 0) "${res.snowaction} (\uD83D\uDEA6 ${res.cooldownDays}일)" else res.snowaction
                    Column {
                        Text(
                            res.snowsignal,
                            style = TextStyle(
                                fontSize = (17 * factor).sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(Color(res.snowColor))
                            )
                        )
                        Text(
                            actionStr,
                            style = TextStyle(
                                fontSize = (13 * factor).sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(Color.White)
                            )
                        )
                        if (activeText.isNotEmpty()) {
                            Text(
                                text = "\uD83D\uDECE\uFE0F $activeText",
                                style = TextStyle(fontSize = (11 * factor).sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color(res.snowColor)))
                            )
                        }
                        Text(
                            text = "\uD83D\uDCE5 $$entryPrice / $entryDays",
                            style = TextStyle(
                                fontSize = (12 * factor).sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(Color.Gray)
                            )
                        )
                    }


                    Spacer(modifier = GlanceModifier.defaultWeight())
                    Text(
                        text = "$$SavedPrice / $userRate",
                        style = TextStyle(fontSize = (12 * factor).sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color.LightGray))
                    )

                    Spacer(modifier = GlanceModifier.defaultWeight())

                    // 수치 정보
                    Column {
                        InfoRow("DISP ", "${String.format("%.1f", diff220)}%")
                        InfoRow("52W ", "${String.format("%.1f", diffq)}%")
                        InfoRow("RSI", String.format("%.1f", res.tqRSI))
                    }
                    // 새로고침 버튼
                    Row (modifier = GlanceModifier.defaultWeight().fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Text(
                            "Upd $refreshTime",
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF8E8E93)),
                                fontSize = (9 * factor).sp
                            )
                        )
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Image(
                            provider = ImageProvider(R.drawable.ic_refresh),
                            contentDescription = "Refresh",
                            modifier = GlanceModifier.size((14 * factor).dp)
                                .clickable(actionRunCallback<UpdateSnowCallback>())
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi", "DefaultLocale")
    @Composable
    fun InfoRow(label: String, valueStr: String) {
        val size = LocalSize.current
        val factor = (size.width.value / 410f).coerceIn(0.6f, 1.0f)
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(label, style = TextStyle(fontSize = (11 * factor).sp, color = ColorProvider(Color.Gray)))
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(valueStr, style = TextStyle(fontSize = (11 * factor).sp, color = ColorProvider(Color.White)))
        }
    }
    private fun calculateMA(prices: List<Double>, period: Int, count: Int): List<Double> {
        if (prices.size < period) return emptyList()
        return List(count) { i ->
            val endIdx = prices.size - count + i
            prices.subList((endIdx - period + 1).coerceAtLeast(0), endIdx + 1).average()
        }
    }

    private fun recoverSnowState(
        tHistory: List<Double>,
        tTimes: List<Long>,
        qHistory: List<Double>,
        qTimes: List<Long>
    ): RecoveredSnowState? {
        if (tHistory.size < 220 || qHistory.size < 220) return null
        val limit = minOf(tHistory.size, tTimes.size, qHistory.size, qTimes.size)
        if (limit < 220) return null

        var signalPrice = 0.0
        var entryTime = 0L
        var slPrice = 0.0
        var slTime = 0L
        var dipPrice = 0.0
        var dipTime = 0L
        var dip2Price = 0.0
        var dip2Time = 0L

        for (i in 219 until limit) {
            val tPrefix = tHistory.take(i + 1)
            val qPrefix = qHistory.take(i + 1)
            val currentCooldownDays = if (slTime > 0L) {
                val daysSinceSl = ((tTimes[i] - slTime) / (1000 * 60 * 60 * 24)).toInt()
                maxOf(0, 5 - daysSinceSl)
            } else {
                0
            }

            val dipAvgPrice = if (dipPrice > 0 || dip2Price > 0) {
                val ddipRatio = (if (dipPrice > 0) 20 else 0) + (if (dip2Price > 0) 50 else 0)
                ((dipPrice * 20) + (dip2Price * 50)) / ddipRatio.coerceAtLeast(1)
            } else 0.0

            val res = SnowStrategy.calc(
                tHistory = tPrefix,
                qHistory = qPrefix,
                tqCurrent = tPrefix.last(),
                qqCurrent = qPrefix.last(),
                entryDays = if (entryTime > 0L) ((tTimes[i] - entryTime) / (1000 * 60 * 60 * 24)).toInt() else 0,
                cooldownDays = currentCooldownDays,
                avgPrice = 0.0,
                usPos = "TQQQ",
                slPrice = slPrice,
                dipPrice = dipPrice,
                dip2Price = dip2Price,
                dipAvgPrice = dipAvgPrice,
                signalPrice = signalPrice
            )

            if (res.isgc && currentCooldownDays == 0) {
                if (signalPrice == 0.0) {
                    signalPrice = tPrefix.last()
                    entryTime = tTimes[i]
                }
            } else if (res.dip2Price > 0) {
                dip2Price = tPrefix.last()
                dip2Time = tTimes[i]
            } else if (res.dipPrice > 0) {
                dipPrice = tPrefix.last()
                dipTime = tTimes[i]
            } else if (res.slPrice > 0) {
                signalPrice = 0.0
                entryTime = 0L
                slPrice = tPrefix.last()
                slTime = tTimes[i]
            }
        }

        return if (signalPrice > 0 || slPrice > 0 || dipPrice > 0 || dip2Price > 0) {
            RecoveredSnowState(signalPrice, entryTime, slPrice, slTime, dipPrice, dipTime, dip2Price, dip2Time)
        } else {
            null
        }
    }
}
