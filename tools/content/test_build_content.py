"""Tests for the content pipeline. Run: python -X utf8 -m unittest discover tools/content"""

import json
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_content as bc  # noqa: E402

CSV_HEADER = "expression,reading,meaning,tags,guid\n"


def word(expression, reading, **kw):
    defaults = dict(guid=expression, other_forms=[], meanings="m", jlpt=5, genki_lesson=None)
    return bc.Word(expression=expression, reading=reading, **{**defaults, **kw})


class ParseCsvTest(unittest.TestCase):
    def test_forms_notes_and_genki_lessons(self):
        text = CSV_HEADER + (
            '足; 脚,あし,"foot; leg",JLPT JLPT_N5,g1\n'
            'すみません (かん),すみません (かん),"sorry",JLPT JLPT_N5,g2\n'
            '青い,あおい,blue,Genki Genki_Ln.9 Genki_Ln.12 JLPT JLPT_N5,g3\n'
        )
        words = bc.parse_jlpt_csv(text, 5)
        self.assertEqual(("足", "あし", ["脚"]), (words[0].expression, words[0].reading, words[0].other_forms))
        self.assertEqual("すみません", words[1].expression)
        self.assertEqual("すみません", words[1].reading)
        self.assertEqual(9, words[2].genki_lesson)
        self.assertIsNone(words[0].genki_lesson)


class MergeAndIdsTest(unittest.TestCase):
    def test_word_listed_twice_keeps_easier_level(self):
        merged = bc.merge_levels({
            3: [word("酸性", "さんせい", guid="a", jlpt=3)],
            2: [word("酸性", "さんせい", guid="b", jlpt=2), word("届く", "とどく", guid="c", jlpt=2)],
        })
        self.assertEqual([("酸性", 3), ("届く", 2)], [(w.expression, w.jlpt) for w in merged])

    def test_ids_are_stable_and_new_words_go_last(self):
        registry = {"old": 7}
        words = [word("新", "しん", guid="new"), word("古", "ふる", guid="old")]
        bc.assign_ids(words, registry)
        self.assertEqual({"old": 7, "new": 8}, registry)
        self.assertEqual([8, 7], [w.id for w in words])

    def test_registry_round_trip(self):
        path = Path(self.id().replace(".", "_") + ".tsv")
        try:
            registry = {"x": 2, "#starts-with-hash": 1}
            bc.save_registry(path, registry)
            self.assertEqual(registry, bc.load_registry(path))
        finally:
            path.unlink(missing_ok=True)


class SentenceMatchingTest(unittest.TestCase):
    def test_parse_index_token(self):
        token = bc.parse_index_token("彼(かれ)[01]")
        self.assertEqual(("彼", "かれ", None, False), (token.headword, token.reading, token.form, token.good_example))
        token = bc.parse_index_token("が(#2028930)[01]")
        self.assertEqual("が", token.headword)
        token = bc.parse_index_token("直ぐに{すぐに}~")
        self.assertEqual(("直ぐに", "すぐに", True), (token.headword, token.form, token.good_example))

    def test_rejects_other_kanji_from_the_same_dictionary_entry(self):
        meet = word("会う", "あう", jmdict_forms=["会う", "逢う", "遭う"])
        self.assertTrue(bc.token_matches(meet, bc.parse_index_token("会う{会った}")))
        self.assertFalse(bc.token_matches(meet, bc.parse_index_token("会う{遭った}")))

    def test_kana_word_matches_kanji_headword(self):
        soon = word("すぐに", "すぐに")
        self.assertTrue(bc.token_matches(soon, bc.parse_index_token("直ぐに{すぐに}")))

    def test_alternate_spellings_match(self):
        breakfast = word("朝御飯", "あさごはん", jmdict_forms=["朝御飯", "朝ご飯"])
        self.assertTrue(bc.token_matches(breakfast, bc.parse_index_token("朝ご飯")))

    def test_picks_kanji_written_examples_first(self):
        meet = word("会う", "あう", id=1)
        indexed = [
            bc.IndexedSentence(10, 100, [bc.parse_index_token("会う{あった}~")]),
            bc.IndexedSentence(11, 101, [bc.parse_index_token("会う{会った}")]),
        ]
        used = bc.pick_sentences([meet], indexed, {10: "スリにあった。", 11: "友達に会った。"}, {100: "a", 101: "b"})
        self.assertEqual([11, 10], meet.sentence_ids)
        self.assertEqual(2, len(used))

    def test_affixes_get_no_examples(self):
        counter = word("～人", "～じん")
        bc.pick_sentences([counter], [bc.IndexedSentence(1, 2, [bc.parse_index_token("人")])], {1: "人"}, {2: "x"})
        self.assertEqual([], counter.sentence_ids)


