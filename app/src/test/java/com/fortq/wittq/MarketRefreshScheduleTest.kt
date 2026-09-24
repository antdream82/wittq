package com.fortq.wittq

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class MarketRefreshScheduleTest {
    private val zone = MarketRefreshSchedule.newYorkZone

    private fun ny(text: String): ZonedDateTime =
        ZonedDateTime.parse(text).withZoneSameInstant(zone)

    @Test
    fun preOpenSchedulesOpeningSlot() {
        assertEquals(
            "2026-09-22T09:30-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-22T09:00:00-04:00")).toString(),
        )
    }

    @Test
    fun activeMarketUsesFixedQuarterHourSlotsWithoutDrift() {
        assertEquals(
            "2026-09-22T10:15-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-22T10:07:31-04:00")).toString(),
        )
        assertEquals(
            "2026-09-22T16:00-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-22T15:59:20-04:00")).toString(),
        )
    }

    @Test
    fun postCloseUsesFinalizeAndConditionalRetryOnly() {
        assertEquals(
            "2026-09-22T16:20-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-22T16:07:00-04:00")).toString(),
        )
        assertEquals(
            "2026-09-22T16:40-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(
                ny("2026-09-22T16:22:00-04:00"),
                closeFinalized = false,
            ).toString(),
        )
        assertEquals(
            "2026-09-23T09:30-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(
                ny("2026-09-22T16:22:00-04:00"),
                closeFinalized = true,
            ).toString(),
        )
    }

    @Test
    fun exactSlotBoundariesAdvanceWithoutDrift() {
        assertEquals(
            "2026-09-22T09:45-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-22T09:30:00-04:00")).toString(),
        )
        assertEquals(
            "2026-09-22T10:00-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-22T09:45:01-04:00")).toString(),
        )
        assertEquals(
            "2026-09-22T16:40-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(
                ny("2026-09-22T16:20:00-04:00"),
                closeFinalized = false,
            ).toString(),
        )
    }

    @Test
    fun afterRetryAndWeekendSleepUntilNextWeekdayOpen() {
        assertEquals(
            "2026-09-23T09:30-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-22T16:41:00-04:00")).toString(),
        )
        assertEquals(
            "2026-09-28T09:30-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-25T17:00:00-04:00")).toString(),
        )
        assertEquals(
            "2026-09-28T09:30-04:00[America/New_York]",
            MarketRefreshSchedule.nextSlot(ny("2026-09-26T12:00:00-04:00")).toString(),
        )
    }
}
