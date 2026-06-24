package com.carnelia.vpn.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CalibrationHistoryEntry(
    val timestamp: Long,
    val networkKey: String,
    val success: Boolean,
    val serverName: String?,
    val profileLabel: String?,
    val tcpPingMs: Long?,
    val latencyMs: Long?,
    val mbps: Double?
)

/** Локальная история калибровок — видно, когда и что сработало, как меняется качество сети со временем. */
object CalibrationHistoryStore {
    private const val PREFS = "calibration_history"
    private const val KEY = "entries_v1"
    private const val MAX_ENTRIES = 50
    private val gson = Gson()

    fun add(context: Context, entry: CalibrationHistoryEntry) {
        val list = getAll(context).toMutableList()
        list.add(0, entry)
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(list))
            .apply()
    }

    fun getAll(context: Context): List<CalibrationHistoryEntry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CalibrationHistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
