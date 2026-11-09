package com.flurai.app.ui.explore

import com.flurai.app.core.model.JlptNumbers
import com.flurai.app.ui.components.BackTopAppBar
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.video.channelsFor
import com.flurai.app.core.video.featuredFor
import com.flurai.app.core.video.grammarQuery
import com.flurai.app.core.video.searchUrl
import com.flurai.app.ui.theme.japanese

private fun open(context: Context, url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(onBack: () -> Unit, viewModel: VideosViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Videos") }, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JlptNumbers.forEach { n ->
                    FilterChip(selected = state.jlpt == n, onClick = { viewModel.chooseLevel(n) }, label = { Text("N$n", maxLines = 1) })
                }
            }

            val featured = featuredFor(state.jlpt)
            if (featured.isNotEmpty()) {
                Text("Start here: simple lessons", style = MaterialTheme.typography.titleMedium)
                featured.forEach { video ->
                    LinkCard(video.title, "${video.by}\n${video.note}") { open(context, video.url) }
                }
            }

            Text("For the grammar you struggle with", style = MaterialTheme.typography.titleMedium)
            if (state.weakGrammar.isEmpty()) {
                Text(
                    "Practise some grammar and the points you miss most will show up here, each with a video search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.weakGrammar.forEach { point ->
                    LinkCard(point.pattern, "Find videos explaining this", japanese = true) {
                        open(context, searchUrl(grammarQuery(point.pattern)))
                    }
                }
            }

            Text("Channels for N${state.jlpt}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            channelsFor(state.jlpt).forEach { channel ->
                LinkCard(channel.name, channel.focus) { open(context, searchUrl(channel.query)) }
            }
            val mine = state.custom.filter { it.level == state.jlpt }
            if (mine.isNotEmpty()) {
                Text("Your channels", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                mine.forEach { channel ->
                    Card(
                        onClick = { open(context, channel.url) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, style = MaterialTheme.typography.titleMedium)
                                Text(channel.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = { viewModel.removeChannel(channel.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${channel.name}")
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Add a channel or playlist")
            }
            Text(
                "These open YouTube in your app or browser. Picks are suggestions, not endorsements.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (adding) {
        AddChannelDialog(
            initialLevel = state.jlpt,
            onAdd = { name, link, level -> viewModel.addChannel(name, link, level).also { ok -> if (ok) viewModel.chooseLevel(level) } },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun AddChannelDialog(initialLevel: Int, onAdd: (String, String, Int) -> Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var level by remember { mutableIntStateOf(initialLevel) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a channel or playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = false }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    link, { link = it; error = false }, label = { Text("Link") }, singleLine = true,
                    isError = error, supportingText = if (error) ({ Text("Enter a name and a valid link") }) else null,
                )
                Text("Level", style = MaterialTheme.typography.labelLarge)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    JlptNumbers.forEach { n ->
                        FilterChip(selected = level == n, onClick = { level = n }, label = { Text("N$n", maxLines = 1) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (onAdd(name, link, level)) onDismiss() else error = true }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LinkCard(title: String, body: String, japanese: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = if (japanese) MaterialTheme.typography.titleMedium.japanese() else MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
