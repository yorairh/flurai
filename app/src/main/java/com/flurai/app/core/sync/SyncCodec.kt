package com.flurai.app.core.sync

import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.course.CourseProgressEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.quiz.TestType
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.CustomWordEntity
import com.flurai.app.core.study.DeckEntity
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.legacyDeckKey
import com.flurai.app.core.video.CustomChannelEntity

// How each kind of row looks in the cloud, and the rules for choosing between a phone's copy and the
// cloud's. All pure. The documents' layout is a stored format: backups written by earlier versions
// must stay readable, so field names here never change.

/** The collections that hold one document per row, in the order the pull reads them. */
internal val ROW_COLLECTIONS = listOf("cards", "days", "tests", "grammar", "course")

/** Small whole-document items (profile, channels; decks before format 2). */
internal const val META_COLLECTION = "meta"

/** One document per deck, its id the deck's key; a deleted deck stays as a tombstone (`deleted: true`). Since format 2. */
internal const val DECK_COLLECTION = "decks"

/**
 * The learner's own words (from an Anki import), one document each, its id the word id; the newer
 * `updatedAt` wins. Added in 2.1 without a format change: an older FluRai ignores the collection.
 */
internal const val CUSTOM_WORD_COLLECTION = "customWords"

/** Every collection of a user's backup (what deleting the cloud copy removes), in the order the pull reads them. */
internal val CLOUD_COLLECTIONS = ROW_COLLECTIONS + META_COLLECTION + DECK_COLLECTION + CUSTOM_WORD_COLLECTION

/**
 * The backup format this app reads and writes. A version that changes the format so that older apps
 * would misread it writes `meta/schema.minVersion`; an app whose [SYNC_FORMAT] is below it stops
 * syncing (and asks to be updated) instead of damaging the backup.
 *
 * 1: FluRai 1.10 (decks in `meta/decks`). 2: FluRai 2.0, decks one document each in [DECK_COLLECTION].
 */
internal const val SYNC_FORMAT = 2

/** The first format with decks in [DECK_COLLECTION]. */
internal const val DECKS_AS_DOCUMENTS = 2

/** The format a backup was written in: `minVersion` of its schema document (0 when there is none yet). */
internal fun backupFormat(schema: Map<String, Any?>?): Int = (schema?.get("minVersion") as? Number)?.toInt() ?: 0

/** The document under [META_COLLECTION] that holds `minVersion`. */
internal const val SCHEMA_DOC = "schema"

/** Whether this app may sync a backup whose schema document is [schema] (null: no such document yet). */
internal fun formatSupported(schema: Map<String, Any?>?): Boolean = backupFormat(schema) <= SYNC_FORMAT

/** Firestore returns every whole number as Long and every fraction as Double. */
private fun Map<*, *>.int(key: String) = (this[key] as? Number)?.toInt()
private fun Map<*, *>.long(key: String) = (this[key] as? Number)?.toLong()
private fun Map<*, *>.double(key: String) = (this[key] as? Number)?.toDouble()

// -- cards -----------------------------------------------------------------------------------------

internal fun cardId(c: CardEntity) = "${c.itemType.name}-${c.itemId}-${c.kind.name}"

/** When a card last changed: its latest review, or its creation. */
internal fun touched(c: CardEntity) = maxOf(c.lastReview ?: 0L, c.createdAt)

/** Whether a card has to go to the cloud: it changed after the last upload ([since], 0 = everything). */
internal fun needsUpload(c: CardEntity, since: Long): Boolean = since == 0L || touched(c) > since

internal fun cardDoc(c: CardEntity): Map<String, Any?> = mapOf(
    "itemType" to c.itemType.name, "itemId" to c.itemId, "kind" to c.kind.name, "state" to c.state, "step" to c.step,
    "stability" to c.stability, "difficulty" to c.difficulty, "due" to c.due, "lastReview" to c.lastReview,
    "reps" to c.reps, "lapses" to c.lapses, "suspended" to c.suspended, "buriedUntil" to c.buriedUntil,
    "createdAt" to c.createdAt,
)

internal fun cardFrom(uid: String, d: Map<String, Any?>): CardEntity? {
    val type = ItemType.entries.firstOrNull { it.name == d["itemType"] } ?: return null
    val kind = CardKind.entries.firstOrNull { it.name == d["kind"] } ?: return null
    return CardEntity(
        uid = uid, itemType = type, itemId = d.int("itemId") ?: return null, kind = kind,
        state = d.int("state") ?: return null, step = d.int("step"),
        stability = d.double("stability"), difficulty = d.double("difficulty"),
        due = d.long("due") ?: return null, lastReview = d.long("lastReview"), reps = d.int("reps") ?: 0,
        lapses = d.int("lapses") ?: 0, suspended = d["suspended"] as? Boolean ?: false,
        buriedUntil = d.long("buriedUntil"), createdAt = d.long("createdAt") ?: 0L,
    )
}

/**
 * After merging one cloud row: true when the cloud already holds what this phone has (the cloud
 * copy won or is the same), so uploading it again would be pointless. False when the local copy is
 * newer and must be pushed. [remote] and [local] are "last changed" times; [local] null = no local row.
 */
internal fun cloudCovers(remote: Long, local: Long?): Boolean = local == null || remote >= local

// -- study days ------------------------------------------------------------------------------------

internal fun dayDoc(d: StudyDayEntity): Map<String, Any?> = mapOf("day" to d.day, "seconds" to d.seconds, "reviews" to d.reviews)

internal fun dayFrom(uid: String, d: Map<String, Any?>): StudyDayEntity? =
    StudyDayEntity(uid, d.long("day") ?: return null, d.int("seconds") ?: 0, d.int("reviews") ?: 0)

