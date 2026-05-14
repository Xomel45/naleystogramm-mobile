package com.xomel45.naleystogramm.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

// Mirrors: src/core/identity.h — UUID + displayName, persisted to filesDir/identity.json.
// Public key material lives in KeyProtector (Android Keystore).
object Identity {
    private const val FILE_NAME = "identity.json"

    var uuid: String = ""
        private set
    var displayName: String = ""
        private set

    private lateinit var identityFile: File

    fun init(context: Context) {
        identityFile = File(context.filesDir, FILE_NAME)
        if (identityFile.exists()) {
            runCatching {
                val json = JSONObject(identityFile.readText())
                uuid        = json.optString("uuid", "")
                displayName = json.optString("displayName", "")
            }.onFailure {
                Logger.e("Identity", "Failed to read identity: ${it.message}")
            }
        }
        if (uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString()
            save()
            Logger.i("Identity", "New identity created: $uuid")
        }
    }

    fun setDisplayName(name: String) {
        displayName = name
        save()
    }

    private fun save() {
        runCatching {
            val json = JSONObject()
            json.put("uuid", uuid)
            json.put("displayName", displayName)
            identityFile.writeText(json.toString())
        }.onFailure {
            Logger.e("Identity", "Failed to save identity: ${it.message}")
        }
    }
}
