package com.carnelia.vpn.data

import android.content.Context
import android.net.Uri
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backup to/from .carnelia file (AES-256-GCM + PBKDF2).
 * Format: magic(4) + version(1) + salt(16) + iv(12) + ciphertext
 */
object SecureBackupManager {

    private val gson = Gson()
    private val MAGIC = byteArrayOf(0x43, 0x4E, 0x4C, 0x41) // CNLA
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 120_000
    private const val KEY_LEN = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    data class BackupResult(val count: Int, val error: String? = null)

    fun exportToUri(context: Context, uri: Uri, password: String): BackupResult {
        return try {
            val servers = ServerRepository(context).getServers()
            if (servers.isEmpty()) return BackupResult(0, "Нет серверов для экспорта")
            val json = gson.toJson(servers)
            val plainBytes = json.toByteArray(Charsets.UTF_8)

            val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val cipherBytes = cipher.doFinal(plainBytes)

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(MAGIC)
                out.write(VERSION.toInt())
                out.write(salt)
                out.write(iv)
                out.write(cipherBytes)
            }
            AppLogger.log("SecureBackup: Exported ${servers.size} servers")
            BackupResult(servers.size)
        } catch (e: Exception) {
            AppLogger.error("SecureBackup: Export failed", e)
            BackupResult(0, e.message ?: "Ошибка экспорта")
        }
    }

    fun importFromUri(context: Context, uri: Uri, password: String): BackupResult {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return BackupResult(0, "Не удалось открыть файл")

            if (bytes.size < MAGIC.size + 1 + SALT_LEN + IV_LEN + 16)
                return BackupResult(0, "Файл повреждён")

            var pos = 0
            val magic = bytes.sliceArray(pos until pos + 4); pos += 4
            if (!magic.contentEquals(MAGIC)) return BackupResult(0, "Не .carnelia файл")
            val version = bytes[pos]; pos++
            if (version != VERSION) return BackupResult(0, "Неподдерживаемая версия бэкапа")

            val salt = bytes.sliceArray(pos until pos + SALT_LEN); pos += SALT_LEN
            val iv = bytes.sliceArray(pos until pos + IV_LEN); pos += IV_LEN
            val cipherBytes = bytes.sliceArray(pos until bytes.size)

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = try {
                cipher.doFinal(cipherBytes)
            } catch (e: Exception) {
                return BackupResult(0, "Неверный пароль")
            }

            val json = String(plain, Charsets.UTF_8)
            val type = object : TypeToken<List<VpnServerConfig>>() {}.type
            val servers: List<VpnServerConfig> = gson.fromJson(json, type)
                ?: return BackupResult(0, "Ошибка чтения данных")

            val repo = ServerRepository(context)
            servers.forEach { repo.addServer(it) }
            AppLogger.log("SecureBackup: Imported ${servers.size} servers")
            BackupResult(servers.size)
        } catch (e: Exception) {
            AppLogger.error("SecureBackup: Import failed", e)
            BackupResult(0, e.message ?: "Ошибка импорта")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LEN)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}
