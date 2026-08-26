package com.fortq.wittq

import java.time.LocalDate
import kotlin.math.abs

/**
 * Trailing strategy performance derived only from OFFICIAL replay rows.
 *
 * Same-close semantics: a signal produced at close t is held over t -> t+1,
 * so the close-to-close return ending on row i uses row i's previousFinalTarget.
 * Rebalancing at close i then pays a one-way 25 bp transaction cost on the
 * absolute target-weight change. Cash earns 0% and cash interest is excluded.
 */
object SoftRunner17dReturns {
    private const val ONE_WAY_TURNOVER_COST = 0.0025 // 0.25% = 25 bp

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

            // The position carried into this close is the prior day's final target.
            val exposure = signals[i].previousFinalTarget / 100.0
            val assetReturn = close / priorClose - 1.0

            // After the close, rebalance from the carried exposure to today's new
            // final target and charge 25 bp on only the absolute changed notional.
            val turnover = abs(signals[i].finalTarget - signals[i].previousFinalTarget) / 100.0
            val transactionCost = turnover * ONE_WAY_TURNOVER_COST

            val netFactor = 1.0 + exposure * assetReturn - transactionCost
            if (!netFactor.isFinite() || netFactor <= 0.0) return null
            equity[i] = equity[i - 1] * netFactor
        }

        val cutoff: LocalDate = bars.last().date.minusMonths(months)
        val startIndex = bars.indexOfLast { !it.date.isAfter(cutoff) }
        if (startIndex < 0 || equity[startIndex] <= 0.0) return null

        return (equity.last() / equity[startIndex] - 1.0) * 100.0
    }
}
