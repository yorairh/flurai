# FluRai

An Android app for learning Japanese: Anki-style vocabulary cards, grammar lessons, level-matched reading, video and media recommendations, study streaks, and (later) an AI conversation partner. Progress is measured against the JLPT levels N5–N1.

Built with Kotlin and Jetpack Compose.

## Build

Requires the Android SDK (via Android Studio) and JDK 17+ (Android Studio's bundled JDK works).

```bash
./gradlew assembleDebug
```

The APK is written to `~/.flurai-build/app/outputs/apk/debug/app-debug.apk` (outside the project folder; see CLAUDE.md for why).
