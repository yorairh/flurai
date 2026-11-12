"""Builds app/src/main/assets/content.db: the study content shipped inside the FluRai app.

Sources (see SOURCES below and the in-app Licences screen):
  * JLPT vocabulary lists N5-N1: jamsinclair/open-anki-jlpt-decks (MIT; word lists by Jonathan Waller, CC BY)
  * JMdict (part of speech, entry ids) and KANJIDIC2 (kanji readings and meanings), via
    scriptin/jmdict-simplified (EDRDG licence, CC BY-SA 4.0)
  * JLPT kanji levels: davidluzgouveia/kanji-data (MIT; levels by Jonathan Waller)
  * Example sentences: Tatoeba (CC BY 2.0 FR), linked to words through Tatoeba's Japanese index

The table layout comes from Room's exported schema (app/schemas/...ContentDatabase/<version>.json),
so the file always matches what the app expects. Word ids are kept stable across rebuilds by
tools/content/word_ids.tsv (user progress refers to them), so commit that file after a rebuild.

Usage:  python tools/content/build_content.py            (downloads into tools/content/.cache)
"""

from __future__ import annotations

import bz2
import csv
import io
import json
import re
import sqlite3
import sys
import tarfile
import urllib.request
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import ruby  # noqa: E402  (furigana markup for sentences, see ruby.py)
ROOT = HERE.parent.parent
CACHE = HERE / ".cache"
WORD_IDS = HERE / "word_ids.tsv"
GRAMMAR = HERE / "grammar.json"
WORD_IDS_HEADER = "guid\tword_id"
SCHEMA_DIR = ROOT / "app" / "schemas" / "com.flurai.app.core.content.ContentDatabase"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "content.db"

JLPT_DECKS_COMMIT = "1ad66734417aca9dbcca6b2d5ee440cb13ab3ba0"
KANJI_DATA_COMMIT = "00fd7079c3890f430759536f91aa5e854ec0ca4f"
JMDICT_SIMPLIFIED_TAG = "3.6.2+20261102210542"

_JMS = f"https://github.com/scriptin/jmdict-simplified/releases/download/{JMDICT_SIMPLIFIED_TAG.replace('+', '%2B')}"
SOURCES = {
    **{
        f"n{n}.csv": f"https://raw.githubusercontent.com/jamsinclair/open-anki-jlpt-decks/{JLPT_DECKS_COMMIT}/src/n{n}.csv"
        for n in range(1, 6)
    },
    "kanji.json": f"https://raw.githubusercontent.com/davidluzgouveia/kanji-data/{KANJI_DATA_COMMIT}/kanji.json",
    "jmdict-eng.json.zip": f"{_JMS}/jmdict-eng-{JMDICT_SIMPLIFIED_TAG}.json.zip",
    "kanjidic2-en.json.zip": f"{_JMS}/kanjidic2-en-{JMDICT_SIMPLIFIED_TAG}.json.zip",
    "jpn_indices.tar.bz2": "https://downloads.tatoeba.org/exports/jpn_indices.tar.bz2",
    "jpn_sentences.tsv.bz2": "https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2",
    "eng_sentences.tsv.bz2": "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2",
}

MAX_SENTENCES_PER_WORD = 3
#: Extra sentences added only for Reading practice, per JLPT level (on top of the word examples).
READING_SENTENCES_PER_LEVEL = {5: 400, 4: 400, 3: 300, 2: 200, 1: 200}
READING_LENGTH = (8, 30)
#: Extra practice sentences per grammar point, found in Tatoeba; ranks from here up are not listed on the lesson page.
EXTRA_GRAMMAR_EXAMPLES = 2
EXTRA_EXAMPLE_RANK = 10
MIN_EXTRA_ANSWER = 3  # shorter answers (particles) match too many unrelated sentences
IDEAL_READING_LENGTH = 16
MAX_SENTENCE_LENGTH = 40  # characters; long sentences make poor flashcard examples
IDEAL_SENTENCE_LENGTH = 14


# --------------------------------------------------------------------------------------------
# Downloads


def fetch(name: str) -> Path:
    """Returns the cached file, downloading it on first use."""
    CACHE.mkdir(exist_ok=True)
    path = CACHE / name
    if not path.exists():
        print(f"  downloading {name} ...", flush=True)
        request = urllib.request.Request(SOURCES[name], headers={"User-Agent": "FluRai-content-pipeline"})
        with urllib.request.urlopen(request) as response, open(path.with_suffix(".part"), "wb") as out:
            out.write(response.read())
        path.with_suffix(".part").rename(path)
    return path


