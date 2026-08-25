package com.fortq.wittq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SoftRunner17dDataSourceTest {
    private val zone = ZoneId.of("America/New_York")

    private fun data(start: LocalDate, count: Int, base: Double, current: Double): MarketData {
        val dates = (0 until count).map { start.plusDays(it.toLong()) }
        return MarketData(
            currentPrice = current,
            prevClose = current - 1.0,
            history = dates.mapIndexed { index, _ -> base + index * 0.1 },
            timestamps = dates.map {
                ZonedDateTime.of(it, java.time.LocalTime.of(9, 30), zone).toInstant().toEpochMilli()
            },
        )
    }

    @Test
    fun calculationUsesCommonDatesAndProducesOfficialSnapshot() {
        val start = LocalDate.of(2010, 2, 11)
        val count = 600
        val lastDate = start.plusDays((count - 1).toLong())
        val now = ZonedDateTime.of(lastDate.plusDays(1), java.time.LocalTime.of(12, 0), zone)
            .toInstant().toEpochMilli()
        val snapshot = SoftRunner17dDataSource.calculate(
            tqqq = data(start, count, 50.0, 110.0),
            qqq = data(start, count, 100.0, 200.0),
            spy = data(start, count, 120.0, 220.0),
            vix = data(start, count, 20.0, 20.0),
            nowMillis = now,
        )
        assertEquals(lastDate, snapshot.officialDate)
        assertEquals(snapshot.officialDate, snapshot.previewDate)
        assertTrue(snapshot.official.isReady)
    }

    @Test
    fun incrementalRangeUsesSmallestReasonableOverlapWindow() {
        val today = LocalDate.of(2026, 8, 25)
        assertEquals("max", SoftRunner17dDataSource.chooseIncrementalRange(null, today))
        assertEquals("1mo", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(5), today))
        assertEquals("3mo", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(45), today))
        assertEquals("6mo", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(120), today))
        assertEquals("1y", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(250), today))
        assertEquals("2y", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(500), today))
        assertEquals("5y", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(1_000), today))
        assertEquals("10y", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(2_000), today))
        assertEquals("max", SoftRunner17dDataSource.chooseIncrementalRange(today.minusDays(4_000), today))
    }
}
