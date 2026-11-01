package jadx.emu.ext.strings

/**
 * What the analyzer found: every site with its outcome, the helpers it looked at, and counts.
 */
class AnalysisReport(val results: List<SiteResult>, val helpers: List<HelperInfo>, val methodsScanned: Int) {

    val decoded: List<SiteResult> get() = results.filter { it.outcome is Decoded }
    val decrypted: List<SiteResult> get() = results.filter { it.outcome is Decoded && (it.outcome as Decoded).confidence == Confidence.DECRYPTED }
    val folded: List<SiteResult> get() = results.filter { it.outcome is Decoded && (it.outcome as Decoded).confidence == Confidence.FOLDED }
    val trivial: List<SiteResult> get() = results.filter { it.outcome is Decoded && (it.outcome as Decoded).confidence == Confidence.TRIVIAL }
    val skipped: List<SiteResult> get() = results.filter { it.outcome is Skipped }

    class Summary(
        val found: Int,
        val decoded: Int,
        val skipped: Int,
        val decodedByTier: Map<Int, Int>,
        val byKind: Map<SiteKind, Pair<Int, Int>>,   // kind -> (decoded, skipped)
        val skipReasons: Map<Reason, Int>,
    )

    fun summary(): Summary {
        val dec = decoded
        val byKind = SiteKind.values().associateWith { k ->
            results.count { it.site.kind == k && it.outcome is Decoded } to results.count { it.site.kind == k && it.outcome is Skipped }
        }.filterValues { it.first + it.second > 0 }
        val reasons = skipped.groupingBy { (it.outcome as Skipped).reason }.eachCount().toSortedMap()
        return Summary(
            found = results.size, decoded = dec.size, skipped = results.size - dec.size,
            decodedByTier = dec.groupingBy { (it.outcome as Decoded).tier }.eachCount().toSortedMap(),
            byKind = byKind, skipReasons = reasons,
        )
    }

    /** A short human summary, then every skipped site with its reason (the list to read when deciding what to build next). */
    fun toText(maxSkipped: Int = 200): String = buildString {
        val s = summary()
        appendLine("sites: ${s.found} found | ${decrypted.size} decrypted, ${trivial.size} trivial, ${folded.size} folded, ${s.skipped} skipped   (${methodsScanned} methods scanned)")
        appendLine("  decrypted = recovered from an obfuscated arithmetic decryptor, value looks real.  trivial = same, but a 1-char/control token (values in JSON only).  folded = an ordinary library/named-helper constant.")
        if (s.decodedByTier.isNotEmpty()) appendLine("  by tier: " + s.decodedByTier.entries.joinToString { "tier ${it.key}: ${it.value}" })
        // real decryptors = helpers that actually produced a decrypted or trivial value (synthetic name + arithmetic + ran)
        val realHelpers = (decrypted + trivial).mapNotNull { (it.outcome as Decoded).via?.substringBefore(" @") }.toSet()
        val realDec = helpers.filter { it.signature in realHelpers }
        if (realDec.isNotEmpty()) {
            appendLine("decryptor helpers (obfuscated name + transform arithmetic):")
            for (h in realDec.sortedByDescending { it.constantSites }) appendLine("  ${shortSig(h.signature)}  ${h.constantSites}/${h.sites} constant call sites")
        }
        if (decrypted.isNotEmpty()) {
            appendLine("decrypted strings (grouped by decryptor):")
            val byHelper = decrypted.groupBy { (it.outcome as Decoded).via?.substringBefore(" @")?.substringBefore(" ")?.let { v -> v.substringBefore("->").let { c -> c } }?.let { _ -> (it.outcome as Decoded).via!!.substringBefore(" @") } ?: "?" }
            for ((helper, sites) in byHelper.entries.sortedByDescending { it.value.size }) {
                appendLine("  via ${shortSig(helper)}  (${sites.size})")
                for (r in sites.take(maxSkipped)) {
                    val d = r.outcome as Decoded
                    appendLine("      ${shortMethod(r.site.method)} @${r.site.offset}  ${q(d.value)}")
                }
                if (sites.size > maxSkipped) appendLine("      ... and ${sites.size - maxSkipped} more")
            }
        }
        if (s.skipReasons.isNotEmpty()) {
            appendLine("skipped, by reason:")
            for ((r, n) in s.skipReasons) appendLine("  ${r.name.padEnd(22)} $n")
            appendLine("skipped sites:")
            for (r in skipped.take(maxSkipped)) {
                val o = r.outcome as Skipped
                appendLine("  ${r.site.method} @${r.site.offset}  ${r.site.label}  -> ${o.reason}: ${o.detail}")
            }
            if (skipped.size > maxSkipped) appendLine("  ... and ${skipped.size - maxSkipped} more")
        }
    }

