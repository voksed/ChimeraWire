package com.carnelia.vpn.core.chat

import com.carnelia.vpn.utils.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Лёгкий клиент публичной Mainline DHT (той же сети, что у BitTorrent) — используется только
 * как механизм ОБНАРУЖЕНИЯ пиров по инфохэшу (get_peers/announce_peer), не для доставки
 * сообщений. Не полноценный Kademlia-узел: своей k-bucket таблицы не строит, входящие запросы
 * от чужих узлов не обслуживает (кроме минимального ответа на ping) — только клиентские
 * запросы наружу с ограниченной 2-хоповой раскруткой (bootstrap-ноды -> ноды, которые они
 * вернули). Этого достаточно, чтобы найти пиров в маленьком приватном "рое" по общему секрету,
 * не строя полноценный DHT-узел уровня настоящего торрент-клиента.
 */
class DhtClient {
    companion object {
        val BOOTSTRAP_NODES = listOf(
            "router.bittorrent.com" to 6881,
            "dht.transmissionbt.com" to 6881,
            "router.utorrent.com" to 6881
        )
        private const val QUERY_TIMEOUT_MS = 4000L
    }

    val nodeId: ByteArray = ByteArray(20).also { SecureRandom().nextBytes(it) }

    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Map<String, Any>>>()
    private var tidCounter = 0

    val listenPort: Int get() = socket?.localPort ?: -1

    fun start() {
        if (socket != null) return
        val s = DatagramSocket(0)
        s.soTimeout = 0
        socket = s
        receiveJob = scope.launch { receiveLoop(s) }
        AppLogger.log("DhtClient: слушаю на порту ${s.localPort}, node_id=${nodeId.toHex()}")
    }

