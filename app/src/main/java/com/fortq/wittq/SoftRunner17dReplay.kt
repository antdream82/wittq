package com.fortq.wittq

object SoftRunner17dReplay {
    fun replay(
        bars: List<SoftRunner17dBar>,
        initialState: SoftRunner17dState = SoftRunner17dState(),
    ): SoftRunner17dReplayResult {
        if (bars.isEmpty()) return SoftRunner17dReplayResult(initialState.copy(), emptyList())
        val sorted = bars.sortedBy { it.date }
        require(sorted.zipWithNext().all { (a, b) -> a.date < b.date }) {
            "17d bars must have unique, strictly increasing dates"
        }

        val qqq = sorted.map { it.qqqClose }
        val tqqq = sorted.map { it.tqqqClose }
        val spy = sorted.map { it.spyClose }

        val qMa3 = SoftRunner17dIndicators.simpleMovingAverage(qqq, 3)
        val qMa161 = SoftRunner17dIndicators.simpleMovingAverage(qqq, 161)
        val qRsi14 = SoftRunner17dIndicators.rsiWilder(qqq, 14)
        val tMa200 = SoftRunner17dIndicators.simpleMovingAverage(tqqq, 200)
        val tMa210 = SoftRunner17dIndicators.simpleMovingAverage(tqqq, 210)
        val tSma290 = SoftRunner17dIndicators.simpleMovingAverage(tqqq, 290)
        val spyMa200 = SoftRunner17dIndicators.simpleMovingAverage(spy, 200)
        val tVol20 = SoftRunner17dIndicators.rollingReturnVolatility(tqqq, 20)
        val disparity = tqqq.indices.map { i ->
            tMa200[i]?.takeIf { it > 0.0 }?.let { tqqq[i] / it * 100.0 }
        }
        val tDispSlope45 = SoftRunner17dIndicators.rollingLinearSlope(disparity, 45)

        var state = initialState.copy()
        val signals = ArrayList<SoftRunner17dSignal>(sorted.size)
        for (i in sorted.indices) {
            val step = SoftRunner17dEngine.evaluate(
                state,
                SoftRunner17dRowInput(
                    bar = sorted[i],
                    qqqMa3 = qMa3[i],
                    qqqMa161 = qMa161[i],
                    qqqRsi14 = qRsi14[i],
                    tqqqMa200 = tMa200[i],
                    tqqqMa210 = tMa210[i],
                    tqqqVol20 = tVol20[i],
                    tqqqDispSlope45 = tDispSlope45[i],
                    spyMa200 = spyMa200[i],
                    tqqqSma290 = tSma290[i],
                ),
            )
            state = step.nextState
            signals += step.signal
        }
        return SoftRunner17dReplayResult(state, signals)
    }

    fun sma290Series(bars: List<SoftRunner17dBar>): List<Double?> =
        SoftRunner17dIndicators.simpleMovingAverage(bars.sortedBy { it.date }.map { it.tqqqClose }, 290)
}
