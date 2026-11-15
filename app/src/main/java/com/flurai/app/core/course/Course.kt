package com.flurai.app.core.course

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.CourseLessonEntity
import com.flurai.app.core.content.CourseQuestionEntity
import com.flurai.app.core.quiz.Question
import com.flurai.app.core.quiz.QuestionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/*
 * The grammar course: a recommended path of lessons (written for FluRai after Cure Dolly's
 * "Japanese from Scratch"), each followed by a test of hand-written questions. Everything the rules
 * decide is a pure function here, so it is tested on the JVM; CourseRepository only connects it to
 * the databases. Course progress is deliberately separate from grammar_progress: it never changes
 * the level estimate.
 */

/**
 * What the learner has done with one course entry. A row exists once a test was taken (or a note
 * read). [passedAt] is set the first time a lesson test reaches [COURSE_PASS_FRACTION] (or when a
 * note is read) and never cleared. [missed] holds the ids of the questions of this lesson that were
 * last answered wrong, comma separated, for "Test yourself" to favor. Saved per user (UserDatabase v7).
 */
@Entity(tableName = "course_progress", primaryKeys = ["uid", "lesson_id"])
data class CourseProgressEntity(
    val uid: String,
    @ColumnInfo(name = "lesson_id") val lessonId: Int,
    @ColumnInfo(name = "best_correct") val bestCorrect: Int,
    @ColumnInfo(name = "best_total") val bestTotal: Int,
    @ColumnInfo(name = "passed_at") val passedAt: Long?,
    val attempts: Int,
    val missed: String,
    @ColumnInfo(name = "last_at") val lastAt: Long,
) {
    val missedIds: Set<Int> get() = missed.split(',').mapNotNull { it.toIntOrNull() }.toSet()
}

@Dao
interface CourseProgressDao {
    @Query("SELECT * FROM course_progress WHERE uid = :uid")
    fun progress(uid: String): Flow<List<CourseProgressEntity>>

    @Query("SELECT * FROM course_progress WHERE uid = :uid")
    suspend fun all(uid: String): List<CourseProgressEntity>

    @Query("SELECT * FROM course_progress WHERE uid = :uid AND lesson_id = :id")
    suspend fun get(uid: String, id: Int): CourseProgressEntity?

    @Upsert
    suspend fun upsert(entity: CourseProgressEntity)
}

/** A lesson test is passed at this share of right answers (the same mark as a level checkpoint). */
const val COURSE_PASS_FRACTION = 0.8

/** How many questions a lesson test asks, and how many of them come from earlier lessons. */
const val LESSON_TEST_SIZE = 10
const val LESSON_TEST_EARLIER = 2

/** The sizes "Test yourself" offers. */
val SELF_TEST_SIZES = listOf(10, 20, 30)

enum class CourseStatus { New, Tried, Done }

fun courseStatus(progress: CourseProgressEntity?): CourseStatus = when {
    progress == null -> CourseStatus.New
    progress.passedAt != null -> CourseStatus.Done
    else -> CourseStatus.Tried
}

fun CourseLessonEntity.isNote(): Boolean = kind == KIND_NOTE

const val KIND_LESSON = "lesson"
const val KIND_NOTE = "note"

/** Ids of the grammar reference points a lesson links to. */
fun CourseLessonEntity.relatedPoints(): List<Int> = related.split(',').mapNotNull { it.trim().toIntOrNull() }

/** The entry to take next: the first, in course order, that is not done (a note is done once read). Null when all are. */
fun nextCourseEntry(lessons: List<CourseLessonEntity>, progress: Map<Int, CourseProgressEntity>): CourseLessonEntity? =
    lessons.sortedBy { it.position }.firstOrNull { courseStatus(progress[it.id]) != CourseStatus.Done }

/** Lessons (not notes) passed. */
fun passedLessons(lessons: List<CourseLessonEntity>, progress: Map<Int, CourseProgressEntity>): List<CourseLessonEntity> =
    lessons.filter { !it.isNote() && courseStatus(progress[it.id]) == CourseStatus.Done }

/** One answered course question: which question of which lesson, and whether it was right. */
data class CourseAnswer(val lessonId: Int, val questionId: Int, val correct: Boolean)

/** The question ids of [lessonId] after [answers]: wrong ones are added, right ones are dropped. */
private fun updatedMissed(previous: CourseProgressEntity?, lessonId: Int, answers: List<CourseAnswer>): String {
    val missed = previous?.missedIds.orEmpty().toMutableSet()
    answers.filter { it.lessonId == lessonId }.forEach { if (it.correct) missed -= it.questionId else missed += it.questionId }
    return missed.sorted().joinToString(",")
}

/**
 * The progress after a test of one lesson: one more attempt, the better score kept, the lesson
 * passed (for good) at [COURSE_PASS_FRACTION], and the missed questions updated.
 */
