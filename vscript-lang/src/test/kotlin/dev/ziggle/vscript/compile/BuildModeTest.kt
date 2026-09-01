package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.vm.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Debug and release builds — what each one owes, at the level the difference actually lives.
 *
 * The split is the ordinary one: a debug build carries the apparatus a debugger needs, and a release build
 * does not pay for it. Two things follow, and both are load-bearing rather than cosmetic:
 *
 *  - `Op.TRACE` markers are what a breakpoint fires on. No markers, no breakpoints.
 *  - A debug build gives every **authored pin** its own constant slot so its value can be rewritten while
 *    the script runs. A release build pools by value like any other compiler, which is what makes a literal
 *    NODE feeding several pins indistinguishable from the same value typed into each of them — the last
 *    thing a text round trip could change about a script without changing what it does.
 *
 * That they still *run the same* is asserted in `DifferentialTest`, which drives both side by side. Here is
 * only the shape of each.
 */
class BuildModeTest {

    private val catalog = dev.ziggle.vscript.model.NodeCatalog()

    /** One literal node wired into two pins — the shape the pooling difference is about. */
    private fun sharedLiteral(): dev.ziggle.vscript.model.Graph {
        val g = GraphBuilder("shared")
        val entry = g.node(BuiltinNodes.ENTRY)
        val lit = g.node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 600))
        val a = g.node(BuiltinNodes.DELAY)
        val b = g.node(BuiltinNodes.DELAY)
        g.link(entry, "Exec", a, "Exec")
        g.link(a, "Exec", b, "Exec")
        g.link(lit, "Value", a, "Ms")
        g.link(lit, "Value", b, "Ms")
        return g.build()
    }

    private fun entryOf(g: dev.ziggle.vscript.model.Graph) = g.entries(catalog).single().id

    @Test
    fun `a debug build emits trace markers and a release build does not`() {
        val g = sharedLiteral()
        fun traces(debug: Boolean): Int {
            val c = GraphCompiler(catalog, debug = debug).compile(g, entryOf(g))
            return (0 until c.size).count { c.code[it * 4] == Op.TRACE }
        }
        assertTrue(traces(debug = true) > 0, "a debug build must mark nodes for the debugger")
        assertEquals(0, traces(debug = false), "a release build must not pay for markers nobody reads")
    }

    @Test
    fun `only a debug build can rewrite a literal while the script runs`() {
        val g = sharedLiteral()

        val debug = GraphCompiler(catalog, debug = true).compile(g, entryOf(g))
        val lit = g.nodes.single { it.type == BuiltinNodes.LITERAL_INT }
        assertTrue(debug.setLiteral(lit.id, "Value", 1), "a debug build owns a slot per authored pin")

        val release = GraphCompiler(catalog, debug = false).compile(g, entryOf(g))
        assertTrue(release.literalSlots.isEmpty(), "a release build keeps no per-pin slots")
        assertFalse(
            release.setLiteral(lit.id, "Value", 1),
            "a release build must REFUSE the edit — pooled by value, the write would land on every pin " +
                "that happened to share it",
        )
    }

    /**
     * The point of interning, stated as the thing it buys.
     *
     * One literal node feeding two pins is one knob in a debug build; the same value typed into each pin is
     * two. In a release build both are one pooled constant, so the distinction — the only remaining way a
     * graph and its printed text could differ without the program differing — stops existing.
     */
    @Test
    fun `a release build pools equal constants however they were authored`() {
        val shared = sharedLiteral()

        val typedIn = GraphBuilder("typed").let { g ->
            val entry = g.node(BuiltinNodes.ENTRY)
            val a = g.node(BuiltinNodes.DELAY, literals = mapOf("Ms" to 600))
            val b = g.node(BuiltinNodes.DELAY, literals = mapOf("Ms" to 600))
            g.link(entry, "Exec", a, "Exec")
            g.link(a, "Exec", b, "Exec")
            g.build()
        }

        val one = GraphCompiler(catalog, debug = false).compile(shared, entryOf(shared))
        val two = GraphCompiler(catalog, debug = false).compile(typedIn, entryOf(typedIn))
        assertEquals(
            one.constants.toList(), two.constants.toList(),
            "a shared literal node and the same value typed into each pin must pool identically",
        )

        // And in a debug build they genuinely differ — one knob versus two — which is why the release build
        // has to be the one that interns.
        val dbg = GraphCompiler(catalog, debug = true).compile(typedIn, entryOf(typedIn))
        assertEquals(2, dbg.literalSlots.size, "each authored pin is separately editable in a debug build")
    }
}
