package com.flurai.app

import android.app.Application
import com.flurai.app.core.content.ContentDatabase
import com.flurai.app.core.study.UserDatabase
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class FluRaiApp : Application() {
    /** For work that must outlive a screen, such as backing up progress when the app is closed. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var contentDatabase: Lazy<ContentDatabase>
    @Inject lateinit var userDatabase: Lazy<UserDatabase>

    override fun onCreate() {
        super.onCreate()
        // Open both databases in the background now, so the first screen that needs them (grammar,
        // flashcards) does not pay for copying and opening them while you wait.
        appScope.launch {
            contentDatabase.get().openHelper.writableDatabase
            userDatabase.get().openHelper.writableDatabase
        }
    }
}
