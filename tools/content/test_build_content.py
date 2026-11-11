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

    def test_a_kana_word_is_not_matched_to_another_word_read_the_same(self):
        yes = word("ええ", "ええ", jmdict_id=1001140)  # ええ "yes"
        self.assertTrue(bc.token_matches(yes, bc.parse_index_token("ええ")))
        self.assertFalse(bc.token_matches(yes, bc.parse_index_token("良い{ええ}")))  # Kansai ええ "good"
        unmatched = word("あれこれ", "あれこれ")  # not in JMdict: the permissive rule stays
        self.assertTrue(bc.token_matches(unmatched, bc.parse_index_token("彼是{あれこれ}")))

    def test_prefers_examples_no_harder_than_the_word(self):
        n5 = word("本", "ほん", id=1, jlpt=5)
        indexed = [
            bc.IndexedSentence(10, 100, [bc.parse_index_token("本")]),
            bc.IndexedSentence(11, 101, [bc.parse_index_token("本")]),
        ]
        japanese = {10: "この法案は本当に長い話だ。", 11: "これは本です。"}
        grades = {10: 1, 11: 5}
        bc.pick_sentences([n5], indexed, japanese, {100: "a", 101: "b"}, grades)
        self.assertEqual([11, 10], n5.sentence_ids)  # the N5 sentence first, although it is farther from the ideal length

    def test_affixes_get_no_examples(self):
        counter = word("～人", "～じん")
        bc.pick_sentences([counter], [bc.IndexedSentence(1, 2, [bc.parse_index_token("人")])], {1: "人"}, {2: "x"})
        self.assertEqual([], counter.sentence_ids)


class GrammarExtrasTest(unittest.TestCase):
    point = {"id": 1, "jlpt": 4, "pattern": "〜たほうがいい", "examples": [{"jp": "行ったほうがいい。", "en": "x", "answer": "たほうが"}]}

    def sentence(self, id, text, *tokens):
        return bc.IndexedSentence(id, id + 1000, [bc.parse_index_token(t) for t in tokens]), text

    def pick(self, *sentences, grades=None):
        indexed = [s for s, _ in sentences]
        japanese = {s.japanese_id: text for s, text in sentences}
        english = {s.english_id: "translation" for s in indexed}
        grades = grades or {s.japanese_id: 4 for s in indexed}
        return bc.pick_grammar_extras([self.point], indexed, japanese, english, grades)

    def test_finds_extra_sentences_that_contain_the_answer_once(self):
        a = self.sentence(1, "早く寝たほうがいいですよ。", "早く", "寝る{寝た}", "ほう", "が", "良い{いい}", "です", "よ")
        extras = self.pick(a)
        self.assertEqual(["早く寝たほうがいいですよ。"], [e["jp"] for e in extras[1]])
        self.assertEqual("たほうが", extras[1][0]["answer"])

    def test_skips_the_lessons_own_sentence_harder_sentences_and_matches_inside_a_longer_word(self):
        own = self.sentence(1, "行ったほうがいい。", "行く{行った}", "ほう", "が", "良い{いい}")
        hard = self.sentence(2, "寝たほうがいいと言われる。", "寝る{寝た}", "ほう", "が", "良い{いい}")
        inside = self.sentence(3, "寝たほうがいいかげん。", "寝る{寝た}", "ほうがいい加減{ほうがいいかげん}")  # answer ends mid-word
        extras = self.pick(own, hard, inside, grades={1: 4, 2: 3, 3: 4})
        self.assertEqual({}, extras)

    def test_short_answers_are_not_matched(self):
        particle = {"id": 2, "jlpt": 5, "pattern": "が", "examples": [{"jp": "誰が来ましたか。", "en": "x", "answer": "が"}]}
        s = self.sentence(5, "私は猫が好きです。", "私", "は", "猫", "が", "好き", "です")
        extras = bc.pick_grammar_extras([particle], [s[0]], {5: s[1]}, {1005: "t"}, {5: 5})
        self.assertEqual({}, extras)


class JmdictMatchTest(unittest.TestCase):
    def entry(self, id, kanji, kana, *glosses):
        return {"id": str(id), "kanji": [{"text": k} for k in kanji], "kana": [{"text": k} for k in kana],
                "sense": [{"partOfSpeech": ["x"], "gloss": [{"text": g} for g in glosses]}]}

    def test_picks_the_entry_whose_glosses_match_the_deck_meaning(self):
        roe = self.entry(1, [], ["いくら"], "salted salmon roe")
        price = self.entry(2, ["幾ら"], ["いくら"], "how much?", "how many?")
        w = word("いくら", "いくら", meanings="how much, how many")
        bc.attach_jmdict([w], {"words": [roe, price]})
        self.assertEqual(2, w.jmdict_id)
        self.assertEqual(["幾ら"], w.jmdict_forms)

    def test_without_any_overlap_several_candidates_leave_the_word_unmatched(self):
        a = self.entry(1, [], ["ぼろ"], "tremendous")
        b = self.entry(2, [], ["ぼろ"], "enormous")
        w = word("ぼろ", "ぼろ", meanings="rag, tattered clothes")
        bc.attach_jmdict([w], {"words": [a, b]})
        self.assertIsNone(w.jmdict_id)
        sole = word("ぼろ", "ぼろ", meanings="rag")
        bc.attach_jmdict([sole], {"words": [a]})
        self.assertEqual(1, sole.jmdict_id)

    def test_polite_family_words_are_not_called_formal(self):
        mother = word("お母さん", "おかあさん", meanings="mother (formal)")
        other = word("丁寧", "ていねい", meanings="polite (formal)")
        bc.tidy_meanings([mother, other])
        self.assertEqual("mother (polite)", mother.meanings)
        self.assertEqual("polite (formal)", other.meanings)


