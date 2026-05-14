package com.xomel45.naleystogramm.service

import android.app.Service

class MessagingService : Service() {
    // TODO: Foreground Service — keeps TCP connections alive in background
    // hosts NetworkManager, handles incoming messages and calls
    override fun onBind(intent: android.content.Intent?) = null
}
