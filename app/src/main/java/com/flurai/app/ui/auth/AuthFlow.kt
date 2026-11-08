package com.flurai.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.auth.MIN_PASSWORD_LENGTH
import com.flurai.app.ui.theme.japanese

@Composable
fun AuthFlow(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.screen != AuthScreen.Welcome) {
        viewModel.show(if (state.screen == AuthScreen.ResetPassword) AuthScreen.SignIn else AuthScreen.Welcome)
    }

    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        AnimatedContent(targetState = state.screen, label = "auth-screen") { screen ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (screen) {
                    AuthScreen.Welcome -> WelcomeContent(
                        onSignUp = { viewModel.show(AuthScreen.SignUp) },
                        onSignIn = { viewModel.show(AuthScreen.SignIn) },
                    )
                    AuthScreen.SignUp -> SignUpContent(state, viewModel)
                    AuthScreen.SignIn -> SignInContent(state, viewModel)
                    AuthScreen.ResetPassword -> ResetContent(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeContent(onSignUp: () -> Unit, onSignIn: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Text(
        "流",
        style = MaterialTheme.typography.displayLarge.japanese().copy(fontSize = 112.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        "FluRai",
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        "Learn Japanese words, grammar and conversation, at your level, a little every day.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onSignUp, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Create an account")
    }
    OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("I already have an account")
    }
}

@Composable
private fun SignUpContent(state: AuthUiState, viewModel: AuthViewModel) {
    Header("はじめまして", "Create your account")
    NameField(state, viewModel)
    EmailField(state, viewModel, imeAction = ImeAction.Next)
    PasswordField(
        state = state,
        viewModel = viewModel,
        supporting = "At least $MIN_PASSWORD_LENGTH characters",
        onDone = viewModel::signUp,
    )
    FormError(state.formError)
    PrimaryButton("Create account", state.busy, viewModel::signUp)
    TextButton(onClick = { viewModel.show(AuthScreen.SignIn) }, modifier = Modifier.fillMaxWidth()) {
        Text("Already have an account? Sign in")
    }
}

@Composable
private fun SignInContent(state: AuthUiState, viewModel: AuthViewModel) {
    Header("おかえり", "Welcome back")
    EmailField(state, viewModel, imeAction = ImeAction.Next)
    PasswordField(state = state, viewModel = viewModel, supporting = null, onDone = viewModel::signIn)
    FormError(state.formError)
    PrimaryButton("Sign in", state.busy, viewModel::signIn)
    TextButton(onClick = { viewModel.show(AuthScreen.ResetPassword) }, modifier = Modifier.fillMaxWidth()) {
        Text("Forgot your password?")
    }
    TextButton(onClick = { viewModel.show(AuthScreen.SignUp) }, modifier = Modifier.fillMaxWidth()) {
        Text("New here? Create an account")
    }
}

@Composable
private fun ResetContent(state: AuthUiState, viewModel: AuthViewModel) {
    Header("パスワード", "Reset your password")
    if (state.resetEmailSent) {
        Text(
            "We sent a reset link to ${state.email.trim()}. Open it, choose a new password, then sign in.",
            style = MaterialTheme.typography.bodyLarge,
        )
        PrimaryButton("Back to sign in", busy = false) { viewModel.show(AuthScreen.SignIn) }
    } else {
        Text(
            "Enter your account's email and we'll send you a link to choose a new password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EmailField(state, viewModel, imeAction = ImeAction.Done, onDone = viewModel::sendReset)
        FormError(state.formError)
        PrimaryButton("Send reset link", state.busy, viewModel::sendReset)
        TextButton(onClick = { viewModel.show(AuthScreen.SignIn) }, modifier = Modifier.fillMaxWidth()) {
            Text("Back to sign in")
        }
    }
}

@Composable
private fun Header(japanese: String, title: String) {
    Spacer(Modifier.height(24.dp))
    Text(japanese, style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NameField(state: AuthUiState, viewModel: AuthViewModel) {
    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::onName,
        label = { Text("Name") },
        singleLine = true,
        isError = state.nameError != null,
        supportingText = state.nameError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmailField(
    state: AuthUiState,
    viewModel: AuthViewModel,
    imeAction: ImeAction,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = state.email,
        onValueChange = viewModel::onEmail,
        label = { Text("Email") },
        singleLine = true,
        isError = state.emailError != null,
        supportingText = state.emailError?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    state: AuthUiState,
    viewModel: AuthViewModel,
    supporting: String?,
    onDone: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPassword,
        label = { Text("Password") },
        singleLine = true,
        isError = state.passwordError != null,
        supportingText = (state.passwordError ?: supporting)?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FormError(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}