def read_zipped_json(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        (name,) = [n for n in z.namelist() if n.endswith(".json")]
        return json.loads(z.read(name))


# --------------------------------------------------------------------------------------------
# Vocabulary


@dataclass
class Word:
    guid: str
    expression: str
    reading: str
    other_forms: list[str]
    meanings: str
    jlpt: int
    genki_lesson: int | None
    id: int = 0
    jmdict_id: int | None = None
    parts_of_speech: str | None = None
    #: Every kanji spelling JMdict lists for the entry (e.g. 有る for 在る), used to find examples.
    jmdict_forms: list[str] = field(default_factory=list)
    sentence_ids: list[int] = field(default_factory=list)

    @property
    def spellings(self) -> set[str]:
        return {self.expression, *self.other_forms, *self.jmdict_forms}

    @property
    def allowed_kanji(self) -> set[str]:
        """Kanji this word is written with in the list. JMdict entries can merge words that
        differ in meaning by kanji (会う "meet" vs 遭う "run into"), so only the list's own
        spellings count when checking a sentence."""
        return {c for form in (self.expression, *self.other_forms) for c in form if is_kanji(c)}

    @property
    def is_affix(self) -> bool:
        """Prefixes and suffixes such as ～人 or 何～ (no example sentences for these)."""
        return "～" in self.expression


_NOTE = re.compile(r"\s*\([^)]*\)\s*$")  # trailing notes such as "すみません (かん)"
_GENKI_LESSON = re.compile(r"^Genki_Ln\.(\d+)$")


def is_kanji(char: str) -> bool:
    return "一" <= char <= "鿿" or char == "々"


def clean_form(text: str) -> str:
    return _NOTE.sub("", text).strip()


def split_forms(text: str) -> list[str]:
    return [f for f in (clean_form(part) for part in text.split(";")) if f]


def parse_jlpt_csv(text: str, jlpt: int) -> list[Word]:
    words = []
    for row in csv.DictReader(io.StringIO(text)):
        forms = split_forms(row["expression"])
        readings = split_forms(row["reading"])
        if not forms or not readings:
            continue
        lessons = [int(m.group(1)) for t in row["tags"].split() if (m := _GENKI_LESSON.match(t))]
        words.append(
            Word(
                guid=row["guid"],
                expression=forms[0],
                reading=readings[0],
                other_forms=forms[1:],
                meanings=row["meaning"].strip(),
                jlpt=jlpt,
                genki_lesson=min(lessons) if lessons else None,
            )
        )
    return words


def merge_levels(levels: dict[int, list[Word]]) -> list[Word]:
    """Combines the per-level lists, easiest first. A word listed at two levels keeps the easier one."""
    seen_guids: set[str] = set()
    seen_words: set[tuple[str, str]] = set()
    merged = []
    for jlpt in sorted(levels, reverse=True):  # 5 (N5) first
        for word in levels[jlpt]:
            key = (word.expression, word.reading)
            if word.guid in seen_guids or key in seen_words:
                continue
            seen_guids.add(word.guid)
            seen_words.add(key)
            merged.append(word)
    return merged


def assign_ids(words: list[Word], registry: dict[str, int]) -> dict[str, int]:
    """Gives every word a stable id from the registry, adding new guids at the end."""
    next_id = max(registry.values(), default=0) + 1
    for word in words:
        if word.guid not in registry:
            registry[word.guid] = next_id
            next_id += 1
        word.id = registry[word.guid]
    return registry


def load_registry(path: Path) -> dict[str, int]:
    if not path.exists():
        return {}
    registry = {}
    # No comment lines: Anki guids may themselves start with "#". The first line is the header.
    for line in path.read_text(encoding="utf-8").splitlines()[1:]:
        if line:
            guid, word_id = line.split("\t")
            registry[guid] = int(word_id)
    return registry


def save_registry(path: Path, registry: dict[str, int]) -> None:
    """Stable ids (Anki guid -> word.id). Never renumber: user progress refers to these ids."""
    lines = [WORD_IDS_HEADER]
    lines +=[f"{guid}\t{word_id}" for guid, word_id in sorted(registry.items(), key=lambda kv: kv[1])]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


_STOP_WORDS = frozenset("a an the to of in on at or and be is for with by as it one".split())


def gloss_words(text: str) -> set[str]:
    return set(re.findall(r"[a-z]+", text.lower())) - _STOP_WORDS


def best_entry(entries: list[dict], meanings: str) -> dict | None:
    """The JMdict entry whose glosses share the most words with a deck meaning. A kana word such as ああ or
    いくら has several entries (a salmon roe, a price); with no overlap at all only a sole candidate is safe to take."""
    wanted = gloss_words(meanings)
    scored = [(len(wanted & {w for s in e["sense"] for g in s["gloss"] for w in gloss_words(g["text"])}), e) for e in entries]
    top = max((n for n, _ in scored), default=0)
    if top > 0:
        return next(e for n, e in scored if n == top)
    return entries[0] if len(entries) == 1 else None


def attach_jmdict(words: list[Word], jmdict: dict) -> None:
    """Fills in the JMdict entry id and part of speech for words found in JMdict."""
    by_form: dict[tuple[str, str], dict] = {}
    by_kana: dict[str, list[dict]] = {}
    for entry in jmdict["words"]:
        kanas = [k["text"] for k in entry["kana"]]
        for kanji in entry["kanji"]:
            for kana in kanas:
                by_form.setdefault((kanji["text"], kana), entry)
        for kana in kanas:
            by_kana.setdefault(kana, []).append(entry)  # also entries with kanji: 通貨 is つうか too
    for word in words:
        entry = by_form.get((word.expression, word.reading))
        if entry is None and word.expression == word.reading:
            entry = best_entry(by_kana.get(word.reading, []), word.meanings)
        if entry is not None:
            word.jmdict_id = int(entry["id"])
            word.parts_of_speech = ",".join(entry["sense"][0]["partOfSpeech"]) or None
            word.jmdict_forms = [k["text"] for k in entry["kanji"]]


def tidy_meanings(words: list[Word]) -> None:
    """Small wording fixes for the deck's meanings. "(formal)" on お母さん, お父さん ... means polite, not formal."""
    for word in words:
        if word.expression.startswith("お") and word.expression.endswith("さん"):
            word.meanings = word.meanings.replace("(formal)", "(polite)")


# --------------------------------------------------------------------------------------------
# Kanji


@dataclass
class Kanji:
    literal: str
    jlpt: int
    meanings: str
    onyomi: str
    kunyomi: str
    stroke_count: int
    grade: int | None
    frequency: int | None


def build_kanji(levels: dict, kanjidic: dict) -> list[Kanji]:
    """Kanji that have a (new) JLPT level, with readings and meanings from KANJIDIC2."""
    jlpt_of = {literal: info["jlpt_new"] for literal, info in levels.items() if info.get("jlpt_new")}
    result = []
    for char in kanjidic["characters"]:
        literal = char["literal"]
        if literal not in jlpt_of:
            continue
        on, kun, meanings = [], [], []
        for group in (char.get("readingMeaning") or {}).get("groups", []):
            for reading in group["readings"]:
                if reading["type"] == "ja_on":
                    on.append(reading["value"])
                elif reading["type"] == "ja_kun":
                    kun.append(reading["value"])
            meanings += [m["value"] for m in group["meanings"] if m["lang"] == "en"]
        misc = char["misc"]
        result.append(
            Kanji(
                literal=literal,
                jlpt=jlpt_of[literal],
                meanings="; ".join(meanings),
                onyomi="、".join(on),
                kunyomi="、".join(kun),
                stroke_count=misc["strokeCounts"][0],
                grade=misc.get("grade"),
                frequency=misc.get("frequency"),
            )
        )
    return sorted(result, key=lambda k: (-k.jlpt, k.frequency or 99999, k.literal))


# --------------------------------------------------------------------------------------------
# Example sentences


@dataclass
class IndexToken:
    headword: str
    reading: str | None
    form: str | None
    good_example: bool


# headword, (reading), (#jmdict id), [sense], {form as written in the sentence}, ~ = good example
_TOKEN = re.compile(
    r"^(?P<head>[^(\[{~]+)"
    r"(?:\((?P<reading>[^)#]*)\))?"
    r"(?:\(#\d+\))?"
    r"(?:\[\d+\])?"
    r"(?:\{(?P<form>[^}]*)\})?"
    r"(?P<good>~)?$"
)


def parse_index_token(token: str) -> IndexToken | None:
    match = _TOKEN.match(token)
    if not match:
        return None
    return IndexToken(
        headword=match["head"],
        reading=match["reading"] or None,
        form=match["form"] or None,
        good_example=bool(match["good"]),
    )


@dataclass
class IndexedSentence:
    japanese_id: int
    english_id: int
    tokens: list[IndexToken]


def parse_indices(text: str) -> list[IndexedSentence]:
    result = []
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        tokens = [t for t in (parse_index_token(raw) for raw in parts[2].split()) if t]
        result.append(IndexedSentence(int(parts[0]), int(parts[1]), tokens))
    return result


def token_matches(word: Word, token: IndexToken) -> bool:
    surface = token.form or token.headword  # how the word is written in the sentence
    if any(is_kanji(c) and c not in word.allowed_kanji for c in surface) and surface != word.reading:
        return False
    if token.headword in word.spellings:
        return token.reading is None or token.reading == word.reading
    # Kana-only words are often indexed under their kanji spelling, e.g. 直ぐに{すぐに} for すぐに.
    if word.expression == word.reading:
        if token.reading == word.reading or token.form == word.reading:
            # The headword must be one of this entry's own spellings: ええ "yes" is not 良い read ええ.
            return token.headword in word.spellings or word.jmdict_id is None
        return False
    return False


def sentence_score(word: Word, token: IndexToken, text: str, grade: int | None = None) -> tuple:
    """Lower is better. Prefers sentences no harder than the word itself (a learner of an N5 word should
    not meet it among N1 vocabulary), then sentences that write a kanji word in kanji (あった could be
    会う or 遭う), then verified examples, then sentences close to a comfortable length."""
    surface = token.form or token.headword
    kana_only = bool(word.allowed_kanji) and not any(is_kanji(c) for c in surface)
    too_hard = 0 if grade is not None and grade >= word.jlpt else (word.jlpt - grade if grade is not None else 9)
    return (too_hard, kana_only, not token.good_example, abs(len(text) - IDEAL_SENTENCE_LENGTH), text)


def pick_sentences(
    words: list[Word],
    indexed: list[IndexedSentence],
    japanese: dict[int, str],
    english: dict[int, str],
    grades: dict[int, int | None] | None = None,
) -> dict[int, tuple[str, str, bool]]:
    """Chooses up to MAX_SENTENCES_PER_WORD examples per word; returns the sentences used."""
    by_headword: dict[str, list[tuple[IndexToken, IndexedSentence]]] = {}
    for sentence in indexed:
        if sentence.japanese_id not in japanese or sentence.english_id not in english:
            continue
        if len(japanese[sentence.japanese_id]) > MAX_SENTENCE_LENGTH:
            continue
        for token in sentence.tokens:
            by_headword.setdefault(token.headword, []).append((token, sentence))
            if token.reading:
                by_headword.setdefault(token.reading, []).append((token, sentence))
            if token.form:
                by_headword.setdefault(token.form, []).append((token, sentence))

    used: dict[int, tuple[str, str, bool]] = {}
    for word in words:
        if word.is_affix:
            continue
        candidates: dict[int, tuple] = {}
        for key in word.spellings | {word.reading}:
            for token, sentence in by_headword.get(key, []):
                if not token_matches(word, token):
                    continue
                text = japanese[sentence.japanese_id]
                score = sentence_score(word, token, text, (grades or {}).get(sentence.japanese_id))
                best = candidates.get(sentence.japanese_id)
                if best is None or score < best[0]:
                    candidates[sentence.japanese_id] = (score, sentence, token.good_example)
        chosen = sorted(candidates.values(), key=lambda c: c[0])[:MAX_SENTENCES_PER_WORD]
        for _, sentence, good in chosen:
            word.sentence_ids.append(sentence.japanese_id)
            previous = used.get(sentence.japanese_id)
            used[sentence.japanese_id] = (
                japanese[sentence.japanese_id],
                english[sentence.english_id],
                good or (previous is not None and previous[2]),
            )
    return used


# --------------------------------------------------------------------------------------------
# Sentence levels
#
# A sentence is as hard as its hardest part: its hardest word (from the JLPT lists) or the hardest
# grammar it uses. Tatoeba's index gives the dictionary form of every word in a sentence, so the
# words are known exactly; grammar is found with a short list of patterns.

#: Particles and endings that the JLPT word lists do not carry as words. They never make a sentence harder.
FUNCTION_WORDS = frozenset(
    "は が を に で と も の へ や か ね よ ぞ わ さ な から まで より ので のに けど けれど でも だ です ます ない "
    "た て だろう でしょう たい らしい ばかり だけ しか ほど くらい ぐらい など って という ところ こと もの ため "
    "ほう よう そう まま みたい ず ぬ ん".split()
)

#: (pattern, level) pairs: the sentence is at most this level (a smaller JLPT number is harder) when it matches.
GRAMMAR_GATES: list[tuple[re.Pattern, int]] = [
    (re.compile(p), level)
    for p, level in [
        # N4 grammar
        (r"たことが|たことは", 4), (r"ながら", 4), (r"たり.*たり", 4), (r"ことができ|ことが出来", 4),
        (r"ように|ような|ようだ|ようです", 4), (r"と思|と思い", 4), (r"かもしれ", 4),
        (r"なければ|なくては|なくても|なきゃ", 4), (r"たほうが|た方が", 4), (r"すぎ|過ぎ", 4),
        (r"ので", 4), (r"ために|為に", 4), (r"てくれ|てあげ|てもら|ていただ|てやっ", 4),
        (r"(?<!と)ても", 4), (r"たら", 4), (r"[えけげせてねべめれ]ば", 4), (r"つもり|予定", 4),
        (r"ところだ|ところです|たばかり", 4), (r"はず", 4), (r"みたい|らしい", 4), (r"のに", 4), (r"ないで", 4),
        (r"という", 4), (r"なさい", 4), (r"始め|終わ|続け", 4), (r"やすい|にくい", 4), (r"んだ|んです|のだ|のです", 4),
        (r"させ", 3), (r"られ", 3),
        # N3 and harder: passive/causative, obligation, keigo, colloquial and written styles
        (r"べき|べく", 3), (r"おいで|いらっしゃ|ございま|申し|致し|いたし|くださる|くださっ|なさる|おっしゃ|召し上が", 3),
        (r"ちゃ|ちゃう|じゃん|っす|やがる|てんだ|なにやっ|てる|でる", 3),
        (r"ざるを得|にもかかわらず|わけだ|わけです|ことになっ|ことにな", 3),
    ]
]

#: Characters that mean the text is a fragment or contains a placeholder, not a sentence to read.
_UNREADABLE = re.compile(r"[～〜…⋯＊*()（）]")


def is_hiragana_only(text: str) -> bool:
    return bool(text) and all("぀" <= c <= "ゟ" or c == "ー" for c in text)


def is_katakana_only(text: str) -> bool:
    return bool(text) and all("゠" <= c <= "ヿ" for c in text)


#: Everyday names and compounds the JLPT lists leave out (or rank oddly, 日本 is N3 there). Treated as N5.
COMMON_WORDS = frozenset(
    "日本 日本人 日本語 東京 京都 大阪 中国 韓国 アメリカ イギリス フランス ドイツ 英語 外国 外国人 今日 今朝 今晩 今夜 毎日 毎朝 毎晩 "
    "お母さん お父さん お兄さん お姉さん 子供 子供たち 人々 みんな 皆 皆さん 誰も 何も 何の 今まで 一人 二人 三人 一時間 時間 週間 ヶ月 ヵ月 か月".split()
)

_SUFFIXES = ("たち", "達", "人", "語", "さん", "ちゃん")
_PARTICLE_ENDINGS = ("まで", "に", "で", "も", "の")
_NUMERALS = "一二三四五六七八九十百千万0123456789０１２３４５６７８９"


def derived_keys(key: str, depth: int = 2):
    """The key itself, then simpler forms of it: 子供たち -> 子供, 一人で -> 一人 -> 人, 早く -> 早い."""
    yield key
    if depth == 0:
        return
    simpler = []
    for suffix in _SUFFIXES + _PARTICLE_ENDINGS:
        if key.endswith(suffix) and len(key) > len(suffix):
            simpler.append(key[: -len(suffix)])
    if key.endswith("く") and len(key) > 1:
        simpler.append(key[:-1] + "い")
    stripped = key.lstrip(_NUMERALS)
    if stripped and stripped != key:
        simpler.append(stripped)
    for form in simpler:
        yield from derived_keys(form, depth - 1)


def word_levels(words: list["Word"]) -> dict[str, int]:
    """Spelling (or kana reading of a kana word) -> JLPT number of the easiest word written that way."""
    levels: dict[str, int] = {}
    for w in words:
        if w.is_affix:
            continue
        keys = set(w.spellings)
        if w.expression == w.reading:
            keys.add(w.reading)
        for key in keys:
            levels[key] = max(levels.get(key, 0), w.jlpt)
    for key in COMMON_WORDS:
        levels[key] = 5
    return levels


def token_level(token: "IndexToken", levels: dict[str, int]) -> int | None:
    """The JLPT number a token asks of the reader, or None when it asks nothing (particles, endings)."""
    # The form written in the sentence comes first: 未だ is read まだ here, not いまだ.
    for key in (token.form, token.reading, token.headword):
        for candidate in derived_keys(key) if key else ():
            if candidate in levels:
                return levels[candidate]
    # What the reader sees is the form written in the sentence: 可き is read as べき, so it is judged as kana.
    shown = token.form or token.headword
    if token.headword in FUNCTION_WORDS or shown in FUNCTION_WORDS or len(shown) <= 1:
        return None
    if any(is_kanji(c) for c in shown):
        return 1  # a kanji word outside the JLPT lists: rare, names and specialist words
    if is_katakana_only(shown):
        return 3  # loanwords and names outside the lists
    return 4 if len(shown) >= 3 else None  # a hiragana word outside the lists


_A_ROW = {"く": "か", "ぐ": "が", "す": "さ", "つ": "た", "ぬ": "な", "ぶ": "ば", "む": "ま", "る": "ら", "う": "わ"}


def is_passive_or_causative(token: "IndexToken") -> bool:
    """A godan verb in a passive or causative form: 書く -> 書かれた, 書かせた (the られ/させ gate covers ichidan verbs)."""
    head, form = token.headword, token.form
    if not form or len(head) < 2 or head[-1] not in _A_ROW:
        return False
    stem = head[:-1] + _A_ROW[head[-1]]
    return form.startswith(stem + "れ") or form.startswith(stem + "せ")


def grade_sentence(text: str, tokens: list["IndexToken"], levels: dict[str, int]) -> int | None:
    """JLPT number of a sentence (5 = N5 ... 1 = N1), or None when it is not fit for reading practice."""
    if not tokens or _UNREADABLE.search(text):
        return None
    grade = 5
    for token in tokens:
        level = token_level(token, levels)
        if level is not None:
            grade = min(grade, level)
    for pattern, level in GRAMMAR_GATES:
        if pattern.search(text):
            grade = min(grade, level)
    if any(is_passive_or_causative(t) for t in tokens):
        grade = min(grade, 3)
    return grade


def pick_reading_sentences(
    indexed: list["IndexedSentence"],
    japanese: dict[int, str],
    english: dict[int, str],
    grades: dict[int, int | None],
    taken: set[int],
    per_level: dict[int, int] = READING_SENTENCES_PER_LEVEL,
) -> tuple[dict[int, tuple[str, str, bool]], dict[int, int]]:
    """Sentences for Reading practice beyond the word examples: graded, readable length, a spread of
    lengths, verified ones first. Returns the sentences and their levels."""
    candidates: dict[int, list[tuple]] = {n: [] for n in per_level}
    for sentence in indexed:
        text = japanese.get(sentence.japanese_id)
        if text is None or sentence.english_id not in english or sentence.japanese_id in taken:
            continue
        if not READING_LENGTH[0] <= len(text) <= READING_LENGTH[1]:
            continue
        grade = grades.get(sentence.japanese_id)
        if grade not in candidates:
            continue
        verified = any(t.good_example for t in sentence.tokens)
        # A fixed scramble of the id spreads the picks over the data instead of favoring early ids.
        scatter = (sentence.japanese_id * 2654435761) % 1000003
        candidates[grade].append(((not verified, abs(len(text) - IDEAL_READING_LENGTH) // 4, scatter), sentence, text, verified, grade))
    picked: dict[int, tuple[str, str, bool]] = {}
    graded: dict[int, int] = {}
    for grade, items in candidates.items():
        for _, sentence, text, verified, level in sorted(items, key=lambda i: i[0])[: per_level[grade]]:
            picked[sentence.japanese_id] = (text, english[sentence.english_id], verified)
            graded[sentence.japanese_id] = level
    return picked, graded


def token_ends(text: str, tokens: list["IndexToken"]) -> set[int]:
    """Where each word of the sentence ends (character offsets), found by laying the index's tokens over the text.
    A word that cannot be found is skipped, so a missing boundary means "do not trust this match"."""
    ends: set[int] = set()
    position = 0
    for token in tokens:
        surface = token.form or token.headword
        found = text.find(surface, position)
        if found < 0:
            continue
        position = found + len(surface)
        ends.add(position)
    return ends


def pick_grammar_extras(
    grammar: list[dict],
    indexed: list["IndexedSentence"],
    japanese: dict[int, str],
    english: dict[int, str],
    grades: dict[int, int | None],
    per_point: int = EXTRA_GRAMMAR_EXAMPLES,
) -> dict[int, list[dict]]:
    """More practice sentences for grammar points from Tatoeba, so a practice question is not the lesson's own
    example. A sentence qualifies for a point when it contains one of the point's answers exactly once and is
    not harder than the point's level. Returns {grammar id: [{"jp", "en", "answer"}]}."""
    owners: dict[str, list[dict]] = {}
    for point in grammar:
        for answer in {e["answer"] for e in point["examples"]}:
            if len(answer) >= MIN_EXTRA_ANSWER:
                owners.setdefault(answer, []).append(point)
    own_text = {e["jp"] for point in grammar for e in point["examples"]}
    candidates: dict[int, list[tuple]] = {}
    for sentence in indexed:
        text = japanese.get(sentence.japanese_id)
        grade = grades.get(sentence.japanese_id)
        if text is None or grade is None or sentence.english_id not in english or text in own_text:
            continue
        if not READING_LENGTH[0] <= len(text) <= READING_LENGTH[1]:
            continue
        verified = any(t.good_example for t in sentence.tokens)
        scatter = (sentence.japanese_id * 2654435761) % 1000003
        ends = None
        for answer, points in owners.items():
            if text.count(answer) != 1:
                continue
            # The pattern must end where a word ends: がたい is not the start of たいへん, みたい is not 見たいと.
            if ends is None:
                ends = token_ends(text, sentence.tokens)
            if text.index(answer) + len(answer) not in ends:
                continue
            for point in points:
                if grade >= point["jlpt"]:  # a smaller number is harder
                    candidates.setdefault(point["id"], []).append(
                        ((not verified, abs(len(text) - IDEAL_READING_LENGTH) // 4, scatter), text, english[sentence.english_id], answer)
                    )
    extras: dict[int, list[dict]] = {}
    for point_id, items in candidates.items():
        chosen: list[dict] = []
        for _, text, translation, answer in sorted(items, key=lambda i: i[0]):
            if len(chosen) == per_point:
                break
            if all(c["jp"] != text for c in chosen):
                chosen.append({"jp": text, "en": translation, "answer": answer})
        extras[point_id] = chosen
    return extras


def read_tatoeba_sentences(path: Path) -> dict[int, str]:
    sentences = {}
    with bz2.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3:
                sentences[int(parts[0])] = parts[2]
    return sentences


def read_indices(path: Path) -> str:
    with tarfile.open(path, "r:bz2") as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("jpn_indices.csv"))
        return tar.extractfile(member).read().decode("utf-8")


# --------------------------------------------------------------------------------------------
# Database


def latest_schema() -> dict:
    files = sorted(SCHEMA_DIR.glob("*.json"), key=lambda p: int(p.stem))
    if not files:
        sys.exit(f"No Room schema in {SCHEMA_DIR}; build the app once (./gradlew kspDebugKotlin) first.")
    return json.loads(files[-1].read_text(encoding="utf-8"))


def create_tables(db: sqlite3.Connection, schema: dict) -> None:
    database = schema["database"]
    for entity in database["entities"]:
        table = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table))
    for query in database["setupQueries"]:
        db.execute(query)
    db.execute(f"PRAGMA user_version = {database['version']}")


def write_database(
    path: Path,
    schema: dict,
    words: list[Word],
    kanji: list[Kanji],
    sentences: dict[int, tuple[str, str, bool]],
    grammar: list[dict],
    info: dict[str, str],
    sentence_levels: dict[int, int | None] | None = None,
    sentence_ruby: dict[int, str | None] | None = None,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    db = sqlite3.connect(tmp)
    create_tables(db, schema)
    db.executemany(
        "INSERT INTO word (id, expression, reading, other_forms, meanings, jlpt, genki_lesson, jmdict_id, parts_of_speech)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                w.id, w.expression, w.reading, "; ".join(w.other_forms) or None, w.meanings, w.jlpt,
                w.genki_lesson, w.jmdict_id, w.parts_of_speech,
            )
            for w in words
        ],
    )
    db.executemany(
        "INSERT INTO kanji (literal, jlpt, meanings, onyomi, kunyomi, stroke_count, grade, frequency)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [(k.literal, k.jlpt, k.meanings, k.onyomi, k.kunyomi, k.stroke_count, k.grade, k.frequency) for k in kanji],
    )
    db.executemany(
        "INSERT INTO sentence (id, japanese, english, verified, jlpt, ruby) VALUES (?, ?, ?, ?, ?, ?)",
        [
            (sid, jp, en, int(good), (sentence_levels or {}).get(sid), (sentence_ruby or {}).get(sid))
            for sid, (jp, en, good) in sorted(sentences.items())
        ],
    )
    db.executemany(
        "INSERT INTO word_sentence (word_id, sentence_id, rank) VALUES (?, ?, ?)",
        [(w.id, sid, rank) for w in words for rank, sid in enumerate(w.sentence_ids)],
    )
    db.executemany(
        "INSERT INTO grammar_point (id, jlpt, pattern, title, meaning, formation, explanation) VALUES (?, ?, ?, ?, ?, ?, ?)",
        [(g["id"], g["jlpt"], g["pattern"], g["title"], g["meaning"], g["formation"], g["explanation"]) for g in grammar],
    )
    db.executemany(
        "INSERT INTO grammar_example (grammar_id, rank, japanese, english, answer, ruby) VALUES (?, ?, ?, ?, ?, ?)",
        [(g["id"], rank, e["jp"], e["en"], e["answer"], e.get("ruby")) for g in grammar for rank, e in enumerate(g["examples"])]
        + [(g["id"], EXTRA_EXAMPLE_RANK + i, e["jp"], e["en"], e["answer"], e.get("ruby")) for g in grammar for i, e in enumerate(g.get("extras", []))],
    )
    db.executemany("INSERT INTO content_info (key, value) VALUES (?, ?)", sorted(info.items()))
    db.commit()
    db.execute("VACUUM")
    db.close()
    path.unlink(missing_ok=True)
    tmp.rename(path)


# --------------------------------------------------------------------------------------------


def main() -> None:
    print("Vocabulary")
    levels = {n: parse_jlpt_csv(fetch(f"n{n}.csv").read_text(encoding="utf-8"), n) for n in range(1, 6)}
    words = merge_levels(levels)
    registry = assign_ids(words, load_registry(WORD_IDS))
    jmdict = read_zipped_json(fetch("jmdict-eng.json.zip"))
    attach_jmdict(words, jmdict)
    tidy_meanings(words)
    jlpt_readings: dict[str, list[str]] = {}
    for w in sorted(words, key=lambda w: -w.jlpt):  # N5 (the biggest number) first: the common reading
        if w.expression == w.reading:
            continue  # a kana word (あれ) does not decide how its kanji spelling (彼) is read
        for spelling in (w.expression, *w.other_forms):  # not JMdict's other spellings: they belong to other words
            if w.reading not in jlpt_readings.setdefault(spelling, []):
                jlpt_readings[spelling].append(w.reading)
    reader = ruby.Reader.build(jmdict["words"], jlpt_readings)

    print("Kanji")
    kanji = build_kanji(
        json.loads(fetch("kanji.json").read_text(encoding="utf-8")),
        read_zipped_json(fetch("kanjidic2-en.json.zip")),
    )

    print("Example sentences")
    indexed = parse_indices(read_indices(fetch("jpn_indices.tar.bz2")))
    japanese = read_tatoeba_sentences(fetch("jpn_sentences.tsv.bz2"))
    english = read_tatoeba_sentences(fetch("eng_sentences.tsv.bz2"))
    levels = word_levels(words)
    grades = {
        s.japanese_id: grade_sentence(japanese[s.japanese_id], s.tokens, levels)
        for s in indexed
        if s.japanese_id in japanese and len(japanese[s.japanese_id]) <= MAX_SENTENCE_LENGTH
    }
    sentences = pick_sentences(words, indexed, japanese, english, grades)
    sentence_levels = {sid: grades.get(sid) for sid in sentences}
    extra, extra_levels = pick_reading_sentences(indexed, japanese, english, grades, set(sentences))
    sentences.update(extra)
    sentence_levels.update(extra_levels)
    tokens_by_id = {s.japanese_id: [(t.headword, t.reading, t.form) for t in s.tokens] for s in indexed}
    sentence_ruby = {sid: ruby.sentence_markup(reader, jp, tokens_by_id.get(sid, [])) for sid, (jp, _, _) in sentences.items()}

    grammar = json.loads(GRAMMAR.read_text(encoding="utf-8"))
    extras = pick_grammar_extras(grammar, indexed, japanese, english, grades)
    for point in grammar:
        point["extras"] = extras.get(point["id"], [])
        for example in point["examples"] + point["extras"]:
            example["ruby"] = ruby.text_markup(reader, example["jp"])

    info = {
        "built_at": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "jlpt_decks_commit": JLPT_DECKS_COMMIT,
        "kanji_data_commit": KANJI_DATA_COMMIT,
        "jmdict_simplified": JMDICT_SIMPLIFIED_TAG,
        "word_count": str(len(words)),
        "kanji_count": str(len(kanji)),
        "sentence_count": str(len(sentences)),
        "grammar_count": str(len(grammar)),
    }
    write_database(OUTPUT, latest_schema(), words, kanji, sentences, grammar, info, sentence_levels, sentence_ruby)
    save_registry(WORD_IDS, registry)

    with_examples = sum(1 for w in words if w.sentence_ids)
    print(f"Wrote {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size / 1e6:.1f} MB)")
    for n in range(5, 0, -1):
        print(f"  N{n}: {sum(w.jlpt == n for w in words)} words, {sum(k.jlpt == n for k in kanji)} kanji")
    graded = {n: sum(1 for g in sentence_levels.values() if g == n) for n in range(5, 0, -1)}
    print(f"  sentence levels: {graded}, ungraded {sum(1 for g in sentence_levels.values() if g is None)}")
    print(f"  grammar: {sum(1 for g in grammar if g['extras'])}/{len(grammar)} points have extra practice sentences")
    print(f"  furigana: {sum(1 for v in sentence_ruby.values() if v)}/{len(sentence_ruby)} sentences, "
          f"{sum(1 for g in grammar for e in g['examples'] + g['extras'] if e.get('ruby'))} grammar examples")
    print(f"  {len(sentences)} sentences; {with_examples}/{len(words)} words have examples;"
          f" {sum(w.jmdict_id is not None for w in words)} matched in JMdict")


if __name__ == "__main__":
    main()