fun CourseProgressEntity?.afterLessonTest(uid: String, lessonId: Int, answers: List<CourseAnswer>, at: Long): CourseProgressEntity {
    val mine = answers.filter { it.lessonId == lessonId }
    val correct = mine.count { it.correct }
    val total = mine.size
    val betterThanBest = this == null || bestTotal == 0 ||
        correct.toDouble() / total.coerceAtLeast(1) > bestCorrect.toDouble() / bestTotal
    val passedNow = total > 0 && correct.toDouble() / total >= COURSE_PASS_FRACTION
    return CourseProgressEntity(
        uid = uid,
        lessonId = lessonId,
        bestCorrect = if (betterThanBest) correct else this!!.bestCorrect,
        bestTotal = if (betterThanBest) total else this!!.bestTotal,
        passedAt = this?.passedAt ?: if (passedNow) at else null,
        attempts = (this?.attempts ?: 0) + 1,
        missed = updatedMissed(this, lessonId, answers),
        lastAt = at,
    )
}

/** The progress after "Test yourself": only the missed questions change, never the score or pass. */
fun CourseProgressEntity.afterSelfTest(answers: List<CourseAnswer>, at: Long): CourseProgressEntity =
    copy(missed = updatedMissed(this, lessonId, answers), lastAt = at)

/** A note the learner has read: done, with no score. */
fun noteRead(uid: String, lessonId: Int, at: Long) =
    CourseProgressEntity(uid, lessonId, bestCorrect = 0, bestTotal = 0, passedAt = at, attempts = 0, missed = "", lastAt = at)

// -- lesson text -----------------------------------------------------------------------------------

/** One car of a train diagram: [role] is "engine", "carriage", "topic", "zero" or "plain". */
@Serializable
data class TrainCar(val text: String, val role: String = "plain", val label: String? = null)

/**
 * One block of a lesson. [type]: "heading" ([text]), "text" ([text]; **bold** marks emphasis), "example"
 * ([jp], [en], [ruby]), "diagram" ([cars], [text] as caption), "keypoints" ([title], [items]) and
 * "table" ([title], [rows], the first row is the header).
 */
