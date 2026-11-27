---
name: flurai-course-lesson
description: How to write, fix or extend a lesson (or study note) of the FluRai grammar course (tools/content/course/*.json). Use whenever the user wants a new course lesson or note, wants to change a lesson's text, train diagram, table or test questions, or reports a bug in a course lesson (furigana, layout, wrong question), even if they only say "lesson 5", "add a lesson about X" or "fix the course test". Covers the file format, the writing and question rules, the checks, and how the rebuilt content reaches phones (no version bump needed).
---

# Writing a FluRai grammar-course lesson

The course is one JSON file per entry in `tools/content/course/NNN-slug.json`; file name order is the course order. `tools/content/course.py` validates them and the pipeline turns them into `course_lesson` / `course_question` rows of `content.db`. `CLAUDE.md` ("Grammar course") has the rules in short; this skill is the working procedure. The finished example to copy is `050-verb-groups-te-form.json` (lesson 5); read it first, together with one lesson of a different kind (for example `930-kuse-ni.json`) so you do not copy one lesson's quirks.

**A course change is JSON only.** Edit the lesson file, run the pipeline, build. No Kotlin change, no `ContentDatabase.version` bump and no new `app/schemas` file: the pipeline writes `content.db.stamp` and the app copies the new content by itself when the stamp changes.

## 1. Before writing

- The text is written for FluRai after Cure Dolly's *Organic Japanese*. **Start from the summarized lessons at https://kellenok.github.io/cure-script/** (read the page of the lesson you are writing, and the ones just before it so the order of ideas and the terms match). Take the order of ideas and the video link (`video`) from the original lesson, but never copy its wording: that site and the video have no license for reuse.
- Decide the entry's `id`, `number` and file name. `id` = number x 10 (7.5 is 75, 8b is 85), file name is `<id padded to 3>-<slug>.json`, and an entry slips between two others by choosing an id between theirs. Ids of entries and questions are stored in learners' progress, so they never change once released.
- A **lesson** has `kind: "lesson"`, `jlpt` (1-5) and at least 20 questions. A **note** has `kind: "note"`, no `jlpt` and no questions.
- `related` lists ids of reference `grammar_point`s (`tools/content/grammar.json`) the lesson covers; each must exist.
- **Titles must be correct.** The `title` is the first thing a learner reads and it promises what the lesson teaches, so check it against the finished lesson, not against the original video alone:
  - It names what the lesson actually teaches, and every part of it is covered by the text and tested by the questions. A title that promises more than the lesson delivers (lesson 10 was "Helper verbs and the potential" but teaches only the potential) is wrong; shorten the title or add the missing teaching.
  - **It states the lesson's idea, not the textbook category.** A bare grammar word ("Adjectives", "Particles", "Verb tenses") is a warning sign: lesson 6 was called "Adjectives" but teaches how any engine turns white and goes before a noun, so it is now "Describing a noun: words before a noun". Ask "what can the learner do or see after this lesson?" and put that in the title. The original lesson's title is only a hint, because the course teaches its own picture (train, stems, detectives); never keep it just because the original used it. Use the course's own terms (main carriage, engine, い-stem), not textbook terms the lesson never uses.
  - It is short (about 3-8 words), in American English, in sentence case, with no number in it (`number` shows separately) and no trailing period.
  - Titles of neighboring lessons are distinct and read as a sequence; two lessons never share a title (use "…, part 2" style only when the original does).
  - The `summary` matches the title and the lesson: one or two sentences, same terms, and no claim the text does not make (no "you saw with 好き" unless that lesson came earlier and the text says so).
  - Every `heading` is checked the same way: it describes the block under it, is numbered when the lesson has several, and does not repeat the lesson title.
  - Re-check all of this at the end (section 6), because the lesson often changes while you write it, and the title must change with it.

## 2. Lesson text (`blocks`)

Block types: `heading`, `text`, `example` (`jp`, `en`), `diagram` (the train), `keypoints` (`title`, `items`), `table` (`title`, `rows`, first row is the header), `dialogue` (`lines` of `who`, `jp`, `en`, two speakers). Text supports only `**bold**` (no italics).

Write in this order, as lesson 5 now does:

1. **Say what the thing is for before how it is made.** A learner who is told the recipe first does not know why to care. Open with the purpose and one example they already know, then the recipe.
2. Every heading is followed by a sentence that explains, then the examples or table.
   **When a lesson has several headings, number them**: main sections `1.`, `2.`, `3.` and sub-sections `1.1`, `1.2`, `2.1`, written into the heading text (`"2. Godan verbs"`, `"2.1 Godan verbs that look ichidan"`). The numbers restart at 1 in each lesson, count every section in order without skipping or repeating, and a sub-heading is numbered after its parent. The intro paragraph before the first heading is not numbered. A lesson with only one or two headings can skip numbering.
3. **Teach only what this lesson is about.** Every word, verb, example and table row must be used by the lesson's own explanation or tested by its questions. Do not add "common words to get to know", extra vocabulary lists, or a table of items the lesson never explains (lesson 4 once had a table of 11 verbs in three forms; the learner only needed three of them, and the rest were noise that a later lesson teaches properly). Before keeping any list, table or example, ask: does the learner need this to understand this lesson's idea? If not, cut it, and leave it to the lesson that teaches it. The same goes for background facts and side remarks: say them in the lesson they belong to.
4. **Show each example once.** If a table lists the examples, do not repeat the same words in the paragraph above it (lesson 5's ichidan section did, and it read as clutter).
5. **Every rule gets at least one example sentence.** A rule is anything the learner must apply: a form (ない on the あ-stem), a change (a potential verb is ichidan, so 話せない), a use (い-stem + 方 = "the way of doing it"), an exception (いい → よくない). Each one needs its own `example` block (a whole sentence with `en`) right after the text that states it; a word in the paragraph (話し方) or a row in a table is not enough. Lesson 7.5 once said 方 makes "way of doing" with no sentence, and never said the potential is ichidan, yet its test asked for 読めない: a rule with no example is easy to miss, and a rule that is only tested and never stated is a bug. When you add or change a rule, add its example in the same edit, and make sure no question relies on a rule the text does not state and show.
6. A rule with exceptions or look-alikes gets its **own sub-heading** (lesson 5: "Godan verbs that look ichidan"). An aside tucked at the end of a paragraph is missed.
7. Do not add mnemonic "memory hooks" unless they are clearly simpler than the rule. Confusing sound stories were removed from lesson 5 because the learner could not follow them; "Remember" keypoints at the end are enough.
8. Tables: short headers and short cells so each row stays on one line at phone width (a table that does not fit scrolls sideways, with a hint). Put a verb and its form in one cell like `買う→買って` only when the column is wide enough; otherwise use separate columns.
9. American English (practice, favor, license).

**Furigana is added by the pipeline.** Never write ruby markup yourself except in `readings` (below). Two traps:
- **Never bold part of a word** (`食べ**て**いる`). It splits the word, and the dictionary then reads 食 alone as しょく. Bold the whole word (`**食べて**いる`) or none of it.
- If a spelling still gets the wrong reading, add it to the entry's `readings`: `{"行いて": "{行|い}いて"}`. The markup must spell exactly the key text.

### The train (`diagram`)

A `diagram` has `cars` (each `text`, `role`, optional `label`) and a `text` caption. Roles: `carriage`, `engine`, `topic`, `zero`, `white`, `particle`, `plain`.

- Say "main carriage" and "engine", never "A-car"/"B-engine". The main carriage is the が-subject (`carriage`, or `zero` written `Øが` when it is not said). A topic is a `topic` car and is never the zero carriage. Noun + だ/です is one `engine`. A clause or word in front of a noun is a `white` describer, never the engine.
- The particle belongs to its noun car (`水を`, not 水 + を). Do not drop it: an object needs its を car.
- Each engine is one car; if a helper matters (食べ + たい) say so in its label or the caption.
- **Keep it small: 3-5 cars.** Car width comes from text plus label, so give a label only where it teaches something. A `zero` car needs none (the caption's `（私が）…` explains it). If it still runs past the screen the app shows a fade and an arrow, which usually means too many labels or too many cars.
- The caption starts with the full sentence, including the unspoken subject in brackets: `（私が）水を飲んでいる。`

## 3. Test questions (`questions`)

Types: `choice` (`options` 2-5, `answer` is an index; options are shuffled unless there are only two), `tap` (tap one word of `options`, `answer` is its index), `order` (`options` are the pieces **in the right order**, the app shuffles them; other natural orders go in `alt`). Every question needs `instruction`, `note` (the one-line why) and, when it shows a Japanese sentence, `en` (shown after answering). `prompt` is "" when there is none. Question ids are unique inside the entry (existing lessons start anywhere, so just pick unused numbers).

Rules that `course.py` enforces (a failure stops the build):
- At least 20 questions per lesson, none in a note.
- **A question must use a new sentence.** One that repeats a lesson `example` (also with a gap, or as joined pieces) fails. Test the idea, not memory.
- Kanji above N4 that the lesson does not show fail; add `extraKanji` only when it is justified.
- A Japanese sentence needs `en`; an `order` question needs a `prompt` and 3+ pieces.

Rules from experience, not enforced:
- **Never ask a learner to produce a form of a verb or word they were not given.** A gap question like `友達を待＿＿いる` needs the dictionary form in the instruction: "Fill the gap with the て-form of 待つ". Questions that offer the full word as an option are fine.
- **Use only verbs and words the lesson itself shows or explains.** A question may not rely on a word that only a deleted table or a later lesson covers (when lesson 4's extra verb table was cut, its questions about 会う and 走る changed to 飲む and 食べる, which the lesson text shows).
- Wrong options should be plausible mistakes of the same shape (て-form endings among て-form endings), never nonsense.
- Mix the types and cover each rule, each exception and the irregular cases; add a few true/false and tap questions so it is not all multiple choice.
- The lesson test asks 10 of the questions at random, so every question must stand alone and be answerable from the lesson text alone.

## 4. Check

Run from the repo root:

```bash
python -X utf8 tools/content/check_course.py <id>     # one entry, no rebuild; prints every problem
python -X utf8 tools/content/build_content.py         # rebuilds content.db AND content.db.stamp (takes minutes)
python -X utf8 -m unittest discover tools/content     # incl. test_course.py, and a check that the stamp matches content.db
```

`content.db.stamp` is the SHA-256 of `content.db`. If the unit tests say the stamp does not match, the pipeline was not re-run after the last change.

## 5. Get it to a phone

Installed apps compare `content.db.stamp` with the stamp saved at their last copy of `content.db`; when it is new they delete the old copy and copy the shipped one (`core/content/ContentCopy.kt`). So nothing in Kotlin changes for a lesson:

1. Run the pipeline and the tests (section 4). Commit the lesson JSON, `app/src/main/assets/content.db` and `app/src/main/assets/content.db.stamp` together (plus `tools/content/word_ids.tsv` if it changed).
2. Build with Android Studio's JDK (see `CLAUDE.md`): `./gradlew assembleRelease`, then `adb install -r ~/.flurai-build/app/outputs/apk/release/app-release.apk`. This also works over an earlier build of the same version: no uninstall, no downgrade, no `adb shell run-as ... rm`.
3. On the emulator open Learn -> Grammar -> the lesson, scroll through every block and open "Test".
4. **Old text still showing?** The stamp was not regenerated: re-run the pipeline, and check that `git status` lists both `content.db` and `content.db.stamp` as changed.

For a release also bump `versionCode`/`versionName` and add `docs/releases/<version>.md` (see `CLAUDE.md`, Workflow).

**Only if a table changes** (a new column in `ContentEntities.kt`; a lesson never needs this): bump `ContentDatabase.version`, run `./gradlew kspDebugKotlin` **before** the pipeline (the pipeline stamps the database with the newest schema file's number, and a mismatch makes Room wipe the content tables, so the course shows 0 lessons), delete the previous schema file in `app/schemas/com.flurai.app.core.content.ContentDatabase/`, then run the pipeline.

## 6. Looking at the result

**Rule-by-rule pass.** Before calling a lesson done, list every rule its text states (each form, change, use and exception, including ones in a "Remember" box) and check each one has an `example` sentence of its own, then go through the questions and check each answer follows from a rule on that list. Fix a missing example or a missing rule in the lesson, not by changing the question. The validator does not check this.

Check these on screen, because the validator cannot: the train fits without scrolling, every table row is one line, furigana reads correctly (especially on words you bolded or that start with a kanji with several readings), the purpose is explained before the recipe, nothing is shown twice, and the title, summary and headings still match what the lesson ended up teaching.
