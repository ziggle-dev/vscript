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
 * `type Pair<A, B>` — a record generic in its fields.
 *
 * It parsed and was refused: *"the text front end does not understand a generic type yet"*. What it needed
 * was not new machinery but the two halves that already existed being joined — `unify` to learn what the
 * arguments are from the values supplied, and `model.substitute` to put them back when a field is read.
 *
 * **Erased.** A `StructValue` carries a name and its fields and no arguments, so the emitter is unchanged
 * and the VM never hears about any of this. Every question is answered by the resolver.
 */
class GenericRecordTest {

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

    @Test
    fun `a generic record declares, builds and reads back`() {
        assertEquals(
            listOf("1", "two"),
            run(
                """
                graph "p"

                type Pair<A, B> { first: A, second: B }

                on start {
                    val p = Pair { first: 1, second: "two" }
                    log(message: "" + p.first)
                    log(message: p.second)
                }
                """.trimIndent(),
            ),
        )
    }

    /** The arguments are learned from the values, so the field types are real and not wildcards. */
    @Test
    fun `an inferred argument is enforced`() {
        val e = errors(
            """
            graph "p"

            type Pair<A, B> { first: A, second: B }

            fn wantsInt(n: Int) -> Int = n

            on start {
                val p = Pair { first: 1, second: "two" }
                log(message: "" + wantsInt(n: p.second))
            }
            """.trimIndent(),
        )
        assertTrue(e.isNotEmpty(), "p.second is a String and must not pass for an Int")
    }

    @Test
    fun `a written argument types a parameter`() {
        assertEquals(
            listOf("3"),
            run(
                """
                graph "p"

                type Box<T> { value: T }

                fn firstOf(b: Box<Int>) -> Int = b.value

                on start {
                    log(message: "" + firstOf(b: Box { value: 3 }))
                }
                """.trimIndent(),
            ),
        )
    }

    /** Same declaration, different arguments — two types, and they do not stand in for each other. */
    @Test
    fun `differently applied arguments are different types`() {
        val e = errors(
            """
            graph "p"

            type Box<T> { value: T }

            fn wantsInts(b: Box<Int>) -> Int = b.value

            on start {
                log(message: "" + wantsInts(b: Box { value: "no" }))
            }
            """.trimIndent(),
        )
        assertTrue(e.isNotEmpty(), "a Box<String> must not pass for a Box<Int>")
    }

    @Test
    fun `the wrong number of arguments is refused, and says how many it takes`() {
        val e = errors(
            """
            graph "p"

            type Pair<A, B> { first: A, second: B }

            fn _listTake(p: Pair<Int>) -> Int = p.first

            on start { log(message: "" + _listTake(p: Pair { first: 1, second: 2 })) }
            """.trimIndent(),
        )
        assertTrue(e.any { "takes 2 type arguments" in it }, e.toString())
    }

    /** A parameter is scoped to its own declaration — a `T` nobody introduced is still a mistake. */
    @Test
    fun `a type variable does not leak to the next declaration`() {
        val e = errors(
            """
            graph "p"

            type Box<T> { value: T }
            type Loose { value: T }

            on start { log(message: "hi") }
            """.trimIndent(),
        )
        assertTrue(e.any { "T" in it }, e.toString())
    }

    /** Generic in a container, which is the shape that actually shows up. */
    @Test
    fun `a generic record may hold a list of its parameter`() {
        assertEquals(
            listOf("2"),
            run(
                """
                graph "p"

                type Bag<T> { items: LIST<T> = [] }

                on start {
                    val b = Bag { items: [10, 20] }
                    log(message: "" + _listCount(b.items))
                }
                """.trimIndent(),
            ),
        )
    }
}
