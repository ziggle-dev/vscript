package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.GraphSource
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
 * Where an enum's values are allowed to come from.
 *
 * **The rule is that a member's row is DATA, not a computation**, and this file is what makes the boundary
 * explicit rather than something discovered at a call site. An enum declaration is a field on the document
 * (`Graph.enums`), printed straight from it — so a value has to be something the document can hold. That is
 * the same rule a graph variable's stored default follows and the same one a function parameter's default
 * follows.
 *
 * What that admits is wider than "a number": anything the language can fold to a constant, including
 * records, tiles, other enums' members and lists. What it excludes is anything that has to RUN.
 *
 * The excluded cases are tested too, and deliberately: each asserts the *message*, because "you cannot do
 * this" is only a good answer when it says so at the declaration instead of failing somewhere downstream.
 */
class EnumValueSourcesTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

    /** Library documents, lowered once and handed over as a real [GraphSource]. */
    private fun sourceOf(docs: Map<String, String>): GraphSource = GraphSource.of(
        docs.map { (_, src) -> Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program).graph },
    )

    private fun lower(src: String, docs: Map<String, String> = emptyMap()) =
        Lower(catalog, Names(catalog), sourceOf(docs)).lower(Parser(Lexer(src).lex()).parse().program)

    private fun said(src: String, docs: Map<String, String> = emptyMap()): List<Any?> {
        val source = sourceOf(docs)
        val low = lower(src, docs)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        val g = low.graph
        assertEquals(
            emptyList(),
            Validator(catalog, source).validate(g).errors().map { it.message },
            "did not validate",
        )
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, false, source).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 800,
        )
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun errorsOf(src: String, docs: Map<String, String> = emptyMap()): String =
        lower(src, docs).errors.joinToString("\n") { it.message }

    // ---- what IS allowed -----------------------------------------------------------------------------

    @Test
    fun `a record literal`() {
        assertEquals(
            listOf(2L, 7L),
            said(
                """
                type Vec2 { x: INT, y: INT }
                enum Spot(at: Vec2) { Home(Vec2 { x: 2, y: 7 }) }
                on start { say(Spot.Home.at.x) say(Spot.Home.at.y) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a nested record literal`() {
        assertEquals(
            listOf(3L),
            said(
                """
                type Vec2 { x: INT, y: INT }
                type Box { origin: Vec2, size: INT }
                enum B(b: Box) { One(Box { origin: Vec2 { x: 3, y: 0 }, size: 1 }) }
                on start { say(B.One.b.origin.x) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a tile, which is a record with a picker`() {
        assertEquals(
            listOf(2452L),
            said("enum S(at: TILE) { A(tile(2452, 3223, 0)) }\non start { say(S.A.at.x) }"),
        )
    }

    @Test
    fun `a member of another enum`() {
        assertEquals(
            listOf("Gather"),
            said(
                """
                enum Phase { Idle, Gather }
                enum Job(phase: Phase) { Gather(Phase.Gather) }
                on start { say(Job.Gather.phase) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a list`() {
        assertEquals(
            listOf(2L, 211L),
            said(
                """
                enum Loot(items: LIST<INT>) { Bird([207, 211]) }
                on start { say(_listCount(list: Loot.Bird.items)) say(Loot.Bird.items[1]) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a script-level const`() {
        assertEquals(
            listOf(30L),
            said("val NEEDED = 30\nenum T(count: INT) { Kebbit(NEEDED) }\non start { say(T.Kebbit.count) }"),
        )
    }

    @Test
    fun `an imported const`() {
        assertEquals(
            listOf(5000L),
            said(
                """
                import * as vars from "vars"
                enum T(count: INT) { Salamander(vars::NEEDED) }
                on start { say(T.Salamander.count) }
                """.trimIndent(),
                mapOf("vars" to "graph \"vars\"\nexport val NEEDED = 5000\n"),
            ),
        )
    }

    @Test
    fun `an imported record type and an imported enum member`() {
        assertEquals(
            listOf(9L, "Gather"),
            said(
                """
                import * as vars from "vars"
                enum T(at: vars::Vec2, phase: vars::Phase) { A(vars::Vec2 { x: 9, y: 0 }, vars::Phase.Gather) }
                on start { say(T.A.at.x) say(T.A.phase) }
                """.trimIndent(),
                mapOf(
                    "vars" to """
                        graph "vars"
                        export type Vec2 { x: INT, y: INT }
                        export enum Phase { Idle, Gather }
                    """.trimIndent(),
                ),
            ),
        )
    }

    // ---- what the round trip does with each ----------------------------------------------------------

    @Test
    fun `a record column round-trips as the record it was written as`() {
        // The fold produces a VALUE, so the printer has to know how to write a record back out. It does,
        // which is what makes records the well-behaved half of this: no spelling is lost.
        val src = """
            type Vec2 { x: INT, y: INT }

            enum Spot(at: Vec2) {
                Home(Vec2 { x: 2, y: 7 }),
            }

            on start {
                say(message: Spot.Home.at.x)
            }
        """.trimIndent()
        assertEquals(src, VsText(catalog).write(lower(src).graph).trim())
    }

    @Test
    fun `a const column round-trips as its VALUE, not its name`() {
        // The one-directional cost of folding, pinned rather than left to be discovered. A const is a name
        // for a literal and only the literal survives into the table — because an enum declaration is
        // document data, and a name that has to be looked up is not data.
        //
        // A `var` does NOT behave this way: its default keeps the spelling, because a variable has an
        // `@init` assignment to run in and an enum has nowhere. That is why the fold is scoped to enums.
        val src = "val NEEDED = 30\nenum T(count: INT) { Kebbit(NEEDED) }\non start { say(T.Kebbit.count) }"
        val out = VsText(catalog).write(lower(src).graph)
        assertTrue("Kebbit(30)" in out, "expected the value, got:\n$out")
    }

    // ---- what is NOT, and what it says ---------------------------------------------------------------

    @Test
    fun `a function call is refused at the declaration`() {
        // Not a limitation of the folding: an enum declaration is a field on the DOCUMENT, printed straight
        // from it, so a value that has to run has nowhere to live. A graph `var` is where a computed value
        // belongs — its default becomes an `@init` assignment at the head of `on start`.
        val msg = errorsOf("fn three() -> INT = 3\nenum T(a: INT) { X(three()) }")
        assertTrue("written out, not worked out" in msg, "got: $msg")
    }

    @Test
    fun `a graph variable is refused at the declaration`() {
        val msg = errorsOf("var Needed: INT = 30\nenum T(a: INT) { X(Needed) }")
        assertTrue("written out, not worked out" in msg, "got: $msg")
    }

    @Test
    fun `arithmetic is refused at the declaration`() {
        val msg = errorsOf("enum T(a: INT) { X(1 + 1) }")
        assertTrue("written out, not worked out" in msg, "got: $msg")
    }

    @Test
    fun `an imported function is refused at the declaration`() {
        val msg = errorsOf(
            """
            import * as vars from "vars"
            enum T(a: INT) { X(vars::three()) }
            """.trimIndent(),
            mapOf("vars" to "graph \"vars\"\nexport fn three() -> INT = 3\n"),
        )
        assertTrue("written out, not worked out" in msg, "got: $msg")
    }

    @Test
    fun `an imported variable is refused at the declaration`() {
        val msg = errorsOf(
            """
            import * as vars from "vars"
            enum T(a: INT) { X(vars::Needed) }
            """.trimIndent(),
            mapOf("vars" to "graph \"vars\"\nexport var Needed: INT = 30\n"),
        )
        assertTrue("written out, not worked out" in msg, "got: $msg")
    }
}
