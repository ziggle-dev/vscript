package dev.ziggle.vscript.editor.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diff that stands in for an edit journal, and the breakpoints that follow it.
 *
 * The single most useful test here is the last one: it does not check line numbers at all, it checks that
 * a breakpoint is still on **the same line of source** after the text around it moves. Numbers are the
 * mechanism; staying attached to the statement is the behaviour.
 */
class TextChangeTest {

    // ---- the diff ---------------------------------------------------------------------------------

    @Test
    fun `identical text is no change`() {
        assertTrue(TextChange.between("abc", "abc").isEmpty)
        assertTrue(TextChange.between("", "").isEmpty)
    }

    @Test
    fun `an insertion is an empty old span`() {
        val c = TextChange.between("ac", "abc")
        assertEquals(1, c.start)
        assertEquals(1, c.oldEnd)
        assertEquals(2, c.newEnd)
    }

    @Test
    fun `a deletion is an empty new span`() {
        val c = TextChange.between("abc", "ac")
        assertEquals(1, c.start)
        assertEquals(2, c.oldEnd)
        assertEquals(1, c.newEnd)
    }

    /**
     * The case that breaks a naive prefix/suffix scan.
     *
     * Both ends agree about the same single `a`: the prefix claims it and so does the suffix, and without
     * a guard the region comes back with `oldEnd < start`. These are one-character edits — the most
     * common thing an editor does.
     */
    @Test
    fun `overlapping prefix and suffix still produce a well-formed region`() {
        for ((old, new) in listOf("aa" to "a", "a" to "aa", "aaa" to "aa", "" to "a", "a" to "")) {
            val c = TextChange.between(old, new)
            assertTrue(c.start <= c.oldEnd, "$old -> $new gave $c")
            assertTrue(c.start <= c.newEnd, "$old -> $new gave $c")
            // And it round-trips: applying the region to `old` must reproduce `new`.
            val rebuilt = old.substring(0, c.start) + new.substring(c.start, c.newEnd) + old.substring(c.oldEnd)
            assertEquals(new, rebuilt, "$old -> $new via $c did not reconstruct")
        }
    }

    @Test
    fun `the region always reconstructs the new text`() {
        val cases = listOf(
            "one\ntwo\nthree" to "one\ntwo\nTHREE",
            "one\ntwo\nthree" to "one\nthree",
            "one\ntwo" to "one\nmiddle\ntwo",
            "" to "graph \"x\"",
            "graph \"x\"" to "",
        )
        for ((old, new) in cases) {
            val c = TextChange.between(old, new)
            val rebuilt = old.substring(0, c.start) + new.substring(c.start, c.newEnd) + old.substring(c.oldEnd)
            assertEquals(new, rebuilt, "$c did not reconstruct")
        }
    }

    // ---- breakpoints following ---------------------------------------------------------------------

    private fun follow(old: String, new: String, armed: Collection<Int>): Set<Int> {
        val b = LineBreakpoints()
        b.restore(armed)
        b.follow(TextChange.between(old, new), LineIndex.of(old), LineIndex.of(new))
        return b.lines
    }

    @Test
    fun `a line inserted above pushes a breakpoint down`() {
        assertEquals(setOf(4), follow("a\nb\nc", "a\nNEW\nb\nc", listOf(3)))
    }

    @Test
    fun `a line deleted above pulls a breakpoint up`() {
        assertEquals(setOf(2), follow("a\nb\nc", "a\nc", listOf(3)))
    }

    @Test
    fun `an edit below leaves it alone`() {
        assertEquals(setOf(1), follow("a\nb\nc", "a\nb\nCCCC", listOf(1)))
    }

    @Test
    fun `editing within a line moves nothing`() {
        assertEquals(setOf(2, 3), follow("a\nb\nc", "a\nBBBB\nc", listOf(2, 3)))
    }

    @Test
    fun `a breakpoint on a deleted line goes with it`() {
        // Rather than sliding to the top of the deleted region, where it would sit on a statement the
        // author never marked.
        assertEquals(setOf(1), follow("a\nb\nc", "a\nc", listOf(1, 2)).minus(2))
        assertTrue(2 !in follow("a\nb\nc", "a\nc", listOf(2)), "the breakpoint outlived its line")
    }

    /** **The behaviour, stated without reference to line numbers.** */
    @Test
    fun `a breakpoint stays on the same source line through edits above it`() {
        val marked = "    log(\"the marked one\")"
        val before = "graph \"p\"\n\non start {\n$marked\n}"
        val armedAt = LineIndex.of(before).let { ix ->
            (1..ix.lineCount).first { ix.textOf(before, it) == marked }
        }

        val edits = listOf(
            // A declaration inserted above the entry.
            before.replaceFirst("on start {", "var Extra: INT = 0\n\non start {"),
            // A blank line removed above it.
            before.replaceFirst("graph \"p\"\n\n", "graph \"p\"\n"),
            // A comment block pushed in above the entry.
            before.replaceFirst("on start {", "// why\n// this exists\non start {"),
        )
        for (after in edits) {
            val moved = follow(before, after, listOf(armedAt))
            assertEquals(1, moved.size, "expected exactly one breakpoint, got $moved")
            val ix = LineIndex.of(after)
            assertEquals(
                marked, ix.textOf(after, moved.first()),
                "the breakpoint came off its statement -- it is now on '${ix.textOf(after, moved.first())}'",
            )
        }
    }

    /**
     * **The limit of using a diff instead of a journal, stated rather than hidden.**
     *
     * Insert two `log(...)` calls above a third and the minimal region is **not unique**: the inserted
     * `\n    log("` is also how the line below begins, so trimming the common prefix and suffix reports an
     * insertion starting *inside* the marked line rather than a pair of whole lines above it. Every
     * line-granular rule then leaves the breakpoint where it was, because the edit began on its line.
     *
     * This is not a bug in [LineBreakpoints.follow] — it is the diff being asked which of two equally
     * valid regions the author meant, which only a real edit journal knows. `TextChange`'s own note says
     * the journal is the better structure and why it is not available; this is what that costs, and it
     * costs it only for an insertion that repeats the text it is inserted next to.
     */
    @Test
    fun `an insertion that repeats its neighbour is ambiguous, and the breakpoint holds its line`() {
        val marked = "    log(\"the marked one\")"
        val before = "graph \"p\"\n\non start {\n$marked\n}"
        val armedAt = 4
        assertEquals(marked, LineIndex.of(before).textOf(before, armedAt))

        val after = before.replaceFirst("on start {", "on start {\n    log(\"a\")\n    log(\"b\")")
        val moved = follow(before, after, listOf(armedAt))

        // It stays on 4 -- which now holds `log("a")`. Asserted, so that a future change to `follow` that
        // improves this is noticed rather than silently reverting.
        assertEquals(setOf(4), moved)
        assertEquals("    log(\"a\")", LineIndex.of(after).textOf(after, 4))
    }
}
