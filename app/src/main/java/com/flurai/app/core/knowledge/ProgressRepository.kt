package com.flurai.app.core.knowledge

import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.StudyDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressRepository @Inject constructor(
    private val study: StudyDao,
    private val content: ContentDao,
) {
    @Volatile
    private var cachedCatalog: Catalog? = null

    /** All words and kanji, loaded once: the content never changes while the app runs. */
    suspend fun catalog(): Catalog = cachedCatalog ?: Catalog(
        words = content.allWords().associateBy { it.id },
        kanjiLevels = content.kanjiLevels().associate { it.literal to it.jlpt },
    ).also { cachedCatalog = it }

    /** Recomputed whenever the user's cards change. */
    fun snapshot(uid: String, start: JlptLevel): Flow<ProgressSnapshot> =
        study.allCards(uid)
            .map { cards -> computeProgress(cards, catalog(), start, Instant.now()) }
            .flowOn(Dispatchers.Default)

    fun cards(uid: String): Flow<List<CardEntity>> = study.allCards(uid)
}
