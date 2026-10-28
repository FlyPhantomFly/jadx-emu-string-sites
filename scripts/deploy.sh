#!/usr/bin/env bash
# Build, test and install the string-sites extension as the ONLY string-sites jar in jadx-emu's extensions dir.
#   usage: scripts/deploy.sh [version]      env: EMU_VERSION (default 0.1.0-beta.6-fp1) must be in ~/.m2 (scripts/deploy-engine.sh of the unpacker repo)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; VER="${1:-0.2.0}"
EXT="$HOME/.config/jadx/plugins-data/jadx-emu/extensions"
cd "$ROOT"
# the Gradle wrapper is not duplicated here: borrow it from the unpacker checkout if it is next door
if [ ! -x ./gradlew ]; then
  SRC="$ROOT/../jadx-emu-dexguard-unpacker"
  [ -x "$SRC/gradlew" ] || { echo "no ./gradlew here and none at $SRC - copy gradle/, gradlew, gradle.properties from a checkout that has them" >&2; exit 1; }
  cp -r "$SRC/gradle" "$SRC/gradlew" "$SRC/gradle.properties" . 2>/dev/null || cp -r "$SRC/gradle" "$SRC/gradlew" .
fi
VERSION="$VER" ./gradlew --no-daemon --console=plain clean test jar
mkdir -p "$EXT"; rm -f "$EXT"/string-sites-*.jar
cp "build/libs/string-sites-$VER.jar" "$EXT/"
echo "installed: $EXT/string-sites-$VER.jar"; ls -la "$EXT"
echo "Enable it:  echo 'autoAnalyze=true' > ~/.config/jadx/plugins-data/jadx-emu/data/string-sites/string-sites.properties"
