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
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `var x = …` inside a body: a local you can assign to.
 *
 * **The point is the frame, not the syntax.** Before this, the only assignable thing was a graph variable —
 * one cell for the whole run — so an accumulator inside a function was shared by every call. A recursive
 * `sqrt` would trample its own working value, two scripts calling one library would trample each other's,
 * and nothing anywhere reported it. That is why the assertions below are about **recursion and nesting**
 * rather than about a counter reaching the right number: a build with locals compiled to graph variables
 * would pass a single-call test perfectly.
 *
 * The mechanism is that a `Hold` already owns a register for the life of its chunk, a chunk is one call's
 * body, and a frame is one call — so assignment writes that register and per-call falls out for free.
 */
class LocalVarTest {

    private val catalog = NodeCatalog(dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

    // ---- it works at all ------------------------------------------------------------------------------

    @Test
    fun `a local accumulates across a loop`() {
        val chunk = compile(
            """
            graph "probe"

            export var Out: INT = 0

            on start {
                var total = 0
                for n in [1, 2, 3, 4] {
                    total = total + n
                }
                Out = total
            }
            """.trimIndent(),
        )
        assertEquals(10, chunk.variable("Out", run(chunk)), "1 + 2 + 3 + 4")
    }

    @Test
    @Timeout(10)
    fun `a while loop can count with one`() {
        // The shape that had no answer before: a counted loop needs a counter, and the only assignable
        // thing was a graph variable.
        val chunk = compile(
            """
            graph "probe"

            export var Out: INT = 0

            export fn countTo(limit: INT) -> INT {
                var i = 0
                var seen = 0
                while i < limit {
                    seen = seen + i
                    i = i + 1
                }
                return seen
            }

            on start {
                Out = countTo(5)
            }
            """.trimIndent(),
        )
        assertEquals(10, chunk.variable("Out", run(chunk)), "0+1+2+3+4")
    }

    @Test
    @Timeout(10)
    fun `Newtons method, which is the thing that could not be written`() {
        val chunk = compile(
            """
            graph "probe"

            export var Out: FLOAT = 0.0

            // Expression-bodied, so PURE — re-expanded at every read, and therefore recomputed each
            // time round the loop. A block-bodied `abs` would be impure, and a while condition holding
            // an impure call is evaluated ONCE (see the KNOWN LIMIT below), which hangs.
            export fn abs(x: FLOAT) -> FLOAT = x < 0.0 ? 0.0 - x : x

            export fn sqrt(x: FLOAT) -> FLOAT {
                var guess = x / 2.0
                var prev = 0.0
                while abs(guess - prev) > 0.00001 {
                    prev = guess
                    guess = (guess + x / guess) / 2.0
                }
                return guess
            }

            on start {
                Out = sqrt(16.0)
            }
            """.trimIndent(),
        )
        val out = chunk.variable("Out", run(chunk)) as Double
        assertTrue(out > 3.999 && out < 4.001, "sqrt(16) should be 4, got $out")
    }

    // ---- and it is PER CALL, which is the whole reason ------------------------------------------------

    @Test
    @Timeout(10)
    fun `a recursive call does not trample the caller's local`() {
        // The assertion a graph variable fails. `total` is written before the recursive call and read
        // after it; with one shared cell the inner call's value comes back out and the sum collapses.
        val chunk = compile(
            """
            graph "probe"

            export var Out: INT = 0

            export fn sumTo(n: INT) -> INT {
                if n <= 0 {
                    return 0
                }
                var total = n
                total = total + sumTo(n - 1)
                return total
            }

            on start {
                Out = sumTo(5)
            }
            """.trimIndent(),
        )
        assertEquals(15, chunk.variable("Out", run(chunk)), "1+2+3+4+5, each call keeping its own total")
    }

    @Test
    @Timeout(10)
    fun `two calls to the same function each start fresh`() {
        val chunk = compile(
            """
            graph "probe"

            export var First: INT = 0
            export var Second: INT = 0

            export fn countTo(limit: INT) -> INT {
                var i = 0
                while i < limit {
                    i = i + 1
                }
                return i
            }

            on start {
                First = countTo(3)
                Second = countTo(3)
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(3, chunk.variable("First", run))
        // A shared cell would already be at 3 and the loop would not run at all the second time.
        assertEquals(3, chunk.variable("Second", run), "the second call starts from zero, like the first")
    }

    @Test
    fun `a local in a loop body is fresh each iteration`() {
        val chunk = compile(
            """
            graph "probe"

            export var Out: INT = 0

            on start {
                var last = 0
                for n in [1, 2, 3] {
                    var doubled = 0
                    doubled = doubled + n * 2
                    last = doubled
                }
                Out = last
            }
            """.trimIndent(),
        )
        // 6, not 12: `doubled` is re-initialised by its own Hold at the top of every iteration.
        assertEquals(6, chunk.variable("Out", run(chunk)), "the declaration runs again each time round")
    }

    // ---- what is refused ------------------------------------------------------------------------------

    @Test
    fun `a val still cannot be assigned, and the message names var`() {
        val errors = loweringErrors(
            """
            graph "probe"

            on start {
                val fixed = 1
                fixed = 2
            }
            """.trimIndent(),
        )
        val message = errors.single()
        assertTrue(message.contains("'fixed' is a `val`"), message)
        assertTrue(message.contains("var fixed"), message)
    }

    @Test
    fun `assigning the wrong type to a local is reported`() {
        // The local's type comes from its initialiser, and the assignment is checked against it — so a
        // register cannot quietly change what kind of thing it holds half way through a body.
        val errors = validationErrors(
            """
            graph "probe"

            export fn zero() -> INT = 0

            export var Out: INT = 0

            on start {
                var n = zero()
                n = "two"
                Out = n
            }
            """.trimIndent(),
        )
        assertTrue(
            errors.any { it.contains("INT") && (it.contains("String") || it.contains("STRING")) },
            "expected the assignment to be type-checked, got $errors",
        )
    }

    /**
     * A local initialised from a bare literal is typed by that literal.
     *
     * `effectivePinType` types a Hold from what FEEDS it, and a typed-in literal is not a wire — so there
     * was nothing to follow and the pin stayed WILDCARD, which accepts anything. Older than mutable locals
     * (every `let x = 5` was untyped) and only visible once something could assign to one.
     *
     * Which kinds are inferred was measured, not guessed: a String is how a coordinate is stored too, and
     * reading one as STRING refuses a real script in the client's folder. See `literalType`.
     */
    @Test
    fun `a local initialised from a literal is typed by it`() {
        val errors = validationErrors(
            """
            graph "probe"

            export var Out: INT = 0

            on start {
                var n = 0
                n = "two"
                Out = n
            }
            """.trimIndent(),
        )
        assertTrue(
            errors.any { it.contains("INT") && (it.contains("String") || it.contains("STRING")) },
            "assigning a string to an int local should be refused, got $errors",
        )
    }

    @Test
    fun `a float local keeps its kind`() {
        val errors = validationErrors(
            """
            graph "probe"

            export var Out: FLOAT = 0.0

            on start {
                var ratio = 0.5
                ratio = "half"
                Out = ratio
            }
            """.trimIndent(),
        )
        assertTrue(errors.isNotEmpty(), "a FLOAT local should refuse a string")
    }

    @Test
    fun `a tile literal stays a wildcard, because a tile is stored as a string`() {
        // The measured exception. `tile(x, y, p)` lowers to the string "x,y,p", so inferring STRING would
        // refuse this — and it is correct code. Pinning it here so a later widening of `literalType`
        // cannot quietly break it.
        val walk = hostNode(
            "test.walk", "walk", dev.ziggle.vscript.model.NodeKind.IMPURE,
            inputs = listOf(
                dev.ziggle.vscript.model.PinSpec("Exec", dev.ziggle.vscript.model.PinType.EXEC),
                dev.ziggle.vscript.model.PinSpec("Tile", dev.ziggle.vscript.domain.TileFixture.TYPE),
            ),
            outputs = listOf(dev.ziggle.vscript.model.PinSpec("Exec", dev.ziggle.vscript.model.PinType.EXEC)),
        )
        val withWalk = NodeCatalog(listOf(walk) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)
        val text = """
            graph "probe"

            on start {
                val spot = tile(3200, 3200, 0)
                walk(tile: spot)
            }
        """.trimIndent()
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { it.message }}")
        val lowered = Lower(withWalk, source = GraphSource.NONE).lower(parsed.program)
        val errors = lowered.errors.map { it.message } +
            Validator(withWalk).validate(lowered.graph).errors().map { it.message }
        assertEquals(emptyList(), errors, "a tile bound to a name has to keep working")
    }

    /**
     * A `while` condition that has to DO something is asked again every iteration.
     *
     * It used to be asked once. `Lower.whileStmt` lowered the condition into the ENCLOSING chain, so an
     * impure call in it ran before the loop and every iteration re-read the register it left behind — a
     * loop that spun forever or never started, with nothing to say why. The printer was the tell: it wrote
     * the graph back as `let result = overTen(i)` above a `while result == 0`, which is a different
     * program from the one that was written.
     *
     * The condition's steps now hang off the While's `Check` pin, which the compiler re-runs at the top of
     * each iteration. A pure condition places nothing there and is unchanged — those are re-expanded at
     * every read already.
     */
    @Test
    @Timeout(20)
    fun `an impure call in a while condition is re-evaluated each iteration`() {
        val chunk = compile(
            """
            graph "probe"

            export var Out: INT = 0

            export fn overTen(n: INT) -> INT {
                if n > 10 {
                    return 1
                }
                return 0
            }

            on start {
                var i = 0
                while overTen(i) == 0 {
                    i = i + 1
                    if i > 50 {
                        break
                    }
                }
                Out = i
            }
            """.trimIndent(),
        )
        // 11: the condition turns true once i passes ten. 51 would mean it was decided before the loop
        // began and only the `break` stopped it.
        assertEquals(11, chunk.variable("Out", run(chunk)), "the condition is asked again each time round")
    }

    @Test
    fun `a while condition that needs steps round trips as it was written`() {
        // The other half. The condition's steps live on a pin nothing walks for statements, so the printer
        // has to render them INSIDE the condition — otherwise it emits a name with no binding.
        val source = """
            graph "probe"

            export var Out: INT = 0

            export fn overTen(n: INT) -> INT {
                if n > 10 {
                    return 1
                } else {
                    return 0
                }
            }

            on start {
                var i = 0
                while overTen(n: i) == 0 {
                    i = i + 1
                }
                Out = i
            }
        """.trimIndent() + "\n"

        val printed = Print(catalog, source = GraphSource.NONE).print(lower(source))
        assertEquals(source, printed)
    }

    // ---- a local may say its own type ------------------------------------------------------------------

    /**
     * `var total: FLOAT = 0`.
     *
     * A local's type otherwise comes from what fed it, which is right nearly always and wrong in the one
     * case people hit first: `0` is an INT, so a float accumulator started as an integer and every
     * assignment to it then had to be one too.
     */
    @Test
    fun `a local may declare its type, and the initialiser is converted to it`() {
        val chunk = compile(
            """
            graph "probe"

            export var Out: FLOAT = 0.0

            on start {
                var total: FLOAT = 0
                total = total + 0.5
                Out = total
            }
            """.trimIndent(),
        )
        assertEquals(0.5, chunk.variable("Out", run(chunk)), "the local started as 0.0, not the Int 0")
    }

    @Test
    fun `a let may declare one too`() {
        val chunk = compile(
            """
            graph "probe"

            export var Out: FLOAT = 0.0

            on start {
                val limit: FLOAT = 2
                Out = limit
            }
            """.trimIndent(),
        )
        assertEquals(2.0, chunk.variable("Out", run(chunk)))
    }

    @Test
    fun `a declared type is enforced against later assignments`() {
        val errors = validationErrors(
            """
            graph "probe"

            export var Out: FLOAT = 0.0

            on start {
                var total: FLOAT = 0
                total = "half"
                Out = total
            }
            """.trimIndent(),
        )
        assertTrue(errors.isNotEmpty(), "a string into a FLOAT local should be refused, got $errors")
    }

    @Test
    fun `a declared type round trips`() {
        val source = """
            graph "probe"

            export var Out: FLOAT = 0.0

            on start {
                var total: FLOAT = 0
                Out = total
            }
        """.trimIndent() + "\n"
        assertEquals(source, Print(catalog, source = GraphSource.NONE).print(lower(source)))
    }

    // ---- it round trips -------------------------------------------------------------------------------

    @Test
    fun `var and its assignments print back as they were written`() {
        // `let` and `var` are the same node with a flag, so the printer has to read the flag — printing
        // every Hold as `let` would turn a working document into one that no longer compiles.
        val source = """
            graph "probe"

            export var Out: INT = 0

            on start {
                var total = 0
                val fixed = 5
                total = total + fixed
                Out = total
            }
        """.trimIndent() + "\n"

        val printed = Print(catalog, source = GraphSource.NONE).print(lower(source))

        assertTrue(printed.contains("var total = 0"), printed)
        assertTrue(printed.contains("val fixed = 5"), printed)
        assertTrue(printed.contains("total = total + fixed"), printed)
        // And the round trip is stable: printing what was read back gives the same text again.
        assertEquals(printed, Print(catalog, source = GraphSource.NONE).print(lower(printed)))
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private fun lower(text: String): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val result = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertEquals(emptyList(), result.errors.map { it.message }, "lowering errors")
        return result.graph
    }

    private fun loweringErrors(text: String): List<String> {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { it.message }}")
        return Lower(catalog, source = GraphSource.NONE).lower(parsed.program).errors.map { it.message }
    }

    private fun validationErrors(text: String): List<String> {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { it.message }}")
        val lowered = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        return lowered.errors.map { it.message } +
            Validator(catalog).validate(lowered.graph).errors().map { it.message }
    }

    private fun compile(text: String): Chunk {
        val graph = lower(text)
        assertEquals(emptyList(), Validator(catalog).validate(graph).errors().map { it.message })
        val entry = graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        return GraphCompiler(catalog, debug = false).compile(graph, entry.id)
    }

    private fun run(chunk: Chunk): DriveResult {
        val result = drive(chunk, BuiltinHosts.registry())
        // isFinished is true for a FAULTED fiber too, so both are checked — otherwise a run that threw
        // leaves every variable at its zero and an assertion expecting one passes for the wrong reason.
        assertEquals(null, result.fiber.error?.message, "the run faulted")
        assertTrue(result.fiber.isFinished, "the run did not complete: ${result.fiber.state}")
        return result
    }

    private fun Chunk.variable(name: String, run: DriveResult): Any? {
        val slot = slots.variables[name] ?: error("no slot for '$name'")
        return run.interpreter.globals.getOrNull(slot)
    }
}
