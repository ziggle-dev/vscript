package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Converting between INT and FLOAT, now that neither happens on its own.
 *
 * **INT widens to FLOAT unasked, and the value is really converted.** The permission on its own is what
 * this used to be, and it was not free: nothing changed the value on the way through, so a FLOAT-typed
 * field fed by an INT held an `Int` for the whole run — `"" + p.x` printed `4` where a float had been
 * declared. The permission is unchanged; `Op.TOF` on the boundary is what makes it honest.
 *
 * So every widening test below asserts what the run HOLDS, not merely that it compiled. Asserting the
 * latter would pass against exactly the build this replaced.
 *
 * Narrowing stays explicit, and has four forms rather than one, because "turn this float into an int" has
 * four different right answers and picking one on the author's behalf is how a rounding bug gets written.
 *
 * A whole-number LITERAL in a float slot is a third case: no wire, so no conversion instruction — the
 * compiler stores it as a float instead (`asDeclared`).
 */
class CastingTest {

    private val catalog = NodeCatalog()

    // ---- the conversions compute the right numbers ---------------------------------------------------

    @Test
    fun `each narrowing rounds the way its name says`() {
        val chunk = compile(
            """
            graph "probe"

            export var FloorUp: INT = 0
            export var FloorDown: INT = 0
            export var CeilUp: INT = 0
            export var CeilDown: INT = 0
            export var Trunc: INT = 0
            export var TruncNeg: INT = 0

            on start {
                FloorUp = floor(2.9)
                FloorDown = floor(0 - 2.1)
                CeilUp = ceil(2.1)
                CeilDown = ceil(0 - 2.9)
                Trunc = toInt(2.9)
                TruncNeg = toInt(0 - 2.9)
            }
            """.trimIndent(),
        )
        val run = run(chunk)

        assertEquals(2L, chunk.variable("FloorUp", run), "floor(2.9)")
        assertEquals(-3L, chunk.variable("FloorDown", run), "floor(-2.1) goes DOWN, away from zero")
        assertEquals(3L, chunk.variable("CeilUp", run), "ceil(2.1)")
        assertEquals(-2L, chunk.variable("CeilDown", run), "ceil(-2.9) goes UP, toward zero")
        assertEquals(2L, chunk.variable("Trunc", run), "toInt drops the fraction")
        assertEquals(-2L, chunk.variable("TruncNeg", run), "toInt(-2.9) keeps the sign — this is (int)")
    }

    @Test
    fun `a tie rounds away from zero, in both directions`() {
        // The one genuinely arbitrary choice here, so it is pinned. Neither JDK primitive does it:
        // `Math.round` is half toward +infinity (-2.5 gives -2, which reads as a bug beside round(2.5)
        // == 3) and `kotlin.math.round` is half-to-even (2.5 gives 2).
        val chunk = compile(
            """
            graph "probe"

            export var Up: INT = 0
            export var Down: INT = 0
            export var Below: INT = 0

            on start {
                Up = round(2.5)
                Down = round(0 - 2.5)
                Below = round(2.4)
            }
            """.trimIndent(),
        )
        val run = run(chunk)

        assertEquals(3L, chunk.variable("Up", run), "round(2.5)")
        assertEquals(-3L, chunk.variable("Down", run), "round(-2.5) — Java would say -2")
        assertEquals(2L, chunk.variable("Below", run), "round(2.4)")
    }

    @Test
    fun `toFloat is what turns integer division into real division`() {
        // The reason toFloat is not redundant now that INT no longer widens on a wire: the widening was
        // never about arithmetic, and `a / b` on two INTs has always been integer division.
        val chunk = compile(
            """
            graph "probe"

            export var Whole: INT = 0
            export var Real: FLOAT = 0.0

            on start {
                Whole = 2 / 3
                Real = toFloat(2) / 3
            }
            """.trimIndent(),
        )
        val run = run(chunk)

        assertEquals(0, chunk.variable("Whole", run), "two INTs divide as INTs, however the answer lands")
        val real = chunk.variable("Real", run) as Double
        assertTrue(real > 0.66 && real < 0.67, "toFloat(2) / 3 should be two thirds, got $real")
    }

    /**
     * Arithmetic carries its operands' type, so a whole-number result widens like any other INT.
     *
     * A math node's pins are WILDCARD — one node type serves INT, FLOAT and string concatenation — and a
     * wildcard connects to anything, so `var f: FLOAT = a * b` on two INTs was accepted and stored an
     * `Int`. It was the one hole removing the implicit widening did not close, because there was no
     * declared type on either side for anything to notice.
     *
     * The result type now comes from the operands, using the VM's own promotion rule read a step earlier,
     * and the ordinary INT -> FLOAT conversion does the rest.
     */
    @Test
    fun `arithmetic on two ints reaching a float variable is converted`() {
        val chunk = compile(
            """
            graph "probe"

            export var Widened: FLOAT = 0.0

            on start {
                Widened = 2 * 3
            }
            """.trimIndent(),
        )
        assertEquals(6.0, chunk.variable("Widened", run(chunk)), "6.0, not the Int 6")
    }

