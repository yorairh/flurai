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
        ContentInfoEntity::class,
    ],
    version = 1,
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

@Dao
interface ContentDao {
    @Query("SELECT jlpt, COUNT(*) AS count FROM word GROUP BY jlpt ORDER BY jlpt DESC")
    fun wordCountsByLevel(): Flow<List<LevelCount>>

    @Query("SELECT jlpt, COUNT(*) AS count FROM kanji GROUP BY jlpt ORDER BY jlpt DESC")
    fun kanjiCountsByLevel(): Flow<List<LevelCount>>

    @Query("SELECT * FROM word WHERE jlpt = :jlpt ORDER BY id")
    fun wordsAtLevel(jlpt: Int): Flow<List<WordEntity>>

    /** Matches the start of the written form or reading, or any part of the English meaning. */
    @Query(
        """
        SELECT * FROM word
        WHERE expression LIKE :query || '%' OR reading LIKE :query || '%' OR meanings LIKE '%' || :query || '%'
        ORDER BY jlpt DESC, id LIMIT 100
        """,
    )
    fun searchWords(query: String): Flow<List<WordEntity>>

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun word(id: Int): WordEntity?

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

    @Query("SELECT * FROM word WHERE id IN (:ids)")
    suspend fun words(ids: List<Int>): List<WordEntity>

    @Query("SELECT * FROM content_info")
    fun info(): Flow<List<ContentInfoEntity>>
}
