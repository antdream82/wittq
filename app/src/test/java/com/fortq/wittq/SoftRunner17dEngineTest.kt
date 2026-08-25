package com.fortq.wittq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.sin

class SoftRunner17dEngineTest {
    private fun readyInput(
        date: LocalDate,
        tqqq: Double = 80.0,
        vix: Double = 20.0,
        sma290: Double = 100.0,
    ) = SoftRunner17dRowInput(
        bar = SoftRunner17dBar(date, qqqClose = 500.0, tqqqClose = tqqq, spyClose = 600.0, vixClose = vix),
        qqqMa3 = 500.0,
        qqqMa161 = 490.0,
        qqqRsi14 = 55.0,
        tqqqMa200 = 100.0,
        tqqqMa210 = 100.0,
        tqqqVol20 = 0.02,
        tqqqDispSlope45 = 0.0,
        spyMa200 = 580.0,
        tqqqSma290 = sma290,
    )

    @Test
    fun panicOverrideCanRaiseFinalTargetAfterBaseHardZero() {
        val state = SoftRunner17dState(
            softAnchor = 110.0,
            campaignAnchor = 110.0,
            contrarianEpisodeLatched = true,
            priorBaseActive = true,
        )
        val step = SoftRunner17dEngine.evaluate(
            state,
            readyInput(LocalDate.of(2020, 3, 16), tqqq = 80.0, vix = 80.0, sma290 = 100.0),
        )
        assertTrue(step.signal.hardRisk)
        assertEquals(0.0, step.signal.baseTarget, 1e-9)
        assertTrue(step.signal.contrarianActive)
        assertEquals(97.5, step.signal.finalTarget, 1e-9)
        assertEquals(SoftRunner17dReason.CONTRARIAN_ENTER, step.signal.reason)
    }

    @Test
    fun threeDrawdownRowsActivateTwentyPercentRunner() {
        var state = SoftRunner17dState(
            softAnchor = 115.0,
            campaignAnchor = 115.0,
            previousBaseTarget = 100.0,
            previousFinalTarget = 100.0,
        )
        var signal: SoftRunner17dSignal? = null
        repeat(3) { offset ->
            val step = SoftRunner17dEngine.evaluate(
                state,
                readyInput(LocalDate.of(2022, 1, 3).plusDays(offset.toLong()), tqqq = 110.0, sma290 = 120.0),
            )
            state = step.nextState
            signal = step.signal
        }
        assertEquals(RunnerStatus.ACTIVE, signal!!.runnerStatus)
        assertEquals(20.0, signal!!.baseTarget, 1e-9)
        assertEquals(SoftRunner17dReason.BASE_TRIGGER_COMPLETE, signal!!.reason)
    }

    @Test
    fun vixLockRequiresThreeCalmRowsToUnlock() {
        var state = SoftRunner17dState()
        state = SoftRunner17dEngine.evaluate(
            state,
            readyInput(LocalDate.of(2023, 1, 3), vix = 50.0, tqqq = 120.0, sma290 = 100.0),
        ).nextState
        assertTrue(state.vixLock)
        repeat(2) { offset ->
            state = SoftRunner17dEngine.evaluate(
                state,
                readyInput(LocalDate.of(2023, 1, 4).plusDays(offset.toLong()), vix = 20.0, tqqq = 120.0, sma290 = 100.0),
            ).nextState
            assertTrue(state.vixLock)
        }
        state = SoftRunner17dEngine.evaluate(
            state,
            readyInput(LocalDate.of(2023, 1, 6), vix = 20.0, tqqq = 120.0, sma290 = 100.0),
        ).nextState
        assertFalse(state.vixLock)
        assertEquals(0, state.vixCalmDays)
    }

    @Test
    fun fullReplayBecomesReadyAndIsDeterministic() {
        val start = LocalDate.of(2010, 2, 11)
        val bars = (0 until 500).map { i ->
            val trend = 50.0 + i * 0.08 + sin(i / 17.0) * 2.0
            SoftRunner17dBar(
                date = start.plusDays(i.toLong()),
                qqqClose = 100.0 + i * 0.05,
                tqqqClose = trend,
                spyClose = 120.0 + i * 0.04,
                vixClose = 20.0,
            )
        }
        val first = SoftRunner17dReplay.replay(bars)
        val second = SoftRunner17dReplay.replay(bars)
        assertNotNull(first.latest)
        assertTrue(first.latest!!.isReady)
        assertEquals(first.latest, second.latest)
        assertEquals(first.finalState, second.finalState)
    }
}
