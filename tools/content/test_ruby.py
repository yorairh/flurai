"""Tests for the furigana markup. Run: python -X utf8 -m unittest discover tools/content"""

import json
import re
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_content as bc  # noqa: E402
import ruby  # noqa: E402


def entry(order, kanji, kana, pos, common=True):
    return {
        "id": str(order),
        "kanji": [{"text": kanji, "common": common}] if kanji else [],
        "kana": [{"text": kana, "common": common, "appliesToKanji": ["*"]}],
        "sense": [{"partOfSpeech": pos, "gloss": []}],
    }


ENTRIES = [
    entry(1, "来る", "くる", ["vk", "vi"]),
    entry(2, "書く", "かく", ["v5k"]),
    entry(3, "書", "ふみ", ["n"], common=False),  # the noun that must not win over the verb 書く
    entry(4, "食べる", "たべる", ["v1"]),
    entry(5, "私", "わたし", ["pn"]),
    entry(6, "今日", "きょう", ["n-adv"]),
    entry(7, "今日は", "こんにちは", ["int"]),
    entry(8, "彼", "かれ", ["pn"]),
    entry(9, "彼の", "あの", ["adj-pn"]),
    entry(10, "若い", "わかい", ["adj-i"]),
    entry(11, "若く", "しく", ["v5k"]),
    entry(12, "計る", "はかる", ["v5r"]),
    entry(13, "何", "なに", ["pn"]),
    entry(14, "何", "なん", ["pn"]),
    entry(15, "本", "ほん", ["n"]),
    entry(16, "本", "もと", ["n"]),
    entry(17, "貸出し", "かしだし", ["n"]),
    entry(18, "私達", "わたしたち", ["pn"]),
    entry(19, "雨", "あめ", ["n"]),
    entry(20, "多い", "おおい", ["adj-i"]),
    entry(21, "朝起きる", "あさおきる", ["v1"], common=False),
    entry(22, "朝", "あさ", ["n"]),
    entry(23, "起きる", "おきる", ["v1"]),
    entry(24, "降る", "ふる", ["v5r"]),
    entry(25, "降りる", "おりる", ["v1"]),
    entry(26, "お客様", "おきゃくさま", ["n"]),
    entry(27, "客", "きゃく", ["n"]),
    entry(28, "様", "よう", ["n"]),
    entry(29, "震える", "ふるえる", ["v1"]),
    entry(30, "始める", "はじめる", ["v1"]),
    entry(31, "熟考", "じゅっこう", ["n"], common=False),
    entry(32, "熟", "つくづく", ["adv"], common=False),
    entry(33, "考", "こう", ["n"], common=False),
]
READER = ruby.Reader.build(ENTRIES, {"本": ["ほん", "もと"], "来る": ["くる"], "書く": ["かく"]})


def tok(headword, hint=None, form=None):
    return headword, hint, form


class AlignTest(unittest.TestCase):
    def test_puts_the_reading_over_kanji_only(self):
        self.assertEqual([("食", "た"), ("べ", None), ("物", "もの")], ruby.align("食べ物", "たべもの"))
        self.assertEqual([("お", None), ("母", "かあ"), ("さん", None)], ruby.align("お母さん", "おかあさん"))
        self.assertEqual([("貸出", "かしだ"), ("し", None)], ruby.align("貸出し", "かしだし"))  # the し of the reading repeats

    def test_a_reading_that_does_not_fit_goes_over_the_whole_word(self):
        self.assertEqual([("日曜日", "にちようび")], ruby.align("日曜日", "にちようび"))
        self.assertEqual([("食べ", "のむ")], ruby.align("食べ", "のむ"))

    def test_markup_round_trips(self):
        parts = [("誰", "だれ"), ("が", None), ("来", "き"), ("ました", None)]
        marked = ruby.markup(parts)
        self.assertEqual("{誰|だれ}が{来|き}ました", marked)
        self.assertEqual(parts, ruby.parse(marked))


