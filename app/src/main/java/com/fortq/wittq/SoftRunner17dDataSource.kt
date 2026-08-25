package com.fortq.wittq

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object SoftRunner17dDataSource {
    private val newYorkZone: ZoneId = ZoneId.of("America/New_York")
    private val officialCutoff: LocalTime = LocalTime.of(16, 20)

    private const val BOOTSTRAP_SOURCE = "period-2010-daily"
    private const val NORMAL_REFRESH_RANGE = "1mo"
    private const val MIN_BOOTSTRAP_ROWS = 290
    private const val BOOTSTRAP_RETRY_DELAY_MS = 900L
    private val bootstrapStartEpochSec: Long = LocalDate.of(2010, 2, 1)
        .atStartOfDay(ZoneOffset.UTC)
        .toEpochSecond()

    suspend fun load(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): SoftRunner17dAppSnapshot {
        val nowNy = Instant.ofEpochMilli(nowMillis).atZone(newYorkZone)
        val today = nowNy.toLocalDate()
        val store = SoftRunner17dHistoryStore.get(context)

        suspend fun loadSymbol(symbol: String): MarketData {
            val before = store.stats(symbol)
            val bootstrapped = store.isBootstrapComplete(symbol) && before.rowCount >= MIN_BOOTSTRAP_ROWS

            var sourceRange: String
            var network: MarketData?

            if (!bootstrapped) {
                // Do not use Yahoo range=max here. Long max requests can be
                // silently coarsened to monthly data (~199 TQQQ rows since 2010).
                // Explicit period bounds force true 1d bars from strategy inception.
                val period2 = nowMillis / 1000L + 86_400L
                sourceRange = BOOTSTRAP_SOURCE
                network = StockApiEngine.fetchMarketDataPeriod(
                    context = context,
                    symbol = symbol,
                    period1EpochSec = bootstrapStartEpochSec,
                    period2EpochSec = period2,
                    interval = "1d",
                )

                if (network == null || network.safeHistory().size < MIN_BOOTSTRAP_ROWS) {
                    val received = network?.safeHistory()?.size ?: 0
                    Log.w(
                        "SOFT_RUNNER_17D_DB",
                        "$symbol explicit-period bootstrap returned $received rows; retrying once",
                    )
                    delay(BOOTSTRAP_RETRY_DELAY_MS)
                    network = StockApiEngine.fetchMarketDataPeriod(
                        context = context,
                        symbol = symbol,
                        period1EpochSec = bootstrapStartEpochSec,
                        period2EpochSec = period2,
                        interval = "1d",
                    )
                }
            } else {
                sourceRange = chooseIncrementalRange(before.latestDate, today)
                network = StockApiEngine.fetchMarketData(context, symbol, sourceRange)
            }

            if (network != null) {
                store.upsert(symbol, network, sourceRange, nowMillis)
                val after = store.stats(symbol)
                if (!bootstrapped && after.rowCount >= MIN_BOOTSTRAP_ROWS) {
                    store.markBootstrapComplete(symbol)
                }
                Log.d(
                    "SOFT_RUNNER_17D_DB",
                    "$symbol refresh=$sourceRange localRows=${after.rowCount} localLatest=${after.latestDate}",
                )
            } else {
                Log.w(
                    "SOFT_RUNNER_17D_DB",
                    "$symbol network refresh failed for $sourceRange; attempting durable local history",
                )
            }

            val local = store.read(symbol)
            requireNotNull(local) { "$symbol local history unavailable; refresh to retry bootstrap" }
            require(local.history.size >= MIN_BOOTSTRAP_ROWS) {
                "$symbol local history has only ${local.history.size} rows; bootstrap required"
            }

            // Current/previous prices are ephemeral. Historical closes come from SQLite.
            return if (network != null) {
                local.copy(
                    currentPrice = network.currentPrice,
                    prevClose = network.prevClose,
                )
            } else {
                local
            }
        }

        // Deliberately sequential. Each successful symbol is durable, so a later
        // Yahoo failure does not make already-completed bootstrap work repeat.
        val tqqq = loadSymbol("TQQQ")
        val qqq = loadSymbol("QQQ")
        val spy = loadSymbol("SPY")
        val vix = loadSymbol("^VIX")

        return calculate(
            tqqq,
            qqq,
            spy,
            vix,
            nowMillis,
        )
    }

    /**
     * Chooses the smallest Yahoo range expected to overlap retained local history.
     * Normal use is 1mo. A device left unused for months/years expands only enough
     * to bridge the gap. Fresh installs never come through here; they use explicit
     * period1/period2 bootstrap above.
     */
    internal fun chooseIncrementalRange(latestLocalDate: LocalDate?, today: LocalDate): String {
        if (latestLocalDate == null) return "5y"
        val days = ChronoUnit.DAYS.between(latestLocalDate, today).coerceAtLeast(0)
        return when {
            days <= 20 -> NORMAL_REFRESH_RANGE
            days <= 80 -> "3mo"
            days <= 170 -> "6mo"
            days <= 350 -> "1y"
            days <= 700 -> "2y"
            else -> "5y"
        }
    }

    fun calculate(
        tqqq: MarketData,
        qqq: MarketData,
        spy: MarketData,
        vix: MarketData,
        nowMillis: Long = System.currentTimeMillis(),
    ): SoftRunner17dAppSnapshot {
        val nowNy = Instant.ofEpochMilli(nowMillis).atZone(newYorkZone)
        val today = nowNy.toLocalDate()

        val tqMap = dailyMap(tqqq)
        val qMap = dailyMap(qqq)
        val spyMap = dailyMap(spy)
        val vixMap = dailyMap(vix)
        require(tqMap.isNotEmpty() && qMap.isNotEmpty() && spyMap.isNotEmpty() && vixMap.isNotEmpty()) {
            "One or more Yahoo/local histories are empty"
        }

        val sourceDates = linkedMapOf(
            "TQQQ" to tqMap.keys.maxOrNull(),
            "QQQ" to qMap.keys.maxOrNull(),
            "SPY" to spyMap.keys.maxOrNull(),
            "VIX" to vixMap.keys.maxOrNull(),
        )

        val commonDates = tqMap.keys.intersect(qMap.keys)
            .intersect(spyMap.keys)
            .intersect(vixMap.keys)
            .sorted()
        require(commonDates.isNotEmpty()) { "No common TQQQ/QQQ/SPY/VIX dates" }

        val latestCommon = commonDates.last()
        val officialDates = if (isClosedDailyDate(latestCommon, nowNy)) {
            commonDates
        } else {
            commonDates.dropLast(1)
        }
        require(officialDates.size >= MIN_BOOTSTRAP_ROWS) {
            "Insufficient aligned history (${officialDates.size} rows); $MIN_BOOTSTRAP_ROWS+ required"
        }

        // Replay is local-only. No long-range network request is needed here.
        val officialBars = officialDates.map { date ->
            SoftRunner17dBar(
                date = date,
                qqqClose = qMap.getValue(date),
                tqqqClose = tqMap.getValue(date),
                spyClose = spyMap.getValue(date),
                vixClose = vixMap.getValue(date),
            )
        }
        val officialReplay = SoftRunner17dReplay.replay(officialBars)
        val official = requireNotNull(officialReplay.latest)

        // Performance uses only completed OFFICIAL rows. Same-close execution means
        // the position decided at close t earns the TQQQ return from t to t+1.
        val trailingReturn1y = SoftRunner17dReturns.trailingPercent(
            officialBars,
            officialReplay.signals,
            months = 12,
        )
        val trailingReturn6m = SoftRunner17dReturns.trailingPercent(
            officialBars,
            officialReplay.signals,
            months = 6,
        )
        val trailingReturn3m = SoftRunner17dReturns.trailingPercent(
            officialBars,
            officialReplay.signals,
            months = 3,
        )

        val currentPricesValid = listOf(
            tqqq.currentPrice,
            qqq.currentPrice,
            spy.currentPrice,
            vix.currentPrice,
        ).all { it.isFinite() && it > 0.0 }
        val previewWindow = isWeekday(today) &&
            !nowNy.toLocalTime().isBefore(LocalTime.of(9, 30)) &&
            nowNy.toLocalTime().isBefore(LocalTime.of(17, 20))
        val sourcesCoverOfficialDate = sourceDates.values.all { latest ->
            latest != null && !latest.isBefore(official.date)
        }
        val previewBar = if (
            official.date < today &&
            previewWindow &&
            sourcesCoverOfficialDate &&
            currentPricesValid
        ) {
            SoftRunner17dBar(
                date = today,
                qqqClose = qqq.currentPrice,
                tqqqClose = tqqq.currentPrice,
                spyClose = spy.currentPrice,
                vixClose = vix.currentPrice,
            )
        } else {
            null
        }

        val previewBars = if (previewBar != null) officialBars + previewBar else officialBars
        val previewReplay = if (previewBar != null) SoftRunner17dReplay.replay(previewBars) else officialReplay
        val preview = requireNotNull(previewReplay.latest)
        val sma290 = SoftRunner17dReplay.sma290Series(previewBars)

        val ageDays = ChronoUnit.DAYS.between(official.date, today)
        val stale = ageDays > 4 || sourceDates.values.any { it == null }
        val status = when {
            stale -> "STALE: official ${official.date}"
            preview.date > official.date -> "LIVE PREVIEW ${preview.date} / OFFICIAL ${official.date}"
            else -> "OFFICIAL ${official.date}"
        }

        return SoftRunner17dAppSnapshot(
            official = official,
            preview = preview,
            officialDate = official.date,
            previewDate = preview.date,
            priceHistory = previewBars.map { it.tqqqClose }.takeLast(120),
            sma290History = sma290.takeLast(120),
            trailingReturn1y = trailingReturn1y,
            trailingReturn6m = trailingReturn6m,
            trailingReturn3m = trailingReturn3m,
            sourceLatestDates = sourceDates,
            updatedAtMillis = nowMillis,
            stale = stale,
            statusMessage = status,
        )
    }

    private fun dailyMap(data: MarketData): Map<LocalDate, Double> {
        val result = linkedMapOf<LocalDate, Double>()
        data.safeTimestamps().zip(data.safeHistory()).forEach { (timestamp, close) ->
            if (close.isFinite() && close > 0.0) {
                val date = Instant.ofEpochMilli(timestamp).atZone(newYorkZone).toLocalDate()
                result[date] = close
            }
        }
        return result
    }

    private fun isClosedDailyDate(date: LocalDate, nowNy: ZonedDateTime): Boolean = when {
        date.isBefore(nowNy.toLocalDate()) -> true
        date.isAfter(nowNy.toLocalDate()) -> false
        !isWeekday(date) -> false
        else -> !nowNy.toLocalTime().isBefore(officialCutoff)
    }

    private fun isWeekday(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
}
