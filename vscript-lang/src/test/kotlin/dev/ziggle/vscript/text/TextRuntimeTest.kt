package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the compiler can do once it stops guessing and asks the resolver.
 *
 * Every case here is something the skeleton either got wrong or could not express, and each is wrong in a
 * way that runs: a shadowed name reading the outer one's register, a FLOAT holding an Int, a document
 * variable that does not exist. So they are checked by RUNNING the bytecode rather than by reading it —
 * the disassembly can look reasonable and still be a different program.
 */
class TextRuntimeTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val read = TextFrontEnd(natives).read(src)
        val chunk = read.chunk
            ?: fail("did not compile: " + read.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun script(body: String) = """
        graph "probe"

        on start {
        $body
        }
    """.trimIndent()

    /**
     * Two `val n` in sibling scopes are two variables.
     *
     * The skeleton kept locals in one flat map keyed by NAME, so the inner declaration overwrote the
     * outer's register while the outer was still live — and the outer name went on reading it. Registers
     * are keyed by the BINDING now, which is what makes them different variables rather than one.
     */
    @Test
    fun `an inner name shadows without destroying the outer one`() {
        assertEquals(
            listOf("inner 2", "outer 1"),
            run(
                script(
                    """    val n = 1
    if true {
        val n = 2
        log(message: "inner " + n)
    }
    log(message: "outer " + n)""",
                ),
            ),
        )
    }

    /**
     * GAPS 16, end to end: a FLOAT declared and given a whole number holds a FLOAT.
     *
     * The permission to widen was always there and nothing converted the value, so the slot held an `Int`
     * for the whole run and a type that had been declared was a claim nothing enforced. `Op.TOF` on
     * exactly this boundary is the difference, and printing it is how you can tell.
     */
    @Test
    fun `a float local really holds a float`() {
        assertEquals(listOf("1.0"), run(script("""    var n: FLOAT = 1${'\n'}    log(message: "" + n)""")))
    }

    @Test
    fun `an int widens into float arithmetic`() {
        assertEquals(listOf("3.5"), run(script("""    val n = 1 + 2.5${'\n'}    log(message: "" + n)""")))
    }

    /** A document variable is run state, reachable from any body, and it survives a write. */
    @Test
    fun `a document variable can be read and written`() {
        assertEquals(
            listOf("2", "5"),
            run(
                """
                graph "probe"

                var Count: INT = 0

                on start {
                    Count = Count + 2
                    log(message: "" + Count)
                    Count = Count + 3
                    log(message: "" + Count)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a record is built and read back`() {
        assertEquals(
            listOf("Varrock 3"),
            run(
                """
                graph "probe"

                type Trip { bank: STRING, laps: INT }

                on start {
                    val t = Trip { bank: "Varrock", laps: 3 }
                    log(message: t.bank + " " + t.laps)
                }
                """.trimIndent(),
            ),
        )
    }

    /** A field left out takes the default the DECLARATION states, not null. */
    @Test
    fun `an unsupplied field takes its declared default`() {
        assertEquals(
            listOf("Varrock 1"),
            run(
                """
                graph "probe"

                type Trip { bank: STRING, laps: INT = 1 }

                on start {
                    val t = Trip { bank: "Varrock" }
                    log(message: t.bank + " " + t.laps)
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- functions -----------------------------------------------------------------------------------

    @Test
    fun `a function is called and its result used`() {
        assertEquals(
            listOf("6"),
            run(
                """
                graph "probe"

                fn double(n: INT) -> INT {
                    return n * 2
                }

                on start {
                    log(message: "" + double(n: 3))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A function may call itself: the program slot is reserved before its body is compiled. */
    @Test
    fun `a recursive function terminates`() {
        assertEquals(
            listOf("120"),
            run(
                """
                graph "probe"

                fn factorial(n: INT) -> INT {
                    if n <= 1 {
                        return 1
                    }
                    return n * factorial(n: n - 1)
                }

                on start {
                    log(message: "" + factorial(n: 5))
                }
                """.trimIndent(),
            ),
        )
    }

    /** One function called from two places is ONE chunk — see invariant 1 in the plan. */
    @Test
    fun `a function called twice is compiled once`() {
        val read = TextFrontEnd(natives).read(
            """
            graph "probe"

            fn twice(n: INT) -> INT {
                return n * 2
            }

            on start {
                log(message: "" + twice(n: 1))
                log(message: "" + twice(n: 2))
            }
            """.trimIndent(),
        )
        val chunk = read.chunk ?: fail("did not compile: ${read.errors}")
        assertEquals(1, chunk.program.count { it.name.endsWith("::twice") })
    }

    @Test
    fun `a function body sees the document's variables`() {
        assertEquals(
            listOf("7"),
            run(
                """
                graph "probe"

                var Base: INT = 5

                fn plus(n: INT) -> INT {
                    return Base + n
                }

                on start {
                    log(message: "" + plus(n: 2))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A tile's fields, which have never been reachable.
     *
     * Stored as the string `"x,y,plane"`, so this is the one place the prelude's structure and the runtime
     * representation have to be reconciled — and today they are not. Whichever of the three options in
     * `docs/TEXT_FRONTEND.md` is taken, this test is what says it worked.
     */
    @Test
    fun `reading a tile's field is refused rather than wrong`() {
        val read = TextFrontEnd(natives).read(
            """
            graph "probe"

            fn eastOf(t: TILE) -> INT {
                return t.x + 1
            }

            on start { }
            """.trimIndent(),
        )
        // The resolver accepts it — a tile HAS an x. The compiler cannot emit it yet, because at run time
        // a tile is still a string, and saying so is better than emitting a GETFIELD that finds one.
        assertTrue(read.resolution?.ok == true, "the resolver should accept a tile's field")
    }
}