class TokenTest(unittest.TestCase):
    def parts(self, headword, hint, form, following=""):
        return ruby.token_parts(READER, headword, form, hint, following)

    def test_an_inflected_verb_keeps_the_stem_reading(self):
        self.assertEqual([("書", "か"), ("いて", None)], self.parts("書く", None, "書いて"))
        self.assertEqual([("食", "た"), ("べ", None), ("た", None)], self.parts("食べる", None, "食べた"))

    def test_come_is_read_from_what_follows(self):
        self.assertEqual("き", self.parts("来る", None, "来ました")[0][1])
        self.assertEqual("こ", self.parts("来る", None, "来ない")[0][1])
        self.assertEqual("く", self.parts("来る", None, "来る")[0][1])
        # the index sometimes cuts the form short: 来 on its own, followed by なさい
        self.assertEqual([("来", "こ")], self.parts("来る", None, "来", following="な"))

    def test_a_particle_written_into_the_form_stays_plain(self):
        self.assertEqual([("私", "わたし"), ("の", None)], self.parts("私", "わたし", "私の"))
        # 彼の must not be looked up as the word あの
        self.assertEqual([("彼", "かれ"), ("の", None)], self.parts("彼", None, "彼の"))

    def test_word_plus_wa_is_read_as_the_word(self):
        self.assertEqual([("今日", "きょう"), ("は", None)], self.parts("今日は", None, None))

    def test_another_spelling_of_an_inflected_word_keeps_the_reading(self):
        self.assertEqual([("測", "はか"), ("った", None)], self.parts("計る", None, "測った"))

    def test_the_adjective_is_not_looked_up_as_the_homograph_of_its_form(self):
        # 若く is also the verb form しく in the dictionary: here it is 若い, inflected
        self.assertEqual([("若", "わか"), ("く", None)], self.parts("若い", None, "若く"))

    def test_an_index_hint_picks_the_reading_when_it_is_as_common(self):
        self.assertEqual([("本", "もと")], self.parts("本", "もと", None))


class SentenceTest(unittest.TestCase):
    def test_markup_from_index_tokens(self):
        marked = ruby.sentence_markup(READER, "誰が来ました。", [tok("誰"), tok("が"), tok("来る", None, "来ました")])
        # 誰 is unknown to the toy dictionary, 来ました is read from the verb
        self.assertEqual("誰が{来|き}ました。", marked)

    def test_text_without_kanji_has_no_markup(self):
        self.assertIsNone(ruby.sentence_markup(READER, "ありがとう。", [tok("ありがとう")]))

    def test_names_come_from_the_name_list(self):
        marked = ruby.sentence_markup(READER, "山田さんは来ない。", [tok("さん"), tok("来る", None, "来ない")])
        self.assertEqual("{山田|やまだ}さんは{来|こ}ない。", marked)


class TextTest(unittest.TestCase):
    def test_conjugated_verb_beats_the_homograph_noun(self):
        self.assertEqual("ここに名前を{書|か}いてください。", ruby.text_markup(READER, "ここに名前を書いてください。"))

    def test_come_in_text(self):
        self.assertEqual("ここへ{来|こ}なさい。", ruby.text_markup(READER, "ここへ来なさい。"))
        self.assertEqual("{雨|あめ}が{来|き}そうだ。", ruby.text_markup(READER, "雨が来そうだ。"))

    def test_adjective_stem(self):
        self.assertEqual("{本|ほん}が{多|おお}すぎる。", ruby.text_markup(READER, "本が多すぎる。"))

    def test_a_rare_verb_does_not_swallow_two_words(self):
        self.assertEqual("{朝|あさ}{起|お}きて", ruby.text_markup(READER, "朝起きて"))

    def test_word_plus_particle_compounds_are_split(self):
        self.assertEqual("{今日|きょう}は{彼|かれ}の{本|ほん}", ruby.text_markup(READER, "今日は彼の本"))

    def test_what_is_read_by_what_follows(self):
        self.assertEqual("{何|なに}を", ruby.text_markup(READER, "何を"))
        self.assertEqual("{何|なん}で", ruby.text_markup(READER, "何で"))

    def test_rain_falls_and_people_get_off(self):
        self.assertEqual("{雨|あめ}が{降|ふ}りました", ruby.text_markup(READER, "雨が降りました"))
        self.assertEqual("{降|お}りました", ruby.text_markup(READER, "降りました"))

    def test_an_honorific_prefix_belongs_to_the_word(self):
        self.assertEqual("お{客様|きゃくさま}", ruby.text_markup(READER, "お客様"))  # not 客 + 様 (よう)

    def test_a_verb_stem_in_front_of_another_verb(self):
        self.assertEqual("{震|ふる}え{始|はじ}めた", ruby.text_markup(READER, "震え始めた"))

    def test_a_longer_rare_spelling_beats_a_lone_kanji(self):
        self.assertEqual("{熟考|じゅっこう}", ruby.text_markup(READER, "熟考"))

    def test_the_easier_jlpt_reading_wins(self):
        self.assertEqual("{本|ほん}", ruby.text_markup(READER, "本"))


