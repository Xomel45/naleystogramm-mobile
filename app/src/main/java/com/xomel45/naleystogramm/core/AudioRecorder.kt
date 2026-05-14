package com.xomel45.naleystogramm.core

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Mirrors: src/core/audiorecorder.h — voice message recording to WAV.
// Format: PCM 16-bit, 16 000 Hz, mono.
// Requires RECORD_AUDIO permission.

class AudioRecorder(private val context: Context) {
    companion object {
        private const val TAG          = "AudioRecorder"
        private const val SAMPLE_RATE  = 16_000
        private const val CHANNEL_IN   = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT
        private const val LEVEL_MS     = 50L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recorder: AudioRecord? = null
    private var recording   = false
    private var startTimeMs = 0L

    var onRecorded: ((filePath: String, durationMs: Int) -> Unit)? = null
    var onLevelChanged: ((level: Float) -> Unit)? = null

    fun isRecording() = recording

    fun startRecording() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING).coerceAtLeast(4096)
        val rec    = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_IN, ENCODING, minBuf)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Logger.e(TAG, "AudioRecord init failed")
            rec.release(); return
        }
        recorder    = rec
        recording   = true
        startTimeMs = System.currentTimeMillis()
        val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
        scope.launch { recordLoop(rec, outFile, minBuf) }
    }

    fun stopRecording() { recording = false }

    private suspend fun recordLoop(rec: AudioRecord, outFile: File, minBuf: Int) {
        val raf = RandomAccessFile(outFile, "rw")
        writeWavHeader(raf, 0)
        rec.startRecording()

        var totalBytes = 0
        var nextLevel  = System.currentTimeMillis() + LEVEL_MS
        val shortBuf   = ShortArray(minBuf / 2)

        while (isActive && recording) {
            val read = rec.read(shortBuf, 0, shortBuf.size)
            if (read <= 0) continue

            val bytes = ByteArray(read * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortBuf, 0, read)
            raf.write(bytes)
            totalBytes += bytes.size

            val now = System.currentTimeMillis()
            if (now >= nextLevel) {
                nextLevel = now + LEVEL_MS
                val maxSample = shortBuf.take(read).maxOrNull()?.let { Math.abs(it.toInt()) } ?: 0
                val level = (maxSample.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)
                withContext(Dispatchers.Main) { onLevelChanged?.invoke(level) }
            }
        }

        rec.stop()
        rec.release()
        recorder = null

        finalizeWav(raf, totalBytes)
        raf.close()

        val duration = (System.currentTimeMillis() - startTimeMs).toInt()
        Logger.i(TAG, "Recorded ${outFile.name}: ${totalBytes}B, ${duration}ms")
        withContext(Dispatchers.Main) { onRecorded?.invoke(outFile.absolutePath, duration) }
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Int) {
        raf.write("RIFF".toByteArray())
        raf.write(le32(dataLen + 36))
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.write(le32(16))
        raf.write(le16(1))                    // PCM
        raf.write(le16(1))                    // mono
        raf.write(le32(SAMPLE_RATE))
        raf.write(le32(SAMPLE_RATE * 2))      // byte rate (16-bit mono)
        raf.write(le16(2))                    // block align
        raf.write(le16(16))                   // bits per sample
        raf.write("data".toByteArray())
        raf.write(le32(dataLen))
    }

    private fun finalizeWav(raf: RandomAccessFile, dataLen: Int) {
        raf.seek(4);  raf.write(le32(dataLen + 36))
        raf.seek(40); raf.write(le32(dataLen))
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    fun destroy() { recording = false; scope.cancel() }
}
