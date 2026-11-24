package com.flurai.app.core.anki

import com.flurai.app.core.dictionary.DICTIONARY_ID_START
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.CustomWordEntity
import com.flurai.app.core.study.ImportedDeck
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyDay
import java.time.Instant

// -- the choices on the confirm screen --------------------------------------------------------------

/** What to do with one note type: whether to import it, which fields are which, and what each template becomes. */
data class NoteTypeChoice(val include: Boolean, val roles: FieldRoles, val kinds: Map<Int, TemplateKind>)

data class AnkiImportOptions(
    val noteTypes: Map<Long, NoteTypeChoice>,
    /** One FluRai deck per Anki deck and subdeck, instead of one per top-level deck. */
    val perSubdeck: Boolean = false,
    /** Bring the cards' scheduling over (else every word starts as new). */
    val keepProgress: Boolean = true,
    /** Spread cards that are already due over the next week or two. */
    val spreadOverdue: Boolean = true,
)

/** One note type as the confirm screen shows it, with the guesses as defaults. */
data class NoteTypePlan(val type: AnkiNoteType, val noteCount: Int, val samples: List<AnkiNote>, val defaults: NoteTypeChoice)

data class AnkiPlan(
    val collection: AnkiCollection,
    val noteTypes: List<NoteTypePlan>,
    /** Cards that were answered in Anki (they bring progress). */
    val studiedCards: Int,
    /** Of those, the ones already due. */
    val overdueCards: Int,
    /** How many Anki decks hold cards, and how many top-level decks. */
    val deckCount: Int,
    val topDeckCount: Int,
) {
    val defaults = AnkiImportOptions(noteTypes.associate { it.type.id to it.defaults })
}

fun planImport(c: AnkiCollection, now: Instant): AnkiPlan {
    val notesByType = c.notes.groupBy { it.typeId }
    val types = c.noteTypes.filter { notesByType[it.id].orEmpty().isNotEmpty() }.map { type ->
        val notes = notesByType.getValue(type.id)
        val samples = notes.take(3)
        val roles = guessFieldRoles(type.fields, notes.take(30))
        val kinds = type.templates.associate { it.ord to if (type.cloze) TemplateKind.Ignore else guessTemplateKind(it, type.fields, roles) }
        NoteTypePlan(type, notes.size, samples, NoteTypeChoice(include = !type.cloze && roles.expression != null, roles = roles, kinds = kinds))
    }
    val studied = c.cards.filter { it.type != 0 }
    val usedDecks = c.cards.map { it.deckId }.toSet()
    val names = c.decks.filter { it.id in usedDecks }.map { it.name }
    return AnkiPlan(
        collection = c, noteTypes = types, studiedCards = studied.size,
        overdueCards = studied.count { it.queue >= 0 && ankiDue(it, c.createdSecs) <= now },
        deckCount = names.size, topDeckCount = names.map { it.substringBefore("::") }.distinct().size,
    )
}

// -- doing it -------------------------------------------------------------------------------------

/** What the importer needs from the phone. [AnkiImportRepository] in the app, a fake in tests. */
interface AnkiImportStore {
    /** Our word for a note's word (built-in, else dictionary), or null when the dictionary has none. */
    suspend fun match(word: NoteWord): Int?
    suspend fun customWord(uid: String, id: Int): CustomWordEntity?
    suspend fun putCustomWords(words: List<CustomWordEntity>)
    suspend fun card(uid: String, wordId: Int, kind: CardKind): CardEntity?
    suspend fun putCards(cards: List<CardEntity>)
    suspend fun importDeck(uid: String, source: String, name: String, wordIds: List<Int>): ImportedDeck
}

data class AnkiImportResult(
    val decks: List<ImportedDeck>,
    val builtInWords: Int,
    val dictionaryWords: Int,
    val customWords: Int,
    /** Notes left out: no Japanese in the word field. */
    val skippedNotes: Int,
    val cardsWithProgress: Int,
    /** Cards FluRai already had answers for, kept as they were. */
    val keptCards: Int,
)

