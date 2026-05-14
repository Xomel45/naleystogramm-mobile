package com.xomel45.naleystogramm.crypto

import com.xomel45.naleystogramm.core.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// Mirrors: src/crypto/e2e.h — one E2ESession per peer.
// Handles key generation, bundle exchange, and message encryption.
//
// Thread safety: per-peer synchronized locks, equivalent to C++ QMutex per session.

private const val TAG = "E2E"
private const val OTPK_POOL_SIZE = 100

class E2EManager(private val keysDir: File) {

    // Our permanent identity key pair + Ed25519 pub for SPK verification
    private var ikPriv  = ByteArray(0)
    private var ikPub   = ByteArray(0)
    private var ikEdPub = ByteArray(0)

    // Our signed pre-key
    private var spkPriv = ByteArray(0)
    private var spkPub  = ByteArray(0)
    private var spkSig  = ByteArray(0)

    // One-time pre-key pool: list of (priv, pub)
    private val otpks = mutableListOf<Pair<ByteArray, ByteArray>>()

    // Per-peer ratchet sessions
    private val sessions = mutableMapOf<String, RatchetState>()

    // Peer identity public keys for Safety Number computation
    private val peerIdentityKeys = mutableMapOf<String, ByteArray>()

    // Per-peer locks; mapLock guards the map itself
    private val sessionLocks = mutableMapOf<String, Any>()
    private val mapLock = Any()

    var onSessionEstablished: ((peerId: String) -> Unit)? = null

    fun init(ourUuid: String) {
        keysDir.mkdirs()
        loadOrGenerateKeys(ourUuid)
    }

    // ── Key persistence ───────────────────────────────────────────────────────

    private fun keysFile(uuid: String) = File(keysDir, "$uuid.json")

    private fun loadOrGenerateKeys(uuid: String) {
        val file = keysFile(uuid)
        if (file.exists()) {
            val raw = file.readBytes()
            if (!KeyProtector.isReady) {
                Logger.e(TAG, "KeyProtector not ready — refusing to load keys")
                return
            }
            val jsonBytes = if (raw.isNotEmpty() && raw[0] == '{'.code.toByte()) {
                // Plaintext on disk (legacy): migrate to encrypted format
                Logger.w(TAG, "keys.json in plaintext — migrating to encrypted format")
                raw
            } else {
                val dec = KeyProtector.decrypt(raw)
                if (dec.isEmpty()) {
                    Logger.e(TAG, "Failed to decrypt keys.json — corrupted?")
                    return
                }
                dec
            }
            parseKeysJson(JSONObject(String(jsonBytes)))

            if (ikPriv.isNotEmpty() && spkPriv.isNotEmpty()) {
                ikEdPub = X3DH.ikPrivToEdPub(ikPriv)
                // Re-save if we migrated from plaintext
                if (raw.isNotEmpty() && raw[0] == '{'.code.toByte()) saveKeys(uuid)
                Logger.d(TAG, "Keys loaded from disk")
                return
            }
        }

        generateFreshKeys(uuid)
    }

