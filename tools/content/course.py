"""The grammar course: loading, checking and turning tools/content/course/*.json into database rows.

One file per course entry (a lesson with a test, or a study note), named NNN-slug.json; the file
order is the course order. The pipeline fails on any problem found by `problems`, so a broken
lesson never reaches the app. Stdlib only.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

LESSON_KINDS = ("lesson", "note")
BLOCK_TYPES = ("heading", "text", "example", "diagram", "keypoints", "table")
CAR_ROLES = ("engine", "carriage", "topic", "zero", "plain", "white", "particle")
QUESTION_TYPES = ("choice", "tap", "order")
MIN_QUESTIONS = 20
#: A Japanese text of at least this many kana/kanji is a sentence: its question needs an English translation (`en`).
SENTENCE_LENGTH = 4
VIDEO_PREFIXES = ("https://www.youtube.com/watch?v=", "https://youtu.be/")


def load(directory: Path) -> list[dict]:
    """Every entry in course order (file name order)."""
    entries = []
    for position, path in enumerate(sorted(directory.glob("*.json")), start=1):
        entry = json.loads(path.read_text(encoding="utf-8"))
        entry["_file"] = path.name
        entry["_position"] = position
        entries.append(entry)
    return entries


def _text(value) -> bool:
    return isinstance(value, str) and value.strip() != ""


def _block_problems(where: str, block: dict) -> list[str]:
    out: list[str] = []
    kind = block.get("type")
    if kind not in BLOCK_TYPES:
        return [f"{where}: unknown block type {kind!r}"]
    if kind in ("heading", "text") and not _text(block.get("text")):
        out.append(f"{where}: {kind} needs text")
    if kind == "example":
        if not _text(block.get("jp")) or not _text(block.get("en")):
            out.append(f"{where}: example needs jp and en")
    if kind == "diagram":
        cars = block.get("cars")
        if not cars:
            out.append(f"{where}: diagram needs cars")
        else:
            for car in cars:
                if not _text(car.get("text")):
                    out.append(f"{where}: a car has no text")
                if car.get("role", "plain") not in CAR_ROLES:
                    out.append(f"{where}: unknown car role {car.get('role')!r}")
    if kind == "keypoints" and not block.get("items"):
        out.append(f"{where}: keypoints needs items")
    if kind == "table":
        rows = block.get("rows") or []
        if len(rows) < 2 or len({len(r) for r in rows}) != 1:
            out.append(f"{where}: table needs a header and rows of equal length")
    return out


def _question_problems(where: str, q: dict) -> list[str]:
    out: list[str] = []
    kind = q.get("type")
    if kind not in QUESTION_TYPES:
        return [f"{where}: unknown question type {kind!r}"]
    if not _text(q.get("instruction")):
        out.append(f"{where}: needs an instruction")
    if not _text(q.get("note")):
        out.append(f"{where}: needs a note (the one-line why)")
    options = q.get("options")
    if not isinstance(options, list) or not all(_text(o) for o in options):
        return out + [f"{where}: options must be a list of non-empty strings"]
    if kind == "choice":
        if not 2 <= len(options) <= 5:
            out.append(f"{where}: choice needs 2 to 5 options")
        if len(set(options)) != len(options):
            out.append(f"{where}: options repeat")
        if not isinstance(q.get("answer"), int) or not 0 <= q["answer"] < len(options):
            out.append(f"{where}: answer is not an option index")
    if kind == "tap":
        if len(options) < 2:
            out.append(f"{where}: tap needs at least 2 words")
        if not isinstance(q.get("answer"), int) or not 0 <= q["answer"] < len(options):
            out.append(f"{where}: answer is not a word index")
    if kind == "order":
        if not _text(q.get("prompt")):
            out.append(f"{where}: order needs a prompt (what to build)")
        if len(options) < 3:
            out.append(f"{where}: order needs at least 3 pieces")
        if len(set(options)) == 1:
            out.append(f"{where}: order pieces are all the same")
        for arrangement in q.get("alt", []):
            if sorted(arrangement) != sorted(options) or arrangement == options:
                out.append(f"{where}: an alt arrangement must reorder exactly the same pieces")
    elif q.get("alt"):
        out.append(f"{where}: only order questions have alt arrangements")
    return out


# -- new sentences, easy words -----------------------------------------------------------------------

_JAPANESE = re.compile(r"[぀-ヿ一-鿿々ー]")
_KANJI = re.compile(r"[一-鿿々]")
_GAP = re.compile(r"＿+")


def japanese_only(text: str) -> str:
    """Just the kana and kanji of [text]: no spaces, punctuation, brackets or English."""
    return "".join(_JAPANESE.findall(text))


def _without_brackets(text: str) -> str:
    return re.sub(r"[（(][^）)]*[）)]", "", text)


def question_japanese(q: dict) -> list[str]:
    """The Japanese texts a question shows: its prompt, the pieces of a tap or order question joined, and every longer option."""
    texts = [q.get("prompt", "")]
    options = q.get("options") or []
    if q.get("type") in ("tap", "order"):
        texts.append("".join(options))
    else:
        texts += [o for o in options if len(japanese_only(o)) >= SENTENCE_LENGTH + 1]
    return [t for t in texts if japanese_only(t)]


def has_sentence(q: dict) -> bool:
    """True when the question shows a Japanese sentence (so it needs an English translation)."""
    if q.get("type") in ("tap", "order"):
        return True
    return len(japanese_only(_GAP.sub("", q.get("prompt", "")))) >= SENTENCE_LENGTH


def reuses_example(text: str, examples: list[str]) -> str | None:
    """The example sentence of the lesson that [text] repeats (also with a gap, or inside a longer text), else None."""
    kind = _GAP.search(text)
    for example in examples:
        for form in {japanese_only(example), japanese_only(_without_brackets(example))}:
            if len(form) < SENTENCE_LENGTH:
                continue
            if kind:
                pieces = [japanese_only(p) for p in _GAP.split(text)]
                if re.fullmatch(".{0,8}?".join(re.escape(p) for p in pieces), form):
                    return example
            elif form in japanese_only(text):
                return example
    return None


def _reuse_and_word_problems(name: str, entry: dict, easy_kanji: set[str] | None) -> list[str]:
    out: list[str] = []
    examples = [b["jp"] for b in entry.get("blocks", []) if b.get("type") == "example"]
    seen = "".join(_KANJI.findall(" ".join(
        b.get("text", "") + b.get("jp", "") + " ".join(b.get("items", [])) for b in entry.get("blocks", [])
    )))
    allowed = None if easy_kanji is None else easy_kanji | set(seen) | set(entry.get("extraKanji", ""))
    for q in entry.get("questions", []):
        where = f"{name} question {q.get('id')}"
        for text in question_japanese(q):
            hit = reuses_example(text, examples)
            if hit:
                out.append(f"{where}: repeats the lesson example {hit!r}; test with a new sentence")
        if has_sentence(q) and not _text(q.get("en")):
            out.append(f"{where}: a question with a Japanese sentence needs an English translation (en)")
        if allowed is not None:
            for text in question_japanese(q):
                hard = sorted(set(_KANJI.findall(text)) - allowed)
                if hard:
                    out.append(f"{where}: kanji above N4 and not in the lesson: {''.join(hard)} (use an easier word or add extraKanji)")
    return out


def problems(entries: list[dict], grammar_ids: set[int], easy_kanji: set[str] | None = None) -> list[str]:
    """Everything wrong with the course; empty when it can be built. [easy_kanji] are the kanji of N5 and N4 words: new
    questions may use those, the kanji the lesson itself shows and the lesson's `extraKanji`; None skips that check."""
    out: list[str] = []
    ids = [e.get("id") for e in entries]
    if len(set(ids)) != len(ids):
        out.append("course entry ids repeat")
    for entry in entries:
        name = entry.get("_file", str(entry.get("id")))
        if not isinstance(entry.get("id"), int):
            out.append(f"{name}: id must be a number")
            continue
        if entry.get("kind") not in LESSON_KINDS:
            out.append(f"{name}: kind must be lesson or note")
            continue
        for field in ("number", "title", "summary"):
            if not _text(entry.get(field)):
                out.append(f"{name}: needs {field}")
        is_lesson = entry["kind"] == "lesson"
        if is_lesson and entry.get("jlpt") not in (1, 2, 3, 4, 5):
            out.append(f"{name}: a lesson needs jlpt 1 to 5")
        video = entry.get("video")
        if video is not None and not str(video).startswith(VIDEO_PREFIXES):
            out.append(f"{name}: video must be a YouTube link")
        out += _readings_problems(name, entry.get("readings"))
        for gid in entry.get("related", []):
            if gid not in grammar_ids:
                out.append(f"{name}: related grammar point {gid} does not exist")
        blocks = entry.get("blocks") or []
        if not blocks:
            out.append(f"{name}: no blocks")
        for i, block in enumerate(blocks):
            out += _block_problems(f"{name} block {i}", block)
        questions = entry.get("questions") or []
        if is_lesson and len(questions) < MIN_QUESTIONS:
            out.append(f"{name}: a lesson needs at least {MIN_QUESTIONS} questions, has {len(questions)}")
        if not is_lesson and questions:
            out.append(f"{name}: a note has no questions")
        qids = [q.get("id") for q in questions]
        if len(set(qids)) != len(qids) or not all(isinstance(i, int) for i in qids):
            out.append(f"{name}: question ids must be unique numbers")
        for q in questions:
            out += _question_problems(f"{name} question {q.get('id')}", q)
        out += _reuse_and_word_problems(name, entry, easy_kanji)
    return out


