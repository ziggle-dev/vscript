package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Optionals — `T?`, `if val` and `?:`.
 *
 * **The single largest tax in the corpus was faking one.** Eight of the nine multi-value returns exist only
 * to say "maybe" (`-> (found: Bool, tile: Tile)`), thirteen booleans exist only to say whether the value
 * beside them means anything (`HaveAnchor`, `HaveHome`, `HavePending`, …), and `salamander` writes
 * `tile(0, 0, 0)` as a stand-in for nothing with a comment explaining that the alternative crashed:
 *
 * > A bare `var MissTile: Tile` is NULL until something assigns it, and `sameTile` reads `.x` off both
 * > arguments — so the first Check that came up empty, an ordinary missed catch, crashed the graph with
 * > `GETFIELD on null, expected a record`.
 *
 * One missing feature, three workarounds, and a crash the type system never mentioned. So the tests here
 * are in two halves: that the type REFUSES the ways a null used to get in, and that the two forms for
 * getting a value back out of one produce the right answer and the right narrowed type.
 */
class OptionalTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** A query that answers "nothing" — the shape every `maybe` in the corpus is really asking for. */
    private val maybeNode = hostNode(
        "test.maybe", "test.maybe", NodeKind.PURE,
        inputs = listOf(PinSpec("When", PinType.BOOL, default = true)),
        outputs = listOf(PinSpec("Value", TypeRef.parse("INT?"))),
    )

    /** Counts its calls, so a short circuit can be proved rather than assumed. */
    private val costlyNode = hostNode(
        "test.costly", "test.costly", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.INT)),
    )

    private val catalog = NodeCatalog(listOf(sayNode, maybeNode, costlyNode) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)
    private val text = VsText(catalog)

    // ---- harness -------------------------------------------------------------------------------------

    private fun lower(src: String): Lower.Result {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        return Lower(catalog).lower(parsed.program)
    }

    private fun graphOf(src: String): Graph {
        val low = lower(src)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(
            emptyList(), Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }
                .map { it.message },
            "did not validate",
        )
        return low.graph
    }

    /** Every ERROR a document produces, at whichever stage produced it. */
    private fun errors(src: String): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { it.message }
        val low = Lower(catalog).lower(parsed.program)
        if (low.errors.isNotEmpty()) return low.errors.map { it.message }
        return Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message }
    }

    var costlyCalls = 0

    private fun said(src: String): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        costlyCalls = 0
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        hosts.register("test.maybe", HostKind.INLINE, arity = 1, results = 1) { a ->
            if (a[0] == true) 7L else null
        }
        hosts.register("test.costly", HostKind.INLINE, arity = 0, results = 1) { costlyCalls++; 99L }
        val r = drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 600,
        )
        assertNull(r.fiber.error, "vm error")
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    /** The round trip is the whole contract for a new piece of syntax, so most tests here include it. */
    private fun roundTrip(src: String) {
        assertEquals(src.trim(), text.write(graphOf(src)).trim(), "round trip")
    }

    // ---- the type ------------------------------------------------------------------------------------

    @Test
    fun `an optional survives a round trip everywhere a type can be written`() {
        roundTrip(
            """
            graph "t"

            export type Spot { at: Tile?, note: STRING }

            export var Home: Tile? = null

            export fn find(from: Tile?) -> Tile? = from

            on start {
                val s = Spot { at: null, note: "x" }
                say(message: s.note)
            }
            """.trimIndent()
        )
    }

    /**
     * The rule, in one test: `T` flows into `T?` and `T?` does not flow into `T`.
     *
     * This is what turns `salamander`'s documented 3am crash into a message at the wire that caused it.
     */
    @Test
    fun `an optional does not fit a slot that is not one`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export fn keep(t: Tile?) -> INT = 1

                on start {
                    say(message: keep(t: tile(1, 2, 0)))
                }
                """.trimIndent()
            ),
            "a plain value should fit an optional slot",
        )
        assertTrue(
            errors(
                """
                graph "t"

                export fn maybe() -> Tile? = null
                export fn needs(t: Tile) -> INT = 1

                on start {
                    say(message: needs(t: maybe()))
                }
                """.trimIndent()
            ).isNotEmpty(),
            "an optional should not fit a plain slot",
        )
    }

    /**
     * `var MissTile: Tile` — the exact declaration `salamander` warns about, now refused.
     *
     * The message has to offer both cures, because both are right in different files: give it a value, or
     * say out loud that it is sometimes absent.
     */
    @Test
    fun `a variable that is never nothing must start with something`() {
        val e = errors(
            """
            graph "t"

            export var MissTile: Tile

            on start {
                say(message: 1)
            }
            """.trimIndent()
        )
        assertEquals(1, e.size, "expected exactly one complaint, got $e")
        assertTrue("starts as nothing" in e[0], e[0])
        assertTrue("Tile?" in e[0], "should offer the optional as the cure: ${e[0]}")
    }

    @Test
    fun `declaring it optional is the other cure`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export var MissTile: Tile?

                on start {
                    say(message: 1)
                }
                """.trimIndent()
            ),
        )
    }

    /** A record starts as its own zero, field by field, so it was never the null this rule is about. */
    @Test
    fun `a record variable still needs no default`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export type Spot { x: INT, y: INT }

                export var Where: Spot

                on start {
                    say(message: Where.x)
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `null cannot be typed into a slot that is never nothing`() {
        val e = errors(
            """
            graph "t"

            export fn needs(t: Tile) -> INT = 1

            on start {
                say(message: needs(t: null))
            }
            """.trimIndent()
        )
        assertTrue(e.any { "never nothing" in it }, "expected a complaint about null, got $e")
    }

    /** `is` reports a runtime KIND, and absence is not one — so the two questions stay apart. */
    @Test
    fun `is does not take an optional`() {
        assertTrue(
            errors(
                """
                graph "t"

                export type Spot { x: INT }

                on start {
                    val s = Spot { x: 1 }
                    say(message: s is Spot?)
                }
                """.trimIndent()
            ).any { "is there" in it },
        )
    }

    // ---- `?:` ----------------------------------------------------------------------------------------

    @Test
    fun `or else takes the value when it is there and the fallback when it is not`() {
        assertEquals(
            listOf<Any?>(7L, 43L),
            said(
                """
                graph "t"

                on start {
                    say(message: maybe(when: true) ?: 43)
                    say(message: maybe(when: false) ?: 43)
                }
                """.trimIndent()
            ),
        )
    }

    /**
     * The fallback sits behind a jump, so it is not merely unused when the value was there — it never runs.
     * That is what makes `nearest() ?: expensiveDefault()` an honest thing to write.
     */
    @Test
    fun `the fallback is not evaluated when the value is there`() {
        assertEquals(
            listOf<Any?>(7L),
            said(
                """
                graph "t"

                on start {
                    say(message: maybe(when: true) ?: costly())
                }
                """.trimIndent()
            ),
        )
        assertEquals(0, costlyCalls, "the fallback ran anyway")
    }

    /** Right-associative, so a chain takes the first thing that is actually there. */
    @Test
    fun `or else chains`() {
        assertEquals(
            listOf<Any?>(43L),
            said(
                """
                graph "t"

                on start {
                    say(message: maybe(when: false) ?: maybe(when: false) ?: 43)
                }
                """.trimIndent()
            ),
        )
    }

    /**
     * Kotlin's placement: tighter than a comparison, looser than arithmetic.
     *
     * Written as a round trip because that is where a precedence mistake shows up — as a stray or a
     * missing parenthesis. Note there are none here at all, and that is the assertion: `?: 1 + 2` groups
     * the sum as the fallback, `?: 1 == 3` compares the RESULT, and a chain associates rightwards. Every
     * one of those needs a bracket if the printer has the level wrong.
     */
    @Test
    fun `or else binds tighter than a comparison and looser than arithmetic`() {
        roundTrip(
            """
            graph "t"

            on start {
                say(message: maybe(when: true) ?: 1 + 2)
                say(message: maybe(when: true) ?: 1 == 3)
                say(message: maybe(when: true) ?: maybe(when: false) ?: 3)
            }
            """.trimIndent()
        )
    }

    /** And where the grouping is NOT the default one, the brackets come back. */
    @Test
    fun `parentheses survive where they change the grouping`() {
        roundTrip(
            """
            graph "t"

            on start {
                say(message: (maybe(when: false) ?: 1) + 2)
                say(message: (maybe(when: false) ?: maybe(when: true)) ?: 3)
            }
            """.trimIndent()
        )
    }

    /** Its result is the value's type with the `?` removed — which is the point of it being a node. */
    @Test
    fun `or else narrows the type`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export fn needs(n: INT) -> INT = n

                on start {
                    say(message: needs(n: maybe(when: true) ?: 0))
                }
                """.trimIndent()
            ),
            "the result of ?: should fit a slot that is never nothing",
        )
    }

    // ---- `if val` ------------------------------------------------------------------------------------

    @Test
    fun `if val runs the some arm with the value bound`() {
        assertEquals(
            listOf<Any?>(7L, "none"),
            said(
                """
                graph "t"

                on start {
                    if val a = maybe(when: true) {
                        say(message: a)
                    } else {
                        say(message: "none")
                    }
                    if val b = maybe(when: false) {
                        say(message: b)
                    } else {
                        say(message: "none")
                    }
                }
                """.trimIndent()
            ),
        )
    }

    /** No `else` is an ordinary `if val`: the None arm is simply unwired. */
    @Test
    fun `if val needs no else`() {
        assertEquals(
            listOf<Any?>(7L, 1L),
            said(
                """
                graph "t"

                on start {
                    if val a = maybe(when: true) {
                        say(message: a)
                    }
                    if val b = maybe(when: false) {
                        say(message: b)
                    }
                    say(message: 1)
                }
                """.trimIndent()
            ),
        )
    }

    /**
     * **The binding is NOT optional**, which is the whole reason this is a node and not sugar over
     * `!= null`: a type belongs to a pin, and on the canvas the wire into a branch is the same wire as the
     * one outside it — so nothing can narrow by re-typing. `Value` is a new pin, and this is that.
     */
    @Test
    fun `the bound value is narrowed`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export fn needs(n: INT) -> INT = n

                on start {
                    if val a = maybe(when: true) {
                        say(message: needs(n: a))
                    }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `if val round-trips, with and without an else`() {
        roundTrip(
            """
            graph "t"

            on start {
                if val a = maybe(when: true) {
                    say(message: a)
                }
                if val b = maybe(when: false) {
                    say(message: b)
                } else {
                    say(message: 0)
                }
            }
            """.trimIndent()
        )
    }

    // ---- `?.` ----------------------------------------------------------------------------------------

    @Test
    fun `safe access reads through a value that is there and answers nothing when it is not`() {
        assertEquals(
            listOf<Any?>(2L, null),
            said(
                """
                graph "t"

                export type Spot { x: INT }

                export fn spot(when: BOOL) -> Spot? = when ? Spot { x: 2 } : null

                on start {
                    say(message: spot(when: true)?.x)
                    say(message: spot(when: false)?.x)
                }
                """.trimIndent()
            ),
        )
    }

    /**
     * **The receiver is asked ONCE.** This is the whole reason `?.` is a node rather than sugar over
     * `a != null ? a.b : null`: written out by hand, that spelling evaluates `a` twice, and for a query
     * that means two host calls which may not even agree with each other.
     */
    @Test
    fun `the receiver of a safe access is evaluated once`() {
        assertEquals(
            listOf<Any?>(99L),
            said(
                """
                graph "t"

                export type Box { v: INT }

                export fn box() -> Box? = Box { v: costly() }

                on start {
                    say(message: box()?.v)
                }
                """.trimIndent()
            ),
        )
        assertEquals(1, costlyCalls, "the receiver was worked out more than once")
    }

    /** Chained, and paired with `?:` — which is how it will actually get written. */
    @Test
    fun `safe access chains and pairs with or else`() {
        assertEquals(
            listOf<Any?>(5L, 0L),
            said(
                """
                graph "t"

                export type Inner { n: INT }
                export type Outer { inner: Inner? }

                export fn outer(when: BOOL) -> Outer? = when ? Outer { inner: Inner { n: 5 } } : null

                on start {
                    say(message: outer(when: true)?.inner?.n ?: 0)
                    say(message: outer(when: false)?.inner?.n ?: 0)
                }
                """.trimIndent()
            ),
        )
    }

    /** A method call through `?.` — the placeholder sits in the receiver slot, so nothing else changes. */
    @Test
    fun `safe access works on a method call`() {
        assertEquals(
            listOf<Any?>(6L, null),
            said(
                """
                graph "t"

                export type Spot { x: INT }

                export fn Spot.tripled(self) -> INT = self.x * 3

                export fn spot(when: BOOL) -> Spot? = when ? Spot { x: 2 } : null

                on start {
                    say(message: spot(when: true)?.tripled())
                    say(message: spot(when: false)?.tripled())
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `safe access round-trips`() {
        roundTrip(
            """
            graph "t"

            export type Inner { n: INT }
            export type Outer { inner: Inner? }

            export fn outer() -> Outer? = null

            on start {
                say(message: outer()?.inner?.n ?: 0)
            }
            """.trimIndent()
        )
    }

    /**
     * The guard covers ONE access, exactly as Kotlin's does — so `a?.b.c` reads `.c` off something that
     * may be nothing, and strict null is what says so. The cure is another `?.`.
     */
    @Test
    fun `the guard covers one step, and the next one is checked`() {
        assertTrue(
            errors(
                """
                graph "t"

                export type Inner { n: INT }
                export type Outer { inner: Inner }

                export fn outer() -> Outer? = null

                on start {
                    say(message: outer()?.inner.n)
                }
                """.trimIndent()
            ).isNotEmpty(),
            "reading a field off the result of `?.` without guarding it should be refused",
        )
    }

    /** The name that was typed is the name that comes back — it is carried on the node, like a Hold's. */
    @Test
    fun `the bound name survives the round trip`() {
        val printed = text.write(
            graphOf(
                """
                graph "t"

                on start {
                    if val somethingSpecific = maybe(when: true) {
                        say(message: somethingSpecific)
                    }
                }
                """.trimIndent()
            )
        )
        assertTrue("somethingSpecific" in printed, printed)
    }

    // ---- narrowing by comparison ---------------------------------------------------------------------
    //
    // `if x != null { … }` narrows, the same way `if val` does and for the same reason: it lowers to the
    // same node. `If Some` already binds a pin that carries the non-optional, so "narrowing" is nothing
    // more than rebinding the tested NAME onto that pin for the arm the test proved.
    //
    // Only a bound name, and only a single condition — see `Lower.nullTestIf` for why each.

    @Test
    fun `a not-null test narrows inside the branch`() {
        assertEquals(
            listOf(8L),
            said(
                """
                graph "probe"
                export fn takes(n: INT) -> INT = n + 1
                on start {
                    val v = maybe(when: true)
                    if v != null {
                        say(message: takes(n: v))
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the else of a not-null test is the empty case`() {
        assertEquals(
            listOf("nothing"),
            said(
                """
                graph "probe"
                on start {
                    val v = maybe(when: false)
                    if v != null {
                        say(message: v)
                    } else {
                        say(message: "nothing")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** `== null` proves it on the OTHER side, so the narrowing goes to the else. */
    @Test
    fun `an is-null test narrows in the else`() {
        assertEquals(
            listOf(8L),
            said(
                """
                graph "probe"
                export fn takes(n: INT) -> INT = n + 1
                on start {
                    val v = maybe(when: true)
                    if v == null {
                        say(message: "nothing")
                    } else {
                        say(message: takes(n: v))
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an is-null test with no else still runs its body`() {
        assertEquals(
            listOf("nothing"),
            said(
                """
                graph "probe"
                on start {
                    val v = maybe(when: false)
                    if v == null {
                        say(message: "nothing")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a not-null test prints back as it was written, not as if val`() {
        roundTrip(
            """
            graph "probe"

            on start {
                val v = maybe(when: true)
                if v != null {
                    say(message: v)
                } else {
                    say(message: "nothing")
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an is-null test prints back with its arms the way round they were written`() {
        roundTrip(
            """
            graph "probe"

            on start {
                val v = maybe(when: true)
                if v == null {
                    say(message: "nothing")
                } else {
                    say(message: v)
                }
            }
            """.trimIndent(),
        )
    }

    /** An `if val` is untouched by any of this and still prints as itself. */
    @Test
    fun `if val still prints as if val`() {
        roundTrip(
            """
            graph "probe"

            on start {
                if val n = maybe(when: true) {
                    say(message: n)
                }
            }
            """.trimIndent(),
        )
    }

    // ---- `else if val` — GAPS #7 ---------------------------------------------------------------------
    //
    // `else if` on a plain condition has always had a recognizer; the `if val` form had none, so it came
    // back as a nested `else { if val … }` — correct, and one level deeper every time. Three arms drifted
    // right across the page, which is how a round trip that "works" still makes a file worse.

    @Test
    fun `else if val round-trips as written`() {
        roundTrip(
            """
            graph "probe"

            on start {
                if val a = maybe(when: true) {
                    say(message: a)
                } else if val b = maybe(when: false) {
                    say(message: b)
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a chain of three stays flat`() {
        roundTrip(
            """
            graph "probe"

            on start {
                if val a = maybe(when: false) {
                    say(message: a)
                } else if val b = maybe(when: false) {
                    say(message: b)
                } else if val c = maybe(when: true) {
                    say(message: c)
                } else {
                    say(message: 0)
                }
            }
            """.trimIndent(),
        )
    }

    /** Mixed the other way: a plain `if` whose else is an `if val`. */
    @Test
    fun `an if whose else is an if val round-trips`() {
        roundTrip(
            """
            graph "probe"

            export var Ready: BOOL = false

            on start {
                if Ready {
                    say(message: 1)
                } else if val b = maybe(when: true) {
                    say(message: b)
                }
            }
            """.trimIndent(),
        )
    }

    /** And an `if val` whose else is a plain `if`. */
    @Test
    fun `an if val whose else is a plain if round-trips`() {
        roundTrip(
            """
            graph "probe"

            export var Ready: BOOL = false

            on start {
                if val a = maybe(when: false) {
                    say(message: a)
                } else if Ready {
                    say(message: 1)
                }
            }
            """.trimIndent(),
        )
    }

    /** The arms must still RUN the way they read, not just print that way. */
    @Test
    fun `an else if val chain takes the first arm that has a value`() {
        assertEquals(
            listOf(7L),
            said(
                """
                graph "probe"

                on start {
                    if val a = maybe(when: false) {
                        say(message: 1)
                    } else if val b = maybe(when: true) {
                        say(message: b)
                    } else {
                        say(message: 3)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an else if val chain falls through to the else when nothing has a value`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                on start {
                    if val a = maybe(when: false) {
                        say(message: 1)
                    } else if val b = maybe(when: false) {
                        say(message: b)
                    } else {
                        say(message: 3)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- narrowing in an EXPRESSION ------------------------------------------------------------------
    //
    // `text != null ? needs(text) : fail()`. Sound for the same reason the statement form is: `Select`
    // compiles to a jump over the arm it did not take, so the proved arm only runs when the test held.

    @Test
    fun `a ternary narrows the arm its test proved`() {
        assertEquals(
            listOf(8L),
            said(
                """
                graph "probe"
                export fn takes(n: INT) -> INT = n + 1
                on start {
                    val v = maybe(when: true)
                    val out = v != null ? takes(n: v) : 0
                    say(message: out)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a ternary takes the other arm when the value is missing`() {
        assertEquals(
            listOf(0L),
            said(
                """
                graph "probe"
                export fn takes(n: INT) -> INT = n + 1
                on start {
                    val v = maybe(when: false)
                    val out = v != null ? takes(n: v) : 0
                    say(message: out)
                }
                """.trimIndent(),
            ),
        )
    }

    /** `== null` proves it on the FALSE arm, so that is the one that narrows. */
    @Test
    fun `a ternary written the other way round narrows the other arm`() {
        assertEquals(
            listOf(8L),
            said(
                """
                graph "probe"
                export fn takes(n: INT) -> INT = n + 1
                on start {
                    val v = maybe(when: true)
                    val out = v == null ? 0 : takes(n: v)
                    say(message: out)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a narrowing ternary prints back as it was written`() {
        roundTrip(
            """
            graph "probe"

            export fn takes(n: INT) -> INT = n + 1

            on start {
                val v = maybe(when: true)
                val out = v != null ? takes(n: v) : 0
                say(message: out)
            }
            """.trimIndent(),
        )
    }

    // ---- narrowing across an `&&` chain --------------------------------------------------------------

    @Test
    fun `a null test narrows the rest of its own condition`() {
        assertEquals(
            listOf("big"),
            said(
                """
                graph "probe"
                export fn takes(n: INT) -> INT = n + 1
                on start {
                    val v = maybe(when: true)
                    if v != null && takes(n: v) > 3 {
                        say(message: "big")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `and the short circuit still holds when it is missing`() {
        assertEquals(
            listOf("none"),
            said(
                """
                graph "probe"
                export fn takes(n: INT) -> INT = n + 1
                on start {
                    val v = maybe(when: false)
                    if v != null && takes(n: v) > 3 {
                        say(message: "big")
                    } else {
                        say(message: "none")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** It composes with the rest of the `?` family: `?.` and `?:` are untouched by any of this. */
    @Test
    fun `narrowing composes with safe access and or-else`() {
        assertEquals(
            listOf(7L, 43L),
            said(
                """
                graph "probe"
                on start {
                    val v = maybe(when: true)
                    if v != null {
                        say(message: v)
                    }
                    say(message: maybe(when: false) ?: 43)
                }
                """.trimIndent(),
            ),
        )
    }
}
