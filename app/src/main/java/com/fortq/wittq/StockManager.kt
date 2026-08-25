package com.fortq.wittq

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
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

    @GET("v8/finance/chart/{symbol}")
    suspend fun getHistoryPeriod(
        @Path("symbol") symbol: String,
        @Query("period1") period1: Long,
        @Query("period2") period2: Long,
        @Query("interval") interval: String = "1d",
        @Query("includePrePost") includePrePost: Boolean = false,
        @Query("events") events: String = "div,splits",
    ): YahooResponse
}

object StockApiEngine {
    private const val CACHE_PREFS = "YahooCache"
    private const val CACHE_PREFIX = "market_data_"
    private const val CACHE_LAST_ERROR = "last_yahoo_error"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val LONG_HISTORY_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val CACHE_STALE_MS = 24 * 60 * 60 * 1000L
    private const val MIN_MAX_HISTORY_ROWS = 290
    private val symbolLocks = ConcurrentHashMap<String, Mutex>()

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
                )
                .build()
            chain.proceed(request)
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://query1.finance.yahoo.com/")
        .client(httpClient)
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

    private fun historyIsUsable(data: MarketData, range: String): Boolean {
        val history = data.safeHistory()
        val timestamps = data.safeTimestamps()
        if (history.isEmpty() || timestamps.isEmpty()) return false
        if (range.equals("max", ignoreCase = true) && history.size < MIN_MAX_HISTORY_ROWS) return false
        return true
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

    private fun parseMarketData(response: YahooResponse): MarketData? {
        val result = response.chart.result?.firstOrNull() ?: return null
        val closes = result.safeCloses()
        val timestamps = result.safeTimestamps()
        val pairedHistory = timestamps.zip(closes).mapNotNull { (timestamp, close) ->
            close?.takeIf { it.isFinite() && it > 0.0 }?.let { (timestamp * 1000L) to it }
        }
        if (pairedHistory.isEmpty()) return null
        return MarketData(
            currentPrice = result.meta.regularMarketPrice,
            prevClose = result.meta.previousClose,
            history = pairedHistory.map { it.second },
            timestamps = pairedHistory.map { it.first },
        )
    }

    suspend fun fetchPrices(
        context: Context,
        symbol: String,
        range: String = "2y",
        interval: String = "1d",
    ): List<Double> = fetchMarketData(context, symbol, range, interval)?.history ?: emptyList()

    /**
     * Explicit period based daily history. This is used for the one-time strategy
     * bootstrap because Yahoo may coarsen very long `range=max` requests even when
     * interval=1d is supplied. Period bounds keep daily granularity deterministic.
     * The resulting history is persisted by the shared SQLite store, so this call
     * is not part of normal recurring refreshes.
     */
    suspend fun fetchMarketDataPeriod(
        context: Context,
        symbol: String,
        period1EpochSec: Long,
        period2EpochSec: Long,
        interval: String = "1d",
    ): MarketData? = symbolLock(
        symbol,
        interval,
        "period:$period1EpochSec:$period2EpochSec",
    ).withLock {
        try {
            val response = service.getHistoryPeriod(
                symbol = symbol,
                period1 = period1EpochSec,
                period2 = period2EpochSec,
                interval = interval,
            )
            val data = parseMarketData(response)
            if (data == null) {
                setLastError(context, "Yahoo $symbol period bootstrap failed: no valid daily bars")
                return@withLock null
            }
            setLastError(context, null)
            Log.d(
                "API_CACHE",
                "Fetched $symbol/$interval explicit-period (${data.history.size} daily bars)",
            )
            data
        } catch (e: CancellationException) {
            Log.d("API_CACHE", "Cancelled Yahoo $symbol/$interval explicit-period fetch")
            throw e
        } catch (e: Exception) {
            Log.e("API_ERROR", "Yahoo $symbol/$interval explicit-period: ${e.message}", e)
            setLastError(
                context,
                "Yahoo $symbol period bootstrap failed: ${e.message ?: "unknown error"}",
            )
            null
        }
    }

    /**
     * Fetches Yahoo chart data.
     *
     * Legacy AGTQ/Snow callers use the historical 2y/1d default. Once the shared
     * SQLite history has been bootstrapped by the market sync worker, those calls
     * are served locally and no longer trigger duplicate 2y Yahoo downloads.
     * Recurring 17d refreshes request short overlapping ranges and feed the shared
     * durable store.
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

        var cached = readCachedMarketData(context, symbol, interval, range)
        var cachedUsable = cached?.data?.let { historyIsUsable(it, range) } == true
        if (cached != null && !cachedUsable && range.equals("max", ignoreCase = true)) {
            Log.w(
                "API_CACHE",
                "Discarding truncated max cache for $symbol (${cached.data.safeHistory().size} bars)",
            )
            removeCachedMarketData(context, symbol, interval, range)
            cached = null
            cachedUsable = false
        }

        val cacheTtlMs = if (range.equals("max", ignoreCase = true)) LONG_HISTORY_CACHE_TTL_MS else CACHE_TTL_MS
        if (cached != null && cachedUsable && now - cached.savedAtMs <= cacheTtlMs) {
            setLastError(context, null)
            Log.d("API_CACHE", "Cache hit for $symbol/$interval/$range")
            return@withLock cached.data
        }

        try {
            val response = service.getHistory(symbol, interval, range)
            val data = parseMarketData(response)
            if (data == null) {
                setLastError(context, "Yahoo $symbol fetch failed: no valid daily bars")
                return@withLock null
            }

            if (range.equals("max", ignoreCase = true) && data.history.size < MIN_MAX_HISTORY_ROWS) {
                removeCachedMarketData(context, symbol, interval, range)
                setLastError(
                    context,
                    "Yahoo $symbol max returned only ${data.history.size} rows; use explicit-period bootstrap",
                )
                Log.w("API_CACHE", "Rejected truncated max response for $symbol (${data.history.size} bars)")
                return@withLock data
            }

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
