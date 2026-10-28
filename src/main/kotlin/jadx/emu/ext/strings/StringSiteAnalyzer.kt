package jadx.emu.ext.strings

import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.Diagnostic
import jadx.plugins.emu.exec.DiagnosticSink
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.graph.Dataflow
import jadx.plugins.emu.exec.graph.DataflowResult
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.UnknownVal
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Recovers decrypted strings from an app without matching any decryptor's shape.
 *
 * Tier 1 reads the argument of every string sink (intern, `new String(char[])`, `valueOf(char[])`, `new String(byte[], ..)`)
 * from [Dataflow]; that works whenever the decoder is straight-line code.
 * Tier 2 finds helpers by how they are used (static, returns String/char[]/byte[], called from several places with only constant
 * arguments) and runs each such call with its constants; that covers loop-based decoders, which dataflow cannot evaluate.
 * Every site that is not decoded is reported with the reason.
 */
class StringSiteAnalyzer(
    private val source: MethodSource,
    private val engine: EngineContext,
    private val settings: AnalyzerSettings = AnalyzerSettings(),
) {
    private class Call(val index: Int, val insn: DalvikInsn, val ref: MethodRef, val args: List<Any?>?, val reachable: Boolean)

    private class SinkScan(val sink: Sink, val value: Any?, val charset: Any?, val tier1: Outcome)

    private class Scan(val method: DexMethod, val targets: Set<Int>, val sinks: List<SinkScan>, val calls: List<Call>, val failure: Skipped?)

    private sealed interface HelperValue {
        class Ok(val raw: Any, val text: String, val note: String?) : HelperValue
        class Fail(val reason: Reason, val detail: String) : HelperValue
    }

    private class Window : DiagnosticSink {
        val events = ArrayList<Diagnostic>()
        override fun report(d: Diagnostic) { events.add(d) }
    }

    private class HelperRun(val method: DexMethod, val vm: Vm, val window: Window) {
        val cache = HashMap<List<Any?>, HelperValue>()
    }

    fun analyze(methods: Collection<DexMethod>): AnalysisReport {
        // ---- phase A: dataflow once per method that has a sink or a candidate helper call --------------------------------------
        val scans = ArrayList<Scan>()
        for (m in methods) {
            val sinks = Sinks.find(m)
            val calls = candidateCalls(m)
            if (sinks.isEmpty() && calls.isEmpty()) continue
            scans.add(scan(m, sinks, calls))
        }

        // ---- phase B: which helpers are decryptors? -----------------------------------------------------------------------------
        class Usage(val ref: MethodRef) { var sites = 0; var constant = 0 }
        val usage = LinkedHashMap<String, Usage>()
        for (s in scans) for (c in s.calls) {
            val u = usage.getOrPut(key(c.ref)) { Usage(c.ref) }
            u.sites++
            if (c.args != null) u.constant++
        }
        val decryptors = usage.filterValues { it.constant >= settings.helperMinConstantSites }.keys
        val arithOf = HashMap<String, Boolean>()
        for (k in decryptors) usage[k]?.ref?.let { r -> source.method(r.declClass, r.shortId)?.let { arithOf[k] = Sinks.doesDecodingArithmetic(it) } }
        val helpers = usage.map { (k, u) -> HelperInfo(sig(u.ref), u.sites, u.constant, k in decryptors, arithOf[k] ?: false) }

        // ---- phase C: tier 2 - run each decryptor helper with the constants of each call site -----------------------------------
        val runs = HashMap<String, HelperRun?>()
        val callOutcome = HashMap<Pair<String, Int>, Outcome>()   // (method signature, call index) -> outcome of the helper-call site
        val callValue = HashMap<Pair<String, Int>, HelperValue.Ok>()
        for (s in scans) for (c in s.calls) {
            if (key(c.ref) !in decryptors) continue
            val id = sig(s.method.ref) to c.index
            val label = sig(c.ref)
            when {
                s.failure != null -> callOutcome[id] = s.failure
                !c.reachable -> callOutcome[id] = Skipped(Reason.UNREACHABLE, "dataflow found no path to this call")
                c.args == null -> callOutcome[id] = Skipped(Reason.NON_CONSTANT_ARGUMENTS, "arguments at this call are not all constants")
                c.ref.let { isNativeHelper(it) } -> callOutcome[id] = Skipped(Reason.NATIVE_METHOD, "${sig(c.ref)} is a native method; the decryption is in a .so, not in the dex")
                else -> {
                    val run = runs.getOrPut(key(c.ref)) { newRun(c.ref) } ?: continue
                    when (val v = runHelper(run, c.args)) {
                        is HelperValue.Ok -> { callValue[id] = v; callOutcome[id] = Decoded(v.text, 2, label, confidenceOf(c.ref, arithOf), v.note) }
                        is HelperValue.Fail -> callOutcome[id] = Skipped(v.reason, v.detail)
                    }
                }
            }
        }

        // ---- phase D: assemble; sinks that tier 1 could not read may be fed by a helper call in the same straight-line block ------
        val results = ArrayList<SiteResult>()
        for (s in scans) {
            val msig = sig(s.method.ref)
            if (key(s.method.ref) in decryptors) continue    // sinks inside a decryptor are its own plumbing, not app strings
            for (ss in s.sinks) {
                val site = Site(msig, ss.sink.insn.offset, ss.sink.kind, ss.sink.label)
                var outcome = ss.tier1
                if (outcome is Skipped && outcome.reason == Reason.UNKNOWN_ARGUMENT) {
                    val callIdx = Sinks.feedingStaticCall(s.method, ss.sink.index, ss.sink.argReg, s.targets)
                    if (callIdx != null) {
                        val fed = callValue[msig to callIdx]
                        val ci = s.method.insns[callIdx]
                        if (fed != null) fromHelper(ss, fed, ci.ref as MethodRef, ci.offset, arithOf)?.let { outcome = it }
                    }
                }
                results.add(SiteResult(site, outcome))
            }
            for (c in s.calls) {
                val o = callOutcome[msig to c.index] ?: continue
                results.add(SiteResult(Site(msig, c.insn.offset, SiteKind.HELPER_CALL, sig(c.ref)), o))
            }
        }
        results.sortWith(compareBy({ it.site.method }, { it.site.offset }))
        return AnalysisReport(results, helpers.sortedBy { it.signature }, scans.size)
    }

    // ---------------------------------------------------------------------------------------------------------------------------

    private fun key(r: MethodRef) = r.declClass + "->" + r.shortId
    private fun sig(r: MethodRef) = key(r)

    private fun known(v: Any?) = v != null && v !is UnknownVal

    private fun describe(v: Any?): String = when (v) {
        null -> "null or unreachable"
        is UnknownVal -> "unknown(" + (v.type ?: "?") + ")"
        else -> v.javaClass.simpleName
    }

    /** Static calls to app methods that return String/char[]/byte[] and take only primitives and Strings. */
    private fun candidateCalls(m: DexMethod): List<Int> {
        val out = ArrayList<Int>()
        for ((i, insn) in m.insns.withIndex()) {
            if (!insn.opcode.name.startsWith("INVOKE_STATIC")) continue
            val ref = insn.ref as? MethodRef ?: continue
            if (!isCandidate(ref)) continue
            out.add(i)
        }
        return out
    }

    private fun isCandidate(ref: MethodRef): Boolean {
        if (ref.returnType != "Ljava/lang/String;" && ref.returnType != "[C" && ref.returnType != "[B") return false
        if (ref.argTypes.isEmpty() && !settings.allowNoArgHelpers) return false
        if (!ref.argTypes.all { it in SCALARS }) return false
        return source.method(ref.declClass, ref.shortId) != null || source.isNative(ref.declClass, ref.shortId)
    }

    private fun scan(m: DexMethod, sinks: List<Sink>, candidateIdx: List<Int>): Scan {
        val targets = Sinks.jumpTargets(m)
        val res: DataflowResult = try {
            Dataflow(engine.newVm(settings.limits, diagnostics = emptyList())).analyze(m)
        } catch (t: Throwable) {
            val failure = Skipped(Reason.ANALYSIS_FAILED, t.toString())
            return Scan(
                m, targets,
                sinks.map { SinkScan(it, null, null, failure) },
                candidateIdx.map { Call(it, m.insns[it], m.insns[it].ref as MethodRef, null, false) },
                failure,
            )
        }
        val sinkScans = sinks.map { sink ->
            val reachable = res.reachable[sink.index]
            if (!reachable) {
                val why = if (res.complete) Skipped(Reason.UNREACHABLE, "dataflow found no path to this instruction")
                else Skipped(Reason.ANALYSIS_INCOMPLETE, "dataflow stopped early; reachability unknown")
                return@map SinkScan(sink, null, null, why)
            }
            val v = res.regIn(sink.index, sink.argReg)
            val cv = if (sink.charsetReg >= 0) res.regIn(sink.index, sink.charsetReg) else null
            SinkScan(sink, v, cv, decodeSink(sink, v, cv, res.complete))
        }
        val calls = candidateIdx.map { i ->
            val insn = m.insns[i]
            val ref = insn.ref as MethodRef
            Call(i, insn, ref, if (res.reachable[i]) constArgs(res, i, insn, ref) else null, res.reachable[i])
        }
        return Scan(m, targets, sinkScans, calls, null)
    }

    private fun constArgs(res: DataflowResult, idx: Int, insn: DalvikInsn, ref: MethodRef): List<Any?>? {
        val out = ArrayList<Any?>(ref.argTypes.size)
        var i = 0
        for (t in ref.argTypes) {
            if (i >= insn.regs.size) return null
            val v = res.regIn(idx, insn.regs[i])
            if (!known(v) || !(v is String || v is Number || v is Char || v is Boolean)) return null
            out.add(v)
            i += if (t == "J" || t == "D") 2 else 1
        }
        return out
    }

    // ---- tier 1 ------------------------------------------------------------------------------------------------------------------

    private fun decodeSink(sink: Sink, v: Any?, cv: Any?, complete: Boolean): Outcome {
        fun unknown(what: String): Outcome {
            val reason = if (!complete) Reason.ANALYSIS_INCOMPLETE else Reason.UNKNOWN_ARGUMENT
            return Skipped(reason, "v${sink.argReg} ($what) is ${describe(v)}" + if (!complete) "; dataflow stopped early" else "")
        }
        return when (sink.kind) {
            SiteKind.INTERN -> if (v is String) Decoded(v, 1) else unknown("string")
            SiteKind.NEW_STRING_CHARS, SiteKind.VALUEOF_CHARS -> if (v is CharArray) Decoded(String(v), 1) else unknown("char[]")
            SiteKind.NEW_STRING_BYTES -> {
                if (v !is ByteArray) return unknown("byte[]")
                val (cs, note) = charsetOf(sink, cv)
                if (cs == null) Skipped(Reason.UNKNOWN_ARGUMENT, "charset register v${sink.charsetReg} is ${describe(cv)}")
                else Decoded(String(v, cs), 1, note = note)
            }
            SiteKind.HELPER_CALL -> error("not a sink")
        }
    }

    private fun charsetOf(sink: Sink, cv: Any?): Pair<Charset?, String?> {
        if (sink.charsetReg < 0) return StandardCharsets.UTF_8 to "no charset given: decoded as UTF-8"
        return when (cv) {
            is Charset -> cv to null
            is String -> runCatching { Charset.forName(cv) }.getOrNull() to null
            else -> null to null
        }
    }

    // ---- tier 2 ------------------------------------------------------------------------------------------------------------------

    private fun newRun(ref: MethodRef): HelperRun? {
        val method = source.method(ref.declClass, ref.shortId) ?: return null
        val window = Window()
        return HelperRun(method, engine.newVm(settings.limits, androidEnvUnknown = false, diagnostics = listOf(window)), window)
    }

    /** True if [ref] is a native method with no Dalvik body - its result comes from a .so, which the engine cannot run here. */
    private fun isNativeHelper(ref: MethodRef): Boolean = source.isNative(ref.declClass, ref.shortId)

    private fun runHelper(run: HelperRun, args: List<Any?>): HelperValue = run.cache.getOrPut(args) {
        run.window.events.clear()
        val r = run.vm.invoke(run.method, args)
        val value: HelperValue = when {
            r is String -> HelperValue.Ok(r, r, null)
            r is CharArray -> HelperValue.Ok(r, String(r), null)
            r is ByteArray -> HelperValue.Ok(r, String(r, StandardCharsets.UTF_8), "bytes decoded as UTF-8 (the charset used at the sink is not applied here)")
            r == null || r is UnknownVal -> reasonOf(run.window.events)
            else -> HelperValue.Fail(Reason.UNSUPPORTED_RESULT, "helper returned ${r.javaClass.simpleName}")
        }
        if (value is HelperValue.Ok && value.text.length > settings.maxResultChars)
            HelperValue.Fail(Reason.UNSUPPORTED_RESULT, "result is ${value.text.length} chars, over the ${settings.maxResultChars} cap")
        else value
    }

    /** Turn what the engine reported while a helper ran into one reason, most explanatory first. */
    private fun reasonOf(events: List<Diagnostic>): HelperValue.Fail {
        fun first(vararg kinds: Diagnostic.Kind) = events.firstOrNull { it.kind in kinds }
        fun text(d: Diagnostic) = "${d.ref.declClass}->${d.ref.name}: ${d.detail}".trim().trimEnd(':').trim() +
            if (events.size > 1) " (+${events.size - 1} more engine event(s))" else ""
        first(Diagnostic.Kind.ABORTED)?.let { return HelperValue.Fail(Reason.STEP_OR_TIME_LIMIT, text(it)) }
        first(Diagnostic.Kind.UNCAUGHT)?.let { return HelperValue.Fail(Reason.EXCEPTION, text(it)) }
        first(Diagnostic.Kind.HOST_THREW)?.let { return HelperValue.Fail(Reason.HOST_THREW, text(it)) }
        first(Diagnostic.Kind.HOST_DENIED, Diagnostic.Kind.HOST_OUTSIDE_NAMESPACE, Diagnostic.Kind.HOST_DISABLED)?.let { return HelperValue.Fail(Reason.HOST_DENIED, text(it)) }
        first(Diagnostic.Kind.MISSING_STUB, Diagnostic.Kind.HOST_NOT_FOUND)?.let {
            val native = runCatching { source.isNative(it.ref.declClass, it.ref.shortId) }.getOrDefault(false)
            return if (native) HelperValue.Fail(Reason.NATIVE_METHOD, text(it) + " (native)")
            else HelperValue.Fail(Reason.MISSING_STUB, text(it))
        }
        first(Diagnostic.Kind.HOST_UNKNOWN_INPUT)?.let { return HelperValue.Fail(Reason.HOST_UNKNOWN_INPUT, text(it)) }
        return HelperValue.Fail(Reason.UNKNOWN_RESULT, "the helper returned an unknown value and the engine reported nothing")
    }

    /** A sink fed by a decoded helper call: apply the sink's own conversion to what the helper returned. */
    private fun fromHelper(ss: SinkScan, fed: HelperValue.Ok, ref: MethodRef, callOffset: Int, arithOf: Map<String, Boolean>): Outcome? {
        val via = "${sig(ref)} @$callOffset"
        val conf = confidenceOf(ref, arithOf)
        val raw = fed.raw
        return when (ss.sink.kind) {
            SiteKind.INTERN -> if (raw is String) Decoded(raw, 2, via, conf) else null
            SiteKind.NEW_STRING_CHARS, SiteKind.VALUEOF_CHARS -> if (raw is CharArray) Decoded(String(raw), 2, via, conf) else null
            SiteKind.NEW_STRING_BYTES -> if (raw is ByteArray) {
                val (cs, note) = charsetOf(ss.sink, ss.charset)
                if (cs == null) null else Decoded(String(raw, cs), 2, via, conf, note)
            } else null
            SiteKind.HELPER_CALL -> null
        }
    }

    /**
     * DECRYPTED if the helper's owner is obfuscated (short/single-char class or package segments) or its body does
     * decode arithmetic; FOLDED otherwise. So a named library helper that merely concatenates (WorkManager's tag
     * prefixer, a hex-decode of a literal certificate) reads as folded, not as a recovered secret.
     */
    private fun confidenceOf(ref: MethodRef, arithOf: Map<String, Boolean>): Confidence =
        if (arithOf[key(ref)] == true || isObfuscatedOwner(ref.declClass)) Confidence.DECRYPTED else Confidence.FOLDED

    private fun isObfuscatedOwner(declClass: String): Boolean {
        // Deliberately conservative: a single-character class name (La/b/c;) or two or more single-character package
        // segments. Short but real names (fx, io, ui, zzj) are NOT treated as obfuscation - the arithmetic-body test
        // is the reliable decryptor signal; this only catches the clearest name obfuscation on top of it.
        val owner = declClass.trim('L', ';').split('/')
        val cls = owner.lastOrNull() ?: return false
        val singleCharSegments = owner.dropLast(1).count { it.length == 1 }
        return (cls.length == 1) || singleCharSegments >= 2
    }

    companion object {
        private val SCALARS = setOf("I", "J", "S", "B", "C", "Z", "Ljava/lang/String;")

        /** Convenience for tests and tools that only have the source. */
        fun forSource(source: MethodSource, settings: AnalyzerSettings = AnalyzerSettings()): StringSiteAnalyzer =
            StringSiteAnalyzer(source, EngineContext(source, settings.limits, jadx.plugins.emu.exec.HostBoundary(), Hooks()), settings)
    }
}
