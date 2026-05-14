package com.xomel45.naleystogramm.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

// Mirrors: src/core/updatechecker.h — GitHub Releases API check.

data class UpdateInfo(
    val version: String     = "",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val available: Boolean  = false
)

class UpdateChecker {
    companion object {
        const val GITHUB_OWNER    = "Xomel45"
        const val GITHUB_REPO     = "naleystogramm"
        const val CURRENT_VERSION = "0.7.3"
        private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        private const val COOLDOWN_MS = 6L * 60 * 60 * 1000   // 6 hours
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastCheckedMs = 0L
    var cached = UpdateInfo()

    var onUpdateAvailable: ((UpdateInfo) -> Unit)? = null
    var onNoUpdate: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onCheckStarted: (() -> Unit)? = null

    fun currentVersion() = CURRENT_VERSION

    fun checkInBackground() {
        if (System.currentTimeMillis() - lastCheckedMs < COOLDOWN_MS) return
        scope.launch { doCheck() }
    }

    fun checkNow() { scope.launch { doCheck() } }

    private suspend fun doCheck() {
        withContext(Dispatchers.Main) { onCheckStarted?.invoke() }
        runCatching {
            val obj    = JSONObject(URL(API_URL).readText())
            val remote = obj.getString("tag_name").removePrefix("v").trim()
            val body   = obj.optString("body", "")
            val assets = obj.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url") ?: ""

            lastCheckedMs = System.currentTimeMillis()
            if (isNewer(remote, CURRENT_VERSION)) {
                cached = UpdateInfo(remote, apkUrl, body, true)
                withContext(Dispatchers.Main) { onUpdateAvailable?.invoke(cached) }
            } else {
                cached = UpdateInfo(CURRENT_VERSION, "", "", false)
                withContext(Dispatchers.Main) { onNoUpdate?.invoke(CURRENT_VERSION) }
            }
        }.onFailure { e ->
            Logger.e("UpdateChecker", "Check failed: ${e.message}")
            withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "unknown") }
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    fun destroy() { scope.cancel() }
}
