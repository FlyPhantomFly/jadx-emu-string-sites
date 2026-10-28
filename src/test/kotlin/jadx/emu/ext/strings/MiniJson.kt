package jadx.emu.ext.strings

/** A small strict JSON parser used only to validate the report independently of the code that wrote it. */
object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text); p.ws(); val v = p.value(); p.ws()
        require(p.i == text.length) { "trailing data at ${p.i}" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \n\r\t") i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length; return v }
        fun num(): Double { val st = i; if (s[i] == '-') i++; while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++; return s.substring(st, i).toDouble() }
        fun str(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("bad escape \\$e")
                    }
                    c < ' ' -> error("raw control character in string at ${i - 1}")
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) { out.add(value()); ws(); if (s[i] == ',') { i++; continue }; require(s[i] == ']') { "expected , or ] at $i" }; i++; return out }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':') { "expected : at $i" }; i++
                out[k] = value(); ws()
                if (s[i] == ',') { i++; continue }; require(s[i] == '}') { "expected , or } at $i" }; i++; return out
            }
        }
    }
}
