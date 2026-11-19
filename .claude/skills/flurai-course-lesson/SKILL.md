---
name: flurai-course-lesson
description: How to write, fix or extend a lesson (or study note) of the FluRai grammar course (tools/content/course/*.json). Use whenever the user wants a new course lesson or note, wants to change a lesson's text, train diagram, table or test questions, or reports a bug in a course lesson (furigana, layout, wrong question), even if they only say "lesson 5", "add a lesson about X" or "fix the course test". Covers the file format, the writing and question rules, the checks, and the content-database version bump needed for the change to reach phones.
---

# Writing a FluRai grammar-course lesson

The course is one JSON file per entry in `tools/content/course/NNN-slug.json`; file name order is the course order. `tools/content/course.py` validates them and the pipeline turns them into `course_lesson` / `course_question` rows of `content.db`. `CLAUDE.md` ("Grammar course") has the rules in short; this skill is the working procedure. The finished example to copy is `050-verb-groups-te-form.json` (lesson 5); read it first, together with one lesson of a different kind (for example `930-kuse-ni.json`) so you do not copy one lesson's quirks.

## 1. Before writing

- The text is written for FluRai after Cure Dolly's *Organic Japanese*. **Start from the summarized lessons at https://kellenok.github.io/cure-script/** (read the page of the lesson you are writing, and the ones just before it so the order of ideas and the terms match). Take the order of ideas and the video link (`video`) from the original lesson, but never copy its wording: that site and the video have no license for reuse.
- Decide the entry's `id`, `number` and file name. `id` = number x 10 (7.5 is 75, 8b is 85), file name is `<id padded to 3>-<slug>.json`, and an entry slips between two others by choosing an id between theirs. Ids of entries and questions are stored in learners' progress, so they never change once released.
- A **lesson** has `kind: "lesson"`, `jlpt` (1-5) and at least 20 questions. A **note** has `kind: "note"`, no `jlpt` and no questions.
- `related` lists ids of reference `grammar_point`s (`tools/content/grammar.json`) the lesson covers; each must exist.

## 2. Lesson text (`blocks`)

Block types: `heading`, `text`, `example` (`jp`, `en`), `diagram` (the train), `keypoints` (`title`, `items`), `table` (`title`, `rows`, first row is the header), `dialogue` (`lines` of `who`, `jp`, `en`, two speakers). Text supports only `**bold**` (no italics).

Write in this order, as lesson 5 now does:

1. **Say what the thing is for before how it is made.** A learner who is told the recipe first does not know why to care. Open with the purpose and one example they already know, then the recipe.
2. Every heading is followed by a sentence that explains, then the examples or table.
   **When a lesson has several headings, number them**: main sections `1.`, `2.`, `3.` and sub-sections `1.1`, `1.2`, `2.1`, written into the heading text (`"2. Godan verbs"`, `"2.1 Godan verbs that look ichidan"`). The numbers restart at 1 in each lesson, count every section in order without skipping or repeating, and a sub-heading is numbered after its parent. The intro paragraph before the first heading is not numbered. A lesson with only one or two headings can skip numbering.
3. **Show each example once.** If a table lists the examples, do not repeat the same words in the paragraph above it (lesson 5's ichidan section did, and it read as clutter).
4. A rule with exceptions or look-alikes gets its **own sub-heading** (lesson 5: "Godan verbs that look ichidan"). An aside tucked at the end of a paragraph is missed.
5. Do not add mnemonic "memory hooks" unless they are clearly simpler than the rule. Confusing sound stories were removed from lesson 5 because the learner could not follow them; "Remember" keypoints at the end are enough.
6. Tables: short headers and short cells so each row stays on one line at phone width (a table that does not fit scrolls sideways, with a hint). Put a verb and its form in one cell like `買う→買って` only when the column is wide enough; otherwise use separate columns.
7. American English (practice, favor, license).

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
- Wrong options should be plausible mistakes of the same shape (て-form endings among て-form endings), never nonsense.
- Mix the types and cover each rule, each exception and the irregular cases; add a few true/false and tap questions so it is not all multiple choice.
- The lesson test asks 10 of the questions at random, so every question must stand alone and be answerable from the lesson text alone.

## 4. Check

Run from the repo root, with Android Studio's JDK for Gradle (see `CLAUDE.md`):

```bash
python -X utf8 tools/content/check_course.py <id>     # one entry, no rebuild; prints every problem
python -X utf8 tools/content/build_content.py         # rebuilds app/src/main/assets/content.db (takes minutes)
python -X utf8 -m unittest discover tools/content     # incl. test_course.py checks on the files and the db
```

## 5. Get it to a phone (the part that is easy to get wrong)

Installed apps re-copy `content.db` **only when `ContentDatabase.version` goes up**, so an unchanged version means the phone keeps the old lesson text. Do it in this order, once:

1. Bump `version` in `app/src/main/java/com/flurai/app/core/content/ContentDatabase.kt`.
2. `./gradlew kspDebugKotlin` exports the new `app/schemas/.../<version>.json`. **This must come before the rebuild**: the pipeline stamps the database with the newest schema file's number, and a mismatch makes Room wipe the content tables (the course then shows 0 lessons). The schema file is identical to the previous one when only rows changed; that is expected. If an earlier unreleased version number is left over, delete its untracked schema file.
3. Rebuild the content, run the tests, then `./gradlew assembleRelease` and `adb install -r ~/.flurai-build/app/outputs/apk/release/app-release.apk`.
4. On the emulator, open Learn -> Grammar -> the lesson, scroll through every block and open "Test". If an earlier build left a wrong-version `content.db` on the device, remove it with `adb shell run-as com.flurai.app rm -f databases/content.db*` and relaunch.

For a release also bump `versionCode`/`versionName` and add `docs/releases/<version>.md` (see `CLAUDE.md`, Workflow).

## 6. Looking at the result

Check these on screen, because the validator cannot: the train fits without scrolling, every table row is one line, furigana reads correctly (especially on words you bolded or that start with a kanji with several readings), the purpose is explained before the recipe, and nothing is shown twice.
