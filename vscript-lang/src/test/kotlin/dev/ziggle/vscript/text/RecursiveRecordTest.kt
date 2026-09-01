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
 * A record may name itself. What it may not do is name itself in a way that cannot be built.
 *
 * The graph front end refuses self-reference outright — *"a record cannot hold one of its own kind"*
 * (`Validator.checkTypes`) — because `GraphCompiler.zeroOf` walks a record's fields to build a zero value
 * and a self-reference would recurse until the stack went. That is a fact about **that** zero-value walk,
 * not about the language: a linked list and a tree are ordinary shapes, and the text front end already ran
 * both. What it did NOT do was notice the case that genuinely cannot exist, so `type Loop { me: Loop }`
 * compiled and left the field null — a non-optional field holding null, which is the failure shape this
 * project treats as the expensive one.
 *
 * The rule is the one Rust reaches with `Box` and Kotlin with a nullable: a cycle is fine as long as some
 * step of it has a finite value of its own. Three of those exist here — an optional is null, a list is
 * empty, a map is empty — and a cycle through any of them terminates.
 */
class RecursiveRecordTest {

    private val STRING = TypeRef(PinType.STRING)
    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = read(src)
        val c = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(c, hosts)
        return said
    }

    @Test
    fun `a record may hold an optional one of itself`() {
        assertEquals(
            listOf("2"),
            run(
                """
                graph "p"

                type Node { value: Int = 0, next: Node? = null }

                on start {
                    val tail = Node { value: 2 }
                    val head = Node { value: 1, next: tail }
                    if val n = head.next { log(message: "" + n.value) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a record may hold a list of itself`() {
        assertEquals(
            listOf("leaf"),
            run(
                """
                graph "p"

                type Tree { label: String = "", kids: LIST<Tree> = [] }

                on start {
                    val leaf = Tree { label: "leaf" }
                    val root = Tree { label: "root", kids: [leaf] }
                    log(message: root.kids[0].label)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a cycle of required fields is refused, and says how to break it`() {
        val r = read(
            """
            graph "p"

            type Loop { me: Loop }

            on start { log(message: "x") }
            """.trimIndent(),
        )
        assertTrue(!r.ok, "a value of Loop would have to exist before one could be made")
        val m = r.errors.single().message
        assertTrue("cycle of required fields" in m, m)
        assertTrue("optional" in m && "LIST" in m, "the message has to name the way out: $m")
    }

    @Test
    fun `a cycle through two records is refused and names the ring`() {
        val r = read(
            """
            graph "p"

            type A { b: B }
            type B { a: A }

            on start { log(message: "x") }
            """.trimIndent(),
        )
        assertTrue(!r.ok)
        assertTrue(r.errors.any { "A -> B -> A" in it.message || "B -> A -> B" in it.message },
            r.errors.joinToString { it.message })
    }

    /** The mutual case, boxed on ONE side only — which is enough, and is how a parent pointer is written. */
    @Test
    fun `a mutual cycle with one optional step is allowed`() {
        val r = read(
            """
            graph "p"

            type Parent { kid: Kid? = null }
            type Kid { owner: Parent? = null }

            on start { log(message: "x") }
            """.trimIndent(),
        )
        assertTrue(r.ok, r.errors.joinToString { it.message })
    }
}
