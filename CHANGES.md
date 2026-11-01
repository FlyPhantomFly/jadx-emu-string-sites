# Changes

## 0.3.0

Recovered strings are now sorted into decrypted, trivial and folded by how they were recovered, instead of a single "decoded" count that mixed real decryptor output with library constants.

A helper counts as a decryptor when its name looks obfuscated and it either does arithmetic on its input or calls another obfuscated helper. Helpers with ordinary names, and short-named ones that only call framework methods, are folded. That keeps things like WorkManager's `"WM-"` tag prefixer and Microblink's `"mb."` key builder out of the decrypted list. Both only build a string from constants.

The decision doesn't depend on being able to see inside the inner decryptor, so the same string gets the same label whichever dex files are loaded.

Tried on two real DexGuard apps (OnePay and VFS Global).

## 0.2.0

Now built against `io.github.flyphantomfly:jadx-emu` 0.2.0 and released under GPLv2.

## 0.1.0

First version: dataflow pass, helpers found by usage, and the skipped-site report.
