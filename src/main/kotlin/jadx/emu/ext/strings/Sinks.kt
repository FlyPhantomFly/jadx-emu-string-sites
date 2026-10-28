package jadx.emu.ext.strings

import jadx.api.plugins.input.insns.Opcode
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.model.SwitchPayload

/** A place where a string value is produced from a char[]/byte[] (or interned), found by the instruction itself, not by its shape. */
class Sink(val index: Int, val insn: DalvikInsn, val kind: SiteKind, val argReg: Int, val charsetReg: Int, val label: String)

object Sinks {
    private const val STRING = "Ljava/lang/String;"

    fun isInvoke(op: Opcode): Boolean = op.name.startsWith("INVOKE_")

    fun find(m: DexMethod): List<Sink> {
        val out = ArrayList<Sink>()
        for ((i, insn) in m.insns.withIndex()) {
            if (!isInvoke(insn.opcode)) continue
            val ref = insn.ref as? MethodRef ?: continue
            if (ref.declClass != STRING) continue
            val op = insn.opcode.name
            val regs = insn.regs
            when {
                op.startsWith("INVOKE_VIRTUAL") && ref.name == "intern" && ref.argTypes.isEmpty() && regs.isNotEmpty() ->
                    out.add(Sink(i, insn, SiteKind.INTERN, regs[0], -1, "String.intern()"))
                op.startsWith("INVOKE_DIRECT") && ref.name == "<init>" -> when (ref.argTypes) {
                    listOf("[C") -> if (regs.size >= 2) out.add(Sink(i, insn, SiteKind.NEW_STRING_CHARS, regs[1], -1, "new String(char[])"))
                    listOf("[B") -> if (regs.size >= 2) out.add(Sink(i, insn, SiteKind.NEW_STRING_BYTES, regs[1], -1, "new String(byte[])"))
                    listOf("[B", STRING), listOf("[B", "Ljava/nio/charset/Charset;") ->
                        if (regs.size >= 3) out.add(Sink(i, insn, SiteKind.NEW_STRING_BYTES, regs[1], regs[2], "new String(byte[], charset)"))
                }
                op.startsWith("INVOKE_STATIC") && (ref.name == "valueOf" || ref.name == "copyValueOf") && ref.argTypes == listOf("[C") && regs.isNotEmpty() ->
                    out.add(Sink(i, insn, SiteKind.VALUEOF_CHARS, regs[0], -1, "String.${ref.name}(char[])"))
            }
        }
        return out
    }

    // --- control flow facts used to decide whether a value can be traced to the call that produced it -------------------------------

    /** Offsets some branch, switch or exception handler can jump to. A superset on purpose: both absolute and relative readings are added. */
    fun jumpTargets(m: DexMethod): Set<Int> {
        val s = HashSet<Int>()
        for (insn in m.insns) {
            val op = insn.opcode.name
            if (op.startsWith("IF_") || op.startsWith("GOTO")) { s.add(insn.target); s.add(insn.offset + insn.target) }
            (insn.payload as? SwitchPayload)?.targets?.forEach { t -> s.add(t); s.add(insn.offset + t) }
        }
        for (t in m.tries) for (h in t.handlers) s.add(h.offset)
        return s
    }

    private fun isBreaker(op: String) =
        op.startsWith("IF_") || op.startsWith("GOTO") || op.endsWith("SWITCH") || op.startsWith("RETURN") || op == "THROW"

    /** Opcodes that never change a register's value (the destination, if any, is not a register of interest). */
    private fun isTransparent(op: String) =
        op.startsWith("INVOKE_") || op.startsWith("APUT") || op.startsWith("IPUT") || op.startsWith("SPUT") ||
            op == "NOP" || op.startsWith("MONITOR") || op == "FILL_ARRAY_DATA" || op == "CHECK_CAST"

    /**
     * Walk backwards from the instruction at [sinkIndex] inside one straight-line block and return the index of the
     * `invoke-static` whose result ended up in [reg], or null if the register was defined some other way, or if the walk
     * would leave the block (branch, return, jump target) before finding the definition. Conservative: null means
     * "cannot say", never "no".
     */
    fun feedingStaticCall(m: DexMethod, sinkIndex: Int, reg: Int, targets: Set<Int>): Int? {
        var cur = sinkIndex
        while (cur > 0) {
            if (m.insns[cur].offset in targets) return null
            val prev = m.insns[cur - 1]
            val op = prev.opcode.name
            if (isBreaker(op)) return null
            if (op.startsWith("MOVE_RESULT") && prev.regs.isNotEmpty() && prev.regs[0] == reg) {
                val p = cur - 1
                if (p == 0 || m.insns[p].offset in targets) return null
                val call = m.insns[p - 1]
                return if (call.opcode.name.startsWith("INVOKE_STATIC")) p - 1 else null
            }
            if (!isTransparent(op) && prev.regs.isNotEmpty() && (prev.regs[0] == reg || prev.regs[0] + 1 == reg)) return null
            cur--
        }
        return null
    }

    /**
     * Whether [m]'s body transforms its input with byte/char arithmetic - the mark of a decoder, as opposed to a helper
     * that only concatenates, prefixes or looks things up. Used to tell a real string decryptor from ordinary constant
     * folding of a named library method (e.g. androidx WorkManager's "WM-"+tag helper, which otherwise looks identical
     * to a decryptor: static, String in, String out, called from many sites with constants).
     */
    fun doesDecodingArithmetic(m: DexMethod): Boolean {
        var arrayWrite = false
        var transform = false
        var loopCounterOnly = true
        for (insn in m.insns) {
            val op = insn.opcode.name
            when {
                // XOR / MUL / RSUB / shifts transform data - the mark of a decoder. (A bare ADD/SUB is usually a loop
                // counter, so it does NOT count on its own: org.bouncycastle.util.Strings.toByteArray is just
                // `b[i]=(byte)s.charAt(i)` with an `add-int/lit8` counter, which must read as NOT a decoder.)
                op.startsWith("XOR_") || op.startsWith("MUL_") || op.startsWith("RSUB") ||
                    op.startsWith("SHL_") || op.startsWith("SHR_") || op.startsWith("USHR") -> { transform = true; loopCounterOnly = false }
                // an ADD/SUB that is NOT the `+1`/`-1` of a lit8 counter (i.e. two-register, or a non-unit literal) is a real transform
                (op.startsWith("ADD_") || op.startsWith("SUB_")) && !isUnitLiteralStep(insn) -> { transform = true; loopCounterOnly = false }
                op.startsWith("APUT") -> arrayWrite = true
            }
        }
        return transform && arrayWrite && !loopCounterOnly
    }

    /** True for `add-int/lit8 x, y, 1` / `-1` style instructions - a loop counter step, not a data transform. */
    private fun isUnitLiteralStep(insn: DalvikInsn): Boolean {
        val op = insn.opcode.name
        if (!op.contains("LIT")) return false
        val lit = insn.literal
        return lit == 1L || lit == -1L
    }
}