    @Test
    fun `a float operand makes the whole expression a float`() {
        val chunk = compile(
            """
            graph "probe"

            export var Half: FLOAT = 0.0
            export var N: INT = 3

            on start {
                Half = N / 2.0
            }
            """.trimIndent(),
        )
        val half = chunk.variable("Half", run(chunk)) as Double
        assertTrue(half > 1.49 && half < 1.51, "3 / 2.0 is 1.5, got $half")
    }

    @Test
    fun `a string operand still makes it concatenation`() {
        // Add is the one arithmetic node that is not always arithmetic, and the promotion rule has to
        // agree with the VM about that or every log message stops type-checking.
        val chunk = compile(
            """
            graph "probe"

            export var Text: STRING = ""

            on start {
                Text = "found " + 3
            }
            """.trimIndent(),
        )
        assertEquals("found 3", chunk.variable("Text", run(chunk)))
    }

    // ---- but a literal still fits, and arrives as the type it was written into -------------------------

    @Test
    fun `a whole number written into a float slot is legal, and is stored as a float`() {
        // Legal because `4` has no type until it is placed. Stored as 4.0 because the declared type has to
        // be TRUE of the value — this is the half the old widening never did.
        val chunk = compile(
            """
            graph "probe"

            export var Declared: FLOAT = 4
            export var Assigned: FLOAT = 0.0

            on start {
                Assigned = 7
            }
            """.trimIndent(),
        )
        val run = run(chunk)

        assertEquals(4.0, chunk.variable("Declared", run), "a literal default arrives as a Double")
        assertEquals(7.0, chunk.variable("Assigned", run), "and so does one assigned to a FLOAT variable")
    }

    @Test
    fun `a record's float field takes a whole number and holds a float`() {
        val chunk = compile(
            """
            graph "probe"

            export type Point { x: FLOAT, y: FLOAT }

            export var X: FLOAT = 0.0
            export var Text: STRING = ""

            on start {
                val p = Point { x: 4, y: 3 }
                X = p.x
                Text = "" + p.x
            }
            """.trimIndent(),
        )
        val run = run(chunk)

        assertEquals(4.0, chunk.variable("X", run))
        // The observable half. Before the compiler widened the constant this printed "4", so a field the
        // author had declared FLOAT read back as a whole number and nothing anywhere said why.
        assertEquals("4.0", chunk.variable("Text", run))
    }

    @Test
    fun `a float literal is still refused where an int belongs`() {
        // The literal rule is one-way. Rounding somebody's number for them is a decision, not a
        // conversion, and the four narrowing nodes are how it gets made out loud.
        val issues = errorsIn(
            """
            graph "probe"

            on start {
                delay(ms: 1.5)
            }
            """.trimIndent(),
        ) + loweringErrorsIn(
            """
            graph "probe"

            on start {
                delay(ms: 1.5)
            }
            """.trimIndent(),
        )

        assertTrue(issues.any { it.contains("Float") }, "expected the float to be refused, got $issues")
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private fun lower(text: String): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { "${it.span} ${it.message}" }}")
        return Lower(catalog, source = GraphSource.NONE).lower(parsed.program).graph
    }

    private fun loweringErrorsIn(text: String): List<String> {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { it.message }}")
        return Lower(catalog, source = GraphSource.NONE).lower(parsed.program).errors.map { it.message }
    }

    /** Lower, insist it validates clean, and compile the start entry. */
    private fun compile(text: String): Chunk {
        val graph = lower(text)
        val issues = Validator(catalog).validate(graph).filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), issues.map { it.message }, "should validate clean")
        val entry = graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        return GraphCompiler(catalog, debug = false).compile(graph, entry.id)
    }

    private fun errorsIn(text: String): List<String> =
        Validator(catalog).validate(lower(text)).filter { it.severity == Severity.ERROR }.map { it.message }

    private fun run(chunk: Chunk): dev.ziggle.vscript.vm.DriveResult {
        val result = drive(chunk, BuiltinHosts.registry())
        assertTrue(
            result.fiber.isFinished,
            "the run did not complete: ${result.fiber.state} ${result.fiber.error?.message.orEmpty()}",
        )
        return result
    }

    private fun Chunk.variable(name: String, run: dev.ziggle.vscript.vm.DriveResult): Any? {
        val slot = slots.variables[name] ?: error("no slot for '$name'")
        return run.interpreter.globals.getOrNull(slot)
    }
}
