# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository. It describes what exists today. Plans and research live in [docs/ROADMAP.md](docs/ROADMAP.md): read it at the start of a session, and update its status table when you open or merge a PR.

## What FluRai is

An Android app (Kotlin + Jetpack Compose, package `com.flurai.app`) for learning Japanese, measured against JLPT N5–N1. Public repo `yorairh/flurai`; releases are signed APKs on GitHub (see Workflow). The running version is shown in the app under Me → Settings → About.

### What the app does

- **Home:** study-now card (cards due, new words left today), streak card with a heat map of study minutes, progress summary.
- **Learn:**
  - **Flashcards:** about 8,000 JLPT words (N5–N1) plus hiragana/katakana, FSRS scheduling, Recognition, Production and Listening cards, your own decks, a deck screen for every built-in deck.
  - **Word library:** search and browse words and kanji with example sentences; add a word to the flashcards or to a deck.
  - **Grammar:** 521 points (N5 89, N4 108, N3 158, N2 95, N1 71) with explanation, formation, examples and fill-in-the-blank practice; one level at a time.
  - **Reading:** graded Tatoeba sentences with furigana, tap-to-look-up, add-to-flashcards, and a comprehension check.
  - **Tests:** adaptive placement test, 20-question level checkpoints (80% to pass), quick quiz, listening quiz.
  - A "Conversation partner" card marked Soon is a placeholder (see Not built).
- **Explore:** Videos (YouTube channels per level, links only) and Media (anime, drama, books from jiten.moe with how much of each you would understand, and "learn the top words first").
- **Me:** level and goals, settings (reminder, streak minutes, listening cards), progress, data sources and licenses, sign out, delete account.
- **Accounts:** Firebase email/password with email confirmation; **guest mode** (data on the phone only, movable into an account later); cloud backup and restore of progress, decks, channels and settings.

### Not built

