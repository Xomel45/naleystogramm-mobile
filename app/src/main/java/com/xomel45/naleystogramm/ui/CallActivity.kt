package com.xomel45.naleystogramm.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.core.CallState
import com.xomel45.naleystogramm.service.MessagingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {

    private var messagingService: MessagingService? = null
    private var serviceBound = false
    private var timerJob: Job? = null
    private var callStartMs = 0L
    private var isMuted = false
    private var isSpeaker = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            messagingService = (binder as? MessagingService.LocalBinder)?.getService()
            serviceBound = true
            observeCallState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            messagingService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        val peerId     = intent.getStringExtra(EXTRA_PEER_ID) ?: ""
        val peerName   = intent.getStringExtra(EXTRA_PEER_NAME) ?: peerId
        val isIncoming = intent.getBooleanExtra(EXTRA_INCOMING, false)
        val callId     = intent.getStringExtra(EXTRA_CALL_ID) ?: ""

        findViewById<TextView>(R.id.peer_name).text = peerName

        val btnMute    = findViewById<ImageButton>(R.id.btn_mute)
        val btnEnd     = findViewById<ImageButton>(R.id.btn_end_call)
        val btnSpeaker = findViewById<ImageButton>(R.id.btn_speaker)
        val btnAccept  = findViewById<ImageButton>(R.id.btn_accept)
        val btnReject  = findViewById<ImageButton>(R.id.btn_reject)
        val controls   = findViewById<LinearLayout>(R.id.controls)
        val incoming   = findViewById<LinearLayout>(R.id.incoming_controls)

        if (isIncoming) {
            controls.visibility = android.view.View.GONE
            incoming.visibility = android.view.View.VISIBLE
            setState(getString(R.string.call_state_incoming))
        } else {
            setState(getString(R.string.call_state_calling))
        }

        btnMute.setOnClickListener {
            isMuted = !isMuted
            messagingService?.callManager?.media?.setMuted(isMuted)
            btnMute.alpha = if (isMuted) 0.5f else 1.0f
        }

        btnSpeaker.setOnClickListener {
            isSpeaker = !isSpeaker
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = isSpeaker
            btnSpeaker.alpha = if (isSpeaker) 1.0f else 0.5f
        }

        btnEnd.setOnClickListener { endCall() }

        btnAccept.setOnClickListener {
            incoming.visibility = android.view.View.GONE
            controls.visibility = android.view.View.VISIBLE
            messagingService?.callManager?.acceptCall(callId)
        }

        btnReject.setOnClickListener {
            messagingService?.callManager?.rejectCall(callId)
            finish()
        }

        bindService(
            Intent(this, MessagingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun observeCallState() {
        val svc = messagingService ?: return
        svc.callManager.onStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    CallState.InCall -> {
                        setState(getString(R.string.call_state_in_call))
                        startTimer()
                    }
                    CallState.Ended -> {
                        setState(getString(R.string.call_state_ended))
                        finish()
                    }
                    CallState.Calling -> setState(getString(R.string.call_state_calling))
                    CallState.Ringing -> setState(getString(R.string.call_state_ringing))
                    else -> {}
                }
            }
        }
        svc.callManager.onCallEnded = { _ -> runOnUiThread { finish() } }
    }

    private fun setState(text: String) {
        findViewById<TextView>(R.id.call_state).text = text
    }

    private fun startTimer() {
        callStartMs = System.currentTimeMillis()
        val timerTv = findViewById<TextView>(R.id.call_timer)
        timerTv.visibility = android.view.View.VISIBLE
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - callStartMs
                val s = elapsed / 1000
                timerTv.text = "%d:%02d".format(s / 60, s % 60)
                delay(1000)
            }
        }
    }

    private fun endCall() {
        messagingService?.callManager?.endCall()
        finish()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        if (serviceBound) {
            messagingService?.callManager?.onStateChanged = null
            messagingService?.callManager?.onCallEnded = null
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Keep call alive — don't end on back
    }

    companion object {
        const val EXTRA_PEER_ID   = "peerId"
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_INCOMING  = "incoming"
        const val EXTRA_CALL_ID   = "callId"

        fun startOutgoing(context: Context, peerId: String, peerName: String) {
            context.startActivity(Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_PEER_ID,   peerId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_INCOMING,  false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        fun startIncoming(context: Context, peerId: String, peerName: String, callId: String) {
            context.startActivity(Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_PEER_ID,   peerId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_INCOMING,  true)
                putExtra(EXTRA_CALL_ID,   callId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }
}
