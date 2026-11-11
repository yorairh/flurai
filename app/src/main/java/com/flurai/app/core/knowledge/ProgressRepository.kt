package com.flurai.app.core.knowledge

import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import com.flurai.app.core.content.WordCache
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.grammar.GrammarProgressDao
import com.flurai.app.core.quiz.TestResultDao
import com.flurai.app.core.quiz.passedCheckpoints
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.StudyDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressRepository @Inject constructor(
    private val study: StudyDao,
    private val content: ContentDao,
    private val wordCache: WordCache,
    private val tests: TestResultDao,
    private val grammar: GrammarProgressDao,
) {
    @Volatile
    private var cachedCatalog: Catalog? = null

    /** All words and kanji, loaded once: the content never changes while the app runs. */
    suspend fun catalog(): Catalog = cachedCatalog ?: Catalog(
        words = wordCache.all().associateBy { it.id },
        kanjiLevels = content.kanjiLevels().associate { it.literal to it.jlpt },
        grammarLevels = content.allGrammarPoints().associate { it.id to it.jlpt },
    ).also { cachedCatalog = it }

    /** Recomputed whenever the user's cards, grammar answers or test results change. */
    fun snapshot(uid: String): Flow<ProgressSnapshot> =
        // Room re-emits on any write to these tables (other accounts, sync): skip identical lists.
        combine(
            study.allCards(uid).distinctUntilChanged(),
            grammar.progress(uid).distinctUntilChanged(),
            tests.results(uid).distinctUntilChanged(),
        ) { cards, grammarProgress, results ->
            computeProgress(cards, catalog(), grammarProgress, passedCheckpoints(results), Instant.now())
        }
            .flowOn(Dispatchers.Default)
            // While answering fast, only the latest state matters.
            .conflate()

    fun cards(uid: String): Flow<List<CardEntity>> = study.allCards(uid)
}
