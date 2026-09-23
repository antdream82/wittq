package com.fortq.wittq

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object MarketRefreshSchedule {
    val newYorkZone: ZoneId = ZoneId.of("America/New_York")

    private val marketOpen = LocalTime.of(9, 30)
    private val marketClose = LocalTime.of(16, 0)
    private val closeFinalize = LocalTime.of(16, 20)
    private val closeRetry = LocalTime.of(16, 40)

    fun nextSlot(
        now: ZonedDateTime = ZonedDateTime.now(newYorkZone),
        closeFinalized: Boolean = false,
    ): ZonedDateTime {
        val ny = now.withZoneSameInstant(newYorkZone)
        val date = ny.toLocalDate()
        val time = ny.toLocalTime()

        if (!isWeekday(date)) return nextWeekdayOpen(date.plusDays(1))

        if (time.isBefore(marketOpen)) {
            return ZonedDateTime.of(date, marketOpen, newYorkZone)
        }

        if (time.isBefore(marketClose)) {
            val minuteOfDay = time.hour * 60 + time.minute
            val nextMinute = ((minuteOfDay / 15) + 1) * 15
            val nextTime = LocalTime.of(nextMinute / 60, nextMinute % 60)
            return ZonedDateTime.of(date, nextTime, newYorkZone)
        }

        if (time.isBefore(closeFinalize)) {
            return ZonedDateTime.of(date, closeFinalize, newYorkZone)
        }

        if (time.isBefore(closeRetry) && !closeFinalized) {
            return ZonedDateTime.of(date, closeRetry, newYorkZone)
        }

        return nextWeekdayOpen(date.plusDays(1))
    }

    private fun nextWeekdayOpen(start: LocalDate): ZonedDateTime {
        var date = start
        while (!isWeekday(date)) date = date.plusDays(1)
        return ZonedDateTime.of(date, marketOpen, newYorkZone)
    }

    private fun isWeekday(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
}
