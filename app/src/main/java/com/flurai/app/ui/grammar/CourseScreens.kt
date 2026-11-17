package com.flurai.app.ui.grammar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.content.CourseLessonEntity
import com.flurai.app.core.course.CourseBlock
import com.flurai.app.core.course.CourseStatus
import com.flurai.app.core.course.SELF_TEST_SIZES
import com.flurai.app.core.course.TrainCar
import com.flurai.app.core.course.isNote
import com.flurai.app.core.course.lessonRuby
import com.flurai.app.ui.components.BackTopAppBar
import com.flurai.app.ui.components.ExampleSentence
import com.flurai.app.ui.components.FuriganaText
import com.flurai.app.ui.components.MissingVoiceBanner
import com.flurai.app.ui.components.SentenceSpeakButton
import com.flurai.app.ui.components.rememberStudyScroll
import com.flurai.app.ui.theme.japanese

private const val CREDIT = "Written for FluRai after Cure Dolly's Organic Japanese video course."

// -- the Course tab --------------------------------------------------------------------------------

/** The recommended course: progress, the next lesson, "Test yourself" and every entry in order. */
@Composable
fun CourseTab(
    onOpenLesson: (Int) -> Unit,
    onSelfTest: (count: Int) -> Unit,
    onCheckup: (lastLessonId: Int) -> Unit,
    viewModel: CourseListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Nothing until the lessons are loaded (a list that starts with only its footer opens scrolled to the bottom).
    if (!state.loaded) return
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("Recommended course", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.passed} of ${state.lessons} lessons passed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "next") { NextEntryCard(state, onOpenLesson) }
        if (state.checkups.isNotEmpty()) item(key = "checkups") { CheckupsCard(state.checkups, onCheckup) }
        item(key = "self") { SelfTestCard(state.canSelfTest, onSelfTest) }
        item(key = "all") {
            Text(
                "All lessons, in course order",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
        }
        // The rows look like one card: round only the first and last.
        items(state.items.size, key = { state.items[it].lesson.id }) { index ->
            val item = state.items[index]
            val top = if (index == 0) 12.dp else 0.dp
            val bottom = if (index == state.items.lastIndex) 12.dp else 0.dp
            Box(Modifier.padding(bottom = bottom)) {
                Surface(shape = RoundedCornerShape(top, top, bottom, bottom), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column {
                        if (index > 0) HorizontalDivider()
                        CourseRow(item) { onOpenLesson(item.lesson.id) }
                    }
                }
            }
        }
        item(key = "footer") {
            Text(
                CREDIT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun NextEntryCard(state: CourseListState, onOpenLesson: (Int) -> Unit) {
    val next = state.next
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (next == null) {
                Text("You have finished the course", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Use Test yourself to keep it fresh.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    if (state.passed == 0) "Start here" else "Next · ${if (next.isNote()) "note" else "lesson"} ${next.number}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                FuriganaText(next.title, remember(next) { lessonRuby(next) }[next.title], MaterialTheme.typography.titleLarge.japanese())
                Text(next.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onOpenLesson(next.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.passed == 0) "Start the first lesson" else "Continue")
                }
            }
        }
    }
}

/** A checkup of 20 questions after every five lessons; each opens when its five lessons are passed. */
@Composable
private fun CheckupsCard(checkups: List<CheckupItem>, onCheckup: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Checkups", style = MaterialTheme.typography.titleMedium)
            Text(
                "20 questions about the last five lessons, after every fifth lesson.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            checkups.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = item.unlocked) { onCheckup(item.id) }.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lessons ${item.first} to ${item.last}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                !item.unlocked -> "Opens when you have passed these five lessons."
                                item.best != null -> "Best ${item.best}"
                                else -> "Ready"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (item.status == CourseStatus.Done) Icon(Icons.Filled.Check, contentDescription = "Passed", tint = MaterialTheme.colorScheme.primary)
                    if (item.unlocked) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelfTestCard(enabled: Boolean, onSelfTest: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Test yourself", style = MaterialTheme.typography.titleMedium)
            Text(
                if (enabled) "Pick how many questions: random ones from every lesson you passed, favoring the ones you missed." else "Opens after you pass your first lesson.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SELF_TEST_SIZES.forEach { size ->
                    AssistChip(onClick = { onSelfTest(size) }, enabled = enabled, label = { Text("$size") })
                }
            }
        }
    }
}

