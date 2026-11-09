package com.flurai.app.core.chat

import com.flurai.app.core.model.JlptLevel

/** A situation to practise. [setup] tells the tutor who it plays and where the conversation starts. */
data class Scenario(val id: String, val japanese: String, val title: String, val blurb: String, val setup: String)

val scenarios = listOf(
    Scenario("free", "雑談", "Free chat", "Talk about anything: your day, hobbies, plans.", "You are a friendly Japanese conversation partner chatting casually. Start with a greeting and an easy question."),
    Scenario("intro", "自己紹介", "Introducing yourself", "Meet someone new: name, where you are from, what you do.", "You are meeting the learner for the first time at a small event. Ask them to introduce themselves."),
    Scenario("cafe", "カフェ", "At a café", "Order food and drinks, ask about the menu.", "You are a waiter or waitress at a café in Japan. The learner has just sat down. Greet them and take the order."),
    Scenario("directions", "道案内", "Asking directions", "Find a station or shop, understand the answer.", "You are a passer-by in Tokyo. The learner stops you to ask for directions. Wait for their question after a short greeting."),
    Scenario("shop", "買い物", "Shopping", "Ask about sizes, prices and colours.", "You are a shop assistant in a clothing store. Greet the learner and ask what they are looking for."),
    Scenario("station", "駅", "At the station", "Buy a ticket, ask about trains and times.", "You are a station staff member at a ticket counter. The learner comes to buy a ticket."),
)

fun scenario(id: String): Scenario = scenarios.firstOrNull { it.id == id } ?: scenarios.first()

/** What the tutor knows about the learner; used to pitch the language. */
data class LearnerContext(
    val level: JlptLevel,
    val knownWords: List<String> = emptyList(),
    val knownGrammar: List<String> = emptyList(),
    val weakGrammar: List<String> = emptyList(),
)

/** The tutor's instructions. The answer format is fixed so that the app can show each part on its own. */
fun systemPrompt(context: LearnerContext, scenario: Scenario): String = buildString {
    appendLine("You are a patient Japanese tutor inside a language-learning app. You role-play with the learner and gently correct their Japanese.")
    appendLine()
    appendLine("Scenario: ${scenario.setup}")
    appendLine()
    appendLine("Learner level: ${levelText(context.level)}.")
    if (context.knownWords.isNotEmpty()) appendLine("Words they know well (prefer these): ${context.knownWords.joinToString("、")}.")
    if (context.knownGrammar.isNotEmpty()) appendLine("Grammar they know: ${context.knownGrammar.joinToString("、")}.")
    if (context.weakGrammar.isNotEmpty()) appendLine("Grammar they find hard (use it naturally and often): ${context.weakGrammar.joinToString("、")}.")
    appendLine()
    appendLine("Rules:")
    appendLine("- Speak Japanese at or just slightly above their level. Keep each turn to 1 to 3 short sentences and end with a question or an easy prompt for them to answer.")
    appendLine("- Use kanji only for words suited to their level; otherwise write hiragana.")
    appendLine("- Stay in the scenario. If they write English, answer in simple Japanese and tell them the Japanese for what they said.")
    appendLine("- Never put English inside the Japanese reply.")
    appendLine()
    appendLine("Always answer in exactly this format, with these three labels, each on its own line:")
    appendLine("REPLY: <your Japanese reply>")
    appendLine("TRANSLATION: <natural English translation of your reply>")
    appendLine("CORRECTION: <if their last message had a mistake: the corrected Japanese, then a short English explanation of the fix, in one line. If it was fine: the single word NONE>")
    appendLine("For the very first message (when they say START) there is no correction: write CORRECTION: NONE.")
}

private fun levelText(level: JlptLevel) = when (level) {
    JlptLevel.Beginner -> "absolute beginner who is just learning hiragana and katakana. Use only very simple words and short sentences with ですます"
    else -> "JLPT ${level.label}. ${level.description}"
}

/** One tutor turn, split into its parts. [correction] is null when there was nothing to fix. */
data class AiTurn(val reply: String, val translation: String?, val correction: String?)

/**
 * Splits the tutor's answer by its REPLY / TRANSLATION / CORRECTION labels. If the model ignored the
 * format the whole text becomes the reply, so a conversation never breaks.
 */
fun parseTurn(text: String): AiTurn {
    val labels = listOf("REPLY", "TRANSLATION", "CORRECTION")
    val parts = HashMap<String, StringBuilder>()
    var current: String? = null
    for (line in text.lines()) {
        val label = labels.firstOrNull { line.trimStart().startsWith("$it:", ignoreCase = true) }
        if (label != null) {
            current = label
            parts[label] = StringBuilder(line.trimStart().substring(label.length + 1).trim())
        } else if (current != null && line.isNotBlank()) {
            parts[current]!!.append('\n').append(line.trim())
        }
    }
    val reply = parts["REPLY"]?.toString()?.trim().orEmpty()
    if (reply.isEmpty()) return AiTurn(text.trim(), null, null)
    val correction = parts["CORRECTION"]?.toString()?.trim()
        ?.takeUnless { it.isEmpty() || it.trim('.', '。', ' ').equals("NONE", ignoreCase = true) }
    return AiTurn(reply, parts["TRANSLATION"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }, correction)
}
