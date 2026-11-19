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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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

/** How many questions a lesson test asks (all of them from the lesson itself). */
const val LESSON_TEST_SIZE = 10

/** A checkup follows every [CHECKUP_EVERY] lessons and asks [CHECKUP_SIZE] questions about those lessons. */
const val CHECKUP_EVERY = 5
const val CHECKUP_SIZE = 20

/** A checkup's progress is saved as a course_progress row with this id plus the id of its last lesson. */
const val CHECKUP_ROW_BASE = 100_000

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

/** The entry after [id] in course order, or null after the last one (the "Next" button goes forward, whatever is passed). */
fun entryAfter(lessons: List<CourseLessonEntity>, id: Int): CourseLessonEntity? {
    val ordered = lessons.sortedBy { it.position }
    val index = ordered.indexOfFirst { it.id == id }
    return if (index < 0) null else ordered.getOrNull(index + 1)
}

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

/** The progress after a checkup: its score, kept like a lesson's best, passed at [COURSE_PASS_FRACTION]. */
fun CourseProgressEntity?.afterCheckup(uid: String, rowId: Int, correct: Int, total: Int, at: Long): CourseProgressEntity {
    val better = this == null || bestTotal == 0 || correct.toDouble() / total.coerceAtLeast(1) > bestCorrect.toDouble() / bestTotal
    val passedNow = total > 0 && correct.toDouble() / total >= COURSE_PASS_FRACTION
    return CourseProgressEntity(
        uid = uid, lessonId = rowId,
        bestCorrect = if (better) correct else this!!.bestCorrect,
        bestTotal = if (better) total else this!!.bestTotal,
        passedAt = this?.passedAt ?: if (passedNow) at else null,
        attempts = (this?.attempts ?: 0) + 1, missed = "", lastAt = at,
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
data class TrainCar(val text: String, val role: String = "plain", val label: String? = null, val ruby: String? = null)

/** One line of a dialogue block: who says it ("A", "B"), the Japanese, its English and the furigana markup of the Japanese. */
@Serializable
data class DialogueLine(val who: String, val jp: String, val en: String, val ruby: String? = null)

/**
 * One block of a lesson. [type]: "heading" ([text]), "text" ([text]; **bold** marks emphasis), "example"
 * ([jp], [en], [ruby]), "diagram" ([cars], [text] as caption), "keypoints" ([title], [items]),
 * "table" ([title], [rows], the first row is the header) and "dialogue" ([lines], two or more speakers).
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
    /** Furigana markup of each of [items] and of each cell of [rows] (null where a text has no kanji). */
    val itemsRuby: List<String?>? = null,
    val rowsRuby: List<List<String?>>? = null,
    val lines: List<DialogueLine>? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** The blocks of a lesson body; empty when the stored text cannot be read. */
fun parseBody(body: String): List<CourseBlock> =
    runCatching { json.decodeFromString(ListSerializer(CourseBlock.serializer()), body) }.getOrDefault(emptyList())

/**
 * Furigana markup stored as a JSON object, as a map from the plain text to its markup: `{"title": "...",
 * "options": [...]}`. [lists] names the keys that hold arrays matching their plain texts by position. Texts
 * without kanji have no entry, so a lookup that misses just draws the text plain.
 */
internal fun rubyMap(stored: String?, texts: Map<String, String?>, lists: Map<String, List<String>> = emptyMap()): Map<String, String> {
    if (stored == null) return emptyMap()
    val root = runCatching { json.parseToJsonElement(stored).jsonObject }.getOrNull() ?: return emptyMap()
    val out = HashMap<String, String>()
    texts.forEach { (key, plain) ->
        val markup = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (plain != null && markup != null) out[plain] = markup
    }
    lists.forEach { (key, plains) ->
        val array = root[key] as? JsonArray ?: return@forEach
        plains.forEachIndexed { i, plain -> (array.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { out[plain] = it } }
    }
    return out
}

/** Furigana markup of a lesson's title and summary, by plain text. */
fun lessonRuby(lesson: CourseLessonEntity): Map<String, String> =
    rubyMap(lesson.ruby, mapOf("title" to lesson.title, "summary" to lesson.summary))

private fun parseAlternatives(alt: String?): Set<String> =
    runCatching { json.decodeFromString(ListSerializer(ListSerializer(String.serializer())), alt ?: "[]") }
        .getOrDefault(emptyList()).map { it.joinToString("") }.toSet()

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
    var ruby = rubyMap(
        entity.ruby,
        mapOf("instruction" to entity.instruction, "prompt" to entity.prompt, "hint" to entity.hint, "note" to entity.note),
        mapOf("options" to options),
    )
    if (entity.type == "order" && options.any { it in ruby }) {
        // the right arrangement is the pieces joined: its markup is the pieces' markup joined
        ruby = ruby + (options.joinToString("") to options.joinToString("") { ruby[it] ?: it })
    }
    return when (entity.type) {
        "choice" -> {
            if (options.size < 2 || entity.answer !in options.indices) return null
            val right = options[entity.answer]
            val shown = if (options.size > 2) options.shuffled(random) else options
            Question(
                kind = QuestionKind.CourseChoice, prompt = entity.prompt, hint = entity.hint, options = shown,
                answer = shown.indexOf(right), level = jlpt, note = entity.note, instruction = entity.instruction,
                courseLesson = key.first, courseQuestion = key.second, ruby = ruby, translation = entity.en,
            )
        }
        "tap" -> {
            if (options.size < 2 || entity.answer !in options.indices) return null
            Question(
                kind = QuestionKind.CourseTap, prompt = options.joinToString(""), hint = entity.hint, options = options,
                answer = entity.answer, level = jlpt, note = entity.note, instruction = entity.instruction,
                courseLesson = key.first, courseQuestion = key.second, ruby = ruby, translation = entity.en,
            )
        }
        "order" -> {
            if (options.size < 2) return null
            val alternatives = parseAlternatives(entity.alt)
            var tiles = options.shuffled(random)
            var tries = 0
            while (tiles == options && tries++ < 10) tiles = options.shuffled(random)
            Question(
                kind = QuestionKind.CourseOrder, prompt = entity.prompt, hint = entity.hint, options = options,
                answer = 0, level = jlpt, note = entity.note, instruction = entity.instruction, tiles = tiles,
                correctText = options.joinToString(""), courseLesson = key.first, courseQuestion = key.second, ruby = ruby, translation = entity.en,
                alsoCorrect = alternatives,
            )
        }
        else -> null
    }
}

/** The questions of one lesson's test: up to [LESSON_TEST_SIZE] of the lesson's own, with few repeated answers. */
fun lessonTest(
    own: List<CourseQuestionEntity>,
    jlptOf: (Int) -> Int,
    random: Random,
): List<Question> =
    variedPick(own.shuffled(random), LESSON_TEST_SIZE).shuffled(random).mapNotNull { courseQuestion(it, jlptOf(it.lessonId), random) }

/** The lessons (not notes) in course order, cut into complete groups of [CHECKUP_EVERY]; each group has a checkup. */
fun checkupGroups(lessons: List<CourseLessonEntity>): List<List<CourseLessonEntity>> =
    lessons.filter { !it.isNote() }.sortedBy { it.position }.chunked(CHECKUP_EVERY).filter { it.size == CHECKUP_EVERY }

/** The id of the checkup after [group]: its last lesson. */
fun checkupId(group: List<CourseLessonEntity>): Int = group.last().id

/** A checkup opens once every lesson of its group is passed. */
fun checkupUnlocked(group: List<CourseLessonEntity>, progress: Map<Int, CourseProgressEntity>): Boolean =
    group.all { courseStatus(progress[it.id]) == CourseStatus.Done }

/**
 * A checkup: [CHECKUP_SIZE] questions spread evenly over the lessons of its group ([byLesson], one list per
 * lesson), the ones missed before first. If a lesson has too few, the others make up the number.
 */
fun checkupTest(
    byLesson: List<List<CourseQuestionEntity>>,
    missed: Set<Pair<Int, Int>>,
    jlptOf: (Int) -> Int,
    random: Random,
): List<Question> {
    if (byLesson.isEmpty()) return emptyList()
    val ordered = byLesson.map { qs -> qs.shuffled(random).sortedBy { (it.lessonId to it.id) !in missed } }
    val share = CHECKUP_SIZE / ordered.size
    val chosen = ordered.flatMap { variedPick(it, share) }.toMutableList()
    val rest = ordered.flatten().filter { it !in chosen }.shuffled(random)
    chosen += rest.take(CHECKUP_SIZE - chosen.size)
    return chosen.shuffled(random).mapNotNull { courseQuestion(it, jlptOf(it.lessonId), random) }
}

/** At most this many questions of one test may have the same right answer (three gaps that all want を feel like a drill). */
internal const val SAME_ANSWER_LIMIT = 2

/** The right answer's text of a choice or tap question; null for an order question (no limit applies). */
private fun answerText(question: CourseQuestionEntity): String? =
    if (question.type == "order") null else parseOptions(question.options).getOrNull(question.answer)

/**
 * The first [count] of [shuffled], but no more than [SAME_ANSWER_LIMIT] with the same right answer while
 * others are available; if that leaves the test short, the held-back ones fill it up.
 */
private fun variedPick(shuffled: List<CourseQuestionEntity>, count: Int): List<CourseQuestionEntity> {
    val seen = HashMap<String, Int>()
    val (kept, held) = shuffled.partition { q ->
        val key = answerText(q) ?: return@partition true
        val n = seen.merge(key, 1, Int::plus)!!
        n <= SAME_ANSWER_LIMIT
    }
    return (kept + held).take(count)
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
        val jlpt = lessons.associate { it.id to (it.jlpt ?: 5) }
        return lessonTest(content.courseQuestions(lessonId), { jlpt[it] ?: 5 }, random)
    }

    /** The questions of the checkup ending with lesson [lastLessonId], see [checkupTest]. Empty when it is not a checkup. */
    suspend fun checkupTest(uid: String, lastLessonId: Int, random: Random = Random): List<Question> {
        val lessons = content.allCourseLessons()
        val group = checkupGroups(lessons).firstOrNull { checkupId(it) == lastLessonId } ?: return emptyList()
        val jlpt = lessons.associate { it.id to (it.jlpt ?: 5) }
        val questions = content.courseQuestionsOf(group.map { it.id })
        return checkupTest(group.map { lesson -> questions.filter { it.lessonId == lesson.id } }, missedKeys(progress.all(uid)), { jlpt[it] ?: 5 }, random)
    }

    /** Saves a checkup: its score on its own row, and the missed questions of the lessons it asked about. */
    suspend fun recordCheckup(uid: String, lastLessonId: Int, answers: List<CourseAnswer>, at: Long) {
        val rowId = CHECKUP_ROW_BASE + lastLessonId
        progress.upsert(progress.get(uid, rowId).afterCheckup(uid, rowId, answers.count { it.correct }, answers.size, at))
        recordSelfTest(uid, answers, at)
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

// -- table layout ----------------------------------------------------------------------------------

/** The width of each table column in dp, and whether the table is wider than the available width and must scroll. */
data class TableLayout(val widths: List<Float>, val scrolls: Boolean)

private const val TABLE_CELL_PADDING_DP = 20f
private const val TABLE_EM_DP = 15f
private const val TABLE_MIN_COLUMN_DP = 64f
private const val TABLE_MAX_COLUMN_DP = 220f

/** Longest line of a cell in "ems" (Japanese characters are 1, others about half); a long cell wraps, so it is capped. */
private fun cellEms(cell: String): Float =
    cell.replace("**", "").lines().maxOf { line -> line.sumOf { c -> if (c.code >= 0x2E80) 1.0 else 0.55 }.toFloat() }.coerceAtMost(14f)

/**
 * Columns sized to their content. When they fit in [availableDp] they are stretched to fill it exactly (so a
 * table is always full width); when they do not, they keep their own width and the table scrolls sideways.
 */
fun tableLayout(rows: List<List<String>>, availableDp: Float): TableLayout {
    if (rows.isEmpty()) return TableLayout(emptyList(), false)
    val columns = rows.maxOf { it.size }
    val ideal = (0 until columns).map { col ->
        val ems = rows.maxOf { cellEms(it.getOrElse(col) { "" }) }
        (ems * TABLE_EM_DP + TABLE_CELL_PADDING_DP).coerceIn(TABLE_MIN_COLUMN_DP, TABLE_MAX_COLUMN_DP)
    }
    val total = ideal.sum()
    return if (total <= availableDp) TableLayout(ideal.map { it * availableDp / total }, false) else TableLayout(ideal, true)
}
