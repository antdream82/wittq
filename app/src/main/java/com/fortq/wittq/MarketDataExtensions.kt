package com.fortq.wittq

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

private val newYorkZone: ZoneId = ZoneId.of("America/New_York")
private val marketCloseGrace = LocalTime.of(17, 20)

private fun shouldDropLatestUnclosedBar(timestamps: List<Long>, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (timestamps.isEmpty()) return false
    val now = Instant.ofEpochMilli(nowMs).atZone(newYorkZone)
    val latest = Instant.ofEpochMilli(timestamps.last()).atZone(newYorkZone)
    val latestDate = latest.toLocalDate()
    val today = now.toLocalDate()
    if (latestDate.isBefore(today)) return false
    if (latestDate.isAfter(today)) return true
    val isWeekday = now.dayOfWeek != DayOfWeek.SATURDAY && now.dayOfWeek != DayOfWeek.SUNDAY
    return isWeekday && now.toLocalTime() < marketCloseGrace
}

@Suppress("UNCHECKED_CAST")
fun MarketData.safeHistory(): List<Double> = (history as? List<Double>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun MarketData.safeTimestamps(): List<Long> = (timestamps as? List<Long>) ?: emptyList()

fun MarketData.safeClosedHistory(nowMs: Long = System.currentTimeMillis()): List<Double> {
    val values = safeHistory()
    val times = safeTimestamps()
    if (values.isEmpty()) return emptyList()
    if (values.size == 1 || !shouldDropLatestUnclosedBar(times, nowMs)) return values
    val closed = values.dropLast(1)
    return if (closed.isNotEmpty()) closed else values
}

fun MarketData.safeClosedTimestamps(nowMs: Long = System.currentTimeMillis()): List<Long> {
    val values = safeTimestamps()
    if (values.isEmpty()) return emptyList()
    if (values.size == 1 || !shouldDropLatestUnclosedBar(values, nowMs)) return values
    val closed = values.dropLast(1)
    return if (closed.isNotEmpty()) closed else values
}

@Suppress("UNCHECKED_CAST")
fun YahooResultData.safeTimestamps(): List<Long> = (timestamp as? List<Long>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun YahooResultData.safeCloses(): List<Double?> =
    ((indicators.quote.firstOrNull()?.close) as? List<Double?>) ?: emptyList()
