package com.fortq.wittq

import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure Kotlin implementation of the frozen 17d Production profile.
 *
 * The order of operations follows the supplied Pine/Python contract:
 * D-base computes and applies its hard-risk zero first, then the contrarian
 * overlay is evaluated. Because panic override is enabled for ANY_HARD, the
 * overlay may raise the final target to 97.5% after the D-base zero. There is
 * intentionally no second hard-zero after the overlay.
 */
object SoftRunner17dEngine {
    private const val THR_VOL = 0.055
    private const val SPY_RISK_RATIO = 1.0015
    private const val LOW_EXPOSURE = 66.67
    private const val ENTRY100_DISP = 100.5
    private const val SPECIAL_SLOPE = 0.11
    private const val SPECIAL_DISP_MAX = 98.8
    private const val SPECIAL_VOL_MAX = 0.06
    private const val TRIM_MID1_DISP = 108.0
    private const val TRIM_MID2_DISP = 143.0
    private const val TRIM5_ARM_DISP = 152.5
    private const val EXIT_DISP = 156.5
    private const val TRIM_MID1_EXPOSURE = 100.0
    private const val TRIM_MID2_EXPOSURE = 2.5
    private const val DISP_TRAIL_GIVEBACK = 8.5
    private const val FINAL_TRAIL_EXPOSURE = 2.5
    private const val PROFIT_TRIM_RATIO = 1.18
    private const val PROFIT_TRIM_EXPOSURE = 80.0
    private const val VIX_LOCK_TRIGGER = 45.5
    private const val VIX_UNLOCK_LEVEL = 28.5
    private const val VIX_UNLOCK_DAYS = 3
    private const val COOLDOWN_DAYS = 10L
    private const val COOLDOWN_RSI = 41.0

    private const val D_STOP_RATIO = 0.97
    private const val D_RUNNER_EXPOSURE = 20.0
    private const val D_TRIGGER_ROWS = 3
    private const val D_RECLAIM_GAP = 0.03
    private const val D_QUALIFYING_TARGET = 66.67
    private const val D_RELEASE_CONFIRMATION = 5
    private const val D_RISK_CLEAR_ROWS = 5
    private const val D_PENDING_EXPIRY = 60
    private const val D_RELEASE_SMA200_DISP = 100.0

    private const val CONTRARIAN_EXPOSURE = 97.5
    private const val CONTRARIAN_CHEAP_THRESHOLD = 0.88
    private const val CONTRARIAN_CONFIRMATION_ROWS = 1
    private const val CONTRARIAN_RECLAIM_THRESHOLD = 0.95
    private const val EPS = 1e-12

