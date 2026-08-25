package com.fortq.wittq

import android.content.Context
import com.google.gson.Gson

/**
 * Shared read facade for market history used by 17d, AGTQ and Snow.
 *
 * Daily history is retained durably in the existing SQLite store. The most
 * recent Yahoo response already lives in the short-lived YahooCache and is
 * used only for currentPrice/prevClose. This lets legacy 2y callers reuse the
 * same durable history without another Yahoo request.
 */
object SharedMarketDataStore {
    private const val CACHE_PREFS = "YahooCache"
    private const val CACHE_PREFIX = "market_data_"
    private const val MIN_SHARED_HISTORY_ROWS = 220
    private val gson = Gson()

    private val sharedSymbols = setOf("TQQQ", "QQQ", "SPY", "^VIX")

    fun isSharedSymbol(symbol: String): Boolean =
        sharedSymbols.contains(symbol.trim().uppercase())

    fun read(context: Context, symbol: String): MarketData? {
        if (!isSharedSymbol(symbol)) return null

        val durable = SoftRunner17dHistoryStore.get(context).read(symbol) ?: return null
        if (durable.safeHistory().size < MIN_SHARED_HISTORY_ROWS) return null

        val latestCached = latestYahooCache(context, symbol)
        val history = durable.safeHistory()
        val current = latestCached?.data?.currentPrice
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: history.last()
        val previous = latestCached?.data?.prevClose
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: history.getOrElse(history.lastIndex - 1) { history.last() }

        return durable.copy(
            currentPrice = current,
            prevClose = previous,
        )
    }

    private fun latestYahooCache(context: Context, symbol: String): CachedMarketData? {
        val prefs = context.applicationContext
            .getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val keyPrefix = "${CACHE_PREFIX}${sanitize(symbol)}_${sanitize("1d")}_"

        return prefs.all.asSequence()
            .filter { (key, value) -> key.startsWith(keyPrefix) && value is String }
            .mapNotNull { (_, value) ->
                runCatching { gson.fromJson(value as String, CachedMarketData::class.java) }
                    .getOrNull()
            }
            .filter { cached ->
                cached.data.safeHistory().isNotEmpty() &&
                    cached.data.safeTimestamps().isNotEmpty()
            }
            .maxByOrNull { it.savedAtMs }
    }

    private fun sanitize(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "_")
}
