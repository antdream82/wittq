package com.fortq.wittq

import java.time.LocalDate

enum class RunnerStatus(val code: Int) {
    INACTIVE(0),
    TRIGGER_CONFIRMING(1),
    ACTIVE(2),
}

enum class ReleaseStatus(val code: Int) {
    INACTIVE(0),
    PENDING(1),
}

enum class SoftRunner17dReason(val code: Int, val label: String) {
    HOLD(0, "HOLD"),
    BASE_TARGET_CHANGE(1, "BASE_TARGET_CHANGE"),
    BASE_NOT_READY(10, "BASE_NOT_READY"),
    BASE_HARD_RISK_TERMINATION(11, "BASE_HARD_RISK_TERMINATION"),
    BASE_TRIGGER_CONFIRMING(12, "BASE_TRIGGER_CONFIRMING"),
    BASE_TRIGGER_COMPLETE(13, "BASE_TRIGGER_COMPLETE"),
    BASE_REACTIVATED_RESET(14, "BASE_REACTIVATED_RESET"),
    BASE_SMA200_GATE_BLOCKED(18, "BASE_SMA200_GATE_BLOCKED"),
    BASE_ANCHOR_NOOP_RETRY(19, "BASE_ANCHOR_NOOP_RETRY"),
    BASE_ANCHOR_MUTATION_COMPLETE(21, "BASE_ANCHOR_MUTATION_COMPLETE"),
    BASE_EXPIRED(22, "BASE_EXPIRED"),
    CONTRARIAN_ENTER(100, "CONTRARIAN_ENTER"),
    CONTRARIAN_ACTIVE_HOLD(101, "CONTRARIAN_ACTIVE_HOLD"),
    CONTRARIAN_PANIC_OVERRIDE_ACTIVE(102, "CONTRARIAN_PANIC_OVERRIDE_ACTIVE"),
    CONTRARIAN_EXIT_RISK_CLEAR(110, "CONTRARIAN_EXIT_RISK_CLEAR"),
    CONTRARIAN_EXIT_SMA_RECLAIM(111, "CONTRARIAN_EXIT_SMA_RECLAIM"),
    CONTRARIAN_EXIT_EMERGENCY(112, "CONTRARIAN_EXIT_EMERGENCY"),
    CONTRARIAN_EXIT_OTHER(119, "CONTRARIAN_EXIT_OTHER"),
}

data class SoftRunner17dBar(
    val date: LocalDate,
    val qqqClose: Double,
    val tqqqClose: Double,
    val spyClose: Double,
    val vixClose: Double,
)

data class SoftRunner17dRowInput(
    val bar: SoftRunner17dBar,
    val qqqMa3: Double?,
    val qqqMa161: Double?,
    val qqqRsi14: Double?,
    val tqqqMa200: Double?,
    val tqqqMa210: Double?,
    val tqqqVol20: Double?,
    val tqqqDispSlope45: Double?,
    val spyMa200: Double?,
    val tqqqSma290: Double?,
)

data class SoftRunner17dState(
    var campaignAnchor: Double? = null,
    var softAnchor: Double? = null,
    var previousBaseTarget: Double = 0.0,
    var hadForceExit: Boolean = false,
    var lastForceExitDate: LocalDate? = null,

    var vixLock: Boolean = false,
    var vixCalmDays: Int = 0,

    var trim5Armed: Boolean = false,
    var trim5Hit: Boolean = false,
    var deepArmed: Boolean = false,
    var deepHit: Boolean = false,
    var overheatMaxDisp: Double? = null,

    var campaignId: Int = 0,
    var episodeId: Int = 0,
    var runnerStatus: RunnerStatus = RunnerStatus.INACTIVE,
    var releaseStatus: ReleaseStatus = ReleaseStatus.INACTIVE,
    var triggerCount: Int = 0,
    var qCount: Int = 0,
    var riskClearCount: Int = 0,
    var trendCount: Int = 0,
    var priceLatched: Boolean = false,
    var qLatched: Boolean = false,
    var pendingAge: Int? = null,
    var preservedPending: Boolean = false,
    var releaseCloseSource: Double? = null,
    var firstQSource: Double? = null,
    var qSourceMax: Double? = null,
    var qSourceSum: Double = 0.0,
    var qSourceN: Int = 0,
    var qSourceWindowOpen: Boolean = false,

    var contrarianEpisodeLatched: Boolean = false,
    var contrarianBranchActive: Boolean = false,
    var contrarianConfirmation: Int = 0,
    var priorBaseActive: Boolean = false,
    var previousFinalTarget: Double = 0.0,
    var lastProcessedDate: LocalDate? = null,
)

data class SoftRunner17dSignal(
    val date: LocalDate,
    val isReady: Boolean,
    val baseShadowTarget: Double,
    val baseTarget: Double,
    val finalTarget: Double,
    val previousFinalTarget: Double,
    val reason: SoftRunner17dReason,
    val baseEvent: String,

    val runnerStatus: RunnerStatus,
    val releaseStatus: ReleaseStatus,
    val softRunnerActive: Boolean,
    val softRunnerEpisodeStarted: Boolean,

    val volRisk: Boolean,
    val spyRisk: Boolean,
    val vixPanicSell: Boolean,
    val hardVixZero: Boolean,
    val hardRisk: Boolean,

    val contrarianEpisodeLatched: Boolean,
    val contrarianRiskSelected: Boolean,
    val contrarianCheap: Boolean,
    val contrarianReclaim: Boolean,
    val contrarianEligible: Boolean,
    val contrarianActive: Boolean,

    val tqqqClose: Double,
    val tqqqSma290: Double?,
    val tqqqSma290Ratio: Double?,
    val tqqqMa200: Double?,
    val vixClose: Double,
)

data class SoftRunner17dStep(
    val nextState: SoftRunner17dState,
    val signal: SoftRunner17dSignal,
)

data class SoftRunner17dReplayResult(
    val finalState: SoftRunner17dState,
    val signals: List<SoftRunner17dSignal>,
) {
    val latest: SoftRunner17dSignal? get() = signals.lastOrNull()
}

data class SoftRunner17dAppSnapshot(
    val official: SoftRunner17dSignal,
    val preview: SoftRunner17dSignal,
    val officialDate: LocalDate,
    val previewDate: LocalDate,
    val priceHistory: List<Double>,
    val sma290History: List<Double?>,
    val trailingReturn1y: Double?,
    val trailingReturn6m: Double?,
    val trailingReturn3m: Double?,
    val sourceLatestDates: Map<String, LocalDate?>,
    val updatedAtMillis: Long,
    val stale: Boolean,
    val statusMessage: String,
)
