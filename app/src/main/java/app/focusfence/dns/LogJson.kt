package app.focusfence.dns

/**
 * Minimal JSON for the activity log: one flat object per line (JSON Lines).
 *
 * Values are restricted to String, Long/Int, Double, Boolean and null. Nested
 * objects/arrays are deliberately unsupported — a flat line is trivially
 * greppable, loads straight into pandas/jq/sqlite, and keeps the encoder small
 * enough to have no dependency. Pure JVM for unit-testability.
 */
object LogJson {

    fun encode(fields: Map<String, Any?>): String {
        val sb = StringBuilder(96)
        sb.append('{')
        var first = true
        for ((key, value) in fields) {
            if (!first) sb.append(',')
            first = false
            appendString(sb, key)
            sb.append(':')
            when (value) {
                null -> sb.append("null")
                is Boolean -> sb.append(if (value) "true" else "false")
                is Int, is Long, is Short, is Byte -> sb.append(value.toString())
                is Double -> if (value.isFinite()) sb.append(value.toString()) else sb.append("null")
                is Float -> if (value.isFinite()) sb.append(value.toDouble().toString()) else sb.append("null")
                else -> appendString(sb, value.toString())
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /**
     * Reads the `"t"` value of a line without decoding it, for cheap time-window
     * filtering of large logs. Relies only on `"t":<digits>` appearing once at
     * top level, which [encode] guarantees for records built by [LogEvents]
     * (a string value containing `"t":` would be escaped as `\"t\":`).
     */
    fun peekT(line: String): Long? {
        val i = line.indexOf("\"t\":")
        if (i < 0) return null
        var j = i + 4
        val start = j
        while (j < line.length && line[j].isDigit()) j++
        return if (j > start) line.substring(start, j).toLongOrNull() else null
    }

    /**
     * Parses one flat JSON object as written by [encode]. Integers become Long,
     * other numbers Double. Returns null for anything malformed or nested —
     * a corrupt line (e.g. torn by a crash mid-write) is skipped, not fatal.
     */
    fun decode(line: String): Map<String, Any?>? = try {
        Parser(line.trim()).parseObject()
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            expect('{')
            skipWs()
            if (peek() == '}') {
                i++
                return finish(out)
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                out[key] = parseValue()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return finish(out)
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun finish(out: Map<String, Any?>): Map<String, Any?> {
            skipWs()
            require(i == s.length) { "trailing data" }
            return out
        }

        private fun parseValue(): Any? = when (peek()) {
            '"' -> parseString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> parseNumber()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal" }
            i += word.length
            return value
        }

        private fun parseNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            require(text.isNotEmpty()) { "bad value" }
            return text.toLongOrNull() ?: text.toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number")
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape $e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char = s[i]

        private fun expect(c: Char) {
            require(s[i] == c) { "expected $c" }
            i++
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
