"""Makes the Anki test packages with the official Anki library (pip install anki), so they are real exports.

    python make_fixtures.py <output dir>

Writes:
  japanese.apkg         Anki 2.1.50+ format (collection.anki21b, zstd, schema 18), with scheduling
  japanese-legacy.apkg  the "support older Anki versions" format (collection.anki21, schema 11)

The deck "Japanese" has a subdeck "Japanese::Food". Note type "Japanese (recognition & production)" has the
fields Expression / Reading / Meaning and two templates; FSRS is on and some cards were reviewed.
"""
import os
import sys
import tempfile
import time

from anki.collection import Collection, ExportAnkiPackageOptions, DeckIdLimit

out_dir = sys.argv[1]
tmp = tempfile.mkdtemp()
col = Collection(os.path.join(tmp, "collection.anki2"))

# A note type like the common Japanese vocabulary decks.
mm = col.models
nt = mm.new("Japanese (recognition & production)")
for name in ("Expression", "Reading", "Meaning", "Audio"):
    mm.add_field(nt, mm.new_field(name))
t1 = mm.new_template("Recognition")
t1["qfmt"] = "{{Expression}}"
t1["afmt"] = "{{FrontSide}}<hr id=answer>{{furigana:Reading}}<br>{{Meaning}}"
mm.add_template(nt, t1)
t2 = mm.new_template("Production")
t2["qfmt"] = "{{Meaning}}"
t2["afmt"] = "{{FrontSide}}<hr id=answer>{{Expression}}<br>{{Reading}}"
mm.add_template(nt, t2)
mm.add(nt)

main = col.decks.id("Japanese")
food = col.decks.id("Japanese::Food")

rows = [
    (main, "食べる", "食べる[たべる]", "to eat"),
    (main, "<b>学校</b>", "がっこう", "school&nbsp;(building)"),
    (main, "推し", "おし", "favourite idol [sound:oshi.mp3]"),
    (main, "ふわふわ", "", "fluffy"),
    (main, "猫が好きです。", "ねこがすきです。", "I like cats."),
    (main, "ぴよぴよ語", "ぴよぴよご", "a word no dictionary has"),
    (food, "寿司", "すし", "sushi"),
    (food, "ラーメン", "", "ramen"),
]
notes = []
for deck, expr, reading, meaning in rows:
    n = col.new_note(nt)
    n["Expression"], n["Reading"], n["Meaning"] = expr, reading, meaning
    col.add_note(n, deck)
    notes.append(n)

# FSRS on, then review the first two notes' recognition cards so they carry stability/difficulty.
col.set_config("fsrs", True)
# 食べる: answered Easy (now a review card with an FSRS memory state); 学校: answered Good (still learning).
for n, rating in ((notes[0], 4), (notes[1], 3)):
    card = n.cards()[0]
    card.start_timer()
    col.sched.answerCard(card, rating)
# Suspend sushi's cards.
col.sched.suspend_cards([c.id for c in notes[6].cards()])

for legacy, name in ((False, "japanese.apkg"), (True, "japanese-legacy.apkg")):
    path = os.path.join(out_dir, name)
    col.export_anki_package(
        out_path=path,
        options=ExportAnkiPackageOptions(with_scheduling=True, with_deck_configs=True, with_media=False, legacy=legacy),
        limit=DeckIdLimit(main),
    )
    print("wrote", path, os.path.getsize(path))
col.close()