_MARKUP_WORD = re.compile(r"\{([^{}|]*)\|[^{}]*\}")


def _readings_problems(name: str, readings) -> list[str]:
    """`readings` maps a text to its furigana markup, for a spelling the dictionary would read the wrong way."""
    if readings is None:
        return []
    if not isinstance(readings, dict) or not readings:
        return [f"{name}: readings must be an object of text -> markup"]
    out = []
    for text, markup in readings.items():
        if not _text(markup) or _MARKUP_WORD.sub(r"\1", markup) != text:
            out.append(f"{name}: the markup of {text!r} must spell the same text, with {{word|reading}} parts")
    return out


def _with_readings(markup, readings: dict | None):
    """[markup] that uses the entry's own `readings` wherever one of those texts appears, and the dictionary elsewhere."""
    if not readings:
        return markup
    splitter = re.compile("(" + "|".join(re.escape(k) for k in sorted(readings, key=len, reverse=True)) + ")")

    def wrapped(text):
        if not text:
            return markup(text)
        pieces = splitter.split(text)
        if len(pieces) == 1:
            return markup(text)
        return "".join(readings[p] if p in readings else (markup(p) or p) if p else "" for p in pieces)

    return wrapped


def _packed(values: dict) -> str | None:
    """A JSON object of the furigana markups that exist, or None when there are none."""
    kept = {k: v for k, v in values.items() if v and (not isinstance(v, list) or any(v))}
    return json.dumps(kept, ensure_ascii=False) if kept else None


