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
 * `a?.b` and `x is T` — the two the checker knew about and the emitter did not.
 *
 * The pairing is why `?.` came first: the resolver's own message for an unnarrowed optional is
 * *"may be absent — check it, or use '?.'"*, and `?.` then refused to compile. The checker was
 * recommending a construct that did not work.
 */
class SafeAccessAndIsTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(src: String) = TextFrontEnd(natives).read(src).errors.map { it.message }

    // ---- `?.` ----------------------------------------------------------------------------------------

    @Test
    fun `safe access reads through a present receiver`() {
        assertEquals(
            listOf("2"),
            run(
                """
                graph "p"

                type Node { value: Int = 0, next: Node? = null }

                on start {
                    val head = Node { value: 1, next: Node { value: 2 } }
                    log(message: "" + (head.next?.value ?: -1))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `safe access answers null for an absent receiver, and does not run the access`() {
        assertEquals(
            listOf("-1"),
            run(
                """
                graph "p"

                type Node { value: Int = 0, next: Node? = null }

                on start {
                    val lone = Node { value: 1 }
                    log(message: "" + (lone.next?.value ?: -1))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **`?.` guards exactly one step**, so `a?.b.c` is `(a?.b).c` and the `.c` is reading through an
     * optional — which is refused. Kotlin's rule, and the reason is the same: the alternative silently
     * makes one `?` cover a chain the author only guarded the front of.
     */
    @Test
    fun `a safe access guards one step, not the rest of the chain`() {
        val e = errors(
            """
            graph "p"

            type Inner { value: Int = 0 }
            type Outer { inner: Inner = Inner { }, next: Outer? = null }

            on start {
                val here = Outer { inner: Inner { value: 3 } }
                val there = Outer { next: here }
                log(message: "" + (there.next?.inner.value ?: -1))
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "may be absent" in it }, e.toString())

        // Written with a `?.` at each step it reads through, and answers null at the first absence.
        assertEquals(
            listOf("3", "-1"),
            run(
                """
                graph "p"

                type Inner { value: Int = 0 }
                type Outer { inner: Inner = Inner { }, next: Outer? = null }

                on start {
                    val here = Outer { inner: Inner { value: 3 } }
                    val there = Outer { next: here }
                    log(message: "" + (there.next?.inner?.value ?: -1))
                    log(message: "" + (here.next?.inner?.value ?: -1))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `safe access nests`() {
        assertEquals(
            listOf("7"),
            run(
                """
                graph "p"

                type Leaf { value: Int = 0 }
                type Mid { leaf: Leaf? = null }
                type Top { mid: Mid? = null }

                on start {
                    val t = Top { mid: Mid { leaf: Leaf { value: 7 } } }
                    log(message: "" + (t.mid?.leaf?.value ?: -1))
                }
                """.trimIndent(),
            ),
        )
    }

    /** The result of a `?.` is optional whatever the access produced — that is the point of it. */
    @Test
    fun `the result of a safe access is optional`() {
        val e = errors(
            """
            graph "p"

            type Node { value: Int = 0, next: Node? = null }

            fn needsInt(n: Int) -> Int = n

            on start {
                val lone = Node { value: 1 }
                log(message: "" + needsInt(n: lone.next?.value))
            }
            """.trimIndent(),
        )
        assertTrue(e.isNotEmpty(), "an Int? must not pass for an Int")
    }

    // ---- `is` ----------------------------------------------------------------------------------------

    @Test
    fun `is answers for a record and for a builtin`() {
        assertEquals(
            listOf("true", "false", "true", "true"),
            run(
                """
                graph "p"

                type Point { x: Int = 0 }
                type Other { y: Int = 0 }

                on start {
                    val p = Point { x: 1 }
                    log(message: "" + (p is Point))
                    log(message: "" + (p is Other))
                    log(message: "" + (p !is Other))
                    log(message: "" + (3 is Int))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `is refuses a type nobody declared`() {
        val e = errors(
            """
            graph "p"

            on start {
                log(message: "" + (3 is Nonesuch))
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "Nonesuch" in it }, e.toString())
    }

    // ---- and one that is deliberately NOT coming ------------------------------------------------------

    /**
     * `sequence` is a canvas construct, not a missing feature.
     *
     * A graph node has one exec output per arm and needs something to say "run these in order"; text
     * statements already do. So it is refused with that reason rather than with "does not understand …
     * yet", which would promise a feature nobody should wait for.
     */
    @Test
    fun `sequence is refused as a canvas construct, not as unimplemented`() {
        val e = errors(
            """
            graph "p"

            on start {
                sequence {
                    log(message: "one")
                } {
                    log(message: "two")
                }
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "canvas construct" in it }, e.toString())
        assertTrue(e.none { "does not understand" in it }, e.toString())
    }
}
