package com.fortq.wittq

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * Trailing strategy performance derived only from OFFICIAL replay rows.
 *
 * Same-close semantics: a signal produced at close t is held over t -> t+1,
 * so the close-to-close return ending on row i uses row i's previousFinalTarget.
 * Rebalancing at close i then pays the frozen Production one-way cost:
 * 5 bp fee + 5 bp slippage = 10 bp on the absolute target-weight change.
 * Cash earns 0% and cash interest is excluded.
 */
object SoftRunner17dReturns {
    internal const val ONE_WAY_TURNOVER_COST = 0.001 // 0.10% = 10 bp

    /** Mirrors the Python Production accounting order: return first, then cost. */
    internal fun dailyNetFactor(
        assetReturn: Double,
        previousTarget: Double,
        finalTarget: Double,
        isReady: Boolean,
    ): Double {
        if (!isReady) return 1.0
        if (!assetReturn.isFinite() || !previousTarget.isFinite() || !finalTarget.isFinite()) {
            return Double.NaN
        }

        val heldExposure = max(0.0, previousTarget / 100.0)
        val nextExposure = max(0.0, finalTarget / 100.0)
        val grossFactor = max(0.0, 1.0 + heldExposure * assetReturn)
        val turnover = abs(nextExposure - heldExposure)
        val transactionCost = if (finalTarget != previousTarget) {
            turnover * ONE_WAY_TURNOVER_COST
        } else {
            0.0
        }
        val costFactor = max(0.0, 1.0 - transactionCost)
        return grossFactor * costFactor
    }

    fun trailingPercent(
        bars: List<SoftRunner17dBar>,
        signals: List<SoftRunner17dSignal>,
        months: Long,
    ): Double? {
        if (bars.size < 2 || bars.size != signals.size || months <= 0L) return null
        if (bars.zipWithNext().any { (a, b) -> !a.date.isBefore(b.date) }) return null
        if (signals.indices.any { signals[it].date != bars[it].date }) return null

        val equity = DoubleArray(bars.size)
        equity[0] = 1.0
        for (i in 1 until bars.size) {
            val priorClose = bars[i - 1].tqqqClose
            val close = bars[i].tqqqClose
            if (!priorClose.isFinite() || priorClose <= 0.0 || !close.isFinite() || close <= 0.0) {
                return null
            }

            val assetReturn = close / priorClose - 1.0
            val netFactor = dailyNetFactor(
                assetReturn = assetReturn,
                previousTarget = signals[i].previousFinalTarget,
                finalTarget = signals[i].finalTarget,
                isReady = signals[i].isReady,
            )
            if (!netFactor.isFinite() || netFactor <= 0.0) return null
            equity[i] = equity[i - 1] * netFactor
        }

        val cutoff: LocalDate = bars.last().date.minusMonths(months)
        val startIndex = bars.indexOfLast { !it.date.isAfter(cutoff) }
        if (startIndex < 0 || equity[startIndex] <= 0.0) return null

        return (equity.last() / equity[startIndex] - 1.0) * 100.0
    }
}
