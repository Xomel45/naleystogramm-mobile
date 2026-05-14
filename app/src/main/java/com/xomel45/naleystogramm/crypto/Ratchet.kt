package com.xomel45.naleystogramm.crypto

import com.xomel45.naleystogramm.core.Logger
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Mirrors: src/crypto/ratchet.h — Double Ratchet Algorithm (per-message E2E encryption).
//
// After X3DH establishes a shared secret, Double Ratchet provides
// forward secrecy and break-in recovery.

private const val MAX_SKIPPED_KEYS = 100

// Skipped-key map key: hex(dhPub) + ":" + msgNum — avoids ByteArray reference-equality issues.
private fun skippedKeyId(dhPub: ByteArray, msgNum: Int): String =
    "${dhPub.toHexString()}:$msgNum"

private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

data class RatchetState(
    var rootKey: ByteArray       = ByteArray(0),
    var sendChainKey: ByteArray  = ByteArray(0),
    var sendMsgNum: Int          = 0,
    var prevSendMsgNum: Int      = 0,
    var recvChainKey: ByteArray  = ByteArray(0),
    var recvMsgNum: Int          = 0,
    var dhPriv: ByteArray        = ByteArray(0),
    var dhPub: ByteArray         = ByteArray(0),
    var peerDHPub: ByteArray     = ByteArray(0),
    val skippedKeys: MutableMap<String, ByteArray> = mutableMapOf(),
    var initialized: Boolean     = false
)

data class RatchetMessage(
    val dhPub: ByteArray        = ByteArray(0),
    val msgNum: Int             = 0,
    val prevChainLen: Int       = 0,
    val ciphertext: ByteArray   = ByteArray(0),
    val nonce: ByteArray        = ByteArray(0),
    val tag: ByteArray          = ByteArray(0)
)

object DoubleRatchet {
    private const val TAG = "Ratchet"
    private val rng = SecureRandom()

