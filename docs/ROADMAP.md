# FluRai roadmap

The master plan for FluRai: what the app should become, the decisions behind it, and where each milestone stands. **Update the status table whenever a milestone's PR is opened or merged.**

## Status

| # | Milestone | Status | Branch / PR | Release |
|---|-----------|--------|-------------|---------|
| 0 | Project setup | ✅ Merged | [PR #1](https://github.com/yorairh/flurai/pull/1) | [v0.1.0](https://github.com/yorairh/flurai/releases/tag/v0.1.0) |
| 1 | Accounts and onboarding | ✅ Merged | [PR #2](https://github.com/yorairh/flurai/pull/2) | [v0.2.0](https://github.com/yorairh/flurai/releases/tag/v0.2.0) |
| 2 | Content pipeline | ✅ Merged | [PR #3](https://github.com/yorairh/flurai/pull/3) | [v0.3.0](https://github.com/yorairh/flurai/releases/tag/v0.3.0) |
| 3 | Vocabulary SRS (Anki-style) | ✅ Merged | [PR #4](https://github.com/yorairh/flurai/pull/4) | [v0.4.0](https://github.com/yorairh/flurai/releases/tag/v0.4.0) |
| 4 | Progress dashboard | ✅ Merged | [PR #6](https://github.com/yorairh/flurai/pull/6) | [0.5.0](https://github.com/yorairh/flurai/releases/tag/v0.5.0) |
| 5 | Study time, streaks, and reminders | ✅ Merged | [PR #7](https://github.com/yorairh/flurai/pull/7) | [0.6.0](https://github.com/yorairh/flurai/releases/tag/v0.6.0) |
| 6 | Tests | ✅ Merged | [PR #8](https://github.com/yorairh/flurai/pull/8) | [0.7.0](https://github.com/yorairh/flurai/releases/tag/v0.7.0) |
| 7 | Grammar module | 🔍 In review (waiting for phone test) | [PR #9](https://github.com/yorairh/flurai/pull/9) | 0.8.0 |
| 8 | Reading and stories | ⏳ | | |
| 9 | YouTube recommendations | ⏳ | | |
| 10 | Media recommendations (jiten.moe) | ⏳ | | |
| 11 | Cloud sync | ⏳ | | |
| 12 | AI conversation partner | ⏳ | | |
| 13 | AI-generated graded content | ⏳ | | |

## Goal

An Android app for learning Japanese: vocabulary (Anki-style cards), grammar (lessons, stories, questions), YouTube video recommendations, level-matched media (anime/shows, manga/books, articles), and later an AI conversation partner. The app shows the learner's current level (JLPT N5→N1 as the reference), what it believes they know, and where to improve now. It schedules both tests of new knowledge and review of known material. Sign-in uses name + email.

Decisions made:
- **Stack:** Kotlin + Jetpack Compose (native Android).
- **Backend:** Firebase (Auth with email/password + display name; Firestore for progress sync later).
- **Starting level:** chosen at onboarding (Beginner, N5–N1). An optional placement test can adjust it later.
- **AI provider:** not decided yet. Build a provider-agnostic interface when we reach milestone 12.
- **Out of scope for now:** friends/social features.

## Development workflow

- Private repo `yorairh/flurai`. Use the `gh` CLI (the GitHub MCP server had auth problems).
- **One branch per milestone** from an up-to-date `main` (`feature/NN-name`); bug fixes on `fix/<topic>`.
- When a milestone is done: push, open a PR with `gh pr create`, and send the user the debug APK. **Merge only after the user has tested on their phone and approved.** Fixes go on the same branch with a new APK.
- Every merged milestone gets a GitHub release (`v0.(N+1).0`, signed APK + notes from `docs/releases/`) via `tools/release.sh`.
- There is no emulator; the user sideloads APKs. Build and test commands are in CLAUDE.md.

## Research findings that shape the design

- **No official JLPT lists have existed since 2010.** The de facto standard is Jonathan Waller's (Tanos) lists (CC BY). Seed data: `jamsinclair/open-anki-jlpt-decks` (MIT, CSV per level, Tanos-derived) and OpenJLPT (CC BY-SA; vocab, kanji, and a 100-point grammar seed).
- **Dictionary:** JMdict/KANJIDIC2 via `scriptin/jmdict-simplified` (JSON). EDRDG licence: CC BY-SA, a dedicated attribution screen, and periodic data updates.
- **Example sentences:** Tatoeba exports (CC BY 2.0 FR). Prefer sentences with checked English translations. Skip Tatoeba audio (mixed licences); use device TTS instead.
- **Grammar:** there is no large open dataset. Tae Kim is NC-licensed, Bunpro has no API, and JLPT Sensei and Imabi are all-rights-reserved (link only). So: take grammar-point names from the Tanos/OpenJLPT lists, **write our own explanations**, attach Tatoeba examples, and link out to external pages and videos.
- **SRS:** FSRS (Anki's modern scheduler) via `open-spaced-repetition/fsrs-kotlin` (FSRS-6, MIT).
- **Media levels:** jiten.moe has an open-source, keyless public API at `https://api.jiten.moe`: media decks by type, per-deck vocabulary sorted by frequency, a difficulty score, and similar decks. The rate limit is 120 requests/min without a key. Its derived data is CC BY-SA. Its rules ban mirroring the database or relaying the API, so **the app calls it directly from the device with a light cache**, never through our own server. Ask on their Discord about app usage before milestone 10.
- **Graded reading:** Tadoku free readers (CC BY-NC-ND), NHK News Web Easy (copyrighted), and Watanoc (N5–N3 tagged articles) are all link-only.
- **YouTube:** the Data API quota is 10k units/day and `search.list` costs 100, so no live search from devices. Instead, use a **curated, bundled catalog** per level and grammar point, for example:
  - Comprehensible Japanese (comprehensible input, N5–N3)
  - Tokini Andy (Genki I/II walkthroughs)
  - Japanese Ammo with Misa (grammar)
  - Nihongo no Mori (N3–N1)
  - Game Gengo
  - Later, refresh the catalog from the API through a Cloud Function.
- **Speech:** Android TextToSpeech (ja-JP) for card audio. SpeechRecognizer (ja-JP) can score speaking by comparing the transcript to the target in kana. Pitch accent can't be judged.

## Architecture

- **Modules:** a single `app` module, with packages split by feature. Split into Gradle modules only if build times demand it.
- **Libraries:**
  - MVVM with StateFlow, Hilt, Room (offline-first), Navigation Compose, and coroutines.
  - Retrofit + kotlinx.serialization (jiten.moe) and Coil (images).
  - Firebase Auth and Firestore.
  - fsrs-kotlin and android-youtube-player.
- **Content DB:** a Python pipeline in `tools/content/` downloads the sources above and builds a **prepackaged SQLite asset** (`app/src/main/assets/content.db`), which Room opens via `createFromAsset`.
  - Tables: `word` (kanji, kana, meanings, JLPT level, frequency), `kanji`, `sentence` (jp, en, linked word ids), `grammar_point` (level, pattern, meaning, formation, our explanation, examples, links), and `video` (curated catalog).
  - User data (cards, reviews, test results) lives in a **separate** Room DB, so content updates never wipe progress.
- **Knowledge model** (the core of "what it thinks I know"):
  - Each item (word, kanji, grammar point) is unseen, learning, known, or mature. The state comes from FSRS stability/retrievability plus test results; placement-test answers can mark items known without SRS.
  - **Level estimate:** coverage per JLPT level (% of that level's vocab, kanji, and grammar known). Current level = the highest level with ≥ 80% coverage; next goal = the level after it.
  - **Weak areas:** items with high lapse rates or low retrievability, plus test categories with low accuracy.
  - This drives the dashboard's "do this now" queue and recommendations. Recommended media targets about 90–95% vocab coverage.
- **Sync:** local storage is the source of truth. Firestore keeps a per-user mirror (`users/{uid}/…`): last-write-wins on card state, append-only review logs.

## Milestones

Each one ends with a PR and an APK for the user to test, then a feedback and bug-fix pass before merging.

0. **Project setup:** private repo, Compose scaffold (`com.flurai.app`), Hilt and Navigation, theme (light/dark, Japanese text), bottom-nav shell, and CLAUDE.md.
1. **Accounts and onboarding:**
   - Firebase project `flurai-app`.
   - Sign up/in with name + email + password, and password reset.
   - Onboarding: choose a level (Beginner→N1), a daily goal (minutes, new cards/day), and a kana check for beginners.
   - "Edit level & goals" on the Me tab changes those choices later.
2. **Content pipeline:** a `tools/content` Python script builds `content.db` (N5–N1 vocab with readings and meanings, kanji, Tatoeba examples), with tests on row counts per level. Also an attribution/licences screen (EDRDG, Tanos, Tatoeba, jiten.moe), and a word library (browse/search words and kanji by level, word pages with examples).
3. **Vocabulary SRS (Anki-style):**
   - Decks per JLPT level, and hiragana/katakana decks (beginners who can't read kana start there).
   - Daily "learn new" and "review" sessions with FSRS-6 and Again/Hard/Good/Easy.
   - Card types: recognition (JP→meaning), then production (meaning→JP) once recognition is learned. Audio via TTS on every card.
   - Example sentence on the answer side; per-word card status with start/suspend/reset; bury/suspend during review.
   - Moved later: custom decks and the card browser (milestone 4), listening-only cards and furigana (milestone 8).
4. **Progress dashboard:**
   - Estimated level with per-level coverage bars (vocabulary and kanji now; grammar once it exists).
   - What the app thinks you know: a word browser filtered by learning / known / mature / suspended.
   - "Improve now" weak areas, review forecast, and today's plan.
5. **Study time, streaks, and reminders:**
   - **Study time:** a `StudySessionTracker` counts active time on study screens. It pauses in the background or after about 2 minutes idle. Totals go per day into a `study_day` table (date, seconds, cards reviewed, new learned).
   - **Streaks:** a day counts once the daily minimum is met (default 5 min or one finished review session, configurable). Show the current and longest streak, a calendar heatmap, and weekly totals. Optional streak freezes (e.g. 1 per week).
   - **Motivation:** a progress ring toward the daily goal, celebrations at 7/30/100 days, and a "streak at risk" banner in the evening.
   - **Notifications (opt-in):** a daily reminder at a chosen time (WorkManager, plus `POST_NOTIFICATIONS` on Android 13+). It is skipped if you've already studied today and has adaptive text ("12 reviews due", "keep your 9-day streak"). Optional streak-at-risk reminder. Rescheduled after reboot.
   - **Settings screen:** reminders on/off, time and days, streak-at-risk toggle, streak minimum, and theme (level and daily goal are already editable from milestone 1).
6. **Tests:**
   - Adaptive placement test.
   - JLPT-style checkpoint tests per level: vocab, kanji reading, grammar, and reading.
   - Daily new-knowledge quizzes and mixed review quizzes.
   - All results feed the knowledge model.
7. **Grammar module:**
   - Grammar points N5→N1: our explanation, formation, Tatoeba examples, external links, and linked videos.
   - Exercises: multiple choice, fill-in-the-blank, and sentence reordering.
   - Grammar points also enter the SRS.
8. **Reading and stories:**
   - An in-app reader for short graded texts, with tap-to-lookup (JMdict), furigana and add-to-deck.
   - Listening-only flashcards (audio → meaning), moved from milestone 3.
   - Level-matched links to Tadoku, NHK Easy, and Watanoc.
   - Comprehension questions.
9. **YouTube recommendations:** the curated catalog by level and grammar point, an embedded player, "watched" tracking, and recommendations driven by your level and weak grammar.
10. **Media recommendations (jiten.moe):**
    - Browse anime, dramas, manga, and novels by difficulty mapped to your level.
    - Estimated personal coverage of each title (its top-frequency vocab vs. your known words).
    - "Learn the top words before watching" builds an SRS deck from a title (with CC BY-SA attribution).
    - Cached, and respects the rate limit.
11. **Cloud sync:** a Firestore mirror of progress, restore on other devices, and an offline queue.
12. **AI conversation partner:**
    - Pick a provider. The LLM is called through a Firebase Cloud Function that holds the API key.
    - Text chat at your level (the prompt is built from the knowledge model), with corrections, explanations, and role-play.
    - Then voice: SpeechRecognizer in, TTS out.
    - Mistakes it notices feed weak areas and cards.
13. **AI-generated graded content:** stories and questions built from known vocab + target grammar ("i+1"), with reading exercises and TTS listening.

## Standing rules

- **App version is always visible:** Me → Settings → About shows `Version x.y.z (code)`, read from the installed package. It lives in Me → Settings → About. Every milestone's PR bumps it (see CLAUDE.md), so the user can tell which build is on the phone.

## Backlog (not scheduled)

- **Custom decks:** user-made word lists to study. Planned for milestone 3, then dropped from milestone 4 to keep it focused; not started. Add when wanted.

## User actions along the way

- Install each test APK on the phone (allow unknown sources once).
- Milestone 1: enable Email/Password sign-in in the Firebase console.
- Before milestone 10: optionally ask jiten.moe (Discord) about API use in a personal app.
- Milestone 12: choose the AI provider and supply an API key.

## Verification (per milestone)

- `assembleDebug`, `testDebugUnitTest`, and `lintDebug` pass (commands in CLAUDE.md).
- Unit tests cover the core logic: FSRS wrapper, knowledge model and level estimator, quiz generation, kana normalization, streak and daily-time math (midnight and time-zone edges), and jiten.moe mapping. The content pipeline has Python tests.
- Manual: the user installs the APK and walks through the milestone's flow.
