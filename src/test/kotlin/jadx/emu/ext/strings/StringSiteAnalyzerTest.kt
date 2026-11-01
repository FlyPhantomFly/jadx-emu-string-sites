package jadx.emu.ext.strings

import jadx.plugins.emu.exec.ExecLimits
import jadx.emu.ext.strings.Confidence
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class StringSiteAnalyzerTest {

    private val source: DexInputSource = EmuTestDex.assemble(Fixtures.decHelpers, Fixtures.usage)

    private fun methods(src: DexInputSource = source): List<DexMethod> = src.allMethods().filter { it.declClass.startsWith("Lfx/") }

    private fun analyze(settings: AnalyzerSettings = AnalyzerSettings()): AnalysisReport =
        StringSiteAnalyzer.forSource(source, settings).analyze(methods())

    private val report: AnalysisReport by lazy { analyze() }

    private fun results(method: String, kind: SiteKind? = null): List<SiteResult> =
        report.results.filter { it.site.method == "Lfx/Use;->$method" && (kind == null || it.site.kind == kind) }

    private fun decoded(r: SiteResult) = r.outcome as Decoded
    private fun skipped(r: SiteResult) = r.outcome as Skipped

    // ---- tier 2: helpers found by usage ----------------------------------------------------------------------------------------------

    @Test fun aLoopDecryptorIsFoundByUsageAndItsSinkIsLinked() {
        val helper = report.helpers.single { it.signature == "Lfx/Dec;->dec(Ljava/lang/String;I)Ljava/lang/String;" }
        assertTrue(helper.decryptor)
        assertEquals(helper.sites - 1, helper.constantSites, "only dynamic() passes a non-constant")   // 4 constant sites + dynamic()

        val call = results("viaHelperIntern()Ljava/lang/String;", SiteKind.HELPER_CALL).single()
        assertEquals(Fixtures.PLAIN_A, decoded(call).value)
        assertEquals(2, decoded(call).tier)

        val sink = results("viaHelperIntern()Ljava/lang/String;", SiteKind.INTERN).single()
        assertEquals(Fixtures.PLAIN_A, decoded(sink).value, "the intern() sink is fed by the helper call in the same block")
        assertEquals(2, decoded(sink).tier)
        assertTrue(decoded(sink).via!!.startsWith("Lfx/Dec;->dec("))
    }

    @Test fun aHelperThatReturnsAStringWithNoSinkStillYieldsTheString() {
        assertEquals(Fixtures.PLAIN_B, decoded(results("helperDirect()Ljava/lang/String;", SiteKind.HELPER_CALL).single()).value)
    }

    @Test fun aHelperReturningCharsFeedsNewStringAndValueOf() {
        assertEquals("chars-one", decoded(results("chars1()Ljava/lang/String;", SiteKind.NEW_STRING_CHARS).single()).value)
        assertEquals("chars-two", decoded(results("chars2()Ljava/lang/String;", SiteKind.NEW_STRING_CHARS).single()).value)
        assertEquals("chars-three", decoded(results("chars3()Ljava/lang/String;", SiteKind.VALUEOF_CHARS).single()).value)
    }

    @Test fun aHelperReturningBytesFeedsNewStringWithTheSinksCharset() {
        val sink = results("bytes1()Ljava/lang/String;", SiteKind.NEW_STRING_BYTES).single()
        assertEquals(Fixtures.DEC_BYTES_3, decoded(sink).value)
        assertEquals(2, decoded(sink).tier)
    }

    @Test fun aSinkIsNotLinkedAcrossAJoin() {
        // both branches call the decryptor with constants, so both CALL sites decode ...
        val calls = results("branchy(Z)Ljava/lang/String;", SiteKind.HELPER_CALL)
        assertEquals(setOf("left", "right"), calls.map { decoded(it).value }.toSet())
        // ... but which one feeds intern() depends on the path, so the sink must NOT claim either
        val sink = results("branchy(Z)Ljava/lang/String;", SiteKind.INTERN).single()
        assertEquals(Reason.UNKNOWN_ARGUMENT, skipped(sink).reason)
    }

    @Test fun aDecryptorCalledWithANonConstantIsReportedNotRun() {
        val s = skipped(results("dynamic(Ljava/lang/String;)Ljava/lang/String;", SiteKind.HELPER_CALL).single())
        assertEquals(Reason.NON_CONSTANT_ARGUMENTS, s.reason)
    }

    @Test fun anOrdinaryHelperBelowTheThresholdIsNotADecryptorAndNotReported() {
        val rare = report.helpers.single { it.signature.startsWith("Lfx/Dec;->rare(") }
        assertFalse(rare.decryptor)
        assertEquals(2, rare.constantSites)
        assertTrue(report.results.none { it.site.label.startsWith("Lfx/Dec;->rare(") })
    }

    @Test fun theDecryptorsOwnSinksAreNotReportedAsAppStrings() {
        assertTrue(report.results.none { it.site.method.startsWith("Lfx/Dec;->dec(") })
        assertTrue(report.results.none { it.site.method.startsWith("Lfx/Dec;->decChars(") })
    }

    @Test fun aLoopDecryptorsOutputIsMarkedDecrypted() {
        val r = results("viaHelperIntern()Ljava/lang/String;", SiteKind.HELPER_CALL).single()
        assertEquals(Confidence.DECRYPTED, decoded(r).confidence)   // Lfx/Dec;->dec does xor + aput
    }

    @Test fun aNamedPrefixHelperIsFoldedNotDecrypted() {
        // Lfx/Dec;->tagWithPrefix: named owner, no byte/char arithmetic, just concat -> FOLDED (the WorkManager false positive)
        val helper = report.helpers.single { it.signature.endsWith("tagWithPrefix(Ljava/lang/String;)Ljava/lang/String;") }
        assertTrue(helper.decryptor, "still a usage match (3 constant sites)")
        assertFalse(helper.doesArithmetic)
        val tagged = results("tagA()Ljava/lang/String;", SiteKind.HELPER_CALL).single()
        assertEquals("WM-Data", decoded(tagged).value)
        assertEquals(Confidence.FOLDED, decoded(tagged).confidence)
        assertTrue(report.decrypted.none { it.site.method.startsWith("Lfx/Use;->tag") })
        assertTrue(report.summary().let { true } && report.folded.any { it.site.method == "Lfx/Use;->tagA()Ljava/lang/String;" })
    }

    @Test fun reportTextSeparatesDecryptedFromFolded() {
        val t = report.toText()
        assertTrue(Regex("\\d+ decrypted, \\d+ trivial, \\d+ folded").containsMatchIn(t), t.lineSequence().first())
        assertTrue(t.contains("decrypted strings (grouped by decryptor):"))
        // trivial values must NOT appear inline in the text report (JSON only)
        val trivialValue = report.trivial.map { (it.outcome as Decoded).value }.firstOrNull()
        if (trivialValue != null && trivialValue.length == 1) assertFalse(t.contains("  via ") && t.substringAfter("grouped by decryptor").contains("\"$trivialValue\""))
    }

    // ---- tier 1: dataflow only ---------------------------------------------------------------------------------------------------------

    @Test fun straightLineDecodersAreReadWithoutRunningAnything() {
        val v = results("straightValueOf()Ljava/lang/String;", SiteKind.VALUEOF_CHARS).single()
        assertEquals("ki", decoded(v).value); assertEquals(1, decoded(v).tier)
        val n = results("straightNewAndIntern()Ljava/lang/String;", SiteKind.NEW_STRING_CHARS).single()
        assertEquals("ki", decoded(n).value); assertEquals(1, decoded(n).tier)
    }

    @Test fun tierOneAgreesWithAConcreteRunOfTheSameMethod() {
        // guards against dataflow returning a plausible-looking but stale array
        val ctxSource = source
        for (name in listOf("straightValueOf()Ljava/lang/String;", "straightNewAndIntern()Ljava/lang/String;")) {
            val m = ctxSource.method("Lfx/Use;", name)!!
            val truth = StringSiteAnalyzer.forSource(ctxSource).let { _ ->
                val ec = jadx.plugins.emu.exec.EngineContext(ctxSource, AnalyzerSettings().limits, jadx.plugins.emu.exec.HostBoundary(), jadx.plugins.emu.api.Hooks())
                ec.newVm(AnalyzerSettings().limits).invoke(m)
            }
            for (r in results(name).filter { it.outcome is Decoded && decoded(it).tier == 1 }) assertEquals(truth, decoded(r).value)
        }
    }

    @Test fun anInlineLoopIsSkippedAsAnUnknownArgumentNeverGuessed() {
        val s = results("inlineLoop()Ljava/lang/String;", SiteKind.NEW_STRING_CHARS).single()
        assertEquals(Reason.UNKNOWN_ARGUMENT, skipped(s).reason)
        assertTrue(skipped(s).detail.contains("unknown"), skipped(s).detail)
    }

    @Test fun deadCodeIsReportedUnreachable() {
        assertEquals(Reason.UNREACHABLE, skipped(results("deadCode()Ljava/lang/String;", SiteKind.INTERN).single()).reason)
    }

    @Test fun aCharArrayThatNeverReachesAStringCreatesNoSite() {
        assertTrue(results("stray()I").isEmpty())
    }

    // ---- the skip report: why a helper run failed -------------------------------------------------------------------------------------

    @Test fun aHelperNeedingARefusedHostCallIsSkippedWithTheHostReason() {
        val rs = report.results.filter { it.site.label.startsWith("Lfx/Dec;->bad(") }
        assertEquals(3, rs.size)
        for (r in rs) {
            assertEquals(Reason.HOST_DENIED, skipped(r).reason, skipped(r).detail)
            assertTrue(skipped(r).detail.contains("Executors"), skipped(r).detail)
        }
    }

    @Test fun aShortNamedHelperCallingOnlyFrameworkFolds() {
        // androidx/work/x->i: short name (synthetic by name) but only calls framework StringBuilder - the WM- prefixer.
        // Must FOLD. This is the WorkManager false positive; without the framework-call exclusion it would wrongly decrypt.
        val iw = report.helpers.single { it.signature.endsWith(";->iw(Ljava/lang/String;)Ljava/lang/String;") }
        assertFalse(iw.doesArithmetic, "iw does no arithmetic")
        val r = results("wmA()Ljava/lang/String;", SiteKind.HELPER_CALL).single()
        assertEquals("WM-Worker", decoded(r).value)
        assertEquals(Confidence.FOLDED, decoded(r).confidence, "calls only framework -> folded, not a decoder")
    }

    @Test fun aDecryptorThatDelegatesToASyntheticCodecDecryptsEvenWithoutOwnArithmetic() {
        // The real idrnd i.a / microblink b.j shape: q does NO arithmetic in its own body, but it CALLS a synthetic app
        // codec (z0K). It must be DECRYPTED - deterministically, whether or not z0K's body resolves - because calling an
        // obfuscated app helper is the decoder signal. (A library helper like androidx work x.i calls only framework
        // StringBuilder, so it does NOT get this and folds - the WM- fix.)
        val r = results("lookupA()Ljava/lang/String;", SiteKind.HELPER_CALL).single()
        assertEquals(Confidence.DECRYPTED, decoded(r).confidence, "delegates to a synthetic codec -> decrypted")
    }

    @Test fun aDelegatingDecryptorIsDecryptedViaOneLevel() {
        // ix does no arithmetic itself; it calls inner z0K which XORs. One-level delegation must classify ix as a decryptor.
        val ix = report.helpers.single { it.signature.endsWith(";->ix(Ljava/lang/String;)Ljava/lang/String;") }
        assertTrue(ix.decryptor, "ix is a usage-flagged decryptor")
        val rs = results("delegA()Ljava/lang/String;", SiteKind.HELPER_CALL).filter { (it.outcome as? Decoded)?.value == "deleg-one" }
        assertTrue(rs.isNotEmpty(), "delegA should decode deleg-one")
        assertEquals(Confidence.DECRYPTED, decoded(rs.first()).confidence)
    }

    @Test fun aLibraryNamedHelperThatDoesArithmeticStillFolds() {
        // decodeConfigValue: camelCase wordlike name, real XOR body -> the name test demotes it; NOT a decryptor.
        val h = report.helpers.single { it.signature.endsWith("decodeConfigValue(Ljava/lang/String;I)Ljava/lang/String;") }
        assertTrue(h.doesArithmetic, "it genuinely does xor arithmetic")
        val r = results("libArithA()Ljava/lang/String;", SiteKind.HELPER_CALL).single()
        assertEquals("alpha-setting", decoded(r).value)
        assertEquals(Confidence.FOLDED, decoded(r).confidence)
    }

    @Test fun aDecryptorReturningASingleCharIsTrivialNotDecrypted() {
        // Lfx/Dec;->dec is a real decryptor, but a one-char output is a token, not a secret -> TRIVIAL.
        for (name in listOf("trivialA", "trivialB", "trivialC")) {
            val r = results("$name()Ljava/lang/String;", SiteKind.HELPER_CALL).single()
            assertEquals(Confidence.TRIVIAL, decoded(r).confidence, "${decoded(r).value} should be trivial")
        }
    }

    @Test fun realDecryptorWithRealStringIsStillDecrypted() {
        // the multi-char secret must survive the trivial gate
        assertEquals(Confidence.DECRYPTED, decoded(results("viaHelperIntern()Ljava/lang/String;", SiteKind.HELPER_CALL).single()).confidence)
    }

    @Test fun aCharToByteCopyHelperIsFoldedNotDecrypted() {
        // org.bouncycastle.util.Strings.toByteArray: aput + int-to-byte + a +1 counter, NO transform -> FOLDED, not DECRYPTED.
        val h = report.helpers.single { it.signature.endsWith("toByteArray(Ljava/lang/String;)[B") }
        assertFalse(h.doesArithmetic, "a char->byte copy with a loop counter must not count as decode arithmetic")
        val r = results("tbA()Ljava/lang/String;", SiteKind.NEW_STRING_BYTES).single()
        assertEquals("alpha", decoded(r).value)
        assertEquals(Confidence.FOLDED, decoded(r).confidence)
    }

    @Test fun aNativeDecryptorIsReportedAsNativeNotMissingStub() {
        // the Brawl Stars case: a native (I)String table decryptor, called from constant sites -> NATIVE_METHOD, not MISSING_STUB
        val rs = report.results.filter { it.site.label.contains("nativeDec(") }
        assertEquals(3, rs.size)
        for (r in rs) {
            assertEquals(Reason.NATIVE_METHOD, skipped(r).reason, skipped(r).detail)
            assertTrue(skipped(r).detail.contains("native"), skipped(r).detail)
        }
    }

    @Test fun aNeverEndingHelperIsSkippedWithTheLimitReason() {
        val tight = AnalyzerSettings(limits = ExecLimits(maxSteps = 20_000, maxMillis = 3_000, maxDepth = 16))
        val rs = analyze(tight).results.filter { it.site.label.startsWith("Lfx/Dec;->spin(") }
        assertEquals(3, rs.size)
        for (r in rs) assertEquals(Reason.STEP_OR_TIME_LIMIT, skipped(r).reason, skipped(r).detail)
    }

    // ---- values: nothing is lost on the way out ---------------------------------------------------------------------------------------

    @Test fun randomPlaintextsRoundTripThroughADecryptor() {
        val rnd = Random(20261018)
        val alphabets = listOf(
            { r: Random -> (0x20 + r.nextInt(0x5f)).toChar().toString() },                       // ASCII
            { r: Random -> (0xa0 + r.nextInt(0x5f)).toChar().toString() },                       // Latin-1
            { r: Random -> (0x4e00 + r.nextInt(0x500)).toChar().toString() },                    // CJK
            { r: Random -> String(Character.toChars(0x1f600 + r.nextInt(0x40))) },               // emoji (surrogate pair)
            { r: Random -> listOf("$", "_", "\"", "\\", "\n", "\u0000", "\u2028")[r.nextInt(7)] }, // awkward characters
        )
        val plains = (0 until 40).map { i ->
            val len = when { i == 0 -> 0; i < 5 -> 4000 + rnd.nextInt(2000); else -> 1 + rnd.nextInt(60) }
            buildString { repeat(len) { append(alphabets[rnd.nextInt(alphabets.size)](rnd)) } }
        }
        val keys = plains.map { 1 + rnd.nextInt(0x7e) }
        val body = plains.indices.joinToString("\n") { i ->
            ".method public static m$i()Ljava/lang/String;\n    .registers 3\n    const-string v0, ${Fixtures.lit(Fixtures.xor(plains[i], keys[i]))}\n" +
                "    const/16 v1, ${keys[i]}\n    invoke-static {v0, v1}, Lfx/Dec;->dec(Ljava/lang/String;I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0\n.end method"
        }
        // a class whose name has '_' and '$' in it, as real protectors produce
        val src = EmuTestDex.assemble(Fixtures.decHelpers, ".class public Lfx/G_1\$A_b;\n.super Ljava/lang/Object;\n$body\n")
        val rep = StringSiteAnalyzer.forSource(src).analyze(methods(src))
        val byMethod = rep.results.filter { it.site.kind == SiteKind.HELPER_CALL }.associateBy { it.site.method }
        assertEquals(plains.size, byMethod.size)
        for (i in plains.indices) {
            val r = byMethod["Lfx/G_1\$A_b;->m$i()Ljava/lang/String;"]
            assertNotNull(r, "site m$i missing")
            assertEquals(plains[i], decoded(r!!).value, "plaintext $i differs")
        }
    }

    // ---- report ----------------------------------------------------------------------------------------------------------------------

    /** What a file on disk would hold: encode to UTF-8 and decode again. An unescaped lone surrogate does not survive this. */
    private fun viaUtf8(json: String): String = String(json.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

    @Test fun summaryCountsAddUp() {
        val s = report.summary()
        assertEquals(report.results.size, s.found)
        assertEquals(s.found, s.decoded + s.skipped)
        assertEquals(s.decoded, s.decodedByTier.values.sum())
        assertEquals(s.skipped, s.skipReasons.values.sum())
        assertEquals(s.found, s.byKind.values.sumOf { it.first + it.second })
        assertTrue(report.toText().contains("skipped sites:"))
    }

    @Test fun jsonSummaryHasAllThreeTiersAndTheySum() {
        val summary = (MiniJson.parse(report.toJson()) as Map<*, *>)["summary"] as Map<*, *>
        val d = (summary["decrypted"] as Double).toInt()
        val t = (summary["trivial"] as Double).toInt()
        val f = (summary["folded"] as Double).toInt()
        assertEquals((summary["decoded"] as Double).toInt(), d + t + f, "decrypted+trivial+folded must equal decoded")
        assertEquals(report.decrypted.size, d); assertEquals(report.trivial.size, t); assertEquals(report.folded.size, f)
    }

    @Test fun jsonIsValidAndRoundTripsEveryValue() {
        val parsed = MiniJson.parse(viaUtf8(report.toJson())) as Map<*, *>
        val sites = parsed["sites"] as List<*>
        assertEquals(report.results.size, sites.size)
        for ((i, r) in report.results.withIndex()) {
            val o = sites[i] as Map<*, *>
            assertEquals(r.site.method, o["method"]); assertEquals(r.site.offset.toDouble(), o["offset"])
            when (val oc = r.outcome) {
                is Decoded -> { assertEquals("decoded", o["status"]); assertEquals(oc.value, o["value"]) }
                is Skipped -> { assertEquals("skipped", o["status"]); assertEquals(oc.reason.name, o["reason"]) }
            }
        }
    }

    @Test fun jsonEscapesControlCharactersAndLoneSurrogates() {
        val nasty = "q\"b\\s\n\r\t\u0001\u2028 lone-high:\uD83D end \uDE00 lone-low, pair:\uD83D\uDE00 é日"
        val rep = AnalysisReport(
            listOf(SiteResult(Site("Lx;->m()V", 3, SiteKind.INTERN, "String.intern()"), Decoded(nasty, 1))),
            emptyList(), 1,
        )
        val o = ((MiniJson.parse(viaUtf8(rep.toJson())) as Map<*, *>)["sites"] as List<*>).single() as Map<*, *>
        assertEquals(nasty, o["value"])
    }
}
