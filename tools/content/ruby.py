"""Furigana for the sentences in content.db, worked out when the database is built.

The app used to guess readings at run time from the 8,000 JLPT words, which left about one kanji in five
without furigana (every conjugated verb) and sometimes picked the wrong word (来 read らい～, "next ~").
Here the readings come from JMdict, with Tatoeba's word index to say which word a sentence uses.

The result is a markup string per sentence: a word with kanji is written ``{base|reading}``, as in
``{誰|だれ}が{来|き}ました`` or ``{貸出し|かしだし}``. Kana and symbols outside the braces carry nothing. A sentence
the pipeline cannot read stays without markup and the app falls back to its own guess.
"""

from __future__ import annotations

from dataclasses import dataclass, field


def is_kanji(c: str) -> bool:
    return "一" <= c <= "鿿" or c in "々〆"


def to_hiragana(text: str) -> str:
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


def align(surface: str, reading: str) -> list[tuple[str, str | None]]:
    """Splits ``surface`` into (text, reading) parts: the right part of ``reading`` over each kanji run, none over
    kana inside the word (食べ物 / たべもの gives 食{た} べ 物{もの}). When the reading does not fit the spelling the
    whole word carries the whole reading, which is less exact but never wrong."""
    if not any(is_kanji(c) for c in surface):
        return [(surface, None)]
    r = to_hiragana(reading)
    runs: list[tuple[str, bool]] = []
    for c in surface:
        kanji = is_kanji(c)
        if runs and runs[-1][1] == kanji:
            runs[-1] = (runs[-1][0] + c, kanji)
        else:
            runs.append((c, kanji))

    def solve(i: int, pos: int) -> list[tuple[str, str | None]] | None:
        if i == len(runs):
            return [] if pos == len(r) else None
        text, kanji = runs[i]
        if not kanji:
            kana = to_hiragana(text)
            if not r.startswith(kana, pos):
                return None
            rest = solve(i + 1, pos + len(kana))
            return None if rest is None else [(text, None)] + rest
        # a kanji run takes at least one kana of the reading; try every length until the rest fits
        for end in range(pos + 1, len(r) + 1):
            rest = solve(i + 1, end)
            if rest is not None:
                return [(text, r[pos:end])] + rest
        return None

    # Kana inside a reading can repeat (かしだし: the し of 貸出し is the last one), so the first split that
    # fits is taken only after the longer readings were tried for the last kanji run before kana.
    parts = solve(0, 0)
    return parts if parts is not None else [(surface, reading)]


def markup(parts: list[tuple[str, str | None]]) -> str:
    return "".join(text if ruby is None else f"{{{text}|{ruby}}}" for text, ruby in parts)


def parse(marked: str) -> list[tuple[str, str | None]]:
    """The inverse of [markup]: (text, reading) parts."""
    parts: list[tuple[str, str | None]] = []
    plain = ""
    i = 0
    while i < len(marked):
        if marked[i] == "{":
            close = marked.index("}", i)
            base, _, reading = marked[i + 1 : close].partition("|")
            if plain:
                parts.append((plain, None))
                plain = ""
            parts.append((base, reading))
            i = close + 1
        else:
            plain += marked[i]
            i += 1
    if plain:
        parts.append((plain, None))
    return parts


# --------------------------------------------------------------------------------------------------
# Dictionary readings from JMdict


@dataclass
class Candidate:
    reading: str
    common: int  # 0..2: how many of the spelling and the reading are marked common
    order: int  # position of the entry in JMdict
    pos: frozenset[str]


