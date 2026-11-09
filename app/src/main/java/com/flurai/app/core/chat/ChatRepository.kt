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
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A running conversation with the tutor; the SDK keeps the history. */
class ChatSession internal constructor(private val chat: Chat) {
    /** Sends the learner's message ("START" opens the scenario) and returns the tutor's turn. */
    suspend fun send(message: String): AiTurn = parseTurn(chat.sendMessage(message).text.orEmpty())
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
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL,
            systemInstruction = content { text(systemPrompt(context, scenario)) },
        )
        return ChatSession(model.startChat())
    }

    companion object {
        /** Gemini model used through Firebase AI Logic (free tier, no billing needed). */
        const val MODEL = "gemini-3.8-flash"
        private const val MAX_WORDS = 60
        private const val MAX_GRAMMAR = 20
        private const val MAX_WEAK = 5
    }
}
