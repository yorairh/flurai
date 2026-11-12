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
    val r = toHiragana(reading)

    // Runs of kanji and runs of everything else, in order.
    val runs = mutableListOf<Pair<String, Boolean>>()
    for (c in surface) {
        val kanji = isKanji(c)
        if (runs.isNotEmpty() && runs.last().second == kanji) runs[runs.lastIndex] = runs.last().first + c to kanji
        else runs += c.toString() to kanji
    }

    // Kana of the reading can repeat (かしだし for 貸出し), so a kanji run tries every length until the rest fits.
    fun solve(i: Int, pos: Int): List<RubyPart>? {
        if (i == runs.size) return if (pos == r.length) emptyList() else null
        val (text, kanji) = runs[i]
        if (!kanji) {
            val kana = toHiragana(text)
            if (!r.startsWith(kana, pos)) return null
            return solve(i + 1, pos + kana.length)?.let { listOf(RubyPart(text, null)) + it }
        }
        for (end in pos + 1..r.length) {
            solve(i + 1, end)?.let { return listOf(RubyPart(text, r.substring(pos, end))) + it }
        }
        return null
    }
    return solve(0, 0) ?: listOf(RubyPart(surface, reading))
}

/**
 * Reads the furigana markup the content pipeline writes: a word with kanji is `{base|reading}`, anything
 * else is plain text (`{誰|だれ}が{来|き}ました`). Text without braces is one plain part.
 */
fun parseRuby(markup: String): List<RubyPart> {
    val parts = mutableListOf<RubyPart>()
    val plain = StringBuilder()
    var i = 0
    while (i < markup.length) {
        val c = markup[i]
        val close = if (c == '{') markup.indexOf('}', i) else -1
        val bar = if (close > 0) markup.indexOf('|', i) else -1
        if (close > 0 && bar in i + 1 until close) {
            if (plain.isNotEmpty()) parts += RubyPart(plain.toString(), null).also { plain.clear() }
            parts += RubyPart(markup.substring(i + 1, bar), markup.substring(bar + 1, close))
            i = close + 1
        } else {
            plain.append(c)
            i++
        }
    }
    if (plain.isNotEmpty()) parts += RubyPart(plain.toString(), null)
    return parts
}
