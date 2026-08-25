package com.fortq.wittq

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap

data class YahooResponse(val chart: YahooChart)
data class YahooChart(val result: List<YahooResultData>?)
data class YahooResultData(
    val meta: YahooMeta,
    val indicators: YahooIndicators,
    val timestamp: List<Long> = emptyList(),
)
data class YahooIndicators(val quote: List<YahooQuote>)
data class YahooQuote(val close: List<Double?>)

data class YahooMeta(
    val regularMarketPrice: Double,
    val previousClose: Double,
)

data class MarketData(
    val currentPrice: Double,
    val prevClose: Double,
    val history: List<Double>,
    val timestamps: List<Long> = emptyList(),
)

data class CachedMarketData(
    val savedAtMs: Long,
    val data: MarketData,
)

interface YahooApiService {
    @GET("v8/finance/chart/{symbol}")
    suspend fun getHistory(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1d",
        @Query("range") range: String = "2y",
    ): YahooResponse
}

object StockApiEngine {
    private const val CACHE_PREFS = "YahooCache"
    private const val CACHE_PREFIX = "market_data_"
    private const val CACHE_LAST_ERROR = "last_yahoo_error"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val LONG_HISTORY_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val CACHE_STALE_MS = 24 * 60 * 60 * 1000L
    private val symbolLocks = ConcurrentHashMap<String, Mutex>()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://query1.finance.yahoo.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: YahooApiService = retrofit.create(YahooApiService::class.java)
    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    private fun sanitize(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "_")

    private fun cacheKey(symbol: String, interval: String, range: String): String =
        "${CACHE_PREFIX}${sanitize(symbol)}_${sanitize(interval)}_${sanitize(range)}"

    private fun removeCachedMarketData(context: Context, symbol: String, interval: String, range: String) {
        prefs(context).edit { remove(cacheKey(symbol, interval, range)) }
    }

    private fun setLastError(context: Context, message: String?) {
        prefs(context).edit {
            if (message.isNullOrBlank()) remove(CACHE_LAST_ERROR)
            else putString(CACHE_LAST_ERROR, message.take(180))
        }
    }

    fun getLastError(context: Context): String? = prefs(context).getString(CACHE_LAST_ERROR, null)

    private fun readCachedMarketData(
        context: Context,
        symbol: String,
        interval: String,
        range: String,
    ): CachedMarketData? {
        val raw = prefs(context).getString(cacheKey(symbol, interval, range), null) ?: return null
        return runCatching { gson.fromJson(raw, CachedMarketData::class.java) }.getOrNull()
    }

