package com.carnelia.vpn.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

data class AppRisk(
    val packageName: String,
    val appName: String,
    val score: Int,
    val reasons: List<String>,
    val isSynthetic: Boolean = false
)

/**
 * Локальная (без сети, без root) эвристика для обнаружения шпионского/сталкерского ПО:
 * подозрительные комбинации прав, accessibility-абьюз, device admin, скрытая иконка,
 * установка не из магазина. Это не антивирус — Android не даёт читать файлы других
 * приложений без root, поэтому ловим только то, что видно через системные API.
 */
object SecurityScanner {

    private const val HIGH_RISK = 60
    private const val MEDIUM_RISK = 30

    fun scanAll(context: Context): List<AppRisk> {
        val pm = context.packageManager
        val accessibilityPkgs = enabledAccessibilityPackages(context)
        val adminPkgs = activeDeviceAdminPackages(context)

        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        val result = apps.mapNotNull { app -> scoreApp(context, app, accessibilityPkgs, adminPkgs) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
        com.carnelia.vpn.utils.AppLogger.log("SecurityScanner: ${apps.size} приложений проверено, ${result.size} флагов")
        result.forEach {
            com.carnelia.vpn.utils.AppLogger.log("SecurityScanner: ${it.packageName} score=${it.score} reasons=${it.reasons.joinToString(" | ")}")
        }
        return result
    }

    fun scanOne(context: Context, packageName: String): AppRisk? {
        val pm = context.packageManager
        val app = try { pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA) } catch (_: Exception) { return null }
        return scoreApp(context, app, enabledAccessibilityPackages(context), activeDeviceAdminPackages(context))
    }

    fun severityLabel(score: Int): String = when {
        score >= HIGH_RISK -> "ВЫСОКИЙ РИСК"
        score >= MEDIUM_RISK -> "ПОДОЗРИТЕЛЬНО"
        else -> "НИЗКИЙ"
    }

    private fun scoreApp(
        context: Context,
        app: ApplicationInfo,
        accessibilityPkgs: Set<String>,
        adminPkgs: Set<String>
    ): AppRisk? {
        val pkg = app.packageName
        if (pkg == context.packageName) return null // не пугаем пользователя самой Carnelia

        val pm = context.packageManager
        val isSystemApp = (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        if (isSystemApp || isTrustedVendor(pkg)) return null // системные/предустановленные/OEM-компоненты — слишком много шума, не настоящий сигнал

        // Сильные признаки — каждый сам по себе достаточен, чтобы показать приложение.
        var strongScore = 0
        val strongReasons = mutableListOf<String>()
        if (pkg in accessibilityPkgs) {
            strongScore += 40
            strongReasons.add("Использует Accessibility — может читать экран и эмулировать нажатия")
        }
        if (pkg in adminPkgs) {
            strongScore += 40
            strongReasons.add("Права администратора устройства — может заблокировать/сбросить телефон")
        }

        // Камера+мик+геолокация+СМС или оверлей поверх экрана — это же обычный профиль прав
        // ЛЮБОГО мессенджера (видеозвонки, шаринг локации, SMS-верификация, floating-чаты).
        // Реальный отличительный признак стейкерsoftware — то, что оно СКРЫТО от пользователя:
        // нет иконки в меню. Поэтому остальные слабые признаки считаем ТОЛЬКО для скрытых
        // приложений — у видимого Telegram/Facebook/VK эти права совершенно нормальны.
        val hasLauncherIcon = pm.getLaunchIntentForPackage(pkg) != null
        val reasons = strongReasons.toMutableList()
        var score = strongScore

        if (!hasLauncherIcon) {
            val weak = mutableListOf<Pair<Int, String>>()
            weak.add(25 to "Скрыто из списка приложений — нет значка в меню")

            val perms = try {
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet()
            } catch (_: Exception) { null } ?: emptySet()

            val hasCamMic = "android.permission.CAMERA" in perms || "android.permission.RECORD_AUDIO" in perms
            val hasLocation = "android.permission.ACCESS_FINE_LOCATION" in perms
            val hasSmsCall = "android.permission.READ_SMS" in perms || "android.permission.READ_CALL_LOG" in perms
            if (hasCamMic && hasLocation && hasSmsCall) {
                weak.add(20 to "Камера/микрофон + геолокация + СМС/звонки одновременно")
            }

            if ("android.permission.SYSTEM_ALERT_WINDOW" in perms) {
                weak.add(15 to "Может рисовать поверх других приложений")
            }

            val installer = installerOf(context, pkg)
            val sideloaded = installer == null
            if (sideloaded && "android.permission.REQUEST_INSTALL_PACKAGES" in perms) {
                weak.add(15 to "Установлено не из магазина и может устанавливать другие приложения")
            }

            score += weak.sumOf { it.first }
            reasons.addAll(weak.map { it.second })
        }

        if (score == 0) return null

        val appName = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { pkg }
        return AppRisk(pkg, appName, score, reasons)
    }

    private fun enabledAccessibilityPackages(context: Context): Set<String> {
        return try {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return emptySet()
            raw.split(':').mapNotNull { it.substringBefore('/').takeIf { p -> p.isNotBlank() } }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun activeDeviceAdminPackages(context: Context): Set<String> {
        return try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return emptySet()
            dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    // Пакеты Google/производителя — модульные компоненты (языковые пакеты, Bixby,
    // SafetyCore, accessory-менеджеры и т.п.) почти всегда без иконки и без FLAG_SYSTEM,
    // но это не сигнал шпионажа, а нормальная архитектура прошивки.
    private val TRUSTED_PREFIXES = listOf(
        "com.google.android.", "com.google.ar.", "com.android.",
        "com.samsung.", "com.sec.android.", "com.sec.",
        "com.qualcomm.", "com.qti.", "android."
    )

    private fun isTrustedVendor(pkg: String): Boolean = TRUSTED_PREFIXES.any { pkg.startsWith(it) }

    private fun installerOf(context: Context, pkg: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                context.packageManager.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(pkg)
            }
        } catch (_: Exception) { null }
    }
}
