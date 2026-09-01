package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Document variables that have to be computed, and the three calls the compiler emits itself.
 *
 * Both were found by measuring the corpus rather than by reading it: 60 variables start from an
 * expression, and `delay` is the second most-used call in the whole of `plugins/scripts` — dropped
 * correctly by the catalogue adapter, because the graph compiler lowers it and it carries no host.
 */
class PrologueTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("roll", results = outs(INT)),
        ),
    )

    private fun read(main: String, others: Map<String, String> = emptyMap()) =
        TextFrontEnd(natives, imports = TextSource.of(others)).read(main)

    private fun run(main: String, others: Map<String, String> = emptyMap()): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("roll", HostKind.INLINE, arity = 0, results = 1) { 42 }
        val r = read(main, others)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    // ---- computed document variables -----------------------------------------------------------------

    @Test
    fun `a variable computed from a call is seeded before the entry runs`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "probe"

                var Seed: INT = roll()

                on start {
                    log(message: "" + Seed)
                }
                """.trimIndent(),
            ),
        )
    }

    /** One may read another, so they are seeded in declaration order. */
    @Test
    fun `a variable may be computed from one declared above it`() {
        assertEquals(
            listOf("43"),
            run(
                """
                graph "probe"

                var Seed: INT = roll()
                var Next: INT = Seed + 1

                on start {
                    log(message: "" + Next)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an initialiser may call a function declared below it`() {
        assertEquals(
            listOf("84"),
            run(
                """
                graph "probe"

                var Seed: INT = doubled()

                fn doubled() -> INT {
                    return roll() * 2
                }

                on start {
                    log(message: "" + Seed)
                }
                """.trimIndent(),
            ),
        )
    }

    /** A library's variables are in place before the document that imported it can read them. */
    @Test
    fun `an imported document's variables are seeded first`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "main"

                import * as lib from "lib"

                on start {
                    log(message: "" + lib::Seed)
                }
                """.trimIndent(),
                others = mapOf(
                    "lib" to """
                        graph "lib"

                        export var Seed: INT = roll()
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `an initialiser of the wrong type is refused`() {
        assertTrue(
            read(
                """
                graph "probe"

                var Seed: INT = "forty two"

                on start { }
                """.trimIndent(),
            ).errors.any { it.message.contains("INT") },
        )
    }

    // ---- the calls the compiler emits itself ---------------------------------------------------------

    /** `delay` parks the fiber. It has no host — the compiler lowers it — so it must be an intrinsic. */
    @Test
    fun `delay parks the fiber and carries on`() {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = read(
            """
            graph "probe"

            on start {
                log(message: "before")
                delay(ms: 500)
                log(message: "after")
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail("did not compile: ${r.errors.map { it.message }}")
        val clock = FakeClock()
        drive(chunk, hosts, clock)
        assertEquals(listOf("before", "after"), said)
        assertTrue(clock.now >= 500, "the delay did not cost any time: ${clock.now}")
    }

    @Test
    fun `delay takes its catalogue default`() {
        assertTrue(read("""graph "probe"${'\n'}${'\n'}on start { delay() }""").ok)
    }

    /**
     * `and` evaluates BOTH sides, always — which is the whole difference between it and `&&`, and why the
     * language has two spellings rather than one pretending to be the other.
     */
    @Test
    fun `and evaluates both sides`() {
        val seen = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> seen += a[0].toString(); null }
        hosts.register("roll", HostKind.INLINE, arity = 0, results = 1) { seen += "rolled"; 42 }
        val r = read(
            """
            graph "probe"

            on start {
                if and(a: false, b: roll() > 0) {
                    log(message: "yes")
                }
                log(message: "done")
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail("did not compile: ${r.errors.map { it.message }}")
        drive(chunk, hosts)
        assertEquals(listOf("rolled", "done"), seen, "'and' must evaluate its right side even so")
    }

    @Test
    fun `or gives back true when either side is`() {
        assertEquals(
            listOf("yes"),
            run(
                """
                graph "probe"

                on start {
                    if or(a: false, b: true) {
                        log(message: "yes")
                    }
                }
                """.trimIndent(),
            ),
        )
    }
}
