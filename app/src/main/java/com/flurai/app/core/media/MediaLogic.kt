package com.flurai.app.core.media

import com.flurai.app.core.model.JlptLevel

/**
 * The jiten.moe difficulty (raw 0 to 5) that suits a learner at [level]. Ranges overlap a little
 * so there is always something to pick. Beginners get the easiest titles.
 */
fun difficultyRange(level: JlptLevel): ClosedFloatingPointRange<Double> = when (level) {
    JlptLevel.Beginner, JlptLevel.N5 -> 0.0..1.0
    JlptLevel.N4 -> 0.5..1.5
    JlptLevel.N3 -> 1.5..2.5
    JlptLevel.N2 -> 2.5..3.5
    JlptLevel.N1 -> 3.5..5.0
}

/** How many of a title's words the learner knows, among the ones the app's word lists teach. */
data class ShowProgress(val knownWords: Int, val listedWords: Int) {
    val share: Double get() = if (listedWords == 0) 0.0 else knownWords.toDouble() / listedWords
}

/**
 * Every word of a title, read page by page with [fetch] (given the offset of the page). Stops at
 * the title's total, at an empty page, or after [maxPages] pages, so a wrong total cannot loop.
 */
suspend fun allDeckWords(maxPages: Int = 150, fetch: suspend (offset: Int) -> VocabularyPage): List<DeckWord> {
    val words = ArrayList<DeckWord>()
    repeat(maxPages) {
        val page = fetch(words.size)
        words += page.data.words
        if (page.data.words.isEmpty() || words.size >= page.totalItems) return words
    }
    return words
}

/** Our ids of all of a title's words that are in the app's word lists ([jmdictToWord]), most common first. */
fun listedWordIds(words: List<DeckWord>, jmdictToWord: Map<Int, Int>): List<Int> =
    words.sortedByDescending { it.occurrences }.mapNotNull { jmdictToWord[it.wordId] }.distinct()

/** [wordIds] are the title's listed words; [knownWordIds] the words the learner knows. */
fun showProgress(wordIds: List<Int>, knownWordIds: Set<Int>) = ShowProgress(wordIds.count { it in knownWordIds }, wordIds.size)
