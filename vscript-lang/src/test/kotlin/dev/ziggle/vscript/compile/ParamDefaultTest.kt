package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.lang.Print
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.DriveResult
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `fn f(x: INT = 5)` — a parameter you may leave out.
 *
 * **The cheaper answer to overloading**, and the reason it is cheap is that nothing new had to be built:
 * a Call node's pins come from the signature, `PinSpec` has carried a default all along, and an argument
 * nobody supplied already falls back to its pin's default when the chunk is compiled. The feature is a
 * field on `FunctionPin`, one line where those pins are built, and the validator no longer complaining
 * that a pin with an answer has none.
 *
 * Overloading would have been the opposite: a name is the identity of a function everywhere here —
 * `Node.callee`, `entryOf`, the sub-chunk table, `isPureFunction` — so two functions sharing one means
 * every one of those needs a mangled key.
 */
class ParamDefaultTest {

    private val catalog = NodeCatalog(dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

    @Test
    fun `an omitted argument takes the default`() {
        val chunk = compile(
            """
            graph "probe"

            export fn scaled(n: INT, by: INT = 10) -> INT = n * by

            export var Given: INT = 0
            export var Left: INT = 0

            on start {
                Given = scaled(n: 3, by: 2)
                Left = scaled(n: 3)
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(6, chunk.variable("Given", run), "the argument that was given wins")
        assertEquals(30, chunk.variable("Left", run), "and the one left out falls back to 10")
    }

    @Test
    fun `every parameter may have one, so a call can take no arguments at all`() {
        // The shape this exists for: one function that answers both `random()` and `random(min, max)`,
        // where overloading would have needed two.
        val chunk = compile(
            """
            graph "probe"

            export fn between(min: INT = 0, max: INT = 100) -> INT = min + max

            export var Both: INT = 0
            export var Neither: INT = 0

            on start {
                Both = between(min: 1, max: 2)
                Neither = between()
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(3, chunk.variable("Both", run))
        assertEquals(100, chunk.variable("Neither", run))
    }

    @Test
    fun `a defaulted parameter is no longer reported as unfed`() {
        // The warning exists because a signature says a parameter's TYPE and never a value to stand in
        // for it. A default IS that value, so the complaint had to stop.
        val graph = lower(
            """
            graph "probe"

            export fn scaled(n: INT, by: INT = 10) -> INT = n * by

            export var Out: INT = 0

            on start {
                Out = scaled(n: 3)
            }
            """.trimIndent(),
        )
        val about = Validator(catalog).validate(graph).filter { it.message.contains("nothing feeding") }
        assertEquals(emptyList(), about.map { it.message })
    }

    @Test
    fun `a parameter with no default is still reported`() {
        val graph = lower(
            """
            graph "probe"

            export fn scaled(n: INT, by: INT) -> INT = n * by

            export var Out: INT = 0

            on start {
                Out = scaled(n: 3)
            }
            """.trimIndent(),
        )
        val about = Validator(catalog).validate(graph).filter { it.message.contains("nothing feeding") }
        assertTrue(about.isNotEmpty(), "a genuinely missing argument still has to be reported")
    }

    @Test
    fun `a default has to be written out`() {
        val src = """
            graph "probe"
            export fn scaled(n: INT, by: INT = 5 * 2) -> INT = n * by
            export var Out: INT = 0
            on start { Out = scaled(n: 1) }
        """.trimIndent()
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { it.message }}")
        val result = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertTrue(
            result.errors.any { it.message.contains("written out") },
            "a signature is document data — there is nowhere in it to hang a computation: ${result.errors.map { it.message }}",
        )
    }

    /**
     * A record's fields DO take a default now — see `RecordDefaultTest`.
     *
     * This used to assert the opposite, and the refusal was never a rule the language needed: a field's
     * default is stored on the same `FunctionPin` a parameter's is, and an unsupplied pin already falls
     * back to it. `single` is what made the gap obvious, since its whole body is a field list.
     */
    @Test
    fun `a record's fields take a default, like a parameter's`() {
        val parsed = Parser(Lexer("""graph "p"  type Point { x: INT = 0 }""").lex()).parse()
        assertTrue(parsed.ok, "unexpected errors: ${parsed.errors.map { it.message }}")
    }

    @Test
    fun `a default round trips`() {
        // Written in the printer's canonical order — variables, then functions — so the comparison is
        // about the DEFAULT surviving rather than about where declarations land.
        val src = """
            graph "probe"

            export var Out: INT = 0

            export fn scaled(n: INT, by: INT = 10) -> INT = n * by

            on start {
                Out = scaled(n: 3)
            }
        """.trimIndent() + "\n"
        assertEquals(src, Print(catalog, source = GraphSource.NONE).print(lower(src)))
    }

    // ---- a default named by a const — GAPS #12 --------------------------------------------------------

    /**
     * An enum COLUMN's default has always accepted a `const`; a parameter's did not, and the two are the
     * same idea. A named constant is exactly what a shared default wants to be — the alternative is writing
     * the number twice and letting the copies drift.
     */
    @Test
    fun `a parameter default may name a const`() {
        val chunk = compile(
            """
            graph "probe"

            export val Base = 10

            export fn scaled(n: INT, by: INT = Base) -> INT = n * by

            export var Left: INT = 0
            export var Given: INT = 0

            on start {
                Left = scaled(n: 3)
                Given = scaled(n: 3, by: 2)
            }
            """.trimIndent(),
        )
        val r = run(chunk)
        assertEquals(30, chunk.variable("Left", r), "the default should fold to the const's value")
        assertEquals(6, chunk.variable("Given", r), "an argument still wins over the default")
    }

    @Test
    fun `a tile const works as a default too`() {
        val chunk = compile(
            """
            graph "probe"

            export val Home = tile(3200, 3200, 0)

            export fn planeOf(t: TILE = Home) -> INT = t.plane

            export var P: INT = 0

            on start { P = planeOf() }
            """.trimIndent(),
        )
        assertEquals(0, chunk.variable("P", run(chunk)))
    }

    /**
     * The stated cost of the fold, pinned so it is a decision rather than a surprise: the printer gives
     * back the VALUE, not the name. An enum row already behaves this way — carrying the spelling would
     * mean a slot on `FunctionPin` for punctuation.
     */
    @Test
    fun `a const default prints back as its value`() {
        val printed = Print(catalog).print(
            lower(
                """
                graph "probe"

                export val Base = 10

                export fn scaled(n: INT, by: INT = Base) -> INT = n * by

                on start { log(message: "" + scaled(n: 1)) }
                """.trimIndent(),
            ),
        )
        assertTrue("by: INT = 10" in printed, "expected the folded value, got:\n$printed")
    }

    /** Still refused: a default has to be a value, and a call is not one. */
    @Test
    fun `a default that has to be worked out is still refused`() {
        val parsed = Parser(Lexer(
            """
            graph "probe"
            export fn now2() -> INT = 2
            export fn scaled(n: INT, by: INT = now2()) -> INT = n * by
            on start { log(message: "" + scaled(n: 1)) }
            """.trimIndent(),
        ).lex()).parse()
        val errors = if (!parsed.ok) parsed.errors.map { it.message }
        else Lower(catalog, source = GraphSource.NONE).lower(parsed.program).errors.map { it.message }
        assertTrue(errors.any { "written out" in it }, "got: $errors")
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun lower(text: String): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val result = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertEquals(emptyList(), result.errors.map { it.message }, "lowering")
        return result.graph
    }

    private fun compile(text: String): Chunk {
        val graph = lower(text)
        assertEquals(emptyList(), Validator(catalog).validate(graph).errors().map { it.message })
        val entry = graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        return GraphCompiler(catalog, debug = false).compile(graph, entry.id)
    }

    private fun run(chunk: Chunk): DriveResult {
        val result = drive(chunk, BuiltinHosts.registry())
        assertEquals(null, result.fiber.error?.message, "the run faulted")
        assertTrue(result.fiber.isFinished, "the run did not complete: ${result.fiber.state}")
        return result
    }

    private fun Chunk.variable(name: String, run: DriveResult): Any? =
        run.interpreter.globals.getOrNull(slots.variables[name] ?: error("no slot for '$name'"))
}
