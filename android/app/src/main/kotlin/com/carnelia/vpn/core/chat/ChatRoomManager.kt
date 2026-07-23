package com.carnelia.vpn.core.chat

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import com.carnelia.vpn.core.VpnServerConfig
import com.carnelia.vpn.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// @Keep — сериализуется в файл через Gson-рефлексию; без этого R8 при минификации переименовывает
// поля класса, и после следующей пересборки старая история чата перестаёт читаться (уже словил
// это на живом устройстве: сохранённый JSON содержал "a","b","c" вместо "id","from","text").
@Keep
data class ChatMessage(
    val id: String,
    val from: String,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean
)

data class PeerInfo(val host: String, val port: Int, val lastSeen: Long)

/**
 * Комната на один VPN-конфиг: находит других держателей того же конфига через публичную
 * Mainline DHT (см. [DhtClient]) и обменивается с ними сообщениями напрямую по UDP,
 * зашифрованными ключом, выведенным из общего секрета конфига (см. [ChatCrypto]).
 * Никакого сервера-посредника — если пиры не нашли друг друга через DHT (например,
 * оба за жёстким carrier-grade NAT), сообщения никуда не денутся, просто не дойдут.
 */
class ChatRoomManager(private val context: Context, val config: VpnServerConfig) {

    private val roomSecret = ChatCrypto.roomSecret(config)
    val roomInfohash: ByteArray = ChatCrypto.roomInfohash(roomSecret)
    private val roomKey = ChatCrypto.roomKey(roomSecret)
    val roomId: String = roomInfohash.toHex()

    val nickname: String = Build.MODEL ?: "Carnelia"

    private val dht = DhtClient()
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dhtJob: Job? = null
    private var recvJob: Job? = null
    private val knownPeers = ConcurrentHashMap<String, InetSocketAddress>()
    private val gson = Gson()
    private val historyFile: File get() = File(context.filesDir, "chat_$roomId.json")

    private val _messages = kotlinx.coroutines.flow.MutableStateFlow(loadHistory())
    val messages: kotlinx.coroutines.flow.StateFlow<List<ChatMessage>> = _messages

    private val _peers = kotlinx.coroutines.flow.MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: kotlinx.coroutines.flow.StateFlow<List<PeerInfo>> = _peers

    private val _isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRunning

    fun start() {
        if (socket != null) return
        try {
            val s = DatagramSocket(0)
            socket = s
            dht.start()
            recvJob = scope.launch { receiveLoop(s) }
            dhtJob = scope.launch { dhtLoop(s.localPort) }
            _isRunning.value = true
            AppLogger.log("Chat[$roomId]: комната запущена, порт=${s.localPort}")
        } catch (e: Exception) {
            AppLogger.error("Chat[$roomId]: не удалось запустить", e)
        }
    }

    fun stop() {
        dhtJob?.cancel()
        recvJob?.cancel()
        socket?.close()
        socket = null
        dht.stop()
        _isRunning.value = false
    }

    private suspend fun dhtLoop(myPort: Int) {
        while (currentCoroutineContext().isActive) {
            try {
                val found = dht.findPeers(roomInfohash, myPort)
                found.forEach { addr ->
                    val key = "${addr.address.hostAddress}:${addr.port}"
                    if (knownPeers.putIfAbsent(key, addr) == null) {
                        AppLogger.log("Chat[$roomId]: новый пир $key")
                    }
                }
                publishPeers()
                knownPeers.values.forEach { sendEnvelope(it, JSONObject().put("type", "hello")) }
            } catch (e: Exception) {
                AppLogger.error("Chat[$roomId]: DHT-цикл", e)
            }
            delay(5 * 60_000L)
        }
    }

    private fun publishPeers() {
        _peers.value = knownPeers.values.map { PeerInfo(it.address.hostAddress ?: "?", it.port, System.currentTimeMillis()) }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(16 * 1024)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val data = packet.data.copyOfRange(0, packet.length)
                val plain = ChatCrypto.decrypt(roomKey, data) ?: continue
                val key = "${packet.address.hostAddress}:${packet.port}"
                val isNew = knownPeers.putIfAbsent(key, InetSocketAddress(packet.address, packet.port)) == null
                if (isNew) publishPeers()

                val json = JSONObject(String(plain, Charsets.UTF_8))
                if (json.optString("type") == "msg") {
                    val msg = ChatMessage(
                        id = json.optString("id", UUID.randomUUID().toString()),
                        from = json.optString("from", "?"),
                        text = json.optString("text", ""),
                        timestamp = json.optLong("ts", System.currentTimeMillis()),
                        outgoing = false
                    )
                    appendMessage(msg)
                }
            } catch (e: Exception) {
                if (!socket.isClosed) AppLogger.error("Chat[$roomId]: приём", e)
            }
        }
    }

    private fun sendEnvelope(addr: InetSocketAddress, json: JSONObject) {
        try {
            val enc = ChatCrypto.encrypt(roomKey, json.toString().toByteArray(Charsets.UTF_8))
            socket?.send(DatagramPacket(enc, enc.size, addr.address, addr.port))
        } catch (_: Exception) {
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val msg = ChatMessage(UUID.randomUUID().toString(), nickname, text, System.currentTimeMillis(), outgoing = true)
        appendMessage(msg)
        scope.launch {
            val json = JSONObject().apply {
                put("type", "msg"); put("id", msg.id); put("from", msg.from); put("text", msg.text); put("ts", msg.timestamp)
            }
            knownPeers.values.forEach { sendEnvelope(it, json) }
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        val updated = (_messages.value + msg).takeLast(500)
        _messages.value = updated
        saveHistory(updated)
    }

    private fun loadHistory(): List<ChatMessage> {
        return try {
            if (!historyFile.exists()) return emptyList()
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            gson.fromJson<List<ChatMessage>>(historyFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.error("Chat[$roomId]: не удалось загрузить историю", e)
            emptyList()
        }
    }

    private fun saveHistory(list: List<ChatMessage>) {
        try {
            historyFile.writeText(gson.toJson(list))
        } catch (e: Exception) {
            AppLogger.error("Chat[$roomId]: не удалось сохранить историю", e)
        }
    }
}

/** Общий реестр запущенных комнат — чтобы UI и фоновый ChatService делили один и тот же сокет/состояние. */
object ChatRoomRegistry {
    private val rooms = ConcurrentHashMap<String, ChatRoomManager>()

    fun get(context: Context, config: VpnServerConfig): ChatRoomManager {
        val secret = ChatCrypto.roomSecret(config)
        val id = ChatCrypto.roomInfohash(secret).toHex()
        return rooms.getOrPut(id) { ChatRoomManager(context.applicationContext, config) }
    }

    fun activeRooms(): List<ChatRoomManager> = rooms.values.toList()

    fun stopAll() {
        rooms.values.forEach { it.stop() }
        rooms.clear()
    }
}
