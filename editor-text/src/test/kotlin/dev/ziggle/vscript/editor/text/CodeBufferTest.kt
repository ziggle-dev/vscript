package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.model.NodeCatalog
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The text surface's document: what it holds, and what it says is wrong with it.
 *
 * This had no test. It is the half of the code view that can be got wrong without an ImGui context —
 * which is exactly what its own note says it exists to be — so the absence was the gap, not the risk.
 */
class CodeBufferTest {

    private fun buffer() = CodeBuffer(NodeCatalog())

    private val GOOD = """
        graph "probe"

        on start {
            log("hi")
        }
    """.trimIndent()

    @Test
    fun `a program that compiles reports no errors`() {
        val b = buffer()
        b.load(GOOD)
        assertTrue(b.compiles, "should compile: ${b.errors.map { it.message }}")
        assertEquals(emptyList(), b.errors.map { it.message })
    }

    @Test
    fun `dirty tracks the baseline, not the emptiness`() {
        val b = buffer()
        b.load(GOOD)
        assertFalse(b.dirty, "freshly loaded is not dirty")
        b.text = GOOD + "\n"
        assertTrue(b.dirty)
        b.saved()
        assertFalse(b.dirty, "saving makes what is here what is there")
    }

    // ---- the line index -------------------------------------------------------------------------

    @Test
    fun `the line index follows the text`() {
        val b = buffer()
        b.load("one\ntwo\nthree")
        assertEquals(3, b.lines.lineCount)
        b.text = "one\ntwo\nthree\nfour"
        assertEquals(4, b.lines.lineCount, "the index must be rebuilt on edit, not on frame")
        b.text = "flat"
        assertEquals(1, b.lines.lineCount)
    }

    // ---- diagnostics, bucketed ------------------------------------------------------------------

    /**
     * The behaviour the bucketing has to preserve.
     *
     * `on(line)` used to filter `(errors + warnings)` on every call, and the paint loop calls it once per
     * visible row per frame. Bucketing moves that work to where the diagnostics change — which is only an
     * optimisation if the answers are identical, so this asks the same question both ways.
     */
    @Test
    fun `a diagnostic is reported on its own line and no other`() {
        val b = buffer()
        b.load(
            """
            graph "probe"

            on start {
                nothingIsCalledThis()
            }
            """.trimIndent(),
        )
        assertFalse(b.compiles, "the probe was supposed to be broken")
        val all = b.errors + b.warnings
        assertTrue(all.isNotEmpty(), "expected a diagnostic")

        // Every diagnostic is found on its own line...
        for (d in all) {
            assertTrue(
                d in b.on(d.span.line),
                "'${d.message}' is on line ${d.span.line} but on(${d.span.line}) did not return it",
            )
        }
        // ...and `on` agrees with the filter it replaced, for every line in the document.
        for (line in 1..b.lines.lineCount) {
            assertEquals(
                all.filter { it.span.line == line }, b.on(line),
                "line $line disagrees with the filter this replaced",
            )
        }
    }

    @Test
    fun `a clean line has no diagnostics and does not allocate one`() {
        val b = buffer()
        b.load(GOOD)
        assertEquals(emptyList(), b.on(1))
        // Past the end is a question the paint loop can ask during a resize; it must not throw.
        assertEquals(emptyList(), b.on(9_999))
    }

    @Test
    fun `the buckets are rebuilt when the text changes`() {
        val b = buffer()
        b.load("graph \"probe\"\n\non start {\n    nope()\n}")
        assertTrue(b.errors.isNotEmpty())
        val badLine = b.errors.first().span.line
        assertTrue(b.on(badLine).isNotEmpty())

        b.load(GOOD)
        assertTrue(b.compiles)
        assertEquals(emptyList(), b.on(badLine), "stale buckets survived a reload")
    }

    // ---- semantics survive a broken buffer ---------------------------------------------------------

