package com.flurai.app.ui.settings

import com.flurai.app.ui.components.BackTopAppBar
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.activity.AppSettings
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.reminder.ReminderScheduler

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    profile: StudyProfile,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickTime by rememberSaveable { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setReminderEnabled(granted)
    }

    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Settings") }, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard("Practice reminders") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Daily reminder", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "A nudge when you have not practised yet today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.reminderEnabled,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= 33 && !ReminderScheduler.canPost(context)) {
                                askPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setReminderEnabled(on)
                            }
                        },
                    )
                }
                if (settings.reminderEnabled) {
                    Row(
                        Modifier.fillMaxWidth().clickable { pickTime = true }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Time", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "%02d:%02d".format(settings.reminderHour, settings.reminderMinute),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            SettingsCard("Streak") {
                Text("A day counts toward your streak after", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.StreakChoices.forEach { minutes ->
                        FilterChip(
                            selected = settings.streakMinutes == minutes,
                            onClick = { viewModel.setStreakMinutes(minutes) },
                            label = { Text("$minutes min") },
                        )
                    }
                }
            }

            SettingsCard("Flashcards") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Listening cards", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Hear a word and say what it is. Added for words you have learned.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.listeningCards, onCheckedChange = viewModel::setListeningCards)
                }
            }

            SettingsCard("Study goals") {
                Text("Daily goal: ${profile.dailyMinutes} minutes", style = MaterialTheme.typography.bodyLarge)
                Text("New words per day: ${profile.newCardsPerDay}", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onEditProfile, modifier = Modifier.fillMaxWidth()) { Text("Edit level & goals") }
            }

            SettingsCard("About") {
                Row {
                    Text("Version", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(versionLabel(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (viewModel.isGuest) {
                SettingsCard("Guest profile") {
                    Text(
                        "You are using FluRai without an account, so your progress is saved on this phone only. " +
                            "Create an account on the Me tab to back it up and use it on other phones; you can move this progress over.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SettingsCard("Guest data") {
                    com.flurai.app.ui.account.EraseGuestSection()
                }
            } else {
                val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
                SettingsCard("Cloud backup") {
                    Text(
                        when {
                            syncStatus.running -> "Syncing…"
                            syncStatus.error != null -> syncStatus.error.orEmpty()
                            syncStatus.lastSyncedAt != null -> "Last synced " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(syncStatus.lastSyncedAt)
                            else -> "Your progress is backed up to your account when you leave the app."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { viewModel.syncNow() }, enabled = !syncStatus.running, modifier = Modifier.fillMaxWidth()) {
                        Text("Sync now")
                    }
                }

                SettingsCard("Account") {
                    com.flurai.app.ui.account.DeleteAccountSection()
                }
            }
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(settings.reminderHour, settings.reminderMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = { viewModel.setReminderTime(state.hour, state.minute); pickTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** "0.6.0 (6)", read from the installed package so it always matches the APK. */
@Composable
private fun versionLabel(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("unknown")
    }
}
