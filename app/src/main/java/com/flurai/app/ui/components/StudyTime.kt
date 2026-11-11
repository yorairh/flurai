package com.flurai.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.flurai.app.core.activity.StudyClock
import kotlinx.coroutines.flow.drop

/**
 * Ties a study screen's clock to the screen: it stops while the screen is not in front (the app in
 * the background, or another screen on top) and when the screen closes, and restarts on return.
 * Every study screen calls this once, so time away is never counted.
 */
@Composable
fun StudyClockEffects(onResume: () -> Unit, onPause: () -> Unit) {
    val resume = rememberUpdatedState(onResume)
    val pause = rememberUpdatedState(onPause)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume.value() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pause.value() }
    DisposableEffect(Unit) { onDispose { pause.value() } }
}

/**
 * For reading-style screens: wires [clock] with [StudyClockEffects] and counts scrolling as
 * activity (so reading without tapping still adds study time). Returns the scroll state to give
 * to the page's `verticalScroll`.
 */
@Composable
fun rememberStudyScroll(clock: StudyClock): ScrollState {
    val scroll = rememberScrollState()
    StudyClockEffects(onResume = clock::resume, onPause = clock::pause)
    LaunchedEffect(scroll, clock) {
        // One tick per chunk of scrolling, not per pixel.
        snapshotFlow { scroll.value / SCROLL_CHUNK_PX }.drop(1).collect { clock.tap() }
    }
    return scroll
}

private const val SCROLL_CHUNK_PX = 300
