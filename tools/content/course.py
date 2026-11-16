"""The grammar course: loading, checking and turning tools/content/course/*.json into database rows.

One file per course entry (a lesson with a test, or a study note), named NNN-slug.json; the file
order is the course order. The pipeline fails on any problem found by `problems`, so a broken
lesson never reaches the app. Stdlib only.
"""

from __future__ import annotations

import json
from pathlib import Path

LESSON_KINDS = ("lesson", "note")
BLOCK_TYPES = ("heading", "text", "example", "diagram", "keypoints", "table")
CAR_ROLES = ("engine", "carriage", "topic", "zero", "plain", "white", "particle")
QUESTION_TYPES = ("choice", "tap", "order")
MIN_QUESTIONS = 10
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
    return out


def problems(entries: list[dict], grammar_ids: set[int]) -> list[str]:
    """Everything wrong with the course; empty when it can be built."""
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
    return out


def lesson_rows(entries: list[dict]) -> list[tuple]:
    """Rows for course_lesson: id, position, number, kind, jlpt, title, summary, video, related, body."""
    return [
        (
            e["id"], e["_position"], e["number"], e["kind"], e.get("jlpt"), e["title"], e["summary"], e.get("video"),
            ",".join(str(g) for g in e.get("related", [])), json.dumps(e["blocks"], ensure_ascii=False),
        )
        for e in entries
    ]


def question_rows(entries: list[dict]) -> list[tuple]:
    """Rows for course_question: lesson_id, id, type, instruction, prompt, hint, options, answer, note."""
    return [
        (
            e["id"], q["id"], q["type"], q["instruction"], q.get("prompt", ""), q.get("hint"),
            json.dumps(q["options"], ensure_ascii=False), q.get("answer", 0), q["note"],
        )
        for e in entries
        for q in e.get("questions", [])
    ]
