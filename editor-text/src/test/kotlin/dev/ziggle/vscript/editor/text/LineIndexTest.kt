package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.Lexer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The line index, and the one property that actually matters: **it agrees with the compiler.**
 *
 * Everything the index is for lands next to a `Span` — a diagnostic's squiggle, a breakpoint, a gutter
 * marker. An index that were internally consistent but disagreed with `Lexer` by one would put every red
 * underline on the wrong line, and would look like the compiler reporting the wrong position rather than
 * like an editor counting wrong. So the last test here does not assert against hand-written numbers; it
 * asserts against the lexer's own line for every token in a real snippet.
 */
class LineIndexTest {

    @Test
    fun `an empty string is one line`() {
        // Not zero: a document with nothing in it still has a line 1 for the caret to sit on, and a gutter
        // that showed no numbers at all for a new file would be a strange first impression.
        val ix = LineIndex.of("")
        assertEquals(1, ix.lineCount)
        assertEquals(1, ix.lineAt(0))
        assertEquals(0, ix.startOf(1))
        assertEquals(0, ix.endOf(1))
    }

    @Test
    fun `a trailing newline opens a line, because the caret can go there`() {
        // "a\n" is two lines: after pressing Enter at the end you are on line 2, and the gutter has to
        // show it. Counting separators instead of starts is the version that gets this wrong.
        val ix = LineIndex.of("a\n")
        assertEquals(2, ix.lineCount)
        assertEquals(2, ix.lineAt(2))
        assertEquals(2, ix.startOf(2))
    }

    @Test
    fun `offsets map to lines and back`() {
        val text = "one\ntwo\nthree"
        val ix = LineIndex.of(text)
        assertEquals(3, ix.lineCount)

        assertEquals(1, ix.lineAt(0))
        assertEquals(1, ix.lineAt(3))   // the newline itself belongs to the line it ends
        assertEquals(2, ix.lineAt(4))
        assertEquals(3, ix.lineAt(text.length))

        assertEquals("one", ix.textOf(text, 1))
        assertEquals("two", ix.textOf(text, 2))
        assertEquals("three", ix.textOf(text, 3))
    }

    @Test
    fun `the end of a line is before its newline`() {
        // So `End` puts the caret after the last character rather than at the start of the next line, and
        // `endOf - startOf` is the length the line reads as.
        val text = "one\ntwo"
        val ix = LineIndex.of(text)
        assertEquals(3, ix.endOf(1))
        assertEquals(3, ix.endOf(1) - ix.startOf(1))
        assertEquals(7, ix.endOf(2))
    }

    @Test
    fun `line and column round-trip through offset`() {
        val text = "alpha\nbeta\n\ndelta"
        val ix = LineIndex.of(text)
        for (off in 0..text.length) {
            val line = ix.lineAt(off)
            val col = ix.columnAt(off)
            assertEquals(off, ix.offsetAt(line, col), "offset $off -> $line:$col -> back")
        }
    }

    @Test
    fun `out-of-range input is clamped rather than thrown`() {
        // A caret can outlive the text it was measured against by one frame — an undo, a reload, a
        // document swap. Throwing there takes the editor down for a transient.
        val ix = LineIndex.of("a\nb")
        assertEquals(1, ix.lineAt(-5))
        assertEquals(2, ix.lineAt(9_999))
        assertEquals(0, ix.startOf(-1))
        assertEquals(3, ix.endOf(99))
    }

    /** The property the rest of phase 6 depends on. */
    @Test
    fun `it agrees with the lexer's own line for every token`() {
        val src = """
            graph "probe"

            // a comment
            var Count: INT = 0

            on start {
                Count = Count + 1
                log("hello")
            }
        """.trimIndent()
        val ix = LineIndex.of(src)
        val tokens = Lexer(src).lex()
        assertTrue(tokens.size > 10, "expected a real token stream, got ${tokens.size}")

        for (t in tokens) {
            val span = t.span
            if (span.line == 0) continue // synthesised, no source position
            assertEquals(
                span.line, ix.lineAt(span.start),
                "token '${t.text}' at offset ${span.start}: lexer says line ${span.line}",
            )
            assertEquals(
                span.col, ix.columnAt(span.start),
                "token '${t.text}' at offset ${span.start}: lexer says col ${span.col}",
            )
        }
    }
}
