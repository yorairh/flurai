package com.flurai.app.core.anki

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.CustomWordEntity
import com.flurai.app.core.study.ImportedDeck
import com.flurai.app.core.study.ItemType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** Imports the real Anki fixture (see make_fixtures.py) into an in-memory phone. */
class AnkiImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = Instant.now()

    /** The phone: what the dictionary knows, and what the database holds. */
    private class Phone : AnkiImportStore {
        val known = mapOf(
            ("食べる" to "たべる") to 101, ("学校" to "がっこう") to 102, ("寿司" to "すし") to 103,
            ("推し" to "おし") to 1_358_999, ("ラーメン" to "ラーメン") to 1_400_000, ("ふわふわ" to "ふわふわ") to 1_500_000,
        )
        val customs = LinkedHashMap<Int, CustomWordEntity>()
        val cards = LinkedHashMap<Triple<String, Int, CardKind>, CardEntity>()
        val decks = LinkedHashMap<String, Pair<String, MutableList<Int>>>()

        override suspend fun match(word: NoteWord) = known[word.expression to word.reading]
        override suspend fun customWord(uid: String, id: Int) = customs[id]
        override suspend fun putCustomWords(words: List<CustomWordEntity>) = words.forEach { customs[it.id] = it }
        override suspend fun card(uid: String, wordId: Int, kind: CardKind) = cards[Triple(uid, wordId, kind)]
        override suspend fun putCards(cards: List<CardEntity>) = cards.forEach { this.cards[Triple(it.uid, it.itemId, it.kind)] = it }
        override suspend fun importDeck(uid: String, source: String, name: String, wordIds: List<Int>): ImportedDeck {
            val (deckName, words) = decks.getOrPut(source) { name to mutableListOf() }
            val added = wordIds.distinct().filter { it !in words }
            words += added
            return ImportedDeck(source, deckName, added.size, words.size, isNew = added.size == words.size)
        }
        fun card(wordId: Int, kind: CardKind) = cards[Triple("u", wordId, kind)]
    }

    private val phone = Phone()

    private fun plan() = planImport(
        AnkiPackageReader.read({ File("src/test/resources/anki/japanese.apkg").inputStream() }, tmp.newFolder(), BundledSQLiteDriver(), ::unzstd),
        now,
    )

    private fun import(options: (AnkiPlan) -> AnkiImportOptions = { it.defaults }, listening: Boolean = false): AnkiImportResult = runBlocking {
        val plan = plan()
        AnkiImporter(phone).import("u", plan, options(plan), now, listening)
    }

    @Test
    fun theConfirmScreenStartsFromGoodGuesses() {
        val plan = plan()
        val type = plan.noteTypes.single()
        assertEquals(8, type.noteCount)
        assertEquals(FieldRoles(0, 1, 2), type.defaults.roles)
        assertEquals(mapOf(0 to TemplateKind.Recognition, 1 to TemplateKind.Production), type.defaults.kinds)
        assertTrue(type.defaults.include)
        assertEquals(2, plan.deckCount)
        assertEquals(1, plan.topDeckCount)
        assertEquals(2, plan.studiedCards)
    }

    @Test
    fun wordsTheDictionaryKnowsAreOursAndTheRestBecomeTheLearnersOwn() {
        val result = import()
        assertEquals(3, result.builtInWords)
        assertEquals(3, result.dictionaryWords)
        assertEquals(2, result.customWords)

        val sentence = phone.customs.values.single { it.expression == "猫が好きです。" }
        assertEquals(CustomWordEntity.KIND_SENTENCE, sentence.kind)
        assertEquals("ねこがすきです。", sentence.reading)
        val own = phone.customs.values.single { it.expression == "ぴよぴよ語" }
        assertEquals(CustomWordEntity.KIND_WORD, own.kind)
        assertEquals("a word no dictionary has", own.meaning)
        assertTrue(own.id < 0)
    }

    @Test
    fun oneDeckHoldsEveryWordStudiedOnesFirst() {
        val result = import()
        val deck = result.decks.single()
        assertEquals("Japanese", deck.name)
        val words = phone.decks.values.single().second
        assertEquals(8, words.size)
        assertEquals(listOf(101, 102), words.take(2)) // the two answered in Anki come first
    }

    @Test
    fun subdecksCanBecomeDecksOfTheirOwn() {
        val result = import({ p -> p.defaults.copy(perSubdeck = true) })
        assertEquals(setOf("Japanese", "Japanese / Food"), result.decks.map { it.name }.toSet())
        assertEquals(setOf(103, 1_400_000), phone.decks.values.single { it.first == "Japanese / Food" }.second.toSet())
    }

    @Test
    fun progressComesOverAndUnlocksTheOtherCardsOfALearnedWord() {
        import(listening = true)
        val eat = phone.card(101, CardKind.Recognition)!!
        assertEquals(SrsState.Review.value, eat.state)
        assertTrue(eat.stability!! > 0)
        // Answered Easy in Anki: learned, so its Production and Listening cards are waiting.
        assertEquals(0, phone.card(101, CardKind.Production)!!.reps)
        assertEquals(0, phone.card(101, CardKind.Listening)!!.reps)
        // 学校 was answered Good once: still learning.
        assertEquals(SrsState.Learning.value, phone.card(102, CardKind.Recognition)!!.state)
        assertNull(phone.card(102, CardKind.Production))
        // Sushi was suspended in Anki and stays out of the way.
        assertTrue(phone.card(103, CardKind.Recognition)!!.suspended)
        // Never answered: no card, the word comes up as new.
        assertNull(phone.card(1_358_999, CardKind.Recognition))
        // Cards are stamped now, so the next sync uploads them.
        assertTrue(phone.cards.values.all { it.createdAt > now.toEpochMilli() })
    }

    @Test
    fun withoutProgressEveryWordStartsNew() {
        val result = import({ p -> p.defaults.copy(keepProgress = false) })
        assertTrue(phone.cards.isEmpty())
        assertEquals(0, result.cardsWithProgress)
        assertEquals(8, phone.decks.values.single().second.size)
    }

    @Test
    fun aWordAlreadyAnsweredInFluRaiKeepsItsOwnProgress() {
        val mine = CardEntity("u", ItemType.Word, 101, CardKind.Recognition, 2, null, 30.0, 4.0, 1, 2, 9, 0, false, null, 1)
        runBlocking { phone.putCards(listOf(mine)) }
        val result = import()
        assertEquals(mine, phone.card(101, CardKind.Recognition))
        assertEquals(1, result.keptCards)
    }

    @Test
    fun importingAgainAddsNothingTwice() {
        import()
        val again = import()
        assertEquals(0, again.decks.single().added)
        assertEquals(2, phone.customs.size)
    }

    @Test
    fun aSentenceNeverGetsAProductionCard() {
        // Make the sentence look studied: map its note type's production template and check nothing is made.
        import()
        val sentence = phone.customs.values.single { it.kind == CustomWordEntity.KIND_SENTENCE }
        assertNull(phone.card(sentence.id, CardKind.Production))
    }

    @Test
    fun aNoteTypeLeftOutImportsNothing() {
        val result = import({ p -> p.defaults.copy(noteTypes = p.defaults.noteTypes.mapValues { it.value.copy(include = false) }) })
        assertTrue(result.decks.isEmpty())
        assertTrue(phone.cards.isEmpty() && phone.customs.isEmpty())
    }
}