class SentenceGradeTest(unittest.TestCase):
    levels = {"私": 5, "学校": 5, "行く": 5, "寒い": 5, "着る": 5, "車": 5, "掛ける": 4, "電話": 5, "繋がる": 3, "呼ぶ": 5, "旅行": 5}

    def grade(self, text, *tokens):
        return bc.grade_sentence(text, [bc.parse_index_token(t) for t in tokens], self.levels)

    def test_a_simple_sentence_is_n5(self):
        self.assertEqual(5, self.grade("私は学校に行く。", "私", "は", "学校", "に", "行く"))

    def test_the_hardest_word_decides(self):
        self.assertEqual(3, self.grade("電話がつながった。", "電話", "が", "繋がる{つながった}"))

    def test_unknown_words_by_kind(self):
        self.assertEqual(1, self.grade("田中が行く。", "田中", "が", "行く"))  # kanji outside the lists
        self.assertEqual(3, self.grade("ワックスを買う。", "ワックス", "を", "行く"))  # katakana outside the lists
        self.assertEqual(5, self.grade("私は行く。", "私", "は", "行く"))  # particles ask nothing

    def test_grammar_beyond_n5_raises_the_level(self):
        self.assertEqual(3, self.grade("寒いからコートを着るべきだ。", "寒い", "から", "着る", "可き{べき}", "だ"))  # べき
        self.assertEqual(4, self.grade("友達がいるので行く。", "私", "ので", "行く"))
        self.assertEqual(3, self.grade("いつ旅行においでになりますか。", "旅行", "に", "お出でになる{おいでになります}", "か"))

    def test_the_bad_n5_sentences_from_the_play_test_are_not_n5(self):
        cases = [
            ("もう～、なにやってんだよ。", ["もう{もう～}", "何{なに}", "遣る{やって}", "のだ{んだ}", "よ"]),
            ("いつ旅行においでになりますか。", ["何時(いつ){いつ}", "旅行", "に", "お出でになる{おいでになります}", "か"]),
            ("その車はワックスがかけられてピカピカしている。", ["其の{その}", "車(くるま)", "は", "ワックス", "が", "掛ける{かけられて}", "ぴかぴか{ピカピカ}", "為る(する){している}"]),
            ("寒いからコートを着るべきだ。", ["寒い(さむい)", "から", "コート", "を", "着る", "可き{べき}", "だ"]),
            ("あなたをケパと呼ぶことにします。", ["貴方(あなた){あなた}", "を", "と", "呼ぶ", "事にする{ことにします}"]),
        ]
        levels = {**self.levels, "何": 5, "何時": 5, "其の": 5, "コート": 5}
        for text, tokens in cases:
            grade = bc.grade_sentence(text, [bc.parse_index_token(t) for t in tokens], levels)
            self.assertTrue(grade is None or grade < 5, f"{text} graded {grade}")

    def test_godan_passive_and_causative_forms_are_not_n5(self):
        levels = {**self.levels, "書く": 5, "本": 5, "英語": 5}
        passive = bc.grade_sentence("英語で書かれた本。", [bc.parse_index_token(t) for t in ("英語", "で", "書く{書かれた}", "本")], levels)
        plain = bc.grade_sentence("英語で書いた本。", [bc.parse_index_token(t) for t in ("英語", "で", "書く{書いた}", "本")], levels)
        self.assertEqual(3, passive)
        self.assertEqual(5, plain)

    def test_placeholders_make_a_sentence_unreadable(self):
        self.assertIsNone(self.grade("もう～、行く。", "もう", "行く"))
        self.assertIsNone(bc.grade_sentence("私は行く。", [], self.levels))

    def test_word_levels_take_the_easiest_word_written_that_way(self):
        levels = bc.word_levels([word("私", "わたし", jlpt=5), word("私", "わたくし", jlpt=3, guid="b"), word("ええ", "ええ", jlpt=5, guid="c")])
        self.assertEqual(5, levels["私"])
        self.assertEqual(5, levels["ええ"])


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

    def test_reading_sentences_are_graded_and_every_level_has_enough(self):
        counts = dict(self.db.execute(
            "SELECT jlpt, COUNT(*) FROM sentence WHERE verified = 1 AND length(japanese) BETWEEN 8 AND 30 AND jlpt IS NOT NULL GROUP BY jlpt"
        ))
        for jlpt, minimum in {5: 100, 4: 200, 3: 300, 2: 200, 1: 100}.items():
            self.assertGreaterEqual(counts.get(jlpt, 0), minimum, f"N{jlpt} reading sentences")

    def test_n5_sentences_use_no_hard_grammar(self):
        for (japanese,) in self.db.execute("SELECT japanese FROM sentence WHERE jlpt = 5"):
            for pattern, level in bc.GRAMMAR_GATES:
                self.assertFalse(pattern.search(japanese), f"N5 sentence matches a level {level} gate: {japanese}")

    def test_extra_grammar_examples_hide_one_answer_and_stay_off_the_lesson_rank(self):
        extras = self.db.execute("SELECT grammar_id, rank, japanese, answer FROM grammar_example WHERE rank >= ?", (bc.EXTRA_EXAMPLE_RANK,)).fetchall()
        self.assertGreater(len({e[0] for e in extras}), 150)
        for grammar_id, rank, japanese, answer in extras:
            self.assertEqual(1, japanese.count(answer), f"{grammar_id}: {japanese}")
            self.assertLess(rank, bc.EXTRA_EXAMPLE_RANK + bc.EXTRA_GRAMMAR_EXAMPLES)

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
