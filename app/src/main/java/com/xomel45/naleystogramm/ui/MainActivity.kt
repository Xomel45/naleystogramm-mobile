package com.xomel45.naleystogramm.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.xomel45.naleystogramm.R
import com.xomel45.naleystogramm.service.MessagingService

class MainActivity : AppCompatActivity() {

    private var messagingService: MessagingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? MessagingService.LocalBinder)?.getService()
            messagingService = svc
            serviceBound = true
            svc?.incomingRequestListener = { peerId, peerName, peerIp ->
                runOnUiThread {
                    IncomingDialog.newInstance(peerId, peerName, peerIp)
                        .show(supportFragmentManager, "incoming_$peerId")
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            messagingService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController

        findViewById<BottomNavigationView>(R.id.bottom_nav)
            .setupWithNavController(navController)

        MessagingService.start(this)
        bindService(
            Intent(this, MessagingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    fun getMessagingService(): MessagingService? = messagingService
}
