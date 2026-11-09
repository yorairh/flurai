package com.flurai.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class FluRaiApp : Application() {
    /** For work that must outlive a screen, such as backing up progress when the app is closed. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
