package com.flurai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flurai.app.core.model.JlptNumbers

/** The N5 … N1 choice used by Reading, Grammar and Videos. Wraps onto a second line where it is narrow (a dialog). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LevelChips(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JlptNumbers.forEach { n ->
            FilterChip(selected = selected == n, onClick = { onSelect(n) }, label = { Text("N$n", maxLines = 1) })
        }
    }
}
