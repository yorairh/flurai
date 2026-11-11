package com.flurai.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.content.displayKunyomi
import com.flurai.app.core.content.displayOnyomi
import com.flurai.app.ui.theme.japanese
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class KanjiDetail(val kanji: KanjiEntity, val words: List<WordEntity>)

@HiltViewModel
class KanjiSheetViewModel @Inject constructor(private val content: ContentDao) : ViewModel() {
    suspend fun load(literal: String): KanjiDetail? =
        content.kanji(literal)?.let { KanjiDetail(it, content.wordsWithKanji(literal)) }
}

/** The kanji sheet for [literal]: loads the kanji and the words written with it. Shows nothing for a character that is not a listed kanji. */
@Composable
fun KanjiSheetHost(literal: String, onOpenWord: (Int) -> Unit, onDismiss: () -> Unit, viewModel: KanjiSheetViewModel = hiltViewModel()) {
    val detail: State<KanjiDetail?> = produceState<KanjiDetail?>(null, literal) { value = viewModel.load(literal) }
    detail.value?.let { KanjiSheet(it.kanji, it.words, onOpenWord, onDismiss) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanjiSheet(kanji: KanjiEntity, words: List<WordEntity>, onOpenWord: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
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
            if (kanji.onyomi.isNotEmpty()) Reading("On'yomi (音): from Chinese", displayOnyomi(kanji.onyomi))
            if (kanji.kunyomi.isNotEmpty()) {
                Reading("Kun'yomi (訓): native Japanese", displayKunyomi(kanji.kunyomi))
                if ('.' in kanji.kunyomi) {
                    Text(
                        "Kana in brackets are okurigana: they are written after the kanji, as in 大きい.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (words.isNotEmpty()) {
                Text("Words with ${kanji.literal}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                words.forEach { word ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenWord(word.id) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(word.expression, style = MaterialTheme.typography.titleMedium.japanese(), modifier = Modifier.width(96.dp))
                        Column(Modifier.weight(1f)) {
                            if (word.reading != word.expression) {
                                Text(word.reading, style = MaterialTheme.typography.bodySmall.japanese(), color = MaterialTheme.colorScheme.secondary)
                            }
                            Text(word.meanings, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("N${word.jlpt}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
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
