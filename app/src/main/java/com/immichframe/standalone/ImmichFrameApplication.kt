package com.immichframe.standalone

import android.app.Application

class ImmichFrameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsBackupHelper.init(this)
    }
}