@Serializable
data class CourseBlock(
    val type: String,
    val text: String? = null,
    val title: String? = null,
    val jp: String? = null,
    val en: String? = null,
    val ruby: String? = null,
    val items: List<String>? = null,
    val cars: List<TrainCar>? = null,
    val rows: List<List<String>>? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** The blocks of a lesson body; empty when the stored text cannot be read. */
fun parseBody(body: String): List<CourseBlock> =
    runCatching { json.decodeFromString(ListSerializer(CourseBlock.serializer()), body) }.getOrDefault(emptyList())

private fun parseOptions(options: String): List<String> =
    runCatching { json.decodeFromString(ListSerializer(String.serializer()), options) }.getOrDefault(emptyList())

// -- questions -------------------------------------------------------------------------------------

/**
 * The question as asked. A choice question's options are shuffled (unless there are only two: a
 * true/false or yes/no keeps its order); a tap question keeps the order of its words; an order
 * question shows its pieces shuffled, never in the right order. Null when the stored question is
 * broken (so one bad row never blocks a test).
 */
fun courseQuestion(entity: CourseQuestionEntity, jlpt: Int, random: Random): Question? {
    val options = parseOptions(entity.options)
    val key = entity.lessonId to entity.id
    return when (entity.type) {
        "choice" -> {
            if (options.size < 2 || entity.answer !in options.indices) return null
            val right = options[entity.answer]
            val shown = if (options.size > 2) options.shuffled(random) else options
            Question(
                kind = QuestionKind.CourseChoice, prompt = entity.prompt, hint = entity.hint, options = shown,
                answer = shown.indexOf(right), level = jlpt, note = entity.note, instruction = entity.instruction,
                courseLesson = key.first, courseQuestion = key.second,
            )
        }
        "tap" -> {
            if (options.size < 2 || entity.answer !in options.indices) return null
            Question(
                kind = QuestionKind.CourseTap, prompt = options.joinToString(""), hint = entity.hint, options = options,
                answer = entity.answer, level = jlpt, note = entity.note, instruction = entity.instruction,
                courseLesson = key.first, courseQuestion = key.second,
            )
        }
        "order" -> {
            if (options.size < 2) return null
            var tiles = options.shuffled(random)
            var tries = 0
            while (tiles == options && tries++ < 10) tiles = options.shuffled(random)
            Question(
                kind = QuestionKind.CourseOrder, prompt = entity.prompt, hint = entity.hint, options = options,
                answer = 0, level = jlpt, note = entity.note, instruction = entity.instruction, tiles = tiles,
                correctText = options.joinToString(""), courseLesson = key.first, courseQuestion = key.second,
            )
        }
        else -> null
    }
}

/**
 * The questions of one lesson's test: up to [LESSON_TEST_SIZE], of which [LESSON_TEST_EARLIER] come
 * from [earlier] lessons the learner passed (the ones missed before first), the rest from the lesson
 * itself. With nothing earlier to ask about, all of them are the lesson's own.
 */
fun lessonTest(
    own: List<CourseQuestionEntity>,
    earlier: List<CourseQuestionEntity>,
    missed: Set<Pair<Int, Int>>,
    jlptOf: (Int) -> Int,
    random: Random,
): List<Question> {
    val fromEarlier = earlier.shuffled(random).sortedBy { (it.lessonId to it.id) !in missed }.take(LESSON_TEST_EARLIER)
    val fromOwn = own.shuffled(random).take(LESSON_TEST_SIZE - fromEarlier.size)
    // If the lesson itself is short, earlier questions fill the gap rather than leaving a short test.
    val padding = if (fromOwn.size < LESSON_TEST_SIZE - fromEarlier.size) earlier.shuffled(random).filter { it !in fromEarlier }.take(LESSON_TEST_SIZE - fromEarlier.size - fromOwn.size) else emptyList()
    return (fromOwn + fromEarlier + padding).shuffled(random).mapNotNull { courseQuestion(it, jlptOf(it.lessonId), random) }
}

/**
 * "Test yourself": [count] questions from the lessons passed ([pool]), about half from the ones
 * answered wrong before when there are enough of them, the rest at random, all shuffled.
 */
fun selfTest(
    pool: List<CourseQuestionEntity>,
    missed: Set<Pair<Int, Int>>,
    count: Int,
    jlptOf: (Int) -> Int,
    random: Random,
): List<Question> {
    val (wrong, rest) = pool.shuffled(random).partition { (it.lessonId to it.id) in missed }
    val chosen = wrong.take(count / 2) + (rest + wrong.drop(count / 2)).take(count - wrong.take(count / 2).size)
    return chosen.shuffled(random).mapNotNull { courseQuestion(it, jlptOf(it.lessonId), random) }
}

// -- repository ------------------------------------------------------------------------------------

@Singleton
class CourseRepository @Inject constructor(
    private val content: ContentDao,
    private val progress: CourseProgressDao,
) {
    fun lessons(): Flow<List<CourseLessonEntity>> = content.courseLessons()

    fun progress(uid: String): Flow<List<CourseProgressEntity>> = progress.progress(uid)

    suspend fun lesson(id: Int): CourseLessonEntity? = content.courseLesson(id)

    /** The course lesson that explains a grammar reference point, if one does. */
    suspend fun lessonForPoint(grammarId: Int): CourseLessonEntity? = content.courseLessonForPoint(grammarId)

    private fun missedKeys(rows: List<CourseProgressEntity>): Set<Pair<Int, Int>> =
        rows.flatMap { row -> row.missedIds.map { row.lessonId to it } }.toSet()

    /** The questions of a lesson's test, see [lessonTest]. */
    suspend fun lessonTest(uid: String, lessonId: Int, random: Random = Random): List<Question> {
        val lessons = content.allCourseLessons()
        val lesson = lessons.firstOrNull { it.id == lessonId } ?: return emptyList()
        val rows = progress.all(uid)
        val done = rows.filter { it.passedAt != null }.map { it.lessonId }.toSet()
        val earlierIds = lessons.filter { !it.isNote() && it.position < lesson.position && it.id in done }.map { it.id }
        val earlier = if (earlierIds.isEmpty()) emptyList() else content.courseQuestionsOf(earlierIds)
        val jlpt = lessons.associate { it.id to (it.jlpt ?: 5) }
        return lessonTest(content.courseQuestions(lessonId), earlier, missedKeys(rows), { jlpt[it] ?: 5 }, random)
    }

    /** The questions of "Test yourself" over every lesson passed. Empty before the first pass. */
    suspend fun selfTest(uid: String, count: Int, random: Random = Random): List<Question> {
        val lessons = content.allCourseLessons()
        val rows = progress.all(uid)
        val passedIds = passedLessons(lessons, rows.associateBy { it.lessonId }).map { it.id }
        if (passedIds.isEmpty()) return emptyList()
        val jlpt = lessons.associate { it.id to (it.jlpt ?: 5) }
        return selfTest(content.courseQuestionsOf(passedIds), missedKeys(rows), count, { jlpt[it] ?: 5 }, random)
    }

    /** Saves a lesson test. Returns the entry to take next (the one after this lesson if it is not done), or null. */
    suspend fun recordLessonTest(uid: String, lessonId: Int, answers: List<CourseAnswer>, at: Long): Int? {
        progress.upsert(progress.get(uid, lessonId).afterLessonTest(uid, lessonId, answers, at))
        val rows = progress.all(uid).associateBy { it.lessonId }
        return nextCourseEntry(content.allCourseLessons(), rows)?.id
    }

    /** Saves "Test yourself": only the missed-question lists of the lessons asked about change. */
    suspend fun recordSelfTest(uid: String, answers: List<CourseAnswer>, at: Long) {
        answers.map { it.lessonId }.distinct().forEach { lessonId ->
            progress.get(uid, lessonId)?.let { progress.upsert(it.afterSelfTest(answers, at)) }
        }
    }

    /** The learner has read a note: it counts as done. A note read before keeps its first time. */
    suspend fun markRead(uid: String, lessonId: Int, at: Long) {
        if (progress.get(uid, lessonId) == null) progress.upsert(noteRead(uid, lessonId, at))
    }
}