#: Family and given names are not in JMdict. The ones that Tatoeba uses most.
NAMES = {
    "田中": "たなか", "山田": "やまだ", "鈴木": "すずき", "佐藤": "さとう", "高橋": "たかはし", "伊藤": "いとう", "渡辺": "わたなべ",
    "山本": "やまもと", "中村": "なかむら", "小林": "こばやし", "加藤": "かとう", "吉田": "よしだ", "山口": "やまぐち", "松本": "まつもと",
    "井上": "いのうえ", "木村": "きむら", "清水": "しみず", "山崎": "やまざき", "池田": "いけだ", "橋本": "はしもと", "石川": "いしかわ",
    "山下": "やました", "中島": "なかじま", "前田": "まえだ", "藤田": "ふじた", "小川": "おがわ", "岡田": "おかだ", "後藤": "ごとう",
    "村上": "むらかみ", "近藤": "こんどう", "坂本": "さかもと", "青木": "あおき", "松川": "まつかわ", "小泉": "こいずみ", "春田": "はるた",
    "成田": "なりた", "太郎": "たろう", "次郎": "じろう", "花子": "はなこ", "洋子": "ようこ", "由美": "ゆみ", "健二": "けんじ",
    "健介": "けんすけ", "秀樹": "ひでき", "一朗": "いちろう", "恵一": "けいいち", "舞子": "まいこ", "妙子": "たえこ", "佳子": "よしこ",
    "亜美": "あみ", "結美": "ゆみ", "友理子": "ゆりこ", "佐知子": "さちこ", "奈良": "なら", "長野": "ながの", "広島": "ひろしま",
    "千葉": "ちば", "水戸": "みと", "青森": "あおもり", "加古川": "かこがわ", "富良野": "ふらの", "私達": "わたしたち",
}


@dataclass
class Reader:
    spell: dict[str, list[Candidate]] = field(default_factory=dict)
    #: Readings the JLPT word lists use for a spelling, easiest word first: they win between equally valid
    #: readings (人 is ひと, 本 is ほん).
    preferred: dict[str, list[str]] = field(default_factory=dict)
    #: Stems of verbs and い-adjectives for text without Tatoeba tokens: stem spelling -> [(stem reading, kind, final kana)]
    stems: dict[str, list[tuple[str, str, str, int]]] = field(default_factory=dict)

    @classmethod
    def build(cls, jmdict_words: list[dict], jlpt_readings: dict[str, list[str]]) -> "Reader":
        reader = cls(preferred=jlpt_readings)
        for name, reading in NAMES.items():
            reader.spell.setdefault(name, []).append(Candidate(reading, 2, -1, frozenset()))
        for order, entry in enumerate(jmdict_words):
            pos = frozenset(p for sense in entry["sense"] for p in sense["partOfSpeech"])
            first_pos = set(entry["sense"][0]["partOfSpeech"]) if entry["sense"] else set()
            for kanji in entry["kanji"]:
                for kana in entry["kana"]:
                    applies = kana.get("appliesToKanji", ["*"])
                    if "*" not in applies and kanji["text"] not in applies:
                        continue
                    common = int(bool(kanji.get("common"))) + int(bool(kana.get("common")))
                    reader.spell.setdefault(kanji["text"], []).append(Candidate(kana["text"], common, order, pos))
                    kind = _inflection_kind(first_pos)
                    if kind and len(kanji["text"]) >= 2 and kanji["text"][-1] == kana["text"][-1] and any(is_kanji(c) for c in kanji["text"]):
                        reader.stems.setdefault(kanji["text"][:-1], []).append((kana["text"][:-1], kind, kanji["text"][-1], common))
        return reader

    def choose(self, spelling: str, hint: str | None = None) -> Candidate | None:
        candidates = self.spell.get(spelling)
        if not candidates:
            return None
        best = max(c.common for c in candidates)
        if hint:
            for c in candidates:
                # an index hint is trusted unless it names a rarer reading than the word has (白菜 is はくさい, not ぱくちょい)
                if to_hiragana(c.reading) == to_hiragana(hint) and c.common >= best:
                    return c
        return min(candidates, key=lambda c: (self.rank(spelling, c.reading), -c.common, c.order))

    def rank(self, spelling: str, reading: str) -> int:
        """0 for the reading of the easiest JLPT word written this way, then 1, 2 ...; 99 when no JLPT word has it."""
        liked = [r for r in self.preferred.get(spelling, []) if "～" not in r]
        return liked.index(reading) if reading in liked else 99


