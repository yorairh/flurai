package com.flurai.app.core.knowledge

import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity

/** How well the learner knows an item, judged from its flashcard. */
enum class Knowledge { Unseen, Learning, Known, Mature }

object KnowledgeRules {
    /** A card counts as known once its memory is predicted to last this many days. */
    const val KNOWN_STABILITY_DAYS = 7.0

    /** Anki's "mature" mark is 21 days. */
    const val MATURE_STABILITY_DAYS = 21.0

    /** A level counts as passed when this share of its words is known. */
    const val LEVEL_PASSED_FRACTION = 0.8
}

/**
 * A card that has never been answered is unseen. One still on the short learning steps (or being
 * relearned) is learning, and so is a graduated card whose memory is still young. FSRS stability is
 * the number of days until recall drops to 90%, so it measures how durable the memory is.
 */
fun CardEntity.knowledge(): Knowledge = when {
    reps == 0 || lastReview == null -> Knowledge.Unseen
    state != SrsState.Review.value -> Knowledge.Learning
    (stability ?: 0.0) >= KnowledgeRules.MATURE_STABILITY_DAYS -> Knowledge.Mature
    (stability ?: 0.0) >= KnowledgeRules.KNOWN_STABILITY_DAYS -> Knowledge.Known
    else -> Knowledge.Learning
}

/**
 * What is known about one JLPT level: the share of its words and of its grammar points the learner
 * knows, and whether they passed its checkpoint test.
 */
data class LevelEvidence(val jlpt: Int, val words: Double, val grammar: Double, val checkpointPassed: Boolean) {
    /** Enough of the level is known from studying: both words and grammar reach the pass share. */
    val studied: Boolean get() = words >= KnowledgeRules.LEVEL_PASSED_FRACTION && grammar >= KnowledgeRules.LEVEL_PASSED_FRACTION
}

/**
 * The learner's level and next goal. [goalWords] and [goalGrammar] are the shares of the goal
 * level's words and grammar points that count as known.
 */
data class LevelEstimate(
    val current: JlptLevel,
    val goal: JlptLevel?,
    val goalWords: Double,
    val goalGrammar: Double,
) {
    /** Progress toward passing the goal level by studying, 0..1: the weaker of words and grammar. */
    val goalProgress: Float
        get() = (minOf(goalWords, goalGrammar) / KnowledgeRules.LEVEL_PASSED_FRACTION).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The level the evidence supports, climbing one level at a time from Beginner. A level is reached
 * when its checkpoint was passed (a passed harder checkpoint covers the easier levels too), or when
 * at least [KnowledgeRules.LEVEL_PASSED_FRACTION] of both its words and its grammar points are
 * known. What the learner chose at sign-up, or a placement result, is not evidence: placement only
 * suggests where to start.
 */
fun estimateLevel(evidence: Map<Int, LevelEvidence>): LevelEstimate {
    val levels = JlptLevel.entries.drop(1)
    val hardestCheckpoint = levels.filter { evidence[it.jlpt]?.checkpointPassed == true }.maxOfOrNull { it.ordinal } ?: 0
    fun reached(level: JlptLevel) = level.ordinal <= hardestCheckpoint || evidence[level.jlpt]?.studied == true
    var current = JlptLevel.Beginner
    for (level in levels) if (reached(level)) current = level else break
    val goal = current.next()
    val goalEvidence = goal?.let { evidence[it.jlpt] }
    return LevelEstimate(current, goal, goalEvidence?.words ?: if (goal == null) 1.0 else 0.0, goalEvidence?.grammar ?: if (goal == null) 1.0 else 0.0)
}
