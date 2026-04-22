package com.fortq.wittq

import kotlin.math.pow
import kotlin.math.sqrt
import android.content.Context
import android.content.SharedPreferences


data class AlgoResult(
    val score: Int,
    val marketStatus: String,
    val actionTitle: String,
    val actionDesc: String,
    val actionColor: Long,
    val disparity: Double,
    val vol20: Double,
    val targetRatio: Int,
    val signalChangeDesc: String? = null,
    val rsi: Double,
    val displayPosition: String,
    val userPosition: String,
    val currentPrice: Double,
    val profitRate: Double,
    val isTqqqBullish: Boolean,
    val isQqqBullish: Boolean,
    val cooldownDaysLeft: Int = 0,
)

data class LinRegResult(
    val slope: Double,
    val intercept: Double,
    val startY: Double,
    val endY: Double
)

// AGTQ Data
data class AGTResult(
    val tq200: Double,
    val agtscore: Int,
    val tqClose: List<Double>,
    val tqqqPrice: Double,
    val tqqqPrevClose: Double,
    val stopLoss: Double,
    val agtsignal: String,
    val agtaction: String,
    val agtColor: Long,
    val isbull: Boolean,
    val isbear: Boolean,
    val tqqqRatio: Int,
    val otherRatio: String,
    val avgPrice: Double = 0.0,
    val userProfit : Double,
    val userPos: String
)

object AGTQStrategy {
    fun calc(tqPrice: List<Double>, entryPrice: Double = 0.0, entryDays: Int = 0, avgPrice: Double = 0.0, userPos: String): AGTResult {
        val bullColor = 0xFF30D158
        val bearColor = 0xFFFF453A
        val grayColor = 0xFF8E8E93
        val purpleColor = 0xFFBF5AF2
        val blueColor = 0xFF0A84FF

        val tqCurrent = tqPrice.lastOrNull() ?: 0.0
        val tq200 = tqPrice.takeLast(200).average()
        val tqPrev = if (tqPrice.size >= 2) tqPrice[tqPrice.size - 2] else tqCurrent
        val tq200prev = tqPrice.takeLast(201).dropLast(1).average()
        val tq2Prev = if (tqPrice.size >= 3) tqPrice[tqPrice.size - 3] else tqCurrent
        val tq200prev2 = tqPrice.takeLast(202).dropLast(2).average()
        val isgc = tqPrice[tqPrice.size - 4] < tqPrice.takeLast(203).dropLast(3).average()


        var agtscore = 0
        if (isgc && tqCurrent >= tq200 && tqPrev >= tq200prev && tq2Prev >= tq200prev2) {
            agtscore = 2
        } else if (tqCurrent >= tq200) {
            agtscore = 1
        } else agtscore == 0

        val isStopLoss = tqCurrent < tq200
        val isbull = (agtscore == 2) || (entryPrice > 0.0 && !isStopLoss)
        val isbear = agtscore == 0



        val profitRate = if (entryPrice > 0) (tqCurrent - entryPrice) / entryPrice else 0.0

        var tqqqRatio = 100
        var actionNote = ""

        if (entryPrice >0) {
            tqqqRatio = when {
                profitRate >= 6.00 -> 0
                profitRate >= 5.00 -> 2
                profitRate >= 4.00 -> 4
                profitRate >= 3.00 -> 9
                profitRate >= 2.00 -> 18
                profitRate >= 1.00 -> 36
                profitRate >= 0.50 -> 73
                profitRate >= 0.25 -> 81
                profitRate >= 0.10 -> 90
                else -> 100
            }
            if (tqqqRatio < 100) actionNote = "(분할)"
        }


        val (agtsignal, agtaction, agtColor) = when {
            //MA200 이탈 시 약세
            isStopLoss -> {
                Triple("졸업 \uD83D\uDE07", "전량 SGOV", bearColor)
            }

            agtscore == 2 || entryPrice > 0 -> {
                if (entryDays >= 1) {
                    Triple("중도입학 \uD83E\uDD17", "SPYM/SGOV", blueColor)
                } else {
                    Triple("TQ입학 \uD83D\uDEF8", "TQQQ ${tqqqRatio}%"+'\n'+"$actionNote", bullColor)
                }
            }
            else -> Triple("예비소집 \uD83E\uDD14", "관심 필요", purpleColor)
        }

        val userProfit = if (avgPrice > 0) ((tqCurrent - avgPrice) / avgPrice) * 100 else 0.0

        return AGTResult(
            tq200 = tq200,
            agtscore = agtscore,
            tqClose = tqPrice,
            tqqqPrice = tqCurrent,
            tqqqPrevClose = tqPrev,
            stopLoss = tq200,
            agtsignal = agtsignal,
            agtaction = agtaction,
            agtColor = agtColor,
            isbull = isbull,
            isbear = isStopLoss,
            tqqqRatio = if (isStopLoss) 0 else tqqqRatio,
            otherRatio = if (isStopLoss) "SGOV 100%" else if (entryDays >= 1) "SPYM/SGOV 50%" else "TQQQ 100%",
            userProfit = userProfit,
            userPos = userPos
        )
    }
}


