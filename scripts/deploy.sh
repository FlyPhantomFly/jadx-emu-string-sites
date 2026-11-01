#!/usr/bin/env bash
# Build, test and install string-sites as the only string-sites jar in the jadx-emu extensions directory.
# Usage: scripts/deploy.sh [version]
# The jadx-emu engine (EMU_VERSION, default 0.2.0) has to be in ~/.m2 already.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXT="$HOME/.config/jadx/plugins-data/jadx-emu/extensions"
cd "$ROOT"
[ -x ./gradlew ] || { echo "no ./gradlew in $ROOT" >&2; exit 1; }
if [ -n "${1:-}" ]; then export VERSION="$1"; fi
./gradlew --no-daemon --console=plain clean test jar
JAR="$(ls -t build/libs/string-sites-*.jar | head -n 1)"
mkdir -p "$EXT"
rm -f "$EXT"/string-sites-*.jar
cp "$JAR" "$EXT/"
echo "installed: $EXT/$(basename "$JAR")"
ls -la "$EXT"
echo "To turn it on: echo 'autoAnalyze=true' > ~/.config/jadx/plugins-data/jadx-emu/data/string-sites/string-sites.properties"
