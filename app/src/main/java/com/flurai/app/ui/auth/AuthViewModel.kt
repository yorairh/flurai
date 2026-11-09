package com.flurai.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.validateEmail
import com.flurai.app.core.auth.validateExistingPassword
import com.flurai.app.core.auth.validateName
import com.flurai.app.core.auth.validateNewPassword
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthScreen { Welcome, SignIn, SignUp, ResetPassword }

data class AuthUiState(
    val screen: AuthScreen = AuthScreen.Welcome,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    /** Error from the server (wrong password, no connection, ...). */
    val formError: String? = null,
    val busy: Boolean = false,
    val resetEmailSent: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** Switches screens, keeping the email (handy when going from sign-in to reset) but clearing errors. */
    fun show(screen: AuthScreen) = _state.update {
        AuthUiState(screen = screen, email = it.email)
    }

    fun continueAsGuest() = auth.continueAsGuest()

    fun onName(value: String) = _state.update { it.copy(name = value, nameError = null, formError = null) }
    fun onEmail(value: String) = _state.update { it.copy(email = value, emailError = null, formError = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, passwordError = null, formError = null) }

    fun signUp() {
        val s = _state.value
        val errors = s.copy(
            nameError = validateName(s.name),
            emailError = validateEmail(s.email),
            passwordError = validateNewPassword(s.password),
        )
        if (errors.hasFieldErrors()) return _state.update { errors }
        submit { auth.signUp(s.name, s.email, s.password) }
    }

    fun signIn() {
        val s = _state.value
        val errors = s.copy(
            emailError = validateEmail(s.email),
            passwordError = validateExistingPassword(s.password),
        )
        if (errors.hasFieldErrors()) return _state.update { errors }
        submit { auth.signIn(s.email, s.password) }
    }

    fun sendReset() {
        val s = _state.value
        val emailError = validateEmail(s.email)
        if (emailError != null) return _state.update { it.copy(emailError = emailError) }
        submit(onSuccess = { it.copy(resetEmailSent = true) }) { auth.sendPasswordReset(s.email) }
    }

    private fun submit(
        onSuccess: (AuthUiState) -> AuthUiState = { it },
        action: suspend () -> Result<Unit>,
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, formError = null) }
        viewModelScope.launch {
            val result = action()
            _state.update { s ->
                result.fold(
                    onSuccess = { onSuccess(s.copy(busy = false)) },
                    onFailure = { s.copy(busy = false, formError = it.message) },
                )
            }
        }
    }

    private fun AuthUiState.hasFieldErrors() = nameError != null || emailError != null || passwordError != null
}
