# jadx-emu string-sites (prototype 0.1.0)

A jadx-emu extension that finds decrypted strings **without matching any decryptor's shape**, and reports every site it could
not decode together with *why*. It changes nothing in the decompiled output yet; it writes `strings-report.json` / `.txt`.

This is tiers 1 and 2 plus the skip report (items 6, 1 and 2 of the extension-ideas list). Items 3-5 and 7-13 are not built.

## How it works

1. **Sinks.** It finds string sinks by the instruction itself: `String.intern()`, `new String(char[])`, `String.valueOf(char[])`,
   `new String(byte[])`, `new String(byte[], charset)`. Register order, extra arithmetic and dead filler don't matter.
2. **Tier 1 - dataflow.** `Dataflow` is run once per method and the sink's argument register is read. This decodes straight-line
   decoders for free and never executes the app. Verified: for an inline decode *loop* it returns *unknown*, never a stale array.
3. **Tier 2 - helpers found by usage.** A static app method that returns `String`/`char[]`/`byte[]`, takes only primitives/Strings and is
   called from at least `helperMinConstantSites` (3) places with **all-constant arguments** is a decryptor. Each such call is run with
   its constants through `Vm.invoke` (one VM per helper, under `ExecLimits`). This is what decodes loop-based decoders, which
   dataflow cannot. A sink fed by a decoded helper call *in the same straight-line block* is linked to it; across a join it is not.
4. **Decrypted vs folded.** A tier-2 value is marked `decrypted` only if its helper does byte/char arithmetic over its input, or the helper's owner is clearly name-obfuscated; otherwise `folded` (an ordinary constant from a named helper — a prefix, a concat, a hex-decode of a literal). This came straight from the first real run, where a WorkManager log-tag helper and a Play-services cert hex-decoder would otherwise have shown up as 66 "decoded strings".
5. **Skip report.** Every site ends `decoded` (`decrypted`/`folded`, with the tier) or `skipped` with one reason: `UNREACHABLE`, `UNKNOWN_ARGUMENT`,
   `ANALYSIS_INCOMPLETE`, `ANALYSIS_FAILED`, `NON_CONSTANT_ARGUMENTS`, `STEP_OR_TIME_LIMIT`, `EXCEPTION`, `HOST_DENIED`, `HOST_THREW`,
   `HOST_UNKNOWN_INPUT`, `MISSING_STUB`, `UNKNOWN_RESULT`, `UNSUPPORTED_RESULT`. Helper-run reasons come from the engine's own
   `Diagnostic`s (so the detail names the host call that was refused), not from guessing.

Not reported on purpose: sinks *inside* a decryptor (its own plumbing), and helpers below the threshold (listed under `helpers` only).

## Use

```bash
scripts/deploy.sh                 # build + test + install as the only string-sites jar (needs the engine in ~/.m2: EMU_VERSION)
scripts/smoke.sh                  # real jadx CLI on two synthetic apps; must print SMOKE: PASS
scripts/run-on.sh app.apk [Lcom/example/]   # analyse a real app; prints the summary + the skipped sites, no decompile
```
The extension does nothing unless `~/.config/jadx/plugins-data/jadx-emu/data/string-sites/string-sites.properties` has `autoAnalyze=true`.
Other keys: `helperMinConstantSites` (3), `allowNoArgHelpers` (false), `maxSteps` (2000000), `maxMillis` (5000), `maxDepth` (32), `classPrefix`.
Output: `strings-report.json` (every site: method, offset, kind, status, tier/value or reason/detail; plus helpers and a summary) and `.txt`.

**Reading the text report:** the "skipped, by reason" block is the work list. `UNKNOWN_ARGUMENT` with no helper nearby = an inline or
branchy decoder (tier 3: run the whole method with `Hooks.after` on the sinks). `NON_CONSTANT_ARGUMENTS` = a decryptor fed from a field or
parameter. `HOST_DENIED` / `MISSING_STUB` name the call to allow or stub.

## What was verified, and how

