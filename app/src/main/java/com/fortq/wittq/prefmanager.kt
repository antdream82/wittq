package com.fortq.wittq

import android.content.Context
import android.content.SharedPreferences

class TqqqPreferenceManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("TqqqAlgoPrefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_LAST_ENTRY_PRICE = "last_entry_price"
        const val KEY_HAD_FORCE_EXIT = "had_force_exit"
        const val KEY_USER_POSITION = "user_position"
    }

    // 1. 조건 진입(100%) 시점의 가격 저장
    var lastEntryPrice: Double
        get() = prefs.getFloat(KEY_LAST_ENTRY_PRICE, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LAST_ENTRY_PRICE, value.toFloat()).apply()

    // 2. 강제 탈출 발생 여부 저장 (RSI 쿨타임용)
    var hadForceExit: Boolean
        get() = prefs.getBoolean(KEY_HAD_FORCE_EXIT, false)
        set(value) = prefs.edit().putBoolean(KEY_HAD_FORCE_EXIT, value).apply()

    // 3. 현재 포지션 상태 저장
    var userPosition: String
        get() = prefs.getString(KEY_USER_POSITION, "CASH") ?: "CASH"
        set(value) = prefs.edit().putString(KEY_USER_POSITION, value).apply()

    // 모든 데이터 초기화 (필요 시)
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}