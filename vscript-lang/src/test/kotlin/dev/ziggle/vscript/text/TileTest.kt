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
 * A tile is a TYPE, and `tile(…)` is an ordinary built-in function that builds one.
 *
 * It used to be neither: a string `"x,y,plane"` with a reserved word spelling it, which is a good answer to
 * "how does a tile get copied off a wiki page" and no answer at all to `t.x`. There is no string form left
 * in the language — and none was needed at the boundary either, because the rest of the system had already
 * moved: the graph converts at the pin, `GameValues.tileRecord` builds the record and `Args.tile` reads it.
 */
class TileTest {

    // `Tile` is a type the HOST declares now, so a test that names it registers it. See TileFixture.
    init { dev.ziggle.vscript.domain.TileFixture.register() }

    private val STRING = TypeRef(PinType.STRING)
    private val TILE = dev.ziggle.vscript.domain.TileFixture.TYPE

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            // Takes a tile the way every real node does: as text.
            NativeFn("walkTo", listOf(NativeParam("where", TILE)), results = emptyList()),
            NativeFn("here", results = outs(TILE)),
        ) + dev.ziggle.vscript.domain.TileFixture.NATIVES,
    )

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        // A node reads a tile with `Args.tile`, which takes the record — so these do what one does.
        hosts.register("walkTo", HostKind.INLINE, arity = 1, results = 0) { a ->
            val t = a[0] as dev.ziggle.vscript.vm.StructValue
            said += "walk ${t.get("x")},${t.get("y")},${t.get("plane")}"
            null
        }
        hosts.register("here", HostKind.INLINE, arity = 0, results = 1) {
            dev.ziggle.vscript.domain.TileFixture.read("3200,3201,1")
        }
        val r = TextFrontEnd(natives).read(src)
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

    /** The whole point: a tile has fields, and they can be read. */
    @Test
    fun `a tile's fields can be read`() {
        assertEquals(
            listOf("3200 3201 1"),
            run(script("""    val t = tile(3200, 3201, 1)${'\n'}    log(message: "" + t.x + " " + t.y + " " + t.plane)""")),
        )
    }

    @Test
    fun `plane defaults to the surface`() {
        assertEquals(listOf("0"), run(script("""    log(message: "" + tile(3200, 3201).plane)""")))
    }

    /** A node takes the record, because `Args.tile` has read one since tiles became records. */
    @Test
    fun `a tile crosses to a native as a record`() {
        assertEquals(listOf("walk 3200,3201,1"), run(script("""    walkTo(where: tile(3200, 3201, 1))""")))
    }

    /** And a tile that came OUT of a node is the same record — `GameValueOut` already builds one. */
    @Test
    fun `a tile from a native is a record`() {
        assertEquals(
            listOf("3201"),
            run(script("""    val t = here()${'\n'}    log(message: "" + t.y)""")),
        )
    }

    @Test
    fun `a tile round-trips through a native and back`() {
        assertEquals(listOf("walk 3200,3201,1"), run(script("""    walkTo(where: here())""")))
    }

    /** Built from values, not only from literals — it is a function, not a spelling. */
    @Test
    fun `a tile may be built from variables`() {
        assertEquals(
            listOf("walk 10,20,0"),
            run(script("""    val x = 10${'\n'}    val y = 20${'\n'}    walkTo(where: tile(x, y))""")),
        )
    }

    @Test
    fun `an unknown field of a tile is refused`() {
        val r = TextFrontEnd(natives).read(script("""    log(message: "" + tile(1, 2).z)"""))
        assertTrue(r.errors.any { it.message.contains("z") }, "got: ${r.errors.map { it.message }}")
    }
}
