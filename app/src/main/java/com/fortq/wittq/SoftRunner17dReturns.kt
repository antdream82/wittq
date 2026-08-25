package com.fortq.wittq

import java.time.LocalDate

/**
 * Trailing strategy performance derived only from OFFICIAL replay rows.
 *
 * Same-close semantics: a signal produced at close t is held over t -> t+1,
 * so the close-to-close return ending on row i uses row i's previousFinalTarget.
 * Cash earns 0% and fees/interest are intentionally excluded from this compact
 * on-device performance display.
 */
object SoftRunner17dReturns {
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
            val exposure = signals[i].previousFinalTarget / 100.0
            val assetReturn = close / priorClose - 1.0
            val gross = 1.0 + exposure * assetReturn
            if (!gross.isFinite() || gross <= 0.0) return null
            equity[i] = equity[i - 1] * gross
        }

        val cutoff: LocalDate = bars.last().date.minusMonths(months)
        val startIndex = bars.indexOfLast { !it.date.isAfter(cutoff) }
        if (startIndex < 0 || equity[startIndex] <= 0.0) return null

        return (equity.last() / equity[startIndex] - 1.0) * 100.0
    }
}
