package com.flurai.app.ui.components

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A progress bar for the app. Material's default track is the secondary container, which is a saturated dark red
 * in the dark theme (an empty bar looks like a full red one); the track here is a neutral surface in both themes.
 */
@Composable
fun StudyProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}
