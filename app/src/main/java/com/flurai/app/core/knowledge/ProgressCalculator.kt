package com.flurai.app.core.knowledge

import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.grammar.GrammarStatus
import com.flurai.app.core.grammar.grammarStatus
import com.flurai.app.core.srs.FsrsScheduler
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyDay
import com.flurai.app.core.study.toSrs
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

const val FORECAST_DAYS = 7
const val MAX_WEAK_WORDS = 8

/** The static study content the numbers are measured against. */
class Catalog(
    val words: Map<Int, WordEntity>,
    /** Kanji literal → JLPT number. */
    val kanjiLevels: Map<String, Int>,
    /** Grammar point id → JLPT number. */
    val grammarLevels: Map<Int, Int> = emptyMap(),
) {
    val wordsPerLevel: Map<Int, Int> = words.values.groupingBy { it.jlpt }.eachCount()
    val kanjiPerLevel: Map<Int, Int> = kanjiLevels.values.groupingBy { it }.eachCount()
    val grammarPerLevel: Map<Int, Int> = grammarLevels.values.groupingBy { it }.eachCount()
}

data class LevelProgress(
    val jlpt: Int,
    val total: Int,
    val mature: Int,
    val known: Int,
    val learning: Int,
    /** Grammar points of the level, and how many of them are known (see `grammarStatus`). */
    val grammarTotal: Int,
    val grammarKnown: Int,
    /** The level's checkpoint test was passed. */
    val checkpointPassed: Boolean,
) {
    val knownCount: Int get() = known + mature
    val fraction: Double get() = if (total == 0) 0.0 else knownCount.toDouble() / total
    val grammarFraction: Double get() = if (grammarTotal == 0) 0.0 else grammarKnown.toDouble() / grammarTotal
}

/** Kanji of a level that appear in at least one known word. */
data class KanjiProgress(val jlpt: Int, val total: Int, val known: Int)

/** A word the learner has forgotten before. [retrievability] is the chance of recalling it right now. */
data class WeakWord(val word: WordEntity, val lapses: Int, val retrievability: Double?)

data class ProgressSnapshot(
    /** N5 first. */
    val vocab: List<LevelProgress>,
    val kanji: List<KanjiProgress>,
    val estimate: LevelEstimate,
    val weak: List<WeakWord>,
    /** Cards due on each of the next [FORECAST_DAYS] study days; day 0 also holds everything overdue. */
    val forecast: List<Int>,
    val studiedWords: Int,
    val knownWords: Int,
)

private val scheduler = FsrsScheduler(fuzz = null)

/**
 * What the dashboard shows, computed from the learner's cards, their grammar answers and the
 * checkpoints they passed ([passedCheckpoints]: JLPT numbers). Pure, so it is easy to test.
 */
fun computeProgress(
    cards: List<CardEntity>,
    catalog: Catalog,
    grammar: List<GrammarProgressEntity>,
    passedCheckpoints: Set<Int>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): ProgressSnapshot {
    val wordCards = cards.filter { it.itemType == ItemType.Word }
    // A word's knowledge is that of its recognition card (the first one it gets).
    val knowledgeByWord = wordCards.filter { it.kind == CardKind.Recognition }.associate { it.itemId to it.knowledge() }

    // jlpt -> [learning, known, mature]
    val counts = HashMap<Int, IntArray>()
    for ((id, knowledge) in knowledgeByWord) {
        val jlpt = catalog.words[id]?.jlpt ?: continue
        val slot = when (knowledge) {
            Knowledge.Learning -> 0
            Knowledge.Known -> 1
            Knowledge.Mature -> 2
            Knowledge.Unseen -> continue
        }
        counts.getOrPut(jlpt) { IntArray(3) }[slot]++
    }
    val knownGrammar = grammar.filter { grammarStatus(it) == GrammarStatus.Known }.mapNotNull { catalog.grammarLevels[it.grammarId] }
        .groupingBy { it }.eachCount()
    val vocab = (5 downTo 1).map { jlpt ->
        val c = counts[jlpt] ?: IntArray(3)
        LevelProgress(
            jlpt = jlpt,
            total = catalog.wordsPerLevel[jlpt] ?: 0,
            mature = c[2],
            known = c[1],
            learning = c[0],
            grammarTotal = catalog.grammarPerLevel[jlpt] ?: 0,
            grammarKnown = knownGrammar[jlpt] ?: 0,
            checkpointPassed = jlpt in passedCheckpoints,
        )
    }

    val knownKanji = HashSet<String>()
    for ((id, knowledge) in knowledgeByWord) {
        if (knowledge != Knowledge.Known && knowledge != Knowledge.Mature) continue
        catalog.words[id]?.expression?.forEach { char ->
            val kanji = char.toString()
            if (kanji in catalog.kanjiLevels) knownKanji += kanji
        }
    }
    val kanji = (5 downTo 1).map { jlpt ->
        KanjiProgress(jlpt, catalog.kanjiPerLevel[jlpt] ?: 0, knownKanji.count { catalog.kanjiLevels[it] == jlpt })
    }

    val weak = wordCards
        .filter { !it.suspended && it.reps > 0 }
        .groupBy { it.itemId }
        .mapNotNull { (id, group) ->
            val lapses = group.sumOf { it.lapses }
            val word = catalog.words[id]
            if (lapses == 0 || word == null) return@mapNotNull null
            val retrievability = group
                .filter { it.state == SrsState.Review.value && it.lastReview != null }
                .minOfOrNull { scheduler.retrievability(it.toSrs(), now) }
            WeakWord(word, lapses, retrievability)
        }
        .sortedWith(compareByDescending<WeakWord> { it.lapses }.thenBy { it.retrievability ?: 1.0 })
        .take(MAX_WEAK_WORDS)

    return ProgressSnapshot(
        vocab = vocab,
        kanji = kanji,
        estimate = estimateLevel(vocab.associate { it.jlpt to LevelEvidence(it.jlpt, it.fraction, it.grammarFraction, it.checkpointPassed) }),
        weak = weak,
        forecast = forecast(cards, now, zone),
        studiedWords = knowledgeByWord.values.count { it != Knowledge.Unseen },
        knownWords = knowledgeByWord.values.count { it == Knowledge.Known || it == Knowledge.Mature },
    )
}

/** Cards due per study day (4 a.m. boundaries), starting with today; suspended cards are left out. */
fun forecast(
    cards: List<CardEntity>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    days: Int = FORECAST_DAYS,
): List<Int> {
    val dayStart = ZonedDateTime.ofInstant(StudyDay.start(now, zone), zone)
    val dayEnds = (1..days).map { dayStart.plusDays(it.toLong()).toInstant().toEpochMilli() }
    val counts = IntArray(days)
    for (card in cards) {
        if (card.suspended) continue
        val index = dayEnds.indexOfFirst { card.due < it }
        if (index >= 0) counts[index]++
    }
    return counts.toList()
}
