# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

FluRai is an Android app (Kotlin + Jetpack Compose) for learning Japanese: SRS vocabulary cards, grammar, tests, reading, video and media recommendations, streaks, and later an AI conversation partner, measured against JLPT N5–N1. The feature roadmap is built one milestone at a time (milestone numbers appear in `PlannedFeature` cards on each tab).

## Commands

Use Android Studio's bundled JDK (the `java` on PATH is an old JDK 13):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug          # APK -> ~/.flurai-build/app/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # all unit tests
./gradlew testDebugUnitTest --tests "com.flurai.app.core.model.JlptLevelTest"   # one test class
./gradlew lintDebug
```

There is no emulator. The user tests on their phone: build `assembleDebug` and send them the APK. Builds are signed with the machine's `~/.android/debug.keystore`, so new APKs install over old ones without losing data.

## Firebase

Project `flurai-app` (console: https://console.firebase.google.com/project/flurai-app), Android app `com.flurai.app`. `app/google-services.json` is gitignored; recreate it with:

```bash
firebase apps:sdkconfig ANDROID 1:451607396485:android:bf1319f7372c68d38829ec --project flurai-app --out app/google-services.json
```

The Email/Password sign-in provider must be enabled in the console (Authentication → Sign-in method); without it sign-up fails with `ERROR_OPERATION_NOT_ALLOWED`.

## Build quirks

- The project path contains Hebrew characters. `build.gradle.kts` moves every module's build dir to `~/.flurai-build/<module>` (the unit-test JVM breaks on non-ASCII classpaths on Windows), and `gradle.properties` sets `android.overridePathCheck=true`. Look for outputs and reports there, not in `app/build`.
- AGP 9 has built-in Kotlin: don't apply `org.jetbrains.kotlin.android`. Hilt uses KSP.
- `local.properties` must escape the drive colon (`sdk.dir=C\:/Users/...`) or lint fails.

## Architecture

- Single `app` module, package `com.flurai.app`. Packages are split by feature (`ui/home`, `ui/learn`, `ui/explore`, `ui/profile`, ...) and shared code (`core/model`, `ui/components`, `ui/theme`).
- `MainActivity` → `FluRaiTheme` → `FluRaiAppShell`: a `Scaffold` with a bottom `NavigationBar` and a type-safe Navigation Compose `NavHost` (routes are `@Serializable` objects). Tabs: Home, Learn, Explore, Me.
- DI: Hilt (`@HiltAndroidApp FluRaiApp`, `@AndroidEntryPoint MainActivity`); bindings in `core/di/AppModule.kt`.
- `FluRaiRoot` switches on `AppViewModel.state` (`AppState`: Loading → SignedOut → NeedsOnboarding → Ready). It shows `AuthFlow` (welcome/sign-up/sign-in/reset), `OnboardingScreen`, or the tab shell, which receives the `AuthUser` and `StudyProfile`.
- Auth: `AuthRepository` (Firebase email/password; the display name is the user's name). Firebase error codes become user-facing text in `AuthErrors.kt`; ViewModels only ever see `AuthException` messages.
- `StudyProfile` (level, daily goal, kana knowledge) lives in DataStore keyed by Firebase uid (`StudyProfileRepository`); no profile = onboarding not done. Cloud sync comes in a later milestone.
- `JlptLevel` orders levels easiest-first (Beginner, N5 … N1). Compare by ordinal, never by the number in the name.
- **Japanese text must use `TextStyle.japanese()`** (`ui/theme/Type.kt`). It sets the `ja-JP` locale so devices with a non-Japanese system language draw Japanese, not Chinese, kanji shapes.
- Theme colors: indigo primary, vermilion secondary, matcha tertiary, washi-paper surfaces. Dynamic color is deliberately off.
- The launcher icon (`res/drawable/ic_launcher_foreground.xml`) is the glyph 流 generated from Noto Sans JP Bold (OFL) with fontTools. Regenerate rather than hand-edit.

## Workflow

- Private repo `yorairh/flurai`. One branch per feature (`feature/NN-name`), push and open a PR with `gh` when it's done; merge only after the user has tested the APK and approved.
