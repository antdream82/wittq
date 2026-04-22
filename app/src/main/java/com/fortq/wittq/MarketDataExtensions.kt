package com.fortq.wittq

@Suppress("UNCHECKED_CAST")
fun MarketData.safeHistory(): List<Double> = (history as? List<Double>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun MarketData.safeTimestamps(): List<Long> = (timestamps as? List<Long>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun YahooResultData.safeTimestamps(): List<Long> = (timestamp as? List<Long>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun YahooResultData.safeCloses(): List<Double?> =
    ((indicators.quote.firstOrNull()?.close) as? List<Double?>) ?: emptyList()