@Composable
private fun CourseRow(item: CourseItem, onClick: () -> Unit) {
    val lesson = item.lesson
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            lesson.number,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Column(Modifier.weight(1f)) {
            FuriganaText(lesson.title, remember(lesson) { lessonRuby(lesson) }[lesson.title], MaterialTheme.typography.titleMedium.japanese())
            Text(
                listOfNotNull(
                    if (lesson.isNote()) "Study note" else lesson.jlpt?.let { "N$it" },
                    item.best?.let { "best $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (item.status) {
            CourseStatus.Done -> Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
            CourseStatus.Tried -> Text("Tried", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CourseStatus.New -> {}
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

// -- a lesson --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseLessonScreen(
    onBack: () -> Unit,
    onTakeTest: (lessonId: Int) -> Unit,
    onOpenPoint: (grammarId: Int) -> Unit,
    onNext: (lessonId: Int) -> Unit,
    viewModel: CourseLessonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberStudyScroll(viewModel.clock)
    val lesson = state.lesson
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(
            title = { Text(lesson?.let { (if (it.isNote()) "Note " else "Lesson ") + it.number } ?: "Course") },
            onBack = onBack,
            actions = {
                // the test is one tap away without scrolling to the end of the lesson
                if (lesson != null && !lesson.isNote()) TextButton(onClick = { onTakeTest(lesson.id) }) { Text("Test") }
            },
        )
        MissingVoiceBanner(viewModel.speaker)
        if (lesson == null) return@Column
        Column(
            Modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val rubyOf = remember(lesson) { lessonRuby(lesson) }
            FuriganaText(lesson.title, rubyOf[lesson.title], MaterialTheme.typography.headlineSmall.japanese())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lesson.isNote()) {
                    Badge("Study note")
                } else {
                    lesson.jlpt?.let { Badge("N$it") }
                }
                if (state.status == CourseStatus.Done && !lesson.isNote()) Badge("Passed")
                state.best?.let { Text("Best $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.blocks.forEach { block -> BlockView(block, viewModel::speak) }

            if (state.related.isNotEmpty()) {
                Text("See also in the grammar reference", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.related.forEach { point ->
                        FilterChip(selected = false, onClick = { onOpenPoint(point.id) }, label = { Text(point.pattern, style = MaterialTheme.typography.labelLarge.japanese()) })
                    }
                }
            }

            if (!lesson.isNote()) {
                Button(onClick = { onTakeTest(lesson.id) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (state.status == CourseStatus.Done) "Take the test again" else "Take the test")
                }
            }
            val next = state.next
            if (next != null) {
                OutlinedButton(onClick = { onNext(next.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Next: ${next.title}",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge.japanese(),
                    )
                }
            }
            lesson.video?.let { video -> VideoCredit(video) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(video))) } }
            Text(CREDIT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VideoCredit(video: String, onOpen: () -> Unit) {
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Watch the original lesson") }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** Text with **bold** parts. */
internal fun boldMarkup(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}

@Composable
private fun BlockView(block: CourseBlock, speak: (String, Boolean) -> Unit) {
    when (block.type) {
        "heading" -> FuriganaText(block.text.orEmpty(), block.ruby, MaterialTheme.typography.titleMedium.japanese(), Modifier.padding(top = 8.dp))
        "text" -> FuriganaText(block.text.orEmpty(), block.ruby, MaterialTheme.typography.bodyLarge.japanese())
        "example" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ExampleSentence(block.jp.orEmpty(), block.ruby, MaterialTheme.typography.titleLarge.japanese())
                    Text(block.en.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                SentenceSpeakButton(onSpeak = { slow -> speak(spokenText(block.jp.orEmpty()), slow) })
            }
        }
        "diagram" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TrainDiagram(block.cars.orEmpty())
            block.text?.let { FuriganaText(it, block.ruby, MaterialTheme.typography.bodySmall.japanese(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        "keypoints" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(block.title ?: "Key points", style = MaterialTheme.typography.labelLarge)
                block.items.orEmpty().forEachIndexed { i, item ->
                    FuriganaText("•  $item", block.itemsRuby?.getOrNull(i)?.let { "•  $it" }, MaterialTheme.typography.bodyLarge.japanese())
                }
            }
        }
        "table" -> TableView(block)
    }
}

/** An example may mark an unspoken part in brackets, e.g. "(私が)食べる"; the audio says only what is written outside them. */
internal fun spokenText(jp: String): String = jp.replace(Regex("[（(][^）)]*[）)]"), "")

@Composable
private fun TableView(block: CourseBlock) {
    val rows = block.rows.orEmpty()
    if (rows.isEmpty()) return
    Column {
        block.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp)) }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        row.forEachIndexed { col, cell ->
                            val base = (if (index == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium).japanese()
                            FuriganaText(
                                cell,
                                block.rowsRuby?.getOrNull(index)?.getOrNull(col),
                                if (index == 0) base.copy(fontWeight = FontWeight.Bold) else base,
                                Modifier.width(110.dp).padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The course's train, drawn as a train: an engine (chimney, smoke, headlight) pulling carriages on a
 * rail, with wheels and couplings. The main carriage (the subject) is dark, the engine is the colored
 * one, a topic is green, and the zero pronoun is an empty, outlined car.
 */
@Composable
fun TrainDiagram(cars: List<TrainCar>) {
    val rail = MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .drawBehind {
                val railY = size.height - 6.dp.toPx()
                drawLine(rail, Offset(0f, railY), Offset(size.width, railY), 3.dp.toPx())
                var x = 4.dp.toPx()
                while (x < size.width) {
                    drawLine(rail, Offset(x, railY), Offset(x, railY + 5.dp.toPx()), 2.dp.toPx())
                    x += 14.dp.toPx()
                }
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val anyLabel = cars.any { it.label != null }
        cars.forEachIndexed { index, car ->
            if (index > 0) {
                Box(
                    Modifier.padding(bottom = 20.dp).width(12.dp).height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            Car(car, reserveLabel = anyLabel)
        }
    }
}

private val TrainBlack = Color(0xFF1F1F26)
private val TrainWhite = Color(0xFFF6F3EC)

@Composable
private fun Car(car: TrainCar, reserveLabel: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (background, content, border) = when (car.role) {
        "engine" -> Triple(scheme.primary, scheme.onPrimary, null)
        // the black carriage stays black and the white one white in both themes (as in the course's pictures)
        "carriage" -> Triple(TrainBlack, TrainWhite, BorderStroke(1.dp, TrainWhite.copy(alpha = 0.5f)))
        "topic" -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, null)
        "zero" -> Triple(scheme.surface, scheme.onSurfaceVariant, BorderStroke(1.dp, scheme.outline))
        "white" -> Triple(TrainWhite, TrainBlack, BorderStroke(1.dp, TrainBlack))
        "particle" -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, null)
        else -> Triple(scheme.surfaceContainerHighest, scheme.onSurface, null)
    }
    val engine = car.role == "engine"
    val smoke = scheme.outlineVariant
    val chimney = scheme.onSurfaceVariant
    val lamp = Color(0xFFFFC83D)
    val wheel = scheme.onSurface
    val shape = if (engine) {
        RoundedCornerShape(topStart = 8.dp, topEnd = 26.dp, bottomEnd = 8.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(8.dp)
    }
    Column(
        Modifier.drawBehind {
            if (engine) {
                // chimney and smoke above the boiler
                val cx = 22.dp.toPx()
                drawRect(chimney, Offset(cx - 5.dp.toPx(), 12.dp.toPx()), Size(10.dp.toPx(), 12.dp.toPx()))
                drawCircle(smoke, 4.dp.toPx(), Offset(cx + 2.dp.toPx(), 8.dp.toPx()))
                drawCircle(smoke, 5.dp.toPx(), Offset(cx + 9.dp.toPx(), 3.dp.toPx()))
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Box {
            Surface(shape = shape, color = background, contentColor = content, border = border) {
                Column(
                    Modifier.padding(start = 12.dp, end = if (engine) 20.dp else 12.dp, top = 8.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FuriganaText(car.text, car.ruby, MaterialTheme.typography.titleMedium.japanese())
                    // a car without a label keeps the line, so the cars of a train are equally tall and the couplings meet
                    if (car.label != null) Text(car.label, style = MaterialTheme.typography.labelSmall)
                    else if (reserveLabel) Text(" ", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (engine) {
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(8.dp)
                        .clip(CircleShape).background(lamp),
                )
            }
        }
        Row(
            Modifier.offset(y = (-6).dp),
            horizontalArrangement = Arrangement.spacedBy(if (engine) 28.dp else 22.dp),
        ) {
            repeat(2) { Box(Modifier.size(12.dp).clip(CircleShape).background(wheel)) }
        }
    }
}
