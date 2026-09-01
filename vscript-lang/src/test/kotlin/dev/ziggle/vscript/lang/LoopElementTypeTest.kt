package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `for p in points { p.x }` — a loop variable keeps the list's element type.
 *
 * **`ForEach.Element` is declared WILDCARD**, because one node iterates every kind of list. Every other
 * construct whose pins are wildcards for that reason has a rule in `effectivePinType` saying what they
 * really carry — a Hold carries what fed it, a cast carries what it reads, arithmetic carries its
 * operands. ForEach had none, so the wildcard simply stood and the element had no type at all.
 *
 * What that looked like was "this has no field 'x'" on a list whose element type the document declares on
 * the line above. It is the same silent widening the Hold rule exists to prevent, one construct along: a
 * record that goes through a loop stops being a record.
 *
 * The workaround was to hand the element straight to a typed function and read the fields in there. That
 * works, which is what made this survive — but it makes `for` the one binding form in the language whose
 * variable has no type, and nobody should have to learn that.
 */
class LoopElementTypeTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun said(src: String): List<Any?> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")

        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val entry = low.graph.entries(catalog).single()
        drive(GraphCompiler(catalog, debug = false).compile(low.graph, entry.id), hosts, maxTicks = 400)
        return out
    }

    @Test
    fun `a loop variable can have its fields read`() {
        assertEquals(
            listOf<Any?>(3L, 7L),
            said(
                """
                graph "probe"

                export type Point { x: INT, y: INT }

                export var Points: LIST < Point > = []

                on start {
                    Points = [Point{ x: 3, y: 4 }, Point{ x: 7, y: 8 }]
                    for p in Points {
                        say(p.x)
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /** A record BUILT from the loop variable's fields — the shape the hunter scripts actually needed. */
    @Test
    fun `a loop variable's fields can be combined`() {
        assertEquals(
            listOf<Any?>(7L, 15L),
            said(
                """
                graph "probe"

                export type Point { x: INT, y: INT }

                export var Points: LIST < Point > = []

                on start {
                    Points = [Point{ x: 3, y: 4 }, Point{ x: 7, y: 8 }]
                    for p in Points {
                        say(p.x + p.y)
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /** Nested loops each keep their OWN element type, rather than the inner one inheriting the outer. */
    @Test
    fun `nested loops each keep their own element type`() {
        assertEquals(
            listOf<Any?>(11L, 12L, 21L, 22L),
            said(
                """
                graph "probe"

                export type Point { x: INT, y: INT }

                export var A: LIST < Point > = []
                export var B: LIST < Point > = []

                on start {
                    A = [Point{ x: 10, y: 0 }, Point{ x: 20, y: 0 }]
                    B = [Point{ x: 1, y: 0 }, Point{ x: 2, y: 0 }]
                    for outer in A {
                        for inner in B {
                            say(outer.x + inner.x)
                        }
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /**
     * An UNTYPED list still works, and still refuses field access.
     *
     * The rule only fills in a type the document already stated; where there is none the wildcard stands
     * exactly as it did. Both halves matter — the first is the fix, the second is what stops it becoming
     * a licence to read fields off anything.
     */
    @Test
    fun `an untyped list still iterates`() {
        assertEquals(
            listOf<Any?>(1L, 2L, 3L),
            said(
                """
                graph "probe"

                on start {
                    for n in [1, 2, 3] {
                        say(n)
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /** A `var` local holding a record, reassigned inside a loop — the other half of the same question. */
    @Test
    fun `a mutable local keeps its record type`() {
        assertEquals(
            listOf<Any?>(0L, 3L),
            said(
                """
                graph "probe"

                export type Point { x: INT, y: INT }

                export var Points: LIST < Point > = []

                on start {
                    Points = [Point{ x: 3, y: 4 }, Point{ x: 7, y: 8 }]
                    var prev = Point{ x: 0, y: 0 }
                    for p in Points {
                        say(prev.x)
                        prev = p
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    /**
     * The same, for a HOST's record — which is where it actually bit.
     *
     * `Tile` is a struct like any other as far as field access goes, but it reaches the language through
     * `HostRecords` rather than through a `type` declaration, so it exercises a different path to the
     * same question. It was a BUILTIN when this was written, through `BuiltinTypes`; that was a third
     * path and it is gone, which is why the test now says "host" where it used to say "builtin".
     */
    @Test
    fun `a mutable local holding a host record keeps its type`() {
        assertEquals(
            listOf<Any?>(0L, 3L),
            said(
                """
                graph "probe"

                export var Tiles: LIST < TILE > = []

                on start {
                    Tiles = [Tile{ x: 3, y: 4, plane: 0 }, Tile{ x: 7, y: 8, plane: 0 }]
                    var prev = Tile{ x: 0, y: 0, plane: 0 }
                    for t in Tiles {
                        say(prev.x)
                        prev = t
                    }
                }
                """.trimIndent(),
            ).map { if (it is Number) it.toLong() else it },
        )
    }

    @Test
    fun `a field that does not exist is still reported`() {
        val parsed = Parser(
            Lexer(
                """
                graph "probe"

                export type Point { x: INT, y: INT }

                export var Points: LIST < Point > = []

                on start {
                    for p in Points {
                        say(p.zzz)
                    }
                }
                """.trimIndent(),
            ).lex(),
        ).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(!low.ok, "reading a field the record does not have must still be an error")
        assertTrue(
            low.errors.any { it.message.contains("zzz") },
            "the message should name the field: ${low.errors.map { it.message }}",
        )
    }
}
