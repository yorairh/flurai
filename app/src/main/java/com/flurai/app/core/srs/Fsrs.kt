package com.flurai.app.core.srs

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/*
 * FSRS-6 (Free Spaced Repetition Scheduler), the scheduler Anki uses. A direct port of the reference
 * implementation py-fsrs 6.x (MIT, github.com/open-spaced-repetition/py-fsrs, fsrs/scheduler.py);
 * FsrsTest checks it against values produced by py-fsrs. Keep the structure parallel to the
 * Python code so future upstream changes are easy to apply.
 */

enum class Rating(val value: Int) { Again(1), Hard(2), Good(3), Easy(4) }

/** Learning = first steps after introduction; Review = graduated; Relearning = forgotten, back on steps. */
enum class SrsState(val value: Int) {
    Learning(1), Review(2), Relearning(3);

    companion object {
        fun of(value: Int) = entries.first { it.value == value }
    }
}

/** Scheduling state of one card. A brand-new card is Learning, step 0, with no stability yet. */
data class SrsCard(
    val state: SrsState = SrsState.Learning,
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Instant,
    val lastReview: Instant? = null,
)

class FsrsScheduler(
    private val parameters: DoubleArray = DEFAULT_PARAMETERS,
    private val desiredRetention: Double = 0.9,
    private val learningSteps: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10)),
    private val relearningSteps: List<Duration> = listOf(Duration.ofMinutes(10)),
    private val maximumInterval: Int = 36500,
    /** Null disables fuzzing (tests and interval previews). */
    private val fuzz: Random? = Random.Default,
) {
    private val decay = -parameters[20]
    private val factor = 0.9.pow(1 / decay) - 1

    init {
        require(parameters.size == 21) { "FSRS-6 needs 21 parameters" }
    }

    /** Predicted probability of recalling the card at [now]. */
    fun retrievability(card: SrsCard, now: Instant): Double {
        val last = card.lastReview ?: return 0.0
        val stability = card.stability ?: return 0.0
        val elapsedDays = max(0L, Duration.between(last, now).toDays())
        return (1 + factor * elapsedDays / stability).pow(decay)
    }

    fun review(card: SrsCard, rating: Rating, now: Instant): SrsCard {
        val daysSinceLastReview = card.lastReview?.let { Duration.between(it, now).toDays() }
        var state = card.state
        var step = card.step
        var stability = card.stability
        var difficulty = card.difficulty
        val interval: Duration

        fun longTermStability(s: Double, d: Double) =
            nextStability(d, s, retrievability(card, now), rating)

        when (card.state) {
            SrsState.Learning -> {
                if (stability == null || difficulty == null) {
                    stability = initialStability(rating)
                    difficulty = initialDifficulty(rating, clamp = true)
                } else if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    stability = shortTermStability(stability, rating)
                    difficulty = nextDifficulty(difficulty, rating)
                } else {
                    stability = longTermStability(stability, difficulty)
                    difficulty = nextDifficulty(difficulty, rating)
                }
                val result = stepThrough(learningSteps, SrsState.Learning, requireNotNull(step), rating, stability)
                state = result.state; step = result.step; interval = result.interval
            }
            SrsState.Review -> {
                stability = if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    shortTermStability(requireNotNull(stability), rating)
                } else {
                    longTermStability(requireNotNull(stability), requireNotNull(difficulty))
                }
                difficulty = nextDifficulty(requireNotNull(difficulty), rating)
                if (rating == Rating.Again && relearningSteps.isNotEmpty()) {
                    state = SrsState.Relearning
                    step = 0
                    interval = relearningSteps[0]
                } else {
                    interval = Duration.ofDays(nextInterval(stability))
                }
            }
            SrsState.Relearning -> {
                if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    stability = shortTermStability(requireNotNull(stability), rating)
                    difficulty = nextDifficulty(requireNotNull(difficulty), rating)
                } else {
                    stability = longTermStability(requireNotNull(stability), requireNotNull(difficulty))
                    difficulty = nextDifficulty(difficulty, rating)
                }
                val result = stepThrough(relearningSteps, SrsState.Relearning, requireNotNull(step), rating, stability)
                state = result.state; step = result.step; interval = result.interval
            }
        }

        val finalInterval = if (fuzz != null && state == SrsState.Review) fuzzed(interval) else interval
        return SrsCard(state, step, stability, difficulty, due = now + finalInterval, lastReview = now)
    }

    /** The interval each rating would give, without fuzz; for the answer buttons. */
    fun preview(card: SrsCard, now: Instant): Map<Rating, Duration> {
        val exact = FsrsScheduler(parameters, desiredRetention, learningSteps, relearningSteps, maximumInterval, fuzz = null)
        return Rating.entries.associateWith { Duration.between(now, exact.review(card, it, now).due) }
    }

    private data class StepResult(val state: SrsState, val step: Int?, val interval: Duration)

    /** Shared by Learning and Relearning: move along the short steps or graduate to Review. */
    private fun stepThrough(
        steps: List<Duration>,
        stayState: SrsState,
        step: Int,
        rating: Rating,
        stability: Double,
    ): StepResult {
        val graduate = StepResult(SrsState.Review, null, Duration.ofDays(nextInterval(stability)))
        if (steps.isEmpty() || (step >= steps.size && rating != Rating.Again)) return graduate
        return when (rating) {
            Rating.Again -> StepResult(stayState, 0, steps[0])
            Rating.Hard -> StepResult(
                stayState,
                step,
                when {
                    step == 0 && steps.size == 1 -> steps[0].multipliedBy(3).dividedBy(2)
                    step == 0 -> steps[0].plus(steps[1]).dividedBy(2)
                    else -> steps[step]
                },
            )
            Rating.Good -> if (step + 1 == steps.size) graduate else StepResult(stayState, step + 1, steps[step + 1])
            Rating.Easy -> graduate
        }
    }

    private fun clampDifficulty(d: Double) = min(max(d, MIN_DIFFICULTY), MAX_DIFFICULTY)
    private fun clampStability(s: Double) = max(s, STABILITY_MIN)

    private fun initialStability(rating: Rating) = clampStability(parameters[rating.value - 1])

    private fun initialDifficulty(rating: Rating, clamp: Boolean): Double {
        val d = parameters[4] - exp(parameters[5] * (rating.value - 1)) + 1
        return if (clamp) clampDifficulty(d) else d
    }

    private fun nextInterval(stability: Double): Long {
        val days = (stability / factor) * (desiredRetention.pow(1 / decay) - 1)
        return min(max(roundHalfEven(days), 1L), maximumInterval.toLong())
    }

    private fun shortTermStability(stability: Double, rating: Rating): Double {
        var increase = exp(parameters[17] * (rating.value - 3 + parameters[18])) * stability.pow(-parameters[19])
        if (rating != Rating.Again) increase = max(increase, 1.0)
        return clampStability(stability * increase)
    }

    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val delta = -(parameters[6] * (rating.value - 3))
        val damped = difficulty + (10.0 - difficulty) * delta / 9.0
        val reverted = parameters[7] * initialDifficulty(Rating.Easy, clamp = false) + (1 - parameters[7]) * damped
        return clampDifficulty(reverted)
    }

    private fun nextStability(difficulty: Double, stability: Double, retrievability: Double, rating: Rating): Double {
        val next = if (rating == Rating.Again) {
            min(
                parameters[11] * difficulty.pow(-parameters[12]) * ((stability + 1).pow(parameters[13]) - 1) *
                    exp((1 - retrievability) * parameters[14]),
                stability / exp(parameters[17] * parameters[18]),
            )
        } else {
            val hardPenalty = if (rating == Rating.Hard) parameters[15] else 1.0
            val easyBonus = if (rating == Rating.Easy) parameters[16] else 1.0
            stability * (
                1 + exp(parameters[8]) * (11 - difficulty) * stability.pow(-parameters[9]) *
                    (exp((1 - retrievability) * parameters[10]) - 1) * hardPenalty * easyBonus
                )
        }
        return clampStability(next)
    }

    private fun fuzzed(interval: Duration): Duration {
        val days = interval.toDays()
        if (days < 2.5) return interval
        var delta = 1.0
        for ((start, end, f) in FUZZ_RANGES) delta += f * max(min(days.toDouble(), end) - start, 0.0)
        val maxIvl = min(roundHalfEven(days + delta), maximumInterval.toLong())
        val minIvl = min(max(2L, roundHalfEven(days - delta)), maxIvl)
        val random = requireNotNull(fuzz).nextDouble() * (maxIvl - minIvl + 1) + minIvl
        return Duration.ofDays(min(roundHalfEven(random), maximumInterval.toLong()))
    }

    companion object {
        val DEFAULT_PARAMETERS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
        private const val STABILITY_MIN = 0.001
        private const val MIN_DIFFICULTY = 1.0
        private const val MAX_DIFFICULTY = 10.0
        private val FUZZ_RANGES = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.POSITIVE_INFINITY, 0.05),
        )

        /** Python's round(): halves go to the even neighbour. */
        private fun roundHalfEven(x: Double): Long = Math.rint(x).roundToLong()
    }
}
