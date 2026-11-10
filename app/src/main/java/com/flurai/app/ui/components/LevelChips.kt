package com.flurai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flurai.app.core.model.JlptNumbers

/** The N5 … N1 tabs used by Reading, Grammar and Videos. */
@Composable
fun LevelChips(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JlptNumbers.forEach { n ->
            FilterChip(selected = selected == n, onClick = { onSelect(n) }, label = { Text("N$n", maxLines = 1) })
        }
    }
}
