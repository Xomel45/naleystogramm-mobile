package com.xomel45.naleystogramm.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

// Mirrors: src/core/network.h — P2P TCP, '\n'-delimited JSON frames.
//
// Protocol: HANDSHAKE → HANDSHAKE_ACK, then PING/PONG keepalive + application frames.
// Exponential backoff reconnect, rate limiting (200 frames/sec), 16 MB buffer guard.
//
// Design: each PeerConnection owns a single coroutine readLoop that runs from first
// byte to disconnect. Pending/accepted state is tracked by map membership + conn.state,
// so acceptIncoming() simply moves the conn between maps — no new readLoop needed.

const val APP_VERSION   = "0.7.3"
const val DEFAULT_PORT  = 47821

private const val MIN_PEER_VERSION      = "0.7.3"
private const val PING_INTERVAL_MS      = 30_000L
private const val PONG_TIMEOUT_MS       = 10_000L
private const val CONNECTION_TIMEOUT_MS = 10_000L
private const val MAX_RECONNECT_ATTEMPTS = 50
private const val MAX_RECONNECT_DELAY_MS = 30_000L
private const val MAX_FRAMES_PER_SEC    = 200
private const val MAX_BUFFER_BYTES      = 16 * 1024 * 1024
private const val MAX_FRAME_BYTES       = 1 * 1024 * 1024
private const val MAX_NAME_LEN          = 256
private const val MESSAGE_QUEUE_SIZE    = 100

// ── Public types ──────────────────────────────────────────────────────────────

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting }

sealed class NetworkEvent {
    data class IncomingRequest(val peerId: String, val peerName: String, val peerIp: String) : NetworkEvent()
    data class PeerConnected(val peerId: String, val peerName: String) : NetworkEvent()
    data class PeerDisconnected(val peerId: String) : NetworkEvent()
    data class MessageReceived(val fromId: String, val msg: JSONObject) : NetworkEvent()
    data class ContactNameUpdated(val peerId: String, val name: String) : NetworkEvent()
    data class ConnectionStateChanged(val peerId: String, val state: ConnectionState) : NetworkEvent()
    data class NetworkError(val message: String) : NetworkEvent()
}

// ── Internal state ────────────────────────────────────────────────────────────

private class PeerConnection(
    var peerId: String,           // starts as tempId for incoming; set to real UUID on HANDSHAKE
    var name: String         = "",
    var ip: String           = "",
    var port: Int            = 0,
    var serverPort: Int      = 0,
    val socket: Socket,
    val writer: PrintWriter,
    var state: ConnectionState  = ConnectionState.Disconnected,
    var latencyMs: Long         = -1,
    var connectedSince: Long    = 0,
    // Rate limiting
    var rateWindowStart: Long   = 0,
    var rateCount: Int          = 0,
    // Keepalive
    var awaitingPong: Boolean   = false,
    var pingSentAt: Long        = 0,
    var pingJob: Job?           = null,
    var pongTimeoutJob: Job?    = null
)

private data class ReconnectInfo(val name: String, val ip: String, val port: Int)

// ── NetworkManager ────────────────────────────────────────────────────────────

class NetworkManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    var localPort: Int = DEFAULT_PORT
        private set

    private val mu = Mutex()

    // pending: tempId → conn (incoming, waiting for user accept)
    private val pending = mutableMapOf<String, PeerConnection>()
    // peers:   realUuid → conn (accepted/outgoing)
    private val peers   = mutableMapOf<String, PeerConnection>()

    private val messageQueues    = mutableMapOf<String, ArrayDeque<JSONObject>>()
    private val reconnectInfo    = mutableMapOf<String, ReconnectInfo>()
    private val reconnectAttempts = mutableMapOf<String, Int>()

    // ── Start / Stop ──────────────────────────────────────────────────────────

    fun start() {
        scope.launch { runServer() }
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun runServer() {
        val configured = DEFAULT_PORT
        var port = configured
        var ss: ServerSocket? = null
        while (ss == null && isActive) {
            ss = runCatching { ServerSocket(port) }.getOrNull()
                ?: if (++port > configured + 20) {
                    emit(NetworkEvent.NetworkError("Cannot bind near port $configured"))
                    return
                } else null
        }
        val server = ss ?: return
        localPort = port
        Logger.i("Network", "Listening on port $port")

        try {
            while (isActive) {
                val client = withContext(Dispatchers.IO) { server.accept() }
                scope.launch { handleIncoming(client) }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { server.close() }
        }
    }

    // ── Incoming ──────────────────────────────────────────────────────────────

    private suspend fun handleIncoming(socket: Socket) {
        val ip     = socket.inetAddress.hostAddress ?: "?"
        val tempId = java.util.UUID.randomUUID().toString()
        val conn   = makeConn(tempId, ip = ip, socket = socket)

        mu.withLock { pending[tempId] = conn }
        Logger.d("Network", "Incoming from $ip (id=${tempId.take(8)})")

        readLoop(conn)

        // Cleanup if still pending (rejected or socket closed before accept)
        mu.withLock { pending.remove(tempId) }
    }

    // ── Outgoing ──────────────────────────────────────────────────────────────

    fun connectToPeer(peerId: String, name: String, ip: String, port: Int) {
        scope.launch {
            mu.withLock {
                val ex = peers[peerId]
                if (ex != null && (ex.state == ConnectionState.Connected ||
                                   ex.state == ConnectionState.Connecting)) return@launch
                reconnectInfo[peerId] = ReconnectInfo(name, ip, port)
            }
            doConnect(peerId, name, ip, port)
        }
    }

    private suspend fun doConnect(peerId: String, name: String, ip: String, port: Int) {
        emit(NetworkEvent.ConnectionStateChanged(peerId, ConnectionState.Connecting))
        Logger.i("Network", "Connecting to $name ($ip:$port)…")

        val result = runCatching {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    Socket().apply { connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT_MS.toInt()) }
                }
            }
        }
        val socket = result.getOrNull() ?: run {
            Logger.w("Network", "Connect to $name failed: ${result.exceptionOrNull()?.message}")
            scheduleReconnect(peerId)
            return
        }

        val conn = makeConn(peerId, name = name, ip = ip, port = port,
                            socket = socket, state = ConnectionState.Connected,
                            connectedSince = System.currentTimeMillis())

        val isDuplicate = mu.withLock {
            val ex = peers[peerId]
            if (ex != null && ex.state == ConnectionState.Connected) {
                true
            } else {
                peers[peerId] = conn
                reconnectAttempts.remove(peerId)
                false
            }
        }
        if (isDuplicate) {
            Logger.d("Network", "Duplicate outgoing to $name — already connected")
            socket.close(); return
        }

        sendHandshake(conn)
        startKeepalive(conn)
        emit(NetworkEvent.ConnectionStateChanged(peerId, ConnectionState.Connected))
        Logger.i("Network", "Connected to $name")

        readLoop(conn)

        // Post-disconnect cleanup
        onPeerLost(conn)
    }

    // ── Read loop (one per connection, runs from open to close) ──────────────

    private suspend fun readLoop(conn: PeerConnection) {
        val reader = BufferedReader(InputStreamReader(conn.socket.getInputStream()))
        var bufSize = 0
        try {
            while (isActive && !conn.socket.isClosed) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                bufSize += line.length
                if (bufSize > MAX_BUFFER_BYTES) {
                    Logger.w("Network", "Buffer overflow from ${conn.ip} — closing")
                    break
                }
                if (line.length > MAX_FRAME_BYTES) {
                    Logger.w("Network", "Frame too large from ${conn.ip} — skipped")
                    continue
                }
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                handleFrame(conn, obj)
            }
        } catch (_: Exception) { }
        runCatching { conn.socket.close() }
    }

    // ── Frame dispatch ────────────────────────────────────────────────────────

    private suspend fun handleFrame(conn: PeerConnection, obj: JSONObject) {
        // Rate limiting: 200 frames/sec
        val now = System.currentTimeMillis()
        if (now - conn.rateWindowStart >= 1000L) { conn.rateWindowStart = now; conn.rateCount = 0 }
        if (++conn.rateCount > MAX_FRAMES_PER_SEC) {
            Logger.w("Network", "Rate limit: ${conn.ip} — closing")
            conn.socket.close(); return
        }

        when (obj.optString("type")) {
            "HANDSHAKE"     -> onHandshake(conn, obj)
            "HANDSHAKE_ACK" -> onHandshakeAck(conn, obj)
            "PING"          -> onPing(conn)
            "PONG"          -> onPong(conn)
            "PROFILE_UPDATE"-> onProfileUpdate(conn, obj)
            else -> {
                // Only forward to upper layer if this is an accepted peer
                val isPeer = mu.withLock { peers.containsValue(conn) }
                if (isPeer) emit(NetworkEvent.MessageReceived(conn.peerId, obj))
            }
        }
    }

    // ── HANDSHAKE ─────────────────────────────────────────────────────────────

    private suspend fun onHandshake(conn: PeerConnection, obj: JSONObject) {
        val rawUuid = obj.optString("uuid", "")
        if (rawUuid.isEmpty()) {
            Logger.w("Network", "HANDSHAKE: empty UUID from ${conn.ip}")
            conn.socket.close(); return
        }
        if (compareVersions(obj.optString("version", ""), MIN_PEER_VERSION) < 0) {
            Logger.w("Network", "HANDSHAKE: version too old from ${conn.ip}")
            conn.socket.close(); return
        }

        val peerName = obj.optString("name", "").take(MAX_NAME_LEN).trim()
        conn.name       = peerName
        conn.serverPort = obj.optInt("port", 0)

        // Store real UUID so acceptIncoming can find this conn
        conn.peerId = rawUuid

        emit(NetworkEvent.IncomingRequest(rawUuid, peerName, conn.ip))
        Logger.i("Network", "HANDSHAKE from $peerName (port=${conn.serverPort})")
    }

    // ── HANDSHAKE_ACK ─────────────────────────────────────────────────────────

    private suspend fun onHandshakeAck(conn: PeerConnection, obj: JSONObject) {
        if (!obj.optBoolean("accepted", false)) {
            Logger.i("Network", "HANDSHAKE_ACK: rejected by ${conn.ip}")
            conn.socket.close(); return
        }
        if (compareVersions(obj.optString("version", ""), MIN_PEER_VERSION) < 0) {
            Logger.w("Network", "HANDSHAKE_ACK: version too old")
            conn.socket.close(); return
        }
        conn.name = obj.optString("name", "").take(MAX_NAME_LEN).trim()
        Logger.i("Network", "HANDSHAKE_ACK: accepted by ${conn.name}")

        emit(NetworkEvent.PeerConnected(conn.peerId, conn.name))
        drainQueue(conn.peerId)
    }

    // ── Accept / Reject ───────────────────────────────────────────────────────

    fun acceptIncoming(realPeerId: String) {
        scope.launch {
            val conn = mu.withLock {
                // Find pending conn whose peerId was updated by onHandshake
                val c = pending.values.find { it.peerId == realPeerId }
                if (c != null) {
                    pending.remove(c.peerId)
                    // Check for duplicate
                    val ex = peers[realPeerId]
                    if (ex != null && ex.state == ConnectionState.Connected) {
                        Logger.d("Network", "Duplicate incoming — already connected")
                        return@withLock null
                    }
                    c.state          = ConnectionState.Connected
                    c.connectedSince = System.currentTimeMillis()
                    peers[realPeerId] = c
                    reconnectInfo[realPeerId] = ReconnectInfo(c.name, c.ip, c.serverPort)
                }
                c
            } ?: return@launch

            val ack = JSONObject().apply {
                put("type",     "HANDSHAKE_ACK")
                put("accepted", true)
                put("uuid",     Identity.uuid)
                put("name",     Identity.displayName)
                put("version",  APP_VERSION)
            }
            conn.writer.println(ack.toString())
            Logger.i("Network", "Accepted ${conn.name}")

            startKeepalive(conn)
            emit(NetworkEvent.PeerConnected(realPeerId, conn.name))
            emit(NetworkEvent.ConnectionStateChanged(realPeerId, ConnectionState.Connected))
            drainQueue(realPeerId)

            // readLoop is already running from handleIncoming — it continues seamlessly
            // and will call onPeerLost when the socket closes
        }
    }

    fun rejectIncoming(realPeerId: String) {
        scope.launch {
            val conn = mu.withLock {
                val c = pending.values.find { it.peerId == realPeerId }
                if (c != null) pending.remove(c.peerId)
                c
            } ?: return@launch
            val ack = JSONObject().apply {
                put("type", "HANDSHAKE_ACK")
                put("accepted", false)
            }
            runCatching { conn.writer.println(ack.toString()) }
            runCatching { conn.socket.close() }
        }
    }

    // ── PING / PONG ───────────────────────────────────────────────────────────

    private fun onPing(conn: PeerConnection) {
        val pong = JSONObject().apply { put("type", "PONG"); put("ts", System.currentTimeMillis()) }
        conn.writer.println(pong.toString())
        Logger.d("Network", "PONG → ${conn.name}")
    }

    private fun onPong(conn: PeerConnection) {
        conn.awaitingPong = false
        conn.pongTimeoutJob?.cancel()
        conn.latencyMs = System.currentTimeMillis() - conn.pingSentAt
        Logger.d("Network", "PONG ← ${conn.name} (${conn.latencyMs}ms)")
    }

    private fun startKeepalive(conn: PeerConnection) {
        conn.pingJob = scope.launch {
            while (isActive) {
                sendPing(conn)
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun stopKeepalive(conn: PeerConnection) {
        conn.pingJob?.cancel(); conn.pingJob = null
        conn.pongTimeoutJob?.cancel(); conn.pongTimeoutJob = null
        conn.awaitingPong = false
    }

    private fun sendPing(conn: PeerConnection) {
        if (conn.awaitingPong) return
        if (conn.socket.isClosed) return
        val ping = JSONObject().apply { put("type", "PING"); put("ts", System.currentTimeMillis()) }
        conn.writer.println(ping.toString())
        conn.awaitingPong = true
        conn.pingSentAt   = System.currentTimeMillis()
        Logger.d("Network", "PING → ${conn.name}")

        conn.pongTimeoutJob?.cancel()
        conn.pongTimeoutJob = scope.launch {
            delay(PONG_TIMEOUT_MS)
            if (conn.awaitingPong) {
                Logger.w("Network", "PONG timeout — ${conn.name} dead")
                conn.socket.close()
            }
        }
    }

    // ── Profile update ────────────────────────────────────────────────────────

    private suspend fun onProfileUpdate(conn: PeerConnection, obj: JSONObject) {
        val newName = obj.optString("name", "").take(MAX_NAME_LEN).trim()
        if (newName.isNotEmpty() && newName != conn.name) {
            conn.name = newName
            emit(NetworkEvent.ContactNameUpdated(conn.peerId, newName))
        }
    }

    // ── Disconnect / reconnect ────────────────────────────────────────────────

    private suspend fun onPeerLost(conn: PeerConnection) {
        val peerId = conn.peerId
        stopKeepalive(conn)
        mu.withLock { peers.remove(peerId) }
        Logger.i("Network", "Lost ${conn.name}")
        emit(NetworkEvent.PeerDisconnected(peerId))
        emit(NetworkEvent.ConnectionStateChanged(peerId, ConnectionState.Disconnected))
        scheduleReconnect(peerId)
    }

    private fun scheduleReconnect(peerId: String) {
        scope.launch {
            val info = mu.withLock { reconnectInfo[peerId] } ?: return@launch
            val attempts = mu.withLock {
                val n = (reconnectAttempts[peerId] ?: 0) + 1
                reconnectAttempts[peerId] = n; n
            }
            if (attempts > MAX_RECONNECT_ATTEMPTS) {
                Logger.w("Network", "Max reconnect for ${info.name} — giving up")
                mu.withLock {
                    reconnectInfo.remove(peerId)
                    reconnectAttempts.remove(peerId)
                    messageQueues.remove(peerId)
                }
                emit(NetworkEvent.NetworkError("Reconnect failed: ${info.name}"))
                return@launch
            }
            val delayMs = backoffMs(attempts)
            Logger.i("Network", "Reconnect ${info.name} in ${delayMs}ms (#$attempts)")
            emit(NetworkEvent.ConnectionStateChanged(peerId, ConnectionState.Reconnecting))
            delay(delayMs)
            doConnect(peerId, info.name, info.ip, info.port)
        }
    }

    private fun backoffMs(n: Int): Long =
        minOf((1L shl minOf(n - 1, 5)) * 1000L, MAX_RECONNECT_DELAY_MS)

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendJson(peerId: String, obj: JSONObject) {
        scope.launch {
            val conn = mu.withLock { peers[peerId] }
            if (conn == null || conn.socket.isClosed) {
                mu.withLock {
                    val q = messageQueues.getOrPut(peerId) { ArrayDeque() }
                    if (q.size < MESSAGE_QUEUE_SIZE) q.addLast(obj)
                    else Logger.w("Network", "Queue full for $peerId — dropped")
                }
                return@launch
            }
            withContext(Dispatchers.IO) { conn.writer.println(obj.toString()) }
        }
    }

    private suspend fun drainQueue(peerId: String) {
        val q = mu.withLock { messageQueues.remove(peerId) } ?: return
        Logger.i("Network", "Drain ${q.size} queued for $peerId")
        q.forEach { sendJson(peerId, it) }
    }

    fun broadcastProfileUpdate(name: String) {
        val msg = JSONObject().apply { put("type", "PROFILE_UPDATE"); put("name", name) }
        scope.launch {
            val ids = mu.withLock { peers.keys.toList() }
            ids.forEach { sendJson(it, msg) }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    fun isOnline(peerId: String): Boolean = peers.containsKey(peerId)
    fun getState(peerId: String): ConnectionState = peers[peerId]?.state ?: ConnectionState.Disconnected
    fun getLatency(peerId: String): Long = peers[peerId]?.latencyMs ?: -1
    fun getPeerIp(peerId: String): String? = peers[peerId]?.ip

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendHandshake(conn: PeerConnection) {
        val hs = JSONObject().apply {
            put("type",    "HANDSHAKE")
            put("uuid",    Identity.uuid)
            put("name",    Identity.displayName)
            put("port",    localPort)
            put("version", APP_VERSION)
        }
        conn.writer.println(hs.toString())
    }

    private fun makeConn(
        peerId: String,
        name: String = "",
        ip: String = "",
        port: Int = 0,
        socket: Socket,
        state: ConnectionState = ConnectionState.Disconnected,
        connectedSince: Long = 0
    ) = PeerConnection(
        peerId = peerId, name = name, ip = ip, port = port,
        socket = socket,
        writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true),
        state  = state, connectedSince = connectedSince
    )

    private fun emit(event: NetworkEvent) {
        scope.launch { _events.emit(event) }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
