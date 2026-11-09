package com.flurai.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.ui.theme.japanese

/** Shown after sign-up (and for any unverified account) until the email link is opened. */
@Composable
fun VerifyEmailScreen(user: AuthUser, viewModel: VerifyEmailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back from the mail app: check whether the link was opened.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.check(quiet = true) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("確認", style = MaterialTheme.typography.displaySmall.japanese(), color = MaterialTheme.colorScheme.secondary)
        Text("Check your email", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Confirm ${user.email} by opening the link we email you. New accounts get it right after sign-up; otherwise tap 'Send the email' below. Then come back here.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            "Can't find it? Look in spam. If the address is wrong, use a different email.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (state.busy) CircularProgressIndicator()
        Button(onClick = { viewModel.check(quiet = false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("I've confirmed it")
        }
        OutlinedButton(onClick = { viewModel.resend() }, enabled = !state.busy && state.cooldown == 0, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    state.cooldown > 0 && state.sent -> "Email sent. Send again in ${state.cooldown} s"
                    state.cooldown > 0 -> "Send again in ${state.cooldown} s"
                    else -> "Send the email"
                },
            )
        }
        OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Use a different email")
        }
        TextButton(onClick = { viewModel.signOut() }) { Text("Sign out") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this account?") },
            text = { Text("The account for ${user.email} has not been confirmed. It will be deleted so you can sign up again with the right address.") },
            confirmButton = {
                Button(onClick = { confirmDelete = false; viewModel.deleteAccount() }) { Text("Delete and start over") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Offers to move study data that another account left on this phone. Shows nothing when there is
 * none. [compact] trims the text for the onboarding screen.
 */
@Composable
fun MoveProgressCard(modifier: Modifier = Modifier, viewModel: MoveProgressViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val other = state.other ?: return
    var confirm by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Progress from another account", style = MaterialTheme.typography.titleMedium)
            Text(
                "This phone has study data from a different account" +
                    (other.level?.let { " (level $it" } ?: " (") + "${other.cards} flashcards). " +
                    "You can move it to the account you're signed in with now.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.done) {
                Text("Moved. Everything is now on this account.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = { confirm = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Move it to this account")
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Move progress here?") },
            text = {
                Text(
                    "Flashcards, review history, streak days, test results, grammar progress, your level and settings " +
                        "move to this account. Where both accounts have the same card, this account's copy is kept. " +
                        "The other account keeps nothing on this phone.",
                )
            },
            confirmButton = { Button(onClick = { confirm = false; viewModel.move() }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
