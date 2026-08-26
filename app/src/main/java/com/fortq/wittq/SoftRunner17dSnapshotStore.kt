package com.fortq.wittq

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SoftRunner17dWidgetSnapshot(
    val officialTarget: Double,
    val previewTarget: Double,
    val reason: String,
    val runnerStatus: String,
    val releaseStatus: String,
    val contrarianActive: Boolean,
    val hardRisk: Boolean,
    val contrarianCheap: Boolean,
    val contrarianReclaim: Boolean,
    val tqqqClose: Double,
    val tqqqSma290: Double?,
    val tqqqSma290Ratio: Double?,
    val priceHistory: List<Double>,
    val sma290History: List<Double?>,
    val trailingReturn1y: Double?,
    val trailingReturn6m: Double?,
    val trailingReturn3m: Double?,
    val statusMessage: String,
    val stale: Boolean,
    val updatedAtMillis: Long,
)

object SoftRunner17dSnapshotStore {
    private const val PREFS = "SoftRunner17dSnapshot"
    private const val KEY_SNAPSHOT = "snapshot_v1"
    private const val KEY_ERROR = "last_refresh_error"
    private const val KEY_ERROR_AT = "last_refresh_error_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, snapshot: SoftRunner17dAppSnapshot) {
        val preview = snapshot.preview
        val json = JSONObject().apply {
            put("officialTarget", snapshot.official.finalTarget)
            put("previewTarget", preview.finalTarget)
            put("reason", preview.reason.name)
            put("runnerStatus", preview.runnerStatus.name)
            put("releaseStatus", preview.releaseStatus.name)
            put("contrarianActive", preview.contrarianActive)
            put("hardRisk", preview.hardRisk)
            put("contrarianCheap", preview.contrarianCheap)
            put("contrarianReclaim", preview.contrarianReclaim)
            put("tqqqClose", preview.tqqqClose)
            putNullableDouble("tqqqSma290", preview.tqqqSma290)
            putNullableDouble("tqqqSma290Ratio", preview.tqqqSma290Ratio)
            put("priceHistory", JSONArray().apply {
                snapshot.priceHistory.forEach { put(it) }
            })
            put("sma290History", JSONArray().apply {
                snapshot.sma290History.forEach { value ->
                    if (value == null) put(JSONObject.NULL) else put(value)
                }
            })
            putNullableDouble("trailingReturn1y", snapshot.trailingReturn1y)
            putNullableDouble("trailingReturn6m", snapshot.trailingReturn6m)
            putNullableDouble("trailingReturn3m", snapshot.trailingReturn3m)
            put("statusMessage", snapshot.statusMessage)
            put("stale", snapshot.stale)
            put("updatedAtMillis", snapshot.updatedAtMillis)
        }

        prefs(context).edit()
            .putString(KEY_SNAPSHOT, json.toString())
            .remove(KEY_ERROR)
            .remove(KEY_ERROR_AT)
            .commit()
    }

    fun read(context: Context): SoftRunner17dWidgetSnapshot? {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return null
        val parsed = runCatching {
            val json = JSONObject(raw)
            SoftRunner17dWidgetSnapshot(
                officialTarget = json.getDouble("officialTarget"),
                previewTarget = json.getDouble("previewTarget"),
                reason = json.getString("reason"),
                runnerStatus = json.getString("runnerStatus"),
                releaseStatus = json.getString("releaseStatus"),
                contrarianActive = json.getBoolean("contrarianActive"),
                hardRisk = json.getBoolean("hardRisk"),
                contrarianCheap = json.getBoolean("contrarianCheap"),
                contrarianReclaim = json.getBoolean("contrarianReclaim"),
                tqqqClose = json.getDouble("tqqqClose"),
                tqqqSma290 = json.nullableDouble("tqqqSma290"),
                tqqqSma290Ratio = json.nullableDouble("tqqqSma290Ratio"),
                priceHistory = json.getJSONArray("priceHistory").toDoubleList(),
                sma290History = json.getJSONArray("sma290History").toNullableDoubleList(),
                trailingReturn1y = json.nullableDouble("trailingReturn1y"),
                trailingReturn6m = json.nullableDouble("trailingReturn6m"),
                trailingReturn3m = json.nullableDouble("trailingReturn3m"),
                statusMessage = json.getString("statusMessage"),
                stale = json.getBoolean("stale"),
                updatedAtMillis = json.getLong("updatedAtMillis"),
            )
        }.getOrNull() ?: return null

        // A failed canonical repair used to be invisible whenever an older valid
        // snapshot existed. Preserve its values, but visibly mark them stale and
        // surface the actual worker failure until a successful save clears it.
        val error = getError(context)
        if (error.isNullOrBlank()) return parsed

        val errorAt = getErrorAt(context)
        return parsed.copy(
            statusMessage = "REPAIR ${error.take(120)}",
            stale = true,
            updatedAtMillis = maxOf(parsed.updatedAtMillis, errorAt),
        )
    }

    fun setError(
        context: Context,
        message: String,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit()
            .putString(KEY_ERROR, message.take(180))
            .putLong(KEY_ERROR_AT, atMillis)
            .apply()
    }

    fun getError(context: Context): String? =
        prefs(context).getString(KEY_ERROR, null)

    fun getErrorAt(context: Context): Long =
        prefs(context).getLong(KEY_ERROR_AT, 0L)

    private fun JSONObject.putNullableDouble(key: String, value: Double?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun JSONArray.toDoubleList(): List<Double> =
        List(length()) { index -> getDouble(index) }

    private fun JSONArray.toNullableDoubleList(): List<Double?> =
        List(length()) { index -> if (isNull(index)) null else getDouble(index) }
}