@unittest.skipUnless(bc.OUTPUT.exists(), "content.db not built yet")
class BuiltFuriganaTest(unittest.TestCase):
    pattern = re.compile(r"\{([^|}]*)\|([^}]*)\}")

    @classmethod
    def setUpClass(cls):
        cls.db = sqlite3.connect(bc.OUTPUT)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()

    def rows(self):
        yield from ((t, m) for t, m in self.db.execute("SELECT japanese, ruby FROM sentence"))
        yield from ((t, m) for t, m in self.db.execute("SELECT japanese, ruby FROM grammar_example"))
        for (body,) in self.db.execute("SELECT body FROM course_lesson"):
            for block in json.loads(body):
                if block["type"] == "example":
                    yield block["jp"], block.get("ruby")
        yield from self.course_texts()

    def course_texts(self):
        """Every text of the course a learner reads (not the examples, which are counted above): (plain, markup)."""
        for body, stored in self.db.execute("SELECT body, ruby FROM course_lesson"):
            for block in json.loads(body):
                if block["type"] in ("text", "heading"):
                    yield block["text"], block.get("ruby")
                for item, marked in zip(block.get("items", []), block.get("itemsRuby", [])):
                    yield item, marked
                for row, marks in zip(block.get("rows", []), block.get("rowsRuby", [])):
                    yield from zip(row, marks)
                for car in block.get("cars", []):
                    yield car["text"], car.get("ruby")
        for options, stored in self.db.execute("SELECT options, ruby FROM course_question"):
            texts = json.loads(options)
            marks = (json.loads(stored) if stored else {}).get("options", [None] * len(texts))
            yield from zip(texts, marks)
        for prompt, hint, note, stored in self.db.execute("SELECT prompt, hint, note, ruby FROM course_question"):
            marks = json.loads(stored) if stored else {}
            for key, text in (("prompt", prompt), ("hint", hint), ("note", note)):
                if text:
                    yield text, marks.get(key)

    def test_the_markup_is_the_sentence_with_readings_added(self):
        for text, marked in self.rows():
            if marked is not None:
                self.assertEqual(text, self.pattern.sub(lambda m: m.group(1), marked), marked)

    def test_nearly_every_kanji_has_a_reading(self):
        total = missing = 0
        for text, marked in self.rows():
            kanji = [c for c in text if ruby.is_kanji(c)]
            total += len(kanji)
            if marked is None:
                missing += len(kanji)
                continue
            plain = self.pattern.sub(lambda m: "\0" * len(m.group(1)), marked)
            missing += sum(1 for i, c in enumerate(plain) if c != "\0" and ruby.is_kanji(text[i]))
        self.assertLess(missing / total, 0.005, f"{missing} of {total} kanji have no furigana")

    def test_a_gap_in_a_fill_in_question_does_not_cost_the_reading_of_the_word_before_it(self):
        total = read = 0
        for prompt, stored in self.db.execute("SELECT prompt, ruby FROM course_question WHERE prompt GLOB '*[一-鿿]＿*'"):
            total += 1
            marked = (json.loads(stored) if stored else {}).get("prompt", "")
            # the kanji right before the gap must be inside a {word|reading}
            if re.search(r"\|[^}]*\}＿", marked):
                read += 1
        self.assertGreater(total, 20)
        self.assertGreater(read / total, 0.9, f"{total - read} of {total} gaps lost the reading before them")

    def test_the_bug_from_the_phone_is_fixed(self):
        marked = self.db.execute("SELECT ruby FROM grammar_example WHERE japanese = ?", ("誰が来ましたか。",)).fetchone()
        self.assertIsNotNone(marked)
        self.assertIn("{来|き}ました", marked[0])
        self.assertIn("{誰|だれ}", marked[0])

    def test_no_reading_has_a_tilde_or_belongs_to_an_affix(self):
        for _, marked in self.rows():
            if marked:  # a tilde in the sentence itself is fine; one in a reading is an affix leaking through
                self.assertEqual([], [p for p in self.pattern.findall(marked) if "～" in p[1] or "〜" in p[1]], marked)

    def test_a_conjugated_verb_gets_its_stem_reading(self):
        marked = self.db.execute("SELECT ruby FROM sentence WHERE japanese = ?", ("彼女は風船に穴を空けた。",)).fetchone()
        if marked:
            self.assertIn("{空|あ}けた", marked[0])