/** Time studied on two phones on the same day: keep the larger totals, never lose time. */
internal fun mergeDay(local: StudyDayEntity?, remote: StudyDayEntity): StudyDayEntity =
    if (local == null) remote else remote.copy(seconds = maxOf(local.seconds, remote.seconds), reviews = maxOf(local.reviews, remote.reviews))

// -- test results ----------------------------------------------------------------------------------

internal fun testId(t: TestResultEntity) = "${t.takenAt}-${t.type.name}"

internal fun testDoc(t: TestResultEntity): Map<String, Any?> =
    mapOf("type" to t.type.name, "level" to t.level, "correct" to t.correct, "total" to t.total, "takenAt" to t.takenAt)

internal fun testFrom(uid: String, d: Map<String, Any?>): TestResultEntity? = TestResultEntity(
    uid = uid, type = TestType.entries.firstOrNull { it.name == d["type"] } ?: return null, level = d.int("level"),
    correct = d.int("correct") ?: 0, total = d.int("total") ?: 0, takenAt = d.long("takenAt") ?: return null,
)

// -- grammar progress ------------------------------------------------------------------------------

internal fun grammarDoc(g: GrammarProgressEntity): Map<String, Any?> = mapOf(
    "grammarId" to g.grammarId, "correct" to g.correct, "wrong" to g.wrong, "lastCorrect" to g.lastCorrect, "lastAt" to g.lastAt,
)

internal fun grammarFrom(uid: String, d: Map<String, Any?>): GrammarProgressEntity? = GrammarProgressEntity(
    uid, d.int("grammarId") ?: return null, d.int("correct") ?: 0, d.int("wrong") ?: 0,
    d["lastCorrect"] as? Boolean ?: false, d.long("lastAt") ?: 0,
)

// -- grammar course progress -----------------------------------------------------------------------

internal fun courseDoc(c: CourseProgressEntity): Map<String, Any?> = mapOf(
    "lessonId" to c.lessonId, "bestCorrect" to c.bestCorrect, "bestTotal" to c.bestTotal, "passedAt" to c.passedAt,
    "attempts" to c.attempts, "missed" to c.missed, "lastAt" to c.lastAt,
)

internal fun courseFrom(uid: String, d: Map<String, Any?>): CourseProgressEntity? = CourseProgressEntity(
    uid, d.int("lessonId") ?: return null, d.int("bestCorrect") ?: 0, d.int("bestTotal") ?: 0, d.long("passedAt"),
    d.int("attempts") ?: 0, d["missed"] as? String ?: "", d.long("lastAt") ?: 0,
)

// -- channels and decks (whole-document lists) -----------------------------------------------------

internal fun channelDoc(c: CustomChannelEntity): Map<String, Any?> =
    mapOf("name" to c.name, "url" to c.url, "level" to c.level, "addedAt" to c.addedAt)

internal fun channelFrom(uid: String, m: Map<*, *>): CustomChannelEntity? = CustomChannelEntity(
    uid = uid, name = m["name"] as? String ?: return null, url = m["url"] as? String ?: return null,
    level = m.int("level") ?: 5, addedAt = m.long("addedAt") ?: 0,
)

// -- the learner's own words ------------------------------------------------------------------------

internal fun customWordDoc(w: CustomWordEntity): Map<String, Any?> = mapOf(
    "id" to w.id, "kind" to w.kind, "expression" to w.expression, "reading" to w.reading, "meaning" to w.meaning,
    "ankiGuid" to w.ankiGuid, "createdAt" to w.createdAt, "updatedAt" to w.updatedAt,
)

internal fun customWordFrom(uid: String, d: Map<String, Any?>): CustomWordEntity? = CustomWordEntity(
    uid = uid, id = d.int("id") ?: return null, kind = d["kind"] as? String ?: CustomWordEntity.KIND_WORD,
    expression = d["expression"] as? String ?: return null, reading = d["reading"] as? String ?: "",
    meaning = d["meaning"] as? String ?: "", ankiGuid = d["ankiGuid"] as? String,
    createdAt = d.long("createdAt") ?: 0, updatedAt = d.long("updatedAt") ?: 0,
)

internal class DeckDoc(val deck: DeckEntity, val words: List<Int>)

/** A deck as an item of the format-1 `meta/decks` list (no key: it is derived, see legacyDeckKey). */
internal fun legacyDeckDoc(d: DeckEntity, words: List<Int>): Map<String, Any?> =
    mapOf("name" to d.name, "createdAt" to d.createdAt, "words" to words)

internal fun legacyDeckFrom(uid: String, m: Map<*, *>): DeckDoc? {
    val name = m["name"] as? String ?: return null
    val createdAt = m.long("createdAt") ?: 0
    val words = (m["words"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
    return DeckDoc(DeckEntity(uid = uid, name = name, createdAt = createdAt, key = legacyDeckKey(name, createdAt)), words)
}

/** A deck's own document in [DECK_COLLECTION] (its id is the key). */
internal fun deckDoc(d: DeckEntity, words: List<Int>): Map<String, Any?> =
    mapOf("name" to d.name, "createdAt" to d.createdAt, "source" to d.source, "words" to words, "deleted" to false)

/** What a deck deleted on some phone leaves in the cloud, so the other phones delete it too. */
internal val DECK_TOMBSTONE: Map<String, Any?> = mapOf("deleted" to true)

internal fun isTombstone(d: Map<String, Any?>) = d["deleted"] == true

internal fun deckFrom(uid: String, key: String, m: Map<String, Any?>): DeckDoc? {
    val name = m["name"] as? String ?: return null
    val words = (m["words"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
    val deck = DeckEntity(uid = uid, name = name, createdAt = m.long("createdAt") ?: 0, key = key, source = m["source"] as? String)
    return DeckDoc(deck, words)
}
