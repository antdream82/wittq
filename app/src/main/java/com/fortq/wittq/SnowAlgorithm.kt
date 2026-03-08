package com.fortq.wittq

import kotlin.math.pow
import kotlin.math.sqrt
import android.content.Context
import android.content.SharedPreferences

// AGTQ Data
data class SnowResult(
    val tq220: Double,
    val snowscore: Int,
    val tqPrice: Double,
    val qPrice: Double,
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
    val dip2Price: Double
)

object SnowStrategy {
    fun calc(
        tqPrice: List<Double>,
        qPrice: List<Double>,
        entryPrice: Double,
        entryDays: Int,
        cooldownDays: Int,
        avgPrice: Double,
        usPos: String,
        slPrice: Double,
        dipPrice: Double,
        dip2Price: Double
    ): SnowResult {
        val bullColor = 0xFF30D158
        val bearColor = 0xFFFF453A
        val grayColor = 0xFF8E8E93
        val purpleColor = 0xFFBF5AF2
        val blueColor = 0xFF0A84FF

        val tqCurrent = tqPrice.lastOrNull() ?: 0.0
        val qCurrent = qPrice.lastOrNull() ?: 0.0
        val tq5 = tqPrice.takeLast(5).average()
        val tq220 = tqPrice.takeLast(220).average()
        val tqRSI = calcRSI(tqPrice, 14)
        val tq5prev = tqPrice.takeLast(6).dropLast(1).average()
        val tq220prev = tqPrice.takeLast(221).dropLast(1).average()

        val qqq52WHigh = qPrice.takeLast(252).maxOrNull() ?: 0.0
        val diff220ma = if (tq220 > 0) (tq5 - tq220) / tq220 * 100 else 0.0
        val diffqqq = if (qqq52WHigh > 0) (qCurrent - qqq52WHigh) / qqq52WHigh * 100 else 0.0

        val isCooldown = cooldownDays > 0

        val isgc = (tq5 > tq220) && (tq5prev <= tq220prev) && !isCooldown
        val isbull = (tq5 > tq220) && !isCooldown
        val isbear = (tq5 < tq220)
        val profitRate = if (entryPrice > 0) (tqCurrent - entryPrice) / entryPrice else 0.0

        val isDip = diffqqq <= -10 && !isCooldown

        var actionNote = ""
        var actNote = ""

        var buyRatio = when {
            isCooldown -> 0
            diffqqq <= -40 -> 0 /* STOP BUY */
            diffqqq <= -22.0 -> 50
            diffqqq <= -10 -> if (tqRSI <= 35) 30 else 20
            else -> 0
        }

        var tqRatio = if (entryPrice > 0) {
            when {
                profitRate >= 3.50 -> 15
                profitRate >= 0.68 -> 35
                profitRate >= 0.15 -> 50
                else -> 100
            }
        } else { buyRatio }

        if (isbear && entryPrice > 0) tqRatio = 0
        else if (isbull || isgc) tqRatio = 100

        if (tqRatio in 1..<100) actionNote = "(분할매도)"
        if (buyRatio in 19..<31) actNote = "DIP1"
        else if (buyRatio == 50) actNote = "DIP2"

        var snowscore = when {
            isgc || isbull -> 3
            tqRatio in 1..99 -> 2
            buyRatio in 1..99 -> 1
            else -> 0
        }

        val (snowsignal, snowaction, snowColor) = when {
            //MA200 이탈 시 약세
            isbear -> { Triple("전량매도\uD83D\uDE07", "탈출\uD83D\uDD25", bearColor) }
            isgc || isbull || entryPrice > 0 -> { Triple("전량매수\uD83D\uDEEB", "TQ ${tqRatio}%", bullColor) }
            snowscore == 2 -> { Triple("Active", "TQ ${tqRatio}% $actionNote", purpleColor) }
            snowscore == 1 -> { Triple("❄\uFE0F 눈덩이", "TQ ${buyRatio}% $actNote", blueColor) }
            else -> { Triple("대기⏳", "대기⌛", grayColor)}
        }

        val usProfit = if (avgPrice > 0) ((tqCurrent - avgPrice) / avgPrice) * 100 else 0.0

        return SnowResult(
            tq220 = tq220,
            snowscore = snowscore,
            tqPrice = tqCurrent,
            qPrice = qCurrent,
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
            tqRatio = if (isbear) 0 else tqRatio,
            buyRatio = buyRatio,
            avgPrice = avgPrice,
            cooldownDays = cooldownDays,
            usProfit = usProfit,
            usPos = usPos,
            dipPrice = dipPrice,
            dip2Price = dip2Price
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
