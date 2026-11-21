package com.flurai.app.core.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every word in the content database, loaded once and shared. The content never changes while the
 * app runs, so progress, quizzes, reading and media all read this one copy instead of each loading
 * the whole table.
 */
@Singleton
class WordCache @Inject constructor(private val content: ContentDao) {
    private val mutex = Mutex()

    @Volatile
    private var words: List<WordEntity>? = null

    @Volatile
    private var jmdictIndex: Map<Int, Int>? = null

    suspend fun all(): List<WordEntity> = words ?: mutex.withLock {
        words ?: withContext(Dispatchers.IO) { content.allWords() }.also { words = it }
    }

    /** JMdict entry id -> built-in word id, for the built-in words found in JMdict. */
    suspend fun byJmdictId(): Map<Int, Int> = jmdictIndex
        ?: all().mapNotNull { w -> w.jmdictId?.let { it to w.id } }.toMap().also { jmdictIndex = it }
}
