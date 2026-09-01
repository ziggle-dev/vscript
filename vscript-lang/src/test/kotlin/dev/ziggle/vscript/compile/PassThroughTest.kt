package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A function whose expression body is just a value it was handed.
 *
 * Purity is derived by walking a body, and the rule refused an EMPTY one — an unfinished function must not
 * read as an expression, or pointing a Call at a freshly declared `fn` would take its exec pins off.
 *
 * But `fn id(n: INT) -> INT = n` has no body NODES either: it wires the box's own parameter straight into
 * its own result. Counting nodes called that unfinished, so every call to a pass-through was treated as a
 * step and the validator then reported that nothing runs a value plainly being read — on a function that
 * is as pure as they come.
 *
 * A fed result is the distinction. An unfinished function has nothing feeding its results; this has.
 */
class PassThroughTest {

    private val catalog = NodeCatalog()

    private fun outOf(src: String): Any? {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertEquals(emptyList(), low.errors.map { it.message }, "lowering")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors().map { it.message })
        val entry = low.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false).compile(low.graph, entry.id)
        val result = drive(chunk, BuiltinHosts.registry())
        assertEquals(null, result.fiber.error?.message)
        return result.interpreter.globals.getOrNull(chunk.slots.variables["Out"] ?: -1)
    }

    @Test
    fun `a function that just hands its argument back is an expression`() {
        assertEquals(
            7,
            outOf(
                """
                graph "probe"
                export fn id(n: INT) -> INT = n
                export var Out: INT = 0
                on start { Out = id(7) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `so is one that hands back a constant`() {
        assertEquals(
            42,
            outOf(
                """
                graph "probe"
                export fn answer() -> INT = 42
                export var Out: INT = 0
                on start { Out = answer() }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a function nobody has written yet is still not an expression`() {
        // The case the empty-body rule exists for, and it must survive: declaring an `fn`, wiring a call
        // and filling it in later is the order everyone works in, and a Call that lost its exec pins half
        // way through that would be worse than the warning it replaced.
        //
        // Asked of the rule directly rather than through a diagnostic, because an unfinished function is
        // a WARNING — "nothing feeding it, so it will arrive as null" — and asserting on the absence of
        // an error would pass whatever the purity answer happened to be.
        assertEquals(false, pureIn("""
            graph "probe"
            export fn later() -> INT { }
            export var Out: INT = 0
            on start { Out = later() }
        """.trimIndent(), "later"))
    }

    @Test
    fun `the pass-through cases really are judged pure`() {
        assertEquals(true, pureIn("""
            graph "probe"
            export fn id(n: INT) -> INT = n
            export var Out: INT = 0
            on start { Out = id(7) }
        """.trimIndent(), "id"))

        assertEquals(true, pureIn("""
            graph "probe"
            export fn answer() -> INT = 42
            export var Out: INT = 0
            on start { Out = answer() }
        """.trimIndent(), "answer"))
    }

    /** Ask the rule itself what it thinks of [name]. */
    private fun pureIn(src: String, name: String): Boolean {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { it.message }}")
        val graph = Lower(catalog, source = GraphSource.NONE).lower(parsed.program).graph
        return dev.ziggle.vscript.model.isPureFunction(
            name, catalog, graph.nodes, graph::function, graph.links,
        )
    }
}
