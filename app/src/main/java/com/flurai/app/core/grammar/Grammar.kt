package com.flurai.app.core.grammar

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.GrammarExampleEntity
import com.flurai.app.core.content.GrammarPointEntity
import com.flurai.app.core.quiz.Question
import com.flurai.app.core.quiz.QuestionKind
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** How often the learner got a grammar point right in exercises. Saved per user (UserDatabase v4). */
@Entity(tableName = "grammar_progress", primaryKeys = ["uid", "grammar_id"])
data class GrammarProgressEntity(
    val uid: String,
    @ColumnInfo(name = "grammar_id") val grammarId: Int,
    val correct: Int,
    val wrong: Int,
    @ColumnInfo(name = "last_correct") val lastCorrect: Boolean,
    @ColumnInfo(name = "last_at") val lastAt: Long,
)

@Dao
interface GrammarProgressDao {
    @Query("SELECT * FROM grammar_progress WHERE uid = :uid")
    fun progress(uid: String): Flow<List<GrammarProgressEntity>>

    @Query("SELECT * FROM grammar_progress WHERE uid = :uid AND grammar_id = :id")
    suspend fun get(uid: String, id: Int): GrammarProgressEntity?

    @Upsert
    suspend fun upsert(entity: GrammarProgressEntity)
}

enum class GrammarStatus { New, Learning, Known }

/** Known once answered right three times and the latest answer was right; Learning after any attempt. */
fun grammarStatus(progress: GrammarProgressEntity?): GrammarStatus = when {
    progress == null || progress.correct + progress.wrong == 0 -> GrammarStatus.New
    progress.correct >= KNOWN_AFTER_CORRECT && progress.lastCorrect -> GrammarStatus.Known
    else -> GrammarStatus.Learning
}

const val KNOWN_AFTER_CORRECT = 3

/** The progress after one more answer. */
fun GrammarProgressEntity?.afterAnswer(uid: String, id: Int, correct: Boolean, at: Long) = GrammarProgressEntity(
    uid = uid,
    grammarId = id,
    correct = (this?.correct ?: 0) + if (correct) 1 else 0,
    wrong = (this?.wrong ?: 0) + if (correct) 0 else 1,
    lastCorrect = correct,
    lastAt = at,
)

/**
 * The shape of an answer, so wrong options look like it: a particle (one or two kana) among particles,
 * a short ending among short endings, a phrase with kanji among phrases with kanji.
 */
internal fun answerShape(answer: String): String {
    val kanji = if (answer.any { it in '一'..'鿿' }) "kanji-" else ""
    val size = when {
        answer.length <= 2 -> "short"
        answer.length <= 5 -> "medium"
        else -> "long"
    }
    return kanji + size
}

/** The first sentence of an explanation, for the note under a practice question. */
internal fun firstSentence(text: String, max: Int = 160): String {
    val end = text.indexOfFirst { it == '。' || it == '.' }.let { if (it < 0) text.length else it + 1 }
    return text.substring(0, end).take(max).trim()
}

/** Examples with a rank from here up were added by the content pipeline for practice only: the lesson page does not list them. */
const val EXTRA_EXAMPLE_RANK = 10

/** The sentence with the pattern's key part hidden. */
const val BLANK = "＿＿"

/**
 * "Which part is missing?" for one example. The wrong options are the key parts of other points
 * in [pool]; parts that contain or sit inside the answer are skipped so only one option fits.
 * Null when the pool is too small.
 */
fun grammarQuestion(
    point: GrammarPointEntity,
    example: GrammarExampleEntity,
    pool: List<GrammarExampleEntity>,
    random: Random,
): Question? {
    val answer = example.answer
    if (example.japanese.indexOf(answer) < 0) return null
    val shape = answerShape(answer)
    val wrong = pool.asSequence()
        .filter { it.grammarId != example.grammarId }
        .map { it.answer }
        .filter { it != answer && !it.contains(answer) && !answer.contains(it) }
        .distinct()
        .toList()
        .shuffled(random)
        // The same kind of part first (a particle among particles), then a similar length: both make it hard to guess by shape.
        .sortedWith(compareBy<String> { answerShape(it) != shape }.thenBy { kotlin.math.abs(it.length - answer.length) })
        .take(3)
    if (wrong.size < 3) return null
    val options = (wrong + answer).shuffled(random)
    return Question(
        kind = QuestionKind.GrammarBlank,
        prompt = example.japanese.replaceFirst(answer, BLANK),
        hint = example.english,
        options = options,
        answer = options.indexOf(answer),
        level = point.jlpt,
        grammarId = point.id,
        note = "${point.pattern}: ${point.meaning}\n${firstSentence(point.explanation)}",
    )
}

@Singleton
class GrammarRepository @Inject constructor(
    private val content: ContentDao,
    private val progress: GrammarProgressDao,
) {
    fun points(): Flow<List<GrammarPointEntity>> = content.grammarPoints()

    fun progress(uid: String): Flow<List<GrammarProgressEntity>> = progress.progress(uid)

    suspend fun point(id: Int): GrammarPointEntity? = content.grammarPoint(id)

    /** The examples of the lesson page: the written ones, without the extra sentences used only for practice. */
    suspend fun examples(id: Int): List<GrammarExampleEntity> = content.grammarExamples(id).filter { it.rank < EXTRA_EXAMPLE_RANK }

    /**
     * Fill-in-the-blank questions. [pointId] practices one point (every example); otherwise
     * [jlpt] draws [count] examples from that level, favoring points not yet known.
     */
    suspend fun practice(
        uid: String,
        jlpt: Int,
        pointId: Int?,
        count: Int = 10,
        random: Random = Random,
        favorUnknown: Boolean = true,
    ): List<Question> {
        val points = content.allGrammarPoints()
        val byId = points.associateBy { it.id }
        val examples = content.allGrammarExamples()
        val levelExamples = examples.filter { byId[it.grammarId]?.jlpt == jlpt }
        val pool = if (levelExamples.size >= 4) levelExamples else examples
        val chosen = if (pointId != null) {
            examples.filter { it.grammarId == pointId }.shuffled(random)
        } else {
            val status = HashMap<Int, GrammarStatus>()
            levelExamples.map { it.grammarId }.distinct().forEach { status[it] = grammarStatus(progress.get(uid, it)) }
            // Points that are not Known yet first (random within each group), unless the test must cover the whole level.
            val (open, known) = levelExamples.shuffled(random).partition { status[it.grammarId] != GrammarStatus.Known }
            (if (favorUnknown) open + known else levelExamples.shuffled(random)).distinctBy { it.grammarId }
        }
        val questions = chosen.mapNotNull { e -> byId[e.grammarId]?.let { grammarQuestion(it, e, pool, random) } }
        return if (pointId == null) questions.take(count) else questions
    }

    suspend fun record(uid: String, grammarId: Int, correct: Boolean, at: Long) {
        progress.upsert(progress.get(uid, grammarId).afterAnswer(uid, grammarId, correct, at))
    }
}
