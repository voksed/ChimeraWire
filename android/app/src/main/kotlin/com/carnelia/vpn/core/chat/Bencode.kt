package com.carnelia.vpn.core.chat

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Минимальный bencode-кодек — формат, которым говорит протокол KRPC (Mainline DHT / BitTorrent).
 * Значения: Long (целые), ByteArray (байт-строки — НЕ обязательно текст, id-шники/токены бинарные),
 * List<Any> (списки), Map<String, Any> (словари, ключи всегда bencode-строки, при энкодинге
 * сортируются — того требует спецификация для канонической формы).
 */
object Bencode {

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        encodeInto(value, out)
        return out.toByteArray()
    }

    private fun encodeInto(value: Any?, out: ByteArrayOutputStream) {
        when (value) {
            is Long -> out.write("i${value}e".toByteArray(StandardCharsets.US_ASCII))
            is Int -> encodeInto(value.toLong(), out)
            is ByteArray -> {
                out.write("${value.size}:".toByteArray(StandardCharsets.US_ASCII))
                out.write(value)
            }
            is String -> encodeInto(value.toByteArray(StandardCharsets.UTF_8), out)
            is List<*> -> {
                out.write('l'.code)
                value.forEach { encodeInto(it, out) }
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                value.entries
                    .map { (it.key as String) to it.value }
                    .sortedBy { it.first }
                    .forEach { (k, v) ->
                        encodeInto(k, out)
                        encodeInto(v, out)
                    }
                out.write('e'.code)
            }
            else -> throw IllegalArgumentException("Unsupported bencode type: ${value?.javaClass}")
        }
    }

    /** Разбирает ОДНО bencode-значение начиная с offset. Возвращает (значение, следующий offset). */
    fun decode(data: ByteArray, offset: Int = 0): Pair<Any, Int> {
        if (offset >= data.size) throw IllegalArgumentException("bencode: unexpected end of data")
        return when (data[offset].toInt().toChar()) {
            'i' -> decodeInt(data, offset)
            'l' -> decodeList(data, offset)
            'd' -> decodeDict(data, offset)
            in '0'..'9' -> decodeString(data, offset)
            else -> throw IllegalArgumentException("bencode: invalid token at $offset: ${data[offset]}")
        }
    }

    private fun decodeInt(data: ByteArray, offset: Int): Pair<Long, Int> {
        val end = indexOf(data, 'e'.code.toByte(), offset + 1)
        val n = String(data, offset + 1, end - offset - 1, StandardCharsets.US_ASCII).toLong()
        return n to (end + 1)
    }

    private fun decodeString(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val colon = indexOf(data, ':'.code.toByte(), offset)
        val len = String(data, offset, colon - offset, StandardCharsets.US_ASCII).toInt()
        val start = colon + 1
        val end = start + len
        return data.copyOfRange(start, end) to end
    }

    private fun decodeList(data: ByteArray, offset: Int): Pair<List<Any>, Int> {
        val list = mutableListOf<Any>()
        var pos = offset + 1
        while (data[pos].toInt().toChar() != 'e') {
            val (v, next) = decode(data, pos)
            list.add(v)
            pos = next
        }
        return list to (pos + 1)
    }

    private fun decodeDict(data: ByteArray, offset: Int): Pair<Map<String, Any>, Int> {
        val map = LinkedHashMap<String, Any>()
        var pos = offset + 1
        while (data[pos].toInt().toChar() != 'e') {
            val (keyBytes, afterKey) = decodeString(data, pos)
            val (value, afterValue) = decode(data, afterKey)
            map[String(keyBytes, StandardCharsets.UTF_8)] = value
            pos = afterValue
        }
        return map to (pos + 1)
    }

    private fun indexOf(data: ByteArray, target: Byte, from: Int): Int {
        var i = from
        while (i < data.size) {
            if (data[i] == target) return i
            i++
        }
        throw IllegalArgumentException("bencode: token not found")
    }
}
