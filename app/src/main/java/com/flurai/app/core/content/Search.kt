package com.flurai.app.core.content

/** A query as typed, with its kana reading when it can be romaji ("taberu" → たべる). */
data class SearchQuery(val text: String, val kana: String) {
    companion object {
        fun of(input: String): SearchQuery {
            val text = input.trim()
            val kana = Romaji.toHiragana(text).orEmpty()
            return SearchQuery(text, kana)
        }
    }
}

/** Meaning words of a gloss list: "to eat, to have a meal (formal)" gives "to eat", "to have a meal". */
private fun glosses(meanings: String): List<String> =
    meanings.split(',', ';').map { it.replace(Regex("\\([^)]*\\)"), "").trim().lowercase() }.filter { it.isNotEmpty() }

private fun containsWord(text: String, word: String): Boolean =
    Regex("(^|[^a-z])" + Regex.escape(word) + "($|[^a-z])").containsMatchIn(text)

/**
 * How well a word answers [query]: 0 is the best, null is no match. Order: the word itself (written
 * form or reading), a meaning that is exactly the query, the start of the word, a meaning containing the
 * query as a whole word, the start of a meaning, then any part of the word. Ties go to easier levels in the list.
 */
fun rankWord(word: WordEntity, query: SearchQuery): Int? {
    val q = query.text.lowercase()
    val kana = query.kana
    if (q.isEmpty()) return null
    val forms = listOf(word.expression, word.reading) + word.otherForms.orEmpty().split("; ").filter { it.isNotBlank() }
    val glossList = glosses(word.meanings)
    return when {
        forms.any { it == query.text || (kana.isNotEmpty() && it == kana) } -> 0
        glossList.any { it == q || it == "to $q" } -> 1
        forms.any { it.startsWith(query.text) || (kana.isNotEmpty() && it.startsWith(kana)) } -> 2
        glossList.any { containsWord(it, q) } -> 3
        glossList.any { it.startsWith(q) } -> 4
        forms.any { it.contains(query.text) || (kana.isNotEmpty() && it.contains(kana)) } -> 5
        word.meanings.lowercase().contains(q) -> 6
        else -> null
    }
}

/** The best [limit] words for [query]: by rank, then easier level first, then list order. */
fun rankWords(candidates: List<WordEntity>, query: SearchQuery, limit: Int = 100): List<WordEntity> =
    candidates.mapNotNull { word -> rankWord(word, query)?.let { it to word } }
        .sortedWith(compareBy<Pair<Int, WordEntity>> { it.first }.thenByDescending { it.second.jlpt }.thenBy { it.second.id })
        .take(limit)
        .map { it.second }

/** Kun'yomi without the okurigana dot and the prefix/suffix hyphen: "おお.きい" gives "おおきい". */
fun plainReading(reading: String): String = reading.replace(".", "").replace("-", "")

/** Same idea for kanji: the character itself, a meaning, or a reading (on'yomi in katakana, kun'yomi in hiragana). */
fun rankKanji(kanji: KanjiEntity, query: SearchQuery): Int? {
    val q = query.text.lowercase()
    if (q.isEmpty()) return null
    val kana = query.kana
    val kata = Romaji.toKatakana(kana)
    val readings = (kanji.onyomi.split('、') + kanji.kunyomi.split('、')).map { plainReading(it) }.filter { it.isNotEmpty() }
    val glossList = glosses(kanji.meanings)
    return when {
        kanji.literal == query.text -> 0
        glossList.any { it == q } -> 1
        kana.isNotEmpty() && readings.any { it == kana || it == kata } -> 1
        glossList.any { containsWord(it, q) } -> 2
        kana.isNotEmpty() && readings.any { it.startsWith(kana) || it.startsWith(kata) } -> 3
        else -> null
    }
}

fun rankKanjiList(candidates: List<KanjiEntity>, query: SearchQuery, limit: Int = 80): List<KanjiEntity> =
    candidates.mapNotNull { k -> rankKanji(k, query)?.let { it to k } }
        .sortedWith(compareBy<Pair<Int, KanjiEntity>> { it.first }.thenByDescending { it.second.jlpt }.thenBy { it.second.frequency ?: Int.MAX_VALUE })
        .take(limit)
        .map { it.second }

/** How a kanji's kun'yomi reads in the sheet: "おお.きい" becomes "おお(きい)" (the kana in brackets follow the kanji). */
fun displayKunyomi(kunyomi: String): String =
    kunyomi.split('、').filter { it.isNotBlank() }.joinToString("・") { reading ->
        val dot = reading.indexOf('.')
        if (dot < 0) reading else reading.substring(0, dot) + "(" + reading.substring(dot + 1) + ")"
    }

fun displayOnyomi(onyomi: String): String = onyomi.split('、').filter { it.isNotBlank() }.joinToString("・")
