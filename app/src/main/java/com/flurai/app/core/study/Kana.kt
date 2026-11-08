package com.flurai.app.core.study

enum class KanaScript(val japanese: String, val label: String) {
    Hiragana("ひらがな", "Hiragana"),
    Katakana("カタカナ", "Katakana"),
}

/** One kana card. [id] is stable (hiragana 1–71, katakana 101–171); progress refers to it. */
data class Kana(val id: Int, val script: KanaScript, val kana: String, val romaji: String)

object KanaData {
    // Gojūon order, then the voiced (dakuten/handakuten) rows. Hepburn romanisation.
    private val rows = listOf(
        "あ" to "a", "い" to "i", "う" to "u", "え" to "e", "お" to "o",
        "か" to "ka", "き" to "ki", "く" to "ku", "け" to "ke", "こ" to "ko",
        "さ" to "sa", "し" to "shi", "す" to "su", "せ" to "se", "そ" to "so",
        "た" to "ta", "ち" to "chi", "つ" to "tsu", "て" to "te", "と" to "to",
        "な" to "na", "に" to "ni", "ぬ" to "nu", "ね" to "ne", "の" to "no",
        "は" to "ha", "ひ" to "hi", "ふ" to "fu", "へ" to "he", "ほ" to "ho",
        "ま" to "ma", "み" to "mi", "む" to "mu", "め" to "me", "も" to "mo",
        "や" to "ya", "ゆ" to "yu", "よ" to "yo",
        "ら" to "ra", "り" to "ri", "る" to "ru", "れ" to "re", "ろ" to "ro",
        "わ" to "wa", "を" to "wo", "ん" to "n",
        "が" to "ga", "ぎ" to "gi", "ぐ" to "gu", "げ" to "ge", "ご" to "go",
        "ざ" to "za", "じ" to "ji", "ず" to "zu", "ぜ" to "ze", "ぞ" to "zo",
        "だ" to "da", "ぢ" to "ji", "づ" to "zu", "で" to "de", "ど" to "do",
        "ば" to "ba", "び" to "bi", "ぶ" to "bu", "べ" to "be", "ぼ" to "bo",
        "ぱ" to "pa", "ぴ" to "pi", "ぷ" to "pu", "ぺ" to "pe", "ぽ" to "po",
    )

    /** Hiragana → katakana is a fixed offset in Unicode. */
    private fun toKatakana(hiragana: String) = hiragana.map { it + 0x60 }.joinToString("")

    val hiragana: List<Kana> = rows.mapIndexed { i, (kana, romaji) -> Kana(i + 1, KanaScript.Hiragana, kana, romaji) }
    val katakana: List<Kana> = rows.mapIndexed { i, (kana, romaji) ->
        Kana(i + 101, KanaScript.Katakana, toKatakana(kana), romaji)
    }

    private val byId = (hiragana + katakana).associateBy { it.id }

    fun byId(id: Int): Kana? = byId[id]
    fun of(script: KanaScript) = if (script == KanaScript.Hiragana) hiragana else katakana
}
