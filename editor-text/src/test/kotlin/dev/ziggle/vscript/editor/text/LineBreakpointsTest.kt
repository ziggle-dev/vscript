package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.Span
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.vm.ProgramBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Breakpoints by line, and whether they land where the author clicked.
 *
 * The interesting half is not the set arithmetic — it is [LineBreakpoints.resolve] against a **real**
 * compilation. Hand-written spans would let this pass while the mapping was wrong about the one thing it
 * exists to get right, so the tests below compile actual source through `TextFrontEnd` and ask its own
 * `Sites` table where things are.
 */
class LineBreakpointsTest {

    /** Site id → span, exactly as `ScriptRuntime.textSpans` publishes it for a running script. */
    private fun spansOf(src: String): Map<Int, Span> {
        val front = TextFrontEnd(
            natives = NodeCatalog().natives(),
            program = ProgramBuilder(),
            imports = dev.ziggle.vscript.text.TextSource.NONE,
            debug = true,
            rootRef = "probe",
        )
        val c = front.compile(src)
        assertTrue(c.ok, "the fixture must compile: ${c.errors.map { it.message }}")
        return front.sites.spans()
    }

    private val SRC = """
        graph "probe"

        var Count: INT = 0

        on start {
            Count = 1
            log("one")
            log("two")
        }
    """.trimIndent()

    // ---- the set ----------------------------------------------------------------------------------

    @Test
    fun `toggle arms and disarms`() {
        val b = LineBreakpoints()
        assertTrue(b.isEmpty)
        assertTrue(b.toggle(5))
        assertTrue(5 in b)
        assertFalse(b.toggle(5))
        assertFalse(5 in b)
        assertTrue(b.isEmpty)
    }

    @Test
    fun `set states it rather than flipping it`() {
        // A remote caller cannot see the gutter: `break 5` twice must leave line 5 armed.
        val b = LineBreakpoints()
        b.set(5, true)
        b.set(5, true)
        assertTrue(5 in b)
        b.set(5, false)
        assertFalse(5 in b)
    }

    @Test
    fun `restore drops nonsense rather than storing it`() {
        val b = LineBreakpoints()
        b.restore(listOf(3, 0, -1, 7))
        assertEquals(setOf(3, 7), b.lines)
    }

    // ---- the mapping, against a real compilation --------------------------------------------------

    @Test
    fun `armable lines are the ones the compiler has a site on`() {
        val spans = spansOf(SRC)
        val armable = LineBreakpoints().armable(spans)

        assertTrue(armable.isNotEmpty(), "a compiled program should have sites somewhere")
        // Line 1 is `graph "probe"` — a header, not a statement. Nothing can stop there.
        assertFalse(1 in armable, "the graph header is not somewhere to stop")
        // Every armable line is a line that exists in the source.
        val lineCount = LineIndex.of(SRC).lineCount
        armable.forEach { assertTrue(it in 1..lineCount, "site on line $it, but the file has $lineCount") }
    }

    @Test
    fun `an armed line resolves to a site on that line`() {
        val spans = spansOf(SRC)
        val b = LineBreakpoints()
        val target = b.armable(spans).first()
        b.toggle(target)

        val resolved = b.resolve(spans)
        assertEquals(setOf(target), resolved.keys)
        val site = resolved.getValue(target)
        assertEquals(
            target, spans.getValue(site).line,
            "the breakpoint resolved to a site on a different line -- exactly what this must never do",
        )
    }

    @Test
    fun `the first site on a line wins`() {
        // `log(a) ; log(b)` compiles to several sites on one line. Stopping at the earliest is what
        // "break here" means reading left to right.
        val src = """
            graph "probe"

            on start {
                log("a") log("b")
            }
        """.trimIndent()
        val spans = spansOf(src)
        val line = spans.values.groupBy { it.line }.entries.firstOrNull { it.value.size > 1 }?.key
            ?: return // the fixture did not produce a multi-site line; nothing to assert
        val b = LineBreakpoints().also { it.toggle(line) }

        val site = b.resolve(spans).getValue(line)
        val earliest = spans.values.filter { it.line == line }.minOf { it.start }
        assertEquals(earliest, spans.getValue(site).start, "a later site on the line was chosen")
    }

    @Test
    fun `a line with no site is dropped rather than throwing`() {
        // Reachable through a restored sidecar: the file was edited since the breakpoint was saved.
        val spans = spansOf(SRC)
        val b = LineBreakpoints()
        b.restore(listOf(9_999))
        assertEquals(emptyMap(), b.resolve(spans))
    }

    @Test
    fun `nothing armed resolves to nothing`() {
        assertEquals(emptyMap(), LineBreakpoints().resolve(spansOf(SRC)))
    }

    // ---- the two compilations must agree -----------------------------------------------------------

    /**
     * **The invariant the whole feature rests on.**
     *
     * The gutter decides what is clickable from `CodeBuffer.armableLines`, which comes from the buffer's
     * own compile — `debug = false`, run on every keystroke. The breakpoint is resolved much later against
     * the compilation that actually runs, which is `debug = true`. Two different compilations of the same
     * text.
     *
     * If the debug one had FEWER lines with sites, some line the gutter offered would resolve to nothing
     * and the author would get a dot that never fires — which reads as a broken debugger, not as a stale
     * mapping. So: every armable line must be resolvable.
     */
    @Test
    fun `every line the gutter offers can actually be armed`() {
        val catalog = NodeCatalog()

        fun sitesWith(debug: Boolean): Map<Int, Span> {
            val front = TextFrontEnd(
                natives = catalog.natives(),
                program = ProgramBuilder(),
                imports = dev.ziggle.vscript.text.TextSource.NONE,
                debug = debug,
                rootRef = "probe",
            )
            val c = front.compile(SRC)
            assertTrue(c.ok, "fixture must compile: ${c.errors.map { it.message }}")
            return front.sites.spans()
        }

        val offered = sitesWith(debug = false).values.mapTo(HashSet()) { it.line }
        val runSpans = sitesWith(debug = true)

        val b = LineBreakpoints()
        b.restore(offered)
        val resolved = b.resolve(runSpans)

        assertEquals(
            offered.sorted(), resolved.keys.sorted(),
            "a line the gutter would offer did not resolve against the run's own sites -- that is a " +
                "breakpoint the author can set and which will never fire",
        )
        // ...and each lands on the line it was set on.
        resolved.forEach { (line, site) ->
            assertEquals(line, runSpans.getValue(site).line, "line $line resolved to a site elsewhere")
        }
    }
}
