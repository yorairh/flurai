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
 * The learner's level and next goal. [goalFraction] is the share of the goal level's words that
 * count as known (1.0 when there is no higher level).
 */
data class LevelEstimate(val current: JlptLevel, val goal: JlptLevel?, val goalFraction: Double) {
    /** Progress toward passing the goal level, 0..1. */
    val goalProgress: Float
        get() = (goalFraction / KnowledgeRules.LEVEL_PASSED_FRACTION).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Estimates the level from vocabulary coverage ([fractions]: JLPT number → share of words known).
 * It starts from the level the learner chose (that level and everything below it counts as already
 * reached) and moves up one level at a time while the next level's words are known well enough.
 * Tests will refine this later; until then the estimate can only go up from the chosen start.
 */
fun estimateLevel(fractions: Map<Int, Double>, start: JlptLevel): LevelEstimate {
    fun effective(level: JlptLevel): Double {
        val jlpt = level.jlpt ?: return 1.0
        return if (level.ordinal <= start.ordinal) 1.0 else fractions[jlpt] ?: 0.0
    }
    var current = JlptLevel.Beginner
    for (level in JlptLevel.entries.drop(1)) {
        if (effective(level) >= KnowledgeRules.LEVEL_PASSED_FRACTION) current = level else break
    }
    val goal = current.next()
    return LevelEstimate(current, goal, goal?.let(::effective) ?: 1.0)
}
