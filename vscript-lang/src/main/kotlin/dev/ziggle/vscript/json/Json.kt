package dev.ziggle.vscript.json

import dev.ziggle.vscript.vm.StructValue
import dev.ziggle.vscript.vm.VmError

object Json {

    /**
     * The deepest a document may nest.
     *
     * Recursive descent means depth costs JVM stack, and a hostile or corrupt file of ten thousand `[`
     * would be a `StackOverflowError` — which is an `Error`, not caught by the fiber's guard, and takes the
     * client rather than the script. 200 is far past any hand-written or machine-written document and far
     * short of the stack.
     */
    private const val MAX_DEPTH = 200

    // ---- reading ------------------------------------------------------------------------------------

    /** Parse [text] whole. Throws [VmError] naming the offset when it is not JSON. */
    fun parse(text: String): Any? = Reader(text).run {
        val v = value(0)
        skipSpace()
        if (!atEnd()) fail("unexpected text after the value")
        v
    }

    private class Reader(private val src: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= src.length

        fun fail(what: String): Nothing =
            throw VmError("not JSON at character $i: $what")

        fun skipSpace() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested more than $MAX_DEPTH deep")
            skipSpace()
            if (atEnd()) fail("the text ended where a value was expected")
            return when (val c = src[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("'$c' starts no value")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!src.startsWith(word, i)) fail("expected '$word'")
            i += word.length
            return value
        }

        private fun obj(depth: Int): LinkedHashMap<Any?, Any?> {
            i++ // '{'
            val out = LinkedHashMap<Any?, Any?>()
            skipSpace()
            if (!atEnd() && src[i] == '}') { i++; return out }
            while (true) {
                skipSpace()
                if (atEnd() || src[i] != '"') fail("a key must be a quoted string")
                val key = string()
                skipSpace()
                if (atEnd() || src[i] != ':') fail("expected ':' after the key")
                i++
                out[key] = value(depth + 1)
                skipSpace()
                if (atEnd()) fail("the text ended inside an object")
                when (src[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): ArrayList<Any?> {
            i++ // '['
            val out = ArrayList<Any?>()
            skipSpace()
            if (!atEnd() && src[i] == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                skipSpace()
                if (atEnd()) fail("the text ended inside an array")
                when (src[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++ // '"'
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("the text ended inside a string")
                when (val c = src[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (atEnd()) fail("the text ended after a backslash")
                        when (val e = src[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= src.length) fail("a \\u escape needs four hex digits")
                                val hex = src.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("'$hex' is not four hex digits")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("'\\$e' is not an escape")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        /**
         * A number, and the one place the INT/FLOAT split is decided.
         *
         * By SPELLING, not by value: `1` is an Int and `1.0` is a Float even though they are the same
         * quantity. The alternative — narrow whenever the fraction is zero — would make a file's schema
         * depend on the data in it, so a `FLOAT` field would parse as an INT on the run where the value
         * happened to be round and stop matching its own declaration.
         */
        private fun number(): Any {
            val start = i
            if (!atEnd() && src[i] == '-') i++
            while (!atEnd() && src[i] in '0'..'9') i++
            var real = false
            if (!atEnd() && src[i] == '.') {
                real = true
                i++
                while (!atEnd() && src[i] in '0'..'9') i++
            }
            if (!atEnd() && (src[i] == 'e' || src[i] == 'E')) {
                real = true
                i++
                if (!atEnd() && (src[i] == '+' || src[i] == '-')) i++
                while (!atEnd() && src[i] in '0'..'9') i++
            }
            val text = src.substring(start, i)
            if (text.isEmpty() || text == "-") fail("'$text' is not a number")
            if (real) return text.toDoubleOrNull() ?: fail("'$text' is not a number")
            val long = text.toLongOrNull()
                // Past Long, the honest answer is the Double — refusing would reject a file that is
                // perfectly good JSON over a value the language has no exact form for either way.
                ?: return text.toDoubleOrNull() ?: fail("'$text' is not a number")
            return if (long in INT_RANGE) long.toInt() else long
        }
    }

    private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

    // ---- writing ------------------------------------------------------------------------------------

    /**
     * [value] as JSON text.
     *
     * [pretty] indents two spaces per level and puts one entry to a line — for a file a person will open.
     * Compact is one line, for a file only a program reads.
     */
    fun write(value: Any?, pretty: Boolean = true): String =
        StringBuilder().also { writeInto(it, value, pretty, 0) }.toString()

    private fun writeInto(sb: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        if (depth > MAX_DEPTH) throw VmError("this value nests more than $MAX_DEPTH deep")
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Int, is Long -> sb.append(value)
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                // Neither is JSON, and both arrive from ordinary arithmetic — a divide by zero, a sqrt of
                // a negative. Naming the value beats writing `NaN`, which no parser on the other side
                // will read back.
                if (d.isNaN() || d.isInfinite()) throw VmError("$d cannot be written as JSON")
                // Whole doubles keep their `.0`, so a FLOAT field round-trips as a FLOAT. See [number].
                sb.append(if (d == Math.floor(d) && !d.isInfinite()) "$d" else d.toString())
            }
            is String -> quote(sb, value)
            is StructValue -> writeEntries(sb, (0 until value.size).map { value.names[it] to value[it] }, pretty, depth)
            is Map<*, *> -> writeEntries(sb, value.entries.map { keyText(it.key) to it.value }, pretty, depth)
            is List<*> -> {
                if (value.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                value.forEachIndexed { n, v ->
                    if (n > 0) sb.append(',')
                    newline(sb, pretty, depth + 1)
                    writeInto(sb, v, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append(']')
            }
            // Everything else is a host value with no JSON form — an entity, a widget, a function. Its
            // `toString` would be a handle, which reads like data and is not, so this refuses instead.
            else -> throw VmError("a ${dev.ziggle.vscript.vm.Values.typeName(value)} cannot be written as JSON")
        }
    }

    private fun writeEntries(
        sb: StringBuilder,
        entries: List<Pair<String, Any?>>,
        pretty: Boolean,
        depth: Int,
    ) {
        if (entries.isEmpty()) { sb.append("{}"); return }
        sb.append('{')
        entries.forEachIndexed { n, (k, v) ->
            if (n > 0) sb.append(',')
            newline(sb, pretty, depth + 1)
            quote(sb, k)
            sb.append(':')
            if (pretty) sb.append(' ')
            writeInto(sb, v, pretty, depth + 1)
        }
        newline(sb, pretty, depth)
        sb.append('}')
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    /** A map key as text. JSON has only string keys, so an INT key writes as its digits and reads back so. */
    private fun keyText(key: Any?): String = key?.toString() ?: "null"

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
