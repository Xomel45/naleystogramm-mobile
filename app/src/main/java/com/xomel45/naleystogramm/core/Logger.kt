package com.xomel45.naleystogramm.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

// Mirrors: src/core/logger.h — file + in-memory ring buffer, shown in LogFragment.
object Logger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private const val TAG = "Naley"
    private const val MAX_ENTRIES = 1000
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val memory = CopyOnWriteArrayList<Entry>()
    private var logFile: File? = null

    fun init(filesDir: File) {
        logFile = File(filesDir, "naleystogramm.log")
    }

    fun log(level: Level, tag: String, message: String) {
        val entry = Entry(level, tag, message)
        if (memory.size >= MAX_ENTRIES) memory.removeAt(0)
        memory.add(entry)

        val line = "${fmt.format(Date(entry.timestamp))} ${level.name} [$tag] $message"
        when (level) {
            Level.DEBUG -> Log.d(TAG, "[$tag] $message")
            Level.INFO  -> Log.i(TAG, "[$tag] $message")
            Level.WARN  -> Log.w(TAG, "[$tag] $message")
            Level.ERROR -> Log.e(TAG, "[$tag] $message")
        }
        try { logFile?.appendText(line + "\n") } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO,  tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN,  tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    fun entries(): List<Entry> = memory.toList()
    fun clear() = memory.clear()
}
