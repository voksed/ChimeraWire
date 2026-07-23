package com.carnelia.vpn.core.chat

import com.carnelia.vpn.core.VpnServerConfig
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Комната чата = общий секрет конкретного VPN-конфига. У кого есть тот же конфиг (тот же
 * uuid/pbk/пароль), у того совпадёт roomSecret -> тот же infohash в DHT -> та же комната.
 * Отдельного обмена ключами/контактами не требуется — секрет уже общий, раз конфиг передан.
 */
object ChatCrypto {
    private const val NONCE_SIZE = 12

    fun roomSecret(config: VpnServerConfig): ByteArray {
        val primary = config.config["uuid"]
            ?: config.config["pbk"] ?: config.config["publicKey"]
            ?: config.config["password"] ?: config.password
            ?: ""
        val material = "${config.protocol}|${config.host}|${config.port}|$primary"
        return sha256(material.toByteArray(Charsets.UTF_8))
    }

    /** 20-байтный SHA1 — под формат infohash-а Mainline DHT. */
    fun roomInfohash(roomSecret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(roomSecret)

    fun roomKey(roomSecret: ByteArray): ByteArray =
        sha256(roomSecret + "carnelia-chat-v1".toByteArray(Charsets.UTF_8))

    /** Возвращает nonce(12 байт) + ciphertext. */
    fun encrypt(roomKey: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(roomKey, "ChaCha20"), IvParameterSpec(nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /** Ожидает вход в формате nonce(12 байт) + ciphertext. Возвращает null, если не расшифровалось. */
    fun decrypt(roomKey: ByteArray, data: ByteArray): ByteArray? {
        if (data.size <= NONCE_SIZE) return null
        return try {
            val nonce = data.copyOfRange(0, NONCE_SIZE)
            val ciphertext = data.copyOfRange(NONCE_SIZE, data.size)
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(roomKey, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
