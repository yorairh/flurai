package com.flurai.app.core.content

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The read-only study content shipped in assets/content.db, built by tools/content/build_content.py.
 * Changing an entity changes the exported schema (app/schemas): bump ContentDatabase.version and
 * re-run the pipeline so the asset matches, or the app fails to open it.
 */

/** A vocabulary word from the JLPT lists. [jlpt] is the JLPT number: 5 = N5 (easiest) … 1 = N1. */
@Entity(
    tableName = "word",
    indices = [Index("jlpt"), Index("reading"), Index("expression")],
)
data class WordEntity(
    @PrimaryKey val id: Int,
    val expression: String,
    val reading: String,
    /** Other ways to write the word (e.g. 脚 for 足), separated by "; ". */
    @ColumnInfo(name = "other_forms") val otherForms: String?,
    /** English meanings as in the source list (separated by "; " or ", "). */
    val meanings: String,
    val jlpt: Int,
    /** Lesson number in Genki I/II (1–23) when the word is taught there. */
    @ColumnInfo(name = "genki_lesson") val genkiLesson: Int?,
    @ColumnInfo(name = "jmdict_id") val jmdictId: Int?,
    /** JMdict part-of-speech codes of the first sense, comma-separated (e.g. "v5r,vt"). */
    @ColumnInfo(name = "parts_of_speech") val partsOfSpeech: String?,
)

@Entity(tableName = "kanji", indices = [Index("jlpt")])
data class KanjiEntity(
    @PrimaryKey val literal: String,
    val jlpt: Int,
    val meanings: String,
    /** On'yomi (Chinese-derived readings) in katakana, separated by "、". */
    val onyomi: String,
    /** Kun'yomi (native readings) in hiragana, separated by "、"; "." marks okurigana. */
    val kunyomi: String,
    @ColumnInfo(name = "stroke_count") val strokeCount: Int,
    /** Japanese school grade (1–6 elementary, 8 secondary), if any. */
    val grade: Int?,
    /** Rank among the 2,500 most frequent kanji in newspapers, if ranked. */
    val frequency: Int?,
)

/** A Japanese sentence with its English translation, from Tatoeba. */
@Entity(tableName = "sentence")
data class SentenceEntity(
    @PrimaryKey val id: Int,
    val japanese: String,
    val english: String,
    /** Marked as a good example sentence in Tatoeba's Japanese index. */
    val verified: Boolean,
    /** JLPT number of the hardest word or grammar in the sentence (5 = N5 ... 1 = N1); null when it is not fit for reading practice. */
    val jlpt: Int? = null,
    /** Furigana as markup (a kanji run followed by its reading in braces: 来{き}ました), worked out by the content pipeline; null when unknown. */
    val ruby: String? = null,
)

@Entity(
    tableName = "word_sentence",
    primaryKeys = ["word_id", "sentence_id"],
    indices = [Index("sentence_id")],
)
data class WordSentenceEntity(
    @ColumnInfo(name = "word_id") val wordId: Int,
    @ColumnInfo(name = "sentence_id") val sentenceId: Int,
    /** Order to show examples in, best first. */
    val rank: Int,
)

/** A grammar point written for FluRai (tools/content/grammar.json). [jlpt] as for words: 5 = N5. */
@Entity(tableName = "grammar_point", indices = [Index("jlpt")])
data class GrammarPointEntity(
    @PrimaryKey val id: Int,
    val jlpt: Int,
    /** How it is written, e.g. "〜たことがある". */
    val pattern: String,
    val title: String,
    val meaning: String,
    val formation: String,
    val explanation: String,
)

/**
 * An example for a grammar point. [answer] is the part of [japanese] that shows the pattern; it
 * appears exactly once and is blanked out in fill-in-the-blank exercises.
 */
@Entity(
    tableName = "grammar_example",
    primaryKeys = ["grammar_id", "rank"],
)
data class GrammarExampleEntity(
    @ColumnInfo(name = "grammar_id") val grammarId: Int,
    val rank: Int,
    val japanese: String,
    val english: String,
    val answer: String,
    /** Furigana markup like [SentenceEntity.ruby]; null when unknown. */
    val ruby: String? = null,
)

/**
 * One entry of the grammar course (tools/content/course): a lesson, or a study note that has no test.
 * [jlpt] is the level the topic is usually met at (5 = N5), null for a note. [related] holds ids of
 * [GrammarPointEntity] rows, comma separated. [body] is the lesson text as JSON blocks (see core/course).
 */
@Entity(tableName = "course_lesson", indices = [Index("position")])
data class CourseLessonEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    /** The number the entry has in the course, e.g. "7.5". */
    val number: String,
    /** "lesson" (has a test) or "note". */
    val kind: String,
    val jlpt: Int?,
    val title: String,
    val summary: String,
    /** The original lesson's video, shown as credit; null when none. */
    val video: String?,
    val related: String,
    val body: String,
    /** JSON object with the furigana markup of `title` and `summary` (the pipeline writes it); null when none. */
    val ruby: String? = null,
)

/**
 * A hand-written test question of a course lesson. [type] is "choice" (pick one of [options]), "tap"
 * (tap one word of the sentence in [options]) or "order" ([options] are the pieces of the answer in
 * the right order and the app shuffles them). [options] is a JSON array of strings.
 */
@Entity(tableName = "course_question", primaryKeys = ["lesson_id", "id"])
data class CourseQuestionEntity(
    @ColumnInfo(name = "lesson_id") val lessonId: Int,
    val id: Int,
    val type: String,
    val instruction: String,
    val prompt: String,
    val hint: String?,
    val options: String,
    /** Index of the right option (choice and tap); 0 for order. */
    val answer: Int,
    /** One line shown after answering: why. */
    val note: String?,
    /** JSON object with the furigana markup of instruction, prompt, hint, options (array) and note; null when none. */
    val ruby: String? = null,
)

/** Build metadata: data versions, build date, row counts. */
@Entity(tableName = "content_info")
data class ContentInfoEntity(
    @PrimaryKey val key: String,
    val value: String,
)
