package com.flurai.app.core.content

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        WordEntity::class,
        KanjiEntity::class,
        SentenceEntity::class,
        WordSentenceEntity::class,
        GrammarPointEntity::class,
        GrammarExampleEntity::class,
        ContentInfoEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class ContentDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao

    companion object {
        const val ASSET_PATH = "content.db"
    }
}

data class LevelCount(val jlpt: Int, val count: Int)

data class WordLevel(val id: Int, val jlpt: Int)

data class KanjiLevel(val literal: String, val jlpt: Int)

@Dao
interface ContentDao {
    @Query("SELECT jlpt, COUNT(*) AS count FROM word GROUP BY jlpt ORDER BY jlpt DESC")
    fun wordCountsByLevel(): Flow<List<LevelCount>>

    @Query("SELECT jlpt, COUNT(*) AS count FROM kanji GROUP BY jlpt ORDER BY jlpt DESC")
    fun kanjiCountsByLevel(): Flow<List<LevelCount>>

    @Query("SELECT * FROM word WHERE jlpt = :jlpt ORDER BY id")
    fun wordsAtLevel(jlpt: Int): Flow<List<WordEntity>>

    /**
     * Words that contain the query (or its kana reading, [kana] empty when the query is not romaji) in the
     * written form, reading or meaning. Easier words first; the ranking happens in [rankWords].
     */
    @Query(
        """
        SELECT * FROM word
        WHERE expression LIKE '%' || :query || '%' OR reading LIKE '%' || :query || '%' OR meanings LIKE '%' || :query || '%'
            OR (:kana != '' AND (expression LIKE '%' || :kana || '%' OR reading LIKE '%' || :kana || '%'))
        ORDER BY jlpt DESC, id LIMIT 800
        """,
    )
    fun searchCandidates(query: String, kana: String): Flow<List<WordEntity>>

    /** Kanji whose character, meaning or reading (without the okurigana dot) contains the query; ranked by [rankKanjiList]. */
    @Query(
        """
        SELECT * FROM kanji
        WHERE literal = :query OR meanings LIKE '%' || :query || '%'
            OR (:kana != '' AND (REPLACE(REPLACE(kunyomi, '.', ''), '-', '') LIKE '%' || :kana || '%' OR onyomi LIKE '%' || :kata || '%'))
        ORDER BY jlpt DESC, frequency LIMIT 300
        """,
    )
    fun searchKanji(query: String, kana: String, kata: String): Flow<List<KanjiEntity>>

    @Query("SELECT * FROM kanji WHERE literal = :literal")
    suspend fun kanji(literal: String): KanjiEntity?

    /** Words written with the kanji, easier first, for the kanji sheet. */
    @Query("SELECT * FROM word WHERE expression LIKE '%' || :literal || '%' ORDER BY jlpt DESC, id LIMIT 12")
    suspend fun wordsWithKanji(literal: String): List<WordEntity>

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun word(id: Int): WordEntity?

    /**
     * A simple word to show with a kana: one whose reading starts with it, a word written with kanji
     * first, easier levels first, then the shortest. Null for kana nothing starts with (ん, を).
     */
    @Query(
        """
        SELECT * FROM word WHERE reading LIKE :prefix || '%' AND expression NOT LIKE '%～%'
        ORDER BY (expression = reading), jlpt DESC, LENGTH(reading), id LIMIT 1
        """,
    )
    suspend fun wordStartingWith(prefix: String): WordEntity?

    /** The same for katakana: a loanword (written in kana only) that starts with it. */
    @Query(
        """
        SELECT * FROM word WHERE expression = reading AND expression LIKE :prefix || '%'
        ORDER BY jlpt DESC, LENGTH(reading), id LIMIT 1
        """,
    )
    suspend fun loanwordStartingWith(prefix: String): WordEntity?

    @Query(
        """
        SELECT s.* FROM sentence s JOIN word_sentence ws ON ws.sentence_id = s.id
        WHERE ws.word_id = :wordId ORDER BY ws.rank
        """,
    )
    suspend fun sentencesForWord(wordId: Int): List<SentenceEntity>

    @Query("SELECT * FROM kanji WHERE jlpt = :jlpt ORDER BY frequency IS NULL, frequency, literal")
    fun kanjiAtLevel(jlpt: Int): Flow<List<KanjiEntity>>

    /** Word ids at a level in teaching order: Genki lesson order first, then the rest of the list. */
    @Query("SELECT id FROM word WHERE jlpt = :jlpt ORDER BY genki_lesson IS NULL, genki_lesson, id")
    suspend fun wordIdsInStudyOrder(jlpt: Int): List<Int>

    @Query("SELECT id, jlpt FROM word")
    suspend fun wordLevels(): List<WordLevel>

    @Query("SELECT * FROM word")
    suspend fun allWords(): List<WordEntity>

    @Query("SELECT * FROM kanji")
    suspend fun allKanji(): List<KanjiEntity>

    @Query("SELECT literal, jlpt FROM kanji")
    suspend fun kanjiLevels(): List<KanjiLevel>

    @Query("SELECT * FROM word WHERE id IN (:ids)")
    suspend fun words(ids: List<Int>): List<WordEntity>

    /** Sentences for Reading practice: graded by the content pipeline (hardest word or grammar) and of reading length. */
    @Query("SELECT * FROM sentence WHERE jlpt IS NOT NULL AND length(japanese) BETWEEN 8 AND 30")
    suspend fun readingSentences(): List<SentenceEntity>

    @Query("SELECT * FROM grammar_point ORDER BY jlpt DESC, id")
    fun grammarPoints(): Flow<List<GrammarPointEntity>>

    @Query("SELECT * FROM grammar_point ORDER BY jlpt DESC, id")
    suspend fun allGrammarPoints(): List<GrammarPointEntity>

    @Query("SELECT * FROM grammar_point WHERE id = :id")
    suspend fun grammarPoint(id: Int): GrammarPointEntity?

    @Query("SELECT * FROM grammar_example WHERE grammar_id = :id ORDER BY rank")
    suspend fun grammarExamples(id: Int): List<GrammarExampleEntity>

    @Query("SELECT * FROM grammar_example")
    suspend fun allGrammarExamples(): List<GrammarExampleEntity>

    @Query("SELECT * FROM content_info")
    fun info(): Flow<List<ContentInfoEntity>>
}
