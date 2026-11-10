package com.flurai.app.core.reading

/** A piece of a written word; [ruby] is the small kana reading shown above it, null for kana and symbols. */
data class RubyPart(val text: String, val ruby: String?)

private fun isKanji(c: Char) = c in '一'..'鿿' || c == '々' || c == '〆'

/** Katakana to hiragana, so 'コート' and 'こうと' can be compared. */
private fun toHiragana(text: String): String =
    text.map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

/**
 * Splits [surface] (e.g. 食べ物) into parts and puts the right part of [reading] (たべもの) over each
 * kanji run, so the kana inside the word (べ) gets none. When the reading does not fit the spelling,
 * the whole word carries the whole reading, which is less exact but never wrong.
 */
fun rubyParts(surface: String, reading: String): List<RubyPart> {
    if (surface.none(::isKanji)) return listOf(RubyPart(surface, null))
    val whole = listOf(RubyPart(surface, reading))
    val r = toHiragana(reading)

    // Runs of kanji and runs of everything else, in order.
    val runs = mutableListOf<Pair<String, Boolean>>()
    for (c in surface) {
        val kanji = isKanji(c)
        if (runs.isNotEmpty() && runs.last().second == kanji) runs[runs.lastIndex] = runs.last().first + c to kanji
        else runs += c.toString() to kanji
    }

    val parts = mutableListOf<RubyPart>()
    var pos = 0
    for ((i, run) in runs.withIndex()) {
        val (text, kanji) = run
        if (!kanji) {
            val kana = toHiragana(text)
            if (!r.startsWith(kana, pos)) return whole
            pos += kana.length
            parts += RubyPart(text, null)
        } else {
            // A kanji run's reading lasts up to where the next kana run starts, or to the end.
            val end = if (i == runs.lastIndex) r.length else r.indexOf(toHiragana(runs[i + 1].first), pos + 1)
            if (end <= pos) return whole
            parts += RubyPart(text, r.substring(pos, end))
            pos = end
        }
    }
    return if (pos == r.length) parts else whole
}
