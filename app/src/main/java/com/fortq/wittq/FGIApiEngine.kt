package com.fortq.wittq

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// FGApiEngine.kt - 새로 생성
object FGApiEngine {
    private const val FGI_URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata"
    private const val FGI_FALLBACK_URL = "https://feargreedchart.com/api/?action=all"

    suspend fun fetchAll(): FullData? {
        return withContext(Dispatchers.IO) {
            fetchFromCnn() ?: fetchFromFallback()
        }
    }

    private fun fetchFromCnn(): FullData? {
        return try {
            val jsonObj = JSONObject(requestJson(FGI_URL, "https://www.cnn.com/markets/fear-and-greed"))

            val fgObj = jsonObj.getJSONObject("fear_and_greed")
            val fgData = FearGreedData(
                score = fgObj.getDouble("score"),
                rating = fgObj.getString("rating")
            )

            val pcRatio = try {
                val pcDataArray = jsonObj.getJSONObject("put_call_options").getJSONArray("data")
                if (pcDataArray.length() > 0) {
                    pcDataArray.getJSONObject(pcDataArray.length() - 1).getDouble("y")
                } else {
                    0.85
                }
            } catch (e: Exception) {
                Log.e("WITTQ_FGI_DEBUG", "PC Ratio parsing failed, using fallback: ${e.message}")
                0.85
            }

            val fgdataArray = jsonObj.getJSONObject("fear_and_greed_historical").getJSONArray("data")
            val historyList = mutableListOf<Double>()
            val startIdx = (fgdataArray.length() - 90).coerceAtLeast(0)

            for (i in startIdx until fgdataArray.length()) {
                historyList.add(fgdataArray.getJSONObject(i).optDouble("y"))
            }

            Log.d("WITTQ_FGI_DEBUG", "CNN fetch success: FG=${fgData.score}, PC=$pcRatio, History=${historyList.size}")
            FullData(fgData, historyList, pcRatio)
        } catch (e: Exception) {
            Log.e("WITTQ_FGI_DEBUG", "CNN FGI fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchFromFallback(): FullData? {
        return try {
            val jsonObj = JSONObject(requestJson(FGI_FALLBACK_URL, null))
            val score = jsonObj.getJSONObject("score").getDouble("score")
            val historyArray = jsonObj.getJSONArray("recent")
            val historyList = mutableListOf<Double>()
            val startIdx = (historyArray.length() - 90).coerceAtLeast(0)

            for (i in startIdx until historyArray.length()) {
                historyList.add(historyArray.getJSONObject(i).getDouble("score"))
            }

            val pcRatio = parseFallbackPcRatio(jsonObj) ?: 0.85
            val fgData = FearGreedData(score = score, rating = ratingFromScore(score))

            Log.d("WITTQ_FGI_DEBUG", "Fallback fetch success: FG=${fgData.score}, PC=$pcRatio, History=${historyList.size}")
            FullData(fgData, historyList, pcRatio)
        } catch (e: Exception) {
            Log.e("WITTQ_FGI_DEBUG", "Fallback FGI fetch failed: ${e.message}")
            null
        }
    }

    private fun requestJson(urlString: String, referer: String?): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            doInput = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9,ko;q=0.8")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            referer?.let { setRequestProperty("Referer", it) }
        }

        val responseCode = connection.responseCode
        Log.d("WITTQ_FGI_DEBUG", "$urlString response code: $responseCode")

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("HTTP $responseCode")
        }

        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parseFallbackPcRatio(jsonObj: JSONObject): Double? {
        val components = jsonObj.getJSONObject("score").optJSONArray("components") ?: return null
        for (i in 0 until components.length()) {
            val component = components.getJSONObject(i)
            if (component.optString("name").equals("PUT/CALL", ignoreCase = true)) {
                val match = Regex("""[-+]?\d*\.?\d+""").find(component.optString("raw"))
                return match?.value?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun ratingFromScore(score: Double): String = when {
        score < 25 -> "extreme fear"
        score < 45 -> "fear"
        score < 55 -> "neutral"
        score < 75 -> "greed"
        else -> "extreme greed"
    }
}
data class FearGreedData(val score: Double, val rating: String)

data class FullData(
    val fgData: FearGreedData,
    val fgHistory: List<Double>,
    val pcRatio: Double
)
