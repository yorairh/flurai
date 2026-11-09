package com.flurai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.flurai.app.ui.FluRaiRoot
import com.flurai.app.ui.theme.FluRaiTheme
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.sync.SyncEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sync: SyncEngine
    @Inject lateinit var auth: AuthRepository

    /** Leaving the app is a good moment to back up progress. Runs in the app scope so it can finish. */
    override fun onStop() {
        super.onStop()
        val user = auth.currentUser.value
        if (user != null && user.emailVerified) {
            (application as FluRaiApp).appScope.launch { sync.sync(user.uid) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FluRaiTheme {
                FluRaiRoot()
            }
        }
    }
}
