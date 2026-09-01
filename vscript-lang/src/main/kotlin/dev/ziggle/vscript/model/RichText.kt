package dev.ziggle.vscript.model

/**
 * How a run of text is drawn. Null colours mean "whatever the caller was going to use".
 *
 * Colours are plain `0xRRGGBB`, not packed draw-list values: this is the model side, and the packing order
 * is a property of the renderer. See `RichTextDraw`.
 */
class TextStyle(
    val color: Int? = null,
    val background: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
) {
    val isPlain: Boolean
        get() = color == null && background == null && !bold && !italic && !underline && !strike

    fun with(
        color: Int? = this.color,
        background: Int? = this.background,
        bold: Boolean = this.bold,
        italic: Boolean = this.italic,
        underline: Boolean = this.underline,
        strike: Boolean = this.strike,
    ) = TextStyle(color, background, bold, italic, underline, strike)

    companion object {
        val PLAIN = TextStyle()
    }
}

/** One run of text drawn with one style. */
class TextSpan(val text: String, val style: TextStyle)

object RichText {

    /** Longest tag body we will consider, so a stray `<` cannot scan to the end of a long message. */
    private const val MAX_TAG = 24

    /**
     * Split [text] into styled runs.
     *
     * Never fails and never drops input: every character of [text] that is not part of a tag this
     * understands appears in exactly one span.
     */
    fun parse(text: String): List<TextSpan> {
        val out = ArrayList<TextSpan>()
        val stack = ArrayList<TextStyle>()
        var style = TextStyle.PLAIN
        val buf = StringBuilder()
        var i = 0

        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(TextSpan(buf.toString(), style))
                buf.setLength(0)
            }
        }

        while (i < text.length) {
            val c = text[i]
            if (c == '<' && i + 1 < text.length && text[i + 1] == '<') {
                buf.append('<'); i += 2; continue
            }
            if (c != '<') {
                buf.append(c); i++; continue
            }
            val close = text.indexOf('>', i + 1)
            if (close < 0 || close - i > MAX_TAG) {
                buf.append(c); i++; continue
            }
            val tag = text.substring(i + 1, close).trim()
            val applied = apply(tag, style, stack)
            if (applied == null) {
                // Not a tag we know. It is text, and it keeps its angle brackets.
                buf.append(c); i++; continue
            }
            flush()
            style = applied
            i = close + 1
        }
        flush()
        return out
    }

    /**
     * The style after [tag], or null when it is not a tag at all.
     *
     * [stack] carries the styles to return to, so nesting closes in the right order — `</>` and `</col>`
     * both pop, because tracking *which* tag each close referred to would make `<b><col=f00>x</b></col>`
     * an error rather than something that renders.
     */
    private fun apply(tag: String, style: TextStyle, stack: MutableList<TextStyle>): TextStyle? {
        if (tag.startsWith("/")) {
            val what = tag.drop(1).lowercase()
            if (what.isNotEmpty() && what !in CLOSEABLE) return null
            // Popping an empty stack is a close with no open — text, not a crash.
            return if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)
        }
        val eq = tag.indexOf('=')
        val name = (if (eq < 0) tag else tag.substring(0, eq)).lowercase()
        val value = if (eq < 0) null else tag.substring(eq + 1)
        val next = when (name) {
            "col", "color", "colour" -> style.with(color = rgb(value) ?: return null)
            "bg", "background" -> style.with(background = rgb(value) ?: return null)
            "b", "bold" -> style.with(bold = true)
            "i", "italic", "em" -> style.with(italic = true)
            "u", "underline" -> style.with(underline = true)
            "str", "s", "strike" -> style.with(strike = true)
            else -> return null
        }
        stack.add(style)
        return next
    }

    private val CLOSEABLE = setOf(
        "col", "color", "colour", "bg", "background",
        "b", "bold", "i", "italic", "em", "u", "underline", "str", "s", "strike",
    )

    /** `ff0000` or `#ff0000` -> 0xFF0000. Null when it is not six hex digits, which makes the tag text. */
    private fun rgb(value: String?): Int? {
        val v = value?.trim()?.removePrefix("#") ?: return null
        if (v.length != 6 || !v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return v.toIntOrNull(16)
    }

    /**
     * [text] with the formatting removed — what it *says*.
     *
     * Needed wherever the string is used as data rather than drawn: a tooltip, a search, a copy to the
     * clipboard. Without it those show the tags, which is how a formatting feature turns into a bug report
     * about the log being full of angle brackets.
     */
    fun plain(text: String): String = buildString {
        for (span in parse(text)) append(span.text)
    }

    /** True when [text] carries any formatting, so a caller can skip the styled path entirely. */
    fun isFormatted(text: String): Boolean {
        val spans = parse(text)
        return spans.size > 1 || spans.any { !it.style.isPlain }
    }
}