@unittest.skipUnless(bc.OUTPUT.exists(), "content.db not built yet")
class BuiltDatabaseTest(unittest.TestCase):
    """Checks the committed asset against Room's schema and expected sizes."""

    @classmethod
    def setUpClass(cls):
        cls.db = sqlite3.connect(bc.OUTPUT)
        cls.schema = bc.latest_schema()["database"]

    @classmethod
    def tearDownClass(cls):
        cls.db.close()

    def test_matches_room_schema(self):
        self.assertEqual(self.schema["version"], self.db.execute("PRAGMA user_version").fetchone()[0])
        identity = self.db.execute("SELECT identity_hash FROM room_master_table").fetchone()[0]
        self.assertEqual(self.schema["identityHash"], identity)
        for entity in self.schema["entities"]:
            columns = [row[1] for row in self.db.execute(f"PRAGMA table_info({entity['tableName']})")]
            self.assertEqual([f["columnName"] for f in entity["fields"]], columns, entity["tableName"])

    def test_every_level_is_populated(self):
        words = dict(self.db.execute("SELECT jlpt, COUNT(*) FROM word GROUP BY jlpt"))
        kanji = dict(self.db.execute("SELECT jlpt, COUNT(*) FROM kanji GROUP BY jlpt"))
        for jlpt, minimum in {5: 600, 4: 550, 3: 1800, 2: 1600, 1: 2400}.items():
            self.assertGreaterEqual(words.get(jlpt, 0), minimum, f"N{jlpt} words")
        for jlpt, minimum in {5: 70, 4: 150, 3: 350, 2: 350, 1: 1100}.items():
            self.assertGreaterEqual(kanji.get(jlpt, 0), minimum, f"N{jlpt} kanji")

    def test_rows_are_complete(self):
        blank = self.db.execute(
            "SELECT COUNT(*) FROM word WHERE expression = '' OR reading = '' OR meanings = ''"
        ).fetchone()[0]
        self.assertEqual(0, blank)
        orphans = self.db.execute(
            "SELECT COUNT(*) FROM word_sentence ws LEFT JOIN sentence s ON s.id = ws.sentence_id WHERE s.id IS NULL"
        ).fetchone()[0]
        self.assertEqual(0, orphans)
        with_examples = self.db.execute("SELECT COUNT(DISTINCT word_id) FROM word_sentence").fetchone()[0]
        total = self.db.execute("SELECT COUNT(*) FROM word").fetchone()[0]
        self.assertGreater(with_examples / total, 0.8)

    def test_ids_match_registry(self):
        registry = bc.load_registry(bc.WORD_IDS)
        ids = {row[0] for row in self.db.execute("SELECT id FROM word")}
        self.assertEqual(ids, set(registry.values()))
        self.assertEqual(len(ids), max(ids), "ids should be 1..n with no gaps")

    def test_grammar_examples_hide_one_clear_answer(self):
        points = dict(self.db.execute("SELECT id, jlpt FROM grammar_point"))
        self.assertGreaterEqual(sum(1 for j in points.values() if j == 5), 20)
        self.assertGreaterEqual(sum(1 for j in points.values() if j == 4), 20)
        for grammar_id, japanese, answer in self.db.execute("SELECT grammar_id, japanese, answer FROM grammar_example"):
            self.assertIn(grammar_id, points)
            self.assertEqual(1, japanese.count(answer), f"{grammar_id}: {japanese} / {answer}")
        for grammar_id in points:
            n = self.db.execute("SELECT COUNT(*) FROM grammar_example WHERE grammar_id = ?", (grammar_id,)).fetchone()[0]
            self.assertGreaterEqual(n, 2, f"grammar point {grammar_id}")


if __name__ == "__main__":
    unittest.main()
