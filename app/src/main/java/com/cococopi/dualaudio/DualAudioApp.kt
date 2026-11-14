package com.cococopi.dualaudio

import android.app.Application
import com.cococopi.dualaudio.routing.ShizukuManager

class DualAudioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(this)
    }
}
