package com.flurai.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.drop
import com.flurai.app.core.activity.StudyClock

/**
 * Wires a reading-style screen to its [StudyClock]: the clock stops while the screen is not in
 * front, and scrolling counts as activity (so reading without tapping still adds study time).
 * Returns the scroll state to give to the page's `verticalScroll`.
 */
@Composable
fun rememberStudyScroll(clock: StudyClock): ScrollState {
    val scroll = rememberScrollState()
    val current = rememberUpdatedState(clock)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { current.value.resume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { current.value.pause() }
    LaunchedEffect(scroll) {
        // One tick per chunk of scrolling, not per pixel.
        snapshotFlow { scroll.value / SCROLL_CHUNK_PX }.drop(1).collect { current.value.tap() }
    }
    return scroll
}

private const val SCROLL_CHUNK_PX = 300
