package com.flurai.app.core.content

/**
 * Typed romaji to kana, so "taberu" finds たべる. Hepburn (shi, chi, tsu, fu, ja) and the usual
 * keyboard spellings (si, ti, tu, hu, zi, jya) both work; "nn" and "n'" make ん, a doubled consonant
 * makes っ, and a macron or a doubled vowel gives the long vowel. Anything that is not romaji gives null.
 */
object Romaji {
    private val syllables: Map<String, String> = buildMap {
        fun add(kana: String, vararg romaji: String) = romaji.forEach { put(it, kana) }
        add("あ", "a"); add("い", "i"); add("う", "u", "wu"); add("え", "e"); add("お", "o")
        add("か", "ka"); add("き", "ki"); add("く", "ku"); add("け", "ke"); add("こ", "ko")
        add("さ", "sa"); add("し", "shi", "si"); add("す", "su"); add("せ", "se"); add("そ", "so")
        add("た", "ta"); add("ち", "chi", "ti"); add("つ", "tsu", "tu"); add("て", "te"); add("と", "to")
        add("な", "na"); add("に", "ni"); add("ぬ", "nu"); add("ね", "ne"); add("の", "no")
        add("は", "ha"); add("ひ", "hi"); add("ふ", "fu", "hu"); add("へ", "he"); add("ほ", "ho")
        add("ま", "ma"); add("み", "mi"); add("む", "mu"); add("め", "me"); add("も", "mo")
        add("や", "ya"); add("ゆ", "yu"); add("よ", "yo")
        add("ら", "ra"); add("り", "ri"); add("る", "ru"); add("れ", "re"); add("ろ", "ro")
        add("わ", "wa"); add("を", "wo")
        add("が", "ga"); add("ぎ", "gi"); add("ぐ", "gu"); add("げ", "ge"); add("ご", "go")
        add("ざ", "za"); add("じ", "ji", "zi"); add("ず", "zu"); add("ぜ", "ze"); add("ぞ", "zo")
        add("だ", "da"); add("ぢ", "di"); add("づ", "du"); add("で", "de"); add("ど", "do")
        add("ば", "ba"); add("び", "bi"); add("ぶ", "bu"); add("べ", "be"); add("ぼ", "bo")
        add("ぱ", "pa"); add("ぴ", "pi"); add("ぷ", "pu"); add("ぺ", "pe"); add("ぽ", "po")
        add("きゃ", "kya"); add("きゅ", "kyu"); add("きょ", "kyo")
        add("しゃ", "sha", "sya"); add("しゅ", "shu", "syu"); add("しょ", "sho", "syo")
        add("ちゃ", "cha", "tya", "cya"); add("ちゅ", "chu", "tyu", "cyu"); add("ちょ", "cho", "tyo", "cyo")
        add("にゃ", "nya"); add("にゅ", "nyu"); add("にょ", "nyo")
        add("ひゃ", "hya"); add("ひゅ", "hyu"); add("ひょ", "hyo")
        add("みゃ", "mya"); add("みゅ", "myu"); add("みょ", "myo")
        add("りゃ", "rya"); add("りゅ", "ryu"); add("りょ", "ryo")
        add("ぎゃ", "gya"); add("ぎゅ", "gyu"); add("ぎょ", "gyo")
        add("じゃ", "ja", "jya", "zya"); add("じゅ", "ju", "jyu", "zyu"); add("じょ", "jo", "jyo", "zyo")
        add("びゃ", "bya"); add("びゅ", "byu"); add("びょ", "byo")
        add("ぴゃ", "pya"); add("ぴゅ", "pyu"); add("ぴょ", "pyo")
        add("ふぁ", "fa"); add("ふぃ", "fi"); add("ふぇ", "fe"); add("ふぉ", "fo")
    }

    private val macrons = mapOf('ā' to "aa", 'ī' to "ii", 'ū' to "uu", 'ē' to "ee", 'ō' to "ou")
    private const val VOWELS = "aiueo"

    /** The hiragana for [input], or null when it is not (only) romaji. */
    fun toHiragana(input: String): String? {
        val text = buildString { input.trim().lowercase().forEach { append(macrons[it] ?: it.toString()) } }
        if (text.isEmpty() || text.any { it !in 'a'..'z' && it != '\'' && it != '-' }) return null
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '-' -> { out.append('ー'); i++ }
                c == '\'' -> { out.append('ん'); i++ }
                // ん: n before a consonant other than y, n at the end, "n'" and "nn". In "konnichiha" the second n
                // starts に, so "nn" only swallows both n when no vowel follows ("kannjiru").
                c == 'n' && (i + 1 == text.length || (text[i + 1] != 'y' && text[i + 1] !in VOWELS)) -> {
                    out.append('ん')
                    val next = text.getOrNull(i + 1)
                    val startsSyllable = text.getOrNull(i + 2)?.let { it in VOWELS || it == 'y' } == true
                    i += if (next == '\'' || (next == 'n' && !startsSyllable)) 2 else 1
                }
                // っ: a doubled consonant, and tch
                i + 1 < text.length && c !in VOWELS && c != 'n' && (text[i + 1] == c || (c == 't' && text.startsWith("tch", i))) -> {
                    out.append('っ'); i++
                }
                else -> {
                    val match = (3 downTo 1).firstNotNullOfOrNull { length ->
                        if (i + length > text.length) null else syllables[text.substring(i, i + length)]?.let { it to length }
                    } ?: return null
                    out.append(match.first)
                    i += match.second
                }
            }
        }
        return out.toString()
    }

    /** The katakana spelling of hiragana text (on'yomi readings are katakana). */
    fun toKatakana(hiragana: String): String = buildString {
        hiragana.forEach { append(if (it in 'ぁ'..'ゖ') (it + 0x60) else it) }
    }
}
