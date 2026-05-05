package com.fortq.wittq

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val newYorkZone: ZoneId = ZoneId.of("America/New_York")
private val marketCloseGrace = LocalTime.of(17, 0)

private fun shouldUseLatestClosedBar(nowMs: Long = System.currentTimeMillis()): Boolean {
    val now = Instant.ofEpochMilli(nowMs).atZone(newYorkZone)
    val isWeekday = now.dayOfWeek != DayOfWeek.SATURDAY && now.dayOfWeek != DayOfWeek.SUNDAY
    return !isWeekday || now.toLocalTime() >= marketCloseGrace
}

@Suppress("UNCHECKED_CAST")
fun MarketData.safeHistory(): List<Double> = (history as? List<Double>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun MarketData.safeTimestamps(): List<Long> = (timestamps as? List<Long>) ?: emptyList()

fun MarketData.safeClosedHistory(nowMs: Long = System.currentTimeMillis()): List<Double> {
    val values = safeHistory()
    if (values.isEmpty()) return emptyList()
    if (shouldUseLatestClosedBar(nowMs) || values.size == 1) return values
    val closed = values.dropLast(1)
    return if (closed.isNotEmpty()) closed else values
}

fun MarketData.safeClosedTimestamps(nowMs: Long = System.currentTimeMillis()): List<Long> {
    val values = safeTimestamps()
    if (values.isEmpty()) return emptyList()
    if (shouldUseLatestClosedBar(nowMs) || values.size == 1) return values
    val closed = values.dropLast(1)
    return if (closed.isNotEmpty()) closed else values
}

@Suppress("UNCHECKED_CAST")
fun YahooResultData.safeTimestamps(): List<Long> = (timestamp as? List<Long>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun YahooResultData.safeCloses(): List<Double?> =
    ((indicators.quote.firstOrNull()?.close) as? List<Double?>) ?: emptyList()
