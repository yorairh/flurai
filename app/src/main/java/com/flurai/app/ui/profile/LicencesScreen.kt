package com.flurai.app.ui.profile

import com.flurai.app.ui.components.BackTopAppBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class Source(val name: String, val usedFor: String, val licence: String, val credit: String)

private val sources = listOf(
    Source(
        "JMdict and KANJIDIC2",
        "Word details, kanji readings and meanings",
        "Creative Commons Attribution-ShareAlike 4.0",
        "This app uses the JMdict/EDICT and KANJIDIC dictionary files. These files are the property of the " +
            "Electronic Dictionary Research and Development Group, and are used in conformance with the " +
            "Group's license (edrdg.org/edrdg/licence.html). Converted to JSON by jmdict-simplified (scriptin).",
    ),
    Source(
        "JLPT word and kanji lists",
        "Which words and kanji belong to N5–N1",
        "Creative Commons Attribution (lists); MIT (decks and data files)",
        "Lists by Jonathan Waller (tanos.co.uk). Vocabulary decks by jamsinclair (open-anki-jlpt-decks); " +
            "kanji levels by davidluzgouveia (kanji-data).",
    ),
    Source(
        "Tatoeba",
        "Example sentences and their translations",
        "Creative Commons Attribution 2.0 France",
        "Sentences from tatoeba.org, including the Tanaka Corpus, by the Tatoeba community.",
    ),
    Source(
        "jiten.moe",
        "Difficulty and vocabulary of anime, dramas, books and games",
        "Creative Commons Attribution-ShareAlike 4.0",
        "Difficulty, word lists and titles of anime, dramas, manga, novels and games come from the public jiten.moe API. " +
            "The data is requested from the device when you open Explore and is not stored or shared by FluRai.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Data sources & licenses") }, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "FluRai is built on open data shared by these projects. Thank you!",
                style = MaterialTheme.typography.bodyMedium,
            )
            sources.forEach { source ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(source.usedFor, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(source.licence, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(source.credit, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
