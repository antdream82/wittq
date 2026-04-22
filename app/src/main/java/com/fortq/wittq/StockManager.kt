package com.fortq.wittq

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

data class YahooResponse(val chart: YahooChart)
data class YahooChart(val result: List<YahooResultData>?)
data class YahooResultData(
    val meta: YahooMeta,
    val indicators: YahooIndicators,
    val timestamp: List<Long> = emptyList()
)
data class YahooIndicators(val quote: List<YahooQuote>)
data class YahooQuote(val close: List<Double?>)

data class YahooMeta(
    val regularMarketPrice: Double, // 현재가
    val previousClose: Double
)

data class MarketData(
    val currentPrice: Double,
    val prevClose: Double,
    val history: List<Double>,
    val timestamps: List<Long> = emptyList()
)

data class CachedMarketData(
    val savedAtMs: Long,
    val data: MarketData
)

interface YahooApiService {
    @GET("v8/finance/chart/{symbol}")
    suspend fun getHistory(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1d",
        @Query("range") range: String = "2y"
    ): YahooResponse
}

object StockApiEngine {
    private const val CACHE_PREFS = "YahooCache"
    private const val CACHE_PREFIX = "market_data_"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_STALE_MS = 24 * 60 * 60 * 1000L

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://query1.finance.yahoo.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: YahooApiService = retrofit.create(YahooApiService::class.java)
    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    private fun cacheKey(symbol: String) = "market_data_${symbol.uppercase()}"

    private fun removeCachedMarketData(context: Context, symbol: String) {
        prefs(context).edit {
            remove(cacheKey(symbol))
        }
    }

    private fun readCachedMarketData(context: Context, symbol: String): CachedMarketData? {
        val raw = prefs(context).getString(cacheKey(symbol), null) ?: return null
        return runCatching { gson.fromJson(raw, CachedMarketData::class.java) }.getOrNull()
    }

    private fun pruneCachedMarketData(context: Context, now: Long) {
        prefs(context).all.forEach { (key, value) ->
            if (!key.startsWith(CACHE_PREFIX) || value !is String) return@forEach
            val cached = runCatching { gson.fromJson(value, CachedMarketData::class.java) }.getOrNull()
            val invalid = cached == null ||
                cached.data.timestamps.isEmpty() ||
                now - cached.savedAtMs > CACHE_STALE_MS
            if (invalid) {
                prefs(context).edit {
                    remove(key)
                }
            }
        }
    }

    private fun writeCachedMarketData(context: Context, symbol: String, data: MarketData, now: Long) {
        prefs(context).edit {
            putString(cacheKey(symbol), gson.toJson(CachedMarketData(now, data)))
        }
    }

    suspend fun fetchPrices(context: Context, symbol: String): List<Double> {
        return fetchMarketData(context, symbol)?.history ?: emptyList()
    }

    suspend fun fetchMarketData(context: Context, symbol: String): MarketData? {
        val now = System.currentTimeMillis()
        pruneCachedMarketData(context, now)
        val cached = readCachedMarketData(context, symbol)
        val cachedHasTimestamps = cached?.data?.timestamps?.isNotEmpty() == true
        if (cached != null && cachedHasTimestamps && now - cached.savedAtMs <= CACHE_TTL_MS) {
            return cached.data
        }

        return try {
            val response = service.getHistory(symbol)
            val result = response.chart.result?.firstOrNull() ?: return null
            val closes = result.indicators.quote.firstOrNull()?.close.orEmpty()
            val pairedHistory = result.timestamp.zip(closes).mapNotNull { (ts, close) ->
                close?.let { (ts * 1000L) to it }
            }
            val data = MarketData(
                currentPrice = result.meta.regularMarketPrice,
                prevClose = result.meta.previousClose,
                history = pairedHistory.map { it.second },
                timestamps = pairedHistory.map { it.first }
            )

            writeCachedMarketData(context, symbol, data, now)
            data
        } catch (e: Exception) {
            Log.e("API_ERROR", e.message.toString())
            if (cached != null && cachedHasTimestamps && now - cached.savedAtMs <= CACHE_STALE_MS) {
                cached.data
            } else {
                removeCachedMarketData(context, symbol)
                null
            }
        }
    }
}
