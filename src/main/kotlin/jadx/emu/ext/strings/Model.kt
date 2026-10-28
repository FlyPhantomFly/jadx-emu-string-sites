package jadx.emu.ext.strings

/** What kind of place a string was (or could not be) recovered from. */
enum class SiteKind {
    /** `String.intern()` - the receiver is the string. */
    INTERN,
    /** `new String(char[])`. */
    NEW_STRING_CHARS,
    /** `String.valueOf(char[])` / `String.copyValueOf(char[])`. */
    VALUEOF_CHARS,
    /** `new String(byte[])`, `new String(byte[], charset)`. */
    NEW_STRING_BYTES,
    /** A call to a helper that was identified as a decryptor by how it is used. */
    HELPER_CALL,
}

/** Why a site was not decoded. Every skipped site carries exactly one of these plus a free-text detail. */
enum class Reason {
    /** Dataflow proved no path reaches the site (dead code, e.g. behind an opaque predicate). */
    UNREACHABLE,
    /** The sink's argument is not a known constant at that point (loop-carried, parameter, field, unknown call). */
    UNKNOWN_ARGUMENT,
    /** Dataflow stopped on a limit or an unhandled instruction, so the site may or may not be reachable. */
    ANALYSIS_INCOMPLETE,
    /** Dataflow threw. */
    ANALYSIS_FAILED,
    /** A decryptor helper is called here with arguments that are not all constants. */
    NON_CONSTANT_ARGUMENTS,
    /** Running the helper hit a step, time or depth limit. */
    STEP_OR_TIME_LIMIT,
    /** Running the helper threw an emulated exception nobody caught. */
    EXCEPTION,
    /** Running the helper needed a host call the host policy refuses. */
    HOST_DENIED,
    /** A host call made by the helper threw. */
    HOST_THREW,
    /** A host call made by the helper received an unknown input. */
    HOST_UNKNOWN_INPUT,
    /** The helper needed a framework/host method that has no stub. */
    MISSING_STUB,
    /** The decryptor (or a method it calls) is native: the decryption is in a .so, not in the dex — nothing to run here. */
    NATIVE_METHOD,
    /** The helper returned an unknown value and the engine reported nothing. */
    UNKNOWN_RESULT,
    /** The helper returned something that is not a String, char[] or byte[]. */
    UNSUPPORTED_RESULT,
}

/**
 * One place in the app: [method] is `Lcls;->name(args)ret`, [offset] the code offset of the instruction,
 * [label] a short description (`String.intern()` or the helper's signature).
 */
data class Site(val method: String, val offset: Int, val kind: SiteKind, val label: String)

sealed interface Outcome

/** How much the result looks like a decrypted secret rather than an ordinary constant. */
enum class Confidence {
    /** The producing helper does byte/char arithmetic over its input (xor/add/sub/array ops): real decoding. */
    DECRYPTED,
    /** A constant computed by an ordinary, non-obfuscated helper (prefix, concat, hex-decode of a literal): folding, not decryption. */
    FOLDED,
}

/**
 * @property tier 1 = read from dataflow without running anything, 2 = recovered by running a helper.
 * @property via for tier 2, the helper signature (and for a sink, the call site that fed it).
 * @property confidence for tier 2, whether the value looks decrypted or merely folded (tier 1 is always FOLDED-grade constant).
 * @property note something the reader should know about the value (e.g. an assumed charset).
 */
data class Decoded(val value: String, val tier: Int, val via: String? = null, val confidence: Confidence? = null, val note: String? = null) : Outcome

data class Skipped(val reason: Reason, val detail: String) : Outcome

data class SiteResult(val site: Site, val outcome: Outcome)

/** A static method that returns a String/char[]/byte[] and is called with all-constant arguments from several places. */
data class HelperInfo(val signature: String, val sites: Int, val constantSites: Int, val decryptor: Boolean, val doesArithmetic: Boolean = false)

class AnalyzerSettings(
    /** Limits for every dataflow run and every helper invocation. */
    val limits: jadx.plugins.emu.exec.ExecLimits = jadx.plugins.emu.exec.ExecLimits(maxSteps = 2_000_000, maxMillis = 5_000, maxDepth = 32),
    /** A helper is a decryptor once it is called with all-constant arguments from at least this many places. */
    val helperMinConstantSites: Int = 3,
    /** Whether a helper that takes no arguments can be a decryptor (off: too many ordinary getters match). */
    val allowNoArgHelpers: Boolean = false,
    /** Decoded values longer than this are reported as skipped rather than carried around. */
    val maxResultChars: Int = 1 shl 20,
)
