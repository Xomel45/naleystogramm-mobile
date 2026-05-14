package com.xomel45.naleystogramm.core

import com.xomel45.naleystogramm.crypto.E2EManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

// Mirrors: src/core/callmanager.h — voice call state machine + signaling.
//
// States: Idle → Calling → InCall → Ended → Idle
//         Idle → Ringing → InCall → Ended → Idle

enum class CallState { Idle, Calling, Ringing, InCall, Ended }

class CallManager(
    private val network: NetworkManager,
    private val e2e: E2EManager
) {
    companion object {
        private const val TAG              = "CallManager"
        private const val CALL_TIMEOUT_MS  = 30_000L
    }

    val media = MediaEngine()

    private var _state       = CallState.Idle
    val state get()          = _state

    private var callId              = ""
    private var activePeerId        = ""
    private var pendingCallerUdpPort = 0
    private var pendingMediaSalt     = ByteArray(0)
    private var callTimeoutJob: Job? = null

    var onIncomingCall: ((fromId: String, callerName: String, callId: String) -> Unit)? = null
    var onCallAccepted: ((peerId: String) -> Unit)? = null
    var onCallRejected: ((peerId: String, reason: String) -> Unit)? = null
    var onCallEnded:    ((peerId: String) -> Unit)? = null
    var onCallError:    ((msg: String) -> Unit)? = null
    var onStateChanged: ((CallState) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isCallActive() = _state == CallState.InCall
    fun activePeer()   = activePeerId

    // ── Outgoing ──────────────────────────────────────────────────────────────

    fun initiateCall(peerId: String) {
        if (_state != CallState.Idle) { onCallError?.invoke("Call already active"); return }
        if (!e2e.hasSession(peerId)) { onCallError?.invoke("No E2E session with $peerId"); return }
        if (!media.prepareSocket()) { onCallError?.invoke("Audio init failed"); return }

        val salt     = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val callUuid = UUID.randomUUID().toString()
        val mediaKey = e2e.snapshotMediaKey(peerId, callUuid, salt)

        media.setMediaKey(mediaKey)
        callId       = callUuid
        activePeerId = peerId
        setState(CallState.Calling)

        network.sendJson(peerId, JSONObject().apply {
            put("type",     "CALL_INVITE")
            put("call_id",  callId)
            put("udp_port", media.localUdpPort())
            put("salt",     salt.joinToString("") { "%02x".format(it) })
        })
        Logger.i(TAG, "CALL_INVITE → $peerId (callId=${callId.take(8)})")

        callTimeoutJob = scope.launch {
            delay(CALL_TIMEOUT_MS)
            if (_state == CallState.Calling) {
                Logger.w(TAG, "Call timeout — no answer from $peerId")
                media.endCall()
                val pid = activePeerId
                resetState()
                onCallRejected?.invoke(pid, "timeout")
            }
        }
    }

    // ── Incoming accept/reject ────────────────────────────────────────────────

    fun acceptCall(acceptCallId: String) {
        if (_state != CallState.Ringing || acceptCallId != callId) return
        val peerIp = network.getPeerIp(activePeerId) ?: run {
            onCallError?.invoke("Peer IP unknown"); return
        }
        if (!media.prepareSocket()) { onCallError?.invoke("Audio init failed"); return }

        val mediaKey = e2e.snapshotMediaKey(activePeerId, callId, pendingMediaSalt)
        media.setMediaKey(mediaKey)
        media.startAudio(peerIp, pendingCallerUdpPort)
        setState(CallState.InCall)

        network.sendJson(activePeerId, JSONObject().apply {
            put("type",     "CALL_ACCEPT")
            put("call_id",  callId)
            put("udp_port", media.localUdpPort())
        })
        Logger.i(TAG, "CALL_ACCEPT → $activePeerId")
    }

    fun rejectCall(rejectCallId: String, reason: String = "declined") {
        if (_state != CallState.Ringing || rejectCallId != callId) return
        network.sendJson(activePeerId, JSONObject().apply {
            put("type",    "CALL_REJECT")
            put("call_id", callId)
            put("reason",  reason)
        })
        Logger.i(TAG, "CALL_REJECT → $activePeerId: $reason")
        resetState()
    }

    fun endCall() {
        if (_state == CallState.Idle) return
        val peerId = activePeerId
        if (peerId.isNotEmpty()) {
            runCatching {
                network.sendJson(peerId, JSONObject().apply {
                    put("type",    "CALL_END")
                    put("call_id", callId)
                })
            }
        }
        media.endCall()
        setState(CallState.Ended)
        val pid = peerId
        scope.launch { delay(1000); resetState() }
        onCallEnded?.invoke(pid)
        Logger.i(TAG, "CALL_END → $peerId")
    }

    // ── Signaling handler (called from MessagingService) ──────────────────────

    suspend fun handleSignaling(fromId: String, msg: JSONObject) {
        val type = msg.optString("type")
        val cid  = msg.optString("call_id")

        when (type) {
            "CALL_INVITE" -> {
                if (_state != CallState.Idle) {
                    network.sendJson(fromId, JSONObject().apply {
                        put("type", "CALL_REJECT"); put("call_id", cid); put("reason", "busy")
                    })
                    return
                }
                callId               = cid
                activePeerId         = fromId
                pendingCallerUdpPort = msg.optInt("udp_port", 0)
                val saltHex          = msg.optString("salt", "")
                pendingMediaSalt     = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                setState(CallState.Ringing)

                val callerName = Storage.contacts.byId(fromId)?.displayName ?: fromId
                onIncomingCall?.invoke(fromId, callerName, cid)
                Logger.i(TAG, "CALL_INVITE from $fromId (callId=${cid.take(8)})")
            }
            "CALL_ACCEPT" -> {
                if (_state != CallState.Calling || cid != callId) return
                callTimeoutJob?.cancel()
                val peerUdpPort = msg.optInt("udp_port", 0)
                val peerIp      = network.getPeerIp(activePeerId) ?: run {
                    Logger.e(TAG, "Peer IP unknown on CALL_ACCEPT"); return
                }
                media.startAudio(peerIp, peerUdpPort)
                setState(CallState.InCall)
                onCallAccepted?.invoke(activePeerId)
                Logger.i(TAG, "CALL_ACCEPT from $activePeerId")
            }
            "CALL_REJECT" -> {
                if (_state != CallState.Calling || cid != callId) return
                callTimeoutJob?.cancel()
                val reason = msg.optString("reason", "declined")
                val pid    = activePeerId
                media.endCall()
                resetState()
                onCallRejected?.invoke(pid, reason)
                Logger.i(TAG, "CALL_REJECT from $pid: $reason")
            }
            "CALL_END" -> {
                if (cid != callId && cid.isNotEmpty()) return
                val pid = activePeerId
                media.endCall()
                setState(CallState.Ended)
                onCallEnded?.invoke(pid)
                scope.launch { delay(1000); resetState() }
                Logger.i(TAG, "CALL_END from $fromId")
            }
        }
    }

    private fun setState(s: CallState) {
        _state = s
        onStateChanged?.invoke(s)
    }

    private fun resetState() {
        callTimeoutJob?.cancel(); callTimeoutJob = null
        callId              = ""
        activePeerId        = ""
        pendingCallerUdpPort = 0
        pendingMediaSalt     = ByteArray(0)
        setState(CallState.Idle)
    }

    fun destroy() {
        media.endCall()
        scope.cancel()
    }
}