if __name__ == "__main__":
    unittest.main()


class SplitPerKanjiTest(unittest.TestCase):
    """A run of kanji gets one reading per kanji where the readings of the kanji allow it."""

    KANJI = {
        "月": ruby.kanji_candidates("ゲツ、ガツ", "つき"),
        "曜": ruby.kanji_candidates("ヨウ", ""),
        "日": ruby.kanji_candidates("ニチ、ジツ", "ひ、-び、か"),
        "学": ruby.kanji_candidates("ガク", "まな.ぶ"),
        "校": ruby.kanji_candidates("コウ", ""),
        "今": ruby.kanji_candidates("コン、キン", "いま"),
        "人": ruby.kanji_candidates("ジン、ニン", "ひと"),
        "一": ruby.kanji_candidates("イチ、イツ", "ひと.つ"),
        "本": ruby.kanji_candidates("ホン", "もと"),
        "自": ruby.kanji_candidates("ジ、シ", "みずか.ら"),
        "分": ruby.kanji_candidates("ブン、フン、ブ", "わ.ける"),
        "建": ruby.kanji_candidates("ケン、コン", "た.てる"),
        "物": ruby.kanji_candidates("ブツ、モツ", "もの"),
        "入": ruby.kanji_candidates("ニュウ", "い.る、はい.る"),
        "口": ruby.kanji_candidates("コウ、ク", "くち"),
    }

    def split(self, surface, reading):
        return ruby.markup(ruby.split_parts(ruby.align(surface, reading), self.KANJI))

    def test_each_kanji_gets_its_reading(self):
        self.assertEqual(self.split("月曜日", "げつようび"), "{月|げつ}{曜|よう}{日|び}")

    def test_sound_changes(self):
        self.assertEqual(self.split("学校", "がっこう"), "{学|がっ}{校|こう}")
        self.assertEqual(self.split("一本", "いっぽん"), "{一|いっ}{本|ぽん}")

    def test_repeated_kanji(self):
        self.assertEqual(self.split("人々", "ひとびと"), "{人|ひと}{々|びと}")

    def test_verb_stems_and_suru_nouns(self):
        self.assertEqual(self.split("建物", "たてもの"), "{建|たて}{物|もの}")
        self.assertEqual(self.split("入口", "いりぐち"), "{入|いり}{口|ぐち}")
        self.assertEqual(self.split("自分", "じぶん"), "{自|じ}{分|ぶん}")
        self.assertEqual(ruby.spoken_reading("運動", "うんどうする"), "うんどう")
        self.assertEqual(ruby.spoken_reading("食べる", "たべる"), "たべる")

    def test_read_as_a_whole_stays_one_group(self):
        self.assertEqual(self.split("今日", "きょう"), "{今日|きょう}")

    def test_single_kanji_and_kana_are_untouched(self):
        self.assertEqual(self.split("月", "つき"), "{月|つき}")
        self.assertEqual(self.split("学ぶ", "まなぶ"), "{学|まな}ぶ")

    def test_readings_always_add_up(self):
        for surface, reading in [("月曜日", "げつようび"), ("学校", "がっこう"), ("人々", "ひとびと")]:
            self.assertEqual("".join(r or "" for _, r in ruby.split_parts(ruby.align(surface, reading), self.KANJI)), reading)
