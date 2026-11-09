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


def attach_jmdict(words: list[Word], jmdict: dict) -> None:
    """Fills in the JMdict entry id and part of speech for words found in JMdict."""
    by_form: dict[tuple[str, str], dict] = {}
    by_kana: dict[str, dict] = {}
    for entry in jmdict["words"]:
        kanas = [k["text"] for k in entry["kana"]]
        for kanji in entry["kanji"]:
            for kana in kanas:
                by_form.setdefault((kanji["text"], kana), entry)
        if not entry["kanji"]:
            for kana in kanas:
                by_kana.setdefault(kana, entry)
    for word in words:
        entry = by_form.get((word.expression, word.reading))
        if entry is None and word.expression == word.reading:
            entry = by_kana.get(word.reading)
        if entry is not None:
            word.jmdict_id = int(entry["id"])
            word.parts_of_speech = ",".join(entry["sense"][0]["partOfSpeech"]) or None
            word.jmdict_forms = [k["text"] for k in entry["kanji"]]


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
        return token.reading == word.reading or token.form == word.reading
    return False


def sentence_score(word: Word, token: IndexToken, text: str) -> tuple:
    """Lower is better. Prefers sentences that write a kanji word in kanji (あった could be 会う or
    遭う), then verified examples, then sentences close to a comfortable length."""
    surface = token.form or token.headword
    kana_only = bool(word.allowed_kanji) and not any(is_kanji(c) for c in surface)
    return (kana_only, not token.good_example, abs(len(text) - IDEAL_SENTENCE_LENGTH), text)


def pick_sentences(
    words: list[Word],
    indexed: list[IndexedSentence],
    japanese: dict[int, str],
    english: dict[int, str],
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
                score = sentence_score(word, token, text)
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
        "INSERT INTO sentence (id, japanese, english, verified) VALUES (?, ?, ?, ?)",
        [(sid, jp, en, int(good)) for sid, (jp, en, good) in sorted(sentences.items())],
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
        "INSERT INTO grammar_example (grammar_id, rank, japanese, english, answer) VALUES (?, ?, ?, ?, ?)",
        [(g["id"], rank, e["jp"], e["en"], e["answer"]) for g in grammar for rank, e in enumerate(g["examples"])],
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
    attach_jmdict(words, read_zipped_json(fetch("jmdict-eng.json.zip")))

    print("Kanji")
    kanji = build_kanji(
        json.loads(fetch("kanji.json").read_text(encoding="utf-8")),
        read_zipped_json(fetch("kanjidic2-en.json.zip")),
    )

    print("Example sentences")
    sentences = pick_sentences(
        words,
        parse_indices(read_indices(fetch("jpn_indices.tar.bz2"))),
        read_tatoeba_sentences(fetch("jpn_sentences.tsv.bz2")),
        read_tatoeba_sentences(fetch("eng_sentences.tsv.bz2")),
    )

    grammar = json.loads(GRAMMAR.read_text(encoding="utf-8"))

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
    write_database(OUTPUT, latest_schema(), words, kanji, sentences, grammar, info)
    save_registry(WORD_IDS, registry)

    with_examples = sum(1 for w in words if w.sentence_ids)
    print(f"Wrote {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size / 1e6:.1f} MB)")
    for n in range(5, 0, -1):
        print(f"  N{n}: {sum(w.jlpt == n for w in words)} words, {sum(k.jlpt == n for k in kanji)} kanji")
    print(f"  {len(sentences)} sentences; {with_examples}/{len(words)} words have examples;"
          f" {sum(w.jmdict_id is not None for w in words)} matched in JMdict")


if __name__ == "__main__":
    main()
