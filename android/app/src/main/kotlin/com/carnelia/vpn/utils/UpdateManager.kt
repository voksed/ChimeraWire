package com.carnelia.vpn.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val UPDATE_URL = "https://voksed.github.io/carnelia-update/latest.json"
    private const val CURRENT_VERSION = "2.4.2"

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releasePageUrl: String,
        val changelog: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(UPDATE_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            val latest = json.optString("version", "").trim()
            if (latest.isBlank() || !isNewerVersion(latest, CURRENT_VERSION)) return@withContext null

            // Prefer arm64, fallback to universal
            val apkUrl = json.optString("arm64", "").ifBlank {
                json.optString("universal", "").ifBlank {
                    json.optString("url", "")
                }
            }
            val changelog = json.optString("changelog", "").take(500)

            UpdateInfo(
                version = latest,
                downloadUrl = apkUrl,
                releasePageUrl = apkUrl,
                changelog = changelog
            )
        } catch (e: Exception) {
            AppLogger.log("UpdateManager: check failed — ${e.message}")
            null
        }
    }

    fun openDownload(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.error("UpdateManager: cannot open URL", e)
        }
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
