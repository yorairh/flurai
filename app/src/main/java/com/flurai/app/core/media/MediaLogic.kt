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

/** How a title's most common words match what the learner knows. */
data class MediaCoverage(
    /** Words among the checked ones that the learner knows. */
    val knownWords: Int,
    /** Words checked (the title's most common ones). */
    val checkedWords: Int,
    /** Share of all occurrences of the checked words that the known words account for, 0..1. */
    val occurrenceShare: Double,
) {
    val wordShare: Double get() = if (checkedWords == 0) 0.0 else knownWords.toDouble() / checkedWords
}

/**
 * [known] holds JMdict ids of the words the learner knows. Words outside the JLPT lists the app
 * teaches cannot be known there, so they count as unknown: the figure is a floor, not an exact score.
 */
fun coverage(words: List<DeckWord>, known: Set<Int>): MediaCoverage {
    val total = words.sumOf { it.occurrences.toLong() }
    val knownOccurrences = words.filter { it.wordId in known }.sumOf { it.occurrences.toLong() }
    return MediaCoverage(
        knownWords = words.count { it.wordId in known },
        checkedWords = words.size,
        occurrenceShare = if (total == 0L) 0.0 else knownOccurrences.toDouble() / total,
    )
}

/**
 * Our word ids to learn before watching: the title's common words that are in the app's word
 * lists ([jmdictToWord]) and are not started yet ([started]), most common first.
 */
fun wordsToLearn(words: List<DeckWord>, jmdictToWord: Map<Int, Int>, started: Set<Int>, limit: Int): List<Int> =
    words.sortedByDescending { it.occurrences }
        .mapNotNull { jmdictToWord[it.wordId] }
        .filter { it !in started }
        .distinct()
        .take(limit)
