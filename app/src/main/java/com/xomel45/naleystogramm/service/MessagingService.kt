package com.xomel45.naleystogramm.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xomel45.naleystogramm.core.CallManager
import com.xomel45.naleystogramm.core.FileTransfer
import com.xomel45.naleystogramm.core.Identity
import com.xomel45.naleystogramm.core.Logger
import com.xomel45.naleystogramm.core.MessageEntity
import com.xomel45.naleystogramm.core.NetworkEvent
import com.xomel45.naleystogramm.core.NetworkManager
import com.xomel45.naleystogramm.core.RemoteShellManager
import com.xomel45.naleystogramm.core.Storage
import com.xomel45.naleystogramm.crypto.E2EManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

// Foreground Service that keeps TCP connections alive in the background.
// Hosts NetworkManager, E2EManager, CallManager, FileTransfer, RemoteShellManager.
// serviceType = microphone|dataSync (declared in AndroidManifest).

class MessagingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val network = NetworkManager()

    lateinit var e2e: E2EManager
        private set

    lateinit var callManager: CallManager
        private set

    lateinit var fileTransfer: FileTransfer
        private set

    lateinit var remoteShell: RemoteShellManager
        private set

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MessagingService = this@MessagingService
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Запущен"))

        e2e          = E2EManager(File(filesDir, "keys"))
        callManager  = CallManager(network, e2e)
        fileTransfer = FileTransfer(this, network, e2e)
        remoteShell  = RemoteShellManager(network, e2e)

        e2e.init(Identity.uuid)
        e2e.onSessionEstablished = { peerId ->
            Logger.i("MessagingService", "E2E session established with $peerId")
        }

        network.start()
        observeNetworkEvents()
        Logger.i("MessagingService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d("MessagingService", "onStartCommand: ${intent?.action}")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        remoteShell.destroy()
        fileTransfer.destroy()
        callManager.destroy()
        network.stop()
        serviceScope.cancel()
        Logger.i("MessagingService", "Service destroyed")
    }

    // ── Network event handling ────────────────────────────────────────────────

    private fun observeNetworkEvents() {
        serviceScope.launch {
            network.events.collect { event ->
                when (event) {
                    is NetworkEvent.PeerConnected -> {
                        Logger.i("MessagingService", "Peer connected: ${event.peerName}")
                        updateNotification("Подключено: ${event.peerName}")
                        Storage.contacts.setOnline(event.peerId, true)
                    }
                    is NetworkEvent.PeerDisconnected -> {
                        Logger.i("MessagingService", "Peer disconnected: ${event.peerId}")
                        Storage.contacts.setOnline(event.peerId, false)
                        if (callManager.activePeer() == event.peerId && callManager.isCallActive()) {
                            callManager.endCall()
                        }
                    }
                    is NetworkEvent.MessageReceived -> {
                        handleMessage(event.fromId, event.msg)
                    }
                    is NetworkEvent.ContactNameUpdated -> {
                        Logger.i("MessagingService", "Name updated: ${event.peerId} → ${event.name}")
                        val existing = Storage.contacts.byId(event.peerId)
                        if (existing != null) {
                            Storage.contacts.upsert(existing.copy(displayName = event.name))
                        }
                    }
                    is NetworkEvent.NetworkError -> {
                        Logger.e("MessagingService", event.message)
                    }
                    else -> { /* ConnectionStateChanged, IncomingRequest — handled by UI */ }
                }
            }
        }
    }

    // ── Message dispatch ──────────────────────────────────────────────────────

    private suspend fun handleMessage(fromId: String, msg: JSONObject) {
        val type = msg.optString("type", "")
        Logger.d("MessagingService", "Message from $fromId: type=$type")

        when (type) {
            // ── E2E key exchange ──────────────────────────────────────────────
            "KEY_INIT" -> {
                val ack = e2e.acceptSession(fromId, msg)
                if (ack.length() > 0) network.sendJson(fromId, ack)
            }
            "KEY_ACK" -> {
                // Session already established when we sent KEY_INIT.
                // KEY_ACK carries the responder's bundle for safety-number display only.
                Logger.i("MessagingService", "KEY_ACK from $fromId — E2E session confirmed")
            }

            // ── Chat (E2E encrypted) ──────────────────────────────────────────
            "CHAT" -> {
                val result = e2e.decrypt(fromId, msg)
                val plaintext = result.getOrNull() ?: run {
                    Logger.w("MessagingService", "Decrypt CHAT failed from $fromId")
                    return
                }
                val text = String(plaintext)
                Storage.messages.insert(MessageEntity(
                    peerId     = fromId,
                    content    = text,
                    timestamp  = System.currentTimeMillis(),
                    isOutgoing = false,
                    type       = "text"
                ))
            }

            // ── File transfer ─────────────────────────────────────────────────
            "FILE_OFFER", "FILE_ACCEPT", "FILE_REJECT",
            "FILE_CHUNK", "FILE_COMPLETE", "FILE_CANCEL", "FILE_PAUSE" -> {
                fileTransfer.handleMessage(fromId, msg)
            }

            // ── Voice calls ───────────────────────────────────────────────────
            "CALL_INVITE", "CALL_ACCEPT", "CALL_REJECT", "CALL_END" -> {
                callManager.handleSignaling(fromId, msg)
            }

            // ── Remote shell signaling (plaintext) ────────────────────────────
            "SHELL_REQUEST", "SHELL_CHALLENGE",
            "SHELL_CHALLENGE_RESPONSE", "SHELL_ACCEPT",
            "SHELL_REJECT", "SHELL_KILL" -> {
                remoteShell.handleSignaling(fromId, msg)
            }

            // ── Shell data (E2E encrypted) ────────────────────────────────────
            "SHELL_DATA", "SHELL_INPUT" -> {
                val result = e2e.decrypt(fromId, msg)
                val plaintext = result.getOrNull() ?: run {
                    Logger.w("MessagingService", "Decrypt shell data failed from $fromId")
                    return
                }
                remoteShell.handleDecryptedData(fromId, plaintext)
            }

            else -> {
                Logger.d("MessagingService", "Unknown message type: $type from $fromId")
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Naleystogramm", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Фоновый сервис сообщений" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Naleystogramm")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(mainPendingIntent())
            .build()

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun mainPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val CHANNEL_ID      = "naleystogramm_service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MessagingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessagingService::class.java))
        }
    }
}
