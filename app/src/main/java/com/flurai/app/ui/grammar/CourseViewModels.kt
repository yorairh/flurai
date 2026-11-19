package com.flurai.app.ui.grammar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.uid
import com.flurai.app.core.content.CourseLessonEntity
import com.flurai.app.core.content.GrammarPointEntity
import com.flurai.app.core.course.CHECKUP_ROW_BASE
import com.flurai.app.core.course.CourseBlock
import com.flurai.app.core.course.checkupGroups
import com.flurai.app.core.course.checkupId
import com.flurai.app.core.course.checkupUnlocked
import com.flurai.app.core.course.CourseProgressEntity
import com.flurai.app.core.course.CourseRepository
import com.flurai.app.core.course.CourseStatus
import com.flurai.app.core.course.courseStatus
import com.flurai.app.core.course.entryAfter
import com.flurai.app.core.course.isNote
import com.flurai.app.core.course.nextCourseEntry
import com.flurai.app.core.course.parseBody
import com.flurai.app.core.course.passedLessons
import com.flurai.app.core.course.relatedPoints
import com.flurai.app.core.grammar.GrammarRepository
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.ui.CourseLessonRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the course list. [best] is the best score text ("9 of 10") once a test was taken. */
data class CourseItem(val lesson: CourseLessonEntity, val status: CourseStatus, val best: String?)

/** One checkup: after lessons [first] to [last] (their numbers); [id] is the last lesson's id. [best] is "17 of 20". */
data class CheckupItem(val id: Int, val first: String, val last: String, val unlocked: Boolean, val status: CourseStatus, val best: String?)

data class CourseListState(
    val loaded: Boolean = false,
    val items: List<CourseItem> = emptyList(),
    /** Lessons passed, and lessons in all (notes are not counted). */
    val passed: Int = 0,
    val lessons: Int = 0,
    val next: CourseLessonEntity? = null,
    /** "Test yourself" needs at least one lesson passed. */
    val canSelfTest: Boolean = false,
    val checkups: List<CheckupItem> = emptyList(),
)

private fun bestText(progress: CourseProgressEntity?): String? =
    progress?.takeIf { it.bestTotal > 0 }?.let { "${it.bestCorrect} of ${it.bestTotal}" }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CourseListViewModel @Inject constructor(
    auth: AuthRepository,
    course: CourseRepository,
) : ViewModel() {
    val state: StateFlow<CourseListState> = auth.currentUser.flatMapLatest { user ->
        val progress = if (user == null) flowOf(emptyList()) else course.progress(user.uid)
        combine(course.lessons(), progress) { lessons, rows ->
            val byId = rows.associateBy { it.lessonId }
            val passed = passedLessons(lessons, byId)
            CourseListState(
                loaded = true,
                items = lessons.map { CourseItem(it, courseStatus(byId[it.id]), bestText(byId[it.id])) },
                passed = passed.size,
                lessons = lessons.count { !it.isNote() },
                next = nextCourseEntry(lessons, byId),
                canSelfTest = passed.isNotEmpty(),
                checkups = checkupGroups(lessons).map { group ->
                    val row = byId[CHECKUP_ROW_BASE + checkupId(group)]
                    CheckupItem(checkupId(group), group.first().number, group.last().number, checkupUnlocked(group, byId), courseStatus(row), bestText(row))
                }.let { all -> all.filter { it.unlocked } + all.filter { !it.unlocked }.take(1) }, // the ones open, and the next to earn
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseListState())
}

data class CourseLessonState(
    val lesson: CourseLessonEntity? = null,
    val blocks: List<CourseBlock> = emptyList(),
    val status: CourseStatus = CourseStatus.New,
    val best: String? = null,
    /** The grammar reference points the lesson links to. */
    val related: List<GrammarPointEntity> = emptyList(),
    /** The entry after this one in course order; null after the last. */
    val next: CourseLessonEntity? = null,
    /** Where the learner left off: the first entry not done, never this lesson. Null when it is [next] or nothing is left. */
    val resume: CourseLessonEntity? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CourseLessonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val course: CourseRepository,
    private val grammar: GrammarRepository,
    activity: StudyActivityRepository,
    val speaker: JapaneseSpeaker,
) : ViewModel() {
    /** Reading a lesson counts as study time. */
    val clock = StudyClock(activity, { auth.uid })

    private val id = savedStateHandle.toRoute<CourseLessonRoute>().id
    private val _state = MutableStateFlow(CourseLessonState())
    val state: StateFlow<CourseLessonState> = _state

    fun speak(text: String, slow: Boolean) = speaker.speak(text, slow)

    init {
        clock.start()
        speaker.start()
        viewModelScope.launch {
            val lesson = course.lesson(id) ?: return@launch
            val related = lesson.relatedPoints().mapNotNull { grammar.point(it) }
            _state.update { it.copy(lesson = lesson, blocks = parseBody(lesson.body), related = related) }
            // A note has no test: opening it is reading it.
            auth.uid?.takeIf { lesson.isNote() }?.let { course.markRead(it, id, System.currentTimeMillis()) }
        }
        viewModelScope.launch {
            val uid = auth.uid ?: return@launch
            combine(course.lessons(), course.progress(uid)) { lessons, rows ->
                val byId = rows.associateBy { it.lessonId }
                val next = entryAfter(lessons, id)
                val resume = nextCourseEntry(lessons.filter { it.id != id }, byId)?.takeIf { it.id != next?.id }
                Triple(byId[id], next, resume)
            }.collect { (mine, next, resume) ->
                _state.update { it.copy(status = courseStatus(mine), best = bestText(mine), next = next, resume = resume) }
            }
        }
    }
}