    fun stop() {
        receiveJob?.cancel()
        socket?.close()
        socket = null
        pending.clear()
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(2048)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val data = packet.data.copyOfRange(0, packet.length)
                handleIncoming(socket, data, packet.address, packet.port)
            } catch (e: Exception) {
                if (!socket.isClosed) AppLogger.error("DhtClient: ошибка приёма", e)
            }
        }
    }

    private fun handleIncoming(socket: DatagramSocket, data: ByteArray, from: InetAddress, fromPort: Int) {
        val msg = try {
            @Suppress("UNCHECKED_CAST")
            Bencode.decode(data).first as Map<String, Any>
        } catch (_: Exception) {
            return
        }
        val tidBytes = msg["t"] as? ByteArray ?: return
        val tidKey = tidBytes.toHex()
        when (msg["y"]?.let { String(it as ByteArray) }) {
            "r", "e" -> {
                pending.remove(tidKey)?.complete(msg)
            }
            "q" -> {
                // Минимальный ответ на чужой ping, чтобы быть "вежливым" узлом — остальные типы запросов игнорируем.
                val query = (msg["q"] as? ByteArray)?.let { String(it) }
                if (query == "ping") {
                    val reply = mapOf(
                        "t" to tidBytes,
                        "y" to "r",
                        "r" to mapOf("id" to nodeId)
                    )
                    try {
                        val bytes = Bencode.encode(reply)
                        socket.send(DatagramPacket(bytes, bytes.size, from, fromPort))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun nextTid(): ByteArray {
        tidCounter = (tidCounter + 1) and 0xFFFF
        return byteArrayOf((tidCounter shr 8).toByte(), (tidCounter and 0xFF).toByte())
    }

    private suspend fun query(addr: InetSocketAddress, q: String, args: Map<String, Any>): Map<String, Any>? {
        val s = socket ?: return null
        val tid = nextTid()
        val tidKey = tid.toHex()
        val deferred = CompletableDeferred<Map<String, Any>>()
        pending[tidKey] = deferred
        val msg = mapOf(
            "t" to tid,
            "y" to "q",
            "q" to q,
            "a" to args
        )
        return try {
            val bytes = Bencode.encode(msg)
            withContext(Dispatchers.IO) {
                s.send(DatagramPacket(bytes, bytes.size, addr.address, addr.port))
            }
            withTimeoutOrNull(QUERY_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            null
        } finally {
            pending.remove(tidKey)
        }
    }

    /** get_peers на одну конкретную ноду. Возвращает либо готовых пиров, либо более близкие ноды для след. хопа. */
    private suspend fun getPeersOnce(addr: InetSocketAddress, infohash: ByteArray): GetPeersResponse? {
        val resp = query(addr, "get_peers", mapOf("id" to nodeId, "info_hash" to infohash)) ?: return null
        @Suppress("UNCHECKED_CAST")
        val r = resp["r"] as? Map<String, Any> ?: return null
        val token = r["token"] as? ByteArray
        val values = (r["values"] as? List<*>)?.mapNotNull { (it as? ByteArray)?.let { b -> decodeCompactPeer(b) } }
        val nodes = (r["nodes"] as? ByteArray)?.let { decodeCompactNodes(it) } ?: emptyList()
        return GetPeersResponse(responderAddr = addr, token = token, peers = values ?: emptyList(), closerNodes = nodes)
    }

    /**
     * Ограниченная 2-хоповая раскрутка: спрашиваем bootstrap-ноды; если сразу вернули пиров — берём;
     * если вернули только более близкие ноды — спрашиваем ещё раз до [maxSecondHop] из них.
     * Заодно announce_peer на ноды, давшие token, чтобы другие участники того же роя нашли и нас.
     */
    suspend fun findPeers(infohash: ByteArray, announcePort: Int, maxSecondHop: Int = 8): List<InetSocketAddress> {
        val bootstrap = resolveBootstrap()
        val peers = mutableSetOf<InetSocketAddress>()
        val secondHopCandidates = mutableListOf<InetSocketAddress>()

        for (addr in bootstrap) {
            val resp = getPeersOnce(addr, infohash) ?: continue
            peers.addAll(resp.peers)
            if (resp.token != null) announcePeer(resp.responderAddr, infohash, resp.token, announcePort)
            secondHopCandidates.addAll(resp.closerNodes)
        }

        for (addr in secondHopCandidates.take(maxSecondHop)) {
            val resp = getPeersOnce(addr, infohash) ?: continue
            peers.addAll(resp.peers)
            if (resp.token != null) announcePeer(resp.responderAddr, infohash, resp.token, announcePort)
        }

        AppLogger.log("DhtClient: найдено ${peers.size} пиров по инфохэшу ${infohash.toHex().take(8)}…")
        return peers.toList()
    }

    private suspend fun announcePeer(addr: InetSocketAddress, infohash: ByteArray, token: ByteArray, port: Int) {
        query(
            addr, "announce_peer", mapOf(
                "id" to nodeId,
                "info_hash" to infohash,
                "port" to port.toLong(),
                "token" to token,
                "implied_port" to 0L
            )
        )
    }

    private suspend fun resolveBootstrap(): List<InetSocketAddress> = withContext(Dispatchers.IO) {
        BOOTSTRAP_NODES.mapNotNull { (host, port) ->
            try {
                InetSocketAddress(InetAddress.getByName(host), port)
            } catch (e: Exception) {
                AppLogger.error("DhtClient: не удалось резолвить bootstrap $host", e)
                null
            }
        }
    }

    private fun decodeCompactPeer(b: ByteArray): InetSocketAddress? {
        if (b.size != 6) return null
        val ip = InetAddress.getByAddress(b.copyOfRange(0, 4))
        val port = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        return InetSocketAddress(ip, port)
    }

    private fun decodeCompactNodes(b: ByteArray): List<InetSocketAddress> {
        val result = mutableListOf<InetSocketAddress>()
        var i = 0
        while (i + 26 <= b.size) {
            val ip = InetAddress.getByAddress(b.copyOfRange(i + 20, i + 24))
            val port = ((b[i + 24].toInt() and 0xFF) shl 8) or (b[i + 25].toInt() and 0xFF)
            result.add(InetSocketAddress(ip, port))
            i += 26
        }
        return result
    }

    private data class GetPeersResponse(
        val responderAddr: InetSocketAddress,
        val token: ByteArray?,
        val peers: List<InetSocketAddress>,
        val closerNodes: List<InetSocketAddress>
    )
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