def _inflection_kind(pos: set[str]) -> str | None:
    """'ichidan', 'godan' or 'adj' for the entries that conjugate with a kanji stem."""
    if "v1" in pos:
        return "ichidan"
    if any(p.startswith("v5") for p in pos):
        return "godan"
    if "adj-i" in pos:
        return "adj"
    return None


# The kana that can follow a stem, by the dictionary form's final kana.
_GODAN_ROWS = {
    "く": "きかいけこっ", "ぐ": "ぎがいげご", "す": "しさせそ", "つ": "ちたってと", "ぬ": "になんねの",
    "ぶ": "びばんべぼ", "む": "みまんめも", "る": "りらっれろ", "う": "いわっえお",
}
_ICHIDAN_AFTER = "まなてたよれさせらろずえ"
_ADJ_AFTER = "くかけさそす"


def stem_ok(kind: str, final: str, following: str) -> bool:
    if not following:
        return False
    if kind == "ichidan":
        # 震え始める: the stem in front of another verb (a kanji) is fine too
        return following in _ICHIDAN_AFTER or is_kanji(following)
    if kind == "godan":
        return following in _GODAN_ROWS.get(final, "")
    return following in _ADJ_AFTER


COME_FOLLOWERS = "ままなてたそさよらるれ"


def come_reading(following: str) -> str:
    """来 in 来ます (き), 来ない (こ), 来る (く)."""
    if following and following in "なさよら":
        return "こ"
    if following and following in "るれ":
        return "く"
    return "き"


# --------------------------------------------------------------------------------------------------
# Sentences with Tatoeba's word index


def prefix_len(a: str, b: str) -> int:
    n = 0
    while n < len(a) and n < len(b) and a[n] == b[n]:
        n += 1
    return n


PARTICLES = "にでもの"

#: A spelling that is a word plus は, の, か or が (今日は = こんにちは, 彼の = あの, 私か = ひそか) is read as the word and the
#: particle. Not に, で or も: 既に, 殊に and 最も are real words that are read as a whole.
LOOSE_PARTICLES = "はのかが"


def compound_with_particle(reader: "Reader", spelling: str) -> bool:
    return len(spelling) > 1 and spelling[-1] in LOOSE_PARTICLES and spelling[:-1] in reader.spell


def token_parts(reader: Reader, headword: str, form: str | None, hint: str | None, following: str = "") -> list[tuple[str, str | None]] | None:
    """(text, reading) parts for one token of a sentence, or None when it cannot be read.
    ``form`` is how the word is written in the sentence when that differs from its dictionary spelling."""
    surface = form or headword
    if not any(is_kanji(c) for c in surface):
        return [(surface, None)]
    # The index often writes the particle after a word into its form (私 / 私の, 上手 / 上手に): the word keeps its
    # own reading and the rest is plain text. This comes first, or 彼の would be looked up as the word あの.
    if form and form != headword and surface.startswith(headword):
        candidate = reader.choose(headword, hint)
        if candidate is not None:
            return align(headword, candidate.reading) + [(surface[len(headword):], None)]
    # Written in a different spelling than the headword (会う / 遭った): look the spelling up on its own. A form that
    # only cuts the headword short (来 for 来る, 多 for 多い) is an inflected stem and is handled below instead.
    if form and form != headword and not headword.startswith(form):
        direct = reader.choose(form)
        word = reader.choose(headword, hint)
        # The spelling's own reading is used when it is the headword's reading (私達 and 私たち are both わたしたち) or the
        # spelling shares nothing with the headword. 若く is not looked up as しく: it is 若い, inflected.
        if direct is not None and (word is None or direct.reading == word.reading or prefix_len(headword, form) == 0):
            return align(form, direct.reading)
    if compound_with_particle(reader, headword):
        base = reader.choose(headword[:-1])
        if base is not None and form in (None, headword):
            return align(headword[:-1], base.reading) + [(headword[-1], None)]
    # A lone 来 in front of a verb ending (来なさい, 来ました) is the verb, which the index sometimes splits off.
    if surface == "来" and following and following in COME_FOLLOWERS:
        return [("来", come_reading(following))]
    candidate = reader.choose(headword, hint)
    if candidate is None:
        # 一人で: a word followed by a particle that the index kept in the token
        if surface and surface[-1] in PARTICLES and len(surface) > 1:
            inner = reader.choose(surface[:-1])
            if inner is not None:
                return align(surface[:-1], inner.reading) + [(surface[-1], None)]
        return None
    reading = candidate.reading
    if surface == headword:
        return align(surface, reading)
    # Inflected: the shared beginning (食べ of 食べる / 食べた) keeps its reading; the rest is kana.
    n = prefix_len(headword, surface)
    tail = headword[n:]
    if n == 0 and form and form != headword and is_kanji(surface[0]):
        # The same word written with another kanji (計る / 測った, 探す / 捜した): its first kanji run keeps the reading.
        parts = align(headword, reading)
        if parts and parts[0][1] is not None and is_kanji(headword[0]):
            lead = ""
            for c in surface:
                if not is_kanji(c):
                    break
                lead += c
            return [(lead, parts[0][1]), (surface[len(lead):], None)] if surface[len(lead):] else [(lead, parts[0][1])]
        return None
    if n == 0 or not any(is_kanji(c) for c in headword[:n]):
        return [(surface, None)] if not any(is_kanji(c) for c in surface) else None
    if headword == "来る":
        rest = surface[n:]
        reading_of_stem = come_reading((rest or following)[:1])
        return [(surface[:n], reading_of_stem)] + ([(rest, None)] if rest else [])
    if tail and to_hiragana(reading).endswith(to_hiragana(tail)):
        stem_reading = reading[: len(reading) - len(tail)]
        return align(surface[:n], stem_reading) + [(surface[n:], None)]
    return None


