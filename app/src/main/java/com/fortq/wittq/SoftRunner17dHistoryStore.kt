package com.fortq.wittq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Durable on-device daily-bar store shared by 17d, AGTQ and Snow.
 *
 * The database survives normal APK updates. Bootstrap completeness is versioned
 * and also requires recent daily-bar density so a Yahoo response that was
 * silently downsampled to weekly/monthly bars can never be treated as a valid
 * daily history merely because it contains many old rows.
 */
class SoftRunner17dHistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class SymbolStats(
        val rowCount: Int,
        val oldestDate: LocalDate?,
        val latestDate: LocalDate?,
    )

    private val newYorkZone: ZoneId = ZoneId.of("America/New_York")

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_BARS (
                symbol TEXT NOT NULL,
                trading_date TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                close REAL NOT NULL,
                fetched_at_ms INTEGER NOT NULL,
                source_range TEXT NOT NULL,
                PRIMARY KEY(symbol, trading_date)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE_BARS}_symbol_date ON $TABLE_BARS(symbol, trading_date)",
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_META (
                meta_key TEXT PRIMARY KEY,
                meta_value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 is append-only. Future migrations should preserve bars.
        if (oldVersion < 1) onCreate(db)
    }

    @Synchronized
    fun upsert(
        symbol: String,
        data: MarketData,
        sourceRange: String,
        fetchedAtMillis: Long = System.currentTimeMillis(),
    ): Int {
        val canonicalSymbol = canonicalSymbol(symbol)
        val pairs = data.safeTimestamps().zip(data.safeHistory()).mapNotNull { (timestamp, close) ->
            if (!close.isFinite() || close <= 0.0) return@mapNotNull null
            val date = Instant.ofEpochMilli(timestamp).atZone(newYorkZone).toLocalDate()
            Triple(date, timestamp, close)
        }
        if (pairs.isEmpty()) return 0

        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            for ((date, timestamp, close) in pairs) {
                val values = ContentValues().apply {
                    put("symbol", canonicalSymbol)
                    put("trading_date", date.toString())
                    put("timestamp_ms", timestamp)
                    put("close", close)
                    put("fetched_at_ms", fetchedAtMillis)
                    put("source_range", sourceRange)
                }
                val rowId = db.insertWithOnConflict(
                    TABLE_BARS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                if (rowId != -1L) changed += 1
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    fun read(symbol: String): MarketData? {
        val canonicalSymbol = canonicalSymbol(symbol)
        val closes = ArrayList<Double>()
        val timestamps = ArrayList<Long>()
        readableDatabase.query(
            TABLE_BARS,
            arrayOf("timestamp_ms", "close"),
            "symbol = ?",
            arrayOf(canonicalSymbol),
            null,
            null,
            "trading_date ASC",
        ).use { cursor ->
            val timestampIndex = cursor.getColumnIndexOrThrow("timestamp_ms")
            val closeIndex = cursor.getColumnIndexOrThrow("close")
            while (cursor.moveToNext()) {
                val close = cursor.getDouble(closeIndex)
                if (close.isFinite() && close > 0.0) {
                    timestamps += cursor.getLong(timestampIndex)
                    closes += close
                }
            }
        }
        if (closes.isEmpty()) return null
        return MarketData(
            currentPrice = Double.NaN,
            prevClose = closes.getOrElse(closes.lastIndex - 1) { closes.last() },
            history = closes,
            timestamps = timestamps,
        )
    }

    fun stats(symbol: String): SymbolStats {
        val canonicalSymbol = canonicalSymbol(symbol)
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(trading_date), MAX(trading_date) FROM $TABLE_BARS WHERE symbol = ?",
            arrayOf(canonicalSymbol),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return SymbolStats(0, null, null)
            val count = cursor.getInt(0)
            val oldest = cursor.getString(1)?.let(LocalDate::parse)
            val latest = cursor.getString(2)?.let(LocalDate::parse)
            return SymbolStats(count, oldest, latest)
        }
    }

    /**
     * Valid daily data has roughly 250 US trading sessions per year. Requiring
     * 180 rows in the 370 calendar days ending at the symbol's own latest row
     * leaves ample holiday/data-gap tolerance while decisively rejecting
     * weekly/monthly histories (about 52/12 rows per year).
     */
    fun hasDailyCadence(symbol: String): Boolean {
        val canonicalSymbol = canonicalSymbol(symbol)
        val latest = stats(symbol).latestDate ?: return false
        val cutoff = latest.minusDays(370).toString()
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_BARS WHERE symbol = ? AND trading_date >= ? AND trading_date <= ?",
            arrayOf(canonicalSymbol, cutoff, latest.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            return cursor.getInt(0) >= MIN_RECENT_DAILY_ROWS
        }
    }

    fun isBootstrapComplete(symbol: String): Boolean =
        getMeta(bootstrapKey(symbol)) == BOOTSTRAP_VERSION && hasDailyCadence(symbol)

    fun markBootstrapComplete(symbol: String) {
        if (hasDailyCadence(symbol)) {
            putMeta(bootstrapKey(symbol), BOOTSTRAP_VERSION)
        }
    }

    fun clearSymbol(symbol: String) {
        val canonicalSymbol = canonicalSymbol(symbol)
        writableDatabase.delete(TABLE_BARS, "symbol = ?", arrayOf(canonicalSymbol))
        writableDatabase.delete(TABLE_META, "meta_key = ?", arrayOf(bootstrapKey(symbol)))
    }

    private fun putMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("meta_key", key)
            put("meta_value", value)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_META,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun getMeta(key: String): String? = readableDatabase.query(
        TABLE_META,
        arrayOf("meta_value"),
        "meta_key = ?",
        arrayOf(key),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun bootstrapKey(symbol: String): String =
        "bootstrap_${canonicalSymbol(symbol)}"

    private fun canonicalSymbol(symbol: String): String = symbol.trim().uppercase()

    companion object {
        private const val DB_NAME = "soft_runner_17d_history.db"
        private const val DB_VERSION = 1
        private const val TABLE_BARS = "daily_bars"
        private const val TABLE_META = "metadata"

        // v1 accepted row-count-only histories, allowing old QQQ/SPY/VIX monthly
        // data to remain marked complete. v2 intentionally forces one daily-period
        // rebootstrap for every shared symbol after upgrade.
        private const val BOOTSTRAP_VERSION = "v2-daily-period"
        private const val MIN_RECENT_DAILY_ROWS = 180

        @Volatile
        private var instance: SoftRunner17dHistoryStore? = null

        fun get(context: Context): SoftRunner17dHistoryStore =
            instance ?: synchronized(this) {
                instance ?: SoftRunner17dHistoryStore(context).also { instance = it }
            }

        /** Used by tests/tools that need canonical 09:30 ET timestamps. */
        internal fun timestampForDate(date: LocalDate): Long =
            ZonedDateTime.of(date, LocalTime.of(9, 30), ZoneId.of("America/New_York"))
                .toInstant()
                .toEpochMilli()
    }
}
