package com.flurai.app.ui.explore

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.video.channelsFor
import com.flurai.app.core.video.grammarQuery
import com.flurai.app.core.video.searchUrl
import com.flurai.app.ui.theme.japanese

private fun open(context: Context, url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(onBack: () -> Unit, viewModel: VideosViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Videos") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 4, 3, 2, 1).forEach { n ->
                    FilterChip(selected = state.jlpt == n, onClick = { viewModel.chooseLevel(n) }, label = { Text("N$n", maxLines = 1) })
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
            Text(
                "These open YouTube in your app or browser. Picks are suggestions, not endorsements.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
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
