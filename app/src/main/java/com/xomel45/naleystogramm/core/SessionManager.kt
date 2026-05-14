package com.xomel45.naleystogramm.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Mirrors: src/core/sessionmanager.h — user settings via DataStore (replaces session.json).
// Init once from NaleystogrammApp; use Flow-based reads everywhere else.

private val Context.sessionDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "session")

object SessionManager {
    const val DEFAULT_PORT = 47821

    private object Keys {
        val language       = stringPreferencesKey("language")
        val port           = intPreferencesKey("port")
        val manualPort     = intPreferencesKey("manual_port")
        val verboseLogging = booleanPreferencesKey("verbose_logging")
        val demoMode       = booleanPreferencesKey("demo_mode")
    }

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    // ── Reads (reactive) ──────────────────────────────────────────────────────

    val language: Flow<String>
        get() = ctx.sessionDataStore.data.map { it[Keys.language] ?: "ru" }

    val port: Flow<Int>
        get() = ctx.sessionDataStore.data.map { it[Keys.port] ?: DEFAULT_PORT }

    // 0 = automatic (use NAT/UPnP), >0 = manual public port
    val manualPort: Flow<Int>
        get() = ctx.sessionDataStore.data.map { it[Keys.manualPort] ?: 0 }

    val verboseLogging: Flow<Boolean>
        get() = ctx.sessionDataStore.data.map { it[Keys.verboseLogging] ?: false }

    val demoMode: Flow<Boolean>
        get() = ctx.sessionDataStore.data.map { it[Keys.demoMode] ?: false }

    // ── Writes ────────────────────────────────────────────────────────────────

    suspend fun setLanguage(value: String)       { ctx.sessionDataStore.edit { it[Keys.language]       = value } }
    suspend fun setPort(value: Int)              { ctx.sessionDataStore.edit { it[Keys.port]           = value } }
    suspend fun setManualPort(value: Int)        { ctx.sessionDataStore.edit { it[Keys.manualPort]     = value } }
    suspend fun setVerboseLogging(value: Boolean){ ctx.sessionDataStore.edit { it[Keys.verboseLogging] = value } }
    suspend fun setDemoMode(value: Boolean)      { ctx.sessionDataStore.edit { it[Keys.demoMode]       = value } }
}