The AI conversation partner and AI-generated graded content. A parked branch `feature/12-conversation` (PR #15) exists; it needs a provider decision first. Details are in the roadmap. Do not describe these as features.

## Commands

Use Android Studio's bundled JDK (the `java` on PATH is an old JDK 13):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug          # APK -> ~/.flurai-build/app/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # all unit tests
./gradlew testDebugUnitTest --tests "com.flurai.app.core.model.JlptLevelTest"   # one test class
./gradlew lintDebug
./gradlew assembleRelease        # R8-optimised, signed -> ~/.flurai-build/app/outputs/apk/release/app-release.apk
```

Content pipeline (Python 3.12+, stdlib only; downloads are cached in `tools/content/.cache`):

```bash
python -X utf8 tools/content/build_content.py           # rebuilds app/src/main/assets/content.db
python -X utf8 -m unittest discover tools/content       # pipeline tests, incl. checks on the built db
```

**Running the app.** An emulator `Pixel_9` exists. It only starts with software rendering: `"$LOCALAPPDATA/Android/Sdk/emulator.backup/emulator.exe" -avd Pixel_9 -no-snapshot -no-audio -gpu swiftshader_indirect -no-boot-anim`. Install with `adb install -r <apk>`; screenshots with `adb exec-out screencap -p` (prefix `MSYS_NO_PATHCONV=1` in Git Bash). The emulator has no Japanese text-to-speech voice, so audio can only be checked on a real phone. Judge speed on a release build, never a debug build (several times slower in Compose). After changing code that R8 could strip (reflection, serialization, Firestore mapping), check `app/proguard-rules.pro`.

**Signing.** Debug and release builds are both signed with the FluRai release key in `~/.flurai-signing/` (`flurai-release.jks` + `keystore.properties`, outside the repo and OneDrive), so test APKs and releases update each other in place. Never commit the key; if it is missing, builds fall back to the standard debug key and those APKs cannot update an installed release. The key exists only on the owner's PC (a backup was handed to them): losing it means nobody can update the app.

## Firebase

Project `flurai-app` (console: https://console.firebase.google.com/project/flurai-app), Android app `com.flurai.app`. `app/google-services.json` is gitignored; recreate it with:

```bash
firebase apps:sdkconfig ANDROID 1:451607396485:android:bf1319f7372c68d38829ec --project flurai-app --out app/google-services.json
```

- The Email/Password sign-in provider must be enabled (Authentication → Sign-in method); without it sign-up fails with `ERROR_OPERATION_NOT_ALLOWED`.
- Security rules are `firestore.rules` (a user reads and writes only `users/{their uid}/...`, and only with a confirmed email). Deploy changes with `firebase deploy --only firestore:rules --project flurai-app`.

## Build quirks

- The project path contains Hebrew characters. `build.gradle.kts` moves every module's build dir to `~/.flurai-build/<module>` (the unit-test JVM breaks on non-ASCII classpaths on Windows), and `gradle.properties` sets `android.overridePathCheck=true`. Look for outputs and test reports there, not in `app/build`.
- AGP 9 has built-in Kotlin: don't apply `org.jetbrains.kotlin.android`. Hilt uses KSP.
- `local.properties` must escape the drive colon (`sdk.dir=C\:/Users/...`) or lint fails.
- Room validates `@Query` SQL at compile time (KSP), so a typo in a table name fails the build.
- Unit tests cannot use the file-backed DataStore on Windows (the file rename fails): use `InMemoryPrefs` (`app/src/test/.../core/prefs/InMemoryPrefs.kt`).

## Architecture

Single `app` module. Packages are split by feature (`ui/home`, `ui/learn`, `ui/explore`, ...) with the logic in `core/*` and shared UI in `ui/components` and `ui/theme`. Logic that matters is in modules with no Android in them, passed their dependencies and a clock, so it is tested on the JVM.

### App shell and accounts
- `MainActivity` → `FluRaiTheme` → `FluRaiAppShell`: a `Scaffold` with a bottom `NavigationBar` and a type-safe Navigation Compose `NavHost` (routes are `@Serializable`). Tabs: Home, Learn, Explore, Me. DI is Hilt (`@HiltAndroidApp FluRaiApp`, bindings in `core/di/AppModule.kt`).
- `FluRaiRoot` switches on `AppViewModel.state` (Loading → SignedOut → NeedsVerification → NeedsOnboarding → Ready): `AuthFlow`, `OnboardingScreen`, or the tab shell, which receives the `AuthUser` and `StudyProfile`.
- Auth: `AuthRepository` (Firebase email/password; the display name is the user's name). Firebase error codes become user-facing text in `AuthErrors.kt`; ViewModels only see `AuthException` messages.
- **Guest mode** (`core/auth`): `AuthUser.isGuest` with the fixed uid `GUEST_UID` ("guest"), flagged in SharedPreferences, so everything keyed by uid just works. Guests skip email verification and never sync. Leaving guest mode keeps the data; `MoveProgressCard` offers to move it into the new account (`AccountMover`) and `EraseGuestSection` wipes it. New code that needs the user id must not assume a Firebase user exists.
- `StudyProfile` (level, daily goal, new words per day, kana knowledge) lives in DataStore (`StudyProfileRepository`); no profile = onboarding not done. Its `level` only chooses the word list new words come from (shown as "Studying from"); it is **not** the learner's level, which is the estimate below.

### Study content
`core/content`: a read-only Room database (`ContentDatabase`) copied from `assets/content.db`, built by `tools/content/build_content.py` from JLPT lists, JMdict/KANJIDIC2, Tatoeba and `tools/content/grammar.json`, using Room's exported schema in `app/schemas/` so it always matches the entities.
- **Installed apps only re-copy `content.db` when `ContentDatabase.version` goes up.** Bump it whenever the db changes (entities or content), run `./gradlew kspDebugKotlin` to export the schema, then re-run the pipeline.
- Word ids come from `tools/content/word_ids.tsv` (Anki guid → id) and must never be renumbered, because user progress refers to them; commit that file after a rebuild. `jlpt` columns hold the JLPT number (5 = N5, easiest).
- Grammar: `grammar.json` is hand-written. Each example stores an `answer` that appears exactly once in its sentence. Ids only need to be unique and never change (the level comes from the `jlpt` field; order inside a level is by id). To add points, append with new ids and re-run the pipeline.

### Flashcards (`core/srs`, `core/study`, `ui/study`)
- `FsrsScheduler` is a port of py-fsrs 6.x (FSRS-6, as in Anki); `FsrsTest` pins it to values generated by py-fsrs, so regenerate those if you touch the math.
- Progress lives in a separate Room `UserDatabase` (`user.db`, version 6): `card`, `review_log`, `study_day`, `test_result`, `grammar_progress`, `custom_channel`, `deck`, `deck_word`, all keyed by uid. It is never recreated, so **schema changes need a Room migration** (see `UserDatabase.kt`).
- A card is (item type Word/Kana, item id, kind Recognition/Production/Listening). A new word gets a Recognition card; Production (and Listening, if the setting is on) unlock, due after the 4 a.m. rollover, when Recognition first graduates to Review.
- `StudySession` is one flashcard session: queue, reveal/answer, bury/suspend, learn-more and the finished-screen rules, exposed as `state`. It takes a `CardContentSource` (`DatabaseCardContent` in the app, a map in tests), a `StudyClock` and a `now` function. `ReviewViewModel` only adds the route, the profile, the clock's lifecycle and speech. Change session behavior in `StudySession`, tested by `StudySessionTest` against the shared fakes in `StudyFakes.kt`.
- `StudyQueue` orders a session (due learning → reviews → new → learn-ahead 20 min). Within a step the card is picked at random (seeded `Random` in tests), and an item answered in the last 5 cards (`SIBLING_SPACING`) is held back while anything else is available, so a failed card or a word's other card kinds do not come straight back. A card that exists but was never answered (added by hand) is counted and shown as new. The Listening card of a word unlocks a day after its Production card. `StudyRepository.loadSession` picks new items for a `StudySource`: Auto follows the profile (beginners get kana first; words in Genki-lesson order); Level, KanaDeck and Deck offer only that deck's cards and ignore the daily allowance, and the finished screen continues with that deck only. Study days roll over at 4 a.m. (`StudyDay`; `StudyDay.ticks()` re-emits at the rollover for on-screen counts).
- The daily new-word count (`StudyDao.introducedSince`) comes from the review log: a first answer in a session is logged with `state_before = 0`. A word added by hand (`addToStudy`) already has a card, so it never uses the allowance, and its card keeps its real creation time (sync relies on that).
- Audio is Android TextToSpeech (`JapaneseSpeaker`, remembers a request made while the engine starts). Example sentences have a `SentenceSpeakButton` (`ui/components/Speech.kt`; tap plays, long press plays slowly at rate 0.7) on the flashcard answer, word page, grammar examples and Reading; sentences are spoken from their Japanese text, words from their kana reading. `MissingVoiceBanner` is the shared "install a voice" prompt. Speak the kana reading, and only after the answer is revealed, except Listening cards and the listening quiz, whose audio plays as the card/question appears (`SessionState.shown` / `QuizRunState.shown` count appearances).
- **Custom decks** (`core/study/Decks.kt`, `ui/study/Deck*`): a deck only lists word ids, cards are never copied. Built-in decks open `SourceDeckScreen` before studying.

### Progress (`core/knowledge`, `ui/progress`)
`CardEntity.knowledge()` classifies a word by its Recognition card (FSRS stability ≥ 7 days known, ≥ 21 mature; thresholds in `KnowledgeRules`). `computeProgress` turns all of a user's cards into per-level vocabulary coverage, kanji coverage (a kanji counts when it appears in a known word), weak words and a 7-day forecast. `estimateLevel` takes per-level `LevelEvidence` (share of words known, share of grammar points known, checkpoint passed) and climbs from Beginner: a level is reached when its checkpoint was passed (a passed harder checkpoint covers easier levels) or both its words and its grammar are ≥ 80% known. The level chosen at sign-up and placement results are not evidence. Grammar known = `grammarStatus` Known (`grammar_progress`). All pure functions (`ProgressCalculatorTest`); `ProgressViewModel` feeds both Home and the Progress screen.

### Study time, streaks, reminders (`core/activity`, `core/reminder`, `ui/settings`)
- `study_day` holds seconds and reviews per uid and study day. `ActivityTimer` credits time between taps (gap capped at 2 min). **Every study screen credits time through a `StudyClock`** (start/tap/pause/resume/finish over `ActivityTimer` + `StudyActivityRepository.record`, which writes in an app-level scope so leaving a screen cannot lose time) and calls `StudyClockEffects` (`ui/components/StudyTime.kt`: stops the clock in the background, under another screen and on close). Reading-style pages use `rememberStudyScroll(clock)`, which also counts scrolling. New study screens must do the same.
- `computeStreaks`/`summarize` are pure: a day counts at `streakMinutes` (default 5); an unmet today does not break the streak until the day ends.
- `AppSettings` (reminder on/off/time, streak minutes, furigana, listening cards) are DataStore values per uid (`SettingsRepository`). `ReminderScheduler` enqueues a 24 h WorkManager job (`ReminderWorker`, dependencies through a Hilt entry point) and skips the notification when today's goal is met.

### Tests and quizzes (`core/quiz`, `ui/tests`)
- `QuizRun` is one test taken by one learner (questions, answering, adaptive placement stages, scoring, saving, add-missed-words, adopt-placement-level) over a `QuizBackend` (`RepositoryQuizBackend` in the app, a fake in `QuizRunTest`). `QuizViewModel` only connects the route, profile, clock lifecycle and listening audio.
- `QuizKind` owns everything that differs per kind (route text, title, saved `TestType`, saved level, pass rule). A new kind is one declaration there plus its questions in `RepositoryQuizBackend`.
- `Questions.kt` builds multiple-choice questions with same-family distractors and holds `PlacementTest` (from N5 upward, 6 questions per level, 5 right passes the level, the first level not passed ends it; a stage is 4 word and 2 grammar questions, the word's reading hidden). A placement result only suggests where to start (`suggestedLevel`; the result screen offers the checkpoint and "study new words from"). A checkpoint is 30 questions (12 words, 6 kanji readings, 12 grammar blanks; built in `RepositoryQuizBackend`), passed at 80%; `passedCheckpoints(results)` feeds `estimateLevel`.
- Grammar practice is `QuizKind.Grammar` (`grammar_progress` counts right/wrong per point; Known after 3 right with the latest right). Reading's check is `QuizKind.Reading` (wrong options are other sentences' translations).

### Reading, Videos, Media
- **Reading** (`core/reading`): `Lexicon` tokenizes by longest match against the word table (no deconjugation; lone kana are not tappable); `sentenceLevel` is the hardest matched word, and a sentence with an unmatched kanji word is rejected. Furigana: `rubyParts(surface, reading)` (tested) puts a word's reading over each kanji run and none over okurigana; Compose `Text` has no ruby, so sentences are a `FlowRow` of two-line cells (`RubySentence`).
- **Videos** (`core/video`): a bundled list of YouTube channels per level; everything links to YouTube *search* URLs, never video or channel ids, so nothing goes stale and no API key is needed. Users can add their own channels.
- **Media** (`core/media`): the public jiten.moe API (no key), called from the device with `HttpURLConnection`. jiten.moe forbids mirroring, so results are only cached in memory for 15 min. `difficultyRange(level)` maps a JLPT level to jiten's 0–5 difficulty; `coverage` matches a deck's 200 most common words (JMdict ids) against our words via `word.jmdict_id`. Covers use Coil. This is the only feature besides sync that needs the network.

### Cloud sync (`core/sync`)
- `SyncEngine` pulls then pushes (`sync` never throws; failures land in `status`). It talks to two interfaces, so pull-merge-push runs in JVM tests: `SyncCloud` (document-level; `FirestoreCloud` stamps `updatedAt` with the server's time, reads from the server, and turns Firestore errors into `CloudException`) and `SyncLocal` (plain lookups and writes; `RoomSyncLocal`). The rules are pure, in `SyncCodec.kt`: the document layout (a stored format: field names never change), `needsUpload`, `cloudCovers` (which copy wins), `mergeDay`.
- `SyncEngineTest` plays one- and two-phone stories against `InMemoryCloud`/`InMemoryLocal`/`InMemoryPrefs`. **Add a story there for any sync change.**
- Cards, days, tests and grammar are one document per row. Channels, decks and settings are whole documents (`meta/*`): each part keeps a fingerprint of what this phone last uploaded or took (`UserPreferences.uploadedHash(uid, part)`); the cloud's version replaces this phone's only while the local one still matches that fingerprint (or the phone never synced it and has none), otherwise this phone's wins and is uploaded. So deletes travel both ways and a stale cloud copy never undoes them. Two phones editing decks at the same time: the last sync wins.
- `markAllDirty` resets the push cursor (`deleteRemote` does it itself). Not synced: the review log, and suspended/buried flags set without a review.
- **A new per-user table** goes in: `UserDatabase` (+ migration), `UserTables` (`core/account`: its name, what the learner calls it, its cloud collection), `AccountDao` (a DELETE query called from `wipe()`, an UPDATE ... SET uid query called from `move()`), and, if backed up, `SyncLocal`/`RoomSyncLocal`, the codec and `SyncEngine` pull/push (collection names are in `SyncCodec.kt`). `UserTablesTest` fails saying what is missing; the delete-account and erase-guest texts are built from `UserTables`.

### Per-user preferences (`core/prefs`)
All accounts' settings share one DataStore as `<uid>.<name>`. `UserKeys` builds those keys (the two repositories use it for single settings); `UserPreferences` does the whole-account operations (export/import for the cloud copy, move, wipe, `levelsByUid`, sync cursors and uploaded hashes). Nothing else builds or splits such a key.

## Conventions

- **Japanese text must use `TextStyle.japanese()`** (`ui/theme/Type.kt`): it sets the `ja-JP` locale so devices with a non-Japanese system language draw Japanese, not Chinese, kanji shapes.
- `JlptLevel` orders levels easiest-first (Beginner, N5 … N1). Compare by ordinal, never by the number in the name.
- All user-visible text, grammar content and release notes use American English (practice, favor, license).
- Theme: indigo primary, vermilion secondary, matcha tertiary, washi-paper surfaces. Dynamic color is deliberately off.
- The launcher icon (`res/drawable/ic_launcher_foreground.xml`) is the glyph 流 generated from Noto Sans JP Bold (OFL) with fontTools. Regenerate rather than hand-edit.
- Keep a code change and its tests together; fix a bug with a test that fails without the fix.

## Workflow and releases

- One branch per piece of work (`feature/NN-name`, `fix/...`, `docs/...`); push and open a PR with `gh`; merge only after the user has tested the APK and approved. Send the signed release APK for phone testing each time.
- **Versions:** 1.0.0 was the first release for friends. A fix is `1.0.x`, a milestone `1.N.0`; `versionCode` goes up by one every release. Bump `versionCode`/`versionName` in `app/build.gradle.kts` in the release PR, and write `docs/releases/<version>.md` (see earlier ones for the format).
- **After merging a release PR:** check out an up-to-date `main` and run `tools/release.sh <version> "<title>"`. It builds the signed APK, verifies the signature, and creates GitHub release `v<version>` with the notes. Then update the roadmap's status table.
- **Before every release:** unit tests and lint pass; install the release APK over the previous one (an in-place update) and check About shows the new version; if the content db changed, `ContentDatabase.version` was bumped; if `user.db` changed, there is a migration; if sync or security rules changed, the rules are deployed.