    fun evaluate(
        priorState: SoftRunner17dState,
        input: SoftRunner17dRowInput,
    ): SoftRunner17dStep {
        val s = priorState.copy()
        val date = input.bar.date
        val qClose = input.bar.qqqClose
        val tClose = input.bar.tqqqClose
        val spyClose = input.bar.spyClose
        val vixClose = input.bar.vixClose

        val isReady = listOf(
            qClose,
            tClose,
            spyClose,
            vixClose,
            input.qqqMa3,
            input.qqqMa161,
            input.qqqRsi14,
            input.tqqqMa200,
            input.tqqqMa210,
            input.tqqqVol20,
            input.spyMa200,
        ).all { value -> value != null && value.isFinite() }

        val qMa3 = input.qqqMa3 ?: Double.NaN
        val qMa161 = input.qqqMa161 ?: Double.NaN
        val qRsi = input.qqqRsi14 ?: Double.NaN
        val tMa200 = input.tqqqMa200 ?: Double.NaN
        val tMa210 = input.tqqqMa210 ?: Double.NaN
        val tVol = input.tqqqVol20 ?: Double.NaN
        val dispSlope = input.tqqqDispSlope45 ?: Double.NaN
        val spyMa200 = input.spyMa200 ?: Double.NaN
        val tSma290 = input.tqqqSma290

        val disp = if (isReady && tMa200 > 0.0) tClose / tMa200 * 100.0 else Double.NaN
        val overheatDisp = if (isReady && tMa210 > 0.0) tClose / tMa210 * 100.0 else Double.NaN
        val contrarianRatio = tSma290?.takeIf { it > 0.0 }?.let { tClose / it }
        val qqqBull = isReady && qMa3 > qMa161
        val volRisk = isReady && tVol > THR_VOL
        val spyRisk = isReady && spyMa200 > 0.0 && spyClose / spyMa200 <= SPY_RISK_RATIO

        if (isReady) {
            if (vixClose >= VIX_LOCK_TRIGGER) {
                s.vixLock = true
                s.vixCalmDays = 0
            } else if (s.vixLock) {
                s.vixCalmDays = if (vixClose <= VIX_UNLOCK_LEVEL) s.vixCalmDays + 1 else 0
                if (s.vixCalmDays >= VIX_UNLOCK_DAYS) {
                    s.vixLock = false
                    s.vixCalmDays = 0
                }
            }
        }

        val vixPanicSell = isReady && vixClose >= VIX_LOCK_TRIGGER
        val hardVixZero = s.vixLock
        val hardRisk = volRisk || spyRisk || vixPanicSell || hardVixZero

        val daysSinceForceExit = s.lastForceExitDate?.let { ChronoUnit.DAYS.between(it, date) }
        val coolingActive = s.hadForceExit && daysSinceForceExit != null && daysSinceForceExit < COOLDOWN_DAYS
        val absPass = !coolingActive
        val rsiPass = isReady && qRsi >= COOLDOWN_RSI
        val canEnter = if (s.hadForceExit) absPass && rsiPass else true

        val overheatMaxDispPre = s.overheatMaxDisp
        val trim5ArmedPre = s.trim5Armed
        val trim5HitPre = s.trim5Hit
        val deepArmedPre = s.deepArmed
        val deepHitPre = s.deepHit

        var shadowTarget = 0.0
        if (isReady && !hardRisk && canEnter) {
            val entry100 = disp >= ENTRY100_DISP && qqqBull
            val entryLow = qqqBull && tClose < tMa200
            val specialEntry = dispSlope >= SPECIAL_SLOPE && disp <= SPECIAL_DISP_MAX && tVol <= SPECIAL_VOL_MAX
            shadowTarget = when {
                entry100 -> 100.0
                entryLow && specialEntry -> 100.0
                entryLow -> LOW_EXPOSURE
                else -> 0.0
            }

            if (almostEqual(shadowTarget, 100.0)) {
                if (overheatDisp >= TRIM_MID2_DISP) {
                    s.overheatMaxDisp = s.overheatMaxDisp?.let { max(it, overheatDisp) } ?: overheatDisp
                    s.deepArmed = true
                    s.deepHit = true
                    if (overheatDisp >= TRIM5_ARM_DISP) s.trim5Armed = true
                    if (s.trim5Armed && s.overheatMaxDisp != null &&
                        overheatDisp <= s.overheatMaxDisp!! - DISP_TRAIL_GIVEBACK
                    ) {
                        s.trim5Hit = true
                    }
                } else if (overheatDisp < TRIM_MID1_DISP) {
                    s.overheatMaxDisp = null
                    s.trim5Armed = false
                    s.trim5Hit = false
                    s.deepArmed = false
                    s.deepHit = false
                }

                val profitHit = s.campaignAnchor?.let { tClose >= it * PROFIT_TRIM_RATIO } ?: false
                shadowTarget = when {
                    overheatDisp >= EXIT_DISP -> 0.0
                    s.trim5Hit -> FINAL_TRAIL_EXPOSURE
                    overheatDisp >= TRIM_MID2_DISP -> TRIM_MID2_EXPOSURE
                    overheatDisp >= TRIM_MID1_DISP -> TRIM_MID1_EXPOSURE
                    profitHit -> PROFIT_TRIM_EXPOSURE
                    else -> 100.0
                }
            }
        }

        val softAnchorPre = s.softAnchor
        val rawDrawdown = isReady && softAnchorPre != null && softAnchorPre > 0.0 &&
            tClose <= softAnchorPre * D_STOP_RATIO
        val tqqqSma200Disp = if (isReady && tMa200 > 0.0) tClose / tMa200 * 100.0 else Double.NaN
        val releaseGatePass = tqqqSma200Disp.isFinite() && tqqqSma200Disp >= D_RELEASE_SMA200_DISP

        val preRunnerStatus = s.runnerStatus
        val preReleaseStatus = s.releaseStatus
        val preActive = preRunnerStatus == RunnerStatus.ACTIVE || preReleaseStatus == ReleaseStatus.PENDING
        var episodeStarted = false
        var completion = false
        var expired = false
        var reactivated = false
        var anchorAttempt = false
        var anchorNoop = false
        var gateBlocked = false
        var anchorNew: Double? = null
        var baseEvent = ""
        var baseTarget: Double

        when {
            !isReady -> {
                check(s.runnerStatus != RunnerStatus.ACTIVE && s.releaseStatus != ReleaseStatus.PENDING) {
                    "Structurally not-ready row encountered during active 17d lifecycle on $date"
                }
                s.runnerStatus = RunnerStatus.INACTIVE
                s.triggerCount = 0
                baseTarget = 0.0
                baseEvent = "NOT_READY"
            }

            hardRisk -> {
                terminateLifecycle(s)
                baseTarget = 0.0
                baseEvent = "HARD_RISK_TERMINATION"
            }

            rawDrawdown -> {
                val activeNow = s.runnerStatus == RunnerStatus.ACTIVE || s.releaseStatus == ReleaseStatus.PENDING
                if (s.releaseStatus == ReleaseStatus.PENDING) {
                    clearReleaseConfirmation(s, retainReleaseClose = false)
                    s.episodeId += 1
                    s.runnerStatus = RunnerStatus.ACTIVE
                    s.releaseStatus = ReleaseStatus.INACTIVE
                    s.triggerCount = D_TRIGGER_ROWS
                    s.pendingAge = null
                    s.preservedPending = false
                    reactivated = true
                    baseEvent = "REACTIVATED_RESET"
                } else if (!activeNow) {
                    s.triggerCount += 1
                    if (s.triggerCount >= D_TRIGGER_ROWS) {
                        s.episodeId += 1
                        s.runnerStatus = RunnerStatus.ACTIVE
                        episodeStarted = true
                        baseEvent = "TRIGGER_COMPLETE"
                    } else {
                        s.runnerStatus = RunnerStatus.TRIGGER_CONFIRMING
                        baseEvent = "TRIGGER_CONFIRMING"
                    }
                }
                baseTarget = if (s.runnerStatus == RunnerStatus.ACTIVE) D_RUNNER_EXPOSURE else shadowTarget
            }

            else -> {
                if (s.runnerStatus == RunnerStatus.TRIGGER_CONFIRMING) {
                    s.runnerStatus = RunnerStatus.INACTIVE
                    s.triggerCount = 0
                }

                val activeNow = s.runnerStatus == RunnerStatus.ACTIVE || s.releaseStatus == ReleaseStatus.PENDING
                if (activeNow) {
                    baseTarget = D_RUNNER_EXPOSURE
                    val enteringPending = s.releaseStatus != ReleaseStatus.PENDING
                    if (enteringPending) {
                        s.pendingAge = (s.pendingAge ?: 0) + 1
                        if (!(s.preservedPending && s.releaseCloseSource != null)) s.releaseCloseSource = tClose
                        s.runnerStatus = RunnerStatus.ACTIVE
                        s.releaseStatus = ReleaseStatus.PENDING
                        s.preservedPending = false
                    } else {
                        s.pendingAge = (s.pendingAge ?: 0) + 1
                    }

                    s.riskClearCount = minOf(D_RISK_CLEAR_ROWS, s.riskClearCount + 1)
                    s.trendCount = if (qqqBull) s.trendCount + 1 else 0

                    val qPass = shadowTarget >= D_QUALIFYING_TARGET
                    when {
                        s.qLatched -> {
                            if (qPass && s.qSourceWindowOpen) {
                                s.qSourceMax = max(s.qSourceMax ?: tClose, tClose)
                                s.qSourceSum += tClose
                                s.qSourceN += 1
                            } else if (!qPass) {
                                s.qSourceWindowOpen = false
                            }
                        }

                        qPass -> {
                            if (s.qCount == 0 || !s.qSourceWindowOpen) {
                                s.firstQSource = tClose
                                s.qSourceMax = tClose
                                s.qSourceSum = tClose
                                s.qSourceN = 1
                                s.qSourceWindowOpen = true
                            } else {
                                s.qSourceMax = max(s.qSourceMax ?: tClose, tClose)
                                s.qSourceSum += tClose
                                s.qSourceN += 1
                            }
                            s.qCount = minOf(D_RELEASE_CONFIRMATION, s.qCount + 1)
                        }

                        else -> {
                            s.qCount = 0
                            s.firstQSource = null
                            s.qSourceMax = null
                            s.qSourceSum = 0.0
                            s.qSourceN = 0
                            s.qSourceWindowOpen = false
                        }
                    }

                    val releaseLine = requireNotNull(softAnchorPre) * (D_STOP_RATIO + D_RECLAIM_GAP)
                    if (!s.priceLatched && tClose > releaseLine) s.priceLatched = true
                    if (!s.qLatched && s.qCount >= D_RELEASE_CONFIRMATION) s.qLatched = true

                    val basePredicate = s.qLatched &&
                        s.riskClearCount >= D_RISK_CLEAR_ROWS && s.priceLatched
                    gateBlocked = basePredicate && !releaseGatePass
                    if (gateBlocked) baseEvent = "SMA200_GATE_BLOCKED"

                    if (basePredicate && releaseGatePass) {
                        anchorAttempt = true
                        anchorNew = max(softAnchorPre, s.releaseCloseSource ?: tClose)
                        anchorNoop = abs(anchorNew - softAnchorPre) <= EPS
                        if (anchorNoop) {
                            clearReleaseConfirmation(s, retainReleaseClose = true)
                            s.runnerStatus = RunnerStatus.ACTIVE
                            s.releaseStatus = ReleaseStatus.PENDING
                            baseEvent = "ANCHOR_NOOP_RETRY"
                        } else {
                            clearReleaseConfirmation(s, retainReleaseClose = false)
                            s.runnerStatus = RunnerStatus.INACTIVE
                            s.releaseStatus = ReleaseStatus.INACTIVE
                            s.triggerCount = 0
                            s.pendingAge = null
                            s.preservedPending = false
                            completion = true
                            baseEvent = "ANCHOR_MUTATION_COMPLETE"
                        }
                    }

                    if (!completion && s.pendingAge != null && s.pendingAge!! >= D_PENDING_EXPIRY) {
                        clearReleaseConfirmation(s, retainReleaseClose = false)
                        s.runnerStatus = RunnerStatus.INACTIVE
                        s.releaseStatus = ReleaseStatus.INACTIVE
                        s.triggerCount = 0
                        s.pendingAge = null
                        s.preservedPending = false
                        expired = true
                        baseEvent = "EXPIRED"
                    }
                } else {
                    baseTarget = shadowTarget
                }
            }
        }

        val runnerOverlayRow =
            (rawDrawdown && (preActive || episodeStarted || reactivated)) ||
                (!rawDrawdown && preActive) || gateBlocked
        if (runnerOverlayRow) {
            s.overheatMaxDisp = overheatMaxDispPre
            s.trim5Armed = trim5ArmedPre
            s.trim5Hit = trim5HitPre
            s.deepArmed = deepArmedPre
            s.deepHit = deepHitPre
        }

        if (hardRisk) baseTarget = 0.0

        val priorBasePosition = s.previousBaseTarget
        val enteredAny = almostEqual(priorBasePosition, 0.0) && baseTarget > 0.0
        val exitedToCash = priorBasePosition > 0.0 && almostEqual(baseTarget, 0.0)
        val meaningfulReduce =
            (priorBasePosition >= 80.0 && almostEqual(baseTarget, LOW_EXPOSURE)) ||
                (almostEqual(priorBasePosition, LOW_EXPOSURE) && almostEqual(baseTarget, 0.0)) ||
                (priorBasePosition >= 80.0 && almostEqual(baseTarget, 0.0))
        val forceExitNow = exitedToCash && (hardRisk || rawDrawdown)
        val cooldownTrigger = meaningfulReduce || forceExitNow
        val cooldownDone = s.hadForceExit && absPass && rsiPass
        var productionAnchorCleared = false

        when {
            enteredAny -> {
                s.campaignAnchor = tClose
                s.softAnchor = tClose
                s.campaignId += 1
                s.hadForceExit = false
                s.lastForceExitDate = null
                if (overheatDisp < TRIM_MID1_DISP) resetOverheat(s)
            }

            cooldownTrigger -> {
                s.hadForceExit = true
                s.lastForceExitDate = date
            }

            cooldownDone -> {
                s.hadForceExit = false
                s.lastForceExitDate = null
            }

            almostEqual(baseTarget, 0.0) && !s.hadForceExit -> {
                s.campaignAnchor = null
                s.softAnchor = null
                productionAnchorCleared = true
            }
        }

        if (anchorAttempt && !anchorNoop && !productionAnchorCleared) s.softAnchor = anchorNew

        if (exitedToCash || productionAnchorCleared) terminateLifecycle(s)
        if (almostEqual(baseTarget, 0.0)) resetOverheat(s)

        val baseActive = s.runnerStatus == RunnerStatus.ACTIVE ||
            s.releaseStatus == ReleaseStatus.PENDING || episodeStarted
        val selectedRisk = hardRisk // ANY_HARD
        val emergency = vixPanicSell || hardVixZero
        val emergencyAllowed = true
        val emergencyBlock = emergency && !emergencyAllowed

        if (baseActive || s.priorBaseActive) s.contrarianEpisodeLatched = true

        val cheap = contrarianRatio != null && contrarianRatio <= CONTRARIAN_CHEAP_THRESHOLD
        val rawContrarianEntry = isReady && s.contrarianEpisodeLatched && selectedRisk && cheap && !emergencyBlock
        s.contrarianConfirmation = if (rawContrarianEntry) s.contrarianConfirmation + 1 else 0
        val confirmedEntry = rawContrarianEntry && s.contrarianConfirmation >= CONTRARIAN_CONFIRMATION_ROWS
        val wasContrarianActive = s.contrarianBranchActive

        if (!s.contrarianBranchActive && confirmedEntry) {
            s.contrarianBranchActive = true
        } else if (s.contrarianBranchActive) {
            val commonHold = isReady && s.contrarianEpisodeLatched && selectedRisk && !emergencyBlock
            s.contrarianBranchActive = commonHold &&
                contrarianRatio != null && contrarianRatio < CONTRARIAN_RECLAIM_THRESHOLD
        }

        var finalTarget = if (s.contrarianBranchActive) max(baseTarget, CONTRARIAN_EXPOSURE) else baseTarget
        if (emergencyBlock) {
            finalTarget = 0.0
            s.contrarianBranchActive = false
        }

        val reason = when {
            s.contrarianBranchActive && !wasContrarianActive -> SoftRunner17dReason.CONTRARIAN_ENTER
            wasContrarianActive && !s.contrarianBranchActive -> when {
                emergencyBlock -> SoftRunner17dReason.CONTRARIAN_EXIT_EMERGENCY
                !selectedRisk -> SoftRunner17dReason.CONTRARIAN_EXIT_RISK_CLEAR
                contrarianRatio != null && contrarianRatio >= CONTRARIAN_RECLAIM_THRESHOLD ->
                    SoftRunner17dReason.CONTRARIAN_EXIT_SMA_RECLAIM
                else -> SoftRunner17dReason.CONTRARIAN_EXIT_OTHER
            }
            s.contrarianBranchActive -> if (emergency) {
                SoftRunner17dReason.CONTRARIAN_PANIC_OVERRIDE_ACTIVE
            } else {
                SoftRunner17dReason.CONTRARIAN_ACTIVE_HOLD
            }
            baseEvent.isNotEmpty() -> baseReason(baseEvent)
            !almostEqual(finalTarget, s.previousFinalTarget) -> SoftRunner17dReason.BASE_TARGET_CHANGE
            else -> SoftRunner17dReason.HOLD
        }

        val signal = SoftRunner17dSignal(
            date = date,
            isReady = isReady,
            baseShadowTarget = shadowTarget,
            baseTarget = baseTarget,
            finalTarget = finalTarget,
            previousFinalTarget = s.previousFinalTarget,
            reason = reason,
            baseEvent = baseEvent,
            runnerStatus = s.runnerStatus,
            releaseStatus = s.releaseStatus,
            softRunnerActive = s.runnerStatus == RunnerStatus.ACTIVE || s.releaseStatus == ReleaseStatus.PENDING,
            softRunnerEpisodeStarted = episodeStarted,
            volRisk = volRisk,
            spyRisk = spyRisk,
            vixPanicSell = vixPanicSell,
            hardVixZero = hardVixZero,
            hardRisk = hardRisk,
            contrarianEpisodeLatched = s.contrarianEpisodeLatched,
            contrarianRiskSelected = selectedRisk,
            contrarianCheap = cheap,
            contrarianReclaim = contrarianRatio != null && contrarianRatio >= CONTRARIAN_RECLAIM_THRESHOLD,
            contrarianEligible = rawContrarianEntry,
            contrarianActive = s.contrarianBranchActive,
            tqqqClose = tClose,
            tqqqSma290 = tSma290,
            tqqqSma290Ratio = contrarianRatio,
            tqqqMa200 = input.tqqqMa200,
            vixClose = vixClose,
        )

        s.previousBaseTarget = baseTarget
        s.previousFinalTarget = finalTarget
        if ((completion || expired || !selectedRisk) && !baseActive) {
            s.contrarianEpisodeLatched = false
            s.contrarianBranchActive = false
            s.contrarianConfirmation = 0
        }
        s.priorBaseActive = baseActive
        s.lastProcessedDate = date

        return SoftRunner17dStep(s, signal)
    }

