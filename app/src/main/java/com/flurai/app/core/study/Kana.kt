package com.flurai.app.core.study

enum class KanaScript(val japanese: String, val label: String) {
    Hiragana("ひらがな", "Hiragana"),
    Katakana("カタカナ", "Katakana"),
}

/** One kana card. [id] is stable (hiragana 1–71 and 201–233, katakana 101–171 and 301–333); progress refers to it. */
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

    // Combination sounds (small ゃ ゅ ょ), appended with new ids: the base kana keep theirs.
    private val combinations = listOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
    )

    /** Hiragana → katakana is a fixed offset in Unicode. */
    private fun toKatakana(hiragana: String) = hiragana.map { it + 0x60 }.joinToString("")

    private const val HIRAGANA_COMBINATIONS_FROM = 201
    private const val KATAKANA_COMBINATIONS_FROM = 301

    private val baseHiragana = rows.mapIndexed { i, (kana, romaji) -> Kana(i + 1, KanaScript.Hiragana, kana, romaji) }
    private val baseKatakana = rows.mapIndexed { i, (kana, romaji) -> Kana(i + 101, KanaScript.Katakana, toKatakana(kana), romaji) }
    private val combinationHiragana = combinations.mapIndexed { i, (kana, romaji) ->
        Kana(HIRAGANA_COMBINATIONS_FROM + i, KanaScript.Hiragana, kana, romaji)
    }
    private val combinationKatakana = combinations.mapIndexed { i, (kana, romaji) ->
        Kana(KATAKANA_COMBINATIONS_FROM + i, KanaScript.Katakana, toKatakana(kana), romaji)
    }

    /** The basic kana, then the combinations: new kana are introduced in this order. */
    val hiragana: List<Kana> = baseHiragana + combinationHiragana
    val katakana: List<Kana> = baseKatakana + combinationKatakana

    private val byId = (hiragana + katakana).associateBy { it.id }

    /** A group of rows of the kana chart; a null cell is a gap in the grid (there is no yi or wu). */
    data class ChartSection(val title: String, val rows: List<List<Kana?>>)

    /** The kana of [script] as the chart in a Japanese textbook: basic (a, ka, sa ...), voiced (ga, za ...), combinations (kya ...). */
    fun chart(script: KanaScript): List<ChartSection> {
        val base = if (script == KanaScript.Hiragana) baseHiragana else baseKatakana
        val combos = if (script == KanaScript.Hiragana) combinationHiragana else combinationKatakana
        fun slice(from: Int, count: Int) = base.subList(from, from + count)
        val basic = (0 until 35 step 5).map { slice(it, 5) } + listOf(
            slice(35, 3).let { listOf(it[0], null, it[1], null, it[2]) },
            slice(38, 5),
            slice(43, 3).let { listOf(it[0], null, null, null, it[1]) },
            listOf(slice(43, 3)[2]),
        )
        val voiced = (46 until 71 step 5).map { slice(it, 5) }
        return listOf(
            ChartSection("Basic", basic),
            ChartSection("Voiced", voiced),
            ChartSection("Combinations", combos.chunked(3)),
        )
    }

    fun byId(id: Int): Kana? = byId[id]
    fun of(script: KanaScript) = if (script == KanaScript.Hiragana) hiragana else katakana
}