    /**
     * **The property the IDE features will rest on.**
     *
     * `CodeBuffer` used to call `compile`, which is a funnel: one syntax error and it returns nothing at
     * all. For a compiler that is right — resolving the wreckage of a failed parse produces a second wave
     * of complaints about damage the first one already explained. For a code view it is useless, because
     * the file is broken on almost every keystroke, and the moment somebody most wants to know what is in
     * scope is the moment they are halfway through typing a name.
     */
    @Test
    fun `a broken buffer still knows what the rest of the file means`() {
        val b = buffer()
        b.load(
            """
            graph "probe"

            var Count: INT = 0

            fn helper(n: INT) -> INT = n + 1

            fn broken( {

            on start {
                Count = 1
            }
            """.trimIndent(),
        )

        assertFalse(b.compiles, "the file is broken and must not claim otherwise")
        assertTrue(b.errors.isNotEmpty(), "and it must say so")

        val meaning = b.meaning
        assertTrue(meaning != null, "the recovered tree was thrown away -- no semantics while typing")
        val names = meaning!!.document.decls.mapNotNull { d ->
            (d as? dev.ziggle.vscript.lang.FnDecl)?.name ?: (d as? dev.ziggle.vscript.lang.VarDecl)?.name
        }
        assertTrue("helper" in names, "the declarations either side of the break were lost: $names")
        assertTrue("Count" in names, "the declarations either side of the break were lost: $names")
    }

    @Test
    fun `an unclosed quote does not take the file out of scope`() {
        val b = buffer()
        b.load("graph \"probe\"\n\nvar Count: INT = 0\n\non start {\n    log(\"oops\n}")
        assertFalse(b.compiles)
        val meaning = b.meaning
        assertTrue(meaning != null, "one quote character emptied the whole document")
        val names = meaning!!.document.decls.mapNotNull { d ->
            (d as? dev.ziggle.vscript.lang.VarDecl)?.name
        }
        assertTrue("Count" in names, "declarations before the bad token were lost: $names")
    }

    @Test
    fun `the last good meaning is kept when nothing can be understood`() {
        // Half a keystroke can leave a buffer that yields nothing at all. Flashing the semantics off would
        // make completion blink out mid-word, so the previous answer stands until a better one arrives.
        val b = buffer()
        b.load(GOOD)
        assertTrue(b.meaning != null)
        b.text = "\u0000"
        assertTrue(b.meaning != null, "the semantics were dropped rather than kept")
    }

    // ---- imports ------------------------------------------------------------------------------------

    /**
     * **The buffer resolves imports through the SAME source the run does.**
     *
     * It did not. `CodeView` defaults its `imports` to `TextSource.NONE`, and `ScriptsPanel` built it
     * without one — so every name reached through an alias came back unknown and a document that imports
     * anything was a wall of red, while the very same file RAN perfectly, because `compileText` was
     * resolving against `documents` and the buffer was not.
     *
     * Two answers to "what does `banking` mean" is one too many, and the visible half was the wrong one.
     *
     * The alias form is `import * as name from "ref"`. `import name from "ref"` is a DEFAULT import and
     * reports `no import is called 'name'` when the library does not provide one — which is what the
     * example in the repo's own CLAUDE.md still writes.
     */
    @Test
    fun `a name reached through an import resolves`() {
        val library = dev.ziggle.vscript.text.TextSource.of(
            mapOf(
                "core/util" to """
                    graph "core/util"

                    export fn double(n: INT) -> INT = n * 2
                """.trimIndent(),
            ),
        )
        val b = CodeBuffer(NodeCatalog(), library)
        b.load(
            """
            graph "probe"

            import * as util from "core/util"

            export var Twice: INT = 0

            on start {
                Twice = util::double(n: 21)
            }
            """.trimIndent(),
        )

        assertTrue(
            b.compiles,
            "an imported function did not resolve: ${b.errors.map { it.message }}",
        )
        assertEquals(emptyList(), b.errors.map { it.message })
    }

    @Test
    fun `without a source, the same document reports the import as the problem`() {
        // The control: this is what the editor was showing for every importing document. Asserted so the
        // difference between the two is the SOURCE and nothing else.
        val b = CodeBuffer(NodeCatalog())
        b.load(
            """
            graph "probe"

            import * as util from "core/util"

            on start { log("" + util::double(n: 21)) }
            """.trimIndent(),
        )
        assertFalse(b.compiles)
        assertTrue(b.errors.isNotEmpty())
    }
}
