package com.fortq.wittq

import kotlin.math.pow
import kotlin.math.sqrt
import android.content.Context
import android.content.SharedPreferences

data class SnowResult(
    val tq220: Double,
    val snowscore: Int,
    val tqPrice: List<Double>,
    val qqPrice: List<Double>,
    val tqCurrent: Double,
    val qqCurrent: Double,
    val qqq52WHigh: Double,
    val diff220ma: Double,
    val diffqqq: Double,
    val tqRSI: Double,
    val stLoss: Double,
    val snowsignal: String,
    val snowaction: String,
    val snowColor: Long,
    val isgc: Boolean,
    val isbull: Boolean,
    val isbear: Boolean,
    val isDip: Boolean,
    val tqRatio: Int,
    val buyRatio: Int,
    val cooldownDays: Int,
    val avgPrice: Double = 0.0,
    val usProfit : Double,
    val usPos: String,
    val dipPrice: Double,
    val dip2Price: Double,
    val dipAvgPrice: Double,
    val entryPrice: Double,
    val slPrice : Double
)

object SnowStrategy {
    fun calc(
        tHistory: List<Double>,
        qHistory: List<Double>,
        tqCurrent: Double,
        qqCurrent: Double,
        entryDays: Int,
        cooldownDays: Int,
        avgPrice: Double,
        usPos: String,
        slPrice: Double,
        dipPrice: Double,
        dip2Price: Double,
        dipAvgPrice: Double,
        signalPrice: Double
    ): SnowResult {
        val bullColor = 0xFF30D158
        val bearColor = 0xFFFF453A
        val grayColor = 0xFF8E8E93
        val purpleColor = 0xFFBF5AF2
        val blueColor = 0xFF0A84FF

        val lastclose = tHistory.last()
        val qlast = qHistory.last()
        val tq5 = tHistory.takeLast(5).average()
        val tq220 = tHistory.takeLast(220).average()
        val tqRSI = calcRSI(tHistory, 14)
        val tq5prev = tHistory.takeLast(6).dropLast(1).average()
        val tq220prev = tHistory.takeLast(221).dropLast(1).average()

        val qqq52WHigh = qHistory.takeLast(252).maxOrNull() ?: 0.0
        val diff220ma = if (tq220 > 0) (tq5 - tq220) / tq220 * 100 else 0.0
        val diffqqq = if (qqq52WHigh > 0) (qlast - qqq52WHigh) / qqq52WHigh * 100 else 0.0

        val isCooldown = cooldownDays > 0

        val isgc = (tq5 > tq220) && (tq5prev <= tq220prev) && !isCooldown
        val isbull = (tq5 > tq220) && !isCooldown
        val isbear = (tq5 < tq220) && (tq5prev > tq220prev)
        val dipPrice = if (diffqqq == -10.0) lastclose else 0.0
        val dip2price = if (diffqqq == -22.0) lastclose else 0.0
        val dipAvgPrice = if (dipPrice > 0 || dip2Price > 0) {
            val totalRatio = (if (dipPrice > 0) 20 else 0) + (if (dip2price > 0) 50 else 0)
            ((dipPrice * 20) + (dip2price * 50)) / totalRatio.coerceAtLeast(1)
        } else 0.0

        val basisPrice = signalPrice
        val profitRate = if (basisPrice > 0) (lastclose - basisPrice) / basisPrice else 0.0
        val dipRate = if (dipAvgPrice > 0) (lastclose - dipAvgPrice) / dipAvgPrice else 0.0
        val slPrice = if (isbear) lastclose else 0.0


        val isDip = diffqqq <= -10

        var actionNote = ""
        var actNote = ""

        var buyRatio = when {
            isCooldown -> 0
            dipAvgPrice > 0 && dipRate >= 3.50 -> 15
            dipAvgPrice > 0 && dipRate >= 0.68 -> 35
            dipAvgPrice > 0 && dipRate >= 0.15 -> 50
            diffqqq <= -40 -> 0 /* STOP BUY */
            diffqqq <= -22 -> 50
            diffqqq <= -10 -> if (tqRSI <= 35) 30 else 20
            isgc && !isCooldown -> 100
            else -> 0
        }


        var tqRatio = if (basisPrice > 0) {
            when {
                isbear -> 0
                profitRate >= 3.50 -> 15
                profitRate >= 0.68 -> 35
                profitRate >= 0.15 -> 50
                else -> 100
            }
        } else { buyRatio }

        if (isbear && basisPrice > 0) tqRatio = 0
        else if (isgc) tqRatio = 100

        if (tqRatio in 1..<100) actionNote = "(매도)"
        else if (tqRatio == 100) actionNote = "(매수)"

        if (buyRatio in 19..<31) actNote = "DIP1"
        else if (buyRatio == 50) actNote = "DIP2"
        else if (dipRate >= 3.50) actNote = "(매도)"
        else if (dipRate >= 0.68) actNote = "(매도)"
        else if (dipRate >= 0.15) actNote = "(매도)"


        var snowscore = when {
            isbull && tqRatio == 0 && profitRate >= 3.50 -> 4
            isgc || isbull && basisPrice > 0 -> 3
            tqRatio in 1..99 -> 2
            buyRatio in 1..99 -> 1
            else -> 0
        }

        val (snowsignal, snowaction, snowColor) = when {
            //MA200 이탈 시 약세
            isbear -> { Triple("전량매도\uD83D\uDE07", "탈출\uD83D\uDD25", bearColor) }
            snowscore == 4 -> { Triple("졸업", "신호대기", grayColor)}
            snowscore == 3 -> { Triple("매수\uD83D\uDEEB", "TQ ${tqRatio}% $actionNote", bullColor) }
            snowscore == 2 -> { Triple("분할", "TQ ${tqRatio}% $actionNote", purpleColor) }
            snowscore == 1 -> { Triple("❄\uFE0F 눈덩이", "TQ ${buyRatio}% $actNote", blueColor) }
            else -> { Triple("대기⏳", "-", grayColor)}
        }

        val usProfit = if (avgPrice > 0) ((tqCurrent - avgPrice) / avgPrice) * 100 else 0.0

        return SnowResult(
            tq220 = tq220,
            snowscore = snowscore,
            tqPrice = tHistory,
            qqPrice = qHistory,
            tqCurrent = tqCurrent,
            qqCurrent = qqCurrent,
            qqq52WHigh = qqq52WHigh,
            diff220ma = diff220ma,
            diffqqq = diffqqq,
            tqRSI = tqRSI,
            stLoss = tq220,
            snowsignal = snowsignal,
            snowaction = snowaction,
            snowColor = snowColor,
            isgc = isgc,
            isbull = isbull,
            isbear = isbear,
            isDip = isDip,
            tqRatio = tqRatio,
            buyRatio = buyRatio,
            avgPrice = avgPrice,
            cooldownDays = cooldownDays,
            usProfit = usProfit,
            usPos = usPos,
            dipPrice = dipPrice,
            dip2Price = dip2Price,
            dipAvgPrice = dipAvgPrice,
            entryPrice = basisPrice,
            slPrice = slPrice
        )
    }

    private fun calcRSI(prices: List<Double>, period: Int): Double {
        if (prices.size < period + 1) return 50.0
        val changes = prices.zipWithNext { a, b -> b - a }
        var rsiup = changes.takeLast(period).filter { it > 0 }.sum() / period
        var rsidown = changes.takeLast(period).filter { it < 0 }.map { Math.abs(it) }.sum() / period

        if (rsidown == 0.0) return 100.0
        val rsvalue = rsiup / rsidown
        return 100.0 - (100.0 / (1.0 + rsvalue))
    }
}
