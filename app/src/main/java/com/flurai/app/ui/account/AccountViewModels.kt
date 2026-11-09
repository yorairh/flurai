package com.flurai.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.account.AccountMover
import com.flurai.app.core.account.OtherProgress
import com.flurai.app.core.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VerifyEmailState(
    val busy: Boolean = false,
    val sent: Boolean = false,
    val message: String? = null,
    /** Seconds until another email may be requested (Firebase rate-limits them). */
    val cooldown: Int = 0,
)

@HiltViewModel
class VerifyEmailViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(VerifyEmailState())
    val state: StateFlow<VerifyEmailState> = _state

    /** Reloads the account; once it is verified the app moves on by itself. */
    fun check(quiet: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = !quiet, message = null) }
        viewModelScope.launch {
            val result = auth.refresh()
            val verified = auth.currentUser.value?.emailVerified == true
            _state.update {
                it.copy(
                    busy = false,
                    message = when {
                        quiet || verified -> null
                        result.isFailure -> result.exceptionOrNull()?.message
                        else -> "Not confirmed yet. Open the link in the email first."
                    },
                )
            }
        }
    }

    fun resend() {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = auth.sendVerificationEmail()
            _state.update { it.copy(busy = false, sent = result.isSuccess, message = result.exceptionOrNull()?.message) }
            // Either way, wait before the next try: a refusal also means Firebase wants a pause.
            for (left in RESEND_COOLDOWN_SECONDS downTo 0) {
                _state.update { it.copy(cooldown = left) }
                if (left > 0) kotlinx.coroutines.delay(1_000)
            }
        }
    }

    fun deleteAccount() {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = auth.deleteUnverifiedAccount()
            _state.update { it.copy(busy = false, message = result.exceptionOrNull()?.message) }
        }
    }

    fun signOut() = auth.signOut()

    private companion object {
        const val RESEND_COOLDOWN_SECONDS = 60
    }
}

data class MoveProgressState(val other: OtherProgress? = null, val busy: Boolean = false, val done: Boolean = false)

@HiltViewModel
class MoveProgressViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val mover: AccountMover,
    private val sync: com.flurai.app.core.sync.SyncEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(MoveProgressState())
    val state: StateFlow<MoveProgressState> = _state

    init {
        viewModelScope.launch {
            val uid = auth.currentUser.value?.uid ?: return@launch
            _state.update { it.copy(other = mover.otherProgress(uid).firstOrNull()) }
        }
    }

    fun move() {
        val uid = auth.currentUser.value?.uid ?: return
        val from = _state.value.other?.uid ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            mover.move(from, uid)
            sync.markAllDirty(uid)
            sync.sync(uid)
            _state.update { it.copy(busy = false, done = true) }
        }
    }
}
