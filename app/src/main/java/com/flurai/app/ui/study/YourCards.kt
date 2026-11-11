package com.flurai.app.ui.study

import com.flurai.app.core.study.formatInterval
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import java.time.Duration
import java.time.Instant

/** A word's flashcards: their state, when they're due, and suspend/reset actions. */
@Composable
fun YourCards(
    cards: List<CardEntity>,
    onStart: () -> Unit,
    onSuspend: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your cards", style = MaterialTheme.typography.titleMedium)
            if (cards.isEmpty()) {
                Text(
                    "You're not studying this word yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start learning it now") }
                return@Column
            }
            val suspended = cards.any { it.suspended }
            cards.forEach { card ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        when (card.kind) {
                            CardKind.Recognition -> "Japanese → meaning"
                            CardKind.Production -> "Meaning → Japanese"
                            CardKind.Listening -> "Listening"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(cardStatus(card), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (cards.none { it.kind == CardKind.Production }) {
                Text(
                    "The meaning → Japanese and listening cards unlock once you've learned this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSuspend(!suspended) }, modifier = Modifier.weight(1f)) {
                    Text(if (suspended) "Resume" else "Suspend")
                }
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.weight(1f)) { Text("Reset") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset this word?") },
            text = { Text("Its cards start over as new. Your answer history is kept.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** "Suspended", "Learning · due now", "Review · in 12d", "New". */
fun cardStatus(card: CardEntity, now: Instant = Instant.now()): String {
    if (card.suspended) return "Suspended"
    if (card.lastReview == null) return "New"
    val state = when (SrsState.of(card.state)) {
        SrsState.Learning -> "Learning"
        SrsState.Review -> "Review"
        SrsState.Relearning -> "Relearning"
    }
    val untilDue = Duration.between(now, Instant.ofEpochMilli(card.due))
    return if (untilDue.isNegative || untilDue.isZero) "$state · due now" else "$state · in ${formatInterval(untilDue)}"
}
