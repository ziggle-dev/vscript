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
 * `x is Int`, `x !is Point` — a run-time type test.
 *
 * **A test and never a narrowing**, which is what makes it possible at all. A type here belongs to a PIN
 * rather than to a point in the exec chain, so `if x is Int { … }` has nowhere to record that `x` is an
 * INT inside the branch — and on the canvas the wire in is the same wire as the one outside. Answering
 * yes or no needs none of that machinery.
 *
 * The interesting half is what it REFUSES. An ITEM, an NPC and an OBJECT are all `Int` at run time and a
 * SKILL and an ENUM are both `String`, so a test against one of those would answer about the underlying
 * kind — a script branching on it would be branching on a coin toss dressed as a type.
 */
class IsTypeTest {

    private val catalog = NodeCatalog(
        dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS + dev.ziggle.vscript.domain.ColorFixture.DESCRIPTORS,
    )

    // ---- built-in kinds --------------------------------------------------------------------------------

    @Test
    fun `it tells the built-in kinds apart`() {
        val chunk = compile(
            """
            graph "probe"

            export var WholeIsInt: BOOL = false
            export var WholeIsFloat: BOOL = false
            export var FractionIsFloat: BOOL = false
            export var TextIsString: BOOL = false
            export var FlagIsBool: BOOL = false
            export var ListIsList: BOOL = false

            on start {
                val whole = 3
                val fraction = 0.5
                val text = "hello"
                val flag = true
                val items = [1, 2]

                WholeIsInt = whole is Int
                WholeIsFloat = whole is Float
                FractionIsFloat = fraction is Float
                TextIsString = text is String
                FlagIsBool = flag is Bool
                ListIsList = items is List
            }
            """.trimIndent(),
        )
        val run = run(chunk)

        assertEquals(true, chunk.variable("WholeIsInt", run))
        assertEquals(false, chunk.variable("WholeIsFloat", run), "3 is not a Float")
        assertEquals(true, chunk.variable("FractionIsFloat", run))
        assertEquals(true, chunk.variable("TextIsString", run))
        assertEquals(true, chunk.variable("FlagIsBool", run))
        assertEquals(true, chunk.variable("ListIsList", run))
    }

