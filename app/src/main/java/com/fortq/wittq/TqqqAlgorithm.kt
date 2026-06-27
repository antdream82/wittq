package com.fortq.wittq

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.abs


data class AlgoResult(
    val score: Int,
    val marketStatus: String,
    val actionTitle: String,
    val actionDesc: String,
    val actionColor: Long,
    val disparity: Double,
    val vol20: Double,
    val targetRatio: Double,
    val signalChangeDesc: String? = null,
    val rsi: Double,
    val displayPosition: String,
    val userPosition: String,
    val currentPrice: Double,
    val profitRate: Double,
    val isTqqqBullish: Boolean,
    val isQqqBullish: Boolean,
    val cooldownDaysLeft: Int = 0,
    val vixClose: Double = 0.0,
    val isVixLock: Boolean = false,
    val vixCalmDays: Int = 0,
    val isVixPanic: Boolean = false,
    val isHardDrawdownRisk: Boolean = false,
    val isVolatilityRisk: Boolean = false,
    val isSpyRisk: Boolean = false,
    val isSoftStop: Boolean = false,
    val finalTrailArmed: Boolean = false,
    val finalTrailHit: Boolean = false,
    val overheatMaxDisp: Double = 0.0,
    val c3ReleaseActive: Boolean = false,
    val qqqBullStreak: Int = 0,
    val overheatDisparity: Double = 0.0,
    val overheatSmaLen: Int = 210,
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
    const val DEFAULT_OVERHEAT_SMA_LEN = 210
    const val DEFAULT_COOLDOWN_DAYS = 10

    private const val VOL_RISK_LIMIT = 5.5
    private const val LOW_EXPOSURE = 66.67
    private const val SPY_RISK_RATIO = 1.0015
    private const val DRAWDOWN_STOP_RATIO = 0.9300
    private const val SOFT_STOP_RUNNER_EXPOSURE = 10.0
    private const val ENTRY100_DISP = 100.5
    private const val SPECIAL_SLOPE_TH = 0.11
    private const val SPECIAL_DISP_MAX = 98.8
    private const val SPECIAL_VOL_MAX = 6.0
    private const val PROFIT_TRIM_RATIO = 1.180
    private const val PROFIT_EXPOSURE = 80.0
    private const val MILD_DISP = 108.0
    private const val MILD_EXPOSURE = 100.0
    private const val DEEP_DISP = 143.0
    private const val DEEP_EXPOSURE = 2.5
    private const val FINAL_TRAIL_ARM_DISP = 152.5
    private const val FINAL_TRAIL_GIVEBACK = 8.5
    private const val FINAL_TRAIL_EXPOSURE = 2.5
    private const val EXIT_DISP = 156.5
    private const val COOLDOWN_RSI = 41.0
    private const val VIX_LOCK_TRIGGER = 45.5
    private const val VIX_UNLOCK_LEVEL = 28.5
    private const val VIX_UNLOCK_DAYS = 3
    private const val USE_C3_RELEASE_GATE = true
    private const val C3_RELEASE_RSI = 50.0
    private const val C3_RELEASE_TREND_DAYS = 10

    fun calculate(
        qPrices: List<Double>,
        tPrices: List<Double>,
        spyPrices: List<Double>,
        vixPrices: List<Double>,
        userPosition: String,
        lastSignalEntryPrice: Double = 0.0,
        hadForceExit: Boolean = false,
        lastForceExitTime: Long = 0L,
        vixLock: Boolean = false,
        vixCalmDays: Int = 0,
        finalTrailArmed: Boolean = false,
        finalTrailHit: Boolean = false,
        overheatMaxDisp: Double = 0.0,
        c3ReleaseActive: Boolean = false,
        qqqBullStreak: Int = 0,
        overheatSmaLen: Int = DEFAULT_OVERHEAT_SMA_LEN,
        cooldownDays: Int = DEFAULT_COOLDOWN_DAYS,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): AlgoResult {
        val tqqqCurrent = tPrices.lastOrNull() ?: 0.0
        val spyCurrent = spyPrices.lastOrNull() ?: 0.0
        val vixClose = vixPrices.lastOrNull() ?: 0.0

        val tqqqMA200 = tPrices.takeLast(200).average()
        val safeOverheatSmaLen = overheatSmaLen.coerceIn(100, 300)
        val safeCooldownDays = cooldownDays.coerceIn(0, 30)
        val tqqqOverheatMA = tPrices.takeLast(safeOverheatSmaLen).average()
        val spyMA200 = spyPrices.takeLast(200).average()
        val qqqMA3 = qPrices.takeLast(3).average()
        val qqqMA161 = qPrices.takeLast(161).average()

        val disparityTQQQ = (tqqqCurrent / tqqqMA200) * 100
        val overheatDisparity = (tqqqCurrent / tqqqOverheatMA) * 100
        val vol20 = calculateVolatility(tPrices, 20)
        val qqqRsi = calculateRSI(qPrices, 14)

        val disparityList = calculateRollingDisparity(tPrices, 200, 45)

        val tqDisSlopeResult = calculateSlope(disparityList)
        val tqDisSlope = tqDisSlopeResult.slope

        // 상태 설정
        var targetRatio = 0.0
        var actionTitle: String
        var actionDesc: String
        var actionColor: Long = 0xFF8E8E93
        var nextFinalTrailArmed = finalTrailArmed
        var nextFinalTrailHit = finalTrailHit
        var nextOverheatMaxDisp = overheatMaxDisp
        var nextC3ReleaseActive = c3ReleaseActive

        // [작동 우선 순위 1, 2, 3] 강제 탈출 조건
        val isReady = !qqqRsi.isNaN() &&
            qPrices.size >= 161 &&
            tPrices.size >= 200 &&
            tPrices.size >= safeOverheatSmaLen &&
            spyPrices.size >= 200 &&
            vixPrices.isNotEmpty()

        val isTqqqBullish = tqqqMA200 > 0 && tqqqCurrent > tqqqMA200
        val isQqqBullish = qqqMA3 > qqqMA161
        val nextQqqBullStreak = if (isReady) {
            if (isQqqBullish) qqqBullStreak + 1 else 0
        } else {
            0
        }
        val isVolatilityRisk = vol20 > VOL_RISK_LIMIT
        val isSpyDisparityRisk = spyMA200 > 0 && (spyCurrent / spyMA200) <= SPY_RISK_RATIO
        val rawDrawdownRisk = if (lastSignalEntryPrice > 0) {
            tqqqCurrent <= lastSignalEntryPrice * DRAWDOWN_STOP_RATIO
        } else false

        val cooldownMillis = safeCooldownDays * 24L * 60L * 60L * 1000L
        val coolingActive = hadForceExit && lastForceExitTime > 0L && (currentTimeMs - lastForceExitTime) < cooldownMillis
        val remainingMillis = if (coolingActive) {
            (cooldownMillis - (currentTimeMs - lastForceExitTime)).coerceAtLeast(0L)
        } else 0L
        val cooldownDaysLeft = if (coolingActive) {
            kotlin.math.ceil(remainingMillis / (24.0 * 60.0 * 60.0 * 1000.0)).toInt()
        } else 0
        val canEnter = if (hadForceExit) !coolingActive && qqqRsi >= COOLDOWN_RSI else true
        val vixPanicSell = isReady && vixClose >= VIX_LOCK_TRIGGER
        var nextVixLock = vixLock
        var nextVixCalmDays = vixCalmDays

        if (isReady) {
            if (vixClose >= VIX_LOCK_TRIGGER) {
                nextVixLock = true
                nextVixCalmDays = 0
            } else if (nextVixLock) {
                if (vixClose <= VIX_UNLOCK_LEVEL) {
                    nextVixCalmDays += 1
                } else {
                    nextVixCalmDays = 0
                }

                if (nextVixCalmDays >= VIX_UNLOCK_DAYS) {
                    nextVixLock = false
                    nextVixCalmDays = 0
                }
            }
        }
        val vixHardZeroActive = isReady && nextVixLock && !vixPanicSell
        val hardRiskActive = vixPanicSell || vixHardZeroActive || isVolatilityRisk || isSpyDisparityRisk
        val softStopHit = rawDrawdownRisk && SOFT_STOP_RUNNER_EXPOSURE > 0.0 && !hardRiskActive
        val hardDrawdownRisk = rawDrawdownRisk && !softStopHit

        when {
            // 0) 데이터 준비 부족
            !isReady -> {
                targetRatio = 0.0
                actionTitle = "WAIT"
                actionDesc = "Loading"
                actionColor = 0xFF8E8E93
            }
            // 1) VIX panic sell and lock
            vixPanicSell -> {
                targetRatio = 0.0
                actionTitle = "ESCAPE"
                actionDesc = "VIX Panic"
                actionColor = 0xFFFF453A
                nextC3ReleaseActive = false
            }
            // 1-a) VIX lock remains active after panic until calm days complete.
            vixHardZeroActive -> {
                targetRatio = 0.0
                actionTitle = "VIX LOCK"
                actionDesc = "Hard Zero"
                actionColor = 0xFFFF453A
                nextC3ReleaseActive = false
            }
            // 1) 변동성 Risk
            isVolatilityRisk -> {
                targetRatio = 0.0
                actionTitle = "ESCAPE"
                actionDesc = "Overheat"
                actionColor = 0xFFFF453A
                nextC3ReleaseActive = false
            }
            // 2) SPY 이격도 Risk
            isSpyDisparityRisk -> {
                targetRatio = 0.0
                actionTitle = "ESCAPE"
                actionDesc = "SPY Weak"
                actionColor = 0xFFFF453A
                nextC3ReleaseActive = false
            }
            // 3) 강제 탈출 (손절)
            hardDrawdownRisk -> {
                targetRatio = 0.0
                actionTitle = "STOP"
                actionDesc = "Hard DD"
                actionColor = 0xFFFF453A
                nextC3ReleaseActive = false
            }
            // 3-a) Soft stop keeps a small runner instead of forcing cash.
            softStopHit -> {
                targetRatio = SOFT_STOP_RUNNER_EXPOSURE
                actionTitle = "SOFT STOP"
                actionDesc = "Runner ${formatRatio(SOFT_STOP_RUNNER_EXPOSURE)}%"
                actionColor = 0xFFFFCC00
                nextC3ReleaseActive = USE_C3_RELEASE_GATE
                nextFinalTrailArmed = false
                nextFinalTrailHit = false
                nextOverheatMaxDisp = 0.0
            }
            // 4) 재진입 불가 상태
            !canEnter -> {
                targetRatio = 0.0
                actionTitle = "Cooling"
                actionDesc = if (coolingActive) "${cooldownDaysLeft}D lock left" else "RSI < ${COOLDOWN_RSI.toInt()}"
                actionColor = 0xFF8E8E93
            }
            // 5) 진입 조건 및 6) 단계적 감량
            else -> {
                // 진입 조건 판별
                val entry100 = isQqqBullish && disparityTQQQ >= ENTRY100_DISP
                val entry10 = qqqMA3 > qqqMA161 && tqqqCurrent < tqqqMA200
                val specialEntry = (tqDisSlope >= SPECIAL_SLOPE_TH) &&
                    (disparityTQQQ <= SPECIAL_DISP_MAX) &&
                    (vol20 <= SPECIAL_VOL_MAX)
                val allowScaleUp = !nextVixLock

                // 기본 진입 비중 결정
                targetRatio = when {
                    entry100 -> 100.0
                    entry10 && specialEntry && allowScaleUp -> 100.0
                    entry10 -> LOW_EXPOSURE
                    else -> 0.0
                }

                val c3ReleasePass = qqqRsi >= C3_RELEASE_RSI && nextQqqBullStreak >= C3_RELEASE_TREND_DAYS
                if (nextC3ReleaseActive && targetRatio > 0.0) {
                    if (c3ReleasePass) {
                        nextC3ReleaseActive = false
                    } else {
                        targetRatio = minOf(targetRatio, SOFT_STOP_RUNNER_EXPOSURE)
                    }
                }

                if (sameRatio(targetRatio, 100.0)) {
                    if (overheatDisparity >= DEEP_DISP) {
                        nextOverheatMaxDisp = maxOf(nextOverheatMaxDisp, overheatDisparity)
                    }
                    if (overheatDisparity >= FINAL_TRAIL_ARM_DISP) {
                        nextFinalTrailArmed = true
                        nextOverheatMaxDisp = maxOf(nextOverheatMaxDisp, overheatDisparity)
                    }
                    if (nextFinalTrailArmed && nextOverheatMaxDisp > 0.0 &&
                        overheatDisparity <= nextOverheatMaxDisp - FINAL_TRAIL_GIVEBACK
                    ) {
                        nextFinalTrailHit = true
                    }
                    if (overheatDisparity < MILD_DISP) {
                        nextFinalTrailArmed = false
                        nextFinalTrailHit = false
                        nextOverheatMaxDisp = 0.0
                    }
                } else {
                    nextFinalTrailArmed = false
                    nextFinalTrailHit = false
                    nextOverheatMaxDisp = 0.0
                }

                // Late de-risk 2.5 + soft runner 10 logic.
                if (sameRatio(targetRatio, 100.0)) {
                    val profitHit = lastSignalEntryPrice > 0 && tqqqCurrent >= lastSignalEntryPrice * PROFIT_TRIM_RATIO
                    val mildHit = overheatDisparity >= MILD_DISP
                    val deepHit = overheatDisparity >= DEEP_DISP
                    targetRatio = when {
                        overheatDisparity >= EXIT_DISP -> 0.0
                        nextFinalTrailHit -> FINAL_TRAIL_EXPOSURE
                        deepHit -> DEEP_EXPOSURE
                        mildHit -> MILD_EXPOSURE
                        profitHit -> PROFIT_EXPOSURE
                        else -> 100.0
                    }
                }

                if (sameRatio(targetRatio, 0.0)) {
                    nextFinalTrailArmed = false
                    nextFinalTrailHit = false
                    nextOverheatMaxDisp = 0.0
                }
                if (nextC3ReleaseActive && targetRatio >= 100.0) {
                    nextC3ReleaseActive = false
                }
                if (sameRatio(targetRatio, 0.0) && !coolingActive) {
                    nextC3ReleaseActive = false
                }

                // UI 메시지 설정
                actionTitle = if (targetRatio > 0.0) "HOLD" else "WAIT"
                actionDesc = when {
                    sameRatio(targetRatio, 100.0) -> "TQQQ FULL"
                    sameRatio(targetRatio, LOW_EXPOSURE) -> "TQQQ 2/3"
                    sameRatio(targetRatio, SOFT_STOP_RUNNER_EXPOSURE) -> "TQQQ Soft 10%"
                    sameRatio(targetRatio, DEEP_EXPOSURE) && sameRatio(DEEP_EXPOSURE, FINAL_TRAIL_EXPOSURE) -> "TQQQ Runner 2.5%"
                    sameRatio(targetRatio, FINAL_TRAIL_EXPOSURE) -> "TQQQ Final 2.5%"
                    sameRatio(targetRatio, DEEP_EXPOSURE) -> "TQQQ Deep 2.5%"
                    targetRatio > 0.0 -> "TQQQ ${formatRatio(targetRatio)}%"
                    else -> "-"
                }
                actionColor = when {
                    targetRatio >= 100.0 -> 0xFF30D158
                    targetRatio >= LOW_EXPOSURE -> 0xFF5AC8FA
                    targetRatio >= SOFT_STOP_RUNNER_EXPOSURE -> 0xFFFFCC00
                    targetRatio > 0.0 -> 0xFF8E8E93
                    else -> 0xFF8E8E93
                }
            }
        }
        if (sameRatio(targetRatio, 0.0)) {
            nextFinalTrailArmed = false
            nextFinalTrailHit = false
            nextOverheatMaxDisp = 0.0
        }
        val profitRate = if (lastSignalEntryPrice > 0) {
            ((tqqqCurrent - lastSignalEntryPrice) / lastSignalEntryPrice) * 100
        } else {
            0.0
        }

        return AlgoResult(
            score = if (targetRatio >= 100.0) 2 else if (targetRatio > 0.0) 1 else 0,
            marketStatus = "${formatRatio(targetRatio)}%",
            actionTitle = actionTitle,
            actionDesc = actionDesc,
            actionColor = actionColor,
            disparity = disparityTQQQ,
            vol20 = vol20,
            targetRatio = targetRatio,
            rsi = qqqRsi,
            displayPosition = if (sameRatio(targetRatio, 0.0)) "CASH" else "TQQQ",
            currentPrice = tqqqCurrent,
            profitRate = profitRate,
            userPosition = userPosition,
            isTqqqBullish = isTqqqBullish,
            isQqqBullish = isQqqBullish,
            cooldownDaysLeft = cooldownDaysLeft,
            vixClose = vixClose,
            isVixLock = nextVixLock,
            vixCalmDays = nextVixCalmDays,
            isVixPanic = vixPanicSell,
            isHardDrawdownRisk = hardDrawdownRisk,
            isVolatilityRisk = isVolatilityRisk,
            isSpyRisk = isSpyDisparityRisk,
            isSoftStop = softStopHit,
            finalTrailArmed = nextFinalTrailArmed,
            finalTrailHit = nextFinalTrailHit,
            overheatMaxDisp = nextOverheatMaxDisp,
            c3ReleaseActive = nextC3ReleaseActive,
            qqqBullStreak = nextQqqBullStreak,
            overheatDisparity = overheatDisparity,
            overheatSmaLen = safeOverheatSmaLen,
        )
    }

    private fun sameRatio(a: Double, b: Double): Boolean = abs(a - b) < 0.01

    private fun formatRatio(ratio: Double): String {
        return if (sameRatio(ratio, kotlin.math.round(ratio))) {
            kotlin.math.round(ratio).toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", ratio).trimEnd('0').trimEnd('.')
        }
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
    private fun calculateRollingDisparity(prices: List<Double>, maPeriod: Int, count: Int): List<Double> {
        if (prices.size < maPeriod + count - 1) return emptyList()
        val startIdx = prices.size - count
        return List(count) { offset ->
            val endIdx = startIdx + offset
            val ma = prices.subList(endIdx - maPeriod + 1, endIdx + 1).average()
            prices[endIdx] / ma * 100
        }
    }

}
