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
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Text → graph → bytecode → **run**.
 *
 * These assert on behaviour rather than on graph shape, because shape assertions pass for a graph that is
 * wired plausibly and does nothing. Running it on the real VM is the only check that the exec chain actually
 * joins up — and "a node was built but never placed on the chain" is precisely the failure mode this
 * lowering can have, since an impure call is both a value and a step.
 *
 * Every graph is validated first, so a lowering that produces something the canvas would refuse fails here
 * rather than surviving to a run.
 */
class LowerTest {

    /**
     * Deliberately NOT called `test.log`: the builtin `debug.log` already claims the short name, and two
     * nodes claiming one name is exactly what `Names` refuses to resolve. A test that tripped over that
     * would be testing the ambiguity rule by accident rather than the lowering on purpose.
     */
    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** An INT pin and a FLOAT one, so "the wrong kind" and "a legal widening" both have a target. */
    private val waitNode = hostNode(
        "test.wait", "wait", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Ms", PinType.INT)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val sizeNode = hostNode(
        "test.size", "size", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Width", PinType.FLOAT)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** Impure with a result, for `let ok = step()` and the no-Hold normalisation. */
    private val stepNode = hostNode(
        "test.step", "step", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Ok", PinType.BOOL)),
    )

    /** Pure, and it counts its calls — so "was this evaluated once or twice" is answerable. */
    private val counterNode = hostNode(
        "test.counter", "counter", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.INT)),
    )

    private val catalog = NodeCatalog(listOf(sayNode, stepNode, counterNode, waitNode, sizeNode))

    private class Host {
        val said = ArrayList<Any?>()
        var counterCalls = 0
        fun registry(): HostRegistry {
            val r = HostRegistry()
            r.register("say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
            r.register("step", HostKind.INLINE, arity = 0) { true }
            r.register("counter", HostKind.INLINE) { ++counterCalls }
            return r
        }
    }

    /** Source → run. Fails loudly at whichever stage went wrong, so a failure names its own cause. */
    private fun run(src: String, host: Host = Host()): Host {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { it.message }}")
        val lowered = Lower(catalog).lower(parsed.program)
        assertTrue(lowered.ok, "lowering errors: ${lowered.errors.map { it.toString() }}")
        val issues = Validator(catalog).validate(lowered.graph).errors()
        assertTrue(issues.isEmpty(), "the lowered graph does not validate: $issues")
        val entry = lowered.graph.entries(catalog).single()
        drive(GraphCompiler(catalog, debug = false).compile(lowered.graph, entry.id), host.registry(), maxTicks = 200)
        return host
    }

    private fun lower(src: String): Lower.Result {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse errors: ${parsed.errors.map { it.message }}")
        return Lower(catalog).lower(parsed.program)
    }

    // ---- assignment names something assignable ---------------------------------------------------------

    /**
     * Reassigning a `let` says what is actually wrong.
     *
     * The generic "no graph variable named 'guess'" sent people to declare a variable they plainly had two
     * lines up, and the cure it named was wrong twice over: a graph variable is shared by every call, so
     * using one as a loop accumulator inside a function breaks recursion and re-entrancy silently.
     */
    @Test
    fun `reassigning a val says it is a binding, not that it is missing`() {
        val result = lower(
            """
            graph "probe"

            export fn newton(x: FLOAT) -> FLOAT {
                val guess = x / 2.0
                guess = (guess + x / guess) / 2.0
                return guess
            }
            """.trimIndent(),
        )

        val message = result.errors.single().message
        assertTrue(message.contains("'guess' is a `val`"), message)
        assertTrue(message.contains("names a value once"), message)
        // The cure it names is the LOCAL one. Sending the reader to a graph variable would be wrong twice
        // over: it is shared by every call, so it breaks recursion and re-entrancy silently.
        assertTrue(message.contains("var guess"), message)
        assertFalse(message.startsWith("no graph variable named"), message)
    }

    @Test
    fun `a genuinely unknown name still says so`() {
        val result = lower(
            """
            graph "probe"

            on start {
                nowhere = 1
            }
            """.trimIndent(),
        )

        val message = result.errors.single().message
        assertTrue(message.startsWith("no graph variable named 'nowhere'"), message)
    }

    // ---- the basics ------------------------------------------------------------------------------------

    @Test
    fun `a call runs`() {
        assertEquals(listOf<Any?>("hi"), run("""on start { say("hi") }""").said)
    }

    @Test
    fun `statements run in order`() {
        assertEquals(listOf<Any?>(1, 2, 3), run("on start { say(1) say(2) say(3) }").said)
    }

    @Test
    fun `a literal argument is typed into the pin, not made a node`() {
        // Inline is the default. A constant node is what `const` is for, and the difference is real: a
        // literal is keyed "nodeId/pin" for live editing, so one node feeding three pins is one knob.
        val g = lower("""on start { say("hi") }""").graph
        assertTrue(g.nodes.none { it.type in BuiltinNodes.LITERALS }, "a literal node was made for an inline value")
        assertEquals("hi", g.nodes.first { it.type == "test.say" }.literals["Message"])
    }

    @Test
    fun `const makes one shared node`() {
        val g = lower("val n = 7\non start { say(n) say(n) }").graph
        assertEquals(1, g.nodes.count { it.type == BuiltinNodes.LITERAL_INT }, "val should be one node")
        val lit = g.nodes.first { it.type == BuiltinNodes.LITERAL_INT }
        assertEquals(2, g.links.count { it.fromNode == lit.id }, "both readers should share the one node")
    }

    // ---- control flow ----------------------------------------------------------------------------------

    @Test
    fun `if takes the true arm`() {
        assertEquals(listOf<Any?>("yes"), run("""on start { if true { say("yes") } else { say("no") } }""").said)
    }

    @Test
    fun `if takes the false arm`() {
        assertEquals(listOf<Any?>("no"), run("""on start { if false { say("yes") } else { say("no") } }""").said)
    }

    @Test
    fun `both arms converge on what follows`() {
        // The reason the open end of a chain is a SET: with one pin, the second arm is silently dropped and
        // half the program stops running.
        assertEquals(listOf<Any?>("yes", "after"), run("""on start { if true { say("yes") } else { say("no") } say("after") }""").said)
        assertEquals(listOf<Any?>("no", "after"), run("""on start { if false { say("yes") } else { say("no") } say("after") }""").said)
    }

    @Test
    fun `an if with no else still continues`() {
        assertEquals(listOf<Any?>("after"), run("""on start { if false { say("x") } say("after") }""").said)
    }

    @Test
    fun `else if chains`() {
        val src = """on start { if false { say(1) } else if true { say(2) } else { say(3) } }"""
        assertEquals(listOf<Any?>(2), run(src).said)
    }

    @Test
    fun `while repeats until the condition fails`() {
        val src = """
            var n: Int = 0
            on start {
                while n < 3 {
                    say(n)
                    n = n + 1
                }
                say("done")
            }
        """.trimIndent()
        assertEquals(listOf<Any?>(0, 1, 2, "done"), run(src).said)
    }

    @Test
    fun `for each walks a written-out list`() {
        assertEquals(listOf<Any?>(10, 20, 30), run("on start { for x in [10, 20, 30] { say(x) } }").said)
    }

    @Test
    fun `for each can bind the index too`() {
        assertEquals(listOf<Any?>(0, 1, 2), run("on start { for (x, i) in [10, 20, 30] { say(i) } }").said)
    }

    @Test
    fun `sequence runs its arms in order`() {
        assertEquals(listOf<Any?>(1, 2), run("on start { sequence { say(1) } { say(2) } }").said)
    }

    // ---- let, and the reason Hold exists ----------------------------------------------------------------

    @Test
    fun `let evaluates once however many read it`() {
        // Without a Hold this is two calls and the two readers disagree — which is what `let` must not mean.
        val host = run("on start { val n = counter() say(n) say(n) }")
        assertEquals(1, host.counterCalls)
        assertEquals(listOf<Any?>(1, 1), host.said)
    }

    @Test
    fun `let builds a hold node`() {
        val g = lower("on start { val n = counter() say(n) }").graph
        val hold = g.nodes.single { it.type == BuiltinNodes.HOLD }
        assertEquals("n", hold.literals[BuiltinNodes.HOLD_NAME])
    }

    @Test
    fun `a binding named after the pin it already has needs no hold`() {
        // The normalisation that keeps hand-wired graphs round-tripping structurally: an impure call with
        // one output already owns a stable register, so a Hold under the same name adds nothing.
        val g = lower("on start { val ok = step() say(ok) }").graph
        assertTrue(g.nodes.none { it.type == BuiltinNodes.HOLD }, "no Hold was needed here")
    }

    @Test
    fun `a renamed binding does get a hold`() {
        val g = lower("on start { val fine = step() say(fine) }").graph
        assertEquals(1, g.nodes.count { it.type == BuiltinNodes.HOLD })
    }

    // ---- variables ---------------------------------------------------------------------------------------

    @Test
    fun `a variable keeps its value across statements`() {
        val src = """
            var n: Int = 5
            on start { say(n) n = n + 1 say(n) }
        """.trimIndent()
        assertEquals(listOf<Any?>(5, 6), run(src).said)
    }

    @Test
    fun `assigning something undeclared is an error`() {
        val r = lower("on start { nope = 1 }")
        assertTrue(r.errors.any { "no graph variable named 'nope'" in it.message }, "${r.errors}")
    }

    // ---- operators ---------------------------------------------------------------------------------------

    @Test
    fun `arithmetic and comparison`() {
        assertEquals(listOf<Any?>(7), run("on start { say(3 + 2 * 2) }").said)
        assertEquals(listOf<Any?>(true), run("on start { say(3 < 4) }").said)
    }

    @Test
    fun `not inverts`() {
        assertEquals(listOf<Any?>(true), run("on start { say(!false) }").said)
    }

    @Test
    fun `short-circuit and lowers to a select`() {
        val g = lower("on start { say(true && false) }").graph
        assertTrue(g.nodes.any { it.type == BuiltinNodes.SELECT }, "&& should lower to a Select")
        assertTrue(g.nodes.none { it.type == BuiltinNodes.AND }, "&& is not logic.and")
        assertEquals(listOf<Any?>(false), run("on start { say(true && false) }").said)
        assertEquals(listOf<Any?>(true), run("on start { say(false || true) }").said)
    }

    @Test
    fun `the and call is the non-short-circuiting node`() {
        val g = lower("on start { say(and(true, false)) }").graph
        assertTrue(g.nodes.any { it.type == BuiltinNodes.AND })
        assertEquals(listOf<Any?>(false), run("on start { say(and(true, false)) }").said)
    }

    @Test
    fun `a ternary picks an arm`() {
        assertEquals(listOf<Any?>("a"), run("""on start { say(true ? "a" : "b") }""").said)
    }

    // ---- functions ----------------------------------------------------------------------------------------

    @Test
    fun `an expression-bodied function is pure and is called`() {
        val src = """
            fn double(x: Int) -> Int = x * 2
            on start { say(double(21)) }
        """.trimIndent()
        assertEquals(listOf<Any?>(42), run(src).said)
    }

    @Test
    fun `an expression body leaves the box exec unwired, which is what makes it pure`() {
        val g = lower("fn double(x: Int) -> Int = x * 2\non start { say(double(1)) }").graph
        val box = g.nodes.single { it.type == BuiltinNodes.FUNCTION }
        assertTrue(
            g.links.none { (it.fromNode == box.id || it.toNode == box.id) && (it.fromPin == "Exec" || it.toPin == "Exec") },
            "an expression body must not wire the box's exec pins",
        )
    }

    @Test
    fun `a block-bodied function runs and returns`() {
        val src = """
            fn pick(n: Int) -> (v: Int) { return n + 1 }
            on start { say(pick(41)) }
        """.trimIndent()
        assertEquals(listOf<Any?>(42), run(src).said)
    }

    @Test
    fun `each return hands back its own value`() {
        // The case that was silently wrong: both returns are literals, and wiring them to the box wrote
        // each into the same pin so the second overwrote the first — every path returned "one".
        val src = """
            fn describe(n: Int) -> (out: String) {
                if n > 1 { return "many" }
                return "one"
            }
            on start { say(describe(1)) say(describe(3)) }
        """.trimIndent()
        assertEquals(listOf<Any?>("one", "many"), run(src).said)
    }

    @Test
    fun `a return can hand back several values`() {
        val src = """
            fn pair(n: Int) -> (a: Int, b: Int) {
                if n > 0 { return 1, 2 }
                return 3, 4
            }
            on start {
                val (x, y) = pair(1)
                say(x)
                say(y)
                val (p, q) = pair(0)
                say(p)
                say(q)
            }
        """.trimIndent()
        assertEquals(listOf<Any?>(1, 2, 3, 4), run(src).said)
    }

    @Test
    fun `a body that just ends still returns what its results are fed`() {
        // The implicit return: control reaching the box IS the return, and that path is unchanged.
        val src = """
            fn twice(n: Int) -> (v: Int) { }
            on start { say(twice(1)) }
        """.trimIndent()
        assertTrue(lower(src).ok, "an implicit return should still lower")
    }

    @Test
    fun `a function body's nodes belong to it`() {
        val g = lower("fn f() -> Int = 1\non start { say(f()) }").graph
        assertTrue(g.nodes.any { it.function == "f" }, "the body should name its function")
        assertTrue(g.nodes.any { it.function == null }, "the entry should not")
    }

    // ---- records --------------------------------------------------------------------------------------------

    @Test
    fun `a record is built and read`() {
        val src = """
            type Spot { n: Int, label: String }
            on start {
                val s = Spot { n: 3, label: "here" }
                say(s.n)
                say(s.label)
            }
        """.trimIndent()
        assertEquals(listOf<Any?>(3, "here"), run(src).said)
    }

    @Test
    fun `with replaces one field and leaves the original alone`() {
        val src = """
            type Spot { n: Int, label: String }
            on start {
                val a = Spot { n: 1, label: "x" }
                val b = a with { n: 2 }
                say(a.n)
                say(b.n)
            }
        """.trimIndent()
        assertEquals(listOf<Any?>(1, 2), run(src).said)
    }

    @Test
    fun `a record can be taken apart by field name`() {
        val src = """
            type Spot { n: Int, label: String }
            on start {
                val s = Spot { n: 9, label: "z" }
                val {n, label} = s
                say(n)
                say(label)
            }
        """.trimIndent()
        assertEquals(listOf<Any?>(9, "z"), run(src).said)
    }

    // ---- annotations and spans --------------------------------------------------------------------------------

    @Test
    fun `a pinned id is used and not handed out twice`() {
        val g = lower("on start { @id(50) say(1) say(2) }").graph
        assertTrue(g.nodes.any { it.id == 50 && it.type == "test.say" })
        assertEquals(g.nodes.size, g.nodes.map { it.id }.distinct().size, "ids must be unique")
    }

    @Test
    fun `a position is not something the text carries`() {
        // Positions are a canvas detail. The importer arranges headlessly, so a lowered node's coordinates
        // come from AutoLayout rather than from anything written — which is the whole trade of this branch:
        // exact in what runs, lossy in how it is drawn.
        val n = lower("on start { say(1) }").graph.nodes.first { it.type == "test.say" }
        assertTrue(n.x != 0f || n.y != 0f, "the importer should have arranged it")
    }

    @Test
    fun `an unrecognised annotation reaches the node`() {
        val n = lower("""on start { @author("jw") say(1) }""").graph.nodes.first { it.type == "test.say" }
        assertEquals("jw", n.literals["@author"])
    }

    @Test
    fun `a note is metadata on the node`() {
        val n = lower("""on start { @note("careful") say(1) }""").graph.nodes.first { it.type == "test.say" }
        assertEquals("careful", n.literals["@note"])
    }

    @Test
    fun `every node knows where it came from`() {
        val r = lower("on start {\n    say(1)\n}")
        val log = r.graph.nodes.first { it.type == "test.say" }
        assertEquals(2, r.spans[log.id]?.line, "the node should point at the line that wrote it")
    }

    @Test
    fun `comments lower to nothing at all`() {
        // A comment belongs to the source document. It is not a node, not a group, not metadata — the graph
        // this produces is indistinguishable from the one written without them.
        val withComments = lower(
            """
            // heading
            on start {
                /* why */ say(1)
            }
            """.trimIndent()
        ).graph
        val without = lower("on start { say(1) }").graph
        assertEquals(without.nodes.size, withComments.nodes.size)
        assertTrue(withComments.nodes.none { it.type == BuiltinNodes.COMMENT })
        assertTrue(withComments.nodes.all { it.group == null })
    }


    // ---- diagnostics -------------------------------------------------------------------------------------------

    @Test
    fun `an unknown call is reported, not thrown`() {
        val r = lower("on start { noSuchThing() }")
        assertTrue(r.errors.any { "nothing here is called 'noSuchThing'" in it.message }, "${r.errors}")
    }

    @Test
    fun `a pure call as a statement is refused`() {
        // It would compute something and throw it away — never joining the chain, so never running.
        val r = lower("on start { counter() }")
        assertTrue(r.errors.any { "nothing is done with it" in it.message }, "${r.errors}")
    }

    @Test
    fun `every lowered graph validates`() {
        // The gate that matters: whatever this produces has to be something the canvas would accept.
        val src = """
            type Spot { n: Int }
            var trips: Int = 0
            fn twice(x: Int) -> Int = x * 2
            on start {
                val s = Spot { n: 1 }
                for (x, i) in [1, 2, 3] {
                    if x > 1 { trips = trips + twice(x) } else { say(i) }
                }
                while trips < 2 { trips = trips + 1 }
                say(s.n)
            }
            on stop { say(trips) }
        """.trimIndent()
        val r = lower(src)
        assertTrue(r.ok, "lowering errors: ${r.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(r.graph).errors(), "did not validate")
    }

    // ---- typed-in values -----------------------------------------------------------------------------

    @Test
    fun `a literal of the wrong kind is refused rather than quietly coerced`() {
        // Wiring the wrong type was always refused by `canConnect`; typing it in was not, because a
        // literal goes straight into Node.literals and never passes a link. So this compiled clean and
        // went wrong at run time, where a host casting the value is a long way from the line that caused
        // it. `Literals.of` returning null for an unparseable field — and null meaning "keep the default"
        // — is the same failure the SKILL comment in Literals.kt records having been bitten by once.
        val delayMs = lower(
            """
            graph "probe"
            on start { wait(ms: "soon") }
            """.trimIndent(),
        )
        assertTrue(
            delayMs.errors.any { "cannot put a String into INT" in it.message },
            "a string typed into an INT pin has to be reported: ${delayMs.errors.map { it.message }}",
        )
    }

    @Test
    fun `the kinds a pin does hold are still accepted`() {
        // The check has to know a Float pin takes an Int — the same widening `canConnect` allows — or it
        // would reject arithmetic that has always been legal.
        val ok = lower(
            """
            graph "probe"
            on start { wait(ms: 600) size(width: 3) }
            """.trimIndent(),
        )
        assertEquals(emptyList(), ok.errors.map { it.message })
    }

    @Test
    fun `a function that returns explicitly is not warned about an unfed box`() {
        // `return` hands its values back through a RETURN node, not through the box — deliberately, so
        // each return can hand back its own. The box is then legitimately unfed, and the signature check
        // was warning "'f' returns: 'Result' has nothing feeding it" about every function written with an
        // explicit return. That is most of them, so the warning trained people to ignore warnings.
        val lowered = lower(
            """
            graph "probe"
            export fn twice(n: INT) -> INT {
                return n * 2
            }
            on start { wait(ms: twice(2)) }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(lowered.graph)
        assertTrue(
            issues.none { "has nothing feeding it" in it.message },
            "a returning function must not be warned about: ${issues.map { it.message }}",
        )
    }

    @Test
    fun `a function that never returns anything is still warned about`() {
        // The case the warning is actually for: a result declared and nothing ever produced for it.
        val lowered = lower(
            """
            graph "probe"
            export fn nothing(n: INT) -> INT {
                wait(ms: n)
            }
            on start { wait(ms: nothing(2)) }
            """.trimIndent(),
        )
        val issues = Validator(catalog).validate(lowered.graph)
        assertTrue(
            issues.any { "has nothing feeding it" in it.message },
            "an unfed result is exactly what this warning is for",
        )
    }

    // ---- variable defaults that have to run -----------------------------------------------------------

    @Test
    fun `a variable default may be an expression, and it runs before the body`() {
        // The declaration has to be true by the time the first written statement runs, or it is a lie
        // about what the variable holds.
        val host = run(
            """
            graph "probe"
            export val Base = 20
            export var Doubled: INT = Base * 2
            on start { say(Doubled) }
            """.trimIndent(),
        )
        assertEquals(listOf<Any?>(40), host.said)
    }

    @Test
    fun `initialisers run in declaration order`() {
        val host = run(
            """
            graph "probe"
            export val Base = 3
            export var First: INT = Base
            export var Second: INT = 100
            on start { say(First) say(Second) }
            """.trimIndent(),
        )
        assertEquals(listOf<Any?>(3, 100), host.said)
    }

    @Test
    fun `a literal default is still stored on the declaration, not turned into a node`() {
        // The canvas reads GraphVariable.default, so the ordinary case must not start needing a prologue.
        val lowered = lower(
            """
            graph "probe"
            export var N: INT = 7
            on start { say(N) }
            """.trimIndent(),
        )
        assertEquals(7, lowered.graph.variables.single { it.name == "N" }.default)
        assertTrue(
            lowered.graph.nodes.none { it.literals.containsKey(Lower.INIT_MARK) },
            "a literal default needs nothing to run",
        )
    }

    @Test
    fun `an initialised variable with no on start still gets one`() {
        // Otherwise the prologue would simply never happen, and the declaration would silently not hold.
        val lowered = lower(
            """
            graph "probe"
            export val Base = 5
            export var N: INT = Base
            """.trimIndent(),
        )
        assertTrue(
            lowered.graph.nodes.any { it.type == BuiltinNodes.ENTRY },
            "an entry has to be synthesised for the prologue to run in",
        )
    }
}