    @Test
    fun `not-is is the other answer`() {
        val chunk = compile(
            """
            graph "probe"

            export var NotFloat: BOOL = false
            export var NotInt: BOOL = false

            on start {
                val whole = 3
                NotFloat = whole !is Float
                NotInt = whole !is Int
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(true, chunk.variable("NotFloat", run))
        assertEquals(false, chunk.variable("NotInt", run))
    }

    // ---- records, which are the point --------------------------------------------------------------------

    @Test
    fun `a record answers to the name its document gave it`() {
        val chunk = compile(
            """
            graph "probe"

            export type Point { x: INT, y: INT }
            export type Leg { from: Point, to: Point }

            export var IsPoint: BOOL = false
            export var IsLeg: BOOL = false
            export var NotLeg: BOOL = false

            on start {
                val p = Point { x: 1, y: 2 }
                IsPoint = p is Point
                IsLeg = p is Leg
                NotLeg = p !is Leg
            }
            """.trimIndent(),
        )
        val run = run(chunk)

        assertEquals(true, chunk.variable("IsPoint", run))
        assertEquals(false, chunk.variable("IsLeg", run), "two records of the same shape are not the same type")
        assertEquals(true, chunk.variable("NotLeg", run))
    }

    @Test
    fun `Tile and Color answer too, because they are records now`() {
        val chunk = compile(
            """
            graph "probe"

            export fn whatIs(spot: TILE) -> BOOL = spot is Tile
            export fn colourIs(ink: COLOR) -> BOOL = ink is Color

            export var SpotIsTile: BOOL = false
            export var InkIsColor: BOOL = false

            on start {
                SpotIsTile = whatIs(spot: tile(3200, 3200, 0))
                InkIsColor = colourIs(ink: 0x80FF8040)
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(true, chunk.variable("SpotIsTile", run))
        assertEquals(true, chunk.variable("InkIsColor", run))
    }

    @Test
    fun `it sorts a mixed list, which is the case it exists for`() {
        // Where a value's type is genuinely unknown: an unconstrained list. Everywhere else the static
        // types already answer, and asking at run time would be asking a question you have the answer to.
        val chunk = compile(
            """
            graph "probe"

            export var Numbers: INT = 0
            export var Words: INT = 0

            on start {
                var numbers = 0
                var words = 0
                for item in [1, "a", 2, "b", "c"] {
                    if item is Int {
                        numbers = numbers + 1
                    }
                    if item is String {
                        words = words + 1
                    }
                }
                Numbers = numbers
                Words = words
            }
            """.trimIndent(),
        )
        val run = run(chunk)
        assertEquals(2, chunk.variable("Numbers", run))
        assertEquals(3, chunk.variable("Words", run))
    }

    // ---- what it refuses ---------------------------------------------------------------------------------

    /**
     * **The test declares `Item` itself now, and that is the point rather than a workaround.**
     *
     * It used to arrive free: `model/Types.kt` registered `Item` — "what kind of item — a shark, a rune
     * scimitar" — in the language, so every test had a game type to hand whether it wanted one or not.
     * That list moved to the pack that declares those types (`dev.ziggle.nodes.GameRecords`), and the bare
     * language now answers `nothing here is a type called 'Item'`, which is the truthful answer for a
     * language with no domain loaded and is NOT the rule this test is about.
     *
     * So the nominal type is built here, over `INT`, exactly as a domain would build it — which also makes
     * the test say what it means: the rule is about a type that is an `Int` at run time, and now the
     * `Int` is written down in the test rather than assumed from a name.
     */
    @Test
    fun `a type the run time cannot tell apart is refused`() {
        // **Both halves, because they answer different questions.** `HostRecords` gives the type its
        // SHAPE -- here, that it is an `Int` underneath, which is the whole premise of the rule below.
        // `Types` gives it a NAME a document may write: `Validator.checkTypes` seeds its legal-name set
        // from `Types.all`, so a type registered only as a record is refused as "nothing here is a type
        // called 'Item'" before the rule under test is ever reached. A domain pack does both; so does this.
        dev.ziggle.vscript.model.HostRecords.register(
            dev.ziggle.vscript.model.HostRecord(
                "Item", emptyList(), over = dev.ziggle.vscript.model.TypeRef(dev.ziggle.vscript.model.PinType.INT),
            ),
        )
        dev.ziggle.vscript.model.Types.register(
            dev.ziggle.vscript.model.TypeInfo("Item", dev.ziggle.vscript.model.TypeRef.named("Item"), "a kind of item", authorable = true),
        )
        val errors = errorsIn(
            """
            graph "probe"

            export var Yes: BOOL = false

            on start {
                val id = 995
                Yes = id is Item
            }
            """.trimIndent(),
        )
        assertTrue(
            errors.any { it.contains("can tell apart") },
            "an ITEM is an Int at run time, so the answer would mean nothing: $errors",
        )
    }

    @Test
    fun `and so is a name that is no type at all`() {
        val errors = errorsIn(
            """
            graph "probe"

            export var Yes: BOOL = false

            on start {
                val n = 1
                Yes = n is Sandwich
            }
            """.trimIndent(),
        )
        assertTrue(errors.any { it.contains("Sandwich") }, errors.toString())
    }

    // ---- it round trips ------------------------------------------------------------------------------------

    @Test
    fun `both spellings print back as they were written`() {
        // The reason `!is` is a flag rather than a wrapping Not: otherwise `x !is T` and `!(x is T)` are
        // the same graph and one of them has to print as the other.
        val source = """
            graph "probe"

            export type Point { x: INT, y: INT }

            export var A: BOOL = false
            export var B: BOOL = false

            on start {
                val p = Point { x: 1, y: 2 }
                A = p is Point
                B = p !is Point
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
