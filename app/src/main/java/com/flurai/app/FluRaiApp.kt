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
    @Inject lateinit var dictionaryDatabase: Lazy<com.flurai.app.core.dictionary.DictionaryDatabase>
    @Inject lateinit var wordMerger: Lazy<com.flurai.app.core.dictionary.WordMerger>

    override fun onCreate() {
        super.onCreate()
        // Open both databases in the background now, so the first screen that needs them (grammar,
        // flashcards) does not pay for copying and opening them while you wait.
        appScope.launch {
            contentDatabase.get().openHelper.writableDatabase
            userDatabase.get().openHelper.writableDatabase
            // A content update may have made a studied dictionary word a built-in one.
            runCatching { wordMerger.get().merge() }
            // The dictionary is large: copy it out of the app now rather than at the first search.
            dictionaryDatabase.get().openHelper.writableDatabase
        }
    }
}
