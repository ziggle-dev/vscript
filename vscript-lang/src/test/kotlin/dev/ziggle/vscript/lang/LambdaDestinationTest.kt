package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
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
 * A lambda is legal wherever something says what it must be — not only after a call.
 *
 * **The rule was enforced as "after a call" and stated as "it needs a destination".** Those differ, and
 * the difference was every position that knew the answer and was refused anyway: a record field, a
 * declared graph variable, a local with a written type. The error even explained that parameter types come
 * from the destination — which was the argument for allowing it. GAPS 25.
 */
class LambdaDestinationTest {

    private val say = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(say))

    private fun errorsIn(body: String): List<String> {
        val parsed = Parser(Lexer(body).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { "parse: ${it.message}" }
        return Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program)
            .errors.map { it.message }
    }

    private val prelude = """
        graph "probe"

        type Hooks {
            ready: fn(INT) -> BOOL,
        }

        fn apply(n: INT, f: fn(INT) -> BOOL) -> BOOL = f(n)
    """.trimIndent()

    @Test
    fun `a lambda may be a record literal's field`() {
        assertEquals(
            emptyList(),
            errorsIn("$prelude\n\nval H: Hooks = Hooks { ready: { it > 0 } }\n\non start { say(message: 1) }"),
        )
    }

    @Test
    fun `a lambda may initialise a declared graph variable`() {
        assertEquals(
            emptyList(),
            errorsIn("$prelude\n\nvar F: fn(INT) -> BOOL = { it > 0 }\n\non start { say(message: 1) }"),
        )
    }

    @Test
    fun `a lambda may initialise a local with a written type`() {
        assertEquals(
            emptyList(),
            errorsIn("$prelude\n\non start { val f: fn(INT) -> BOOL = { it > 0 }\nsay(message: apply(n: 1, f: f)) }"),
        )
    }

    /** The rule that remains, and now the message says what it actually is. */
    @Test
    fun `a lambda with no destination is still refused`() {
        val errs = errorsIn("$prelude\n\non start { val x = { it > 0 }\nsay(message: 1) }")
        assertTrue(errs.any { it.contains("needs something to say what its parameters are") }, "got $errs")
    }

    // ---- `{ }` — the do-nothing body, GAPS 26 ------------------------------------------------------

    private val voidPrelude = """
        graph "probe"

        type Hooks {
            ready: fn(INT) -> BOOL,
            step: fn(INT),
        }

        fn run2(n: INT, f: fn(INT)) { f(n) }
    """.trimIndent()

    @Test
    fun `an empty lambda is a function that hands nothing back`() {
        assertEquals(emptyList(), errorsIn(voidPrelude + "\n\non start { run2(n: 1) { } }"))
    }

    @Test
    fun `an empty lambda may be a record field of no result`() {
        assertEquals(
            emptyList(),
            errorsIn(
                voidPrelude +
                    "\n\nval H: Hooks = Hooks { ready: { it > 0 }, step: { } }" +
                    "\n\non start { run2(n: 1) { } }",
            ),
        )
    }

    /** ...but not where a value is wanted, and it says which. */
    @Test
    fun `an empty lambda is refused where a result is wanted`() {
        val errs = errorsIn(
            voidPrelude +
                "\n\nval H: Hooks = Hooks { ready: { }, step: { } }" +
                "\n\non start { run2(n: 1) { } }",
        )
        assertTrue(errs.any { "hands back nothing" in it }, "got $errs")
    }

    // ---- a lambda that ACTS -------------------------------------------------------------------------

    /**
     * `xs.each { … }` — the ordinary way to write a side effect over a list, and it used to be refused.
     *
     * A lambda was lowered with no exec chain and declared pure before its body was read, so a step inside
     * one had nowhere to go and was reported as "nothing runs this". `each` takes `fn(T)` and calls it
     * from inside a `for`, on its own chain, precisely so that this could be written.
     */
    @Test
    fun `a lambda that hands nothing back may act`() {
        val src = """
            graph "probe"

            fn LIST<T>.each(self, f: fn(T)) {
                for item in self {
                    f(item)
                }
            }

            on start {
                [1, 2, 3].each { say(message: it) }
            }
        """.trimIndent()
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "${parsed.errors.map { it.message }}")
        val g = Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program)
        assertEquals(emptyList(), g.errors.map { it.message })
        val source = GraphSource.of(emptyList())
        assertEquals(
            emptyList(),
            Validator(catalog, source).validate(g.graph).filter { it.severity == Severity.ERROR }.map { it.message },
        )
        val entry = g.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(g.graph, entry.id)
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 800)
        assertTrue(r.fiber.isFinished, "${r.fiber.state} ${r.fiber.error?.message.orEmpty()}")
        assertEquals(listOf("1", "2", "3"), out.map { it.toString() }, "each item, in order, once")
    }

    /**
     * ...and one that RETURNS a value still may not, which is the half worth keeping.
     *
     * Those are what `mapped`, `filtered` and `firstWhere` take, and those are PURE nodes re-expanded at
     * every read of their result — so a body that acts there runs its effects once per read. §3.6 states
     * the hazard for a named function passed the same way; keeping the lambda form an expression is what
     * stops it being the easy thing to write by accident.
     */
    @Test
    fun `a lambda that hands a value back still may not act`() {
        // Through the VALIDATOR, not `Lower`: an orphaned step whose output is read is a graph-level
        // fact, so it is caught where the graph is judged rather than while it is being built.
        val src = """
            graph "probe"

            fn pull() -> INT {
                say(message: 0)
                return 1
            }

            fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)

            on start {
                say(message: _listCount(list: [1, 2].filter { it < pull() }))
            }
        """.trimIndent()
        val low = Lower(catalog, source = GraphSource.of(emptyList())).lower(Parser(Lexer(src).lex()).parse().program)
        val errs = Validator(catalog, GraphSource.of(emptyList()))
            .validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message }
        assertTrue(errs.any { "nothing runs" in it }, "got $errs")
    }

    // ---- declaration-level slots -------------------------------------------------------------------
    //
    // Five places a FUNCTION can be written into a declaration. All five have always taken a named
    // function; the question each asks of a lambda is whether the value has to be a compile-time constant.

    private fun declErrors(decls: String): List<String> = errorsIn(
        "graph \"probe\"\n\nfn yes(n: INT) -> BOOL = true\n\n" + decls + "\n\non start { say(message: 1) }",
    )

    @Test
    fun `a lambda may be a record field's default`() {
        assertEquals(emptyList(), declErrors("type H { ready: fn(INT) -> BOOL = { it > 0 } }"))
    }

    @Test
    fun `a lambda may be a single field's default`() {
        assertEquals(emptyList(), declErrors("single S { ready: fn(INT) -> BOOL = { it > 0 } }"))
    }

    @Test
    fun `a lambda may be a function's declared result`() {
        assertEquals(emptyList(), declErrors("fn pick() -> fn(INT) -> BOOL = { it > 0 }"))
    }

    /**
     * The three constant-folded slots take one too, by folding to the NAME of a function synthesised for
     * it — which is exactly what the column already held when somebody wrote a name there.
     */
    @Test
    fun `a lambda may be an enum row's value`() {
        assertEquals(emptyList(), declErrors("enum E(f: fn(INT) -> BOOL) { A({ it > 0 }) }"))
    }

    @Test
    fun `a lambda may be an enum column's default`() {
        assertEquals(emptyList(), declErrors("enum E(f: fn(INT) -> BOOL = { it > 0 }) { A() }"))
    }

    @Test
    fun `a lambda may be a parameter's default`() {
        assertEquals(emptyList(), declErrors("fn takes(f: fn(INT) -> BOOL = { it > 0 }) -> BOOL = f(1)"))
    }

    /** And the one that matters: the column is really callable, not a string that looks like a name. */
    @Test
    fun `an enum column lambda actually runs`() {
        val src = """
            graph "probe"

            enum E(f: fn(INT) -> BOOL) {
                A({ it > 0 }),
                B({ it > 99 }),
            }

            fn apply(n: INT, f: fn(INT) -> BOOL) -> BOOL = f(n)

            on start {
                for e in E.values() {
                    say(message: apply(n: 5, f: e.f))
                }
            }
        """.trimIndent()
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "${parsed.errors.map { it.message }}")
        val g = Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program)
        assertEquals(emptyList(), g.errors.map { it.message })
        val source = GraphSource.of(emptyList())
        assertEquals(
            emptyList(),
            Validator(catalog, source).validate(g.graph).filter { it.severity == Severity.ERROR }.map { it.message },
        )
        val entry = g.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(g.graph, entry.id)
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 800)
        assertTrue(r.fiber.isFinished, "${r.fiber.state} ${r.fiber.error?.message.orEmpty()}")
        assertEquals(listOf<Any?>(true, false), out, "5 is over 0 and not over 99")
    }

    /**
     * ...and it comes BACK as a lambda, not as the name nobody typed.
     *
     * The round trip is the load-bearing half: a folded lambda is stored as `@lambda1`, which is not a
     * name a person can write, so a printer that echoed it would produce a file that no longer parses.
     *
     * `takes` is written braced rather than as `= f(1)` because calling a function VALUE needs an exec
     * chain, so the printer gives back the braced form — which is existing behaviour and nothing to do
     * with the defaults this is about.
     */
    @Test
    fun `a folded lambda prints back as a lambda`() {
        val src = """
            graph "probe"

            enum E(f: fn(INT) -> BOOL = { it > 1 }) {
                A({ it > 0 }),
            }

            fn takes(f: fn(INT) -> BOOL = { it > 2 }) -> BOOL {
                return f(1)
            }

            on start {
                say(message: 1)
            }
        """.trimIndent()
        val printed = VsText(catalog).write(graphOf(src)).trim()
        assertTrue("@lambda" !in printed, "a synthesised name leaked into the source:\n$printed")
        assertEquals(src, printed, "round trip")
    }

    private fun graphOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(
            emptyList(),
            Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message },
            "did not validate",
        )
        return low.graph
    }

    /** ...and the field a lambda lands in is really wired: the record's hook runs and answers. */
    @Test
    fun `a record field lambda actually runs`() {
        val src = """
            $prelude

            val H: Hooks = Hooks { ready: { it > 0 } }

            on start {
                say(message: apply(n: 5, f: H.ready))
            }
        """.trimIndent()
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "${parsed.errors.map { it.message }}")
        val g = Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program)
        assertEquals(emptyList(), g.errors.map { it.message })
        val source = GraphSource.of(emptyList())
        assertEquals(
            emptyList(),
            Validator(catalog, source).validate(g.graph).filter { it.severity == Severity.ERROR }.map { it.message },
        )
        val entry = g.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(g.graph, entry.id)
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 400)
        assertTrue(r.fiber.isFinished, "${r.fiber.state} ${r.fiber.error?.message.orEmpty()}")
        assertEquals(listOf<Any?>(true), out)
    }
}
