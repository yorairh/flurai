package com.flurai.app.ui.study

import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FormattingTest {
    @Test
    fun intervals() {
        assertEquals("<1m", formatInterval(Duration.ofSeconds(30)))
        assertEquals("10m", formatInterval(Duration.ofMinutes(10)))
        assertEquals("5h", formatInterval(Duration.ofHours(5)))
        assertEquals("3d", formatInterval(Duration.ofDays(3)))
        assertEquals("2mo", formatInterval(Duration.ofDays(60)))
        assertEquals("1.5y", formatInterval(Duration.ofDays(548)))
    }

    @Test
    fun cardStatusText() {
        val now = Instant.parse("2026-03-10T03:00:00Z")
        val card = CardEntity(
            "u", ItemType.Word, 1, CardKind.Recognition, SrsState.Review.value, null, 5.0, 5.0,
            due = now.plus(Duration.ofDays(12)).toEpochMilli(), lastReview = now.toEpochMilli(),
            reps = 3, lapses = 0, suspended = false, buriedUntil = null, createdAt = 0,
        )
        assertEquals("Review · in 12d", cardStatus(card, now))
        assertEquals("Learning · due now", cardStatus(card.copy(state = SrsState.Learning.value, due = now.toEpochMilli()), now))
        assertEquals("New", cardStatus(card.copy(lastReview = null), now))
        assertEquals("Suspended", cardStatus(card.copy(suspended = true), now))
    }
}