    private fun parseKeysJson(obj: JSONObject) {
        ikPriv  = obj.getString("ik_priv").hexToBytes()
        ikPub   = obj.getString("ik_pub").hexToBytes()
        spkPriv = obj.getString("spk_priv").hexToBytes()
        spkPub  = obj.getString("spk_pub").hexToBytes()
        spkSig  = obj.getString("spk_sig").hexToBytes()
        otpks.clear()
        val arr = obj.optJSONArray("otpks") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            otpks.add(Pair(o.getString("priv").hexToBytes(), o.getString("pub").hexToBytes()))
        }
    }

    private fun generateFreshKeys(uuid: String) {
        val bundle = X3DH.generateBundle() ?: run {
            Logger.e(TAG, "Key generation failed!")
            return
        }
        ikPriv  = bundle.ikPriv
        ikPub   = bundle.ikPub
        spkPriv = bundle.spkPriv
        spkPub  = bundle.spkPub
        spkSig  = bundle.spkSig
        ikEdPub = X3DH.ikPrivToEdPub(ikPriv)

        otpks.clear()
        repeat(OTPK_POOL_SIZE) {
            X3DH.generateBundle()?.let { b ->
                otpks.add(Pair(b.otpkPriv, b.otpkPub))
            }
        }

        saveKeys(uuid)
        Logger.i(TAG, "Fresh keys generated")
    }

    private fun saveKeys(uuid: String) {
        if (!KeyProtector.isReady) {
            Logger.e(TAG, "KeyProtector not ready — keys NOT saved")
            return
        }
        val obj = JSONObject().apply {
            put("ik_priv",  ikPriv.toHex())
            put("ik_pub",   ikPub.toHex())
            put("spk_priv", spkPriv.toHex())
            put("spk_pub",  spkPub.toHex())
            put("spk_sig",  spkSig.toHex())
            val arr = JSONArray()
            for ((priv, pub) in otpks) {
                arr.put(JSONObject().apply {
                    put("priv", priv.toHex())
                    put("pub",  pub.toHex())
                })
            }
            put("otpks", arr)
        }
        val encrypted = KeyProtector.encrypt(obj.toString().toByteArray())
        if (encrypted.isEmpty()) {
            Logger.e(TAG, "Encryption of keys.json failed — NOT saved")
            return
        }
        keysFile(uuid).writeBytes(encrypted)
        Logger.d(TAG, "keys.json saved (encrypted)")
    }

    // ── Bundle serialization ──────────────────────────────────────────────────

    fun ourBundleJson(): JSONObject = JSONObject().apply {
        put("ik",      ikPub.toHex())
        put("spk",     spkPub.toHex())
        put("spk_sig", spkSig.toHex())
        if (ikEdPub.isNotEmpty()) put("ik_ed", ikEdPub.toHex())
        if (otpks.isNotEmpty())   put("otpk",  otpks.first().second.toHex())
    }

    // ── Session establishment ─────────────────────────────────────────────────

    fun initiateSession(peerId: String, theirBundle: JSONObject): JSONObject {
        val bundle = X3DHKeyBundle(
            identityKey      = theirBundle.getString("ik").hexToBytes(),
            signedPreKey     = theirBundle.getString("spk").hexToBytes(),
            signedPreKeySig  = theirBundle.getString("spk_sig").hexToBytes(),
            ikEdPub          = theirBundle.optString("ik_ed", "").hexToBytes(),
            oneTimePreKey    = theirBundle.optString("otpk", "").hexToBytes()
        )

        val result = X3DH.initiatorAgreement(ikPriv, bundle) ?: run {
            Logger.w(TAG, "initiatorAgreement failed for $peerId")
            return JSONObject()
        }
        val (secret, ephPub) = result

        peerIdentityKeys[peerId] = bundle.identityKey
        lockFor(peerId).let { lock ->
            synchronized(lock) {
                sessions[peerId] = DoubleRatchet.initSender(secret, bundle.signedPreKey)
            }
        }

        onSessionEstablished?.invoke(peerId)

        return JSONObject().apply {
            put("type",   "KEY_INIT")
            put("ik",     ikPub.toHex())
            put("ek",     ephPub.toHex())
            put("otpk",   bundle.oneTimePreKey.toHex())
            put("bundle", ourBundleJson())
        }
    }

    fun acceptSession(peerId: String, initMsg: JSONObject): JSONObject {
        val alice = X3DHInitMessage(
            identityKey      = initMsg.getString("ik").hexToBytes(),
            ephemeralKey     = initMsg.getString("ek").hexToBytes(),
            usedOtpkId       = ByteArray(0),
            initialCiphertext = ByteArray(0)
        )

        val otpkPriv = if (initMsg.optString("otpk", "").isNotEmpty()) {
            val otpkPub = initMsg.getString("otpk").hexToBytes()
            consumeOtpkPriv(otpkPub)
        } else {
            ByteArray(0)
        }

        val secret = X3DH.responderAgreement(ikPriv, spkPriv, otpkPriv, alice) ?: run {
            Logger.w(TAG, "responderAgreement failed for $peerId")
            return JSONObject()
        }

        peerIdentityKeys[peerId] = alice.identityKey
        lockFor(peerId).let { lock ->
            synchronized(lock) {
                sessions[peerId] = DoubleRatchet.initReceiver(secret, spkPriv, spkPub)
            }
        }

        onSessionEstablished?.invoke(peerId)

        return JSONObject().apply {
            put("type",   "KEY_ACK")
            put("bundle", ourBundleJson())
        }
    }

    // ── Encrypt / Decrypt ─────────────────────────────────────────────────────

    fun encrypt(peerId: String, plaintext: ByteArray): JSONObject {
        return synchronized(lockFor(peerId)) {
            val state = sessions[peerId] ?: run {
                Logger.w(TAG, "No session for $peerId")
                return JSONObject()
            }
            val rm = DoubleRatchet.encrypt(state, plaintext)
            JSONObject().apply {
                put("type",  "CHAT")
                put("dh",    rm.dhPub.toHex())
                put("n",     rm.msgNum)
                put("pn",    rm.prevChainLen)
                put("ct",    rm.ciphertext.toHex())
                put("nonce", rm.nonce.toHex())
                put("tag",   rm.tag.toHex())
            }
        }
    }

    fun decrypt(peerId: String, envelope: JSONObject): Result<ByteArray> {
        return synchronized(lockFor(peerId)) {
            val state = sessions[peerId] ?: run {
                Logger.w(TAG, "No session for $peerId")
                return Result.failure(IllegalStateException("no session for $peerId"))
            }
            val msg = RatchetMessage(
                dhPub        = envelope.getString("dh").hexToBytes(),
                msgNum       = envelope.getInt("n"),
                prevChainLen = envelope.optInt("pn", 0),
                ciphertext   = envelope.getString("ct").hexToBytes(),
                nonce        = envelope.getString("nonce").hexToBytes(),
                tag          = envelope.getString("tag").hexToBytes()
            )
            DoubleRatchet.decrypt(state, msg).also { result ->
                if (result.isFailure)
                    Logger.w(TAG, "Decrypt failed for $peerId: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun hasSession(peerId: String): Boolean = sessions.containsKey(peerId)

    // ── Media key (голосовые звонки) ─────────────────────────────────────────
    // Both peers derive the same 32-byte key for a given callId+salt.

    fun snapshotMediaKey(peerId: String, callId: String, salt: ByteArray): ByteArray {
        return synchronized(lockFor(peerId)) {
            val state = sessions[peerId] ?: return ByteArray(0)
            if (!state.initialized) return ByteArray(0)
            val info = "naleystogramm-media-v1:${callId}:${salt.toHex()}"
            DoubleRatchet.hkdf2(state.rootKey, info, 32)
        }
    }

    // ── Safety Number ─────────────────────────────────────────────────────────
    // SHA-256(ourIKpub || theirIKpub), formatted as 5 groups of 8 hex chars.

    fun getSafetyNumber(peerId: String): String {
        val theirIK = peerIdentityKeys[peerId] ?: return ""
        if (ikPub.isEmpty()) return ""
        val hash = MessageDigest.getInstance("SHA-256").digest(ikPub + theirIK)
        val hex  = hash.take(20).joinToString("") { "%02X".format(it) }
        return (0 until 5).joinToString(" ") { hex.substring(it * 8, it * 8 + 8) }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun lockFor(peerId: String): Any {
        return synchronized(mapLock) {
            sessionLocks.getOrPut(peerId) { Any() }
        }
    }

    private fun consumeOtpkPriv(pub: ByteArray): ByteArray {
        val idx = otpks.indexOfFirst { it.second.contentEquals(pub) }
        if (idx < 0) return ByteArray(0)
        val priv = otpks[idx].first
        otpks.removeAt(idx)
        return priv
    }

    fun destroy() {
        ikPriv.secureZero()
        spkPriv.secureZero()
        for ((priv, _) in otpks) priv.secureZero()
        otpks.clear()
        sessions.clear()
    }

    // ── Hex helpers ───────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