object TqqqAlgorithm {
    private const val VOL_RISK_LIMIT = 5.8
    private const val SPY_RISK_RATIO = 0.974
    private const val DRAWDOWN_STOP_RATIO = 0.942
    private const val ENTRY100_DISP = 101.0
    private const val SPECIAL_SLOPE_TH = 0.11
    private const val SPECIAL_DISP_MAX = 98.8
    private const val SPECIAL_VOL_MAX = 6.0
    private const val TRIM90_DISP = 139.0
    private const val TRIM80_DISP = 146.0
    private const val TRIM5_DISP = 149.0
    private const val EXIT_DISP = 151.0
    private const val PROFIT_TRIM_RATIO = 1.10
    private const val COOLDOWN_DAYS = 7
    private const val COOLDOWN_RSI = 43.0

    fun calculate(
        qPrices: List<Double>,
        tPrices: List<Double>,
        spyPrices: List<Double>,
        userPosition: String,
        lastSignalEntryPrice: Double = 0.0,
        hadForceExit: Boolean = false,
        lastForceExitTime: Long = 0L,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): AlgoResult {
        val tqqqCurrent = tPrices.lastOrNull() ?: 0.0
        val spyCurrent = spyPrices.lastOrNull() ?: 0.0

        val tqqqMA200 = tPrices.takeLast(200).average()
        val spyMA200 = spyPrices.takeLast(200).average()
        val qqqMA3 = qPrices.takeLast(3).average()
        val qqqMA161 = qPrices.takeLast(161).average()

        val disparityTQQQ = (tqqqCurrent / tqqqMA200) * 100
        val disparitySPY = (spyCurrent / spyMA200) * 100
        val vol20 = calculateVolatility(tPrices, 20)
        val qqqRsi = calculateRSI(qPrices, 14)

        val tqMA200List = calculateMA(tPrices, 200, 45)
        val tqPriceList = tPrices.takeLast(45)
        val disparityList = tqPriceList.zip(tqMA200List) { price, ma -> (price / ma) * 100 }

        val tqDisSlopeResult = calculateSlope(disparityList)
        val tqDisSlope = tqDisSlopeResult.slope

        // 상태 설정
        var targetRatio = 0
        var actionTitle: String
        var actionDesc: String
        var actionColor: Long = 0xFF8E8E93

        // [작동 우선 순위 1, 2, 3] 강제 탈출 조건
        val isReady = !qqqRsi.isNaN() &&
            qPrices.size >= 161 &&
            tPrices.size >= 200 &&
            spyPrices.size >= 200

        val isTqqqBullish = disparityTQQQ >= ENTRY100_DISP
        val isQqqBullish = qqqMA3 > qqqMA161
        val isVolatilityRisk = vol20 >= VOL_RISK_LIMIT
        val isSpyDisparityRisk = disparitySPY <= SPY_RISK_RATIO
        val isDrawdownRisk = if (lastSignalEntryPrice > 0) {
            tqqqCurrent <= lastSignalEntryPrice * DRAWDOWN_STOP_RATIO
        } else false

        val cooldownMillis = COOLDOWN_DAYS * 24L * 60L * 60L * 1000L
        val coolingActive = hadForceExit && lastForceExitTime > 0L && (currentTimeMs - lastForceExitTime) < cooldownMillis
        val remainingMillis = if (coolingActive) {
            (cooldownMillis - (currentTimeMs - lastForceExitTime)).coerceAtLeast(0L)
        } else 0L
        val cooldownDaysLeft = if (coolingActive) {
            kotlin.math.ceil(remainingMillis / (24.0 * 60.0 * 60.0 * 1000.0)).toInt()
        } else 0
        val canEnter = if (hadForceExit) !coolingActive && qqqRsi >= COOLDOWN_RSI else true

        when {
            // 0) 데이터 준비 부족
            !isReady -> {
                targetRatio = 0
                actionTitle = "WAIT"
                actionDesc = "Loading"
                actionColor = 0xFF8E8E93
            }
            // 1) 변동성 Risk
            isVolatilityRisk -> {
                targetRatio = 0
                actionTitle = "ESCAPE"
                actionDesc = "Overheat"
                actionColor = 0xFFFF453A
            }
            // 2) SPY 이격도 Risk
            isSpyDisparityRisk -> {
                targetRatio = 0
                actionTitle = "ESCAPE"
                actionDesc = "SPY Weak"
                actionColor = 0xFFFF453A
            }
            // 3) 강제 탈출 (손절)
            isDrawdownRisk -> {
                targetRatio = 0
                actionTitle = "STOP"
                actionDesc = "Runaway"
                actionColor = 0xFFFF453A
            }
            // 4) 재진입 불가 상태
            !canEnter -> {
                targetRatio = 0
                actionTitle = "Cooling"
                actionDesc = if (coolingActive) "${cooldownDaysLeft}D lock left" else "RSI < ${COOLDOWN_RSI.toInt()}"
                actionColor = 0xFF8E8E93
            }
            // 5) 진입 조건 및 6) 단계적 감량
            else -> {
                // 진입 조건 판별
                val entry100 = disparityTQQQ >= ENTRY100_DISP
                val entry10 = qqqMA3 > qqqMA161 && tqqqCurrent < tqqqMA200
                val specialEntry = (tqDisSlope >= SPECIAL_SLOPE_TH) &&
                    (disparityTQQQ <= SPECIAL_DISP_MAX) &&
                    (vol20 <= SPECIAL_VOL_MAX)

                // 기본 진입 비중 결정
                targetRatio = when {
                    entry100 -> 100
                    entry10 && specialEntry -> 100
                    entry10 -> 10
                    else -> 0
                }

                // 단계적 감량 (Scaling Down) - 100% 진입 상태일 때 적용
                if (targetRatio == 100) {
                    targetRatio = when {
                        disparityTQQQ >= EXIT_DISP -> 0
                        disparityTQQQ >= TRIM5_DISP -> 5
                        disparityTQQQ >= TRIM80_DISP -> 80
                        disparityTQQQ >= TRIM90_DISP -> 90
                        lastSignalEntryPrice > 0 && tqqqCurrent >= lastSignalEntryPrice * PROFIT_TRIM_RATIO -> 95
                        else -> 100
                    }
                }

                // UI 메시지 설정
                actionTitle = if (targetRatio > 0) "HOLD" else "WAIT"
                actionDesc = when (targetRatio) {
                    100 -> "FULL"
                    95 -> "TRIM95"
                    90 -> "TRIM90"
                    80 -> "TRIM80"
                    10 -> "QLD"
                    5 -> "MINI"
                    else -> "-"
                }
                actionColor = when {
                    targetRatio >= 100 -> 0xFF30D158
                    targetRatio >= 95 -> 0xFF0A84FF
                    targetRatio >= 80 -> 0xFF5AC8FA
                    targetRatio >= 10 -> 0xFFFFCC00
                    targetRatio > 0 -> 0xFF8E8E93
                    else -> 0xFF8E8E93
                }
            }
        }
        val profitRate = if (lastSignalEntryPrice > 0) {
            ((tqqqCurrent - lastSignalEntryPrice) / lastSignalEntryPrice) * 100
        } else {
            0.0
        }

        return AlgoResult(
            score = if (targetRatio >= 100) 2 else if (targetRatio > 0) 1 else 0,
            marketStatus = "${targetRatio}%",
            actionTitle = actionTitle,
            actionDesc = actionDesc,
            actionColor = actionColor,
            disparity = disparityTQQQ,
            vol20 = vol20,
            targetRatio = targetRatio,
            rsi = qqqRsi,
            displayPosition = if (targetRatio == 0) "CASH" else "TQQQ",
            currentPrice = tqqqCurrent,
            profitRate = profitRate,
            userPosition = userPosition,
            isTqqqBullish = isTqqqBullish,
            isQqqBullish = isQqqBullish,
            cooldownDaysLeft = cooldownDaysLeft,
        )
    }