class AnkiImporter(private val store: AnkiImportStore) {
    /**
     * Imports [plan]'s collection with [options] for [uid]. [listening]: the learner has Listening cards on, so a
     * word that comes over already learned also gets its Listening card (as when it is learned in FluRai).
     */
    suspend fun import(
        uid: String,
        plan: AnkiPlan,
        options: AnkiImportOptions,
        now: Instant,
        listening: Boolean,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): AnkiImportResult {
        val c = plan.collection
        val typeById = c.noteTypes.associateBy { it.id }
        val cardsByNote = c.cards.groupBy { it.noteId }
        val reviewsByCard = c.reviews.groupBy { it.cardId }
        val deckName = c.decks.associate { it.id to it.name }
        val nowMs = now.toEpochMilli()
        var stamp = nowMs // creation times a millisecond apart: sync sends cards in this order

        // 1. Each note's word: ours when the dictionary knows it, else a word of the learner's own.
        val wordOfNote = HashMap<Long, Int>()
        val sentences = HashSet<Int>()
        val customs = LinkedHashMap<Int, CustomWordEntity>()
        var builtIn = 0; var dictionary = 0; var skipped = 0
        val notes = c.notes.filter { options.noteTypes[it.typeId]?.include == true }
        notes.forEachIndexed { i, note ->
            if (i % 50 == 0) progress(i, notes.size)
            val word = noteWord(note, options.noteTypes.getValue(note.typeId).roles)
            if (word == null) { skipped++; return@forEachIndexed }
            val matched = matchVariants(word).firstNotNullOfOrNull { store.match(it) }
            val sentence = matched == null && looksLikeSentence(word.expression)
            val id = matched ?: customId(uid, word, customs)
            if (matched == null) {
                customs.getOrPut(id) {
                    CustomWordEntity(
                        uid = uid, id = id, kind = if (sentence) CustomWordEntity.KIND_SENTENCE else CustomWordEntity.KIND_WORD,
                        expression = word.expression, reading = word.reading, meaning = word.meaning, ankiGuid = note.guid,
                        createdAt = nowMs, updatedAt = nowMs,
                    )
                }
                if (sentence) sentences += id
            } else if (matched >= DICTIONARY_ID_START) dictionary++ else builtIn++
            wordOfNote[note.id] = id
        }
        progress(notes.size, notes.size)
        store.putCustomWords(customs.values.toList())

        // 2. Cards with their Anki progress (a word FluRai has already answered keeps FluRai's).
        val seeded = HashMap<Pair<Int, CardKind>, Pair<CardEntity, Int>>() // value: card and its Anki reps, the busiest wins
        val suspendedNew = HashSet<Int>()
        if (options.keepProgress) {
            for (note in notes) {
                val wordId = wordOfNote[note.id] ?: continue
                val type = typeById[note.typeId] ?: continue
                val kinds = options.noteTypes.getValue(note.typeId).kinds
                for (card in cardsByNote[note.id].orEmpty()) {
                    val kind = kinds[card.ord]?.card
                    if (type.cloze || kind == null || (kind == CardKind.Production && wordId in sentences)) continue
                    val seed = seedCard(card, reviewsByCard[card.id].orEmpty(), c.createdSecs)
                    if (seed == null) {
                        if (card.queue == -1 && kind == CardKind.Recognition) suspendedNew += wordId
                        continue
                    }
                    val entity = CardEntity(
                        uid = uid, itemType = ItemType.Word, itemId = wordId, kind = kind, state = seed.srs.state.value, step = seed.srs.step,
                        stability = seed.srs.stability, difficulty = seed.srs.difficulty, due = seed.srs.due.toEpochMilli(),
                        lastReview = seed.srs.lastReview?.toEpochMilli(), reps = seed.reps, lapses = seed.lapses,
                        suspended = seed.suspended, buriedUntil = null, createdAt = 0,
                    )
                    val key = wordId to kind
                    if ((seeded[key]?.second ?: -1) < card.reps) seeded[key] = entity to card.reps
                }
            }
        }
        var cards = seeded.values.map { it.first }
        if (options.spreadOverdue) {
            val dues = spreadOverdue(cards.map { Instant.ofEpochMilli(it.due) }, now)
            cards = cards.mapIndexed { i, card -> card.copy(due = dues[i].toEpochMilli()) }
        }
        // A word learned in Anki unlocks its other cards as in FluRai, when Anki had none for them.
        val unlocked = cards.filter { it.kind == CardKind.Recognition && it.state == SrsState.Review.value && !it.suspended }.flatMap { r ->
            val production = StudyDay.end(now)
            listOfNotNull(
                (r.itemId to CardKind.Production).takeIf { r.itemId !in sentences && it !in seeded }?.let { newCard(uid, r.itemId, CardKind.Production, production.toEpochMilli()) },
                (r.itemId to CardKind.Listening).takeIf { listening && it !in seeded }?.let { newCard(uid, r.itemId, CardKind.Listening, StudyDay.end(production).toEpochMilli()) },
            )
        }
        val suspended = suspendedNew.filter { (it to CardKind.Recognition) !in seeded }.map {
            newCard(uid, it, CardKind.Recognition, nowMs).copy(suspended = true)
        }
        var kept = 0
        val toWrite = (cards + unlocked + suspended).filter { card ->
            val mine = store.card(uid, card.itemId, card.kind)
            (mine == null || mine.reps == 0).also { if (!it) kept++ }
        }.map { it.copy(createdAt = ++stamp) }
        store.putCards(toWrite)

        // 3. Decks, in Anki's order: cards already studied first, then the new ones by their position.
        val groups = notes.filter { it.id in wordOfNote }.groupBy { note ->
            val deckId = cardsByNote[note.id].orEmpty().minByOrNull { it.ord }?.deckId ?: 0L
            val path = deckName[deckId] ?: "Anki"
            if (options.perSubdeck) path else path.substringBefore("::")
        }
        val decks = groups.map { (path, groupNotes) ->
            val ordered = groupNotes.sortedWith(compareBy({ note -> if (cardsByNote[note.id].orEmpty().any { it.type != 0 }) 0 else 1 }, { note ->
                cardsByNote[note.id].orEmpty().filter { it.type == 0 }.minOfOrNull { it.due } ?: 0L
            }, { it.id }))
            val sourceId = c.decks.firstOrNull { it.name == path }?.id ?: path.hashCode().toLong()
            store.importDeck(uid, "anki:$sourceId", deckTitle(path), ordered.mapNotNull { wordOfNote[it.id] })
        }
        val customCount = wordOfNote.values.toSet().count { it < 0 }
        return AnkiImportResult(decks, builtIn, dictionary, customCount, skipped, cardsWithProgress = toWrite.count { it.reps > 0 }, keptCards = kept)
    }

    /** A free id for [word]: its hash, moved on past one already taken by a different word. */
    private suspend fun customId(uid: String, word: NoteWord, taken: Map<Int, CustomWordEntity>): Int {
        var id = customWordId(word.expression, word.reading)
        repeat(1_000) {
            val existing = taken[id] ?: store.customWord(uid, id)
            if (existing == null || (existing.expression == word.expression && existing.reading == word.reading)) return id
            id = if (id == Int.MIN_VALUE + 1) -1 else id - 1
        }
        return id
    }

    private fun newCard(uid: String, wordId: Int, kind: CardKind, due: Long) = CardEntity(
        uid = uid, itemType = ItemType.Word, itemId = wordId, kind = kind, state = SrsState.Learning.value, step = 0,
        stability = null, difficulty = null, due = due, lastReview = null, reps = 0, lapses = 0, suspended = false,
        buriedUntil = null, createdAt = 0,
    )
}

/** A deck's name in FluRai: its Anki path ("Japanese::Core 2k" → "Japanese / Core 2k"), or just its own name when that is too long. */
fun deckTitle(path: String): String {
    val full = path.split("::").joinToString(" / ") { it.trim() }
    return if (full.length <= 40) full else path.substringAfterLast("::").trim()
}
