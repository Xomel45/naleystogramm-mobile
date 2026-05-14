package com.xomel45.naleystogramm.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Mirrors: src/media/mediaengine.h — PCM audio pipeline for voice calls.
//
// Pipeline: AudioRecord → AES-256-GCM encrypt → UDP send
//           UDP recv → AES-256-GCM decrypt → jitter buffer → AudioTrack
//
// No Opus dependency: uses raw PCM 16-bit 16 kHz mono (640 bytes / 20 ms frame).
// Packet format: [4 seq BE][1 type][12 nonce][4 ctLen BE][ciphertext][16 tag]

class MediaEngine {
    companion object {
        private const val TAG          = "MediaEngine"
        private const val SAMPLE_RATE  = 16_000
        private const val FRAME_MS     = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000   // 320
        private const val FRAME_BYTES  = FRAME_SAMPLES * 2                // 640
        private const val PKT_AUDIO: Byte = 0x01
        private const val PKT_SILENCE: Byte = 0xFF.toByte()
        private const val GCM_NONCE  = 12
        private const val GCM_TAG    = 16
        private const val GCM_TAG_BITS = 128
        private const val MAX_JITTER = 8    // frames in jitter buffer
        private const val UDP_RX_TIMEOUT = 1_000  // ms
    }

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rng    = SecureRandom()

    private var udpSock: DatagramSocket? = null
    private var audioRec: AudioRecord?   = null
    private var audioTrack: AudioTrack?  = null

    @Volatile private var peerAddr: InetAddress? = null
    @Volatile private var peerUdpPort: Int       = 0
    private var mediaKey = ByteArray(0)

    @Volatile private var _inCall = false
    @Volatile private var _muted  = false
    private var _seq = 0

    // Relay
    private var relayAddr: InetAddress? = null
    private var relayPort = 0
    private var myUuidPrefix   = ByteArray(0)
    private var peerUuidPrefix = ByteArray(0)
    private var relayEnabled   = false

    private val jitterBuf = ConcurrentLinkedQueue<ByteArray>()

    var onAudioLevel: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun isInCall()     = _inCall
    fun localUdpPort() = udpSock?.localPort ?: 0
    fun isMuted()      = _muted
    fun setMuted(v: Boolean) { _muted = v }

    fun enableUdpRelay(relayIp: String, relayUdpPort: Int, myUuid: String, peerUuid: String) {
        relayAddr       = InetAddress.getByName(relayIp)
        relayPort       = relayUdpPort
        myUuidPrefix    = myUuid.replace("-", "").toByteArray()
        peerUuidPrefix  = peerUuid.replace("-", "").toByteArray()
        relayEnabled    = true
    }

    // Bind UDP socket and return whether it succeeded.
    // Call before sending CALL_INVITE/CALL_ACCEPT to know localUdpPort().
    fun prepareSocket(): Boolean {
        udpSock?.close()
        udpSock = runCatching { DatagramSocket() }.getOrElse { e ->
            Logger.e(TAG, "prepareSocket failed: ${e.message}")
            onError?.invoke("UDP bind failed"); return false
        }
        return true
    }

    fun setMediaKey(key: ByteArray) { mediaKey = key.copyOf() }

    // Begin audio capture/playback toward peer. Call after prepareSocket() and setMediaKey().
    fun startAudio(peerIp: String, peerUdpPort: Int) {
        val sock = udpSock ?: run { Logger.e(TAG, "startAudio without prepareSocket"); return }
        peerAddr       = InetAddress.getByName(peerIp)
        this.peerUdpPort = peerUdpPort
        _inCall        = true
        _seq           = 0
        jitterBuf.clear()
        startCapture(sock)
        startReceive(sock)
        startPlayback()
        Logger.i(TAG, "Audio started → $peerIp:$peerUdpPort, localPort=${sock.localPort}")
    }

    // Convenience: prepareSocket + setMediaKey + startAudio in one call.
    fun startCall(peerIp: String, peerUdpPort: Int, key: ByteArray): Boolean {
        if (_inCall) return false
        if (key.size != 32) { Logger.e(TAG, "Invalid key size ${key.size}"); return false }
        if (!prepareSocket()) return false
        setMediaKey(key)
        startAudio(peerIp, peerUdpPort)
        return true
    }

    fun endCall() {
        _inCall = false
        audioRec?.stop(); audioRec?.release(); audioRec = null
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        udpSock?.close(); udpSock = null
        jitterBuf.clear()
        mediaKey.fill(0)
        Logger.i(TAG, "Call ended")
    }

    // ── Capture → encrypt → send ──────────────────────────────────────────────

