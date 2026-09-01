package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
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
 * `val` — one word for a name bound once, at either scope.
 *
 * **It replaces three spellings.** `let` inside a body, `const` at document level, and `const` inside a
 * body — where the third differed from the first only by folding to a literal instead of a binding, which
 * is a distinction about the graph rather than about the meaning. `var` already spanned both scopes under
 * one word; the immutable column needed three, and the asymmetry carried no information.
 *
 * **Which shape a document-level `val` becomes is decided by the VALUE**, in `Lower`. Written out, it folds
 * to a literal node exactly as a `const` did — and that is not an optimisation, it is what keeps it usable
 * in an enum row and a parameter default, where only a literal will do. Anything that has to run becomes a
 * graph variable plus the one-time initialiser a `var`'s computed default already used, with assignment
 * refused. So the new expressible thing is `val Home: TILE = somewhereFound()`, which had no spelling
 * before: a `const` could not run and a `var` could not say it never changes.
 *
 * `let` and `const` are still READ and only `val` is printed. That is the migration — a file converges the
 * first time a tool writes it back — and it is deliberately temporary: two permanent spellings for one
 * construct is what the round-trip contract exists to prevent.
 */
class ValTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** Something that has to RUN, so a `val` of it cannot fold. */
    private val rollNode = hostNode(
        "test.roll", "roll", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.INT)),
    )
    private val catalog = NodeCatalog(listOf(sayNode, rollNode) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)
    private val text = VsText(catalog)

    private fun lower(src: String) = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)

    private fun problems(src: String): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { it.message }
        val low = Lower(catalog).lower(parsed.program)
        if (low.errors.isNotEmpty()) return low.errors.map { it.message }
        return Validator(catalog).validate(low.graph).errors().map { it.message }
    }

    var rolls = 0

    private fun said(src: String): List<Any?> {
        val low = lower(src)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors().map { it.message })
        val out = ArrayList<Any?>()
        rolls = 0
        val hosts = BuiltinHosts.registry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        hosts.register("roll", HostKind.INLINE, arity = 0, results = 1) { rolls++; 40L + rolls }
        drive(
            GraphCompiler(catalog, debug = false)
                .compile(low.graph, low.graph.entries(catalog).single().id),
            hosts, maxTicks = 200,
        )
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun roundTrip(src: String) {
        val low = lower(src)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(src.trim(), text.write(low.graph).trim(), "round trip")
    }

    // ---- inside a body — what `let` was ---------------------------------------------------------------

    @Test
    fun `a val binds a name`() {
        assertEquals(
            listOf(5L),
            said(
                """
                graph "probe"
                on start {
                    val n = 5
                    say(n)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a val is evaluated once however many read it`() {
        assertEquals(listOf(41L, 41L), said(
            """
            graph "probe"
            on start {
                val n = roll()
                say(n)
                say(n)
            }
            """.trimIndent(),
        ))
        assertEquals(1, rolls, "a binding names one value, it does not re-ask for it")
    }

    @Test
    fun `a val cannot be assigned, and the message names var`() {
        val p = problems(
            """
            graph "probe"
            on start {
                val n = 5
                n = 6
            }
            """.trimIndent(),
        )
        assertTrue(p.any { "`val`" in it && "var n" in it }, "got: $p")
    }

    @Test
    fun `a val may destructure, and may declare its type`() {
        roundTrip(
            """
            graph "probe"

            export fn pair() -> (low: INT, high: INT) {
                return 1, 2
            }

            on start {
                val (low, high) = pair()
                val n: FLOAT = 0
                say(message: low + high + n)
            }
            """.trimIndent(),
        )
    }

    // ---- the old spellings still read, and converge ---------------------------------------------------

    @Test
    fun `let is still accepted and prints back as val`() {
        val printed = text.write(
            lower(
                """
                graph "probe"
                on start {
                    let n = 5
                    say(n)
                }
                """.trimIndent(),
            ).graph,
        )
        assertTrue("val n = 5" in printed, "a `let` should converge to `val`:\n$printed")
        assertTrue("let " !in printed, printed)
    }

    @Test
    fun `const is still accepted and prints back as val`() {
        val printed = text.write(lower("""graph "probe"${'\n'}const Speed = 600${'\n'}on start { say(Speed) }""").graph)
        assertTrue("val Speed = 600" in printed, "a `const` should converge to `val`:\n$printed")
        assertTrue("const " !in printed, printed)
    }

    @Test
    fun `if let is still accepted and prints back as if val`() {
        val printed = text.write(
            lower(
                """
                graph "probe"
                export fn maybe() -> INT? = null
                on start {
                    if let n = maybe() {
                        say(n)
                    }
                }
                """.trimIndent(),
            ).graph,
        )
        assertTrue("if val n =" in printed, printed)
    }

    // ---- `while val` — GAPS #9 -----------------------------------------------------------------------
    //
    // Desugared in the PARSER to the shape people were already writing by hand — a `while true` around one
    // `if val` with a `break` — so it costs no node, no lowering and nothing in the VM. What it costs is a
    // recognizer in `Print`, which is the price every piece of sugar here pays.

    @Test
    fun `while val takes values until there are none left`() {
        assertEquals(
            listOf(2L, 1L, 0L),
            said(
                """
                graph "probe"
                export var Left: INT = 3
                export fn next() -> INT? {
                    if Left <= 0 {
                        return null
                    }
                    Left = Left - 1
                    return Left
                }
                on start {
                    while val n = next() {
                        say(n)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `while val runs its body not at all when the first is empty`() {
        assertEquals(
            emptyList(),
            said(
                """
                graph "probe"
                export fn next() -> INT? = null
                on start {
                    while val n = next() {
                        say(n)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `while val round-trips as itself`() {
        roundTrip(
            """
            graph "probe"

            export fn next() -> INT? = null

            on start {
                while val n = next() {
                    say(message: n)
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `while let is still accepted and prints back as while val`() {
        val printed = text.write(
            lower(
                """
                graph "probe"
                export fn next() -> INT? = null
                on start {
                    while let n = next() {
                        say(n)
                    }
                }
                """.trimIndent(),
            ).graph,
        )
        assertTrue("while val n =" in printed, printed)
    }

    /**
     * The hand-written shape and the sugar are the SAME GRAPH, so the sugar prints for both.
     *
     * That is the property that makes it admissible rather than an accident: `while true { if val n = f()
     * { … } else { break } … }` and `while val n = f() { … … }` are not merely equivalent, they lower to
     * identical nodes — the `else` broke, so the statements after the `if val` attach to the Then arm and
     * end up inside the loop body either way. Printing the shorter word back is therefore not a guess about
     * what somebody typed; it is the one spelling of one graph, which is what §6.7 asks of every piece of
     * sugar here.
     */
    @Test
    fun `the hand-written shape is the same graph, and prints as the sugar`() {
        fun printed(src: String) = text.write(lower(src).graph)
        val byHand = printed(
            """
            graph "probe"
            export fn next() -> INT? = null
            on start {
                while true {
                    if val n = next() {
                        say(n)
                    } else {
                        break
                    }
                    say(0)
                }
            }
            """.trimIndent(),
        )
        val sugared = printed(
            """
            graph "probe"
            export fn next() -> INT? = null
            on start {
                while val n = next() {
                    say(n)
                    say(0)
                }
            }
            """.trimIndent(),
        )
        assertEquals(sugared, byHand, "the two spellings are one graph")
        assertTrue("while val n =" in sugared, sugared)
    }

    // ---- at document level, written out — what `const` was --------------------------------------------

    @Test
    fun `a folded val is one shared literal node`() {
        val g = lower("""graph "probe"${'\n'}val n = 7${'\n'}on start { say(n) say(n) }""").graph
        assertEquals(
            1, g.nodes.count { it.type == BuiltinNodes.LITERAL_INT },
            "one literal node with a wire to each reader — that is what makes it one tunable knob",
        )
        assertTrue(g.variables.none { it.name == "n" }, "a folded val is not a variable")
    }

    @Test
    fun `a folded val round-trips`() {
        roundTrip(
            """
            graph "probe"

            export val Speed = 600

            on start {
                say(message: Speed)
            }
            """.trimIndent(),
        )
    }

    /** The whole reason the fold is kept: only a literal will do in these two places. */
    @Test
    fun `a folded val may fill an enum row and a parameter default`() {
        assertEquals(
            emptyList(),
            problems(
                """
                graph "probe"
                export val Needed = 30
                export enum T(count: INT) { Kebbit(Needed) }
                export fn scaled(n: INT, by: INT = Needed) -> INT = n * by
                on start { say(T.Kebbit.count + scaled(n: 1)) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a folded val may be a tile, and stays one`() {
        roundTrip(
            """
            graph "probe"

            export val Home = tile(3200, 3200, 0)

            on start {
                say(message: Home.x)
            }
            """.trimIndent(),
        )
    }

    // ---- at document level, computed — the new thing --------------------------------------------------

    @Test
    fun `a computed val runs once, before the body`() {
        assertEquals(listOf(41L, 41L), said(
            """
            graph "probe"
            export val Seed: INT = roll()
            on start {
                say(Seed)
                say(Seed)
            }
            """.trimIndent(),
        ))
        assertEquals(1, rolls, "the initialiser is a prologue, not a re-read")
    }

    @Test
    fun `a computed val is a graph variable underneath`() {
        val g = lower("""graph "probe"${'\n'}val Seed: INT = roll()${'\n'}on start { say(Seed) }""").graph
        val v = g.variables.single { it.name == "Seed" }
        assertTrue(v.isImmutable, "it has to be marked, or the printer gives it back as a var")
    }

    @Test
    fun `a computed val refuses assignment`() {
        val p = problems(
            """
            graph "probe"
            export val Seed: INT = roll()
            on start {
                Seed = 3
            }
            """.trimIndent(),
        )
        assertTrue(p.any { "`val`" in it && "set once" in it }, "got: $p")
    }

    /**
     * A graph variable is always typed, and by the time we know this became one it is too late to guess.
     * The message says which case it is in rather than restating the rule.
     */
    @Test
    fun `a computed val with no type says what to write`() {
        val p = problems("""graph "probe"${'\n'}val Seed = roll()${'\n'}on start { say(Seed) }""")
        assertTrue(p.any { "Seed: <Type>" in it }, "got: $p")
    }

    @Test
    fun `a computed val round-trips as a val, not as a var and an init`() {
        roundTrip(
            """
            graph "probe"

            export val Seed: INT = roll()

            on start {
                say(message: Seed)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a computed val may be exported`() {
        roundTrip(
            """
            graph "probe"

            export val Seed: INT = roll()

            on start {
                say(message: Seed)
            }
            """.trimIndent(),
        )
    }

    /**
     * A written type does NOT decide whether a value folds — the value does, and the type is carried.
     *
     * **It used to decide, and that made an annotation change what a declaration meant.** `val Speed: INT
     * = 600` became a graph variable while the identical line without `: INT` became a constant — so
     * adding a type for a reader quietly took the declaration out of every place a constant is allowed:
     * an enum row could not name it, and it did not cross an import as a value. The reason given was that
     * a literal node had nowhere to carry the type, which was true and is what `Lower.CONST_TYPE` fixes.
     */
    @Test
    fun `a written type is carried rather than forcing a variable`() {
        val g = lower("""graph "probe"${'\n'}val Speed: INT = 600${'\n'}on start { say(Speed) }""").graph
        assertTrue(g.variables.none { it.name == "Speed" }, "a value written out is a constant, not a slot")
    }

    /** ...and the annotation survives the round trip, which is what it needed somewhere to live for. */
    @Test
    fun `a written type comes back`() {
        roundTrip(
            """
            graph "probe"

            val Speed: INT = 600

            on start {
                say(message: Speed)
            }
            """.trimIndent(),
        )
    }
}
