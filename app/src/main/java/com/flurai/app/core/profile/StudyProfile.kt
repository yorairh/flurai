package com.flurai.app.core.profile

import com.flurai.app.core.model.JlptLevel

/** What the learner chose at onboarding. Stored per account, so two people can share a phone. */
data class StudyProfile(
    val level: JlptLevel,
    val dailyMinutes: Int,
    val newCardsPerDay: Int,
    /** Only asked of beginners; used to decide whether to start with the kana decks. */
    val knowsHiragana: Boolean,
    val knowsKatakana: Boolean,
) {
    companion object {
        val DailyMinuteOptions = listOf(5, 10, 15, 30, 60)
        val NewCardOptions = listOf(5, 10, 15, 20, 30)
        const val DEFAULT_DAILY_MINUTES = 15
        const val DEFAULT_NEW_CARDS = 10
    }
}
