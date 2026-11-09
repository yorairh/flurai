package com.flurai.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.account.AccountMover
import com.flurai.app.core.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeleteAccountState(val busy: Boolean = false, val error: String? = null)

@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val mover: AccountMover,
    private val sync: com.flurai.app.core.sync.SyncEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = _state

    /**
     * Checks the password first (Firebase needs a recent sign-in to delete), then removes this
     * account's data from the phone, then the account itself. Once it is gone the app shows sign-in.
     */
    fun delete(password: String) {
        val uid = auth.currentUser.value?.uid ?: return
        _state.update { DeleteAccountState(busy = true) }
        viewModelScope.launch {
            val checked = auth.reauthenticate(password)
            if (checked.isFailure) {
                _state.update { DeleteAccountState(error = checked.exceptionOrNull()?.message) }
                return@launch
            }
            try {
                sync.deleteRemote(uid)
            } catch (e: Exception) {
                _state.update { DeleteAccountState(error = "Could not delete your cloud backup. Check your connection and try again.") }
                return@launch
            }
            mover.wipe(uid)
            val deleted = auth.deleteAccount()
            _state.update { DeleteAccountState(error = deleted.exceptionOrNull()?.message) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

/** The "Delete account" button and its two-step confirmation, for the settings screen. */
@Composable
fun DeleteAccountSection(viewModel: DeleteAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(0) } // 0 closed, 1 warning, 2 password
    var password by remember { mutableStateOf("") }

    OutlinedButton(
        onClick = { step = 1 },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Delete account") }

    when (step) {
        1 -> AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This permanently deletes your FluRai account and everything it holds on this phone: flashcards, " +
                        "review history, streaks, test results, grammar progress, your channels, level and settings. " +
                        "It cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { step = 2 },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Keep my account") } },
        )
        2 -> AlertDialog(
            onDismissRequest = { if (!state.busy) { step = 0; password = ""; viewModel.clearError() } },
            title = { Text("Enter your password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("To confirm it's you, enter your password. Your account is then deleted.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; viewModel.clearError() },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = state.error != null,
                        supportingText = state.error?.let { { Text(it) } },
                    )
                    if (state.busy) CircularProgressIndicator()
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.delete(password) },
                    enabled = password.isNotEmpty() && !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton(onClick = { step = 0; password = ""; viewModel.clearError() }, enabled = !state.busy) { Text("Cancel") }
            },
        )
    }
}
