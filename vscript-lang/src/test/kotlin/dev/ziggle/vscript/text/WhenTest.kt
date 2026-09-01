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

/** `when`, both forms, and the lambda written after the closing paren. */
class WhenTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun script(body: String) = """
        graph "probe"

        on start {
        $body
        }
    """.trimIndent()

    @Test
    fun `a when with a subject picks the matching arm`() {
        assertEquals(
            listOf("two"),
            run(
                script(
                    """    val n = 2
    when n {
        1 -> log(message: "one")
        2 -> log(message: "two")
        else -> log(message: "other")
    }""",
                ),
            ),
        )
    }

    @Test
    fun `a when falls through to else`() {
        assertEquals(
            listOf("other"),
            run(
                script(
                    """    val n = 9
    when n {
        1 -> log(message: "one")
        else -> log(message: "other")
    }""",
                ),
            ),
        )
    }

    /** With no subject each arm is its own condition — the `cond -> …` form. */
    @Test
    fun `a when without a subject tests each arm`() {
        assertEquals(
            listOf("big"),
            run(
                script(
                    """    val n = 9
    when {
        n < 5 -> log(message: "small")
        n < 20 -> log(message: "big")
        else -> log(message: "huge")
    }""",
                ),
            ),
        )
    }

    @Test
    fun `only the first matching arm runs`() {
        assertEquals(
            listOf("first"),
            run(
                script(
                    """    when {
        true -> log(message: "first")
        true -> log(message: "second")
    }""",
                ),
            ),
        )
    }

    @Test
    fun `an arm that cannot match the subject is refused`() {
        assertTrue(
            read(
                script(
                    """    val n = 2
    when n {
        "two" -> log(message: "two")
    }""",
                ),
            ).errors.any { it.message.contains("cannot be matched") },
        )
    }

    // ---- trailing lambdas ----------------------------------------------------------------------------

    @Test
    fun `a lambda may be written after the closing paren`() {
        assertEquals(
            listOf("1", "2"),
            run(
                """
                graph "probe"

                fn each(list: LIST<INT>, f: fn(INT)) {
                    for x in list {
                        f(x)
                    }
                }

                on start {
                    each(list: [1, 2]) { log(message: "" + it) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a trailing lambda fills the function parameter whatever else was supplied`() {
        assertEquals(
            listOf("a1"),
            run(
                """
                graph "probe"

                fn tag(prefix: STRING, f: fn(STRING)) {
                    f(prefix + "1")
                }

                on start {
                    tag(prefix: "a") { log(message: it) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a trailing lambda on something that takes no function is refused`() {
        assertTrue(
            read("""graph "probe"${'\n'}${'\n'}on start { log(message: "x") { 1 } }""")
                .errors.any { it.message.contains("takes no function") },
        )
    }
}