def add_ruby(entries: list[dict], base_markup) -> None:
    """Adds furigana markup to everything a learner reads: `markup(text)` gives the markup or None.

    Blocks keep it next to their text (`ruby`, `itemsRuby`, `rowsRuby`, a car's `ruby`); a lesson's title
    and summary and a question's texts go into `_ruby`, stored as one JSON column.
    """
    for entry in entries:
        markup = _with_readings(base_markup, entry.get("readings"))
        entry["_ruby"] = _packed({"title": markup(entry["title"]), "summary": markup(entry["summary"])})
        for block in entry["blocks"]:
            kind = block["type"]
            if kind == "example":
                block["ruby"] = markup(block["jp"])
            elif kind in ("text", "heading"):
                block["ruby"] = markup(block["text"])
            elif kind == "keypoints":
                block["itemsRuby"] = [markup(item) for item in block["items"]]
            elif kind == "table":
                block["rowsRuby"] = [[markup(cell) for cell in row] for row in block["rows"]]
            elif kind == "diagram":
                for car in block["cars"]:
                    car["ruby"] = markup(car["text"])
                block["ruby"] = markup(block["text"]) if block.get("text") else None
        for q in entry.get("questions", []):
            q["_ruby"] = _packed({
                "instruction": markup(q["instruction"]),
                "prompt": markup(q.get("prompt", "")),
                "hint": markup(q.get("hint")),
                "options": [markup(o) for o in q["options"]],
                "note": markup(q["note"]),
            })


def lesson_rows(entries: list[dict]) -> list[tuple]:
    """Rows for course_lesson: id, position, number, kind, jlpt, title, summary, video, related, body, ruby."""
    return [
        (
            e["id"], e["_position"], e["number"], e["kind"], e.get("jlpt"), e["title"], e["summary"], e.get("video"),
            ",".join(str(g) for g in e.get("related", [])), json.dumps(e["blocks"], ensure_ascii=False), e.get("_ruby"),
        )
        for e in entries
    ]


def question_rows(entries: list[dict]) -> list[tuple]:
    """Rows for course_question: lesson_id, id, type, instruction, prompt, hint, options, answer, note, ruby, en, alt."""
    return [
        (
            e["id"], q["id"], q["type"], q["instruction"], q.get("prompt", ""), q.get("hint"),
            json.dumps(q["options"], ensure_ascii=False), q.get("answer", 0), q["note"], q.get("_ruby"), q.get("en"),
            json.dumps(q["alt"], ensure_ascii=False) if q.get("alt") else None,
        )
        for e in entries
        for q in e.get("questions", [])
    ]
