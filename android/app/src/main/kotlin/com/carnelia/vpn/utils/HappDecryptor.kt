package com.carnelia.vpn.utils

import android.util.Base64
import com.happproxy.util.ErrorCodeJNIWrapper

object HappDecryptor {

    private val CRYPT_PREFIXES = mapOf(
        "crypt/"  to 0,
        "crypt2/" to 1,
        "crypt3/" to 2,
        "crypt4/" to 3,
        "crypt5/" to 4
    )

    // Returns a plain HTTPS subscription URL if input is a Happ deeplink, null otherwise
    fun resolveSubscriptionUrl(rawUrl: String): String? {
        val url = rawUrl.trim()

        // happ://crypt4/<payload>
        if (url.startsWith("happ://", ignoreCase = true)) {
            return decryptHappUrl(url)
        }

        // https://...?deeplinkb64=<base64_of_happ_url>
        val deeplinkb64 = extractDeeplinkB64(url)
        if (deeplinkb64 != null) {
            return try {
                val decoded = String(Base64.decode(deeplinkb64, Base64.DEFAULT), Charsets.UTF_8)
                decryptHappUrl(decoded)
            } catch (_: Exception) { null }
        }

        return null
    }

    private fun decryptHappUrl(happUrl: String): String? {
        // Strip "happ://" prefix
        val body = happUrl.removePrefix("happ://").removePrefix("happ://")
            .let { if (it.startsWith("happ://")) it.removePrefix("happ://") else it }

        // Strip trailing #fragment if present
        val cleanBody = body.substringBefore('#')

        for ((prefix, type) in CRYPT_PREFIXES) {
            if (cleanBody.startsWith(prefix, ignoreCase = true)) {
                val payload = cleanBody.removePrefix(prefix)
                if (payload.isBlank()) return null
                val decrypted = ErrorCodeJNIWrapper().decrypt(payload, type) ?: return null
                return if (decrypted.startsWith("http")) decrypted else null
            }
        }
        return null
    }

    private fun extractDeeplinkB64(url: String): String? {
        return try {
            val idx = url.indexOf("deeplinkb64=")
            if (idx < 0) return null
            val start = idx + "deeplinkb64=".length
            val end = url.indexOf('&', start).let { if (it < 0) url.length else it }
            url.substring(start, end).takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    fun isHappUrl(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("happ://", ignoreCase = true) ||
               u.contains("deeplinkb64=")
    }
}