    private fun terminateLifecycle(s: SoftRunner17dState) {
        s.runnerStatus = RunnerStatus.INACTIVE
        s.releaseStatus = ReleaseStatus.INACTIVE
        s.triggerCount = 0
        s.qCount = 0
        s.riskClearCount = 0
        s.trendCount = 0
        s.priceLatched = false
        s.qLatched = false
        s.pendingAge = null
        s.preservedPending = false
        s.releaseCloseSource = null
        s.firstQSource = null
        s.qSourceMax = null
        s.qSourceSum = 0.0
        s.qSourceN = 0
        s.qSourceWindowOpen = false
    }

    private fun clearReleaseConfirmation(s: SoftRunner17dState, retainReleaseClose: Boolean) {
        s.qCount = 0
        s.riskClearCount = 0
        s.trendCount = 0
        s.priceLatched = false
        s.qLatched = false
        if (!retainReleaseClose) s.releaseCloseSource = null
        s.firstQSource = null
        s.qSourceMax = null
        s.qSourceSum = 0.0
        s.qSourceN = 0
        s.qSourceWindowOpen = false
    }

    private fun resetOverheat(s: SoftRunner17dState) {
        s.overheatMaxDisp = null
        s.trim5Armed = false
        s.trim5Hit = false
        s.deepArmed = false
        s.deepHit = false
    }

    private fun baseReason(event: String): SoftRunner17dReason = when (event) {
        "NOT_READY" -> SoftRunner17dReason.BASE_NOT_READY
        "HARD_RISK_TERMINATION" -> SoftRunner17dReason.BASE_HARD_RISK_TERMINATION
        "TRIGGER_CONFIRMING" -> SoftRunner17dReason.BASE_TRIGGER_CONFIRMING
        "TRIGGER_COMPLETE" -> SoftRunner17dReason.BASE_TRIGGER_COMPLETE
        "REACTIVATED_RESET" -> SoftRunner17dReason.BASE_REACTIVATED_RESET
        "SMA200_GATE_BLOCKED" -> SoftRunner17dReason.BASE_SMA200_GATE_BLOCKED
        "ANCHOR_NOOP_RETRY" -> SoftRunner17dReason.BASE_ANCHOR_NOOP_RETRY
        "ANCHOR_MUTATION_COMPLETE" -> SoftRunner17dReason.BASE_ANCHOR_MUTATION_COMPLETE
        "EXPIRED" -> SoftRunner17dReason.BASE_EXPIRED
        else -> SoftRunner17dReason.HOLD
    }

    private fun almostEqual(a: Double, b: Double): Boolean = abs(a - b) <= EPS
}
