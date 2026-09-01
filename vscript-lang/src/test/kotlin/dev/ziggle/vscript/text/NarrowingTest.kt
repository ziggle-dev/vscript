package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A null test narrows what follows it.
 *
 * `Generics.kt` records why the graph could not do this: *"a type here belongs to a pin rather than to a
 * point in the exec chain, so there is nowhere to record that a value is something narrower inside one
 * branch"*. A tree has such a point — a scope — and `core/orchestrator` has the comment to prove somebody
 * wanted it:
 *
 *     val have = current()
 *     if have == null { return true }
 *     return have.idle(have)      // could just be return have?.idle(have) ?: false
 */
class NarrowingTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("maybe", results = outs(INT.orNull())),
            NativeFn("nothing", results = outs(INT.orNull())),
            NativeFn("twice", listOf(NativeParam("n", INT)), results = outs(INT)),
        ),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("maybe", HostKind.INLINE, arity = 0, results = 1) { 21 }
        hosts.register("nothing", HostKind.INLINE, arity = 0, results = 1) { null }
        hosts.register("twice", HostKind.INLINE, arity = 1, results = 1) { a -> (a[0] as Number).toInt() * 2 }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(src: String) = read(src).errors.map { it.message }

    /** The corpus's own shape: an early return leaves the value present for the rest of the body. */
    @Test
    fun `an early return narrows what follows`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "probe"

                fn doubled() -> INT {
                    val n = maybe()
                    if n == null {
                        return 0
                    }
                    return twice(n: n)
                }

                on start { log(message: "" + doubled()) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a not-null test narrows the branch it guards`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "probe"

                on start {
                    val n = maybe()
                    if n != null {
                        log(message: "" + twice(n: n))
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** The mirrored form narrows the ELSE arm, for the same reason. */
    @Test
    fun `an is-null test narrows the else arm`() {
        assertEquals(
            listOf("42"),
            run(
                """
                graph "probe"

                on start {
                    val n = maybe()
                    if n == null {
                        log(message: "none")
                    } else {
                        log(message: "" + twice(n: n))
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the narrowing does not escape the branch`() {
        assertTrue(
            errors(
                """
                graph "probe"

                on start {
                    val n = maybe()
                    if n != null {
                        log(message: "" + n)
                    }
                    log(message: "" + twice(n: n))
                }
                """.trimIndent(),
            ).any { it.contains("INT?") },
            "the value is optional again after the branch",
        )
    }

    /** An `if` that does NOT always leave narrows nothing after it. */
    @Test
    fun `a branch that falls through narrows nothing`() {
        assertTrue(
            errors(
                """
                graph "probe"

                on start {
                    val n = maybe()
                    if n == null {
                        log(message: "none")
                    }
                    log(message: "" + twice(n: n))
                }
                """.trimIndent(),
            ).any { it.contains("INT?") },
        )
    }

    /** Narrowing is EVIDENCE, not a guess: a test it does not understand changes nothing. */
    @Test
    fun `an unrelated condition narrows nothing`() {
        assertTrue(
            errors(
                """
                graph "probe"

                on start {
                    val n = maybe()
                    if true {
                        return
                    }
                    log(message: "" + twice(n: n))
                }
                """.trimIndent(),
            ).any { it.contains("INT?") },
        )
    }
}