    fun toJson(): String = buildString {
        val s = summary()
        append("{\n")
        append("  \"summary\": {\"methodsScanned\": $methodsScanned, \"found\": ${s.found}, \"decoded\": ${s.decoded}, \"decrypted\": ${decrypted.size}, \"trivial\": ${trivial.size}, \"folded\": ${folded.size}, \"skipped\": ${s.skipped},\n")
        append("    \"decodedByTier\": {" + s.decodedByTier.entries.joinToString(", ") { "\"tier${it.key}\": ${it.value}" } + "},\n")
        append("    \"skipReasons\": {" + s.skipReasons.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" } + "}},\n")
        append("  \"helpers\": [" + helpers.joinToString(",\n    ", prefix = if (helpers.isEmpty()) "" else "\n    ") {
            "{\"signature\": ${q(it.signature)}, \"sites\": ${it.sites}, \"constantSites\": ${it.constantSites}, \"decryptor\": ${it.decryptor}, \"doesArithmetic\": ${it.doesArithmetic}}"
        } + (if (helpers.isEmpty()) "" else "\n  ") + "],\n")
        append("  \"sites\": [" + results.joinToString(",\n    ", prefix = if (results.isEmpty()) "" else "\n    ") { r ->
            val base = "\"method\": ${q(r.site.method)}, \"offset\": ${r.site.offset}, \"kind\": \"${r.site.kind}\", \"label\": ${q(r.site.label)}"
            when (val o = r.outcome) {
                is Decoded -> "{$base, \"status\": \"decoded\", \"tier\": ${o.tier}, \"confidence\": \"${o.confidence ?: Confidence.FOLDED}\", \"value\": ${q(o.value)}" +
                    (o.via?.let { ", \"via\": ${q(it)}" } ?: "") + (o.note?.let { ", \"note\": ${q(it)}" } ?: "") + "}"
                is Skipped -> "{$base, \"status\": \"skipped\", \"reason\": \"${o.reason}\", \"detail\": ${q(o.detail)}}"
            }
        } + (if (results.isEmpty()) "" else "\n  ") + "]\n")
        append("}\n")
    }

    /** `Lcom/foo/Bar;->baz(...)ret` -> `com.foo.Bar.baz` for readable reports. */
    private fun shortMethod(desc: String): String {
        val cls = desc.substringBefore(";->").trimStart('L').replace('/', '.')
        val m = desc.substringAfter(";->").substringBefore('(')
        return "$cls.$m"
    }
    private fun shortSig(sig: String): String = shortMethod(sig)

    companion object {
        /** JSON string literal. Control characters and unpaired surrogates are escaped so the output is always valid. */
        fun q(s: String): String = buildString(s.length + 2) {
            append('"')
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' || c == '\u2028' || c == '\u2029' -> append(String.format("\\u%04x", c.code))
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { append(c); append(s[i + 1]); i++ }
                    Character.isSurrogate(c) -> append(String.format("\\u%04x", c.code))
                    else -> append(c)
                }
                i++
            }
            append('"')
        }
    }
}
