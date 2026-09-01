package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A document's initialisers run in DEPENDENCY order, not in the order they were typed.
 *
 * Reported from `activities/gather/farming/lantern-lens.vs`, where a `single Config` field defaulted to
 * `bankCount(item: Ids.GlassId)` and the run died with `GETFIELD on null, expected a record` — pointing at
 * the prologue and naming neither variable. `Ids` was declared BELOW `Config`, so it had not been built
 * when `Config` read it. Moving the declaration cured it, which is the property this language refuses
 * everywhere else: `Resolver.resolve` reads every declaration before any body precisely so that meaning
 * does not depend on the order somebody typed things in.
 *
 * **It looked like a `single` problem and was not.** A LITERAL initialiser rides in the run's starting
 * values and needs no prologue, so `val B: Int = 41` is always ready — which is why plain values seemed
 * fine and hid the general case. Anything computed becomes a prologue step, and a `single`'s value is a
 * synthesised record literal, so it is never the cheap kind.
 */
class InitOrderTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        val d = drive(chunk, hosts)
        assertEquals(FiberState.DONE, d.fiber.state, "run failed: ${d.fiber.error?.message}")
        return said
    }

    private fun errors(src: String) = TextFrontEnd(natives).read(src).errors.map { it.message }

    /** The reported shape, reduced: a `single` reading a `single` declared below it. */
    @Test
    fun `a single may read a single declared below it`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "p"

                single Config { Target: Int = Ids.Value + 1 }
                single Ids { Value: Int = 41 }

                on start { log(message: "" + Config.Target) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `order of declaration does not matter either way round`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "p"

                single Ids { Value: Int = 41 }
                single Config { Target: Int = Ids.Value + 1 }

                on start { log(message: "" + Config.Target) }
                """.trimIndent(),
            ),
        )
    }

    /** The general case the `single` report was one instance of. */
    @Test
    fun `a computed value may read a computed value declared below it`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "p"

                val A: Int = B + 1
                val B: Int = 40 + 1

                on start { log(message: "" + A) }
                """.trimIndent(),
            ),
        )
    }

    /** A chain, so the order is genuinely computed rather than a single swap. */
    @Test
    fun `a chain of three sorts end to end`() {
        assertEquals(
            listOf("6"),
            run(
                """
                graph "p"

                val A: Int = B * 2
                val B: Int = C + 1
                val C: Int = 40 - 38

                on start { log(message: "" + A) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * Independent initialisers keep the order they were written in.
     *
     * Side effects at start-up are rare and observable, and reordering the ones that had no reason to move
     * would be a silent behaviour change in every script at once.
     */
    @Test
    fun `independent initialisers keep their written order`() {
        assertEquals(
            listOf("first", "second"),
            run(
                """
                graph "p"

                val A: Int = noise(what: "first")
                val B: Int = noise(what: "second")

                fn noise(what: String) -> Int {
                    log(message: what)
                    return 1
                }

                on start { log(message: "" + (A + B - 2)) }
                """.trimIndent(),
            ).dropLast(1),
        )
    }

    /** A genuine ring cannot be ordered, and says which variable it stalled on rather than nulling it. */
    @Test
    fun `a cycle is refused and names a variable`() {
        val e = errors(
            """
            graph "p"

            val A: Int = B + 1
            val B: Int = A + 1

            on start { log(message: "" + A) }
            """.trimIndent(),
        )
        assertTrue(e.any { "needs itself to start up" in it }, e.toString())
        assertTrue(e.any { "'A'" in it || "'B'" in it }, e.toString())
    }

    // ---- enums, whose columns are document variables too -----------------------------------------------
    //
    // A column is not folded to a constant: it is a hidden document variable seeded by the same prologue,
    // so a column may be any expression an initialiser may be. That also puts enums squarely inside this
    // problem, twice — and the second one did not fail loudly.

    /** The cell is CHECKED late, so it may name something declared below it. */
    @Test
    fun `an enum column may read a single declared below it`() {
        assertEquals(
            listOf("41"),
            run(
                """
                graph "p"

                enum Roster(target: Int) { A(Ids.Value), B(1) }
                single Ids { Value: Int = 41 }

                on start { log(message: "" + Roster.A.target) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an enum column may read a computed value declared below it`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "p"

                enum Roster(target: Int) { A(Base + 1), B(1) }
                val Base: Int = 40 + 1

                on start { log(message: "" + Roster.A.target) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * And the other way round — the one that failed SILENTLY.
     *
     * `Config` read `Roster.A.target` before the column had been seeded, so `Want` was null and the run
     * completed without a word. A crash is recoverable; a null that reads as a legitimate answer is the
     * failure this project keeps paying for.
     */
    @Test
    fun `a single may read an enum column declared below it`() {
        assertEquals(
            listOf("7"),
            run(
                """
                graph "p"

                single Config { Want: Int = Roster.A.target }
                enum Roster(target: Int) { A(7), B(1) }

                on start { log(message: "" + Config.Want) }
                """.trimIndent(),
            ),
        )
    }
}
