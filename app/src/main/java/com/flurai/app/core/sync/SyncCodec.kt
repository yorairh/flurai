package com.flurai.app.core.sync

import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.quiz.TestType
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.DeckEntity
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.video.CustomChannelEntity

// How each kind of row looks in the cloud, and the rules for choosing between a phone's copy and the
// cloud's. All pure. The documents' layout is a stored format: backups written by earlier versions
// must stay readable, so field names here never change.

/** The collections that hold one document per row, in the order the pull reads them. */
internal val ROW_COLLECTIONS = listOf("cards", "days", "tests", "grammar")

/** Small whole-document items (profile, channels, decks). */
internal const val META_COLLECTION = "meta"

/** Every collection of a user's backup (what deleting the cloud copy removes). */
internal val CLOUD_COLLECTIONS = ROW_COLLECTIONS + META_COLLECTION

/** Firestore returns every whole number as Long and every fraction as Double. */
private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt()
private fun Map<String, Any?>.long(key: String) = (this[key] as? Number)?.toLong()

// -- cards -----------------------------------------------------------------------------------------

internal fun cardId(c: CardEntity) = "${c.itemType.name}-${c.itemId}-${c.kind.name}"

/** When a card last changed: its latest review, or its creation. */
internal fun touched(c: CardEntity) = maxOf(c.lastReview ?: 0L, c.createdAt)

/**
 * Whether a card has to go to the cloud: it changed after the last upload ([since], 0 = everything),
 * or it was added by hand (creation time 0, see StudyRepository.addToStudy) and never reviewed, which
 * leaves nothing to compare with [since]. Such a card is re-sent until its first review.
 */
internal fun needsUpload(c: CardEntity, since: Long): Boolean =
    since == 0L || touched(c) > since || (c.lastReview == null && c.createdAt == 0L)

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
        stability = (d["stability"] as? Number)?.toDouble(), difficulty = (d["difficulty"] as? Number)?.toDouble(),
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

// -- channels and decks (whole-document lists) -----------------------------------------------------

internal fun channelDoc(c: CustomChannelEntity): Map<String, Any?> =
    mapOf("name" to c.name, "url" to c.url, "level" to c.level, "addedAt" to c.addedAt)

internal fun channelFrom(uid: String, m: Map<*, *>): CustomChannelEntity? = CustomChannelEntity(
    uid = uid, name = m["name"] as? String ?: return null, url = m["url"] as? String ?: return null,
    level = (m["level"] as? Number)?.toInt() ?: 5, addedAt = (m["addedAt"] as? Number)?.toLong() ?: 0,
)

internal class DeckDoc(val deck: DeckEntity, val words: List<Int>)

internal fun deckDoc(d: DeckEntity, words: List<Int>): Map<String, Any?> =
    mapOf("name" to d.name, "createdAt" to d.createdAt, "words" to words)

internal fun deckFrom(uid: String, m: Map<*, *>): DeckDoc? {
    val name = m["name"] as? String ?: return null
    val createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0
    val words = (m["words"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
    return DeckDoc(DeckEntity(uid = uid, name = name, createdAt = createdAt), words)
}
