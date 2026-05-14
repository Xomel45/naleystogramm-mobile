package com.xomel45.naleystogramm.core

import android.content.Context
import android.os.Environment
import com.xomel45.naleystogramm.crypto.E2EManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Mirrors: src/core/filetransfer.h — streaming file transfer with AES-256-GCM + SHA-256.
//
// Protocol: FILE_OFFER → FILE_ACCEPT → FILE_CHUNK* → FILE_COMPLETE
//           FILE_REJECT | FILE_CANCEL — abort at any stage
//           FILE_PAUSE / (resume implicit via resuming chunk stream)

enum class TransferState { Pending, Active, Paused, Completed, Failed, Cancelled }

data class TransferProgress(
    val id: String,
    val peerId: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val state: TransferState,
    val outgoing: Boolean,
    val speedBps: Double = 0.0,
    val durationMs: Int  = 0
)

class FileTransfer(
    private val context: Context,
    private val network: NetworkManager,
    @Suppress("UNUSED_PARAMETER") private val e2e: E2EManager
) {
    companion object {
        private const val TAG          = "FileTransfer"
        private const val CHUNK_SIZE   = 65_536       // 64 KB
        private const val GCM_TAG_SIZE = 16
        private const val KEY_SIZE     = 32
        private const val NONCE_SIZE   = 12
        private const val SPEED_PERIOD = 500L         // ms between speed samples
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rng   = SecureRandom()

    private inner class OutTx(
        val id: String, val peerId: String,
        val filePath: String, val fileName: String,
        val fileSize: Long, val fileHash: ByteArray,
        val key: ByteArray, val baseNonce: ByteArray,
        val durationMs: Int = 0
    ) {
        @Volatile var state: TransferState = TransferState.Pending
        var bytesSent: Long   = 0L
        var speedBps: Double  = 0.0
        var lastBytes: Long   = 0L
        var lastTime: Long    = System.currentTimeMillis()
    }

    private inner class InTx(
        val id: String, val peerId: String,
        val fileName: String, val fileSize: Long,
        val expectedHash: ByteArray,
        val key: ByteArray, val baseNonce: ByteArray,
        val tempPath: String, val finalPath: String,
        val durationMs: Int = 0
    ) {
        @Volatile var state: TransferState = TransferState.Pending
        var bytesReceived: Long = 0L
        val hasher: MessageDigest = MessageDigest.getInstance("SHA-256")
        var speedBps: Double = 0.0
        var lastBytes: Long  = 0L
        var lastTime: Long   = System.currentTimeMillis()
    }

    private val outgoing = HashMap<String, OutTx>()
    private val incoming = HashMap<String, InTx>()

    var onFileOffer: ((fromId: String, name: String, size: Long, offerId: String, durationMs: Int) -> Unit)? = null
    var onTransferStarted:   ((TransferProgress) -> Unit)? = null
    var onTransferProgress:  ((TransferProgress) -> Unit)? = null
    var onTransferCompleted: ((id: String, filePath: String, outgoing: Boolean) -> Unit)? = null
    var onTransferFailed:    ((id: String, error: String) -> Unit)? = null
    var onTransferCancelled: ((id: String) -> Unit)? = null
    var onFileReceived:      ((fromId: String, path: String, name: String) -> Unit)? = null

    // ── Send ──────────────────────────────────────────────────────────────────

    fun sendFile(peerId: String, filePath: String, durationMs: Int = 0) {
        scope.launch {
            val file = File(filePath)
            if (!file.exists()) { Logger.e(TAG, "File not found: $filePath"); return@launch }
            val id        = java.util.UUID.randomUUID().toString()
            val key       = ByteArray(KEY_SIZE).also { rng.nextBytes(it) }
            val baseNonce = ByteArray(NONCE_SIZE).also { rng.nextBytes(it) }
            val hash      = computeHash(filePath)
            val tx        = OutTx(id, peerId, filePath, sanitize(file.name), file.length(), hash, key, baseNonce, durationMs)
            synchronized(outgoing) { outgoing[id] = tx }

            network.sendJson(peerId, JSONObject().apply {
                put("type",     "FILE_OFFER")
                put("offer_id", id)
                put("name",     tx.fileName)
                put("size",     tx.fileSize)
                put("hash",     hash.toHex())
                put("key",      key.toHex())
                put("nonce",    baseNonce.toHex())
                if (durationMs > 0) put("duration_ms", durationMs)
            })
            Logger.i(TAG, "FILE_OFFER → $peerId: ${tx.fileName} (${tx.fileSize}B)")
        }
    }

    fun acceptOffer(fromId: String, offerId: String) {
        val tx = synchronized(incoming) { incoming[offerId] } ?: return
        if (tx.state != TransferState.Pending) return
        tx.state = TransferState.Active
        network.sendJson(fromId, JSONObject().apply {
            put("type", "FILE_ACCEPT"); put("offer_id", offerId)
        })
        onTransferStarted?.invoke(tx.toProgress())
        Logger.i(TAG, "FILE_ACCEPT → $fromId")
    }

    fun rejectOffer(fromId: String, offerId: String) {
        synchronized(incoming) { incoming.remove(offerId) }
        network.sendJson(fromId, JSONObject().apply {
            put("type", "FILE_REJECT"); put("offer_id", offerId)
        })
        Logger.i(TAG, "FILE_REJECT → $fromId")
    }

    fun cancelTransfer(id: String) {
        val peerId = synchronized(outgoing) { outgoing.remove(id)?.peerId }
            ?: synchronized(incoming) { incoming.remove(id)?.peerId } ?: return
        network.sendJson(peerId, JSONObject().apply {
            put("type", "FILE_CANCEL"); put("offer_id", id)
        })
        onTransferCancelled?.invoke(id)
    }

    fun pauseTransfer(id: String) {
        val tx = synchronized(outgoing) { outgoing[id] } ?: return
        tx.state = TransferState.Paused
        network.sendJson(tx.peerId, JSONObject().apply {
            put("type", "FILE_PAUSE"); put("offer_id", id)
        })
    }

    fun resumeTransfer(id: String) {
        val tx = synchronized(outgoing) { outgoing[id] } ?: return
        if (tx.state != TransferState.Paused) return
        tx.state = TransferState.Active
        scope.launch { streamChunks(id) }
    }

    fun getProgress(id: String): TransferProgress? {
        synchronized(outgoing) { outgoing[id]?.let { return it.toProgress() } }
        synchronized(incoming) { incoming[id]?.let { return it.toProgress() } }
        return null
    }

    fun hasPendingTransfers(peerId: String): Boolean {
        return synchronized(outgoing) { outgoing.values.any { it.peerId == peerId } } ||
               synchronized(incoming) { incoming.values.any { it.peerId == peerId } }
    }

    // ── Message dispatch ──────────────────────────────────────────────────────

    fun handleMessage(fromId: String, msg: JSONObject) {
        when (msg.optString("type")) {
            "FILE_OFFER"    -> handleOffer(fromId, msg)
            "FILE_ACCEPT"   -> handleAccept(fromId, msg)
            "FILE_REJECT"   -> handleReject(msg)
            "FILE_CHUNK"    -> handleChunk(msg)
            "FILE_COMPLETE" -> handleComplete(msg)
            "FILE_CANCEL"   -> handleCancel(msg)
            "FILE_PAUSE"    -> synchronized(outgoing) { outgoing[msg.optString("offer_id")]?.state = TransferState.Paused }
        }
    }

    private fun handleOffer(fromId: String, msg: JSONObject) {
        val id         = msg.getString("offer_id")
        val name       = sanitize(msg.getString("name"))
        val size       = msg.getLong("size")
        val hash       = msg.getString("hash").fromHex()
        val key        = msg.getString("key").fromHex()
        val baseNonce  = msg.getString("nonce").fromHex()
        val duration   = msg.optInt("duration_ms", 0)
        val tempPath   = File(context.cacheDir, "dl_$id.tmp").absolutePath
        val finalPath  = safeDownloadPath(name)

        val tx = InTx(id, fromId, name, size, hash, key, baseNonce, tempPath, finalPath, duration)
        synchronized(incoming) { incoming[id] = tx }

        if (duration > 0) {
            acceptOffer(fromId, id)
        } else {
            onFileOffer?.invoke(fromId, name, size, id, duration)
        }
        Logger.i(TAG, "FILE_OFFER from $fromId: $name ($size B)")
    }

    private fun handleAccept(fromId: String, msg: JSONObject) {
        val id = msg.optString("offer_id")
        val tx = synchronized(outgoing) { outgoing[id] } ?: return
        if (tx.peerId != fromId) return
        tx.state = TransferState.Active
        onTransferStarted?.invoke(tx.toProgress())
        scope.launch { streamChunks(id) }
        Logger.i(TAG, "FILE_ACCEPT from $fromId: $id")
    }

    private fun handleReject(msg: JSONObject) {
        val id = msg.optString("offer_id")
        synchronized(outgoing) { outgoing.remove(id) }
        onTransferCancelled?.invoke(id)
    }

    private fun handleCancel(msg: JSONObject) {
        val id = msg.optString("offer_id")
        synchronized(outgoing) { outgoing.remove(id) }
        synchronized(incoming) { incoming.remove(id) }
        onTransferCancelled?.invoke(id)
    }

    private fun handleChunk(msg: JSONObject) {
        val id  = msg.optString("offer_id")
        val tx  = synchronized(incoming) { incoming[id] } ?: return
        if (tx.state == TransferState.Completed || tx.state == TransferState.Failed) return

        val seq = msg.getLong("seq")
        val ct  = msg.getString("ct").fromHex()
        val tag = msg.getString("tag").fromHex()
        val chunk = decryptChunk(ct, tag, tx.key, tx.baseNonce, seq) ?: run {
            Logger.e(TAG, "Chunk decrypt failed id=$id seq=$seq")
            tx.state = TransferState.Failed
            synchronized(incoming) { incoming.remove(id) }
            onTransferFailed?.invoke(id, "Decryption failed")
            return
        }

        runCatching {
            RandomAccessFile(tx.tempPath, "rw").use { raf ->
                raf.seek(seq * CHUNK_SIZE); raf.write(chunk)
            }
        }.onFailure { e ->
            Logger.e(TAG, "Write chunk failed: ${e.message}")
            tx.state = TransferState.Failed
            onTransferFailed?.invoke(id, "Write error")
            return
        }

        synchronized(tx) { tx.hasher.update(chunk) }
        tx.bytesReceived += chunk.size
        updateSpeed(tx)
        onTransferProgress?.invoke(tx.toProgress())

        if (tx.bytesReceived >= tx.fileSize) finishReceiving(id)
    }

    private fun handleComplete(msg: JSONObject) {
        val id = msg.optString("offer_id")
        val tx = synchronized(incoming) { incoming[id] } ?: return
        if (tx.bytesReceived >= tx.fileSize) finishReceiving(id)
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private suspend fun streamChunks(id: String) {
        val tx = synchronized(outgoing) { outgoing[id] } ?: return
        val raf = runCatching { RandomAccessFile(File(tx.filePath), "r") }.getOrElse {
            onTransferFailed?.invoke(id, "File not found"); return
        }
        val buf = ByteArray(CHUNK_SIZE)
        var seq = 0L

        while (isActive) {
            val state = synchronized(outgoing) { outgoing[id]?.state }
            when {
                state == null || state == TransferState.Cancelled -> break
                state == TransferState.Paused -> { delay(200); continue }
            }
            val read = raf.read(buf)
            if (read <= 0) break

            val (ct, tag) = encryptChunk(buf.copyOf(read), tx.key, tx.baseNonce, seq)
            network.sendJson(tx.peerId, JSONObject().apply {
                put("type",     "FILE_CHUNK")
                put("offer_id", id)
                put("seq",      seq)
                put("ct",       ct.toHex())
                put("tag",      tag.toHex())
            })
            tx.bytesSent += read
            seq++
            updateSpeed(tx)
            onTransferProgress?.invoke(tx.toProgress())
            delay(1)
        }
        raf.close()

        val active = synchronized(outgoing) { outgoing[id]?.state == TransferState.Active }
        if (active) {
            network.sendJson(tx.peerId, JSONObject().apply {
                put("type", "FILE_COMPLETE"); put("offer_id", id)
            })
            tx.state = TransferState.Completed
            synchronized(outgoing) { outgoing.remove(id) }
            onTransferCompleted?.invoke(id, tx.filePath, true)
            Logger.i(TAG, "Transfer done: ${tx.fileName} → ${tx.peerId}")
        }
    }

    private fun finishReceiving(id: String) {
        val tx = synchronized(incoming) { incoming[id] } ?: return
        if (tx.state == TransferState.Completed) return
        tx.state = TransferState.Completed

        val hash = synchronized(tx) { tx.hasher.digest() }
        if (!hash.contentEquals(tx.expectedHash)) {
            Logger.e(TAG, "SHA-256 mismatch: $id")
            File(tx.tempPath).delete()
            synchronized(incoming) { incoming.remove(id) }
            onTransferFailed?.invoke(id, "Hash mismatch — file corrupted")
            return
        }

        val dst = File(tx.finalPath)
        File(tx.tempPath).copyTo(dst, overwrite = true)
        File(tx.tempPath).delete()
        synchronized(incoming) { incoming.remove(id) }

        onTransferCompleted?.invoke(id, dst.absolutePath, false)
        onFileReceived?.invoke(tx.peerId, dst.absolutePath, tx.fileName)
        Logger.i(TAG, "File received: ${tx.fileName} → ${dst.absolutePath}")
    }

    // ── Crypto ────────────────────────────────────────────────────────────────

    private fun encryptChunk(
        plain: ByteArray, key: ByteArray, base: ByteArray, seq: Long
    ): Pair<ByteArray, ByteArray> {
        val nonce  = chunkNonce(base, seq)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
        val out = cipher.doFinal(plain)
        return Pair(out.copyOfRange(0, out.size - GCM_TAG_SIZE),
                    out.copyOfRange(out.size - GCM_TAG_SIZE, out.size))
    }

    private fun decryptChunk(
        ct: ByteArray, tag: ByteArray, key: ByteArray, base: ByteArray, seq: Long
    ): ByteArray? {
        val nonce = chunkNonce(base, seq)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE * 8, nonce))
            cipher.doFinal(ct + tag)
        }.getOrNull()
    }

    // Unique nonce per chunk: XOR the last 8 bytes of baseNonce with seq
    private fun chunkNonce(base: ByteArray, seq: Long): ByteArray {
        val n = base.copyOf()
        for (i in 0..7) n[NONCE_SIZE - 1 - i] =
            (n[NONCE_SIZE - 1 - i].toInt() xor ((seq shr (i * 8)).toInt() and 0xFF)).toByte()
        return n
    }

    private fun computeHash(path: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { inp ->
            val buf = ByteArray(CHUNK_SIZE); var n: Int
            while (inp.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest()
    }

    // ── Speed tracking ────────────────────────────────────────────────────────

    private fun updateSpeed(tx: OutTx) {
        val now = System.currentTimeMillis(); val dt = now - tx.lastTime
        if (dt >= SPEED_PERIOD) { tx.speedBps = (tx.bytesSent - tx.lastBytes) * 1000.0 / dt; tx.lastBytes = tx.bytesSent; tx.lastTime = now }
    }
    private fun updateSpeed(tx: InTx) {
        val now = System.currentTimeMillis(); val dt = now - tx.lastTime
        if (dt >= SPEED_PERIOD) { tx.speedBps = (tx.bytesReceived - tx.lastBytes) * 1000.0 / dt; tx.lastBytes = tx.bytesReceived; tx.lastTime = now }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun sanitize(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(255).ifBlank { "file" }

    private fun safeDownloadPath(name: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.also { it.mkdirs() } ?: context.filesDir
        var f = File(dir, name); var n = 1
        while (f.exists()) {
            val base = name.substringBeforeLast("."); val ext = name.substringAfterLast(".", "")
            f = File(dir, if (ext.isEmpty()) "${base}_$n" else "${base}_$n.$ext"); n++
        }
        return f.absolutePath
    }

    private fun OutTx.toProgress() = TransferProgress(id, peerId, fileName, fileSize, bytesSent, state, true, speedBps, durationMs)
    private fun InTx.toProgress()  = TransferProgress(id, peerId, fileName, fileSize, bytesReceived, state, false, speedBps, durationMs)
    private fun ByteArray.toHex()  = joinToString("") { "%02x".format(it) }
    private fun String.fromHex()   = if (isEmpty()) ByteArray(0) else ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    fun destroy() { scope.cancel() }
}
