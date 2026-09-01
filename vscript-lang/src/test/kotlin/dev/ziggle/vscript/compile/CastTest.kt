package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.lang.Print
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.DriveResult
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `p as Vec2i` — one record read as another.
 *
 * **By name, narrowing, no zero-fill** — TypeScript's rule, and essentially every structurally-typed
 * language's. By name rather than by position because a record's field ORDER is otherwise cosmetic:
 * nobody expects reordering a declaration to change behaviour, and positional matching would silently
 * turn every cast of that type into garbage. `Point { x, y }` read as `Point2 { y, x }` type-checks
 * perfectly and swaps the coordinates.
 *
 * The rename clause is how you say two names mean the same thing — the case a by-name rule cannot guess
 * and a positional one would guess wrongly half the time.
 */
class CastTest {

    private val catalog = NodeCatalog(dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

    private val types = """
        type Vec3i { x: INT, y: INT, z: INT }
        type Vec2i { x: INT, y: INT }
        type Named { x: INT, y: INT, label: STRING }
    """.trimIndent()

    // ---- narrowing -------------------------------------------------------------------------------------

    @Test
    fun `a wider record narrows to a smaller one`() {
        val chunk = compile(
            """
            graph "probe"

            $types

            export var X: INT = 0
            export var Y: INT = 0

            on start {
                val full = Vec3i { x: 3, y: 4, z: 5 }
                val flat = full as Vec2i
                X = flat.x
                Y = flat.y
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(3, chunk.variable("X", run))
        assertEquals(4, chunk.variable("Y", run), "z is simply dropped")
    }

    @Test
    fun `a rename says which field a differently-named one comes from`() {
        // The case that made you ask: a Tile is x/y/plane and a Vec3i is x/y/z. Only a person knows
        // those mean the same thing, so a person says it.
        val chunk = compile(
            """
            graph "probe"

            $types

            export var X: INT = 0
            export var Z: INT = 0

            export fn asVec(spot: TILE) -> Vec3i = spot as Vec3i { z: plane }

            on start {
                val v = asVec(spot: tile(3200, 3210, 2))
                X = v.x
                Z = v.z
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(3200, chunk.variable("X", run), "x and y matched by name")
        assertEquals(2, chunk.variable("Z", run), "and plane filled z because the rename said so")
    }

    @Test
    fun `a record casts to a Tile too, since Tile is one`() {
        val chunk = compile(
            """
            graph "probe"

            $types

            export var Plane: INT = 0

            export fn planeOf(spot: TILE) -> INT = spot.plane

            on start {
                val v = Vec3i { x: 1, y: 2, z: 3 }
                Plane = planeOf(spot: v as Tile { plane: z })
            }
            """.trimIndent(),
        )
        assertEquals(3, chunk.variable("Plane", run(chunk)))
    }

    // ---- what it refuses ---------------------------------------------------------------------------------

    @Test
    fun `a target field with no source is refused, and the message says how to fix it`() {
        val errors = errorsIn(
            """
            graph "probe"

            $types

            export var X: INT = 0

            on start {
                val flat = Vec2i { x: 1, y: 2 }
                val full = flat as Vec3i
                X = full.z
            }
            """.trimIndent(),
        )
        assertTrue(errors.any { it.contains("nothing called 'z'") }, errors.toString())
        // Nothing is zero-filled: a zero is indistinguishable from a value that was genuinely zero.
        assertTrue(errors.any { it.contains("as Vec3i { z:") }, "the message should show the cure: $errors")
    }

    @Test
    fun `a field of the wrong type is refused even when the name matches`() {
        val errors = errorsIn(
            """
            graph "probe"

            $types

            export var X: INT = 0

            on start {
                val named = Named { x: 1, y: 2, label: "here" }
                val bad = named as Vec3i { z: label }
                X = bad.z
            }
            """.trimIndent(),
        )
        assertTrue(errors.any { it.contains("STRING") && it.contains("INT") }, errors.toString())
    }

    @Test
    fun `a rename naming a field nobody has is refused`() {
        val errors = errorsIn(
            """
            graph "probe"

            $types

            export var X: INT = 0

            on start {
                val full = Vec3i { x: 1, y: 2, z: 3 }
                val flat = full as Vec2i { x: nope }
                X = flat.x
            }
            """.trimIndent(),
        )
        assertTrue(errors.any { it.contains("'nope'") }, errors.toString())
    }

    @Test
    fun `casting something that is not a record is refused`() {
        val errors = errorsIn(
            """
            graph "probe"

            $types

            export var X: INT = 0

            on start {
                val n = 5
                val v = n as Vec2i
                X = v.x
            }
            """.trimIndent(),
        )
        assertTrue(errors.any { it.contains("reads one record as another") }, errors.toString())
    }

    // ---- it round trips ------------------------------------------------------------------------------------

    @Test
    fun `both forms print back as casts rather than as the record they compile to`() {
        // The reason this is a node at all. The expansion is indistinguishable from a hand-written
        // record literal, so lowering straight to one would make the cast vanish on the first round trip.
        val source = """
            graph "probe"

            export type Vec3i { x: INT, y: INT, z: INT }
            export type Vec2i { x: INT, y: INT }

            export var X: INT = 0

            on start {
                val full = Vec3i { x: 1, y: 2, z: 3 }
                val flat = full as Vec2i
                val back = flat as Vec3i { z: x }
                X = back.z
            }
        """.trimIndent() + "\n"
        assertEquals(source, Print(catalog, source = GraphSource.NONE).print(lower(source)))
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun lower(text: String): Graph {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val result = Lower(catalog, source = GraphSource.NONE).lower(parsed.program)
        assertEquals(emptyList(), result.errors.map { it.message }, "lowering")
        return result.graph
    }

    private fun errorsIn(text: String): List<String> {
        val parsed = Parser(Lexer(text).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { it.message }}")
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

    private fun run(chunk: Chunk): DriveResult {
        val result = drive(chunk, BuiltinHosts.registry())
        assertEquals(null, result.fiber.error?.message, "the run faulted")
        assertTrue(result.fiber.isFinished, "the run did not complete: ${result.fiber.state}")
        return result
    }

    private fun Chunk.variable(name: String, run: DriveResult): Any? =
        run.interpreter.globals.getOrNull(slots.variables[name] ?: error("no slot for '$name'"))
}
