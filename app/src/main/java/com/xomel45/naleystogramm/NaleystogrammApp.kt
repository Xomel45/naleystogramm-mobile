package com.xomel45.naleystogramm

import android.app.Application
import com.xomel45.naleystogramm.core.Identity
import com.xomel45.naleystogramm.core.Logger
import com.xomel45.naleystogramm.core.SessionManager
import com.xomel45.naleystogramm.core.Storage

class NaleystogrammApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.init(filesDir)
        SessionManager.init(this)
        Identity.init(this)
        Storage.init(this)
        Logger.i("App", "Naleystogramm 0.7.3 started, uuid=${Identity.uuid}")
    }
}
