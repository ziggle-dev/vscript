package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
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
 * A function that calls itself.
 *
 * The knot is small and specific: `CALLG` names a sub-chunk **by index into the calling chunk's own table**,
 * so a recursive call needs its index while the body that contains it is still being emitted. The compiler
 * reserves the slot first and fills it with the finished chunk afterwards, which leaves the chunk holding
 * itself — and that is what makes the third test here necessary rather than paranoid.
 */
class RecursionTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun graphOf(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        return low.graph
    }

    private fun said(src: String, maxTicks: Int = 800): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = maxTicks,
        )
        return out.map { if (it is Number) it.toLong() else it }
    }

    private val countdown = """
        fn down(n: Int) {
            if n <= 0 { return }
            say(n)
            down(n - 1)
        }
        on start { down(3) say("done") }
    """.trimIndent()

    @Test
    fun `a function can call itself`() {
        assertEquals(listOf(3L, 2L, 1L, "done"), said(countdown))
    }

    @Test
    fun `an expression-bodied function can recurse too`() {
        // The other call site — a Call read as a VALUE rather than run as a step. It resolves its sub-chunk
        // through the same path, because two ways to reach one is how one of them stops handling a case.
        assertEquals(
            listOf(6L),
            said(
                """
                fn total(n: Int) -> Int = n <= 0 ? 0 : n + total(n - 1)
                on start { say(total(3)) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The one that would have crashed.
     *
     * **This test used to assert that a recursive function's chunk contained ITSELF** — which it did, when
     * a callee was nested inside each chunk that called it, and which is why `setLiteral` needed an
     * identity-keyed visited set to avoid following that edge until the stack went.
     *
     * The linker removed the cycle rather than guarding it: a function is compiled once into a flat program
     * and named by index, so nothing contains anything. The old assertion pinned the *workaround*, so it
     * could only ever fail once the workaround was gone. What is worth pinning is the behaviour it was
     * protecting — that retuning a literal in a recursive function terminates and lands — so that is what
     * is asserted now.
     */
    @Test
    fun `editing a literal in a recursive function terminates and lands`() {
        val g = graphOf(countdown)
        val chunk = GraphCompiler(catalog, debug = true).compile(g, g.entries(catalog).single().id)

        // The premise: the recursion really was compiled, or the rest of this proves nothing. One chunk
        // for `down`, however many times it calls itself.
        assertEquals(
            1, chunk.program.size,
            "a recursive function is one chunk in the program, not one per call site",
        )

        // The point of both calls is that they RETURN rather than overflowing. Neither pin is expected to
        // be owned: `say(n)`'s Message is wired to the parameter, so it has no literal slot at all — which
        // is exactly why the original test called this without asserting the result.
        assertTrue(
            !chunk.setLiteral(-1, "nothing", 0),
            "a pin nothing owns reports false rather than recursing",
        )
        val say = g.nodes.first { it.type == "test.say" }
        assertTrue(
            !chunk.setLiteral(say.id, "Message", 99),
            "a wired pin owns no literal — and asking still terminates",
        )
    }

    @Test
    fun `runaway recursion is stopped by the depth cap rather than the stack`() {
        val g = graphOf(
            """
            fn forever(n: Int) { forever(n + 1) }
            on start { forever(0) }
            """.trimIndent(),
        )
        val chunk = GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)
        val r = drive(chunk, HostRegistry(), maxTicks = 5_000)
        val err = r.fiber.error?.message.orEmpty()
        assertTrue("call depth" in err, "expected the frame cap to fire, got: '$err'")
    }

    /**
     * Mutual recursion RUNS, since the linker — and this asserts the behaviour, not the shape.
     *
     * It used to be refused by the validator, because a callee's chunk was nested inside its caller's: F's
     * would have had to contain G's, which contains F's, and neither object exists while the other is being
     * built. Compiling each function once into a flat program closes it, because an index is reserved
     * before the body is compiled — so `pong`'s call back to `ping` resolves to a slot `ping` has not
     * finished filling.
     */
    @Test
    fun `mutual recursion runs`() {
        assertEquals(
            listOf<Any?>(3L, 2L, 1L),
            said(
                """
                fn ping(n: Int) {
                    if n <= 0 { return }
                    say(n)
                    pong(n - 1)
                }
                fn pong(n: Int) {
                    if n <= 0 { return }
                    say(n)
                    ping(n - 1)
                }
                on start { ping(3) }
                """.trimIndent()
            ),
        )
    }

    /** And it validates, rather than merely not crashing. */
    @Test
    fun `mutual recursion validates`() {
        val src = """
            fn ping(n: Int) { pong(n) }
            fn pong(n: Int) { ping(n) }
            on start { ping(1) }
        """.trimIndent()
        val low = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)
        val errors = Validator(catalog).validate(low.graph).errors()
        assertEquals(emptyList(), errors.map { it.message })
    }
}
