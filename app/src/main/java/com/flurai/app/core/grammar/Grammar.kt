package com.flurai.app.core.grammar

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

/**
 * One grammar point the learner has started: a row exists from the moment they mark the lesson
 * learned (or answer a question about it), and then counts their practice answers.
 * Saved per user (UserDatabase v4).
 */
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

    @Query("SELECT * FROM grammar_progress WHERE uid = :uid")
    suspend fun all(uid: String): List<GrammarProgressEntity>

    @Query("SELECT * FROM grammar_progress WHERE uid = :uid AND grammar_id = :id")
    suspend fun get(uid: String, id: Int): GrammarProgressEntity?

    @Upsert
    suspend fun upsert(entity: GrammarProgressEntity)

    /** Adds the row unless the point already has one (so marking a lesson learned never resets its answers). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: GrammarProgressEntity)
}

enum class GrammarStatus { New, Learning, Known }

/**
 * New until the learner starts the point (marks its lesson learned or answers a question about it);
 * Known once answered right three times and the latest answer was right; Learning in between.
 */
fun grammarStatus(progress: GrammarProgressEntity?): GrammarStatus = when {
    progress == null -> GrammarStatus.New
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

/** A point the learner has just started, with no practice answers yet. */
fun startedGrammar(uid: String, id: Int, at: Long) =
    GrammarProgressEntity(uid = uid, grammarId = id, correct = 0, wrong = 0, lastCorrect = false, lastAt = at)

/**
 * The order N5 is taught in: です and the first particles, verbs in the polite form, existence,
 * adjectives, wishes and invitations, the て-form, then plain forms. Ids not listed here (and every
 * other level) follow in id order.
 */
internal val N5_LESSON_ORDER = listOf(
    526, 501, 510, 511, 507, 506, 536, 537, 538, 539, 527, 528,
    549, 503, 512, 513, 550, 504, 554, 505, 508, 573, 509, 587, 569, 568, 585, 582,
    542, 543, 544, 545, 546, 502, 558, 586, 588, 541, 547, 548,
    529, 533, 532, 523, 530, 531, 584, 583, 589, 540, 534, 535, 577, 580, 576, 555, 556, 557,
    517, 518, 565, 514, 575, 553, 525, 522, 579, 581, 567, 559, 560, 572, 571,
    578, 516, 515, 519, 520, 563,
    552, 521, 562, 551, 564, 524, 561, 574, 570, 566,
)

private val lessonRank = N5_LESSON_ORDER.withIndex().associate { (index, id) -> id to index }

/** [points] in the order their lessons are taken: easiest level first, then the teaching order inside it. */
fun lessonOrder(points: List<GrammarPointEntity>): List<GrammarPointEntity> =
    points.sortedWith(compareByDescending<GrammarPointEntity> { it.jlpt }.thenBy { lessonRank[it.id] ?: Int.MAX_VALUE }.thenBy { it.id })

/** The next lesson of level [jlpt]: its first point, in lesson order, that is not in [started]. Null when all are. */
fun nextLesson(points: List<GrammarPointEntity>, started: Set<Int>, jlpt: Int): GrammarPointEntity? =
    lessonOrder(points.filter { it.jlpt == jlpt }).firstOrNull { it.id !in started }

/**
 * The examples a level's practice asks about, in order. With [learnedOnly] only points the learner
 * has started ([status] not New). Points that are not Known come first when [favorUnknown]. One
 * example per point first; further examples of the same points follow, so a learner with only a
 * few points still gets a full session.
 */
internal fun practiceExamples(
    levelExamples: List<GrammarExampleEntity>,
    status: Map<Int, GrammarStatus>,
    learnedOnly: Boolean,
    favorUnknown: Boolean,
    random: Random,
): List<GrammarExampleEntity> {
    val shuffled = levelExamples
        .filter { !learnedOnly || (status[it.grammarId] ?: GrammarStatus.New) != GrammarStatus.New }
        .shuffled(random)
    val ordered = if (favorUnknown) shuffled.sortedBy { status[it.grammarId] == GrammarStatus.Known } else shuffled
    val onePerPoint = ordered.distinctBy { it.grammarId }
    return onePerPoint + (ordered - onePerPoint.toSet())
}

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
     * [jlpt] draws [count] examples from that level, favoring points not yet known. With
     * [learnedOnly] it asks only about points the learner has started (the learner's own practice);
     * tests leave it off so they cover the whole level.
     */
    suspend fun practice(
        uid: String,
        jlpt: Int,
        pointId: Int?,
        count: Int = 10,
        random: Random = Random,
        favorUnknown: Boolean = true,
        learnedOnly: Boolean = false,
    ): List<Question> {
        val points = content.allGrammarPoints()
        val byId = points.associateBy { it.id }
        val examples = content.allGrammarExamples()
        val levelExamples = examples.filter { byId[it.grammarId]?.jlpt == jlpt }
        val pool = if (levelExamples.size >= 4) levelExamples else examples
        val chosen = if (pointId != null) {
            examples.filter { it.grammarId == pointId }.shuffled(random)
        } else {
            val rows = progress.all(uid).associateBy { it.grammarId }
            val status = levelExamples.map { it.grammarId }.distinct().associateWith { grammarStatus(rows[it]) }
            practiceExamples(levelExamples, status, learnedOnly, favorUnknown, random)
        }
        val questions = chosen.mapNotNull { e -> byId[e.grammarId]?.let { grammarQuestion(it, e, pool, random) } }
        return if (pointId == null) questions.take(count) else questions
    }

    /** The learner read the lesson and says they have it: the point joins their practice. Answers already counted are kept. */
    suspend fun markLearned(uid: String, grammarId: Int, at: Long) = progress.insertIfAbsent(startedGrammar(uid, grammarId, at))

    suspend fun record(uid: String, grammarId: Int, correct: Boolean, at: Long) {
        progress.upsert(progress.get(uid, grammarId).afterAnswer(uid, grammarId, correct, at))
    }
}
