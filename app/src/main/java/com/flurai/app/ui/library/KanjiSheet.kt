package com.flurai.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.ui.theme.japanese

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanjiSheet(kanji: KanjiEntity, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kanji.literal,
                    style = MaterialTheme.typography.displayLarge.japanese().copy(fontSize = 88.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(kanji.meanings, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append("N${kanji.jlpt} · ${kanji.strokeCount} strokes")
                            kanji.grade?.let { append(" · grade $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (kanji.onyomi.isNotEmpty()) Reading("On'yomi (音)", kanji.onyomi)
            if (kanji.kunyomi.isNotEmpty()) Reading("Kun'yomi (訓)", kanji.kunyomi)
        }
    }
}

@Composable
private fun Reading(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge.japanese(), color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.titleLarge.japanese())
    }
}
