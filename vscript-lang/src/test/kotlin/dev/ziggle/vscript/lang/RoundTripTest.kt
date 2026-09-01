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
 * The round-trip contract, tested as a **fixed point**.
 *
 * The spec's claim is that text → graph → text is character-identical *given the input was already
 * printer-canonical*, which is not something an arbitrary source can be asserted against: the printer writes
 * named arguments and its own line breaks, and a hand-written file need not. So the property actually tested
 * is the one that matters and that holds for every input — **printing twice changes nothing**:
 *
 *     print(lower(parse(src)))  ==  print(lower(parse(print(lower(parse(src))))))
 *
 * A missing recognizer breaks this immediately. If `Lower` emits a Select for `&&` and `Print` has no rule
 * for it, the second pass writes a ternary where the first wrote `&&`, and the two differ. That is exactly
 * the failure the contract exists to prevent, and it is caught here without anyone having to hand-write the
 * canonical form of every construct.
 *
 * The second property is the other direction: the graph printed and re-read must still **do** the same
 * thing. Structure may shift; behaviour may not.
 */
class RoundTripTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val counterNode = hostNode(
        "test.counter", "counter", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.INT)),
    )
    private val catalog = NodeCatalog(listOf(sayNode, counterNode))

    private fun parse(src: String): Program {
        val r = Parser(Lexer(src).lex()).parse()
        assertTrue(r.ok, "parse errors: ${r.errors.map { it.message }}")
        return r.program
    }

    private fun render(src: String): String {
        val lowered = Lower(catalog).lower(parse(src))
        assertTrue(lowered.ok, "lowering errors: ${lowered.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(lowered.graph).errors(), "did not validate")
        return Print(catalog).print(lowered.graph)
    }

    /** Print it, read it back, print it again — the two must agree. */
    private fun fixedPoint(src: String): String {
        val once = render(src)
        val twice = render(once)
        assertEquals(once, twice, "printing is not a fixed point — a recognizer is missing")
        return once
    }

    private fun said(src: String): List<Any?> {
        val lowered = Lower(catalog).lower(parse(src))
        assertTrue(lowered.ok, "lowering errors: ${lowered.errors}")
        val out = ArrayList<Any?>()
        val hosts = HostRegistry()
        hosts.register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        hosts.register("counter", HostKind.INLINE) { 1 }
        val entry = lowered.graph.entries(catalog).single()
        drive(GraphCompiler(catalog, debug = false).compile(lowered.graph, entry.id), hosts, maxTicks = 200)
        return out
    }

    /** Print it, read it back, and check it still DOES the same thing. Structure may shift; behaviour may not. */
    private fun behaviourSurvives(src: String) {
        assertEquals(said(src), said(render(src)), "the printed form does not behave like the original")
    }

    private fun check(src: String) {
        fixedPoint(src)
        behaviourSurvives(src)
    }

    // ---- statements -------------------------------------------------------------------------------------

    @Test fun `a call`() = check("""on start { say("hi") }""")

    @Test fun `several calls in order`() = check("on start { say(1) say(2) say(3) }")

    @Test fun `if else`() = check("""on start { if true { say(1) } else { say(2) } say(3) }""")

    @Test fun `if with no else`() = check("on start { if true { say(1) } say(2) }")

    @Test
    fun `else if chains stay chained`() {
        // The recognizer under test: a Branch reached only from the enclosing Branch's False pin is an
        // `else if`, not a nested block. Without it the second pass writes `else { if … }` and the fixed
        // point fails — which is precisely how a missing recognizer announces itself.
        val out = fixedPoint("on start { if false { say(1) } else if true { say(2) } else { say(3) } }")
        assertTrue("else if" in out, "an else-if chain was flattened into a nested block:\n$out")
    }

    @Test fun `while`() = check("var n: Int = 0\non start { while n < 3 { n = n + 1 } say(n) }")

    @Test fun `for each`() = check("on start { for x in [1, 2, 3] { say(x) } }")

    @Test fun `for each with an index`() = check("on start { for (x, i) in [1, 2, 3] { say(i) } }")

    @Test fun `sequence`() = check("on start { sequence { say(1) } { say(2) } }")

    @Test fun `a stop handler`() = check("""on start { say(1) } on stop { say(2) }""")

    // ---- bindings ----------------------------------------------------------------------------------------

    @Test
    fun `let survives as let`() {
        val out = fixedPoint("on start { val n = counter() say(n) say(n) }")
        assertTrue("val n =" in out, "a Hold did not print back as a let:\n$out")
    }

    @Test
    fun `const keeps its name`() {
        // The reason a const node carries `@name`: without somewhere to keep it, a construct that exists
        // only to be named could be written and never printed back.
        val out = fixedPoint("val speed = 600\non start { say(speed) }")
        assertTrue("val speed = 600" in out, "a const lost its name:\n$out")
    }

    @Test fun `a variable and its default`() = check("var trips: Int = 5\non start { trips = trips + 1 say(trips) }")

    // ---- expressions --------------------------------------------------------------------------------------

    @Test
    fun `short-circuit operators are not printed as ternaries`() {
        // `&&`, `||` and `? :` are all one node. Which one it prints as depends on the arms, and getting
        // that wrong is invisible until the fixed point moves.
        val out = fixedPoint("on start { say(true && false) say(false || true) }")
        assertTrue("&&" in out, "a Select with a false arm should print as &&:\n$out")
        assertTrue("||" in out, "a Select with a true arm should print as ||:\n$out")
    }

    @Test
    fun `a genuine ternary still prints as one`() {
        val out = fixedPoint("""on start { say(true ? "a" : "b") }""")
        assertTrue("?" in out && ":" in out, "a plain Select should stay a ternary:\n$out")
    }

    @Test fun `the and call stays a call`() = check("on start { say(and(true, false)) }")

    @Test fun `arithmetic precedence survives`() = check("on start { say(1 + 2 * 3) say((1 + 2) * 3) }")

    @Test
    fun `parentheses are kept only where they change meaning`() {
        val out = fixedPoint("on start { say((1 + 2) * 3) }")
        assertTrue("(1 + 2) * 3" in out, "necessary parentheses were dropped:\n$out")
        assertTrue("1 + 2 * 3" !in out.replace("(1 + 2) * 3", ""), "meaning changed:\n$out")
    }

    @Test fun `not`() = check("on start { say(!false) }")

    @Test fun `comparison`() = check("on start { say(1 < 2) }")

    @Test fun `indexing`() = check("on start { say([1, 2, 3][0]) }")

    // ---- records ---------------------------------------------------------------------------------------------

    @Test
    fun `a record is made, read and copied`() = check(
        """
        type Spot { n: Int, label: String }
        on start {
            val s = Spot { n: 1, label: "x" }
            val t = s with { n: 2 }
            say(s.n)
            say(t.n)
        }
        """.trimIndent()
    )

    // ---- functions -------------------------------------------------------------------------------------------

    @Test
    fun `an expression-bodied function stays an expression`() {
        // If this printed as a block body it would stop being pure, which changes what the graph MEANS —
        // the loudest possible round-trip failure, and one only this direction can catch.
        val out = fixedPoint("fn double(x: Int) -> Int = x * 2\non start { say(double(21)) }")
        assertTrue("= x * 2" in out, "an expression body became a block:\n$out")
    }

    @Test
    fun `each return keeps its own value through a round trip`() = check(
        """
        fn describe(n: Int) -> (out: String) {
            if n > 1 {
                return "many"
            }
            return "one"
        }
        on start { say(describe(1)) say(describe(3)) }
        """.trimIndent()
    )

    @Test
    fun `a multi-value return survives`() = check(
        """
        fn pair(n: Int) -> (a: Int, b: Int) {
            if n > 0 {
                return 1, 2
            }
            return 3, 4
        }
        on start { val (x, y) = pair(1) say(x) say(y) }
        """.trimIndent()
    )

    @Test
    fun `a block-bodied function stays a block`() = check(
        "fn pick(n: Int) -> (v: Int) { return n + 1 }\non start { say(pick(1)) }"
    )

    // ---- metadata ------------------------------------------------------------------------------------------------

    @Test
    fun `a note survives`() {
        val out = fixedPoint("""on start { @note("careful") say(1) }""")
        assertTrue("@note" in out, "a note was dropped:\n$out")
    }

    @Test
    fun `an unrecognised annotation survives`() {
        val out = fixedPoint("""on start { @author("jw") say(1) }""")
        assertTrue("@author" in out, "unknown metadata was dropped:\n$out")
    }

    @Test
    fun `comments do not survive a graph round trip, and that is the contract`() {
        // The two document formats do not trade comments. A `.vs` keeps its `//` and `/* */`; a canvas
        // document keeps its comment boxes; going through the other one drops them. So the T -> G -> T
        // guarantee is character-identical MODULO COMMENTS, and this is the test that says so out loud.
        val src = """
            on start {
                // gone after a graph round trip
                say(1)
            }
        """.trimIndent()
        val out = Print(catalog).print(Lower(catalog).lower(parse(src)).graph)
        assertTrue("//" !in out, "a comment came back out of the graph:\n${'$'}out")
        // What it DOES guarantee: the program is untouched.
        behaviourSurvives(src)
    }

    @Test
    fun `a comment box on the canvas is not printed`() {
        // Built as a GRAPH, since there is no longer any text that makes one. The printer walks past it.
        val g = Lower(catalog).lower(parse("on start { say(1) }")).graph
        val boxed = g.copy(
            nodes = g.nodes + dev.ziggle.vscript.model.Node(
                9_000, dev.ziggle.vscript.model.BuiltinNodes.COMMENT,
            ).also { it.comment = "Bank trip" },
        )
        val out = Print(catalog).print(boxed)
        assertTrue("Bank trip" !in out, "a canvas-only comment box reached the text:\n${'$'}out")
        assertTrue("comment" !in out, "the removed syntax was emitted:\n${'$'}out")
    }

    @Test
    fun `the graph name survives`() {
        assertTrue("""graph "Miner"""" in fixedPoint("""graph "Miner"${'\n'}on start { say(1) }"""))
    }

    // ---- everything at once ---------------------------------------------------------------------------------------

    @Test
    fun `a program using most of the language is a fixed point`() = check(
        """
        graph "Everything"

        export type Spot { n: Int, label: String }

        export var trips: Int = 0

        export val speed = 600

        export fn twice(x: Int) -> Int = x * 2

        export fn describe(n: Int) -> (out: String) {
            if n > 1 {
                return "many"
            }
            return "one"
        }

        on start {
            val s = Spot { n: 1, label: "here" }
            for (x, i) in [1, 2, 3] {
                if x > 1 && i < 2 {
                    trips = trips + twice(x)
                } else {
                    say(describe(x))
                }
            }
            while trips < 4 {
                trips = trips + 1
            }
            say(s.label)
            say(speed)
        }

        on stop {
            say(trips)
        }
        """.trimIndent()
    )

    @Test
    fun `a variable default that has to run prints back as a default`() {
        // It lives as an `@init` Set at the head of `on start`, so the danger is printing it where it
        // SITS — one declaration would become a bare `var` plus a statement nobody typed, and reading
        // that back would produce a different graph again.
        val printed = fixedPoint(
            """
            graph "probe"

            export val Base = 20

            export var Doubled: INT = Base * 2

            on start {
                say(Doubled)
            }
            """.trimIndent(),
        )
        assertTrue("var Doubled: INT = " in printed, "the default should print on the declaration:\n$printed")
        assertTrue(
            "Doubled = " !in printed,
            "the initialiser must not also print as a statement:\n$printed",
        )
    }

    @Test
    fun `an ordinary assignment to the same variable still prints`() {
        // Only the `@init` Set is folded into the declaration; a real assignment is still a statement.
        val printed = fixedPoint(
            """
            graph "probe"

            export var N: INT = 0

            on start {
                N = 5
                say(N)
            }
            """.trimIndent(),
        )
        assertTrue("N = 5" in printed, "a written assignment is a statement:\n$printed")
    }
}
