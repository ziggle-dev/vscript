package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What an editor can know about a file that does not compile.
 *
 * `compile` and `resolve` are funnels — a parse error means there is no program worth resolving, and they
 * return nothing. That is right for a compiler and useless for a code view, where the file is broken on
 * almost every keystroke and the moment somebody most wants to know what is in scope is the moment they
 * are halfway through typing a name.
 *
 * So these tests all ask the same question of deliberately broken files: **is there still a resolution,
 * and does it know about the parts that were fine?**
 */
class AnalyseTest {

    private fun front() = TextFrontEnd(NodeCatalog().natives(), rootRef = "probe")

    private val GOOD = """
        graph "probe"

        var Count: INT = 0

        fn helper(n: INT) -> INT = n + 1

        on start {
            Count = helper(n: 1)
        }
    """.trimIndent()

    @Test
    fun `a clean file resolves completely`() {
        val a = front().analyse(GOOD)
        assertTrue(a.complete, "should be complete: ${a.errors.map { it.message }}")
        assertEquals(emptyList(), a.errors)
        assertNotNull(a.resolution)
    }

    // ---- a broken declaration ----------------------------------------------------------------------

    /**
     * The case the whole thing is for.
     *
     * One declaration is unfinished. The parser resynchronises at the next declaration boundary, so
     * everything else in the file was understood — and an editor should be able to offer `helper` and
     * `Count` while the author is still typing the broken one.
     */
    @Test
    fun `a broken declaration does not cost the rest of the file`() {
        val src = """
            graph "probe"

            var Count: INT = 0

            fn helper(n: INT) -> INT = n + 1

            fn broken( {

            on start {
                Count = 1
            }
        """.trimIndent()

        val a = front().analyse(src)
        assertFalse(a.complete, "the file is broken; it must not claim otherwise")
        assertTrue(a.errors.isNotEmpty(), "and it must say so")

        val r = assertNotNull(a.resolution, "no resolution at all -- the recovered tree was thrown away")
        assertTrue("helper" in r.exports.keys || r.document.decls.isNotEmpty(), "nothing survived recovery")
        // The declarations either side of the broken one are still there.
        val names = r.document.decls.mapNotNull { d ->
            (d as? dev.ziggle.vscript.lang.FnDecl)?.name ?: (d as? dev.ziggle.vscript.lang.VarDecl)?.name
        }
        assertTrue("helper" in names, "the function before the break was lost: $names")
    }

    @Test
    fun `only the first failing stage is reported`() {
        // Otherwise a broken file shows the parse error AND everything the resolver then thinks of the
        // wreckage -- forty squiggles for one mistake.
        val src = """
            graph "probe"

            fn broken( {

            on start { nothingIsCalledThis() }
        """.trimIndent()
        val a = front().analyse(src)
        assertTrue(a.errors.isNotEmpty())
        assertTrue(
            a.errors.none { "nothingIsCalledThis" in it.message },
            "a resolver complaint leaked past a parse error: ${a.errors.map { it.message }}",
        )
    }

    // ---- a broken token ----------------------------------------------------------------------------

    /**
     * An unclosed quote is always one keystroke away, and the lexer throws rather than collecting.
     *
     * Re-lexing the prefix is what keeps the rest of the file knowable. Without it, typing `"` anywhere
     * in a document takes every name in it out of scope until the closing quote arrives.
     */
    @Test
    fun `an unclosed string still leaves the file above it knowable`() {
        val src = """
            graph "probe"

            var Count: INT = 0

            fn helper(n: INT) -> INT = n + 1

            on start {
                log("unterminated
            }
        """.trimIndent()

        val a = front().analyse(src)
        assertFalse(a.complete)
        assertTrue(a.errors.isNotEmpty(), "the unclosed string must be reported")

        val r = assertNotNull(a.resolution, "an unclosed quote took the whole file out of scope")
        val names = r.document.decls.mapNotNull { d ->
            (d as? dev.ziggle.vscript.lang.FnDecl)?.name ?: (d as? dev.ziggle.vscript.lang.VarDecl)?.name
        }
        assertTrue("helper" in names, "declarations before the bad token were lost: $names")
        assertTrue("Count" in names, "declarations before the bad token were lost: $names")
    }

    @Test
    fun `nonsense does not throw`() {
        // The contract is best-effort, and a keystroke must never propagate an exception.
        for (src in listOf("", "{", "\"", "graph", "fn", "((((", "on start {", "\u0000")) {
            val a = front().analyse(src)
            assertFalse(a.complete && a.errors.isNotEmpty(), "inconsistent verdict for <<$src>>")
        }
    }

    @Test
    fun `an empty file is not an error`() {
        val a = front().analyse("")
        assertEquals(emptyList(), a.errors, "an empty buffer is a document nobody has typed in yet")
    }
}
