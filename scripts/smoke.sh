#!/usr/bin/env bash
# Post-deploy check through the REAL jadx CLI, no APK needed. Runs the extension on two synthetic apps and verifies the report.
#   fixtures/string-sites-fixture.dex : 30 sites -> 20 decoded (tier 1: 3, tier 2: 17), 10 skipped for 5 distinct reasons
#   fixtures/string-sites-random.dex  : 40 helper calls with random non-ASCII / long plaintexts -> all 40 decoded
# Restores your string-sites.properties afterwards.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$HOME/.config/jadx/plugins-data/jadx-emu/data/string-sites"; PROPS="$DATA/string-sites.properties"
command -v jadx >/dev/null || { echo "jadx not on PATH" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 needed to validate the JSON" >&2; exit 2; }
mkdir -p "$DATA"; bak=$(mktemp); had=0; [ -f "$PROPS" ] && { cp "$PROPS" "$bak"; had=1; }
trap 'if [ "$had" -eq 1 ]; then cp "$bak" "$PROPS"; else rm -f "$PROPS"; fi; rm -f "$bak"' EXIT
run() { # fixture, extra properties
  printf 'autoAnalyze=true\n%s\n' "$2" > "$PROPS"; rm -f "$DATA"/strings-report.*
  timeout 300 jadx -v --no-src --no-res -d "$(mktemp -d)" "$HERE/fixtures/$1" > /tmp/string-sites-smoke.log 2>&1 || true
  grep -E "string-sites v|jadx-emu .* loaded" /tmp/string-sites-smoke.log | sed 's/^/  /'
  [ -f "$DATA/strings-report.json" ] || { echo "SMOKE: FAIL - no report written. Last lines:"; grep -E "ERROR|Exception|string-sites|NoSuchMethod" /tmp/string-sites-smoke.log | tail -8; exit 1; }
}
echo "== fixture 1 (mixed: every outcome and reason)"
run string-sites-fixture.dex $'maxSteps=20000\nmaxMillis=3000\nmaxDepth=16'
python3 - "$DATA/strings-report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8")); s = r["summary"]
assert (s["found"], s["decoded"], s["skipped"]) == (42, 29, 13), s
assert (s["decrypted"], s["folded"]) == (17, 12), (s["decrypted"], s["folded"])
assert s["decodedByTier"] == {"tier1": 3, "tier2": 26}, s["decodedByTier"]
assert s["skipReasons"] == {"UNREACHABLE": 1, "UNKNOWN_ARGUMENT": 2, "NON_CONSTANT_ARGUMENTS": 1, "STEP_OR_TIME_LIMIT": 3, "HOST_DENIED": 3, "NATIVE_METHOD": 3}, s["skipReasons"]
vals = {x["value"] for x in r["sites"] if x["status"] == "decoded"}
assert {"app-secret", "https://example.test/api", "chars-one", "ki", "WM-Data"} <= vals, vals
# the tag* helper is folded, the xor loop is decrypted
byconf = {(x["value"], x["confidence"]) for x in r["sites"] if x["status"] == "decoded"}
assert ("WM-Data", "FOLDED") in byconf and ("app-secret", "DECRYPTED") in byconf, byconf
print("  ok: 42 found / 29 decoded (17 decrypted, 12 folded) / 13 skipped; tag*/char-to-byte folded, xor-loop decrypted, native -> NATIVE_METHOD")
PY
echo "== fixture 2 (random plaintexts: emoji, CJK, NUL, U+2028, 4-6k chars)"
run string-sites-random.dex ""
python3 - "$DATA/strings-report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8")); s = r["summary"]
assert (s["found"], s["decoded"], s["skipped"]) == (40, 40, 0), s
assert any(len(x["value"]) > 3000 for x in r["sites"]) and any(any(ord(c) > 0xffff for c in x["value"]) for x in r["sites"])
print("  ok: 40/40 decoded; JSON parses with Python's strict json.load; long and supplementary-plane strings present")
PY
echo "SMOKE: PASS"
