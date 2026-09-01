package dev.ziggle.vscript.model

/**
 * `Baskets full: {count} of {total}` — a message with holes in it.
 *
 * The holes ARE the node's pins. Type the sentence and the pins appear, named after what you called them;
 * delete a hole and its pin goes with it. That is the whole point of doing this rather than shipping a
 * Format node with three anonymous inputs: the alternative to a template is a chain of Add nodes, and by
 * the third one you are reading the wiring to work out what the sentence says.
 *
 * Braces are doubled to escape, as in most template syntaxes — `{{` is a literal `{`.
 */
object Templates {

    /**
     * The placeholder names in [text], in the order they first appear, without repeats.
     *
     * Ordered and de-duplicated because this is the pin list: a name used twice is one pin used twice, and
     * the order is the order they read in, which is the only order an author will expect to find them in.
     */
    fun placeholders(text: String): List<String> {
        val out = LinkedHashSet<String>()
        forEachToken(text) { name, _ -> if (name != null) out += name }
        return out.toList()
    }

    /** [text] with each `{name}` replaced by its value; an absent one renders as `null`, never as nothing. */
    fun render(text: String, values: Map<String, Any?>): String = buildString {
        forEachToken(text) { name, literal ->
            if (name == null) append(literal)
            // "null" rather than an empty string: a message with a hole silently closed up hides the very
            // thing you would want to be told, which is that the value never arrived.
            else append(values[name]?.toString() ?: "null")
        }
    }

    /**
     * Walk [text], handing back either a literal run or a placeholder name.
     *
     * One scanner shared by both jobs, so the pins a node shows and the values it substitutes can never
     * disagree about what counts as a placeholder — which they would, written twice.
     */
    private inline fun forEachToken(text: String, emit: (name: String?, literal: String) -> Unit) {
        val buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{' && i + 1 < text.length && text[i + 1] == '{') {
                buf.append('{'); i += 2; continue
            }
            if (c == '}' && i + 1 < text.length && text[i + 1] == '}') {
                buf.append('}'); i += 2; continue
            }
            if (c == '{') {
                val end = text.indexOf('}', i + 1)
                // An unclosed brace is text, not a broken placeholder. Half a sentence typed so far should
                // not make the node's pins vanish and take their wires with them.
                if (end < 0) { buf.append(text.substring(i)); break }
                val name = text.substring(i + 1, end).trim()
                if (name.isEmpty()) {
                    buf.append(text, i, end + 1)
                } else {
                    if (buf.isNotEmpty()) { emit(null, buf.toString()); buf.setLength(0) }
                    emit(name, "")
                }
                i = end + 1
                continue
            }
            buf.append(c)
            i++
        }
        if (buf.isNotEmpty()) emit(null, buf.toString())
    }
}
