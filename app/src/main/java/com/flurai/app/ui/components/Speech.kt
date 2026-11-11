package com.flurai.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.speech.JapaneseSpeaker

/**
 * A small play button for a sentence. A tap plays it at normal speed, a long press plays it slowly.
 * [onSpeak] gets `slow`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SentenceSpeakButton(onSpeak: (slow: Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Play the sentence",
                onLongClickLabel = "Play the sentence slowly",
                onLongClick = { onSpeak(true) },
                onClick = { onSpeak(false) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play the sentence", tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Shown when the phone has no Japanese voice: audio is silent until one is installed. */
@Composable
fun MissingVoiceBanner(speaker: JapaneseSpeaker, text: String = "No Japanese voice on this phone, so audio is silent.") {
    val status by speaker.status.collectAsStateWithLifecycle()
    if (status != JapaneseSpeaker.Status.MissingVoice) return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { runCatching { context.startActivity(speaker.installVoiceIntent()) } }) { Text("Install") }
        }
    }
}
