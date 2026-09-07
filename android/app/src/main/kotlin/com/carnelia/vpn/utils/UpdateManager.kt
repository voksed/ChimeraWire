package com.carnelia.vpn.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.carnelia.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Проверка и установка обновлений напрямую из GitHub Releases — пользователю не нужно
 * заходить на GitHub и качать вручную: приложение само находит последнюю версию,
 * скачивает подписанный APK и запускает установку (как sideload-обновление у Telegram).
 *
 * Требует, чтобы release-сборки публиковались через GitHub Actions (см. .github/workflows).
 */
object UpdateManager {

    // Публичный API GitHub Releases — работает без токена на публичном репозитории.
    // После ребрендинга обновления берутся из нового репозитория ChimeraWire.
    private const val RELEASES_API = "https://api.github.com/repos/voksed/ChimeraWire/releases/latest"
    private val CURRENT_VERSION = BuildConfig.VERSION_NAME

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val changelog: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            // tag_name вида "v3.0.1" — убираем ведущую 'v'
            val latest = json.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")
            if (latest.isBlank() || !isNewerVersion(latest, CURRENT_VERSION)) return@withContext null

            // Ищем APK-ассет: arm64 в приоритете, иначе universal
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var arm64Url = ""
            var universalUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name").lowercase()
                val url = a.optString("browser_download_url")
                if (!name.endsWith(".apk")) continue
                when {
                    name.contains("arm64") -> arm64Url = url
                    name.contains("universal") -> universalUrl = url
                }
            }
            val apkUrl = arm64Url.ifBlank { universalUrl }
            if (apkUrl.isBlank()) return@withContext null

            UpdateInfo(
                version = latest,
                downloadUrl = apkUrl,
                changelog = localizeChangelog(json.optString("body", ""))
            )
        } catch (e: Exception) {
            AppLogger.log("UpdateManager: check failed — ${e.message}")
            null
        }
    }

    /**
     * Скачивает APK во внутреннее хранилище и запускает системную установку.
     * onProgress: 0f..1f (или -1f если размер неизвестен). Возвращает true, если установка запущена.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val outFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "carnelia-update.apk")
            if (outFile.exists()) outFile.delete()

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.error("UpdateManager: download HTTP ${response.code}")
                return@withContext false
            }
            val body = response.body ?: return@withContext false
            val total = body.contentLength()
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        onProgress(if (total > 0) downloaded.toFloat() / total else -1f)
                    }
                }
            }

            // content:// URI через FileProvider — обязательно на Android 7+
            val apkUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", outFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            AppLogger.error("UpdateManager: download/install failed", e)
            false
        }
    }

    /** Запасной вариант: открыть страницу релиза в браузере. */
    fun openDownload(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.error("UpdateManager: cannot open URL", e)
        }
    }

    /**
     * Picks the release-notes section for the device language. A release body may bundle
     * several languages, each led by a line marker like `[[en]]`, `[[ru]]`, `[[es]]`
     * (2-letter code). Falls back to English, then to the whole body if unmarked.
     */
    private fun localizeChangelog(body: String): String {
        if (body.isBlank()) return ""
        val marker = Regex("(?m)^[ \\t]*\\[\\[([A-Za-z]{2})\\]\\][ \\t]*$")
        val matches = marker.findAll(body).toList()
        if (matches.isEmpty()) return body.trim()
        val sections = LinkedHashMap<String, String>()
        for (i in matches.indices) {
            val lang = matches[i].groupValues[1].lowercase()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            sections[lang] = body.substring(start, end).trim()
        }
        val lang = java.util.Locale.getDefault().language.lowercase()
        return sections[lang] ?: sections["en"] ?: sections.values.firstOrNull() ?: body.trim()
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val lp = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val cp = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(lp.size, cp.size)) {
            val l = lp.getOrElse(i) { 0 }
            val c = cp.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