    private fun pruneCachedMarketData(context: Context, now: Long) {
        val keysToRemove = prefs(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith(CACHE_PREFIX) || value !is String) return@mapNotNull null
            val cached = runCatching { gson.fromJson(value, CachedMarketData::class.java) }.getOrNull()
            val invalid = cached == null ||
                cached.data.safeTimestamps().isEmpty() ||
                cached.data.safeHistory().isEmpty() ||
                now - cached.savedAtMs > CACHE_STALE_MS
            key.takeIf { invalid }
        }
        if (keysToRemove.isNotEmpty()) {
            prefs(context).edit { keysToRemove.forEach { key -> remove(key) } }
            Log.d("API_CACHE", "Pruned ${keysToRemove.size} stale Yahoo cache entries")
        }
    }

    private fun writeCachedMarketData(
        context: Context,
        symbol: String,
        interval: String,
        range: String,
        data: MarketData,
        now: Long,
    ) {
        prefs(context).edit {
            putString(cacheKey(symbol, interval, range), gson.toJson(CachedMarketData(now, data)))
        }
    }

    private fun symbolLock(symbol: String, interval: String, range: String): Mutex =
        symbolLocks.getOrPut("${symbol.uppercase()}|$interval|$range") { Mutex() }

    suspend fun fetchPrices(
        context: Context,
        symbol: String,
        range: String = "2y",
        interval: String = "1d",
    ): List<Double> = fetchMarketData(context, symbol, range, interval)?.history ?: emptyList()

    /**
     * Fetches Yahoo chart data.
     *
     * Legacy AGTQ/Snow callers use the historical 2y/1d default. Once the shared
     * SQLite history has been bootstrapped by the market sync worker, those calls
     * are served locally and no longer trigger duplicate 2y Yahoo downloads.
     * Explicit ranges such as 1mo/max still go through Yahoo and feed the shared
     * durable store via the 17d market sync path.
     */
    suspend fun fetchMarketData(
        context: Context,
        symbol: String,
        range: String = "2y",
        interval: String = "1d",
    ): MarketData? = symbolLock(symbol, interval, range).withLock {
        val now = System.currentTimeMillis()
        pruneCachedMarketData(context, now)

        val shouldUseSharedDurable =
            interval.equals("1d", ignoreCase = true) &&
                range.equals("2y", ignoreCase = true) &&
                SharedMarketDataStore.isSharedSymbol(symbol)
        if (shouldUseSharedDurable) {
            SharedMarketDataStore.read(context, symbol)?.let { durable ->
                setLastError(context, null)
                Log.d(
                    "API_CACHE",
                    "Shared SQLite hit for $symbol/$interval/$range (${durable.history.size} bars)",
                )
                return@withLock durable
            }
        }

        val cached = readCachedMarketData(context, symbol, interval, range)
        val cachedUsable = cached?.data?.let { data ->
            data.safeTimestamps().isNotEmpty() && data.safeHistory().isNotEmpty()
        } == true
        val cacheTtlMs = if (range.equals("max", ignoreCase = true)) LONG_HISTORY_CACHE_TTL_MS else CACHE_TTL_MS
        if (cached != null && cachedUsable && now - cached.savedAtMs <= cacheTtlMs) {
            setLastError(context, null)
            Log.d("API_CACHE", "Cache hit for $symbol/$interval/$range")
            return@withLock cached.data
        }

        try {
            val response = service.getHistory(symbol, interval, range)
            val result = response.chart.result?.firstOrNull()
            if (result == null) {
                setLastError(context, "Yahoo $symbol fetch failed: empty response")
                return@withLock null
            }
            val closes = result.safeCloses()
            val timestamps = result.safeTimestamps()
            val pairedHistory = timestamps.zip(closes).mapNotNull { (timestamp, close) ->
                close?.takeIf { it.isFinite() && it > 0.0 }?.let { (timestamp * 1000L) to it }
            }
            if (pairedHistory.isEmpty()) {
                setLastError(context, "Yahoo $symbol fetch failed: no valid daily bars")
                return@withLock null
            }
            val data = MarketData(
                currentPrice = result.meta.regularMarketPrice,
                prevClose = result.meta.previousClose,
                history = pairedHistory.map { it.second },
                timestamps = pairedHistory.map { it.first },
            )
            writeCachedMarketData(context, symbol, interval, range, data, now)
            setLastError(context, null)
            Log.d("API_CACHE", "Fetched $symbol/$interval/$range (${data.history.size} bars)")
            data
        } catch (e: CancellationException) {
            Log.d("API_CACHE", "Cancelled Yahoo $symbol/$interval/$range fetch")
            throw e
        } catch (e: Exception) {
            Log.e("API_ERROR", "Yahoo $symbol/$interval/$range: ${e.message}", e)
            if (cached != null && cachedUsable && now - cached.savedAtMs <= CACHE_STALE_MS) {
                setLastError(context, null)
                Log.d("API_CACHE", "Using stale cache for $symbol/$interval/$range")
                cached.data
            } else {
                removeCachedMarketData(context, symbol, interval, range)
                setLastError(context, "Yahoo $symbol fetch failed: ${e.message ?: "unknown error"}")
                null
            }
        }
    }
}
