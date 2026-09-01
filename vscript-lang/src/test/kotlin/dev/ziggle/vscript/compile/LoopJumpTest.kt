package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.lang.Print
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `break` and `continue` — **run**, not inspected.
 *
 * Every mistake available here is an off-by-one in a jump target, and every one of them produces code that
 * compiles perfectly. `continue` in a `for` is the sharp case: the loop's back edge points at the
 * `ITERNEXT`, and the `ITER` setup sits *above* it, so a `continue` aimed one instruction too high rebuilds
 * the iterator every pass and hands out the first element forever. That is a hang, and no assertion about
 * shape would have caught it — so these tests watch what the script actually does.
 */
class LoopJumpTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    /** Run [src] and return everything it said, so the assertion is about behaviour. */
    private fun said(src: String, maxTicks: Int = 400): List<Any?> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")

        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val entry = low.graph.entries(catalog).single()
        drive(GraphCompiler(catalog, debug = false).compile(low.graph, entry.id), hosts, maxTicks = maxTicks)
        return out
    }

    @Test
    fun `break leaves a while loop`() {
        assertEquals(
            listOf(0L, 1L, 2L, "after"),
            said(
                """
                var n: Int = 0
                on start {
                    while true {
                        if n > 2 { break }
                        say(n)
                        n = n + 1
                    }
                    say("after")
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /**
     * The one that would hang.
     *
     * `continue` must land on the `ITERNEXT`, which advances the iterator, and not on the `ITER` that built
     * it. Aimed one instruction high this loops forever on the first element and never reaches `after`.
     */
    @Test
    fun `continue advances a for loop rather than restarting it`() {
        assertEquals(
            listOf(1L, 3L, "after"),
            said(
                """
                on start {
                    for x in [1, 2, 3, 4] {
                        if x == 2 { continue }
                        if x == 4 { continue }
                        say(x)
                    }
                    say("after")
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /**
     * A `continue` that sits AFTER a call in the same loop body.
     *
     * The stray-jump walk stopped dead at any `Call`, meaning to say "a break inside the callee is not mine
     * to legitimise". But a block-bodied function is a STEP: its exec output continues the CALLER's chain,
     * and the callee's body is a separate chain this walk never had a way into. So the guard was not
     * refusing to descend, it was abandoning the rest of the loop body — and every statement after the
     * first call became, as far as the check could tell, outside the loop.
     *
     * `if !pace::free() { pace::beat() continue }` is the shape that found it: the gate calls before it
     * branches, so the guard fired on the most ordinary loop in the corpus.
     */
    @Test
    fun `a jump after a call in the same body is still inside the loop`() {
        assertEquals(
            listOf(3L),
            said(
                """
                var n: Int = 0

                fn tick() {
                    n = n + 1
                }

                on start {
                    while true {
                        tick()
                        if n < 3 { continue }
                        say(n)
                        break
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    @Test
    fun `break leaves a for loop`() {
        assertEquals(
            listOf(1L, 2L, "after"),
            said(
                """
                on start {
                    for x in [1, 2, 3, 4] {
                        if x > 2 { break }
                        say(x)
                    }
                    say("after")
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /**
     * Innermost only — the outer loop keeps going, which is what makes it "the innermost loop".
     *
     * x=1 breaks on its first pass and says nothing; x=2 says 1 then breaks; x=3 says 1 and 2 then breaks.
     */
    @Test
    fun `break leaves only the loop it is in`() {
        assertEquals(
            listOf(1L, 1L, 2L, "after"),
            said(
                """
                on start {
                    for x in [1, 2, 3] {
                        for y in [1, 2, 3] {
                            if y >= x { break }
                            say(y)
                        }
                    }
                    say("after")
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    @Test
    fun `several breaks in one loop all reach the same place`() {
        assertEquals(
            listOf(1L, "after"),
            said(
                """
                on start {
                    for x in [1, 2, 3] {
                        if x == 2 { break }
                        if x == 3 { break }
                        say(x)
                    }
                    say("after")
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    // ---- refusals ------------------------------------------------------------------------------------

    @Test
    fun `a break outside any loop is refused`() {
        val parsed = Parser(Lexer("on start { break }").lex()).parse()
        val low = Lower(catalog).lower(parsed.program)
        val errors = Validator(catalog).validate(low.graph).errors()
        assertTrue(errors.any { "not inside a loop" in it.message }, errors.toString())
    }

    /**
     * A loop in the caller does not legitimise a `break` in the callee.
     *
     * The compiler keeps its loop stack per chunk, so a `break` compiled inside a function body has no
     * enclosing loop to jump out of even when every call site is in one. The validator has to agree.
     */
    @Test
    fun `a break inside a called function is not covered by the caller's loop`() {
        val src = """
            fn helper() { break }
            on start { for x in [1, 2] { helper() } }
        """.trimIndent()
        val low = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)
        val errors = Validator(catalog).validate(low.graph).errors()
        assertTrue(errors.any { "not inside a loop" in it.message }, errors.toString())
    }

    // ---- past a `when` -------------------------------------------------------------------------------
    //
    // A `when` declares NO exec outputs on its descriptor — `resolveNode` synthesises `Then 1..N` and
    // `Else` from the `Cases` literal — so a reachability walk reading the raw catalogue entry found no
    // way out of one and stopped. Everything after a `when` in the same loop body was then reported as a
    // saying so; these are the shapes it could not write.

    @Test
    fun `a break AFTER a when is still inside the loop`() {
        assertEquals(
            listOf("zero", "other", "other", "after"),
            said(
                """
                var n: Int = 0
                on start {
                    while true {
                        when n {
                            0 -> say("zero")
                            else -> say("other")
                        }
                        n = n + 1
                        if n > 2 { break }
                    }
                    say("after")
                }
                """.trimIndent(),
            ),
        )
    }

    /** The jump INSIDE an arm — unreachable by the same walk, for the same reason. */
    @Test
    fun `a continue inside a when arm is inside the loop`() {
        assertEquals(
            listOf(1L, 3L),
            said(
                """
                on start {
                    for x in [1, 2, 3] {
                        when x {
                            2 -> continue
                            else -> say(x)
                        }
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /**
     * No `else`, which is the other way out of a `when`: nothing matching falls through the Else pin to
     * whatever follows. A walk that only knew about the arms would miss the fall-through path.
     */
    @Test
    fun `a break after an else-less when is still inside the loop`() {
        assertEquals(
            listOf("done"),
            said(
                """
                var n: Int = 0
                on start {
                    while true {
                        when n {
                            5 -> say("five")
                        }
                        n = n + 1
                        if n > 1 { break }
                    }
                    say("done")
                }
                """.trimIndent(),
            ),
        )
    }

    /** And the guard still guards: reaching a `when` does not make a jump outside every loop legal. */
    @Test
    fun `a break after a when but outside any loop is still refused`() {
        val src = """
            on start {
                when 1 {
                    1 -> say("x")
                }
                break
            }
        """.trimIndent()
        val low = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)
        val errors = Validator(catalog).validate(low.graph).errors()
        assertTrue(errors.any { "not inside a loop" in it.message }, errors.toString())
    }

    // ---- the round trip ------------------------------------------------------------------------------

    @Test
    fun `break and continue survive being printed`() {
        val src = """
            on start {
                for x in [1, 2, 3] {
                    if x == 1 { continue }
                    if x == 3 { break }
                    say(x)
                }
            }
        """.trimIndent()
        val low = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)
        val printed = Print(catalog).print(low.graph)
        assertTrue("break" in printed, printed)
        assertTrue("continue" in printed, printed)
        // And still does the same thing, which is the only claim that matters.
        assertEquals(said(src), said(printed))
    }
}