def sentence_markup(reader: Reader, text: str, tokens: list[tuple[str, str | None, str | None]]) -> str | None:
    """The furigana markup of a Tatoeba sentence from its index tokens ``(headword, hint, form)``, or None when it
    has no kanji or cannot be laid over the text."""
    if any(c in text for c in "{|}") or not any(is_kanji(c) for c in text):
        return None
    parts: list[tuple[str, str | None]] = []
    position = 0
    for i, (headword, hint, form) in enumerate(tokens):
        surface = form or headword
        found = text.find(surface, position)
        if found < 0:
            continue
        if found > position:
            parts += _gap(reader, text[position:found])
        following = text[found + len(surface): found + len(surface) + 1]
        token = token_parts(reader, headword, form, hint, following)
        if token is None and any(is_kanji(c) for c in surface):
            token = text_parts(reader, surface)  # a headword JMdict does not know (気が付く): read the written form
        parts += token if token is not None else [(surface, None)]
        position = found + len(surface)
    if position < len(text):
        parts += _gap(reader, text[position:])
    return _finish(text, parts)


# 何 is なん before だ, で, の, て, と and counters, and なに before を, が, か, も: the dictionary lists both readings.
_NANI_BEFORE = ("を", "が", "か", "も", "に", "より")
_NAN_BEFORE = ("で", "だ", "の", "て", "と", "ん", "人", "回", "度", "時", "日", "年", "個", "分", "番", "故", "者", "なら", "とも")


def adjust_nani(parts: list[tuple[str, str | None]]) -> list[tuple[str, str | None]]:
    out = list(parts)
    for i, (text, reading) in enumerate(out):
        if text == "何" and reading in ("なに", "なん") and i + 1 < len(out):
            after = out[i + 1][0]
            if after.startswith(_NANI_BEFORE):
                out[i] = (text, "なに")
            elif after.startswith(_NAN_BEFORE):
                out[i] = (text, "なん")
    return out


def _gap(reader: Reader, text: str) -> list[tuple[str, str | None]]:
    """Text the index has no token for (a name, say): read from the dictionary alone."""
    return text_parts(reader, text) if any(is_kanji(c) for c in text) else [(text, None)]


def _finish(text: str, parts: list[tuple[str, str | None]]) -> str | None:
    if "".join(t for t, _ in parts) != text:
        return None
    parts = adjust_nani(parts)
    merged: list[tuple[str, str | None]] = []
    for part in parts:  # neighbouring plain pieces join up
        if merged and merged[-1][1] is None and part[1] is None:
            merged[-1] = (merged[-1][0] + part[0], None)
        else:
            merged.append(part)
    return markup(merged) if any(r for _, r in merged) else None


