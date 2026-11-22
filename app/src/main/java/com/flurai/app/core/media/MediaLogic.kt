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

/** How many of a title's words the learner knows, among the ones found in the dictionary. */
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

/** [wordIds] are the title's listed words; [knownWordIds] the words the learner knows. */
fun showProgress(wordIds: List<Int>, knownWordIds: Set<Int>) = ShowProgress(wordIds.count { it in knownWordIds }, wordIds.size)

// -- making a deck from a title ---------------------------------------------------------------------

/** How many of a title's words go into the deck: enough to cover a share of its text, or a fixed number. */
enum class ImportTarget(val label: String, val coverage: Double? = null, val count: Int? = null) {
    Coverage80("80%", coverage = 0.80),
    Coverage90("90%", coverage = 0.90),
    Coverage95("95%", coverage = 0.95),
    Top200("Top 200", count = 200),
    Top500("Top 500", count = 500),
    Top1000("Top 1,000", count = 1_000),
    All("All"),
}

/** The choices of the "Make a deck" sheet; the defaults are what most learners want. */
data class ImportOptions(
    val target: ImportTarget = ImportTarget.Coverage90,
    /** Leave out words the learner already knows. */
    val skipKnown: Boolean = true,
    /** Leave out words written only in kana (often sound words and names). */
    val skipKanaOnly: Boolean = false,
    /** Leave out words that occur only once in the title. */
    val skipSeenOnce: Boolean = true,
    /** Leave out JLPT words more than one level above the learner's study level (words outside the lists stay). */
    val skipTooHard: Boolean = true,
    /** Story order (as the words first appear) instead of most frequent first. */
    val storyOrder: Boolean = false,
)

/** One word of a title that matched a word of ours, in story order. */
data class TitleWord(
    /** Our word id (built-in or dictionary). */
    val id: Int,
    val occurrences: Int,
    /** The JLPT number of a built-in word, 0 for a dictionary word. */
    val jlpt: Int,
    val kanaOnly: Boolean,
    val known: Boolean,
)

/**
 * The words of a title that go into a deck, in deck order. [words] are the matched words in story order;
 * [totalOccurrences] counts every word of the title, matched or not, so a coverage target means a share
 * of the real text. The target picks the most frequent words first; the filters then leave words out.
 */
fun pickTitleWords(words: List<TitleWord>, totalOccurrences: Int, options: ImportOptions, studyLevel: JlptLevel): List<Int> {
    val byFrequency = words.withIndex().sortedByDescending { it.value.occurrences } // stable: ties keep story order
    val chosen = when {
        options.target.count != null -> byFrequency.take(options.target.count)
        options.target.coverage != null -> {
            val needed = options.target.coverage * totalOccurrences
            var covered = 0L
            byFrequency.takeWhile { w -> (covered < needed).also { covered += w.value.occurrences } }
        }
        else -> byFrequency
    }
    val hardest = studyLevel.ordinal + 1
    val kept = chosen.filter { (_, w) ->
        !(options.skipKnown && w.known) &&
            !(options.skipKanaOnly && w.kanaOnly) &&
            !(options.skipSeenOnce && w.occurrences <= 1) &&
            !(options.skipTooHard && w.jlpt != 0 && (JlptLevel.ofJlpt(w.jlpt)?.ordinal ?: 0) > hardest)
    }
    return (if (options.storyOrder) kept.sortedBy { it.index } else kept).map { it.value.id }.distinct()
}
