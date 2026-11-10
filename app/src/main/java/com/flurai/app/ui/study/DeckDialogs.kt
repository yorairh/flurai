package com.flurai.app.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.flurai.app.core.study.DeckEntity
import com.flurai.app.core.study.MAX_DECK_NAME
import com.flurai.app.core.study.cleanDeckName

/** Asks for a deck name (new deck, or rename). */
@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_DECK_NAME) },
                singleLine = true,
                label = { Text("Deck name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = cleanDeckName(text) != null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Ticks the decks a word belongs to, and can make a new deck for it. */
@Composable
fun DeckPickerDialog(
    decks: List<DeckEntity>,
    selected: Set<Long>,
    onToggle: (deckId: Long, inDeck: Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("New deck", "", "Create and add", onDismiss = { creating = false }) {
            creating = false
            onCreate(it)
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to deck") },
        text = {
            Column {
                if (decks.isEmpty()) {
                    Text(
                        "You have no decks yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                decks.forEach { deck ->
                    val inDeck = deck.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(deck.id, !inDeck) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = inDeck, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(deck.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                TextButton(onClick = { creating = true }) { Text("New deck…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