# --------------------------------------------------------------------------------------------------
# Hand-written text without tokens (grammar examples)


def text_markup(reader: Reader, text: str) -> str | None:
    """Furigana markup for any text from the dictionary alone (see [text_parts])."""
    if any(c in text for c in "{|}") or not any(is_kanji(c) for c in text):
        return None
    return _finish(text, text_parts(reader, text))


def text_parts(reader: Reader, text: str) -> list[tuple[str, str | None]]:
    """Furigana markup for any text, from the dictionary alone: the longest known spelling at each kanji, plus
    conjugated verbs and い-adjectives by their stem. Less exact than the Tatoeba route, so it is for short
    hand-written sentences."""
    longest = max((len(k) for k in reader.spell), default=1)
    stem_longest = max((len(k) for k in reader.stems), default=1)
    parts: list[tuple[str, str | None]] = []
    plain = ""
    i = 0
    while i < len(text):
        if not is_kanji(text[i]):
            plain += text[i]
            i += 1
            continue
        hit = None
        # an honorific お or ご in front belongs to the word: お客様 is おきゃくさま, not 客 + 様 (よう)
        if plain[-1:] in ("お", "ご"):
            for length in range(min(longest, len(text) - i), 0, -1):
                spelling = plain[-1] + text[i : i + length]
                candidate = reader.choose(spelling)
                if candidate is not None:
                    plain = plain[:-1]
                    hit = (length, align(spelling, candidate.reading))
                    break
            if hit is not None:
                if plain:
                    parts.append((plain, None))
                    plain = ""
                parts += hit[1]
                i += hit[0]
                continue
        # a stem of a conjugated word first when it is longer than any plain spelling at this position
        for length in range(min(longest, len(text) - i), 0, -1):
            spelling = text[i : i + length]
            candidate = reader.choose(spelling)
            if (
                candidate is not None and any(is_kanji(c) for c in spelling) and not compound_with_particle(reader, spelling)
                # a single kanji must be an everyday word (熟 is つくづく only on its own), a longer spelling is trusted
                and (len(spelling) >= 2 or candidate.common > 0 or reader.rank(spelling, candidate.reading) < 99)
            ):
                hit = (length, align(spelling, candidate.reading))
                break
        stem_hit = None
        for length in range(min(stem_longest, len(text) - i), 0, -1):
            stem = text[i : i + length]
            following = text[i + length : i + length + 1]
            options = reader.stems.get(stem, [])
            if stem == "来":
                stem_hit = (length, [(stem, come_reading(following))]) if following and following in COME_FOLLOWERS else None
                break
            # only everyday verbs: a rare one (朝起きる) would swallow words that are really two
            ok = [o for o in options if stem_ok(o[1], o[2], following) and (o[3] > 0 or reader.rank(stem + o[2], o[0] + o[2]) < 99)]
            if ok:
                # the same stem can belong to several verbs (行く / 行う): the JLPT reading wins
                spelling_options = sorted(ok, key=lambda o: (reader.rank(stem + o[2], o[0] + o[2]), len(o[0])))
                stem_hit = (length, align(stem, spelling_options[0][0]))
                break
        # A conjugated stem followed by the right kana beats a bare spelling of the same length: 書いて is 書く, not the
        # noun 書 (ふみ); 来ました is 来る, not 来 (らい).
        if stem_hit and (hit is None or stem_hit[0] >= hit[0]):
            hit = stem_hit
        # 雨が降りました: rain and snow fall (ふ), people get off (お)
        if hit and text[i : i + 2] == "降り" and any(c in text[max(0, i - 2) : i] for c in "雨雪雹霰") and hit[0] >= 2:
            hit = (1, [("降", "ふ")])
        if hit is None:
            plain += text[i]
            i += 1
            continue
        if plain:
            parts.append((plain, None))
            plain = ""
        parts += hit[1]
        i += hit[0]
    if plain:
        parts.append((plain, None))
    return parts
