#!/usr/bin/env bash
# Analyse an app and print the start of the report. Skips decompiling, so it's quick.
# Usage: scripts/run-on.sh <app.apk|classes.dex> [class-prefix, e.g. Lcom/example/]
set -euo pipefail
IN="${1:?usage: run-on.sh <apk|dex> [class-prefix]}"; PFX="${2:-}"
DATA="$HOME/.config/jadx/plugins-data/jadx-emu/data/string-sites"; PROPS="$DATA/string-sites.properties"
mkdir -p "$DATA"; bak=$(mktemp); had=0; [ -f "$PROPS" ] && { cp "$PROPS" "$bak"; had=1; }
trap 'if [ "$had" -eq 1 ]; then cp "$bak" "$PROPS"; else rm -f "$PROPS"; fi; rm -f "$bak"' EXIT
{ echo "autoAnalyze=true"; [ -n "$PFX" ] && echo "classPrefix=$PFX"; true; } > "$PROPS"; rm -f "$DATA"/strings-report.*
OUT="$(mktemp -d)"
time jadx -v --no-src --no-res -d "$OUT" "$IN" 2>&1 | tee /tmp/string-sites-run.log | grep -E "string-sites|jadx-emu .* loaded" || true
[ -f "$DATA/strings-report.txt" ] || { echo "no report - is the extension installed (scripts/deploy.sh)?"; exit 1; }
echo; head -40 "$DATA/strings-report.txt"; echo "..."; echo "full report: $DATA/strings-report.txt  and  strings-report.json"
