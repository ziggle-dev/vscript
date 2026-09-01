package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `enum Phase { Chop, Bank }` — a closed set of names a DOCUMENT declares.
 *
 * The point of the feature is that the language stops carrying the sets itself: `PinType.SKILL` is an enum
 * baked into the lexer's neighbours and every `PinType.ENUM` pin is a list a node author wrote in Kotlin, so
 * a script wanting "one of these three states" had constants, bare strings, or a record used as a namespace.
 *
 * Three properties are worth proving and they are independent, so they are tested apart:
 *
 *  1. **It round-trips**, per spelling. `Phase.Chop` lowers to an `enum.of` NODE, which is what makes this
 *     need no marker annotation — the node records which enum and which member, so the printer reads the
 *     spelling back off the graph. Compare `@wroteField`, which was needed precisely because `s.f = v` and
 *     `s = s with {…}` lower to the same graph. These do not.
 *  2. **It runs**, and comparison works, since a member is its name and nothing more.
 *  3. **The refusals are the RIGHT refusals.** `is` and `as` are both rejected, and the reason matters more
 *     than the rejection — see [`is` is refused, because a member is just its name].
 */
class EnumTest {

    private val catalog = NodeCatalog()

    // ---- round trips -----------------------------------------------------------------------------------

    private fun keeps(text: String) {
        val vs = VsText(catalog)
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val graph = assertNotNull(read.graph)
        assertEquals(text, vs.write(graph, read.comments).trim(), "the spelling changed on the way back out")
    }

    @Test
    fun `a declaration round-trips`() {
        keeps(
            """
            graph "probe"

            export enum Phase { Chop, Bank, Walk }
            """.trimIndent(),
        )
    }

    @Test
    fun `a member used in a body round-trips`() {
        keeps(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                State = Phase.Bank
            }
            """.trimIndent(),
        )
    }

    /**
     * A member as a stored DEFAULT, which is the case that nearly became two lines.
     *
     * A default that is not a literal becomes an `@init` Set at the head of `on start`, so unless `literalOf`
     * recognises a member the declaration would print back as a statement. It stores the NAME, so the type is
     * the only thing that says to print `Phase.Chop` rather than `"Chop"`.
     */
    @Test
    fun `a member as a variable default stays on the declaration`() {
        val vs = VsText(catalog)
        val read = vs.read(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop
            """.trimIndent(),
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        assertEquals("Chop", graph.variable("State")?.default, "the default should be the member's name")
        assertTrue(
            graph.nodes.none { it.type == BuiltinNodes.VAR_SET },
            "it should be a stored default, not an initialiser statement",
        )
    }

