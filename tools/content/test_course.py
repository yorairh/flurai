"""Checks on the grammar course: the rules in course.py, every entry file in tools/content/course, and the built database."""

from __future__ import annotations

import json
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import build_content as bc  # noqa: E402
import course  # noqa: E402

GRAMMAR_IDS = {g["id"] for g in json.loads(bc.GRAMMAR.read_text(encoding="utf-8"))}


def good_lesson(**changes) -> dict:
    questions = [
        {"id": i, "type": "choice", "instruction": "Pick one", "prompt": "さくらが歩く", "en": "Sakura walks.", "options": ["a", "b", "c"], "answer": 1, "note": "Because."}
        for i in range(1, 21)
    ]
    entry = {
        "id": 10, "number": "1", "kind": "lesson", "jlpt": 5, "title": "T", "summary": "S", "video": "https://www.youtube.com/watch?v=abc",
        "related": [], "blocks": [{"type": "text", "text": "hello"}], "questions": questions, "_file": "001-x.json", "_position": 1,
    }
    entry.update(changes)
    return entry


class RulesTest(unittest.TestCase):
    def check(self, *entries) -> list[str]:
        return course.problems(list(entries), GRAMMAR_IDS)

    def test_a_good_lesson_has_no_problems(self):
        self.assertEqual([], self.check(good_lesson()))

    def test_a_lesson_needs_twenty_questions(self):
        short = good_lesson(questions=good_lesson()["questions"][:19])
        self.assertTrue(any("at least 20" in p for p in self.check(short)))

    def test_a_note_has_no_questions_and_no_level(self):
        note = good_lesson(kind="note", jlpt=None, questions=[])
        self.assertEqual([], self.check(note))
        self.assertTrue(any("no questions" in p for p in self.check(good_lesson(kind="note", jlpt=None))))

    def test_ids_must_be_unique(self):
        self.assertTrue(any("repeat" in p for p in self.check(good_lesson(), good_lesson())))

    def test_a_choice_answer_must_be_an_option(self):
        bad = good_lesson()
        bad["questions"][0]["answer"] = 3
        self.assertTrue(any("answer" in p for p in self.check(bad)))

    def test_options_must_not_repeat(self):
        bad = good_lesson()
        bad["questions"][0]["options"] = ["a", "a", "b"]
        self.assertTrue(any("repeat" in p for p in self.check(bad)))

    def test_order_questions_need_pieces_that_can_be_shuffled(self):
        bad = good_lesson()
        bad["questions"][0] = {"id": 1, "type": "order", "instruction": "Order", "prompt": "I eat", "options": ["は", "は", "は"], "note": "n"}
        self.assertTrue(any("same" in p for p in self.check(bad)))

    def test_related_points_must_exist(self):
        self.assertTrue(any("does not exist" in p for p in self.check(good_lesson(related=[1]))))

    def test_the_video_must_be_a_youtube_link(self):
        self.assertTrue(any("YouTube" in p for p in self.check(good_lesson(video="https://example.com/x"))))

    def test_every_question_needs_a_note(self):
        bad = good_lesson()
        bad["questions"][2]["note"] = ""
        self.assertTrue(any("note" in p for p in self.check(bad)))


class NewSentenceRulesTest(unittest.TestCase):
    def lesson_with(self, question: dict, example: str = "犬が走る。", **changes) -> dict:
        entry = good_lesson(blocks=[{"type": "example", "jp": example, "en": "x"}], **changes)
        entry["questions"][0] = {"id": 1, "instruction": "Pick", "note": "n", **question}
        return entry

    def check(self, entry, easy=None):
        return course.problems([entry], GRAMMAR_IDS, easy)

    def test_a_question_may_not_quote_a_lesson_example(self):
        q = {"type": "choice", "prompt": "犬が走る", "en": "The dog runs.", "options": ["a", "b"], "answer": 0}
        self.assertTrue(any("repeats the lesson example" in p for p in self.check(self.lesson_with(q))))

    def test_a_gap_sentence_that_is_an_example_with_a_blank_counts_as_quoting_it(self):
        q = {"type": "choice", "prompt": "犬＿走る。", "en": "The dog runs.", "options": ["が", "を"], "answer": 0}
        self.assertTrue(any("repeats the lesson example" in p for p in self.check(self.lesson_with(q))))

    def test_pieces_that_build_an_example_count_as_quoting_it(self):
        q = {"type": "order", "prompt": "The dog runs.", "en": "The dog runs.", "options": ["犬", "が", "走る"]}
        self.assertTrue(any("repeats" in p for p in self.check(self.lesson_with(q))))

    def test_a_new_sentence_is_fine(self):
        q = {"type": "choice", "prompt": "猫が歩く。", "en": "The cat walks.", "options": ["a", "b"], "answer": 0}
        self.assertEqual([], self.check(self.lesson_with(q)))

    def test_a_question_with_a_sentence_needs_its_translation(self):
        q = {"type": "choice", "prompt": "猫が歩く。", "options": ["a", "b"], "answer": 0}
        self.assertTrue(any("translation" in p for p in self.check(self.lesson_with(q))))

    def test_a_concept_question_needs_no_translation(self):
        q = {"type": "choice", "prompt": "Which verb is other-move?", "options": ["a", "b"], "answer": 0}
        self.assertEqual([], self.check(self.lesson_with(q)))

    def test_new_sentences_use_easy_kanji_the_lesson_shows_or_extra_kanji(self):
        q = {"type": "choice", "prompt": "猫が歩く。", "en": "The cat walks.", "options": ["a", "b"], "answer": 0}
        easy = set("犬走")
        self.assertTrue(any("above N4" in p and "猫" in p for p in self.check(self.lesson_with(q), easy)))
        self.assertEqual([], self.check(self.lesson_with(q, extraKanji="猫歩"), easy))


class CourseFilesTest(unittest.TestCase):
    """The real entries in tools/content/course."""

    @classmethod
    def setUpClass(cls):
        cls.entries = course.load(bc.COURSE_DIR)

    def test_the_course_has_no_problems(self):
        easy = None
        if bc.OUTPUT.exists():
            import check_course
            easy = check_course.easy_kanji(bc.OUTPUT)
        self.assertEqual([], course.problems(self.entries, GRAMMAR_IDS, easy))

    def test_entries_are_in_course_order(self):
        numbers = [e["number"] for e in self.entries]
        self.assertEqual(len(numbers), len(set(numbers)), "a course number is used twice")
        ids = [e["id"] for e in self.entries]
        self.assertEqual(ids, sorted(ids), "file order must follow the entry ids")

    def test_order_questions_do_not_hide_the_answer_in_the_options(self):
        for entry in self.entries:
            for q in entry.get("questions", []):
                if q["type"] == "order":
                    self.assertGreaterEqual(len(set(q["options"])), 2, f"{entry['_file']} #{q['id']}")


class BuiltCourseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.db = sqlite3.connect(bc.OUTPUT)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()

    def test_every_lesson_made_it_into_the_database(self):
        built = self.db.execute("SELECT COUNT(*) FROM course_lesson").fetchone()[0]
        self.assertEqual(len(course.load(bc.COURSE_DIR)), built)

    def test_every_lesson_has_its_questions(self):
        rows = self.db.execute(
            "SELECT l.id, COUNT(q.id) FROM course_lesson l LEFT JOIN course_question q ON q.lesson_id = l.id WHERE l.kind = 'lesson' GROUP BY l.id"
        ).fetchall()
        for lesson_id, count in rows:
            self.assertGreaterEqual(count, course.MIN_QUESTIONS, f"lesson {lesson_id}")


if __name__ == "__main__":
    unittest.main()
