package com.fortq.wittq

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftRunner17dReturnsTest {
    @Test
    fun productionCostIsTenBasisPointsOneWay() {
        assertEquals(0.001, SoftRunner17dReturns.ONE_WAY_TURNOVER_COST, 1e-15)
    }

    @Test
    fun fullRebalancePaysTenBasisPoints() {
        val factor = SoftRunner17dReturns.dailyNetFactor(
            assetReturn = 0.0,
            previousTarget = 0.0,
            finalTarget = 100.0,
            isReady = true,
        )
        assertEquals(0.999, factor, 1e-15)
    }

    @Test
    fun partialRebalancePaysCostOnlyOnChangedWeight() {
        val factor = SoftRunner17dReturns.dailyNetFactor(
            assetReturn = 0.0,
            previousTarget = 20.0,
            finalTarget = 100.0,
            isReady = true,
        )
        assertEquals(0.9992, factor, 1e-15)
    }

    @Test
    fun returnIsAppliedBeforeTurnoverCostLikePythonProduction() {
        val factor = SoftRunner17dReturns.dailyNetFactor(
            assetReturn = 0.10,
            previousTarget = 100.0,
            finalTarget = 0.0,
            isReady = true,
        )
        assertEquals(1.10 * 0.999, factor, 1e-15)
    }

    @Test
    fun notReadyRowDoesNotMoveEquityOrChargeCost() {
        val factor = SoftRunner17dReturns.dailyNetFactor(
            assetReturn = 0.10,
            previousTarget = 100.0,
            finalTarget = 0.0,
            isReady = false,
        )
        assertEquals(1.0, factor, 1e-15)
    }
}
