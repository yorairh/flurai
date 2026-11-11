# FluRai

FluRai (流) is an Android app for learning Japanese. It teaches vocabulary with spaced-repetition flashcards, grammar, and reading, tests you, and keeps track of how close you are to each JLPT level (N5 to N1).

## Download

1. On your Android phone (Android 8.0 or newer), open the **[latest release](https://github.com/yorairh/flurai/releases/latest)** and download **`FluRai-<version>.apk`**.
2. Open the downloaded file. Android will ask you to allow installing apps from this source ("install unknown apps"): allow it once, then tap **Install**.
3. Open FluRai. Create an account (a confirmation link is sent to your email) or tap **Continue without an account** to try it as a guest.

New versions install over the old one and keep your progress. Always download the newest release.

> The project is private. To open the release link you need a GitHub account that has been invited to this repository. If you cannot open it, ask for the APK file to be sent to you directly.

## What you can do

- **Flashcards:** about 8,000 words from N5 to N1 with readings, meanings and example sentences, scheduled with FSRS (the method Anki uses). Cards go word to meaning, meaning to word, and hear-the-word. Start with hiragana and katakana, make your own decks, or study one level at a time.
- **Grammar:** 521 points from N5 to N1, each with an explanation, how it is built, examples, and practice questions.
- **Reading:** real sentences at your level, with furigana you can turn off. Tap a word for its meaning or to add it to your flashcards.
- **Tests:** a placement test that finds your level, 20-question checkpoints for each level (80% to pass), quick quizzes, and a listening quiz.
- **Progress:** the level the app thinks you are at, how much of each level's words and kanji you know, your weak words, and a 7-day forecast.
- **Streaks and reminders:** study time per day, streaks, and an optional daily reminder.
- **Explore:** video channels for each level, and anime, drama and book recommendations matched to what you know.
- **Backup:** with an account your progress is backed up, so a new phone picks up where you left off. As a guest your progress stays on the phone, and you can move it into an account later.

Coming later: an AI conversation partner.

## Good to know

- Audio uses your phone's text-to-speech. If there is no Japanese voice, the app offers to install one.
- Decks and channels are backed up as a whole: if you change decks on two phones at nearly the same time, the one that syncs last wins.
- The version you have is shown in the app under **Me → Settings → About**.
- Found a bug or have an idea? Tell the person who sent you the app.

## Data and credits

FluRai is built on open data: the JMdict and KANJIDIC dictionaries (EDRDG), the JLPT word lists by Jonathan Waller (tanos.co.uk), example sentences from Tatoeba, and media difficulty from jiten.moe. The full list with licenses is in the app under **Me → Data sources & licenses**.

## For developers

Built with Kotlin and Jetpack Compose. The project plan and status of each milestone are in [docs/ROADMAP.md](docs/ROADMAP.md), release notes are in [docs/releases](docs/releases), and [CLAUDE.md](CLAUDE.md) describes the architecture and the commands.

Build (needs the Android SDK via Android Studio, and JDK 17 or newer; Android Studio's bundled JDK works):

```bash
./gradlew assembleDebug
```

The APK is written to `~/.flurai-build/app/outputs/apk/debug/app-debug.apk` (outside the project folder; see CLAUDE.md for why).
