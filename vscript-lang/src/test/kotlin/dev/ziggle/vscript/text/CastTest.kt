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
 * `readJson(path: …) as Break` — a decode, through the same host the graph calls.
 *
 * One decoder, given the same schema, so the two surfaces cannot disagree about what a saved file means.
 * The answer is OPTIONAL and that is not a hedge: the file may be absent, truncated, or written by an
 * older shape, and every use in the corpus already wraps it in `if val`.
 */
class CastTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("readJson", listOf(NativeParam("path", STRING)), results = outs(TypeRef.WILDCARD)),
        ),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String, json: Any?): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("readJson", HostKind.INLINE, arity = 1, results = 1) { json }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private val script = """
        graph "probe"

        type Save { laps: INT, bank: STRING }

        on start {
            if val saved = readJson(path: "state.json") as Save {
                log(message: saved.bank + " " + saved.laps)
            } else {
                log(message: "nothing saved")
            }
        }
    """.trimIndent()

    @Test
    fun `a record is decoded from json`() {
        assertEquals(
            listOf("Varrock 3"),
            run(script, mapOf("laps" to 3, "bank" to "Varrock")),
        )
    }

    /** Absent is the ordinary case — the file may simply not be there yet. */
    @Test
    fun `nothing decodes to nothing`() {
        assertEquals(listOf("nothing saved"), run(script, null))
    }

    @Test
    fun `the result is optional`() {
        val r = read(
            """
            graph "probe"

            type Save { laps: INT }

            on start {
                val s = readJson(path: "x") as Save
            }
            """.trimIndent(),
        )
        assertTrue(r.resolution?.ok == true, "did not resolve: ${r.errors.map { it.message }}")
        assertTrue(r.resolution!!.localOf.values.first { it.name == "s" }.type.optional)
    }

    @Test
    fun `an unknown target type is refused`() {
        assertTrue(
            read("""graph "probe"${'\n'}${'\n'}on start { val s = readJson(path: "x") as Nope }""")
                .errors.any { it.message.contains("Nope") },
        )
    }
}
