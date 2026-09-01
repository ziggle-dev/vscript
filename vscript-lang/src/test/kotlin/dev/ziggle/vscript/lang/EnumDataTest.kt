package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
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
 * Enums that carry data — a member with a row, and `t.field` to read one.
 *
 * **The motivating case is a table forced through control flow.** `rumor.vs` runs a nine-arm `when` from
 * line 53 to the end of a 192-line file, each arm returning a record built from four constants. No logic in
 * any of it. It is a table, written as a branch, because an enum member could not carry data.
 *
 * Two things are load-bearing and are what most of these tests are about:
 *
 * - **A member is still its NAME at run time.** The lookup is "find the name in the member list, take the
 *   same position in the column", which is only sound because of that — so the invariant that keeps
 *   documents legible is also what lets this compile to a host call rather than a jump chain.
 * - **The access does not fold**, even when the member is statically known, because a folded
 *   `Target.WildKebbit.anchor` would print back as the tile rather than as what was written.
 */
class EnumDataTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)
    private val text = VsText(catalog)

    private fun lower(src: String) = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)

    private fun graphOf(src: String): dev.ziggle.vscript.model.Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        return low.graph
    }

    private fun said(src: String): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 800,
        )
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    /** The shape `rumor.vs` wants, trimmed to three members. */
    private val targets = """
        enum Target(count: INT, quarry: STRING) {
            WildKebbit(30, "Wild kebbit"),
            HornedGraahk(30, "Horned graahk"),
            RedSalamander(5000, "Red salamander"),
        }
    """.trimIndent()

    // ---- reading a row -------------------------------------------------------------------------------

    @Test
    fun `a member carries its row, read off the type`() {
        assertEquals(
            listOf(5000L, "Red salamander"),
            said("$targets\non start { say(Target.RedSalamander.count) say(Target.RedSalamander.quarry) }"),
        )
    }

    @Test
    fun `and off a value, which is the case that matters`() {
        // This is what turns the nine-arm `when` into a table: the member arrives on a wire.
        assertEquals(
            listOf(30L, "Horned graahk"),
            said(
                """
                $targets
                fn describe(t: Target) { say(t.count) say(t.quarry) }
                on start { describe(Target.HornedGraahk) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a column is read off whichever member is held, across a variable`() {
        assertEquals(
            listOf(30L, 5000L),
            said(
                """
                $targets
                var T: Target = Target.WildKebbit
                on start {
                    say(T.count)
                    T = Target.RedSalamander
                    say(T.count)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a field carries its declared type, so arithmetic and comparison work`() {
        assertEquals(
            listOf(31L, true),
            said("$targets\non start { val t = Target.WildKebbit say(t.count + 1) say(t.quarry == \"Wild kebbit\") }"),
        )
    }

    @Test
    fun `a record-typed column works, which is what rumor actually needs`() {
        // `rumor.vs`'s real table has an anchor TILE per member. A tile is a record, so this is the
        // column type that would have found any hole in the literal round trip.
        assertEquals(
            listOf(2452L, 3223L),
            said(
                """
                enum Spot(at: TILE) {
                    Kebbit(tile(2308, 3575, 0)),
                    Salamander(tile(2452, 3223, 0)),
                }
                on start {
                    val s = Spot.Salamander
                    say(s.at.x)
                    say(s.at.y)
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the invariants ------------------------------------------------------------------------------

    @Test
    fun `a member is still carried as its name`() {
        // The whole lookup rests on this. If a member ever became an ordinal, the compiled column would
        // still be in declaration order but nothing would find it.
        assertEquals(listOf("RedSalamander"), said("$targets\non start { say(Target.RedSalamander) }"))
    }

    @Test
    fun `reordering the declaration does not change what a member means`() {
        // Rows are keyed by NAME, so moving a member moves its row with it. An ordinal would not.
        val a = said("$targets\non start { say(Target.WildKebbit.count) }")
        val reordered = """
            enum Target(count: INT, quarry: STRING) {
                RedSalamander(5000, "Red salamander"),
                WildKebbit(30, "Wild kebbit"),
                HornedGraahk(30, "Horned graahk"),
            }
        """.trimIndent()
        assertEquals(a, said("$reordered\non start { say(Target.WildKebbit.count) }"))
    }

    @Test
    fun `the access is a node, not a folded constant`() {
        // If it folded, the printer would give back the value rather than the spelling — and the canvas
        // would have nothing to draw. One node covers the static and dynamic cases alike.
        val g = graphOf("$targets\non start { say(Target.WildKebbit.count) }")
        assertEquals(1, g.nodes.count { it.type == BuiltinNodes.ENUM_FIELD })
    }

    @Test
    fun `a plain enum is completely unaffected`() {
        // Every existing file is one of these. No fields declared, no rows stored, nothing in the JSON.
        val g = graphOf("enum Phase { Idle, Chop }\non start { say(Phase.Chop) }")
        val e = g.enums.single()
        assertEquals(emptyList(), e.fields)
        assertEquals(emptyMap(), e.values)
        assertTrue("fields" !in GraphDoc.toJson(g), "a plain enum should write no fields key")
        assertEquals(listOf("Chop"), said("enum Phase { Idle, Chop }\non start { say(Phase.Chop) }"))
    }

    // ---- round trip ----------------------------------------------------------------------------------

    @Test
    fun `a table round-trips through text`() {
        val src = """
            enum Target(count: INT, quarry: STRING) {
                WildKebbit(30, "Wild kebbit"),
                HornedGraahk(30, "Horned graahk"),
                RedSalamander(5000, "Red salamander"),
            }

            on start {
                say(message: Target.RedSalamander.count)
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim())
    }

    @Test
    fun `a table round-trips through the document format`() {
        val g = graphOf("$targets\non start { say(Target.RedSalamander.quarry) }")
        val back = GraphDoc.fromJson(GraphDoc.toJson(g))
        val e = back.enums.single()
        assertEquals(listOf("count", "quarry"), e.fields.map { it.name })
        assertEquals(listOf("INT", "STRING"), e.fields.map { it.type.toString() })
        assertEquals(listOf<Any?>(5000, "Red salamander"), e.values["RedSalamander"])
        // And the column the compiler bakes lines up with the members, index for index.
        assertEquals(listOf<Any?>(30, 30, 5000), e.column("count"))
    }

    // ---- what is refused -----------------------------------------------------------------------------

    @Test
    fun `a short row is refused, naming the FIELD that has no answer`() {
        val g = Lower(catalog).lower(Parser(Lexer("enum T(a: INT, b: INT) { X(1) }").lex()).parse().program).graph
        val msg = Validator(catalog).validate(g).filter { it.severity == Severity.ERROR }
            .joinToString("\n") { it.message }
        assertTrue("'T.X' gives no value for 'b'" in msg, "got: $msg")
    }

    @Test
    fun `too many values is refused too`() {
        val g = Lower(catalog).lower(Parser(Lexer("enum T(a: INT) { X(1, 2) }").lex()).parse().program).graph
        val msg = Validator(catalog).validate(g).filter { it.severity == Severity.ERROR }
            .joinToString("\n") { it.message }
        assertTrue("gives 2 value(s) but 'T' declares only 1" in msg, "got: $msg")
    }

    // ---- defaults ------------------------------------------------------------------------------------

    @Test
    fun `a column may have a default, so a member can leave it off`() {
        assertEquals(
            listOf(1L, 30L, 7L, 30L),
            said(
                """
                enum T(rank: INT, count: INT = 30) {
                    Low(1),
                    High(7, 30),
                }
                on start { say(T.Low.rank) say(T.Low.count) say(T.High.rank) say(T.High.count) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a member may give no values at all when every column has a default`() {
        assertEquals(
            listOf(30L),
            said("enum T(count: INT = 30) { Only }\non start { say(T.Only.count) }"),
        )
    }

    @Test
    fun `a default round-trips, and a row that stopped early stays stopped`() {
        // The printer must not write the defaults a member was allowed to omit — that would turn one
        // spelling into another on every save.
        val src = """
            enum T(rank: INT, count: INT = 30) {
                Low(1),
                High(7, 30),
            }

            on start {
                say(message: T.Low.count)
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim())
    }

    @Test
    fun `a default survives the document format, and so does a function parameter's`() {
        // `encodePins` wrote only a name and a type, so a default was lost the moment a document went
        // through JSON — silently turning `fn f(dx: INT = 1)` into one that reports a missing argument.
        val g = graphOf(
            """
            enum T(rank: INT, count: INT = 30) { Low(1) }
            fn bump(n: INT, by: INT = 5) -> INT = n + by
            on start { say(bump(1)) }
            """.trimIndent(),
        )
        val back = GraphDoc.fromJson(GraphDoc.toJson(g))
        assertEquals(listOf<Any?>(null, 30), back.enums.single().fields.map { it.default })
        assertEquals(listOf<Any?>(null, 5), back.function("bump")?.params?.map { it.default })
        // And the column still answers with the default for the member that left it off.
        assertEquals(listOf<Any?>(30), back.enums.single().column("count"))
    }

    @Test
    fun `a value of the wrong type is refused`() {
        val g = Lower(catalog).lower(Parser(Lexer("enum T(a: STRING) { X(1) }").lex()).parse().program).graph
        val msg = Validator(catalog).validate(g).filter { it.severity == Severity.ERROR }
            .joinToString("\n") { it.message }
        assertTrue("gives a INT for 'a'" in msg, "got: $msg")
    }

    @Test
    fun `a string-shaped value is NOT type-checked, which is a known limit and not this feature's`() {
        // `literalTypeOf` answers null for a String on purpose: a string is also how a tile, a skill and an
        // enum member are stored, so it cannot say which was meant. LANGUAGE.md §13 already records this
        // for locals. A column typed INT given `"no"` therefore passes here and fails where the value is
        // used — pinned so the gap is a known one rather than a surprise, and so that closing it (which
        // strict null in phase D will want anyway) shows up as this test changing.
        val g = Lower(catalog).lower(Parser(Lexer("enum T(a: INT) { X(\"no\") }").lex()).parse().program).graph
        assertEquals(
            emptyList(),
            Validator(catalog).validate(g).filter { it.severity == Severity.ERROR }.map { it.message },
        )
    }

    @Test
    fun `a value that has to be worked out is refused`() {
        val msg = lower("enum T(a: INT) { X(1 + 1) }").errors.joinToString("\n") { it.message }
        assertTrue("written out, not worked out" in msg, "got: $msg")
    }

    @Test
    fun `an unknown field names what the enum does have`() {
        val msg = lower("$targets\non start { say(Target.WildKebbit.nope) }")
            .errors.joinToString("\n") { it.message }
        assertTrue("'Target' has no field 'nope'" in msg && "count, quarry" in msg, "got: $msg")
    }

    @Test
    fun `a field on a plain enum still says what it always said`() {
        // Not "this enum carries no fields": `Phase.Chop.foo` is an ordinary missing field, and answering
        // it with something about tables would send the reader looking for the wrong thing.
        val msg = lower("enum Phase { Idle }\non start { say(Phase.Idle.foo) }")
            .errors.joinToString("\n") { it.message }
        assertTrue("no field 'foo'" in msg, "got: $msg")
    }

    @Test
    fun `empty parentheses are refused rather than meaning a plain enum`() {
        val r = runCatching { Parser(Lexer("enum T() { X }").lex()).parse() }
        val msg = r.getOrNull()?.errors?.joinToString { it.message } ?: r.exceptionOrNull()?.message.orEmpty()
        assertTrue("declares no fields" in msg, "got: $msg")
    }

    @Test
    fun `a repeated field name is refused`() {
        val r = runCatching { Parser(Lexer("enum T(a: INT, a: INT) { X(1, 2) }").lex()).parse() }
        val msg = r.getOrNull()?.errors?.joinToString { it.message } ?: r.exceptionOrNull()?.message.orEmpty()
        assertTrue("already has a field called 'a'" in msg, "got: $msg")
    }

    // ---- the thing it was for ------------------------------------------------------------------------

    @Test
    fun `the nine-arm when becomes a table`() {
        // Before: `when t { Target.WildKebbit -> return 30  … }`, one arm per member, nine of them.
        // After: the declaration IS the table and the dispatch is one read.
        val before = """
            enum Target { WildKebbit, HornedGraahk, RedSalamander }
            fn countOf(t: Target) -> INT {
                when t {
                    Target.WildKebbit -> return 30
                    Target.HornedGraahk -> return 30
                    Target.RedSalamander -> return 5000
                }
                return 0
            }
            on start { say(countOf(Target.RedSalamander)) say(countOf(Target.WildKebbit)) }
        """.trimIndent()
        val after = """
            $targets
            on start { say(Target.RedSalamander.count) say(Target.WildKebbit.count) }
        """.trimIndent()
        assertEquals(said(before), said(after), "the table has to mean what the branch meant")
        assertEquals(listOf(5000L, 30L), said(after))
    }

    // ---- across an import ----------------------------------------------------------------------------
    //
    // The table has to CROSS. Both lists of visible enums renamed an imported one by rebuilding it from its
    // name and its members, which silently dropped `fields` and `values` — so the members arrived and the
    // columns did not, and `ids::Altar.Air.level` reported "this has no field 'level'" while the identical
    // read inside the declaring document was fine.
    //
    // Two lists, because two stages ask: `Lower.importedEnums` decides whether the read resolves at all,
    // and `ImportScope.visibleEnums` is what the validator checks and what the compiler bakes the column
    // out of. Fixing one alone gives either a false error or a column of nulls, so both are driven here —
    // these run the script, which is the only way to notice the second.

    /** A library whose enum carries a table, and a record with a column of that enum's type. */
    private fun tableLib(): dev.ziggle.vscript.model.Graph {
        val read = text.read(
            """
            graph "ids"

            export enum Altar(obj: INT, level: INT, at: STRING) {
                Air(43701, 1, "north"),
                Blood(43708, 77, "south"),
            }

            export type Row { a: Altar, n: INT }
            """.trimIndent(),
        )
        return kotlin.test.assertNotNull(read.graph, "library: ${read.errors.map { it.message }}")
    }

    private fun saidAcross(src: String): List<Any?> {
        val lib = tableLib()
        val source = dev.ziggle.vscript.model.GraphSource { if (it.ref == "ids") lib else null }
        val read = VsText(catalog, source).read(src)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val g = kotlin.test.assertNotNull(read.graph)
        assertEquals(
            emptyList(), Validator(catalog, source).validate(g).errors().map { it.message },
            "an imported enum's columns should resolve",
        )
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        drive(
            GraphCompiler(catalog, debug = false, source = source)
                .compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 800,
        )
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    @Test
    fun `a column of an imported enum reads through the alias`() {
        assertEquals(
            listOf(77L, 43701L, "north"),
            saidAcross(
                """
                graph "probe"

                import * as ids from "ids"

                on start {
                    say(ids::Altar.Blood.level)
                    say(ids::Altar.Air.obj)
                    say(ids::Altar.Air.at)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The same read off a name, and off a RECORD field holding one.
     *
     * `rumor.vs` relays a duplicate column per field because `rumor.target.selfPaced` reported a missing
     * field, and blamed the struct lowering for tracing a struct-get's pin back to a struct rather than to
     * an enum. It was never the struct lowering: the enum simply arrived with no table. Locally the
     * identical read always worked, which is what made the struct the plausible suspect.
     */
    @Test
    fun `a column reads off a local and off a record field of imported enum type`() {
        assertEquals(
            listOf(1L, 77L),
            saidAcross(
                """
                graph "probe"

                import * as ids from "ids"

                on start {
                    val a = ids::Altar.Air
                    say(a.level)
                    val r = ids::Row{ a: ids::Altar.Blood, n: 2 }
                    say(r.a.level)
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- values() ------------------------------------------------------------------------------------
    //
    // `gotr-ids` writes all twelve altars out by hand into a `var All` so it can walk them, and says why:
    // "an enum's members cannot be iterated — there is no `values()`, a member is a literal". The list and
    // the declaration then have to be kept in step by hand, which is the kind of duplication a table was
    // introduced to remove.

    @Test
    fun `values gives every member in declaration order`() {
        assertEquals(
            listOf("WildKebbit", "HornedGraahk", "RedSalamander"),
            said(
                """
                $targets
                on start {
                    for t in Target.values() {
                        say(t)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** The elements carry the ENUM's type, which is what makes the loop able to read a column. */
    @Test
    fun `a member from values can be read as a table row`() {
        assertEquals(
            listOf(30L, 30L, 5000L),
            said(
                """
                $targets
                on start {
                    for t in Target.values() {
                        say(t.count)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `values prints back as it was written`() {
        val src = """
            $targets
            on start {
                for t in Target.values() {
                    say(t.count)
                }
            }
        """.trimIndent()
        val printed = Print(catalog).print(graphOf(src))
        assertTrue("Target.values()" in printed, printed)
    }

    @Test
    fun `values on something that is not a choice is refused`() {
        val low = lower(
            """
            graph "probe"
            on start {
                for t in Nosuch.values() {
                    say(t)
                }
            }
            """.trimIndent(),
        )
        assertTrue(low.errors.isNotEmpty(), "should not resolve: ${low.errors.map { it.message }}")
    }
}
