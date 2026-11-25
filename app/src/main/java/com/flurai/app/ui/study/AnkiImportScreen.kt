package com.flurai.app.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.anki.AnkiImportOptions
import com.flurai.app.core.anki.AnkiImportResult
import com.flurai.app.core.anki.AnkiPlan
import com.flurai.app.core.anki.FieldRoles
import com.flurai.app.core.anki.NoteTypePlan
import com.flurai.app.core.anki.TemplateKind
import com.flurai.app.core.anki.noteWord
import com.flurai.app.ui.components.BackTopAppBar
import com.flurai.app.ui.theme.japanese

/** Import an Anki deck: read it, confirm what comes over, import, and say what happened. */
@Composable
fun AnkiImportScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: AnkiImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Import from Anki") }, onBack = onBack)
        when (val s = state) {
            AnkiImportState.Reading -> Busy("Reading the Anki file…", null)
            is AnkiImportState.Importing -> Busy("Importing… ${s.done} of ${s.total} notes", if (s.total == 0) null else s.done.toFloat() / s.total)
            is AnkiImportState.Failed -> Message(s.message, "Back", onBack)
            is AnkiImportState.Confirm -> Confirm(s.plan, s.options, viewModel)
            is AnkiImportState.Done -> Done(s.result, onDone)
        }
    }
}

@Composable
private fun Busy(text: String, progress: Float?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress == null) CircularProgressIndicator() else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(text, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Message(text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center)
        Button(onClick = onClick, modifier = Modifier.padding(top = 16.dp)) { Text(button) }
    }
}

@Composable
private fun Confirm(plan: AnkiPlan, options: AnkiImportOptions, viewModel: AnkiImportViewModel) {
    val c = plan.collection
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${c.notes.size} notes in ${plan.deckCount} ${if (plan.deckCount == 1) "deck" else "decks"}" +
                    if (plan.studiedCards > 0) " · ${plan.studiedCards} cards studied" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Each note becomes a word in a FluRai deck. Words the dictionary knows use its meanings and readings; the rest are kept as your own words. Pictures and sounds are not imported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.noteTypes.forEach { NoteTypeCard(it, options, viewModel) }

            if (plan.deckCount > plan.topDeckCount) {
                Text("Decks", style = MaterialTheme.typography.titleMedium)
                RadioRow("One deck" + if (plan.topDeckCount > 1) " per top-level deck (${plan.topDeckCount})" else "", !options.perSubdeck) { viewModel.setOptions { it.copy(perSubdeck = false) } }
                RadioRow("One deck per Anki deck and subdeck (${plan.deckCount})", options.perSubdeck) { viewModel.setOptions { it.copy(perSubdeck = true) } }
            }
            if (plan.studiedCards > 0) {
                Text("Progress", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Keep my Anki progress (${plan.studiedCards} cards)", options.keepProgress) { on -> viewModel.setOptions { it.copy(keepProgress = on) } }
                if (options.keepProgress && plan.overdueCards > 0) {
                    Text(
                        "${plan.overdueCards} of them are already due. Spreading them out keeps your first days from being one huge review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow("Spread them over the next ${if (plan.overdueCards > 700) "two weeks" else "week"}", options.spreadOverdue) { on -> viewModel.setOptions { it.copy(spreadOverdue = on) } }
                }
                Text(
                    "Words you have already answered in FluRai keep their FluRai progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val any = options.noteTypes.values.any { it.include && it.roles.expression != null }
        Button(onClick = viewModel::import, enabled = any, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(if (any) "Import" else "Choose a note type to import")
        }
    }
}

@Composable
private fun NoteTypeCard(plan: NoteTypePlan, options: AnkiImportOptions, viewModel: AnkiImportViewModel) {
    val choice = options.noteTypes.getValue(plan.type.id)
    val fields = plan.type.fields.sortedBy { it.ord }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.type.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${plan.noteCount} notes" + if (plan.type.cloze) " · cloze notes are not imported" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = choice.include, enabled = !plan.type.cloze, onCheckedChange = { on -> viewModel.setNoteType(plan.type.id) { it.copy(include = on) } })
            }
            if (!choice.include) return@Column

            Text("Fields", style = MaterialTheme.typography.labelLarge)
            val names = listOf("None") + fields.map { it.name }
            fun pick(current: Int?) = fields.indexOfFirst { it.ord == current } + 1
            fun ord(index: Int) = if (index == 0) null else fields[index - 1].ord
            MenuRow("Word", names, pick(choice.roles.expression)) { i -> viewModel.setNoteType(plan.type.id) { it.copy(roles = it.roles.copy(expression = ord(i))) } }
            MenuRow("Reading", names, pick(choice.roles.reading)) { i -> viewModel.setNoteType(plan.type.id) { it.copy(roles = it.roles.copy(reading = ord(i))) } }
            MenuRow("Meaning", names, pick(choice.roles.meaning)) { i -> viewModel.setNoteType(plan.type.id) { it.copy(roles = it.roles.copy(meaning = ord(i))) } }
            Preview(plan, choice.roles)

            if (plan.type.templates.isNotEmpty()) {
                HorizontalDivider()
                Text("Cards", style = MaterialTheme.typography.labelLarge)
                val kinds = TemplateKind.entries
                plan.type.templates.sortedBy { it.ord }.forEach { t ->
                    MenuRow(t.name, kinds.map { it.label }, kinds.indexOf(choice.kinds[t.ord] ?: TemplateKind.Ignore)) { i ->
                        viewModel.setNoteType(plan.type.id) { it.copy(kinds = it.kinds + (t.ord to kinds[i])) }
                    }
                }
            }
        }
    }
}

/** The first notes as they will come over, so a wrong field is easy to see. */
@Composable
private fun Preview(plan: NoteTypePlan, roles: FieldRoles) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        plan.samples.forEach { note ->
            val w = noteWord(note, roles)
            Text(
                if (w == null) "(no Japanese word: left out)" else listOf(w.expression, w.reading.takeIf { it != w.expression }, w.meaning).filter { !it.isNullOrBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.japanese(),
                maxLines = 2,
                color = if (w == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MenuRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options.getOrElse(selected) { options.first() }, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(i) })
                }
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Done(result: AnkiImportResult, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Imported", style = MaterialTheme.typography.headlineSmall)
        result.decks.forEach { d ->
            Text(
                if (d.isNew) "Made the deck \"${d.name}\" with ${d.total} words."
                else "Added ${d.added} words to \"${d.name}\" (${d.total} in all).",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val parts = listOfNotNull(
            "${result.builtInWords} JLPT words".takeIf { result.builtInWords > 0 },
            "${result.dictionaryWords} from the dictionary".takeIf { result.dictionaryWords > 0 },
            "${result.customWords} of your own, marked \"Your word\"".takeIf { result.customWords > 0 },
        )
        if (parts.isNotEmpty()) Text("Words: " + parts.joinToString(", ") + ".", style = MaterialTheme.typography.bodyMedium)
        if (result.cardsWithProgress > 0) Text("${result.cardsWithProgress} cards brought their Anki progress.", style = MaterialTheme.typography.bodyMedium)
        if (result.keptCards > 0) Text("${result.keptCards} cards you had answered in FluRai kept their FluRai progress.", style = MaterialTheme.typography.bodyMedium)
        if (result.skippedNotes > 0) Text("${result.skippedNotes} notes had no Japanese word and were left out.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "The words come up as new words when you study the deck. To take your daily new words from it, choose it in Me → Edit level & goals.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Go to my decks") }
    }
}
