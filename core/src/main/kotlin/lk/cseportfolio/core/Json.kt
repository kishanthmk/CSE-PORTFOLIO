package lk.cseportfolio.core

/** Tiny dependency-free JSON reader/writer (maps, lists, strings, numbers, booleans, null). */
object Json {
    fun write(v: Any?): String = StringBuilder().also { w(it, v) }.toString()

    private fun w(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> { sb.append('{'); var first = true
                for ((k, x) in v) { if (!first) sb.append(','); first = false; str(sb, k.toString()); sb.append(':'); w(sb, x) }
                sb.append('}') }
            is Iterable<*> -> { sb.append('['); var first = true
                for (x in v) { if (!first) sb.append(','); first = false; w(sb, x) }
                sb.append(']') }
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    fun parse(text: String): Any? { val p = P(text); p.ws(); val v = p.value(); p.ws(); if (p.i != text.length) p.fail("trailing data"); return v }

    private class P(val s: String) {
        var i = 0
        fun fail(m: String): Nothing = throw IllegalArgumentException("Invalid JSON at $i: $m")
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> string()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) fail("expected $w"); i += w.length; return v }
        fun num(): Any { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i); return if (t.any { it in ".eE" }) java.math.BigDecimal(t) else t.toLong() }
        fun string(): String { i++; val sb = StringBuilder()
            while (true) { if (i >= s.length) fail("unterminated string"); val c = s[i++]
                when (c) { '"' -> return sb.toString()
                    '\\' -> { val e = s[i++]; when (e) { 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e) } }
                    else -> sb.append(c) } } }
        fun obj(): Map<String, Any?> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
            while (true) { ws(); if (s[i] != '"') fail("expected key"); val k = string(); ws(); if (s[i] != ':') fail("expected ':'"); i++; ws(); m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }; if (s[i] == '}') { i++; return m }; fail("expected ',' or '}'") } }
        fun arr(): List<Any?> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
            while (true) { ws(); l.add(value()); ws(); if (s[i] == ',') { i++; continue }; if (s[i] == ']') { i++; return l }; fail("expected ',' or ']'") } }
    }
}

/** Password-based encryption for backup files: PBKDF2-HMAC-SHA256 (120k rounds) + AES-256-GCM. */
object BackupCrypto {
    private val MAGIC = "CSEPP1".toByteArray(Charsets.US_ASCII)
    private const val ROUNDS = 120_000

    fun isEncrypted(data: ByteArray): Boolean = data.size > MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun key(password: CharArray, salt: ByteArray): javax.crypto.SecretKey {
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(javax.crypto.spec.PBEKeySpec(password, salt, ROUNDS, 256)).encoded
        return javax.crypto.spec.SecretKeySpec(k, "AES")
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val rnd = java.security.SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key(password, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    /** @throws javax.crypto.AEADBadTagException when the password is wrong or the file is damaged. */
    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(isEncrypted(data)) { "Not an encrypted CSE Portfolio Pro backup" }
        val o = MAGIC.size
        val salt = data.copyOfRange(o, o + 16); val iv = data.copyOfRange(o + 16, o + 28)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(password, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        return c.doFinal(data, o + 28, data.size - o - 28)
    }
}
