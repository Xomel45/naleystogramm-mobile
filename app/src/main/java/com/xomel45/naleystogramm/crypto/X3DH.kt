package com.xomel45.naleystogramm.crypto

import com.xomel45.naleystogramm.core.Logger
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

// Mirrors: src/crypto/x3dh.h — Extended Triple Diffie-Hellman key agreement.
//
// Alice (initiator) uses Bob's pre-key bundle to compute:
//   masterSecret = KDF( DH(IK_A, SPK_B) || DH(EK_A, IK_B) ||
//                       DH(EK_A, SPK_B) [|| DH(EK_A, OPK_B)] )
//
// All keys are Curve25519 (X25519 via BouncyCastle).

data class X3DHKeyBundle(
    val identityKey: ByteArray,
    val ikEdPub: ByteArray,           // Ed25519 pub (for SPK sig verification)
    val signedPreKey: ByteArray,
    val signedPreKeySig: ByteArray,
    val oneTimePreKey: ByteArray      // may be empty
)

data class X3DHInitMessage(
    val identityKey: ByteArray,
    val ephemeralKey: ByteArray,
    val usedOtpkId: ByteArray,
    val initialCiphertext: ByteArray
)

object X3DH {
    private const val TAG = "X3DH"
    private val rng = SecureRandom()

    data class Bundle(
        val ikPriv: ByteArray, val ikPub: ByteArray,
        val spkPriv: ByteArray, val spkPub: ByteArray,
        val spkSig: ByteArray,
        val otpkPriv: ByteArray, val otpkPub: ByteArray
    )

    // ── Primitives ────────────────────────────────────────────────────────────

    fun generateX25519(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(rng)
        val pub  = priv.generatePublicKey()
        return Pair(priv.encoded, pub.encoded)
    }

    fun dh(privKey: ByteArray, peerPubKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privKey))
        val out = ByteArray(X25519Agreement.SECRET_SIZE)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPubKey), out, 0)
        return out
    }

    // HKDF-SHA256 with zero salt — matches C++ X3DH::kdf()
    fun kdf(ikm: ByteArray, info: String): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, ByteArray(32), info.toByteArray()))
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    // X25519 private bytes → Ed25519 public key (same Curve25519 scalar, different encoding)
    fun ikPrivToEdPub(ikPriv: ByteArray): ByteArray {
        if (ikPriv.size != 32) return ByteArray(0)
        return runCatching {
            Ed25519PrivateKeyParameters(ikPriv).generatePublicKey().encoded
        }.getOrElse {
            Logger.w(TAG, "ikPrivToEdPub failed: ${it.message}")
            ByteArray(0)
        }
    }

    fun verifySpkSig(ikEdPub: ByteArray, spkPub: ByteArray, sig: ByteArray): Boolean {
        if (ikEdPub.size != 32 || spkPub.isEmpty() || sig.isEmpty()) {
            Logger.w(TAG, "verifySpkSig: invalid arguments")
            return false
        }
        return runCatching {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(ikEdPub))
            verifier.update(spkPub, 0, spkPub.size)
            verifier.verifySignature(sig)
        }.getOrElse {
            Logger.e(TAG, "verifySpkSig failed: ${it.message}")
            false
        }
    }

    // ── Bundle generation ─────────────────────────────────────────────────────

    fun generateBundle(): Bundle? {
        return runCatching {
            val (ikPriv, ikPub)     = generateX25519()
            val (spkPriv, spkPub)   = generateX25519()
            val (otpkPriv, otpkPub) = generateX25519()

            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(ikPriv))
            signer.update(spkPub, 0, spkPub.size)
            val spkSig = signer.generateSignature()

            Bundle(ikPriv, ikPub, spkPriv, spkPub, spkSig, otpkPriv, otpkPub)
        }.getOrElse {
            Logger.e(TAG, "generateBundle failed: ${it.message}")
            null
        }
    }

    // ── X3DH Initiator (Alice) ────────────────────────────────────────────────
    // Returns (sharedSecret, ephemeralPub) or null on failure.

    fun initiatorAgreement(
        aliceIKPriv: ByteArray,
        bobBundle: X3DHKeyBundle
    ): Pair<ByteArray, ByteArray>? {
        if (bobBundle.ikEdPub.isEmpty()) {
            Logger.e(TAG, "Peer has no ik_ed — SPK verification impossible, session rejected")
            return null
        }
        if (!verifySpkSig(bobBundle.ikEdPub, bobBundle.signedPreKey, bobBundle.signedPreKeySig)) {
            Logger.e(TAG, "SPK signature invalid! Possible MITM attack. Session rejected.")
            return null
        }

        return runCatching {
            val (ekPriv, ekPub) = generateX25519()

            val dh1 = dh(aliceIKPriv, bobBundle.signedPreKey)
            val dh2 = dh(ekPriv, bobBundle.identityKey)
            val dh3 = dh(ekPriv, bobBundle.signedPreKey)

            var ikm = dh1 + dh2 + dh3
            if (bobBundle.oneTimePreKey.isNotEmpty()) {
                ikm += dh(ekPriv, bobBundle.oneTimePreKey)
            }

            val secret = kdf(ikm, "naleystogramm_X3DH_v1")
            Logger.d(TAG, "SPK signature verified. Initiator agreement complete.")
            Pair(secret, ekPub)
        }.getOrElse {
            Logger.e(TAG, "initiatorAgreement failed: ${it.message}")
            null
        }
    }

    // ── X3DH Responder (Bob) ──────────────────────────────────────────────────

    fun responderAgreement(
        bobIKPriv: ByteArray,
        bobSPKPriv: ByteArray,
        bobOTPKPriv: ByteArray,
        aliceMsg: X3DHInitMessage
    ): ByteArray? {
        return runCatching {
            val dh1 = dh(bobSPKPriv, aliceMsg.identityKey)
            val dh2 = dh(bobIKPriv, aliceMsg.ephemeralKey)
            val dh3 = dh(bobSPKPriv, aliceMsg.ephemeralKey)

            var ikm = dh1 + dh2 + dh3
            if (bobOTPKPriv.isNotEmpty()) {
                ikm += dh(bobOTPKPriv, aliceMsg.ephemeralKey)
            }

            kdf(ikm, "naleystogramm_X3DH_v1")
        }.getOrElse {
            Logger.e(TAG, "responderAgreement failed: ${it.message}")
            null
        }
    }
}
