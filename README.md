# jadx-emu string-sites

An extension for [jadx-emu](https://github.com/FlyPhantomFly/jadx-emu) that finds the strings an app decrypts at runtime and tells you which ones it couldn't recover, and why.

It doesn't change the decompiled code. It runs once, before decompiling, and writes a report.

## How it works

It looks for places where the app builds a string from a char[] or byte[] (`new String(...)`, `String.valueOf(char[])`, `intern()`). For each one it first tries to read the value with a dataflow pass. If that isn't enough, it finds helper methods that are called with constant arguments from several places, runs them in the emulator and uses what they return. Anything it can't recover is listed with a reason.

## Install

You need jadx with the jadx-emu 0.2.0 plugin installed, and the engine in `~/.m2` so the build can find it.

```
./scripts/deploy.sh
```

That runs the tests, builds the jar and puts it in the jadx-emu extensions directory. Afterwards `./scripts/smoke.sh` runs it through jadx on two small test apps and should end with `SMOKE: PASS`.

## Use

It does nothing until you turn it on:

```
echo 'autoAnalyze=true' > ~/.config/jadx/plugins-data/jadx-emu/data/string-sites/string-sites.properties
```

Then run jadx on the whole APK, not on single dex files. A decryptor often calls a helper that lives in another dex, and it can only follow the call if both are loaded.

```
JAVA_OPTS="-Xmx8g" jadx --no-src --no-res -d /tmp/out app.apk
```

`scripts/run-on.sh app.apk` does the same and prints the start of the report. Add a class prefix like `Lcom/example/` to look at one package only.

The report ends up in `~/.config/jadx/plugins-data/jadx-emu/data/string-sites/` as `strings-report.txt` and `strings-report.json`.

## Reading the report

The first line gives the counts: decrypted, trivial, folded and skipped. The text report then lists the decrypted strings grouped by the decryptor that produced them, followed by the skipped sites. The JSON has every site.

- decrypted: recovered by running a decryptor with an obfuscated name. This describes how the string was recovered, not whether it's a secret. On a DexGuard app most strings are stored this way.
- folded: a constant from an ordinary library helper (`Hex.decode`, a `"WM-"` log tag prefixer) or one that dataflow resolved directly.
- trivial: recovered like a decrypted string, but the value is a single character or all control characters. These are counted, and only listed in the JSON.
- skipped: not recovered. Each one has a reason. `NATIVE_METHOD` means the decryption happens in a .so. `HOST_DENIED` means the emulator refused a call the decryptor needed. `NON_CONSTANT_ARGUMENTS` means the decryptor is called with a value that's only known at runtime. The rest are named in the report.

## Settings

All optional, in the same properties file.

| key | default | what it does |
|---|---|---|
| autoAnalyze | false | run the analysis |
| classPrefix | all classes | only look at classes starting with this, e.g. `Lcom/example/` |
| helperMinConstantSites | 3 | how many constant-argument calls make a method count as a decryptor |
| allowNoArgHelpers | false | let methods with no arguments count as decryptors |
| maxSteps | 2000000 | step limit for each emulated run |
| maxMillis | 5000 | time limit in ms for each emulated run |
| maxDepth | 32 | call depth limit for each emulated run |

## Limits

It only handles Dalvik code. If an app decrypts in native code you'll see `NATIVE_METHOD` and nothing recovered.

It runs app code in the emulator, under the step and time limits above and the engine's host policy.

The decrypted/folded split is a judgement based on the helper's name and what it calls, so look at the odd one yourself.

Recovered values aren't written back into the decompiled output. The [jadx-emu-string-deobfuscator](https://github.com/FlyPhantomFly/jadx-emu-string-deobfuscator) does that.

## License

GPLv2, because it links the GPLv2 jadx-emu engine. See `LICENSE` and `NOTICE`.
