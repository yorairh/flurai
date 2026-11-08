package com.flurai.app.core.model

/**
 * Learner levels, easiest first. JLPT levels count down (N5 is the easiest, N1 the hardest),
 * so compare levels by [ordinal], never by the number in the name.
 */
enum class JlptLevel(val label: String, val description: String) {
    Beginner("Beginner", "Learning hiragana and katakana"),
    N5("N5", "Basic words, kana and simple sentences"),
    N4("N4", "Everyday conversation basics"),
    N3("N3", "Intermediate: everyday topics and simple native material"),
    N2("N2", "Upper intermediate: news, most native media"),
    N1("N1", "Advanced: complex texts on any topic"),
    ;

    /** The JLPT number (5 for N5 … 1 for N1); null for Beginner, which isn't a JLPT level. */
    val jlpt: Int?
        get() = when (this) {
            Beginner -> null
            N5 -> 5
            N4 -> 4
            N3 -> 3
            N2 -> 2
            N1 -> 1
        }

    /** The next goal after this level, or null at N1. */
    fun next(): JlptLevel? = entries.getOrNull(ordinal + 1)

    fun isAtLeast(other: JlptLevel): Boolean = ordinal >= other.ordinal

    companion object {
        fun ofJlpt(number: Int): JlptLevel? = entries.firstOrNull { it.jlpt == number }
    }
}