    private fun calculateVolatility(prices: List<Double>, n: Int): Double {
        if (prices.size < n + 1) return 0.0
        val returns = mutableListOf<Double>()
        for (i in prices.size - n until prices.size) {
            returns.add((prices[i] - prices[i - 1]) / prices[i - 1] * 100)
        }
        val mean = returns.average()
        return sqrt(returns.sumOf { (it - mean).pow(2) } / returns.size)
    }

    private fun calculateSlope(symbol: List<Double>): LinRegResult {
        val fixedData = symbol.takeLast(45)
        val n = fixedData.size

        if (n < 2) return LinRegResult(0.0, 0.0, 0.0, 0.0)

        val x = DoubleArray(n) { it.toDouble() }
        val y = fixedData.toDoubleArray()

        val sumX = x.sum()
        val sumY = y.sum()
        val sumXX = x.sumOf {  it * it }
        val sumXY = x.zip(y) { xi, yi -> xi * yi }.sum()

        val slope = (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX)
        val intercept = (sumY - slope * sumX) / n

        val startY = intercept
        val endY = slope * (n - 1) + intercept

        return LinRegResult(slope, intercept, startY, endY)

    }

    private fun calculateRSI(prices: List<Double>, period: Int): Double {
        if (prices.size < period + 1) return Double.NaN

        val changes = prices.zipWithNext { a, b -> b - a }
        var avgGain = changes.take(period).filter { it > 0 }.sum() / period
        var avgLoss = changes.take(period).filter { it < 0 }.sumOf { kotlin.math.abs(it) } / period

        for (i in period until changes.size) {
            val change = changes[i]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) kotlin.math.abs(change) else 0.0
            avgGain = ((avgGain * (period - 1)) + gain) / period
            avgLoss = ((avgLoss * (period - 1)) + loss) / period
        }

        if (avgGain == 0.0 && avgLoss == 0.0) return 50.0
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
    private fun calculateMA(prices: List<Double>, period: Int, count: Int): List<Double> {
        if (prices.size < period) return emptyList()
        return List(count) { i ->
            val endIdx = prices.size - count + i
            prices.subList((endIdx - period + 1).coerceAtLeast(0), endIdx + 1).average()
        }
    }

}