    @Test
    fun `a comparison round-trips`() {
        keeps(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                if State == Phase.Bank {
                    log(message: "banking")
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a enum round-trips`() {
        keeps(
            """
            graph "probe"

            export enum Phase { Chop, Bank }
            """.trimIndent(),
        )
    }

    /** A doc comment on one, since declarations are keyed by kind and a new kind needs its own key. */
    @Test
    fun `a comment on an enum is carried`() {
        keeps(
            """
            graph "probe"

            /** Where we are in the loop. */
            export enum Phase { Chop, Bank }
            """.trimIndent(),
        )
    }

    /**
     * The declaration is the authority on capitalisation.
     *
     * `phase.chop` is accepted — every other name lookup here is case-insensitive — but it is STORED and
     * printed as the declaration spells it. Otherwise two spellings of one member would compare unequal,
     * since comparison is string equality.
     */
    @Test
    fun `a member is normalised to the declared spelling`() {
        val vs = VsText(catalog)
        val read = vs.read(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = phase.chop
            """.trimIndent(),
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        assertTrue(
            vs.write(graph).contains("Phase.Chop"),
            "should print the declared spelling:\n${vs.write(graph)}",
        )
    }

    // ---- imports ---------------------------------------------------------------------------------------

    private fun library(): Graph {
        val read = VsText(catalog).read(
            """
            graph "states"

            export enum Phase { Chop, Bank }

            enum Secret { Hidden }
            """.trimIndent(),
        )
        return assertNotNull(read.graph, "the library should compile: ${read.errors.map { it.message }}")
    }

    @Test
    fun `an imported enum round-trips`() {
        val lib = library()
        val vs = VsText(catalog, GraphSource { if (it.ref == "states") lib else null })
        val text = """
            graph "probe"

            import * as st from "states"

            export var State: st::Phase = st::Phase.Chop

            on start {
                State = st::Phase.Bank
            }
        """.trimIndent()
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph), read.comments).trim())
    }

    /** `private` means the same thing here it means everywhere else. */
    @Test
    fun `an unexported enum does not cross an import`() {
        val lib = library()
        val vs = VsText(catalog, GraphSource { if (it.ref == "states") lib else null })
        val read = vs.read(
            """
            graph "probe"

            import * as st from "states"

            on start {
                log(message: "" + st::Secret.Hidden)
            }
            """.trimIndent(),
        )
        assertTrue(read.errors.isNotEmpty(), "an unexported enum should not be reachable through an import")
    }

    // ---- what it runs as -------------------------------------------------------------------------------

    private fun run(text: String): List<Any?> {
        val read = VsText(catalog).read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        val issues = Validator(catalog).validate(graph)
        assertTrue(issues.errors().isEmpty(), "$issues")
        val chunk = GraphCompiler(catalog).compile(graph, graph.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        return result.fiber.result
    }

    /** A member IS its name — the decision the whole design rests on, asserted rather than assumed. */
    @Test
    fun `a member evaluates to its own name`() {
        assertEquals(
            listOf("Bank"),
            run(
                """
                graph "probe"

                export enum Phase { Chop, Bank }

                on start {
                    return Phase.Bank
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a member equals itself and differs from its sibling`() {
        assertEquals(
            listOf(true),
            run(
                """
                graph "probe"

                export enum Phase { Chop, Bank }

                export var State: Phase = Phase.Bank

                on start {
                    return State == Phase.Bank
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(false),
            run(
                """
                graph "probe"

                export enum Phase { Chop, Bank }

                export var State: Phase = Phase.Bank

                on start {
                    return State == Phase.Chop
                }
                """.trimIndent(),
            ),
        )
    }

    /** Branching on one, which is what an author actually writes. */
    @Test
    fun `a branch on a member takes the right arm`() {
        assertEquals(
            listOf("banking"),
            run(
                """
                graph "probe"

                export enum Phase { Chop, Bank }

                export var State: Phase = Phase.Bank

                on start {
                    if State == Phase.Bank {
                        return "banking"
                    }
                    return "chopping"
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- diagnostics -----------------------------------------------------------------------------------

    private fun errorFrom(text: String): String {
        val read = VsText(catalog).read(text)
        val fromRead = read.errors.joinToString("; ") { it.message }
        if (fromRead.isNotEmpty()) return fromRead
        val graph = assertNotNull(read.graph, "expected either an error or a graph")
        return Validator(catalog).validate(graph).errors().joinToString("; ") { it.message }
    }

    @Test
    fun `an unknown member names the ones that exist`() {
        val message = errorFrom(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            on start {
                log(message: "" + Phase.Fish)
            }
            """.trimIndent(),
        )
        assertTrue("Fish" in message, "should name what was written: $message")
        assertTrue("Chop, Bank" in message, "should list the real members: $message")
    }

    @Test
    fun `a duplicate member is refused at the declaration`() {
        assertTrue("already has a member" in errorFrom("""graph "p"${'\n'}enum Phase { Chop, chop }"""))
    }

    @Test
    fun `an empty enum is refused`() {
        assertTrue("at least one" in errorFrom("""graph "p"${'\n'}enum Phase { }"""))
    }

    /**
     * `is` is refused, because a member is just its name.
     *
     * This CORRECTS the design note in VSCRIPT_LANG_PLAN.md §7.4, which guessed that `x is Phase` "should
     * work by the same rule structs use". It cannot, and the rule itself is why: a record is testable because
     * it carries its own type name at run time, which is the reason TILE and COLOR became testable when they
     * became records. An enum member is a bare String, indistinguishable from any other String and from every
     * other enum's members — exactly the case `Validator.checkTypeTests` already refuses for SKILL and ENUM.
     * Answering it would be branching on a coin toss dressed as a type.
     */
    @Test
    fun `is is refused, because a member is just its name`() {
        val message = errorFrom(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export var State: Phase = Phase.Chop

            on start {
                if State is Phase {
                    log(message: "never mind")
                }
            }
            """.trimIndent(),
        )
        assertTrue("choice" in message, "should say what a Phase is: $message")
        assertTrue("==" in message, "should point at comparison as the fix: $message")
    }

    @Test
    fun `as is refused, and not as a missing record`() {
        val message = errorFrom(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            export type Point { x: INT, y: INT }

            on start {
                val p = Point { x: 1, y: 2 }
                log(message: "" + (p as Phase))
            }
            """.trimIndent(),
        )
        assertTrue("choice" in message, "should say a Phase is a choice: $message")
        assertTrue("nothing here is a record called" !in message, "it IS here — it is just not a record: $message")
    }

    // ---- shadowing -------------------------------------------------------------------------------------

    /**
     * A value in scope beats the enum, which is the same rule every other name follows.
     *
     * Worth a test because the enum is recognised BEFORE the target is lowered — necessary, since `Phase` is
     * a type rather than a value and lowering it would report "nothing here is called 'Phase'" — and an
     * interception that early is exactly the kind that forgets to check for shadowing.
     */
    @Test
    fun `a local of the same name shadows the enum`() {
        assertEquals(
            listOf(7),
            run(
                """
                graph "probe"

                export enum Phase { Chop, Bank }

                export type Holder { Chop: INT }

                on start {
                    val Phase = Holder { Chop: 7 }
                    return Phase.Chop
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- persistence -----------------------------------------------------------------------------------

    @Test
    fun `an enum survives being saved and read back`() {
        val read = VsText(catalog).read(
            """
            graph "probe"

            export enum Phase { Chop, Bank }

            enum Mode { Fast }
            """.trimIndent(),
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        val back = GraphDoc.fromJson(GraphDoc.toJson(graph))
        assertEquals(listOf("Chop", "Bank"), back.enum("Phase")?.members)
        assertEquals(false, back.enum("Mode")?.isExported)
    }

    /**
     * A record field typed by its OWN document's enum, filled from across an import.
     *
     * The asymmetry that made this fail: an enum has nothing inside it to qualify, so the rewriting that
     * carries a record's field types over an import skipped enums entirely — and thereby skipped the case
     * where a record field is typed BY one. The member arrived as `vars::Target`, the field it was going
     * into still read `Target`, and a correct record literal was refused.
     *
     * Validated rather than merely read: the two spellings lower perfectly well and only the wire check
     * ever compares them, so a test that stopped at `read.ok` would have passed throughout the bug.
     */
    @Test
    fun `a record field typed by an imported enum takes that enum's member`() {
        val lib = assertNotNull(
            VsText(catalog).read(
                """
                graph "vars"

                export enum Target { WildKebbit, Sabreteeth }

                export type Rumor { target: Target, count: Int }
                """.trimIndent(),
            ).graph,
        )
        val source = GraphSource { if (it.ref == "vars") lib else null }
        val text = """
            graph "probe"

            import * as vars from "vars"

            export fn pick() -> vars::Rumor {
                return vars::Rumor{
                    target: vars::Target.WildKebbit,
                    count: 30
                }
            }
        """.trimIndent()
        val read = VsText(catalog, source).read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val graph = assertNotNull(read.graph)
        assertEquals(
            emptyList(),
            Validator(catalog, source).validate(graph).errors().map { it.message },
            "an enum-typed field should accept that enum across the import",
        )
        // Stability rather than a hand-written layout: the printer puts a record literal on one line, so
        // pinning the multi-line spelling above would test my typing and not the round trip.
        val printed = VsText(catalog, source).write(graph, read.comments).trim()
        assertTrue("vars::Target.WildKebbit" in printed, printed)
        val again = VsText(catalog, source).read(printed)
        assertTrue(again.ok, "the printed form should re-read: ${again.errors.map { it.message }}")
        assertEquals(printed, VsText(catalog, source).write(assertNotNull(again.graph), again.comments).trim())
    }

    /**
     * An older client REFUSES a document with enums rather than reading it without them.
     *
     * The whole reason the format bumped, since there is nothing to migrate: dropping the enums would leave
     * every `enum.of` naming a type that does not exist, and a node falling back to the first member is a
     * script that runs and takes the wrong branch.
     */
    @Test
    fun `a document carrying enums is written at the new format`() {
        val read = VsText(catalog).read("""graph "p"${'\n'}enum Phase { Chop }""")
        val json = GraphDoc.toJson(assertNotNull(read.graph))
        assertTrue("\"format\": ${GraphDoc.FORMAT}" in json, json.take(200))
        assertTrue(GraphDoc.FORMAT >= 7, "the enum format bump should not have been reverted")
    }
}
