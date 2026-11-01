package jadx.emu.ext.strings

/** Smali for the synthetic "protected app" the tests analyze. Nothing here is specific to any real protector. */
object Fixtures {
    /** A string literal as smali source: ASCII printable stays, everything else (incl. surrogates) is a \uXXXX escape. */
    fun lit(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c in ' '..'~' -> append(c)
            else -> append(String.format("\\u%04x", c.code))
        }
        append('"')
    }

    fun xor(s: String, key: Int): String = String(CharArray(s.length) { (s[it].code xor key).toChar() })

    /** The decryptor helpers. [dec] is the classic String+int XOR loop; the others vary the return type or fail on purpose. */
    val decHelpers = """
.class public Lfx/Dec;
.super Ljava/lang/Object;

.method public static dec(Ljava/lang/String;I)Ljava/lang/String;
    .registers 6
    invoke-virtual {p0}, Ljava/lang/String;->toCharArray()[C
    move-result-object v0
    const/4 v1, 0
    :loop
    array-length v2, v0
    if-ge v1, v2, :done
    aget-char v2, v0, v1
    xor-int/2addr v2, p1
    int-to-char v2, v2
    aput-char v2, v0, v1
    add-int/lit8 v1, v1, 1
    goto :loop
    :done
    new-instance v1, Ljava/lang/String;
    invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
    return-object v1
.end method

.method public static decChars(Ljava/lang/String;I)[C
    .registers 6
    invoke-virtual {p0}, Ljava/lang/String;->toCharArray()[C
    move-result-object v0
    const/4 v1, 0
    :loop
    array-length v2, v0
    if-ge v1, v2, :done
    aget-char v2, v0, v1
    xor-int/2addr v2, p1
    int-to-char v2, v2
    aput-char v2, v0, v1
    add-int/lit8 v1, v1, 1
    goto :loop
    :done
    return-object v0
.end method

.method public static decBytes(I)[B
    .registers 7
    const/4 v0, 4
    new-array v1, v0, [B
    fill-array-data v1, :data
    const/4 v2, 0
    :loop
    array-length v3, v1
    if-ge v2, v3, :done
    aget-byte v3, v1, v2
    xor-int/2addr v3, p0
    int-to-byte v3, v3
    aput-byte v3, v1, v2
    add-int/lit8 v2, v2, 1
    goto :loop
    :done
    return-object v1
    :data
    .array-data 1
        0x6at 0x6dt 0x6ft 0x6ft
    .end array-data
.end method

# needs a host call the host policy refuses; the result depends on it
.method public static bad(I)Ljava/lang/String;
    .registers 4
    invoke-static {}, Ljava/util/concurrent/Executors;->newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;
    move-result-object v0
    invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# never returns
.method public static spin(I)Ljava/lang/String;
    .registers 3
    :top
    add-int/lit8 p0, p0, 1
    goto :top
.end method

# a NATIVE string table decryptor (the Brawl Stars elsm/K->elsm/at shape): no Dalvik body
.method public static native nativeDec(I)Ljava/lang/String;
.end method

# a synthetic-named helper with NO arithmetic and NO visible delegation (models idrnd i.a on the full app, where the
# codec isn't resolvable): returns a real multi-char string. Must still be DECRYPTED under name-only classification.
.method public static q(I)Ljava/lang/String;
    .registers 2
    const-string v0, "VKD5Mw=="
    invoke-static {v0}, Lfx/Dec;->z0K(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# DexGuard delegation shape: outer decryptor 'ix' does NO arithmetic itself - it calls inner 'z0K' which XORs.
# 'ix' must still classify as a decryptor via one-level delegation.
.method public static z0K(Ljava/lang/String;)Ljava/lang/String;
    .registers 5
    invoke-virtual {p0}, Ljava/lang/String;->toCharArray()[C
    move-result-object v0
    const/4 v1, 0
    :loop
    array-length v2, v0
    if-ge v1, v2, :done
    aget-char v2, v0, v1
    xor-int/lit8 v2, v2, 3
    int-to-char v2, v2
    aput-char v2, v0, v1
    add-int/lit8 v1, v1, 1
    goto :loop
    :done
    new-instance v1, Ljava/lang/String;
    invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
    return-object v1
.end method

.method public static ix(Ljava/lang/String;)Ljava/lang/String;
    .registers 2
    invoke-static {p0}, Lfx/Dec;->z0K(Ljava/lang/String;)Ljava/lang/String;
    move-result-object p0
    return-object p0
.end method

# androidx/work/x->i shape: SHORT name (synthetic by name) but body only calls framework StringBuilder.append ("WM-"+tag).
# No arithmetic, no synthetic app-helper call -> must FOLD (the WorkManager false positive), not decrypt.
.method public static iw(Ljava/lang/String;)Ljava/lang/String;
    .registers 3
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "WM-"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

# a library-named helper that DOES transform arithmetic (xor) but has a wordlike camelCase name -> must FOLD, not decrypt.
.method public static decodeConfigValue(Ljava/lang/String;I)Ljava/lang/String;
    .registers 6
    invoke-virtual {p0}, Ljava/lang/String;->toCharArray()[C
    move-result-object v0
    const/4 v1, 0
    :loop
    array-length v2, v0
    if-ge v1, v2, :done
    aget-char v2, v0, v1
    xor-int/2addr v2, p1
    int-to-char v2, v2
    aput-char v2, v0, v1
    add-int/lit8 v1, v1, 1
    goto :loop
    :done
    new-instance v1, Ljava/lang/String;
    invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
    return-object v1
.end method

# org.bouncycastle.util.Strings.toByteArray shape: b[i]=(byte)s.charAt(i) with a +1 counter. NOT a decoder.
.method public static toByteArray(Ljava/lang/String;)[B
    .registers 5
    invoke-virtual {p0}, Ljava/lang/String;->length()I
    move-result v0
    new-array v1, v0, [B
    const/4 v2, 0x0
    :g
    if-eq v2, v0, :done
    invoke-virtual {p0, v2}, Ljava/lang/String;->charAt(I)C
    move-result v3
    int-to-byte v3, v3
    aput-byte v3, v1, v2
    add-int/lit8 v2, v2, 0x1
    goto :g
    :done
    return-object v1
.end method

# an ordinary string-returning helper, called with constants from only two places
.method public static rare(I)Ljava/lang/String;
    .registers 3
    const-string v0, "x"
    return-object v0
.end method

# a NAMED helper that only prefixes (no arithmetic): the WorkManager-tagWithPrefix false-positive shape
.method public static tagWithPrefix(Ljava/lang/String;)Ljava/lang/String;
    .registers 3
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    const-string v1, "WM-"
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
"""

    private fun use(body: String) = ".class public Lfx/Use;\n.super Ljava/lang/Object;\n$body\n"
    private fun m(sig: String, regs: Int, body: String) = ".method public static $sig\n    .registers $regs\n$body\n.end method\n"

    private fun ixCall(plain: String) = """
    const-string v0, ${lit(xor(plain, 3))}
    invoke-static {v0}, Lfx/Dec;->ix(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0"""

    private fun decConfigCall(plain: String) = """
    const-string v0, ${lit(xor(plain, 3))}
    const/16 v1, 3
    invoke-static {v0, v1}, Lfx/Dec;->decodeConfigValue(Ljava/lang/String;I)Ljava/lang/String;
    move-result-object v0"""

    private fun decCall(cipher: String, key: Int) = """
    const-string v0, ${lit(cipher)}
    const/16 v1, $key
    invoke-static {v0, v1}, Lfx/Dec;->dec(Ljava/lang/String;I)Ljava/lang/String;
    move-result-object v0"""

    const val PLAIN_A = "app-secret"
    const val PLAIN_B = "https://example.test/api"

    val usage: String = use(
        m("viaHelperIntern()Ljava/lang/String;", 3, decCall(xor(PLAIN_A, 3), 3) + """
    invoke-virtual {v0}, Ljava/lang/String;->intern()Ljava/lang/String;
    move-result-object v0
    return-object v0""") +
        m("helperDirect()Ljava/lang/String;", 3, decCall(xor(PLAIN_B, 5), 5) + "\n    return-object v0") +
        m("chars1()Ljava/lang/String;", 4, """
    const-string v0, ${lit(xor("chars-one", 3))}
    const/4 v1, 3
    invoke-static {v0, v1}, Lfx/Dec;->decChars(Ljava/lang/String;I)[C
    move-result-object v0
    new-instance v1, Ljava/lang/String;
    invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
    return-object v1""") +
        m("chars2()Ljava/lang/String;", 4, """
    const-string v0, ${lit(xor("chars-two", 3))}
    const/4 v1, 3
    invoke-static {v0, v1}, Lfx/Dec;->decChars(Ljava/lang/String;I)[C
    move-result-object v0
    new-instance v1, Ljava/lang/String;
    invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
    return-object v1""") +
        m("chars3()Ljava/lang/String;", 4, """
    const-string v0, ${lit(xor("chars-three", 3))}
    const/4 v1, 3
    invoke-static {v0, v1}, Lfx/Dec;->decChars(Ljava/lang/String;I)[C
    move-result-object v0
    invoke-static {v0}, Ljava/lang/String;->valueOf([C)Ljava/lang/String;
    move-result-object v0
    return-object v0""") +
        m("bytes1()Ljava/lang/String;", 5, bytesBody()) + m("bytes2()Ljava/lang/String;", 5, bytesBody()) + m("bytes3()Ljava/lang/String;", 5, bytesBody()) +
        m("straightValueOf()Ljava/lang/String;", 5, """
    const/4 v0, 2
    new-array v1, v0, [C
    const/16 v2, 0x68
    const/4 v3, 3
    xor-int/2addr v2, v3
    int-to-char v2, v2
    const/4 v4, 0
    aput-char v2, v1, v4
    const/16 v2, 0x6a
    xor-int/2addr v2, v3
    int-to-char v2, v2
    const/4 v4, 1
    aput-char v2, v1, v4
    invoke-static {v1}, Ljava/lang/String;->valueOf([C)Ljava/lang/String;
    move-result-object v0
    return-object v0""") +
        m("straightNewAndIntern()Ljava/lang/String;", 5, """
    const/4 v0, 2
    new-array v1, v0, [C
    const/16 v2, 0x68
    const/4 v3, 3
    xor-int/2addr v2, v3
    int-to-char v2, v2
    const/4 v4, 0
    aput-char v2, v1, v4
    const/16 v2, 0x6a
    xor-int/2addr v2, v3
    int-to-char v2, v2
    const/4 v4, 1
    aput-char v2, v1, v4
    new-instance v0, Ljava/lang/String;
    invoke-direct {v0, v1}, Ljava/lang/String;-><init>([C)V
    invoke-virtual {v0}, Ljava/lang/String;->intern()Ljava/lang/String;
    move-result-object v0
    return-object v0""") +
        m("inlineLoop()Ljava/lang/String;", 4, """
    const-string v0, ${lit(xor("inline", 3))}
    invoke-virtual {v0}, Ljava/lang/String;->toCharArray()[C
    move-result-object v0
    const/4 v1, 0
    :loop
    array-length v2, v0
    if-ge v1, v2, :done
    aget-char v2, v0, v1
    xor-int/lit8 v2, v2, 3
    int-to-char v2, v2
    aput-char v2, v0, v1
    add-int/lit8 v1, v1, 1
    goto :loop
    :done
    new-instance v1, Ljava/lang/String;
    invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
    return-object v1""") +
        m("branchy(Z)Ljava/lang/String;", 5, """
    if-eqz p0, :b
${decCall(xor("left", 3), 3)}
    goto :join
    :b
${decCall(xor("right", 5), 5)}
    :join
    invoke-virtual {v0}, Ljava/lang/String;->intern()Ljava/lang/String;
    move-result-object v0
    return-object v0""") +
        m("deadCode()Ljava/lang/String;", 3, """
    const/4 v0, 0
    if-nez v0, :dead
    const-string v1, "live"
    return-object v1
    :dead
    const-string v1, "xyz"
    invoke-virtual {v1}, Ljava/lang/String;->intern()Ljava/lang/String;
    move-result-object v1
    return-object v1""") +
        m("dynamic(Ljava/lang/String;)Ljava/lang/String;", 4, """
    const/4 v0, 3
    invoke-static {p0, v0}, Lfx/Dec;->dec(Ljava/lang/String;I)Ljava/lang/String;
    move-result-object v0
    return-object v0""") +
        m("badA()Ljava/lang/String;", 2, badBody()) + m("badB()Ljava/lang/String;", 2, badBody()) + m("badC()Ljava/lang/String;", 2, badBody()) +
        m("spinA()Ljava/lang/String;", 2, spinBody()) + m("spinB()Ljava/lang/String;", 2, spinBody()) + m("spinC()Ljava/lang/String;", 2, spinBody()) +
        m("wmA()Ljava/lang/String;", 2, "    const-string v0, \"Worker\"\n    invoke-static {v0}, Lfx/Dec;->iw(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("wmB()Ljava/lang/String;", 2, "    const-string v0, \"Processor\"\n    invoke-static {v0}, Lfx/Dec;->iw(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("wmC()Ljava/lang/String;", 2, "    const-string v0, \"Scheduler\"\n    invoke-static {v0}, Lfx/Dec;->iw(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("lookupA()Ljava/lang/String;", 2, "    const/4 v0, 1\n    invoke-static {v0}, Lfx/Dec;->q(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("lookupB()Ljava/lang/String;", 2, "    const/4 v0, 2\n    invoke-static {v0}, Lfx/Dec;->q(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("lookupC()Ljava/lang/String;", 2, "    const/4 v0, 3\n    invoke-static {v0}, Lfx/Dec;->q(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("delegA()Ljava/lang/String;", 3, ixCall("deleg-one")) +
        m("delegB()Ljava/lang/String;", 3, ixCall("deleg-two")) +
        m("delegC()Ljava/lang/String;", 3, ixCall("deleg-three")) +
        m("libArithA()Ljava/lang/String;", 3, decConfigCall("alpha-setting") ) +
        m("libArithB()Ljava/lang/String;", 3, decConfigCall("beta-setting") ) +
        m("libArithC()Ljava/lang/String;", 3, decConfigCall("gamma-setting") ) +
        m("trivialA()Ljava/lang/String;", 3, decCall(xor("x", 3), 3) + "\n    return-object v0") +
        m("trivialB()Ljava/lang/String;", 3, decCall(xor("y", 3), 3) + "\n    return-object v0") +
        m("trivialC()Ljava/lang/String;", 3, decCall(xor("z", 3), 3) + "\n    return-object v0") +
        m("tbA()Ljava/lang/String;", 5, "    const-string v0, \"alpha\"\n    invoke-static {v0}, Lfx/Dec;->toByteArray(Ljava/lang/String;)[B\n    move-result-object v1\n    new-instance v2, Ljava/lang/String;\n    const-string v3, \"UTF-8\"\n    invoke-direct {v2, v1, v3}, Ljava/lang/String;-><init>([BLjava/lang/String;)V\n    return-object v2") +
        m("tbB()Ljava/lang/String;", 5, "    const-string v0, \"beta\"\n    invoke-static {v0}, Lfx/Dec;->toByteArray(Ljava/lang/String;)[B\n    move-result-object v1\n    new-instance v2, Ljava/lang/String;\n    const-string v3, \"UTF-8\"\n    invoke-direct {v2, v1, v3}, Ljava/lang/String;-><init>([BLjava/lang/String;)V\n    return-object v2") +
        m("tbC()Ljava/lang/String;", 5, "    const-string v0, \"gamma\"\n    invoke-static {v0}, Lfx/Dec;->toByteArray(Ljava/lang/String;)[B\n    move-result-object v1\n    new-instance v2, Ljava/lang/String;\n    const-string v3, \"UTF-8\"\n    invoke-direct {v2, v1, v3}, Ljava/lang/String;-><init>([BLjava/lang/String;)V\n    return-object v2") +
        m("natA()Ljava/lang/String;", 2, "    const/4 v0, 1\n    invoke-static {v0}, Lfx/Dec;->nativeDec(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("natB()Ljava/lang/String;", 2, "    const/4 v0, 2\n    invoke-static {v0}, Lfx/Dec;->nativeDec(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("natC()Ljava/lang/String;", 2, "    const/4 v0, 3\n    invoke-static {v0}, Lfx/Dec;->nativeDec(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("rareA()Ljava/lang/String;", 2, "    const/4 v0, 7\n    invoke-static {v0}, Lfx/Dec;->rare(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("rareB()Ljava/lang/String;", 2, "    const/16 v0, 8\n    invoke-static {v0}, Lfx/Dec;->rare(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("tagA()Ljava/lang/String;", 2, "    const-string v0, \"Data\"\n    invoke-static {v0}, Lfx/Dec;->tagWithPrefix(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("tagB()Ljava/lang/String;", 2, "    const-string v0, \"Processor\"\n    invoke-static {v0}, Lfx/Dec;->tagWithPrefix(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("tagC()Ljava/lang/String;", 2, "    const-string v0, \"Schedulers\"\n    invoke-static {v0}, Lfx/Dec;->tagWithPrefix(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n    return-object v0") +
        m("stray()I", 3, "    const/4 v0, 4\n    new-array v1, v0, [C\n    array-length v2, v1\n    return v2"),
    )

    private fun bytesBody() = """
    const/4 v0, 3
    invoke-static {v0}, Lfx/Dec;->decBytes(I)[B
    move-result-object v1
    new-instance v2, Ljava/lang/String;
    const-string v3, "UTF-8"
    invoke-direct {v2, v1, v3}, Ljava/lang/String;-><init>([BLjava/lang/String;)V
    return-object v2"""

    private fun badBody() = "    const/4 v0, 1\n    invoke-static {v0}, Lfx/Dec;->bad(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0"
    private fun spinBody() = "    const/4 v0, 1\n    invoke-static {v0}, Lfx/Dec;->spin(I)Ljava/lang/String;\n    move-result-object v0\n    return-object v0"

    /** The expected plaintext of `decBytes(3)`: bytes 'jmoo' XOR 3. */
    val DEC_BYTES_3 = String(byteArrayOf(0x6a, 0x6d, 0x6f, 0x6f).map { (it.toInt() xor 3).toByte() }.toByteArray(), Charsets.UTF_8)
}
