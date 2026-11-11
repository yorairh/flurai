package com.flurai.app.core.quiz

import com.flurai.app.core.content.WordCache
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyDao
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

enum class TestType { Placement, Checkpoint, Quick, Grammar, Reading, Listening }

/**
 * One finished test. [level] is the JLPT number tested (checkpoint) or the number passed
 * (placement; 0 means even N5 was too hard); null for quick quizzes.
 */
@Entity(tableName = "test_result")
data class TestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val type: TestType,
    val level: Int?,
    val correct: Int,
    val total: Int,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
) {
    val fraction: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
}

@Dao
interface TestResultDao {
    @Insert
    suspend fun insert(result: TestResultEntity)

    @Query("SELECT * FROM test_result WHERE uid = :uid ORDER BY taken_at DESC")
    fun results(uid: String): Flow<List<TestResultEntity>>
}

/**
 * The JLPT numbers whose checkpoint the learner passed (at least 80%). Only checkpoints count as
 * evidence of a level: a placement result is a starting suggestion, see [suggestedLevel].
 */
fun passedCheckpoints(results: List<TestResultEntity>): Set<Int> =
    results.filter { it.type == TestType.Checkpoint && it.fraction >= CHECKPOINT_PASS_FRACTION }.mapNotNull { it.level }.toSet()

/** Where the latest placement test put the learner (Beginner when N5 was too hard); null when none was taken. */
fun suggestedLevel(results: List<TestResultEntity>): JlptLevel? =
    results.filter { it.type == TestType.Placement }.maxByOrNull { it.takenAt }?.let { if (it.level == 0) JlptLevel.Beginner else it.level?.let(JlptLevel::ofJlpt) }

/** Builds quizzes from the content database and saves results. */
@Singleton
class QuizRepository @Inject constructor(
    private val content: ContentDao,
    private val wordCache: WordCache,
    private val study: StudyDao,
    private val results: TestResultDao,
) {
    private var wordsByLevel: Map<Int, List<WordEntity>>? = null
    private var kanjiByLevel: Map<Int, List<KanjiEntity>>? = null

    private suspend fun words(): Map<Int, List<WordEntity>> =
        wordsByLevel ?: wordCache.all().groupBy { it.jlpt }.also { wordsByLevel = it }

    private suspend fun kanji(): Map<Int, List<KanjiEntity>> =
        kanjiByLevel ?: content.allKanji().groupBy { it.jlpt }.also { kanjiByLevel = it }

    fun results(uid: String): Flow<List<TestResultEntity>> = results.results(uid)

    suspend fun save(result: TestResultEntity) = results.insert(result)

    /**
     * [count] vocabulary questions at one level (a placement stage). The reading is not shown under
     * a kanji word: placement should test whether the word is known, not whether kana can be read.
     */
    suspend fun placementStage(jlpt: Int, count: Int, random: Random = Random): List<Question> {
        val pool = words()[jlpt].orEmpty()
        return pool.shuffled(random).asSequence().mapNotNull { wordQuestion(it, pool, random)?.copy(hint = null) }.take(count).toList()
    }

    /** The word and kanji part of a level checkpoint (the backend adds grammar questions). */
    suspend fun checkpoint(jlpt: Int, wordCount: Int = 12, kanjiCount: Int = 6, random: Random = Random): List<Question> {
        val wordPool = words()[jlpt].orEmpty()
        val kanjiPool = kanji()[jlpt].orEmpty()
        val wordQs = wordPool.shuffled(random).asSequence().mapNotNull { wordQuestion(it, wordPool, random) }.take(wordCount).toList()
        val kanjiQs = kanjiPool.shuffled(random).asSequence().mapNotNull { kanjiQuestion(it, kanjiPool, random) }.take(kanjiCount).toList()
        return (wordQs + kanjiQs).shuffled(random)
    }

    /**
     * Ten words: up to seven the learner has already started (review), the rest new ones from
     * their level so the quiz also shows what is still to learn.
     */
    suspend fun quick(
        uid: String,
        level: JlptLevel,
        count: Int = 10,
        random: Random = Random,
        build: (WordEntity, List<WordEntity>, Random) -> Question? = ::wordQuestion,
    ): List<Question> {
        val all = words()
        val started = study.introducedItems(uid).filter { it.itemType == ItemType.Word }.map { it.itemId }.toSet()
        val byId = all.values.flatten().associateBy { it.id }
        val reviewPool = started.mapNotNull { byId[it] }
        val levelNumber = level.jlpt ?: 5
        val newPool = (levelNumber downTo 1).firstNotNullOfOrNull { n ->
            all[n].orEmpty().filter { it.id !in started }.takeIf { it.size >= 3 }
        }.orEmpty()

        fun ask(candidates: List<WordEntity>, n: Int): List<Question> = candidates.shuffled(random).asSequence()
            .mapNotNull { w -> build(w, all[w.jlpt].orEmpty(), random) }.take(n).toList()

        val review = ask(reviewPool, minOf(7, count))
        val fresh = ask(newPool, count - review.size)
        return (review + fresh).shuffled(random)
    }

    /** Ten spoken words (same mix as the quick quiz): pick the meaning of what you hear. */
    suspend fun listening(uid: String, level: JlptLevel, count: Int = 10, random: Random = Random): List<Question> =
        quick(uid, level, count, random, build = ::listeningQuestion)
}
