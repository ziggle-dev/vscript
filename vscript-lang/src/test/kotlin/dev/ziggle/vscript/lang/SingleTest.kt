package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `single` — a record with exactly one of it, and record field defaults, which it needed first.
 *
 * **Sugar over two declarations that already worked.** The state a script keeps was a `type` and a
 * document-level `var` of it under the same name, which is two declarations and a naming convention kept
 * in step by hand — right until somebody adds a field to one half. A `single` registers both, initialises
 * the variable from the field defaults, and prints back as the one declaration that was written.
 *
 * Nothing new runs. `State.laps` is the field read that already existed and `State.laps = …` the field
 * assignment; the instance is an ordinary graph variable with an ordinary stored default. What it costs is
 * a flag on `StructType` and a recognizer in `Print` — which is the price every piece of sugar here pays,
 * and the reason the round trip is most of what these tests check.
 *
 * **Field defaults came with it and belong on `type` generally.** Putting them only inside `single` would
 * have meant two spellings of a field. The model already had the slot: a record's fields are
 * `FunctionPin`s, and a `FunctionPin` has carried a default since parameters got one.
 */
class SingleTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))
    private val text = VsText(catalog)

    private fun lower(src: String) = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)

    private fun graphOf(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors().map { it.message })
        return low.graph
    }

    private fun said(src: String): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 200,
        )
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun roundTrip(src: String) {
        assertEquals(src.trim(), text.write(graphOf(src)).trim(), "round trip")
    }

    // ---- record field defaults -----------------------------------------------------------------------

    @Test
    fun `a record literal may leave a defaulted field out`() {
        assertEquals(
            listOf(3L, 7L),
            said(
                """
                graph "probe"
                export type Point { x: INT = 3, y: INT = 7 }
                on start {
                    val p = Point { }
                    say(p.x)
                    say(p.y)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a written field still wins over its default`() {
        assertEquals(
            listOf(9L, 7L),
            said(
                """
                graph "probe"
                export type Point { x: INT = 3, y: INT = 7 }
                on start {
                    val p = Point { x: 9 }
                    say(p.x)
                    say(p.y)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A field with neither a value nor a default arrives as NULL from a record LITERAL.
     *
     * Not the type's zero, which is what a record-typed VARIABLE gets — the two paths disagree, and that
     * is recorded as GAPS #17 rather than quietly fixed here. Asserted so the difference is visible: a
     * `single`'s instance goes through the variable path and does get zeros (below).
     */
    @Test
    fun `a field with no default arrives as null from a literal`() {
        assertEquals(
            listOf(null, 7L),
            said(
                """
                graph "probe"
                export type Point { x: INT, y: INT = 7 }
                on start {
                    val p = Point { }
                    say(p.x)
                    say(p.y)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a field default may name a val`() {
        assertEquals(
            listOf(30L),
            said(
                """
                graph "probe"
                export val Needed = 30
                export type Quota { count: INT = Needed }
                on start {
                    say(Quota { }.count)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a field default round-trips on the declaration`() {
        roundTrip(
            """
            graph "probe"

            export type Point { x: INT = 3, y: INT = 7 }

            on start {
                say(message: Point {}.x)
            }
            """.trimIndent(),
        )
    }

    /**
     * `= null` is DROPPED on the way back, and that is a normalisation rather than a loss.
     *
     * `FunctionPin.default` is an `Any?`, so a default of null is indistinguishable from having none — and
     * for an optional field the two mean the same thing, since null is already what it starts as. Writing
     * it is allowed and says nothing the type did not.
     */
    @Test
    fun `a null default is accepted and normalised away`() {
        val printed = text.write(
            graphOf(
                """
                graph "probe"
                export type Point { x: INT = 3, tag: STRING? = null }
                on start { say(Point {}.x) }
                """.trimIndent(),
            ),
        )
        assertTrue("tag: STRING?" in printed, printed)
        assertTrue("= null" !in printed, printed)
    }

    /**
     * A field default may be an enum MEMBER, which is what forced the fold into a third pass.
     *
     * The two declarations point at each other: a record's field may be typed as an enum, and an enum's
     * column may be typed as a record. Whichever pass ran first would refuse the other's defaults — and it
     * refused them saying "that is not a value", which is true of nothing here.
     */
    @Test
    fun `a field default may be an enum member`() {
        assertEquals(
            listOf("Bank"),
            said(
                """
                graph "probe"
                export enum Phase { Chop, Bank }
                export single Run { phase: Phase = Phase.Bank }
                on start { say(Run.phase) }
                """.trimIndent(),
            ),
        )
    }

    /** And the other way round: an enum column typed as a record still works. */
    @Test
    fun `an enum column may still be a record`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"
                export type Spot { x: INT = 0 }
                export enum Where(at: Spot) { Home(Spot { x: 3 }) }
                on start { say(Where.Home.at.x) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A field default may be worked out, and it is worked out AT EACH CONSTRUCTION.
     *
     * It used to have to be written out — the same rule a parameter default still keeps — which made
     * `single Run { anchor: TILE = tiles::home() }` illegal, and a `single` therefore data rather than an
     * instance. See BuiltinNodes.FIELD_DEFAULT for why it becomes a function rather than an expression
     * stored on the pin.
     */
    @Test
    fun `a field default may be worked out`() {
        assertEquals(
            listOf(2L),
            said(
                """
                graph "probe"
                export type Point { x: INT = 1 + 1 }
                on start { say(Point { }.x) }
                """.trimIndent(),
            ),
        )
    }

    /** Supplied wins over the default, which is the whole point of having one. */
    @Test
    fun `a field written out is not filled from its default`() {
        assertEquals(
            listOf(9L),
            said(
                """
                graph "probe"
                export type Point { x: INT = 1 + 1 }
                on start { say(Point { x: 9 }.x) }
                """.trimIndent(),
            ),
        )
    }

    // ---- `single` ------------------------------------------------------------------------------------

    @Test
    fun `a single declares a record and the one instance of it`() {
        val g = graphOf(
            """
            graph "probe"
            export single State { phase: STRING = "idle", laps: INT = 0 }
            on start { say(State.phase) }
            """.trimIndent(),
        )
        val t = g.types.single { it.name == "State" }
        assertTrue(t.isSingle, "the type has to be marked, or the printer gives back both pieces")
        assertTrue(g.variables.any { it.name == "State" }, "and there is one variable of it")
    }

    @Test
    fun `its fields start at their defaults`() {
        assertEquals(
            listOf("idle", 0L),
            said(
                """
                graph "probe"
                export single State { phase: STRING = "idle", laps: INT = 0 }
                on start {
                    say(State.phase)
                    say(State.laps)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a field can be written, and the write sticks`() {
        assertEquals(
            listOf(0L, 3L),
            said(
                """
                graph "probe"
                export single State { laps: INT = 0 }
                on start {
                    say(State.laps)
                    State.laps = 3
                    say(State.laps)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a compound write works, since it is the field assignment that already existed`() {
        assertEquals(
            listOf(2L),
            said(
                """
                graph "probe"
                export single State { laps: INT = 0 }
                on start {
                    State.laps += 1
                    State.laps += 1
                    say(State.laps)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a field with no default takes its type's zero`() {
        assertEquals(
            listOf(0L, ""),
            said(
                """
                graph "probe"
                export single State { laps: INT, note: STRING }
                on start {
                    say(State.laps)
                    say(State.note)
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the recognizer ------------------------------------------------------------------------------

    @Test
    fun `a single round-trips as one declaration, not as two`() {
        roundTrip(
            """
            graph "probe"

            export single State { phase: STRING = "idle", laps: INT = 0 }

            on start {
                say(message: State.laps)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a single round-trips`() {
        roundTrip(
            """
            graph "probe"

            export single State { laps: INT = 0 }

            on start {
                say(message: State.laps)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a single beside an ordinary type and an ordinary var round-trips`() {
        roundTrip(
            """
            graph "probe"

            export type Point { x: INT = 0 }
            export single State { laps: INT = 0 }

            export var Trips: INT = 0

            on start {
                say(message: State.laps + Trips + Point {}.x)
            }
            """.trimIndent(),
        )
    }

    /** Through the document format as well, or the canvas gives back a `type` and a stray `var`. */
    @Test
    fun `single survives the document format`() {
        val g = graphOf(
            """
            graph "probe"
            export single State { laps: INT = 0 }
            on start { say(State.laps) }
            """.trimIndent(),
        )
        val back = GraphDoc.fromJson(GraphDoc.toJson(g))
        assertTrue(back.types.single { it.name == "State" }.isSingle)
        assertTrue("single State" in text.write(back), text.write(back))
    }
}
