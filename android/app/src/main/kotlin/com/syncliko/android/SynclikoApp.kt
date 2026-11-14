package com.syncliko.android

import android.app.Application
import com.syncliko.android.sync.Controller
import com.syncliko.android.sync.Watchdog

class SynclikoApp : Application() {
    val controller: Controller by lazy { Controller(this) }

    override fun onCreate() {
        super.onCreate()
        if (controller.mac.value != null) {
            controller.ensureRunning()
            Watchdog.schedule(this)
        }
    }
}