    private fun startCapture(sock: DatagramSocket) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(FRAME_BYTES * 4)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Logger.e(TAG, "AudioRecord init failed"); rec.release(); return
        }
        audioRec = rec
        rec.startRecording()

        scope.launch(Dispatchers.IO) {
            val buf       = ByteArray(FRAME_BYTES)
            var nextLevel = System.currentTimeMillis() + 20L
            while (isActive && _inCall) {
                var offset = 0
                while (offset < FRAME_BYTES && isActive && _inCall) {
                    val n = rec.read(buf, offset, FRAME_BYTES - offset)
                    if (n <= 0) break
                    offset += n
                }
                if (offset == 0) continue

                val now = System.currentTimeMillis()
                if (now >= nextLevel) {
                    nextLevel = now + 20L
                    val rms = computeRms(buf, offset)
                    onAudioLevel?.invoke(rms)
                }

                val pcm  = if (_muted) ByteArray(FRAME_BYTES) else buf.copyOf(offset)
                val type = if (_muted) PKT_SILENCE else PKT_AUDIO
                val pkt  = encryptPacket(pcm, type, _seq++)
                sendUdp(sock, pkt)
            }
        }
    }

    // ── Receive → decrypt → jitter buffer ────────────────────────────────────

    private fun startReceive(sock: DatagramSocket) {
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            while (isActive && _inCall) {
                runCatching {
                    sock.soTimeout = UDP_RX_TIMEOUT
                    sock.receive(pkt)
                    var raw = buf.copyOf(pkt.length)
                    if (relayEnabled) raw = stripRelayHeader(raw) ?: return@runCatching
                    val pcm = decryptPacket(raw) ?: return@runCatching
                    if (jitterBuf.size < MAX_JITTER) jitterBuf.add(pcm)
                }
            }
        }
    }

    // ── Playback from jitter buffer ───────────────────────────────────────────

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf.coerceAtLeast(FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        scope.launch(Dispatchers.IO) {
            val silence = ByteArray(FRAME_BYTES)
            while (isActive && _inCall) {
                val frame = jitterBuf.poll() ?: silence
                track.write(frame, 0, frame.size)
            }
        }
    }

    // ── Packet codec ─────────────────────────────────────────────────────────

    private fun encryptPacket(pcm: ByteArray, type: Byte, seq: Int): ByteArray {
        val nonce = ByteArray(GCM_NONCE).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(mediaKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce))
        val out = cipher.doFinal(pcm)
        val ct  = out.copyOfRange(0, out.size - GCM_TAG)
        val tag = out.copyOfRange(out.size - GCM_TAG, out.size)
        return ByteBuffer.allocate(4 + 1 + GCM_NONCE + 4 + ct.size + GCM_TAG)
            .putInt(seq).put(type).put(nonce).putInt(ct.size).put(ct).put(tag).array()
    }

    private fun decryptPacket(raw: ByteArray): ByteArray? {
        if (raw.size < 4 + 1 + GCM_NONCE + 4 + GCM_TAG) return null
        val bb    = ByteBuffer.wrap(raw)
        bb.getInt()                                     // seq (unused)
        val type  = bb.get()
        if (type == PKT_SILENCE) return ByteArray(FRAME_BYTES)
        val nonce = ByteArray(GCM_NONCE).also { bb.get(it) }
        val ctLen = bb.getInt()
        if (ctLen < 0 || bb.remaining() < ctLen + GCM_TAG) return null
        val ct    = ByteArray(ctLen).also { bb.get(it) }
        val tag   = ByteArray(GCM_TAG).also { bb.get(it) }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(mediaKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ct + tag)
        }.getOrNull()
    }

    private fun sendUdp(sock: DatagramSocket, data: ByteArray) {
        runCatching {
            val (dest, port, payload) = if (relayEnabled) {
                val pkt = peerUuidPrefix + data
                Triple(relayAddr!!, relayPort, pkt)
            } else {
                Triple(peerAddr ?: return, peerUdpPort, data)
            }
            sock.send(DatagramPacket(payload, payload.size, dest, port))
        }
    }

    private fun stripRelayHeader(raw: ByteArray): ByteArray? {
        if (raw.size <= myUuidPrefix.size) return null
        return raw.copyOfRange(myUuidPrefix.size, raw.size)
    }

    private fun computeRms(buf: ByteArray, len: Int): Float {
        var sum = 0L
        val shorts = len / 2
        for (i in 0 until shorts) {
            val s = ByteBuffer.wrap(buf, i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toLong()
            sum += s * s
        }
        if (shorts == 0) return 0f
        return (Math.sqrt(sum.toDouble() / shorts) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    fun destroy() {
        endCall()
        scope.cancel()
    }
}
