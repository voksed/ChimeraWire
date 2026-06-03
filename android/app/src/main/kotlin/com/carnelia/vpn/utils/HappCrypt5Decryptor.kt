package com.carnelia.vpn.utils

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * Decrypts happ://crypt5/ links locally using bundled RSA keys + ChaCha20-Poly1305.
 *
 * Algorithm (from reverse-engineering of Happ APK):
 *  1. Strip "happ://crypt5/" prefix
 *  2. blockPairSwap: every ABCD→CDAB (2-char halves swapped per 4-char block)
 *  3. marker = shuffled[0..4] + shuffled[last4]  → look up RSA key
 *  4. Parse body: nonce(12) | decimal-N | marker-byte | url_b64(N) | rsa_b64(rest)
 *  5. RSA-PKCS1v15 decrypt rsa_b64 → swapPairs → base64 → 32-byte ChaCha key
 *  6. ChaCha20-Poly1305 decrypt url_b64 using key+nonce
 *  7. swapPairs → base64 → final URL
 */
object HappCrypt5Decryptor {

    private var keysJson: JSONObject? = null

    fun decrypt(context: Context, happLink: String): String {
        val payload = when {
            happLink.lowercase().startsWith("happ://crypt5/") -> happLink.substring("happ://crypt5/".length)
            happLink.lowercase().startsWith("happ://crypt4/") -> return decryptLegacy(context, happLink, 4)
            else -> throw IllegalArgumentException("Not a happ://crypt5/ link")
        }

        val shuffled = blockPairSwap(payload)
        if (shuffled.length < 20) throw IllegalArgumentException("Payload too short")

        val marker = shuffled.substring(0, 4) + shuffled.substring(shuffled.length - 4)
        val body = shuffled.substring(4, shuffled.length - 4)

        val nonce = body.substring(0, 12).toByteArray(StandardCharsets.UTF_8)

        // Parse leading decimal N
        var d = 0
        while (d < body.length - 12 && body[12 + d].isDigit()) d++
        val N = body.substring(12, 12 + d).toIntOrNull()
            ?: throw IllegalArgumentException("crypt5: missing decimal length field")

        val packed = body.substring(12 + d)
        if (packed.length < 1 + N) throw IllegalArgumentException("crypt5: packed too short")
        val urlB64 = packed.substring(1, 1 + N)
        val rsaB64 = packed.substring(1 + N)

        // RSA decrypt
        val rsaKey = loadKey(context, marker)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, rsaKey)
        val rsaCipherBytes = base64Decode(rsaB64)
        val rsaPlain = cipher.doFinal(rsaCipherBytes)
        val rsaPlainStr = String(rsaPlain, StandardCharsets.UTF_8)
        val chachaKey = base64Decode(swapPairs(rsaPlainStr))

        // ChaCha20-Poly1305 decrypt
        val urlCipherWithTag = base64Decode(urlB64)
        val plainBytes = chacha20Poly1305Decrypt(urlCipherWithTag, chachaKey, nonce)

        return base64Decode(swapPairs(String(plainBytes, StandardCharsets.UTF_8)))
            .toString(StandardCharsets.UTF_8)
    }

    /** ABCD → CDAB for every full 4-char block; tail passes unchanged */
    private fun blockPairSwap(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i + 4 <= s.length) {
            sb.append(s, i + 2, i + 4)
            sb.append(s, i, i + 2)
            i += 4
        }
        while (i < s.length) { sb.append(s[i]); i++ }
        return sb.toString()
    }

    /** ABCD → BADC — swap every adjacent pair */
    private fun swapPairs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i + 2 <= s.length) {
            sb.append(s[i + 1])
            sb.append(s[i])
            i += 2
        }
        if (i < s.length) sb.append(s[i])
        return sb.toString()
    }

    private fun base64Decode(s: String): ByteArray {
        val normalized = s.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun loadKey(context: Context, marker: String): java.security.PrivateKey {
        if (keysJson == null) {
            val json = context.assets.open("happ_crypt5_keys.json")
                .bufferedReader().use { it.readText() }
            keysJson = JSONObject(json)
        }
        val pkcs8B64 = keysJson!!.optString(marker).ifBlank {
            throw IllegalArgumentException("crypt5: unknown marker '$marker' — key not found")
        }
        val keyBytes = Base64.decode(pkcs8B64, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    /** ChaCha20-Poly1305 decryption using Android's built-in provider */
    private fun chacha20Poly1305Decrypt(cipherWithTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "ChaCha20")
        val paramSpec = javax.crypto.spec.IvParameterSpec(nonce)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, paramSpec)
        return cipher.doFinal(cipherWithTag)
    }

    /** crypt1-4: pure RSA-PKCS1v15 block decryption */
    private fun decryptLegacy(context: Context, happLink: String, gen: Int): String {
        AppLogger.log("HappDecrypt: crypt$gen → legacy RSA block decryption not yet implemented")
        throw UnsupportedOperationException("happ://crypt$gen not supported yet; use crypt5")
    }
}
