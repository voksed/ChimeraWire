package com.carnelia.vpn.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.carnelia.vpn.utils.AppLogger

enum class DisguiseOption(val alias: String, val label: String) {
    REAL(".LauncherReal", "Carnelia VPN"),
    CALCULATOR(".LauncherCalculator", "Калькулятор"),
    NOTES(".LauncherNotes", "Заметки");
}

/**
 * Переключает, под каким именем/иконкой приложение видно на рабочем столе — через
 * activity-alias'ы в манифесте (каждый указывает на MainActivity, различаются только
 * android:icon/android:label). Ровно один alias enabled в любой момент, остальные disabled —
 * так на лаунчере виден только один ярлык. Сама MainActivity больше не имеет собственного
 * MAIN/LAUNCHER intent-filter, только алиасы.
 */
object IconDisguiseManager {
    private const val PREFS = "disguise_prefs"
    private const val KEY_CURRENT = "current_disguise"

    fun current(context: Context): DisguiseOption {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT, null)
        return DisguiseOption.entries.find { it.name == name } ?: DisguiseOption.REAL
    }

    fun apply(context: Context, option: DisguiseOption) {
        val pm = context.packageManager
        DisguiseOption.entries.forEach { opt ->
            val state = if (opt == option) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(context.packageName, "${context.packageName}${opt.alias}"),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                AppLogger.error("IconDisguiseManager: не удалось переключить ${opt.alias}", e)
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT, option.name).apply()
        AppLogger.log("IconDisguiseManager: переключено на ${option.label}")
    }
}
