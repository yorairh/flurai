"""Checks some course entries without rebuilding content.db: `python -X utf8 tools/content/check_course.py 210 220`.

Prints every problem of the entries with those ids (all entries when none is given), including the new-sentence
and easy-kanji rules, using the N5/N4 words of the built content.db. Exit code 1 when there is a problem.
"""

from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import build_content as bc  # noqa: E402
import course  # noqa: E402


def easy_kanji(db_path: Path) -> set[str]:
    db = sqlite3.connect(db_path)
    try:
        return {c for (e,) in db.execute("SELECT expression FROM word WHERE jlpt >= 4") for c in e if course._KANJI.match(c)}
    finally:
        db.close()


def main(argv: list[str]) -> int:
    wanted = {int(a) for a in argv}
    entries = course.load(bc.COURSE_DIR)
    grammar_ids = {g["id"] for g in json.loads(bc.GRAMMAR.read_text(encoding="utf-8"))}
    found = course.problems([e for e in entries if not wanted or e["id"] in wanted], grammar_ids, easy_kanji(bc.OUTPUT))
    for line in found:
        print(line)
    print(f"{len(found)} problem(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
