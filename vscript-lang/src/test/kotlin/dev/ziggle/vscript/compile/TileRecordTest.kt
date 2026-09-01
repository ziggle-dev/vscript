package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.DriveResult
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.StructValue
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Tile` and `Color` as records the language knows the shape of.
 *
 * **The pin type is unchanged, and that is the point.** `dev.ziggle.vscript.domain.TileFixture.TYPE` still exists, so the canvas keeps
 * its picker and a `Vec3i` still cannot be wired where a tile belongs. What is new is that the language
 * knows the FIELDS, so `t.plane` reads and `Tile { … }` constructs — both through the struct machinery
 * that already existed, with nothing added to the VM.
 *
 * The names cost nothing to unify: `TypeRef.named` interns case-insensitively against the built-in kinds,
 * so `TypeRef.named("Tile")` already IS `dev.ziggle.vscript.domain.TileFixture.TYPE`. A `Tile { … }` literal's type is the tile
 * pin type by identity rather than by a compatibility rule.
 */
class TileRecordTest {

    // `Tile` is a type the HOST declares now, so a test that names it registers it. See TileFixture.
    init {
        dev.ziggle.vscript.domain.TileFixture.register()
        dev.ziggle.vscript.domain.ColorFixture.register()
    }

    /** A host node handing back a tile, standing in for `game.playerTile`. */
    private val here = hostNode(
        "test.here", "here", NodeKind.PURE,
        outputs = listOf(PinSpec("Tile", dev.ziggle.vscript.domain.TileFixture.TYPE)),
    )

    /** And one taking a tile, standing in for `game.walkTo`. */
    private val walk = hostNode(
        "test.walk", "walk", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Tile", dev.ziggle.vscript.domain.TileFixture.TYPE)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Went", PinType.STRING)),
    )

    private val paint = hostNode(
        "test.paint", "paint", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Color", dev.ziggle.vscript.domain.ColorFixture.TYPE, default = 0xFF00FFFF.toInt())),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Painted", PinType.STRING)),
    )

    private val catalog = NodeCatalog(
        listOf(here, walk, paint) +
            dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS +
            dev.ziggle.vscript.domain.ColorFixture.DESCRIPTORS,
    )

    private class Host {
        var lastTile: Any? = null
        var lastColor: Any? = null

        fun registry(): HostRegistry = BuiltinHosts.registry()
            // The canonical runtime form: a record. The client's own boundary decodes the same three
            // shapes — see GameValues.tileRecord.
            .register("here", HostKind.INLINE, results = 1) {
                dev.ziggle.vscript.domain.TileFixture.read("3200,3210,1")
            }
            .register("walk", HostKind.INLINE, arity = 1, results = 1) { a ->
                lastTile = a.getOrNull(0); "ok"
            }
            .register("paint", HostKind.INLINE, arity = 1, results = 1) { a ->
                lastColor = a.getOrNull(0); "ok"
            }
    }

    // ---- reading a tile's fields -----------------------------------------------------------------------

    @Test
    fun `a tile from a node can be taken apart`() {
        val host = Host()
        val chunk = compile(
            """
            graph "probe"

            export var X: INT = 0
            export var Y: INT = 0
            export var P: INT = 0

            on start {
                val spot = here()
                X = spot.x
                Y = spot.y
                P = spot.plane
            }
            """.trimIndent(),
        )
        val run = run(chunk, host)

        assertEquals(3200, chunk.variable("X", run))
        assertEquals(3210, chunk.variable("Y", run))
        assertEquals(1, chunk.variable("P", run), "plane, not z — it is the game's word")
    }

    // ---- building one -----------------------------------------------------------------------------------

    @Test
    fun `a tile can be built from parts and handed to a node that wants one`() {
        // The shape the whole change exists for: read a tile, offset it, walk there.
        val host = Host()
        val chunk = compile(
            """
            graph "probe"

            on start {
                val spot = here()
                walk(tile: Tile { x: spot.x + 5, y: spot.y - 2, plane: spot.plane })
            }
            """.trimIndent(),
        )
        run(chunk, host)

        val tile = host.lastTile as? StructValue ?: error("expected a Tile record, got ${host.lastTile}")
        assertEquals("Tile", tile.type)
        assertEquals(3205, tile.get("x"))
        assertEquals(3208, tile.get("y"))
        assertEquals(1, tile.get("plane"))
    }

    @Test
    fun `a typed-in tile literal arrives as a record too`() {
        // The stored form is still the "x,y,plane" string the picker edits — the conversion happens on the
        // way into the constant pool, so existing documents keep working and gain fields.
        val host = Host()
        val chunk = compile(
            """
            graph "probe"

            on start {
                walk(tile: tile(3000, 3100, 2))
            }
            """.trimIndent(),
        )
        run(chunk, host)

        val tile = host.lastTile as? StructValue ?: error("expected a Tile record, got ${host.lastTile}")
        assertEquals(3000, tile.get("x"))
        assertEquals(3100, tile.get("y"))
        assertEquals(2, tile.get("plane"))
    }

    // ---- colours ------------------------------------------------------------------------------------------

    @Test
    fun `a colour literal arrives as channels, alpha included`() {
        val host = Host()
        val chunk = compile(
            """
            graph "probe"

            on start {
                paint(color: 0x80FF8040)
            }
            """.trimIndent(),
        )
        run(chunk, host)

        val c = host.lastColor as? StructValue ?: error("expected a Color record, got ${host.lastColor}")
        assertEquals(0xFF, c.get("r"))
        assertEquals(0x80, c.get("g"))
        assertEquals(0x40, c.get("b"))
        assertEquals(0x80, c.get("a"), "alpha survives — dropping it makes every overlay opaque")
    }

    @Test
    fun `a colour can be built from channels`() {
        val host = Host()
        val chunk = compile(
            """
            graph "probe"

            on start {
                paint(color: Color { r: 10, g: 20, b: 30, a: 255 })
            }
            """.trimIndent(),
        )
        run(chunk, host)

        val c = host.lastColor as? StructValue ?: error("expected a Color record, got ${host.lastColor}")
        assertEquals(10, c.get("r"))
        assertEquals(255, c.get("a"))
    }

    // ---- the pin type still means something --------------------------------------------------------------

    @Test
    fun `a lookalike record is still refused where a tile belongs`() {
        // The reason TILE stays a PinType rather than becoming an ordinary declared type. A Vec3i has the
        // same shape and is not a tile; if the pin took any three-int record the picker would be the only
        // thing left telling them apart.
        val errors = errorsIn(
            """
            graph "probe"

            export type Vec3i { x: INT, y: INT, plane: INT }

            on start {
                walk(tile: Vec3i { x: 1, y: 2, plane: 0 })
            }
            """.trimIndent(),
        )
        assertTrue(errors.any { it.contains("Vec3i") }, "a Vec3i is not a Tile, got $errors")
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun lower(text: String): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val result = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertEquals(emptyList(), result.errors.map { it.message }, "lowering errors")
        return result.graph
    }

    private fun errorsIn(text: String): List<String> {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors ${parsed.errors.map { it.message }}")
        val lowered = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        return lowered.errors.map { it.message } +
            Validator(catalog).validate(lowered.graph).errors().map { it.message }
    }

    private fun compile(text: String): Chunk {
        val graph = lower(text)
        assertEquals(emptyList(), Validator(catalog).validate(graph).errors().map { it.message })
        val entry = graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        return GraphCompiler(catalog, debug = false).compile(graph, entry.id)
    }

    private fun run(chunk: Chunk, host: Host): DriveResult {
        val result = drive(chunk, host.registry())
        assertEquals(null, result.fiber.error?.message, "the run faulted")
        assertTrue(result.fiber.isFinished, "the run did not complete: ${result.fiber.state}")
        return result
    }

    private fun Chunk.variable(name: String, run: DriveResult): Any? {
        val slot = slots.variables[name] ?: error("no slot for '$name'")
        return run.interpreter.globals.getOrNull(slot)
    }

    // ---- a tile LITERAL bound to a local ---------------------------------------------------------------
    //
    // `let spot = here()` always worked: the Hold is fed by a wire and the pin on the other end says TILE.
    // A literal has no wire — `tile(…)` lowers to the text `"3200,3210,1"` typed straight into the Hold —
    // and a value that is text cannot say what KIND of text it is, so the local stayed WILDCARD and
    // `spot.x` reported "this has no field 'x'" on a value that is plainly a record. It was listed under
    // Known limits in the language reference.
    //
    // The ambiguity is in the value and not in the SOURCE, which is why the answer is recorded where the
    // expression still exists.

    @Test
    fun `a tile LITERAL bound to a local can be taken apart`() {
        val host = Host()
        val chunk = compile(
            """
            graph "probe"

            export var X: INT = 0
            export var P: INT = 0

            on start {
                val spot = tile(3200, 3210, 1)
                X = spot.x
                P = spot.plane
            }
            """.trimIndent(),
        )
        val r = run(chunk, host)
        assertEquals(3200, chunk.variable("X", r))
        assertEquals(1, chunk.variable("P", r))
    }

    /** And it is a real tile at the other end, not a string that happens to look like one. */
    @Test
    fun `a local holding a tile literal wires into a TILE pin`() {
        val host = Host()
        val chunk = compile(
            """
            graph "probe"

            on start {
                val spot = tile(3200, 3210, 1)
                walk(tile: spot)
            }
            """.trimIndent(),
        )
        run(chunk, host)
        assertTrue(host.lastTile is StructValue, "a tile should arrive as a record: ${host.lastTile}")
    }

    /**
     * The inferred type is not printed back, which is what keeps it admissible.
     *
     * `HOLD_TYPE` would have done the typing just as well and would also have written `let spot: TILE = …`
     * into a document whose author wrote no such thing — a round trip that changes the text.
     */
    @Test
    fun `the inferred type of a local is never printed`() {
        val src = """
            graph "probe"

            on start {
                val spot = tile(3200, 3210, 1)
                walk(tile: spot)
            }
        """.trimIndent()
        val printed = dev.ziggle.vscript.lang.Print(catalog).print(lower(src))
        assertTrue("val spot = tile(3200, 3210, 1)" in printed, printed)
        assertTrue(": TILE" !in printed, "nobody typed a declared type here: $printed")
    }

    // ---- a tile written straight into a wildcard pin — GAPS #6 ---------------------------------------

    /**
     * `if tile(1, 2, 0) == tile(1, 2, 0)` used to come back as `if "1,2,0" == "1,2,0"`.
     *
     * A comparison's pins are WILDCARD, so the printer had only the pin type to go on, and a tile IS that
     * string underneath. Behaviour never changed — which is exactly what made it easy to leave: the file
     * simply got worse every time a tool touched it, and the reader lost the fact that these were tiles.
     *
     * The local case above already worked, because a Hold records what its initialiser was. This is the
     * same answer one level down, keyed per pin.
     */
    @Test
    fun `a tile compared inline prints back as a tile`() {
        val src = """
            graph "probe"

            on start {
                if tile(1, 2, 0) == tile(1, 2, 0) {
                    walk(tile: tile(3, 4, 0))
                }
            }
        """.trimIndent()
        val printed = dev.ziggle.vscript.lang.Print(catalog).print(lower(src))
        assertTrue("tile(1, 2, 0) == tile(1, 2, 0)" in printed, "expected tiles, got:\n$printed")
        assertTrue("\"1,2,0\"" !in printed, "a tile should not degrade to a string:\n$printed")
    }

    /** And it survives the trip, so the second print is the same as the first. */
    @Test
    fun `an inline tile round-trips character for character`() {
        val src = """
            graph "probe"

            on start {
                if tile(1, 2, 0) == tile(3, 4, 5) {
                    walk(tile: tile(3, 4, 0))
                }
            }
        """.trimIndent()
        val once = dev.ziggle.vscript.lang.Print(catalog).print(lower(src))
        assertEquals(src.trim(), once.trim(), "round trip")
    }

    /**
     * A pin that DOES say what it holds keeps deciding — the hint is a fallback, never a second opinion.
     * `walk(tile:)` is a TILE pin and printed correctly long before any of this.
     */
    @Test
    fun `a typed pin still decides for itself`() {
        val src = """
            graph "probe"

            on start {
                walk(tile: tile(3200, 3210, 1))
            }
        """.trimIndent()
        assertTrue(
            "walk(tile: tile(3200, 3210, 1))" in dev.ziggle.vscript.lang.Print(catalog).print(lower(src)),
        )
    }

    /** A plain string in a wildcard pin is still a plain string — nothing was taught to guess. */
    @Test
    fun `a string in a wildcard pin is unaffected`() {
        val src = """
            graph "probe"

            on start {
                if "a" == "b" {
                    walk(tile: tile(3, 4, 0))
                }
            }
        """.trimIndent()
        assertEquals(src.trim(), dev.ziggle.vscript.lang.Print(catalog).print(lower(src)).trim())
    }
}
