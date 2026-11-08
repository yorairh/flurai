#!/usr/bin/env bash
# Publishes a FluRai release from main after a milestone PR is merged:
#   tools/release.sh 0.5.0 "Title of the milestone"
# Needs: docs/releases/<version>.md (release notes), versionName <version> in app/build.gradle.kts,
# the release key in ~/.flurai-signing, and an authenticated gh CLI.
set -euo pipefail

version="${1:?usage: tools/release.sh <version> <title>}"
title="${2:?usage: tools/release.sh <version> <title>}"
notes="docs/releases/$version.md"
cd "$(git rev-parse --show-toplevel)"

[[ "$(git branch --show-current)" == "main" ]] || { echo "Release from main (git checkout main && git pull)."; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "Working tree has uncommitted changes."; exit 1; }
git fetch -q origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "main is not in sync with origin/main."; exit 1; }
[[ -f "$notes" ]] || { echo "Missing release notes: $notes"; exit 1; }
grep -q "versionName = \"$version\"" app/build.gradle.kts || { echo "app/build.gradle.kts versionName is not $version."; exit 1; }
[[ -f "$HOME/.flurai-signing/keystore.properties" ]] || { echo "Release key not found in ~/.flurai-signing."; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
./gradlew assembleRelease --console=plain -q

apk_dir="$HOME/.flurai-build/app/outputs/apk/release"
out="$apk_dir/FluRai-$version.apk"
cp "$apk_dir/app-release.apk" "$out"

build_tools="$(ls -d "${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk/build-tools/"* | sort -V | tail -1)"
"$build_tools/apksigner.bat" verify --print-certs "$out" | grep -q "CN=FluRai" || { echo "APK is not signed with the FluRai key."; exit 1; }

gh release create "v$version" "$out" --target "$(git rev-parse HEAD)" --title "FluRai $version: $title" --notes-file "$notes" --latest
echo "Released v$version"
