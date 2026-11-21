package com.flurai.app.ui.profile

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import com.flurai.app.core.study.DeckRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen
import com.flurai.app.ui.theme.japanese

private val profileFeatures = emptyList<PlannedFeature>()

@Composable
fun ProfileScreen(
    user: AuthUser,
    profile: StudyProfile,
    onEditProfile: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    TabScreen(
        titleJapanese = "マイページ",
        title = "Me",
        subtitle = "Account, progress and settings",
        features = profileFeatures,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountCard(user, profile, onEditProfile, onSignOut)
                ProgressEntryCard(onOpenProgress)
                com.flurai.app.ui.account.MoveProgressCard()
                SettingsEntryCard(onOpenSettings)
            }
        },
        footer = {
            TextButton(onClick = onOpenLicences, modifier = Modifier.fillMaxWidth()) {
                Text("Data sources & licenses")
            }
        },
    )
}

@Composable
private fun SettingsEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("設定", style = MaterialTheme.typography.headlineSmall.japanese(), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Text("Reminders, streak rules, goals and app version.", style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun ProgressEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("進歩", style = MaterialTheme.typography.headlineSmall.japanese(), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Progress", style = MaterialTheme.typography.titleMedium)
                Text("Your level, what you know, and where to improve.", style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun AccountCard(
    user: AuthUser,
    profile: StudyProfile,
    onEditProfile: () -> Unit,
    onSignOut: () -> Unit,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(user.name.ifBlank { "Learner" }, style = MaterialTheme.typography.titleLarge)
            Text(
                if (user.isGuest) "Guest profile: saved on this phone only, not backed up." else user.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val deckName = profile.studyDeckKey?.let { key ->
                val names: StudyDeckViewModel = hiltViewModel()
                remember(user.uid, key) { names.deckName(user.uid, key) }.collectAsStateWithLifecycle(null).value
            }
            InfoRow("Studying from", if (deckName != null) "$deckName, then ${profile.level.label}" else profile.level.label)
            InfoRow("Daily goal", "${profile.dailyMinutes} minutes")
            InfoRow("New words per day", profile.newCardsPerDay.toString())
            Button(onClick = onEditProfile, modifier = Modifier.fillMaxWidth()) {
                Text("Edit level & goals")
            }
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (user.isGuest) "Create an account or sign in" else "Sign out")
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(if (user.isGuest) "Leave guest mode?" else "Sign out?") },
            text = {
                Text(
                    if (user.isGuest) "Your guest progress stays on this phone. Create an account or sign in, and you can move it over."
                    else "Your settings stay on this phone, and you can sign back in any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(if (user.isGuest) "Continue" else "Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** The learner's decks for "Studying from" a deck: the one chosen (null when it was deleted) and the list to choose from. */
@HiltViewModel
class StudyDeckViewModel @Inject constructor(private val decks: DeckRepository) : ViewModel() {
    fun decks(uid: String): Flow<List<com.flurai.app.core.study.DeckEntity>> = decks.decks(uid)

    fun deckName(uid: String, key: String): Flow<String?> = decks.decks(uid).map { list -> list.firstOrNull { it.key == key }?.name }
}
