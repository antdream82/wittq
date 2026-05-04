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
    companion object {
        private const val VOL_RISK_LIMIT = 5.5
        private const val KEY_SIGNAL_ENTRY_PRICE = "last_signal_entry_price"
        private const val KEY_HAD_FORCE_EXIT = "had_force_exit"
        private const val KEY_LAST_FORCE_EXIT_TIME = "last_force_exit_time"
        private const val KEY_USER_POSITION = "user_position"
        private const val KEY_LAST_RATIO = "last_ratio"
        private const val KEY_LAST_SIGNAL_DESC = "last_signal_desc"
        private const val KEY_VIX_LOCK = "vix_lock"
        private const val KEY_VIX_CALM_DAYS = "vix_calm_days"
    }

    // 3. SizeMode 적용: 기기별 다양한 4x2 사이즈에 대응
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(300.dp, 100.dp), DpSize(412.dp, 150.dp))
    )

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        StockUpdateWorker.enqueue(context)

        val prefs = context.getSharedPreferences("StockPrefs", Context.MODE_PRIVATE)
        val userPosition = prefs.getString(KEY_USER_POSITION, "TQQQ") ?: "TQQQ"

        val savedSignalEntryPrice = prefs.getFloat(KEY_SIGNAL_ENTRY_PRICE, 0f)
        val persistedSignalEntryPrice = ((savedSignalEntryPrice * 10).toInt() / 10.0)
        val hadForceExit = prefs.getBoolean(KEY_HAD_FORCE_EXIT, false)
        val lastForceExitTime = prefs.getLong(KEY_LAST_FORCE_EXIT_TIME, 0L)
        val vixLock = prefs.getBoolean(KEY_VIX_LOCK, false)
        val vixCalmDays = prefs.getInt(KEY_VIX_CALM_DAYS, 0)
        var signalDesc: String = prefs.getString(KEY_LAST_SIGNAL_DESC, "-") ?: "-"
        var effectiveSignalEntryPrice = 0.0
        var effectiveLastRatio = 0

        val resultdata = withContext(Dispatchers.IO) {
            try {
                val tqData = StockApiEngine.fetchMarketData(context, "TQQQ") ?: return@withContext null
                val qData = StockApiEngine.fetchMarketData(context, "QQQ") ?: return@withContext null
                val spyData = StockApiEngine.fetchMarketData(context, "SPY") ?: return@withContext null
                val vixData = StockApiEngine.fetchMarketData(context, "^VIX") ?: return@withContext null
                val tHis = tqData.safeHistory()
                val qHis = qData.safeHistory()
                val spyHis = spyData.safeHistory()
                val vixHis = vixData.safeHistory()

                if (tHis.isEmpty() || qHis.isEmpty() || spyHis.isEmpty() || vixHis.isEmpty()) {
                    Log.e("WITTQ_DEBUG", "Price data is empty")
                    throw Exception("Data empty")
                }

                val recoveredState = if (userPosition == "CASH") {
                    null
                } else {
                    recoverHistoricalSignalState(qData, tqData, spyData, vixData)
                }

                effectiveSignalEntryPrice = if (userPosition == "CASH") {
                    0.0
                } else {
                    recoveredState?.signalEntryPrice ?: persistedSignalEntryPrice
                }
                val effectiveHadForceExit = if (userPosition == "CASH") {
                    false
                } else {
                    recoveredState?.hadForceExit ?: hadForceExit
                }
                val effectiveLastForceExitTime = if (userPosition == "CASH") {
                    0L
                } else {
                    recoveredState?.lastForceExitTime ?: lastForceExitTime
                }
                effectiveLastRatio = if (userPosition == "CASH") {
                    0
                } else {
                    recoveredState?.lastRatio ?: prefs.getInt(KEY_LAST_RATIO, 0)
                }
                val effectiveVixLock = if (userPosition == "CASH") {
                    false
                } else {
                    recoveredState?.vixLock ?: vixLock
                }
                val effectiveVixCalmDays = if (userPosition == "CASH") {
                    0
                } else {
                    recoveredState?.vixCalmDays ?: vixCalmDays
                }
                signalDesc = if (userPosition == "CASH") {
                    "-"
                } else {
                    recoveredState?.lastSignalDesc
                        ?: prefs.getString(KEY_LAST_SIGNAL_DESC, "-")
                        ?: "-"
                }
                val hasSignalBasis = effectiveSignalEntryPrice > 0

                val result = TqqqAlgorithm.calculate(
                    qPrices = qHis,
                    tPrices = tHis,
                    spyPrices = spyHis,
                    vixPrices = vixHis,
                    userPosition = userPosition,
                    lastSignalEntryPrice = effectiveSignalEntryPrice,
                    hadForceExit = effectiveHadForceExit,
                    lastForceExitTime = effectiveLastForceExitTime,
                    vixLock = effectiveVixLock,
                    vixCalmDays = effectiveVixCalmDays,
                    currentTimeMs = System.currentTimeMillis()
                )

                val currentRatio = result.targetRatio
                val enteredAny = effectiveLastRatio == 0 && currentRatio > 0 && !hasSignalBasis
                val exitedToCash = effectiveLastRatio > 0 && currentRatio == 0
                val wasTqqqTier = effectiveLastRatio >= 80
                val wasTwoThirds = effectiveLastRatio == 67
                val toTwoThirds = currentRatio == 67
                val toCash = currentRatio == 0
                val meaningfulReduce = (wasTqqqTier && toTwoThirds) || (wasTwoThirds && toCash) || (wasTqqqTier && toCash)
                val forceExitNow = exitedToCash && (result.actionTitle == "ESCAPE" || result.actionTitle == "STOP")
                val cooldownTrigger = meaningfulReduce || forceExitNow
                val cooldownDone = effectiveHadForceExit && result.cooldownDaysLeft == 0 && result.rsi >= 43
                val now = System.currentTimeMillis()
                val nextHadForceExit = when {
                    enteredAny -> false
                    cooldownTrigger -> true
                    cooldownDone -> false
                    else -> effectiveHadForceExit
                }

                if (effectiveLastRatio != currentRatio) {
                    signalDesc = "${ratioLabel(effectiveLastRatio)} -> ${ratioLabel(currentRatio)}"
                }

                prefs.edit {
                    putInt(KEY_LAST_RATIO, currentRatio)
                    putString(KEY_LAST_SIGNAL_DESC, signalDesc)

                    if (recoveredState != null && recoveredState.signalEntryPrice > 0 && userPosition != "CASH") {
                        putFloat(KEY_SIGNAL_ENTRY_PRICE, recoveredState.signalEntryPrice.toFloat())
                        putBoolean(KEY_HAD_FORCE_EXIT, recoveredState.hadForceExit)
                        putLong(KEY_LAST_FORCE_EXIT_TIME, recoveredState.lastForceExitTime)
                    }

                    if (recoveredState != null && userPosition != "CASH") {
                        putBoolean(KEY_VIX_LOCK, recoveredState.vixLock)
                        putInt(KEY_VIX_CALM_DAYS, recoveredState.vixCalmDays)
                    }

                    if (enteredAny) {
                        putFloat(KEY_SIGNAL_ENTRY_PRICE, result.currentPrice.toFloat())
                        putBoolean(KEY_HAD_FORCE_EXIT, false)
                        putLong(KEY_LAST_FORCE_EXIT_TIME, 0L)
                    } else if (cooldownTrigger) {
                        putBoolean(KEY_HAD_FORCE_EXIT, true)
                        putLong(KEY_LAST_FORCE_EXIT_TIME, now)
                    } else if (cooldownDone) {
                        putBoolean(KEY_HAD_FORCE_EXIT, false)
                        putLong(KEY_LAST_FORCE_EXIT_TIME, 0L)
                    }

                    putBoolean(KEY_VIX_LOCK, result.isVixLock)
                    putInt(KEY_VIX_CALM_DAYS, result.vixCalmDays)

                    if (currentRatio == 0 && !nextHadForceExit) {
                        putFloat(KEY_SIGNAL_ENTRY_PRICE, 0f)
                    }

                    putString(KEY_USER_POSITION, result.displayPosition)
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
                context.getSharedPreferences("YahooCache", Context.MODE_PRIVATE)
                    .edit { putString("last_yahoo_error", "Widget update failed: ${e.message ?: "unknown error"}") }
                null
            }
        }

        val lastUpdate =
            SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        Log.d("WITTQ_DEBUG", "Widget updated at: $lastUpdate")

        provideContent {
            val size = LocalSize.current
            val lastError = StockApiEngine.getLastError(context)?.takeIf { it.isNotBlank() }

            if (resultdata != null) {
                val (result, tChart, qChart) = resultdata
                WidgetContent(
                    result,
                    tChart,
                    qChart,
                    lastUpdate,
                    size,
                    signalDesc,
                    effectiveSignalEntryPrice
                )
            } else {
                val fallbackError = lastError ?: "Widget update failed"
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Updating...", style = TextStyle(color = ColorProvider(Color.White)))
                        if (lastError != null) {
                            Text(
                                lastError,
                                style = TextStyle(
                                    color = ColorProvider(Color.White.copy(alpha = 0.75f)),
                                    fontSize = 9.sp
                                )
                            )
                        } else {
                            Text(
                                fallbackError,
                                style = TextStyle(
                                    color = ColorProvider(Color.White.copy(alpha = 0.75f)),
                                    fontSize = 9.sp
                                )
                            )
                        }
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
        lastSignal: String,
        signalEntryPrice: Double
    ) {
        val factor = (size.width.value / 410f).coerceIn(0.6f, 1.0f)
        val hpadding = (40 * factor).dp
        val vpadding = (16 * factor).dp

        val isCooling = res.actionTitle == "Cooling"
        val isSpyRisk = res.actionTitle == "ESCAPE" && res.actionDesc == "SPY Weak"
        val isVixRisk = res.isVixPanic || res.isVixLock
        val qqqState = if (res.isQqqBullish) "BULL" else "BEAR"
        val tqqqState = if (res.isTqqqBullish) "BULL" else "BEAR"
        val spyState = if (isSpyRisk) "RISK" else "SAFE"
        val hasLastMove = lastSignal != "-"
        val targetSubLabel = if (res.targetRatio > 0) "TQQQ exposure" else "No position"
        val actionLabel = if (res.targetRatio > 0 && res.actionTitle == "HOLD" && hasLastMove) "CHANGE" else "ACTION"
        val actionDisplayTitle = when {
            res.targetRatio > 0 && res.actionTitle == "HOLD" && hasLastMove -> lastSignal
            res.targetRatio > 0 && res.actionTitle == "HOLD" -> "TARGET"
            else -> res.actionTitle
        }
        val actionDisplayDesc = when {
            res.targetRatio > 0 && res.actionTitle == "HOLD" && hasLastMove -> ""
            res.targetRatio > 0 && res.actionTitle == "HOLD" -> "No change"
            else -> res.actionDesc
        }
        val cooldownState = when {
            isCooling && res.cooldownDaysLeft > 0 -> "${res.cooldownDaysLeft}D left"
            isCooling -> "COOL"
            else -> "OFF"
        }
        val finalPosColor = Color(res.actionColor)
        val grayColor = Color(0xFF8E8E93)

        val labelSize = (10 * factor).sp
        val valueSize = (13 * factor).sp
        val actionSize = (16 * factor).sp

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
                                text = res.marketStatus,
                                style = TextStyle(
                                    color = ColorProvider(finalPosColor),
                                    fontSize = (40 * factor).sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = GlanceModifier.width(6.dp))

                            Column {
                                Spacer(modifier = GlanceModifier.height(4.dp))
                                Text(
                                    "TARGET ($updateTime)",
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF8E8E93)),
                                        fontSize = (12 * factor).sp
                                    )
                                )
                                Text(
                                    targetSubLabel,
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF8E8E93)),
                                        fontSize = (16 * factor).sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height((7 * factor).dp))
                        Text(
                            "TQQQ 200MA",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF8E8E93)),
                                fontSize = (9 * factor).sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        tChart?.let {
                            Image(
                                provider = ImageProvider(it),
                                contentDescription = null,
                                modifier = GlanceModifier.fillMaxWidth().height((44 * factor).dp)
                            )
                        }
                        Spacer(modifier = GlanceModifier.height((6 * factor).dp))
                        Text(
                            "QQQ 3/161",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF8E8E93)),
                                fontSize = (9 * factor).sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        qChart?.let {
                            Image(
                                provider = ImageProvider(it),
                                contentDescription = null,
                                modifier = GlanceModifier.fillMaxWidth().height((44 * factor).dp)
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.width((16 * factor).dp))

                    // --- [우측 섹션] ---
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("VOL", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    String.format("%.2f%%", res.vol20),
                                    style = TextStyle(color = ColorProvider(if (res.vol20 >= VOL_RISK_LIMIT) Color(0xFFFF453A) else Color(0xFF30D158)), fontSize = valueSize, fontWeight = FontWeight.Bold)
                                )
                            }
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("SPY Risk", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    spyState,
                                    style = TextStyle(color = ColorProvider(if (isSpyRisk) Color(0xFFFF453A) else Color(0xFF30D158)), fontSize = valueSize, fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height((8 * factor).dp))

                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("QQQ 3/161", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    qqqState,
                                    style = TextStyle(color = ColorProvider(if (res.isQqqBullish) Color(0xFF30D158) else Color(0xFFFF453A)), fontSize = valueSize, fontWeight = FontWeight.Bold)
                                )
                            }
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("TQQQ 200MA", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    tqqqState,
                                    style = TextStyle(color = ColorProvider(if (res.isTqqqBullish) Color(0xFF30D158) else Color(0xFFFF453A)), fontSize = valueSize, fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height((8 * factor).dp))

                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(actionLabel, style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    actionDisplayTitle,
                                    style = TextStyle(color = ColorProvider(Color(res.actionColor)), fontSize = actionSize, fontWeight = FontWeight.Bold)
                                )
                                if (actionDisplayDesc.isNotEmpty()) {
                                    Text(
                                        actionDisplayDesc,
                                        style = TextStyle(color = ColorProvider(Color(res.actionColor)), fontSize = (9 * factor).sp)
                                    )
                                }
                            }
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("Cooldown", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    cooldownState,
                                    style = TextStyle(color = ColorProvider(if (isCooling) Color(0xFF8E8E93) else Color(0xFF30D158)), fontSize = valueSize, fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height((8 * factor).dp))
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("PRICE", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    "$${String.format("%.2f", res.currentPrice)}",
                                    style = TextStyle(
                                        color = ColorProvider(Color.White),
                                        fontSize = valueSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("ENTRY", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    if (signalEntryPrice > 0) "$${String.format("%.2f", signalEntryPrice)}" else "NA",
                                    style = TextStyle(
                                        color = ColorProvider(Color.White),
                                        fontSize = valueSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height((8 * factor).dp))
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("DISP", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    String.format("%.1f%%", res.disparity),
                                    style = TextStyle(
                                        color = ColorProvider(Color.White),
                                        fontSize = valueSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text("VIX", style = TextStyle(color = ColorProvider(grayColor), fontSize = labelSize))
                                Text(
                                    if (res.vixClose > 0) String.format("%.1f", res.vixClose) else "NA",
                                    style = TextStyle(
                                        color = ColorProvider(if (isVixRisk) Color(0xFFFF453A) else Color.White),
                                        fontSize = valueSize,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                        Spacer(modifier = GlanceModifier.height((8 * factor).dp))
                        Box(
                            modifier = GlanceModifier
                                .size((30 * factor).dp)
                                .background(Color.White.copy(alpha = 0.15f))
                                .cornerRadius((15 * factor).dp)
                                .clickable(actionRunCallback<UpdateActionCallback>()),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_refresh),
                                contentDescription = "Refresh",
                                modifier = GlanceModifier.size((16 * factor).dp)
                            )
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

    private data class HistoricalSeries(
        val timestamps: List<Long>,
        val qPrices: List<Double>,
        val tPrices: List<Double>,
        val spyPrices: List<Double>,
        val vixPrices: List<Double>
    )

    private data class RecoveredSignalState(
        val signalEntryPrice: Double,
        val hadForceExit: Boolean,
        val lastForceExitTime: Long,
        val lastRatio: Int,
        val lastSignalDesc: String,
        val vixLock: Boolean,
        val vixCalmDays: Int
    )

    private fun recoverHistoricalSignalState(
        qData: MarketData,
        tData: MarketData,
        spyData: MarketData,
        vixData: MarketData
    ): RecoveredSignalState? {
        val series = alignHistoricalSeries(qData, tData, spyData, vixData) ?: return null
        if (series.timestamps.size < 200) return null

        var signalEntryPrice = 0.0
        var hadForceExit = false
        var lastForceExitTime = 0L
        var lastRatio = 0
        var lastSignalDesc = "-"
        var vixLock = false
        var vixCalmDays = 0

        for (index in series.timestamps.indices) {
            val result = TqqqAlgorithm.calculate(
                qPrices = series.qPrices.take(index + 1),
                tPrices = series.tPrices.take(index + 1),
                spyPrices = series.spyPrices.take(index + 1),
                vixPrices = series.vixPrices.take(index + 1),
                userPosition = "TQQQ",
                lastSignalEntryPrice = signalEntryPrice,
                hadForceExit = hadForceExit,
                lastForceExitTime = lastForceExitTime,
                vixLock = vixLock,
                vixCalmDays = vixCalmDays,
                currentTimeMs = series.timestamps[index]
            )

            val currentRatio = result.targetRatio
            val enteredAny = lastRatio == 0 && currentRatio > 0
            val exitedToCash = lastRatio > 0 && currentRatio == 0
            val wasTqqqTier = lastRatio >= 80
            val wasTwoThirds = lastRatio == 67
            val toTwoThirds = currentRatio == 67
            val toCash = currentRatio == 0
            val meaningfulReduce = (wasTqqqTier && toTwoThirds) || (wasTwoThirds && toCash) || (wasTqqqTier && toCash)
            val forceExitNow = exitedToCash && (result.actionTitle == "ESCAPE" || result.actionTitle == "STOP")
            val cooldownTrigger = meaningfulReduce || forceExitNow
            val cooldownDone = hadForceExit && result.cooldownDaysLeft == 0 && result.rsi >= 43
            val nextHadForceExit = when {
                enteredAny -> false
                cooldownTrigger -> true
                cooldownDone -> false
                else -> hadForceExit
            }

            if (lastRatio != currentRatio) {
                lastSignalDesc = "${ratioLabel(lastRatio)} -> ${ratioLabel(currentRatio)}"
            }

            if (enteredAny) {
                signalEntryPrice = result.currentPrice
                hadForceExit = false
                lastForceExitTime = 0L
            } else if (cooldownTrigger) {
                hadForceExit = true
                lastForceExitTime = series.timestamps[index]
            } else if (cooldownDone) {
                hadForceExit = false
                lastForceExitTime = 0L
            }

            if (currentRatio == 0 && !nextHadForceExit) {
                signalEntryPrice = 0.0
            }

            vixLock = result.isVixLock
            vixCalmDays = result.vixCalmDays
            lastRatio = currentRatio
        }

        return RecoveredSignalState(
            signalEntryPrice = signalEntryPrice,
            hadForceExit = hadForceExit,
            lastForceExitTime = lastForceExitTime,
            lastRatio = lastRatio,
            lastSignalDesc = lastSignalDesc,
            vixLock = vixLock,
            vixCalmDays = vixCalmDays
        )
    }

    private fun alignHistoricalSeries(
        qData: MarketData,
        tData: MarketData,
        spyData: MarketData,
        vixData: MarketData
    ): HistoricalSeries? {
        val qHistory = qData.safeHistory()
        val tHistory = tData.safeHistory()
        val spyHistory = spyData.safeHistory()
        val vixHistory = vixData.safeHistory()
        val qTimestamps = qData.safeTimestamps()
        val tTimestamps = tData.safeTimestamps()
        val spyTimestamps = spyData.safeTimestamps()
        val vixTimestamps = vixData.safeTimestamps()

        if (qHistory.isEmpty() || tHistory.isEmpty() || spyHistory.isEmpty() || vixHistory.isEmpty()) return null
        if (qTimestamps.isEmpty() || tTimestamps.isEmpty() || spyTimestamps.isEmpty() || vixTimestamps.isEmpty()) return null

        val qMap = qTimestamps.zip(qHistory).toMap()
        val tMap = tTimestamps.zip(tHistory).toMap()
        val spyMap = spyTimestamps.zip(spyHistory).toMap()
        val vixMap = vixTimestamps.zip(vixHistory).toMap()

        val commonTimes = qMap.keys
            .intersect(tMap.keys)
            .intersect(spyMap.keys)
            .intersect(vixMap.keys)
            .toList()
            .sorted()

        if (commonTimes.isEmpty()) return null

        val qPrices = commonTimes.mapNotNull { qMap[it] }
        val tPrices = commonTimes.mapNotNull { tMap[it] }
        val spyPrices = commonTimes.mapNotNull { spyMap[it] }
        val vixPrices = commonTimes.mapNotNull { vixMap[it] }

        if (
            qPrices.size != commonTimes.size ||
            tPrices.size != commonTimes.size ||
            spyPrices.size != commonTimes.size ||
            vixPrices.size != commonTimes.size
        ) {
            return null
        }

        return HistoricalSeries(commonTimes, qPrices, tPrices, spyPrices, vixPrices)
    }

    private fun ratioLabel(ratio: Int): String = when (ratio) {
        100 -> "TQQQ"
        95 -> "95%"
        90 -> "90%"
        80 -> "80%"
        67 -> "2/3"
        5 -> "5%"
        0 -> "CASH"
        else -> "${ratio}%"
    }

}
