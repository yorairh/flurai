package com.flurai.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An Anki file another app handed to FluRai ("Open with" or "Share"), waiting for the app shell to open
 * its import once the learner is signed in. Only content URIs are taken: the import just reads the file.
 */
object IncomingAnkiFile {
    val uri = MutableStateFlow<String?>(null)

    fun take(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri?.scheme == "content") this.uri.value = uri.toString()
    }
}
