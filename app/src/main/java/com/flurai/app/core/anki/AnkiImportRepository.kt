package com.flurai.app.core.anki

import android.content.Context
import android.net.Uri
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.flurai.app.core.content.WordCache
import com.flurai.app.core.dictionary.DictionaryDao
import com.flurai.app.core.dictionary.WordSource
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.CustomWordDao
import com.flurai.app.core.study.CustomWordEntity
import com.flurai.app.core.study.DeckRepository
import com.flurai.app.core.study.ImportedDeck
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.sync.SyncDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reads an Anki file the learner picked and imports it ([AnkiImporter]) into this phone's database. */
@Singleton
class AnkiImportRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val words: WordCache,
    private val dictionary: DictionaryDao,
    private val wordSource: WordSource,
    private val customWords: CustomWordDao,
    private val cardDao: SyncDao,
    private val decks: DeckRepository,
) : AnkiImportStore {
    private val mutex = Mutex()

    /** Reads the package behind [uri] (from the file picker or "Open with"). Slow for a big collection: call it off the main thread. */
    suspend fun read(uri: Uri): AnkiCollection = withContext(Dispatchers.IO) {
        mutex.withLock {
            val resolver = context.contentResolver
            AnkiPackageReader.read(
                { resolver.openInputStream(uri) ?: throw AnkiException("Could not open the file.") },
                File(context.cacheDir, "anki-import"),
                AndroidSQLiteDriver(),
            ) { com.github.luben.zstd.ZstdInputStream(it) }
        }
    }

    // -- AnkiImportStore -------------------------------------------------------------------------

    private var builtIn: Map<Pair<String, String>, Int>? = null
    private var builtInByExpression: Map<String, List<Int>>? = null

    /**
     * Our word for [word]: a built-in word with that spelling and reading; else the dictionary entry that
     * can be written and read so (common entries first); else the only entry with that spelling; null
     * when none fits. A dictionary entry that is a built-in word becomes the built-in word.
     */
    override suspend fun match(word: NoteWord): Int? {
        val all = builtIn ?: words.all().associate { (it.expression to it.reading) to it.id }.also { builtIn = it }
        val byExpression = builtInByExpression ?: words.all().groupBy({ it.expression }, { it.id }).also { builtInByExpression = it }
        if (word.reading.isNotEmpty()) all[word.expression to word.reading]?.let { return it }
        else byExpression[word.expression]?.singleOrNull()?.let { return it }
        if (word.reading.isNotEmpty()) {
            dictionary.entriesWithFormAndReading(word.expression, word.reading).firstOrNull()?.let { return wordSource.resolve(it.id) }
        }
        val byForm = dictionary.entriesWithForm(word.expression)
        val fitting = if (word.reading.isEmpty()) byForm else byForm.filter { it.reading == word.reading }
        return (fitting.singleOrNull() ?: byForm.singleOrNull())?.let { wordSource.resolve(it.id) }
    }

    override suspend fun customWord(uid: String, id: Int) = customWords.get(uid, id)

    override suspend fun putCustomWords(words: List<CustomWordEntity>) = words.chunked(500).forEach { customWords.upsertAll(it) }

    override suspend fun card(uid: String, wordId: Int, kind: CardKind): CardEntity? = cardDao.card(uid, ItemType.Word, wordId, kind)

    override suspend fun putCards(cards: List<CardEntity>) = cards.forEach { cardDao.upsertCard(it) }

    override suspend fun importDeck(uid: String, source: String, name: String, wordIds: List<Int>): ImportedDeck =
        decks.importInto(uid, source, name, wordIds)
}
