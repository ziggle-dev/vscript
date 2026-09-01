package dev.ziggle.vscript.editor.text

/**
 * Where every line of a source string starts — the text core's foundation.
 *
 * ### What it is for
 *
 * Three things the code view needs constantly are all the same question in disguise: which line is this
 * caret on, where does line N begin, and how many lines are there. Without an index each is a scan, and
 * the code view was doing two of them **per frame**: `edit.text.count { it == '\n' } + 1` to size the
 * gutter, and `CodeBuffer.on(line)` filtering every diagnostic once per visible row.
 *
 * It is also what the rest of phase 6 rests on. A breakpoint is a LINE, and a line has to survive the text
 * around it being edited; a marker in the gutter is a line; folding is a range of lines. None of those can
 * be built on `TextLayout`, which describes *visual* rows and belongs to whoever is drawing.
 *
 * ### Lines and columns are 1-BASED, and that is not a style choice
 *
 * `Span` is 1-based because `Lexer` starts at `line = 1`, and every diagnostic the compiler produces is a
 * `Span`. An index that counted from 0 would need a conversion at every boundary between this and a
 * diagnostic, a breakpoint or the gutter — and the bug that conversion eventually produces is off-by-one
 * on the line a red squiggle lands on, which reads as the compiler being wrong about where the error is.
 *
 * Offsets stay 0-based, because they index a `String`.
 *
 * ### Rebuilt, not patched
 *
 * [of] walks the text once. An incremental version that adjusts the starts after an edit is possible and
 * is not worth it here: a script is a few hundred lines, the walk is a single pass over a string already
 * in cache, and it happens on EDIT rather than on frame. The version that is easy to be sure of wins,
 * which is the same argument `TextHistory` makes for snapshots over a command log.
 */
class LineIndex private constructor(
    /** Offset of the first character of each line. `starts[0]` is line 1, and is always 0. */
    private val starts: IntArray,
    /** Length of the text this was built from — the one-past-the-end offset. */
    val length: Int,
) {

    val lineCount: Int get() = starts.size

    /** The line [offset] falls on, 1-based. Clamped, so a stale caret cannot throw. */
    fun lineAt(offset: Int): Int {
        val at = offset.coerceIn(0, length)
        // Binary search for the last start <= at. `binarySearch` gives the insertion point negated when
        // absent, which is one past the line we want.
        val hit = starts.binarySearch(at)
        return if (hit >= 0) hit + 1 else -hit - 1
    }

    /** Offset of the first character of [line] (1-based). Clamped. */
    fun startOf(line: Int): Int = starts[(line - 1).coerceIn(0, starts.size - 1)]

    /**
     * Offset one past the last character of [line], **not counting the newline**.
     *
     * So `endOf(n) - startOf(n)` is the length of the line as it reads, and a caret at `endOf(n)` is at
     * the end of the text rather than at the start of the next line — which is where `End` should put it.
     */
    fun endOf(line: Int): Int {
        val i = (line - 1).coerceIn(0, starts.size - 1)
        return if (i + 1 < starts.size) starts[i + 1] - 1 else length
    }

    /** The column [offset] falls on, 1-based — matching [dev.ziggle.vscript.lang.Span]. */
    fun columnAt(offset: Int): Int = offset.coerceIn(0, length) - startOf(lineAt(offset)) + 1

    /** The offset of (1-based) [line], [column]; both clamped to something that exists. */
    fun offsetAt(line: Int, column: Int): Int {
        val start = startOf(line)
        return (start + (column - 1).coerceAtLeast(0)).coerceAtMost(endOf(line))
    }

    /** [line] of [text], without its newline. [text] must be what this index was built from. */
    fun textOf(text: String, line: Int): String =
        text.substring(startOf(line).coerceAtMost(text.length), endOf(line).coerceIn(0, text.length))

    companion object {
        fun of(text: String): LineIndex {
            val starts = ArrayList<Int>(text.length / 32 + 4)
            starts.add(0)
            for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
            return LineIndex(starts.toIntArray(), text.length)
        }
    }
}