| claim | evidence |
|---|---|
| decodes helper loops, straight-line code, char[] and byte[] sinks, `intern()` | 19 unit tests on a synthetic app (smali assembled and run through the real interpreter) |
| never links a sink across a branch join | test + mutation (removing the guard makes it fail) |
| reasons are real: refused host call, step limit, non-constant, unreachable, unknown | tests assert the reason *and* that the detail names the cause |
| exact values: non-ASCII, emoji pairs, NUL, U+2028, `$`/`_` in class names, 4-6k-char strings | 40 randomized plaintexts, in-process and through the real jadx CLI; the report parsed by Python's strict `json.load`: 40/40 identical |
| tier 1 agrees with a concrete run | test compares each tier-1 value with executing the method |
| the tests can fail | 6 mutations (link, join guard, reason mapping, threshold boundary, decryptor-internal suppression, JSON surrogate escaping): each makes the suite fail |
| works inside jadx | jadx 1.5.6 CLI + the engine plugin jar + this jar: `extension 'string-sites' initialized, 1 active`, report written; also with `--no-src --no-res` |
| **decoded a real DexGuard app** | **OnePay 5.98.2** (banking, in-dex DexGuard string encryption): tier 2 ran the keyed XOR decryptor and recovered real secrets — `AES/GCM/NoPadding`, `RSA/ECB/OAEPPadding`, `SHA1PRNG`, image-format magics — 71 decrypted across two dex, 0 false positives. The first real end-to-end validation of the decode path. Findings doc in the chat. |
| native string table detected, not mislabelled | Brawl Stars' 548-site `elsm/K` table decryptor resolves to a `native` method; the report says `NATIVE_METHOD` ("the decryption is in a .so"), not a misleading `MISSING_STUB`. A unit test pins this. |
| decrypted≠folded split | fixtures model three real false-positive shapes — a named prefixer (WorkManager `WM-`+tag), a library `String→byte[]` copy (BouncyCastle `toByteArray`), and a native table decryptor — and assert `folded`/`folded`/`NATIVE_METHOD`; the real XOR-loop decryptor still asserts `decrypted`. Verified on myRaiffeisen, Brawl Stars and myAIS. |

Verified against the `upstream-hooks` engine (ba65619 + `Hooks.after`, 83/83 engine tests). It uses none of `after`.

## What is NOT verified - read before trusting it on a real app

- **Seven real apps have now been run.** Six are native-protected or non-Dalvik (myRaiffeisen/Promon, Brawl Stars/native-ECC+Quago, TiviMate/DexProtector-runtime, AZTV & VEXO/plaintext shells, myAIS/Appdome+Flutter) — 0 genuine decryptions, no surviving false positives after three heuristic tightenings these apps drove. **The seventh, OnePay (banking), uses genuine in-dex DexGuard string encryption, and tier 2 decoded it** — crypto config and format constants recovered by running the real keyed XOR decryptor in the emulator. So the scope is now proven both ways: the decode path works on a real DexGuard target, and the native protectors are where it (correctly) does not reach. The earlier internal note about three apps (myRaiffeisen / Promon SHIELD; Brawl Stars / native ECC + Quago; TiviMate / DexProtector), static only, up to 978 sites. On TiviMate tier 2 **did** reach real Dalvik decryptor helpers (skips were `STEP_OR_TIME_LIMIT`, not `NATIVE_METHOD`), but DexProtector's strings there are a **runtime-populated lookup cache** filled natively from `assets.dat` — so a stateless per-call run returns nothing, correctly. See the TiviMate findings doc.
- **Known boundary (not a bug):** tier 2 assumes a decryptor is a pure function of its arguments. It cannot recover strings that come from a field/map populated by an init the analyzer hasn't run, or decrypted natively. A warm-VM tier (run the class init once, persist the VM) is the natural next step for the cache case — tracked, not built.
- Across all real samples so far, the decode path has **not yet fully decoded a real encrypted string** end-to-end, because every sample protects strings natively or via a native-filled cache. A pure **Dalvik** string decryptor (older DexGuard, or DexProtector in in-dex mode) remains the sample that matters most.
- **Gradle was not run.** Built and tested with `kotlinc` + JUnit; `build.gradle.kts` is the unpacker 2.0.0 build file (which did build under Gradle) with names changed.
- **Tier 1 trusts `Dataflow`'s heap model.** It was right on the cases tested (straight-line mutation of a char[]; unknown for a loop). A decoder that mutates the array through a called method, `System.arraycopy`, or an aliased field is untested. If you see a wrong tier-1 string, that is where to look.
- **Tier 2 executes app code** (constant arguments, `ExecLimits`, the engine's host policy). It is bounded, not sandboxed beyond that. Helpers keep static state across their calls, in call-site order, which may differ from a real run.
- **Performance is unmeasured at scale.** One `Dataflow` per method that has a sink or candidate call, and one VM per helper. It is single-threaded (it runs once in the decompile pass's `init`).
- **Tier 2 helper sites with `byte[]` results** are decoded as UTF-8 in the helper-call entry (the sink's charset is applied only to the linked sink).
- Decoded values are not yet written back into the decompiled code (item 7).

## Source map

`Sinks.kt` sink detection + the straight-line def-use walk · `StringSiteAnalyzer.kt` tiers 1-2 · `AnalysisReport.kt` summary/text/JSON ·
`Model.kt` sites, outcomes, reasons, settings · `StringSitesExtension.kt` jadx shell · `src/test` fixtures and tests.
