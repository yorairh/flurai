package com.flurai.app.core.chat

import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.grammar.GrammarProgressDao
import com.flurai.app.core.grammar.GrammarStatus
import com.flurai.app.core.grammar.grammarStatus
import com.flurai.app.core.knowledge.Knowledge
import com.flurai.app.core.knowledge.knowledge
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyDao
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A running conversation with the tutor. The history is kept here too, so that when a model is
 * overloaded the conversation can carry on with the next model.
 */
class ChatSession internal constructor(private val models: List<GenerativeModel>) {
    private val history = mutableListOf<Content>()
    private var chat: Chat = models.first().startChat()

    /** Sends the learner's message ("START" opens the scenario) and returns the tutor's turn. */
    suspend fun send(message: String): AiTurn {
        var failure: Exception? = null
        for (round in 0 until ROUNDS) {
            for (model in models) {
                try {
                    val text = chat.sendMessage(message).text.orEmpty()
                    history += content("user") { text(message) }
                    history += content("model") { text(text) }
                    return parseTurn(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isOverloaded(e)) throw e
                    failure = e
                    // Try the next model with the same conversation so far.
                    val next = models[(models.indexOf(model) + 1) % models.size]
                    chat = next.startChat(history.toList())
                }
            }
            if (round < ROUNDS - 1) delay(RETRY_DELAY_MS)
        }
        throw failure ?: IllegalStateException("No model answered")
    }

    private companion object {
        const val ROUNDS = 2
        const val RETRY_DELAY_MS = 2000L
    }
}

/** Google says a model is busy: worth retrying or switching, unlike a permission or quota error. */
internal fun isOverloaded(e: Exception): Boolean {
    val text = (e.message ?: "") + " " + e.javaClass.simpleName
    return listOf("high demand", "overloaded", "503", "unavailable").any { text.contains(it, ignoreCase = true) }
}

/**
 * Talks to Gemini through Firebase AI Logic: no API key lives in the app, and the Firebase project
 * decides what is allowed. The conversation is not saved anywhere.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val content: ContentDao,
    private val studyDao: StudyDao,
    private val grammar: GrammarProgressDao,
) {
    /** What the tutor should know about [uid]: their level, a sample of known words and grammar, and weak grammar. */
    suspend fun learnerContext(uid: String, level: JlptLevel): LearnerContext {
        val knownWordIds = studyDao.allCards(uid).first()
            .filter { it.itemType == ItemType.Word && it.kind == CardKind.Recognition }
            .filter { it.knowledge().let { k -> k == Knowledge.Known || k == Knowledge.Mature } }
            .map { it.itemId }
        val words = content.words(knownWordIds.shuffled().take(MAX_WORDS)).map { it.expression }
        val points = content.allGrammarPoints().associateBy { it.id }
        val progress = grammar.progress(uid).first()
        return LearnerContext(
            level = level,
            knownWords = words,
            knownGrammar = progress.filter { grammarStatus(it) == GrammarStatus.Known }.mapNotNull { points[it.grammarId]?.pattern }.take(MAX_GRAMMAR),
            weakGrammar = progress.filter { it.wrong > it.correct }.sortedByDescending { it.wrong - it.correct }
                .mapNotNull { points[it.grammarId]?.pattern }.take(MAX_WEAK),
        )
    }

    fun start(context: LearnerContext, scenario: Scenario): ChatSession {
        val ai = Firebase.ai(backend = GenerativeBackend.googleAI())
        val instruction = content { text(systemPrompt(context, scenario)) }
        return ChatSession(MODELS.map { ai.generativeModel(modelName = it, systemInstruction = instruction) })
    }

    companion object {
        /** Gemini models used through Firebase AI Logic (free tier), best first; the next is used when one is busy. */
        val MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite")
        private const val MAX_WORDS = 60
        private const val MAX_GRAMMAR = 20
        private const val MAX_WEAK = 5
    }
}
