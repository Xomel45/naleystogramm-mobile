package com.xomel45.naleystogramm.core

import android.util.Base64
import com.xomel45.naleystogramm.crypto.E2EManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

// Mirrors: src/core/remoteshellmanager.h — OTP-authenticated shell sessions over E2E channel.
//
// Signaling (plaintext JSON frames):
//   SHELL_REQUEST → SHELL_CHALLENGE → SHELL_CHALLENGE_RESPONSE → SHELL_ACCEPT | SHELL_REJECT
//   SHELL_KILL (either side)
//
// Shell data (E2E-encrypted, outer type SHELL_DATA / SHELL_INPUT):
//   Inner: {shell_type, session, data:<base64>}

class RemoteShellManager(
    private val network: NetworkManager,
    private val e2e: E2EManager
) {
    companion object {
        private const val TAG         = "RemoteShell"
        private const val OTP_CHARS   = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val OTP_LENGTH  = 6
        private const val TIMEOUT_MS  = 30 * 60 * 1000L

        // Blocks privilege-escalation attempts in shell input
        private val FORBIDDEN = listOf(
            Regex("\\bsudo\\b"),
            Regex("\\bsu\\b"),
            Regex("\\bpasswd\\b"),
            Regex("rm\\s+-rf\\s+/"),
            Regex("chmod\\s+[0-9]*7"),
            Regex("chown\\s+root"),
            Regex("/etc/shadow"),
        )
    }

    enum class Role { Initiator, Receiver }

    private inner class Session(
        val role: Role,
        val peerId: String,
        var otp: String = "",
        var process: Process? = null,
        var inactivityJob: Job? = null
    )

    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = HashMap<String, Session>()

    var onChallengeGenerated: ((sessionId: String, peerId: String, otp: String) -> Unit)?    = null
    var onPasswordRequired:   ((sessionId: String, peerId: String) -> Unit)?                 = null
    var onShellAccepted:      ((sessionId: String, peerId: String) -> Unit)?                 = null
    var onShellStarted:       ((sessionId: String) -> Unit)?                                 = null
    var onShellRejected:      ((sessionId: String, reason: String) -> Unit)?                 = null
    var onDataReceived:       ((sessionId: String, data: ByteArray) -> Unit)?                = null
    var onInputMonitored:     ((sessionId: String, data: ByteArray) -> Unit)?                = null
    var onSessionEnded:       ((sessionId: String, reason: String) -> Unit)?                 = null
    var onPrivEscDetected:    ((sessionId: String) -> Unit)?                                 = null

    // ── Initiator side ────────────────────────────────────────────────────────

    fun requestShell(peerId: String) {
        if (sessions.isNotEmpty()) { Logger.w(TAG, "Already have an active session"); return }
        val sid = UUID.randomUUID().toString()
        sessions[sid] = Session(Role.Initiator, peerId)
        network.sendJson(peerId, JSONObject().apply {
            put("type", "SHELL_REQUEST"); put("session_id", sid)
        })
        Logger.i(TAG, "SHELL_REQUEST → $peerId (${sid.take(8)})")
    }

    fun respondToChallenge(sessionId: String, password: String) {
        val sess = sessions[sessionId] ?: return
        network.sendJson(sess.peerId, JSONObject().apply {
            put("type",       "SHELL_CHALLENGE_RESPONSE")
            put("session_id", sessionId)
            put("password",   password)
        })
    }

    fun sendInput(sessionId: String, data: ByteArray) {
        val sess = sessions[sessionId] ?: return
        val inner = JSONObject().apply {
            put("shell_type", "SHELL_INPUT")
            put("session",    sessionId)
            put("data",       Base64.encodeToString(data, Base64.NO_WRAP))
        }
        sendEncrypted(sess.peerId, inner, "SHELL_INPUT")
        onInputMonitored?.invoke(sessionId, data)
        resetInactivity(sessionId)
    }

    fun rejectRequest(sessionId: String, reason: String = "declined") {
        val sess = sessions.remove(sessionId) ?: return
        network.sendJson(sess.peerId, JSONObject().apply {
            put("type", "SHELL_REJECT"); put("session_id", sessionId); put("reason", reason)
        })
    }

    fun killSession(sessionId: String, reason: String = "terminated") {
        val sess = sessions[sessionId] ?: return
        network.sendJson(sess.peerId, JSONObject().apply {
            put("type", "SHELL_KILL"); put("session_id", sessionId); put("reason", reason)
        })
        cleanupSession(sessionId, reason)
    }

    // ── Signaling handler ─────────────────────────────────────────────────────

    fun handleSignaling(fromId: String, msg: JSONObject) {
        val type = msg.optString("type")
        val sid  = msg.optString("session_id")

        when (type) {
            "SHELL_REQUEST" -> {
                if (sessions.isNotEmpty()) {
                    network.sendJson(fromId, JSONObject().apply {
                        put("type", "SHELL_REJECT"); put("session_id", sid); put("reason", "busy")
                    })
                    return
                }
                val otp = generateOtp()
                sessions[sid] = Session(Role.Receiver, fromId, otp = otp)
                network.sendJson(fromId, JSONObject().apply {
                    put("type", "SHELL_CHALLENGE"); put("session_id", sid)
                })
                onChallengeGenerated?.invoke(sid, fromId, otp)
                Logger.i(TAG, "SHELL_REQUEST from $fromId — OTP=$otp")
            }
            "SHELL_CHALLENGE" -> {
                onPasswordRequired?.invoke(sid, fromId)
            }
            "SHELL_CHALLENGE_RESPONSE" -> {
                val sess = sessions[sid] ?: return
                val pwd  = msg.optString("password")
                if (pwd != sess.otp) {
                    Logger.w(TAG, "Wrong OTP for $sid from $fromId")
                    network.sendJson(fromId, JSONObject().apply {
                        put("type", "SHELL_REJECT"); put("session_id", sid)
                        put("reason", "wrong_password")
                    })
                    sessions.remove(sid); return
                }
                sess.otp = ""  // consumed — cannot be reused
                network.sendJson(fromId, JSONObject().apply {
                    put("type", "SHELL_ACCEPT"); put("session_id", sid)
                })
                onShellStarted?.invoke(sid)
                spawnProcess(sid)
            }
            "SHELL_ACCEPT" -> {
                onShellAccepted?.invoke(sid, fromId)
                Logger.i(TAG, "Shell accepted by $fromId")
            }
            "SHELL_REJECT" -> {
                val reason = msg.optString("reason", "rejected")
                onShellRejected?.invoke(sid, reason)
                sessions.remove(sid)
            }
            "SHELL_KILL" -> {
                cleanupSession(sid, msg.optString("reason", "remote killed"))
            }
        }
    }

    // ── Decrypted SHELL_DATA / SHELL_INPUT handler ────────────────────────────

    fun handleDecryptedData(fromId: String, plaintext: ByteArray) {
        runCatching {
            val inner     = JSONObject(String(plaintext))
            val shellType = inner.optString("shell_type")
            val sid       = inner.optString("session")
            val data      = Base64.decode(inner.getString("data"), Base64.NO_WRAP)

            when (shellType) {
                "SHELL_DATA" -> {
                    onDataReceived?.invoke(sid, data)
                    resetInactivity(sid)
                }
                "SHELL_INPUT" -> {
                    val sess = sessions[sid] ?: return
                    if (sess.role != Role.Receiver || sess.process == null) return
                    if (hasForbiddenPattern(data)) {
                        Logger.w(TAG, "Priv-esc attempt in $sid — killing session")
                        onPrivEscDetected?.invoke(sid)
                        killSession(sid, "privilege_escalation"); return
                    }
                    sess.process?.outputStream?.write(data)
                    sess.process?.outputStream?.flush()
                    onInputMonitored?.invoke(sid, data)
                    resetInactivity(sid)
                }
            }
        }.onFailure { e -> Logger.e(TAG, "handleDecryptedData: ${e.message}") }
    }

    // ── Process (Receiver side) ───────────────────────────────────────────────

    private fun spawnProcess(sessionId: String) {
        val sess = sessions[sessionId] ?: return
        scope.launch {
            runCatching {
                val proc = ProcessBuilder("sh").redirectErrorStream(true).start()
                sess.process = proc

                // Forward stdout/stderr to initiator
                launch {
                    val buf = ByteArray(1024)
                    while (isActive && sess.process != null) {
                        val n = proc.inputStream.read(buf)
                        if (n <= 0) break
                        val chunk = buf.copyOf(n)
                        val inner = JSONObject().apply {
                            put("shell_type", "SHELL_DATA")
                            put("session",    sessionId)
                            put("data",       Base64.encodeToString(chunk, Base64.NO_WRAP))
                        }
                        sendEncrypted(sess.peerId, inner, "SHELL_DATA")
                    }
                    cleanupSession(sessionId, "process exited")
                }

                resetInactivity(sessionId)
            }.onFailure { e ->
                Logger.e(TAG, "spawnProcess failed: ${e.message}")
                network.sendJson(sess.peerId, JSONObject().apply {
                    put("type", "SHELL_REJECT"); put("session_id", sessionId)
                    put("reason", "spawn_failed")
                })
                sessions.remove(sessionId)
            }
        }
    }

    private fun cleanupSession(sessionId: String, reason: String) {
        val sess = sessions.remove(sessionId) ?: return
        sess.inactivityJob?.cancel()
        runCatching { sess.process?.destroy() }
        sess.process = null
        onSessionEnded?.invoke(sessionId, reason)
        Logger.i(TAG, "Session ${sessionId.take(8)} ended: $reason")
    }

    private fun resetInactivity(sessionId: String) {
        val sess = sessions[sessionId] ?: return
        sess.inactivityJob?.cancel()
        sess.inactivityJob = scope.launch {
            delay(TIMEOUT_MS)
            killSession(sessionId, "timeout")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendEncrypted(peerId: String, inner: JSONObject, outerType: String) {
        val envelope = e2e.encrypt(peerId, inner.toString().toByteArray())
        if (envelope.length() == 0) { Logger.w(TAG, "sendEncrypted: no session for $peerId"); return }
        envelope.put("type", outerType)
        network.sendJson(peerId, envelope)
    }

    private fun generateOtp(): String {
        val rng = SecureRandom()
        return (1..OTP_LENGTH).map { OTP_CHARS[rng.nextInt(OTP_CHARS.length)] }.joinToString("")
    }

    private fun hasForbiddenPattern(data: ByteArray): Boolean {
        val text = String(data)
        return FORBIDDEN.any { it.containsMatchIn(text) }
    }

    fun destroy() {
        sessions.keys.toList().forEach { cleanupSession(it, "service destroyed") }
        scope.cancel()
    }
}
