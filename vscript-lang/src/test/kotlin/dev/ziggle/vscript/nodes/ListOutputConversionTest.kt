package dev.ziggle.vscript.nodes

import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostFn
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.StructValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A node returning a LIST of tiles converts its ELEMENTS, not just whole-pin values.
 *
 * **The bug this pins down produced a runtime error from a graph the compiler had just accepted.** A node
 * whose pin is `TILE` had its result turned into a record, so `t.plane` read. A node whose pin is
 * `LIST<TILE>` did not — the converter matched on the pin's own type, which is `LIST`, and never looked at
 * what the list was OF. So the elements arrived as raw host objects and the first field read failed with
 * "GETFIELD on Tile, expected a record".
 *
 * **Traversal belongs to the language, not to the host.** A converter knows what an `dev.ziggle.api.Tile`
 * is; that a `LIST<TILE>` is a list whose elements are tiles is a fact about the type system. Leaving it
 * to each host would be asking every one of them to get the same walk right.
 */
class ListOutputConversionTest {

    // `Tile` is a type the HOST declares now, so a test that names it registers it. See TileFixture.
    init { dev.ziggle.vscript.domain.TileFixture.register() }

    /** Stands in for the client's own tile type — something the language knows nothing about. */
    private class HostTile(val x: Int, val y: Int, val plane: Int)

    /** The client's converter, in miniature: it knows [HostTile] and nothing else. */
    private object Converter : OutputConverter {
        override fun handles(type: TypeRef): Boolean = type == dev.ziggle.vscript.domain.TileFixture.TYPE

        override fun convert(type: TypeRef, value: Any?): Any? {
            if (value is HostTile) return dev.ziggle.vscript.domain.TileFixture.of(value.x, value.y, value.plane)
            return dev.ziggle.vscript.domain.TileFixture.read(value) ?: value
        }
    }

    private fun library(output: PinSpec, result: Any?): HostRegistry {
        val def = NodeDef(
            NodeDescriptor(
                "test.tiles", "Tiles", "Test", NodeKind.PURE,
                outputs = listOf(output),
                host = "tiles",
                hostKind = HostKind.INLINE,
            ),
            HostFn { result },
        )
        return NodeLibrary(listOf(def)).install(HostRegistry(), Converter)
    }

    private fun call(hosts: HostRegistry): Any? =
        hosts.get("tiles")!!.fn.invoke(emptyArray())

    @Test
    fun `a list of tiles comes back as records`() {
        val hosts = library(
            PinSpec("Path", dev.ziggle.vscript.model.TypeRef.list(dev.ziggle.vscript.domain.TileFixture.TYPE)),
            listOf(HostTile(3200, 3210, 0), HostTile(3201, 3211, 1)),
        )
        val out = call(hosts) as List<*>
        assertEquals(2, out.size)

        val first = out[0] as? StructValue
        assertTrue(first != null, "element 0 came back as ${out[0]?.javaClass?.simpleName}, not a record")
        assertEquals(3200, (first.get("x") as Number).toInt())
        assertEquals(3210, (first.get("y") as Number).toInt())
        assertEquals(0, (first.get("plane") as Number).toInt())

        val second = out[1] as? StructValue
        assertTrue(second != null, "element 1 was not converted — only the first was")
        assertEquals(1, (second.get("plane") as Number).toInt())
    }

    /** The single-tile case still works, which is what made the list case easy to miss. */
    @Test
    fun `a single tile still comes back as a record`() {
        val hosts = library(PinSpec("Tile", dev.ziggle.vscript.domain.TileFixture.TYPE), HostTile(100, 200, 2))
        val out = call(hosts) as? StructValue
        assertTrue(out != null, "a plain TILE pin must still convert")
        assertEquals(100, (out.get("x") as Number).toInt())
    }

    /** A list of something the converter does not handle is passed through untouched. */
    @Test
    fun `a list of other things is left alone`() {
        val hosts = library(
            PinSpec("Names", PinType.LIST, elementType = PinType.STRING),
            listOf("a", "b"),
        )
        assertEquals(listOf("a", "b"), call(hosts))
    }

    /**
     * An empty list survives, and a null does not become one.
     *
     * The traversal maps, and mapping nothing has to stay nothing rather than turning into a list with a
     * converted null in it.
     */
    @Test
    fun `an empty list stays empty`() {
        val hosts = library(
            PinSpec("Path", dev.ziggle.vscript.model.TypeRef.list(dev.ziggle.vscript.domain.TileFixture.TYPE)),
            emptyList<Any?>(),
        )
        assertEquals(emptyList<Any?>(), call(hosts))
    }

    @Test
    fun `a null list is not turned into a list`() {
        val hosts = library(PinSpec("Path", dev.ziggle.vscript.model.TypeRef.list(dev.ziggle.vscript.domain.TileFixture.TYPE)), null)
        assertEquals(null, call(hosts))
    }
}
