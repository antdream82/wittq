package com.fortq.wittq

fun MarketData.safeHistory(): List<Double> = runCatching { history }.getOrDefault(emptyList())

fun MarketData.safeTimestamps(): List<Long> = runCatching { timestamps }.getOrDefault(emptyList())

fun YahooResultData.safeTimestamps(): List<Long> = runCatching { timestamp }.getOrDefault(emptyList())

fun YahooResultData.safeCloses(): List<Double?> =
    runCatching { indicators.quote.firstOrNull()?.close }.getOrNull() ?: emptyList()
