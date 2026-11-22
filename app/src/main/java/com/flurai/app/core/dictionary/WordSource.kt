package com.flurai.app.core.dictionary

import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.SearchQuery
import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.rankWords
import com.flurai.app.core.content.WordCache
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.study.CustomWordDao
import com.flurai.app.core.study.CustomWordEntity
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Word ids, as cards, decks and progress store them:
 *   1 ..< 1,000,000   a built-in JLPT word (content.db `word.id`, from tools/content/word_ids.tsv)
 *   >= 1,000,000      any other JMdict entry, by its JMdict sequence number (dictionary.db)
 *   < 0               a word or sentence of the learner's own (user.db `custom_word`, from an Anki import)
 * A JMdict entry that is also a built-in word is always stored as the built-in id ([WordSource.resolve]).
 */

/** The first id of a dictionary (JMdict) word; built-in ids stay below it. */
const val DICTIONARY_ID_START = 1_000_000

/** [WordEntity.jlpt] of a word that is not on the JLPT lists (a dictionary word). */
const val NOT_JLPT = 0

fun isDictionaryId(id: Int): Boolean = id >= DICTIONARY_ID_START

fun isCustomId(id: Int): Boolean = id < 0

/** True for a word on the JLPT lists (levels N5..N1). */
val WordEntity.isJlpt: Boolean get() = jlpt != NOT_JLPT

/** "N5" for a JLPT word, "Not JLPT" for a dictionary word, "Your word" for one of the learner's own. */
val WordEntity.levelLabel: String get() = when {
    isJlpt -> "N$jlpt"
    isCustomId(id) -> "Your word"
    else -> "Not JLPT"
}

/** A word of the learner's own in the shape every word screen uses. */
fun CustomWordEntity.asWord(): WordEntity = WordEntity(
    id = id,
    expression = expression,
    reading = reading,
    otherForms = null,
    meanings = meaning,
    jlpt = NOT_JLPT,
    genkiLesson = null,
    jmdictId = null,
    partsOfSpeech = null,
    ruby = null,
)

/**
 * The full-text query for an English search: its words, all required ("to eat" matches both words).
 * Empty when the query has no latin words (a Japanese search does not use the full-text index).
 */
fun ftsQuery(text: String): String =
    Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.joinToString(" ")

/** A dictionary entry in the shape every word screen uses. */
fun DictEntryEntity.asWord(): WordEntity = WordEntity(
    id = id,
    expression = expression,
    reading = reading,
    otherForms = otherForms,
    meanings = meanings,
    jlpt = NOT_JLPT,
    genkiLesson = null,
    jmdictId = id,
    partsOfSpeech = partsOfSpeech,
    ruby = ruby,
)

/** A dictionary example as a sentence. Its id is the negated entry id so it never clashes with Tatoeba ids in content.db. */
fun DictExampleEntity.asSentence(): SentenceEntity =
    SentenceEntity(id = -entryId, japanese = japanese, english = english, verified = false, jlpt = null, ruby = ruby)

/** Looks a word id up in the right database: content.db for built-in words, dictionary.db for the rest. */
@Singleton
class WordSource @Inject constructor(
    private val content: ContentDao,
    private val dictionary: DictionaryDao,
    private val cache: WordCache,
    private val custom: CustomWordDao,
) {
    suspend fun word(id: Int): WordEntity? = when {
        isCustomId(id) -> custom.anyById(id)?.asWord()
        isDictionaryId(id) -> dictionary.entry(id)?.asWord()
        else -> content.word(id)
    }

    /** The words for [ids] (missing ones left out), in no particular order. */
    suspend fun words(ids: Collection<Int>): List<WordEntity> {
        val all = ids.distinct()
        val own = all.filter(::isCustomId)
        val dict = all.filter(::isDictionaryId)
        val builtIn = all.filter { it > 0 && !isDictionaryId(it) }
        return builtIn.chunked(CHUNK).flatMap { content.words(it) } +
            dict.chunked(CHUNK).flatMap { chunk -> dictionary.entries(chunk).map { it.asWord() } } +
            own.chunked(CHUNK).flatMap { chunk -> custom.anyByIds(chunk).map { it.asWord() } }
    }

    /** Example sentences, best first: up to three for a built-in word, at most one for a dictionary word, none for the learner's own. */
    suspend fun sentences(id: Int): List<SentenceEntity> = when {
        isCustomId(id) -> emptyList()
        isDictionaryId(id) -> listOfNotNull(dictionary.example(id)?.asSentence())
        else -> content.sentencesForWord(id)
    }

    /**
     * Words for a library search, best first ([rankWords]): built-in words and the rest of JMdict. An entry
     * that is a built-in word appears once, as the built-in word; on equal rank built-in words come first.
     */
    suspend fun search(query: SearchQuery, builtIn: List<WordEntity>): List<WordEntity> {
        if (query.text.isEmpty()) return emptyList()
        val builtInIds = cache.byJmdictId()
        val fromDictionary = dictionary.searchCandidates(query.text, query.kana, ftsQuery(query.text))
            .filter { it.id !in builtInIds }
            .map { it.asWord() }
        return rankWords(builtIn + fromDictionary, query)
    }

    /** The id to store for JMdict entry [jmdictId]: the built-in word when there is one, else the entry itself. */
    suspend fun resolve(jmdictId: Int): Int = cache.byJmdictId()[jmdictId] ?: jmdictId

    private companion object {
        const val CHUNK = 500 // SQLite's limit on bound parameters is 999
    }
}
