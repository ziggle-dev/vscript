package dev.ziggle.vscript.vm

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lists that come back from a HOST, which are not the lists the VM makes for itself.
 *
 * **Kotlin's read-only `List` is not a `MutableList` at run time.** A node ending in `.toList()` — most of
 * the scene queries do — returns `kotlin.collections.EmptyList` when it found nothing, and that satisfies
 * `is List<*>` while failing `as? MutableList`. The VM then said "expected a List, got List": a message
 * that reads like a typo and is really the distinction being invisible from inside.
 *
 * The empty case is the one that broke, which is what made it nasty — one match comes back as a Java
 * singleton list and passes, so `objectsNamed(…)` worked right up until the object was not there.
 */
class HostListTest {

    private val findNode = hostNode(
        "test.find", "find", NodeKind.PURE,
        inputs = listOf(PinSpec("Count", PinType.INT)),
        outputs = listOf(PinSpec("Items", PinType.LIST, elementType = PinType.INT)),
    )
    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(findNode, sayNode))

    /** [n] items, built the way a real node builds them — so 0 gives Kotlin's read-only `EmptyList`. */
    private fun found(n: Int): List<Any?> = (1..n).map { it.toLong() }.filter { true }.toList()

    private fun said(src: String): List<Any?> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")

        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("find", HostKind.INLINE, arity = 1) { a -> found(Values.toInt(a[0])) }
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val entry = low.graph.entries(catalog).single()
        drive(GraphCompiler(catalog, debug = false).compile(low.graph, entry.id), hosts, maxTicks = 400)
        return out
    }

    @Test
    fun `an empty list from a host can be iterated`() {
        // Proves the premise rather than trusting it: this is the value the node actually hands over.
        assertTrue(found(0) !is MutableList<*>, "the empty case should be a read-only list")

        assertEquals(
            listOf("done"),
            said(
                """
                on start {
                    for x in find(count: 0) {
                        say(x)
                    }
                    say("done")
                }
                """.trimIndent(),
            ),
        )
    }
}
