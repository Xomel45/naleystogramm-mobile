package com.xomel45.naleystogramm.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xomel45.naleystogramm.core.Logger
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Mirrors: src/crypto/keyprotector.h — master-key manager.
//
// Android variant: a 32-byte master key is generated on first run, encrypted with an
// Android Keystore AES-GCM wrapping key, and stored in filesDir/master.key.enc.
// The raw master key is held in memory only; only the wrapped blob lives on disk.
//
// Encrypted blob format (same as C++ version): [12 bytes nonce][16 bytes tag][ciphertext]
// Wrapping blob format:                        [1 byte ivLen][iv][Keystore-GCM ciphertext+tag]

object KeyProtector {
    private const val TAG = "KeyProtector"
    private const val KEYSTORE_ALIAS = "naleystogramm_master_enc"
    private const val MASTER_KEY_FILE = "master.key.enc"

    private var masterKey: ByteArray? = null
    private lateinit var masterKeyFile: File

    val isReady: Boolean get() = masterKey != null

    fun init(context: Context) {
        masterKeyFile = File(context.filesDir, MASTER_KEY_FILE)
        ensureKeystoreKey()
        if (masterKeyFile.exists()) {
            loadMasterKey()
        } else {
            generateAndSaveMasterKey()
        }
    }

    private fun ensureKeystoreKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEYSTORE_ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            kg.generateKey()
            Logger.d(TAG, "Android Keystore wrapping key generated")
        }
    }

    private fun generateAndSaveMasterKey() {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val blob = wrapWithKeystore(raw)
        masterKeyFile.writeBytes(blob)
        masterKey = raw
        Logger.i(TAG, "Master key generated")
    }

    private fun loadMasterKey() {
        val blob = masterKeyFile.readBytes()
        val raw = unwrapWithKeystore(blob)
        if (raw == null || raw.size != 32) {
            Logger.e(TAG, "master.key.enc corrupted (size=${raw?.size})")
            return
        }
        masterKey = raw
        Logger.d(TAG, "Master key loaded")
    }

    private fun keystoreKey(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun wrapWithKeystore(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ctWithTag = cipher.doFinal(data)
        // format: [ivLen(1)][iv][ctWithTag]
        return byteArrayOf(iv.size.toByte()) + iv + ctWithTag
    }

    private fun unwrapWithKeystore(blob: ByteArray): ByteArray? {
        if (blob.isEmpty()) return null
        val ivLen = blob[0].toInt() and 0xFF
        if (blob.size < 1 + ivLen) return null
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ctWithTag = blob.copyOfRange(1 + ivLen, blob.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ctWithTag)
        }.getOrElse {
            Logger.e(TAG, "unwrapWithKeystore failed: ${it.message}")
            null
        }
    }

    // HKDF-SHA256(masterKey, label) → N bytes — mirrors C++ KeyProtector::deriveKey()
    fun deriveKey(label: String, bytes: Int = 32): ByteArray {
        val mk = masterKey ?: run {
            Logger.e(TAG, "deriveKey called before init()")
            return ByteArray(0)
        }
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(mk, ByteArray(32), label.toByteArray()))
        val out = ByteArray(bytes)
        hkdf.generateBytes(out, 0, bytes)
        return out
    }

    // AES-256-GCM encrypt — output format: [12 nonce][16 tag][ciphertext]
    fun encrypt(plaintext: ByteArray): ByteArray {
        if (!isReady) {
            Logger.e(TAG, "encrypt called before init()")
            return ByteArray(0)
        }
        val key = deriveKey("naleystogramm-file-enc-v1")
        if (key.isEmpty()) return ByteArray(0)

        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val ctWithTag = cipher.doFinal(plaintext)
            // Android appends 16-byte tag after ciphertext
            val ct  = ctWithTag.copyOfRange(0, ctWithTag.size - 16)
            val tag = ctWithTag.copyOfRange(ctWithTag.size - 16, ctWithTag.size)
            nonce + tag + ct
        }.getOrElse {
            Logger.e(TAG, "encrypt failed: ${it.message}")
            ByteArray(0)
        }
    }

    // AES-256-GCM decrypt — expects blob format: [12 nonce][16 tag][ciphertext]
    fun decrypt(blob: ByteArray): ByteArray {
        if (!isReady) {
            Logger.e(TAG, "decrypt called before init()")
            return ByteArray(0)
        }
        if (blob.size < 28) {
            Logger.w(TAG, "decrypt: blob too small (${blob.size} bytes)")
            return ByteArray(0)
        }
        val key = deriveKey("naleystogramm-file-enc-v1")
        if (key.isEmpty()) return ByteArray(0)

        val nonce = blob.copyOfRange(0, 12)
        val tag   = blob.copyOfRange(12, 28)
        val ct    = blob.copyOfRange(28, blob.size)

        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            // Android expects ciphertext + tag combined
            cipher.doFinal(ct + tag)
        }.getOrElse {
            Logger.e(TAG, "decrypt: GCM auth failed — data corrupted or wrong key")
            ByteArray(0)
        }
    }
}
