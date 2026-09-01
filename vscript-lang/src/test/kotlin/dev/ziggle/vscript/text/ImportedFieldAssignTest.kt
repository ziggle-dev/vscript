package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A field of an IMPORTED record can be assigned, not only read.
 *
 * The resolver looked a field-assignment's record up in this document's own table and the prelude —
 * `records[record.name] ?: Prelude.record(record)` — and nowhere else, while a field READ went through
 * `recordFor` and so found the declaration by owner or by import. So `o.want = 1` on an imported `Order`
 * reported *"Order has no fields to assign"* about a record whose fields the very next line could read.
 *
 * It survived because every record anyone had assigned into happened to be declared in the file doing the
 * assigning: `goal.vs` mutates its own `Goal`, `activity.vs` its own `Activity`. It surfaced the moment
 * the scheduler stopped publishing scalars and started holding an `Order` from another document.
 */
class ImportedFieldAssignTest {

    private val lib = """
        graph "lib"
        export type Order { item: String = "", want: Int = 0 }
        export type Box<T> { value: T }
        export { Order, Box }
    """.trimIndent()

    private fun read(src: String) =
        TextFrontEnd(NodeCatalog().natives(), imports = TextSource.of(mapOf("lib" to lib))).read(src)

    private fun errors(src: String) = read(src).errors.map { it.message }

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("vscript.log", HostKind.INLINE, arity = 2, results = 0) { a -> said += a[0].toString(); null }
        val r = read(src)
        drive(r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" }), hosts)
        return said
    }

    @Test
    fun `a field of an imported record can be assigned`() {
        assertEquals(
            listOf("shark x4"),
            run(
                """
                graph "p"
                import "lib"
                on start {
                    var o = Order { }
                    o.item = "shark"
                    o.want = 4
                    log(message: o.item + " x" + o.want)
                }
                """.trimIndent(),
            ),
        )
    }

    /** The shape that found it: one out of a map, defaulted with an elvis, then edited. */
    @Test
    fun `an imported record taken out of a map can be edited`() {
        assertEquals(
            listOf("2"),
            run(
                """
                graph "p"
                import "lib"
                on start {
                    var m: MAP<String, Order> = _newMap()
                    var o = _mapAt(map: m, key: "k") ?: Order { }
                    o.want = o.want + 2
                    m = _mapWith(map: m, key: "k", value: o)
                    log(message: "" + (_mapAt(map: m, key: "k")?.want ?: 0))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A generic record's field takes the type its ARGUMENT gives it on the way in, as it does on the way out. */
    @Test
    fun `an imported generic record's field is assigned at its bound type`() {
        assertEquals(
            listOf("9"),
            run(
                """
                graph "p"
                import "lib"
                on start {
                    var b: Box<Int> = Box { value: 0 }
                    b.value = 9
                    log(message: "" + b.value)
                }
                """.trimIndent(),
            ),
        )
        assertTrue(
            errors(
                """
                graph "p"
                import "lib"
                on start {
                    var b: Box<Int> = Box { value: 0 }
                    b.value = "nine"
                }
                """.trimIndent(),
            ).any { it.contains("INT") },
            "a Box<Int>'s value must still refuse a string",
        )
    }

    /** A field that does not exist is still named, and now names the record it looked in. */
    @Test
    fun `an unknown field on an imported record is refused by name`() {
        assertTrue(
            errors(
                """
                graph "p"
                import "lib"
                on start { var o = Order { }
                o.nope = 1 }
                """.trimIndent(),
            ).any { it.contains("'Order' has no field 'nope'") },
        )
    }
}