    // HKDF-SHA256 with zero salt — public API, also used for media keys in E2E
    fun hkdf2(ikm: ByteArray, info: String, outLen: Int = 64): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, ByteArray(32), info.toByteArray()))
        val out = ByteArray(outLen)
        hkdf.generateBytes(out, 0, outLen)
        return out
    }

    // KDF_CK(CK) → (newCK, msgKey) via HKDF-SHA256
    private fun chainStep(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val derived = hkdf2(chainKey, "MsgKey", 64)
        return Pair(derived.copyOfRange(0, 32), derived.copyOfRange(32, 64))
    }

    private fun generateX25519() = X3DH.generateX25519()
    private fun dh(priv: ByteArray, pub: ByteArray) = X3DH.dh(priv, pub)

    private fun aesgcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ctWithTag = cipher.doFinal(plaintext)
        val ct  = ctWithTag.copyOfRange(0, ctWithTag.size - 16)
        val tag = ctWithTag.copyOfRange(ctWithTag.size - 16, ctWithTag.size)
        return Pair(ct, tag)
    }

    private fun aesgcmDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, tag: ByteArray): Result<ByteArray> {
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.doFinal(ct + tag)
        }
    }

    // ── Skipped key cache ─────────────────────────────────────────────────────
    //
    // Advances chainKey and msgNum to `until`, storing skipped message keys.
    // Returns (newChainKey, newMsgNum) — callers must assign back to state.

    private fun skipChainKeys(
        state: RatchetState,
        chainKey: ByteArray,
        dhPub: ByteArray,
        msgNum: Int,
        until: Int
    ): Pair<ByteArray, Int> {
        if (until <= msgNum) return Pair(chainKey, msgNum)
        val limit = minOf(until, msgNum + MAX_SKIPPED_KEYS)
        var ck = chainKey
        var num = msgNum
        while (num < limit) {
            if (state.skippedKeys.size >= MAX_SKIPPED_KEYS) {
                Logger.w(TAG, "skipChainKeys: skipped-key buffer full")
                break
            }
            val (newCK, msgKey) = chainStep(ck)
            ck = newCK
            state.skippedKeys[skippedKeyId(dhPub, num)] = msgKey
            num++
        }
        return Pair(ck, num)
    }

    // ── DH ratchet step ───────────────────────────────────────────────────────
    //
    // Mirrors C++ dhRatchet(). Computes new rootKey, sendChainKey, peerDHPub,
    // dhPriv, dhPub. Returns CKr (recv chain key) or failure.

    private fun dhRatchet(state: RatchetState, peerDHPub: ByteArray): Result<ByteArray> {
        return runCatching {
            val dhOut1   = dh(state.dhPriv, peerDHPub)
            val derived1 = hkdf2(state.rootKey + dhOut1, "RatchetStep", 64)

            val (newDHPriv, newDHPub) = generateX25519()
            val dhOut2   = dh(newDHPriv, peerDHPub)
            val derived2 = hkdf2(derived1.copyOfRange(0, 32) + dhOut2, "RatchetStep", 64)

            state.rootKey      = derived2.copyOfRange(0, 32)
            state.sendChainKey = derived2.copyOfRange(32, 64)
            state.peerDHPub    = peerDHPub
            state.dhPriv       = newDHPriv
            state.dhPub        = newDHPub

            derived1.copyOfRange(32, 64) // CKr
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    fun initSender(sharedSecret: ByteArray, peerDHPub: ByteArray): RatchetState {
        val s = RatchetState(rootKey = sharedSecret, peerDHPub = peerDHPub)
        val (dhPriv, dhPub) = generateX25519()
        s.dhPriv = dhPriv
        s.dhPub  = dhPub

        // RK', CKs = KDF_RK(SK, DH(A1, SPK_B))
        val dhOut   = dh(s.dhPriv, peerDHPub)
        val derived = hkdf2(s.rootKey + dhOut, "RatchetStep", 64)
        s.rootKey      = derived.copyOfRange(0, 32)
        s.sendChainKey = derived.copyOfRange(32, 64)
        s.initialized  = true
        return s
    }

    fun initReceiver(sharedSecret: ByteArray, ourDHPriv: ByteArray, ourDHPub: ByteArray): RatchetState {
        return RatchetState(
            rootKey     = sharedSecret,
            dhPriv      = ourDHPriv,
            dhPub       = ourDHPub,
            initialized = true
        )
    }

    // ── Encrypt ───────────────────────────────────────────────────────────────

    fun encrypt(state: RatchetState, plaintext: ByteArray): RatchetMessage {
        check(state.initialized) { "encrypt on uninitialized RatchetState" }

        val (newCK, msgKey) = chainStep(state.sendChainKey)
        state.sendChainKey = newCK

        val aesKey = msgKey.copyOfRange(0, 32)
        val nonce  = ByteArray(12).also { rng.nextBytes(it) }
        val (ct, tag) = aesgcmEncrypt(aesKey, nonce, plaintext)

        val msgNum = state.sendMsgNum++
        return RatchetMessage(
            dhPub        = state.dhPub,
            msgNum       = msgNum,
            prevChainLen = state.prevSendMsgNum,
            ciphertext   = ct,
            nonce        = nonce,
            tag          = tag
        )
    }

    // ── Decrypt ───────────────────────────────────────────────────────────────

    fun decrypt(state: RatchetState, msg: RatchetMessage): Result<ByteArray> {
        // 1. Check skipped-key cache (out-of-order delivery)
        val cachedMsgKey = state.skippedKeys.remove(skippedKeyId(msg.dhPub, msg.msgNum))
        if (cachedMsgKey != null) {
            val aesKey = cachedMsgKey.copyOfRange(0, 32)
            return aesgcmDecrypt(aesKey, msg.nonce, msg.ciphertext, msg.tag)
        }

        // 2. DH ratchet step if peer sent a new DH key
        val didDHRatchet = !msg.dhPub.contentEquals(state.peerDHPub)
        if (didDHRatchet) {
            // Capture old peer pub before dhRatchet overwrites it
            val oldDHPub = state.peerDHPub.copyOf()

            // Store remaining keys of the old recv chain
            val (newCK1, newNum1) = skipChainKeys(state, state.recvChainKey, oldDHPub, state.recvMsgNum, msg.prevChainLen)
            state.recvChainKey = newCK1
            state.recvMsgNum   = newNum1

            state.prevSendMsgNum = state.sendMsgNum
            state.sendMsgNum     = 0

            val ckrResult = dhRatchet(state, msg.dhPub)
            if (ckrResult.isFailure) {
                Logger.e(TAG, "dhRatchet failed: ${ckrResult.exceptionOrNull()?.message}")
                return Result.failure(ckrResult.exceptionOrNull()!!)
            }
            state.recvChainKey = ckrResult.getOrThrow()
            state.recvMsgNum   = 0

            val (newCK2, newNum2) = skipChainKeys(state, state.recvChainKey, msg.dhPub, state.recvMsgNum, msg.msgNum)
            state.recvChainKey = newCK2
            state.recvMsgNum   = newNum2
        } else {
            val (newCK, newNum) = skipChainKeys(state, state.recvChainKey, msg.dhPub, state.recvMsgNum, msg.msgNum)
            state.recvChainKey = newCK
            state.recvMsgNum   = newNum
        }

        // 3. Decrypt current message
        val (newCK, msgKey) = chainStep(state.recvChainKey)
        state.recvChainKey = newCK
        state.recvMsgNum++

        val aesKey = msgKey.copyOfRange(0, 32)
        return aesgcmDecrypt(aesKey, msg.nonce, msg.ciphertext, msg.tag).also { result ->
            if (result.isFailure)
                Logger.w(TAG, "GCM auth tag mismatch — message tampered!")
        }
    }
}
