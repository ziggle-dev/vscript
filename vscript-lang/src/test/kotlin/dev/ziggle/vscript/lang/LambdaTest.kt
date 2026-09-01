package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `xs.filter { it > 3 }` — a function written where it is used.
 *
 * **A lambda is an anonymous function and nothing more exotic.** `Lower` synthesises a real function under
 * a name beginning with `@` — which no author can type — and puts an ordinary `value.function` reference on
 * the wire. So the canvas shows a function box and a reference to it, the same two things a named function
 * shows, and everything downstream already knew what to do with both. The printer tells the two apart by
 * the NAME, with nothing stored to say so.
 *
 * **Closures are the part that needed the VM.** A lambda may read the locals of the body it was written in,
 * and those cannot be reached from inside the callee's frame — a frame is one call's registers. So the free
 * names become trailing PARAMETERS of the synthesised function, `Op.CLOSURE` copies their values into the
 * function value when it is built, and `Op.CALLV` writes them into the callee's frame past the arguments
 * the call site supplied. Copied at build time, so a closure is a value: reassigning the local afterwards
 * cannot be observed through it, which is the same choice lists and records already make.
 */
class LambdaTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(listOf(sayNode))
    private val text = VsText(catalog)

    private fun graphOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors.map { it.message }}")
        assertEquals(
            emptyList(),
            Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message },
            "did not validate",
        )
        return low.graph
    }

    private fun errors(src: String): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { it.message }
        val low = Lower(catalog).lower(parsed.program)
        if (low.errors.isNotEmpty()) return low.errors.map { it.message }
        return Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message }
    }

    private fun compile(src: String): Chunk {
        val g = graphOf(src)
        return GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)
    }

    private fun said(src: String): List<Any?> {
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val r = drive(compile(src), hosts, maxTicks = 20000)
        assertNull(r.fiber.error, "vm error: ${r.fiber.error}")
        assertTrue(r.fiber.isFinished, "did not finish")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    /** The wrappers a real script reaches these through — `core/list.vs`, cut down to what is used here. */
    private val LIST = """
        fn LIST<T>.map(self, f: fn(T) -> U) -> LIST<U> = mapped(list: self, using: f)
        fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)
        fn LIST<T>.firstWhere(self, f: fn(T) -> BOOL) -> T? = firstWhere(list: self, matching: f)
    """.trimIndent()

    // ---- it works --------------------------------------------------------------------------------------

    /** The whole point, in the spelling people will write. */
    @Test
    fun `a trailing lambda filters and maps`() {
        assertEquals(
            listOf(2L, 4L, 3L),
            said(
                """
                graph "t"

                $LIST

                on start {
                    val evens = [1, 2, 3, 4].filter { it % 2 == 0 }
                    say(message: _listCount(list: evens))
                    say(message: evens[1])
                    say(message: _listCount(list: [1, 2, 3].map { it * 10 }))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A named parameter instead of `it`, which is the same thing written down. */
    @Test
    fun `a named parameter works the same`() {
        assertEquals(
            listOf(2L),
            said(
                """
                graph "t"

                $LIST

                on start {
                    say(message: _listCount(list: [1, 2, 3, 4].filter { n -> n > 2 }))
                }
                """.trimIndent(),
            ),
        )
    }

    /** Straight onto the verb, with no wrapper in the way — and with other arguments beside it. */
    @Test
    fun `a lambda goes to the last function parameter`() {
        assertEquals(
            listOf(3L, 30L),
            said(
                """
                graph "t"

                on start {
                    val big = firstWhere(list: [1, 3, 5]) { it > 2 } ?: 0
                    say(message: big)
                    say(message: _listFirst(list: mapped(list: [3, 4]) { it * 10 }))
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- closures --------------------------------------------------------------------------------------

    /**
     * **The feature the VM had to grow for.** `range` is a local of `on start`, and the lambda runs in its
     * own frame — so without capture it would read register 1 of somebody else's call.
     */
    @Test
    fun `a lambda captures a local`() {
        assertEquals(
            listOf(2L),
            said(
                """
                graph "t"

                $LIST

                on start {
                    val range = 3
                    say(message: _listCount(list: [1, 2, 5, 9].filter { it < range }))
                }
                """.trimIndent(),
            ),
        )
    }

    /** Several captures, in the order the body first reads them. */
    @Test
    fun `a lambda captures more than one local`() {
        assertEquals(
            listOf(2L),
            said(
                """
                graph "t"

                $LIST

                on start {
                    val low = 2
                    val high = 8
                    say(message: _listCount(list: [1, 3, 5, 9].filter { it > low && it < high }))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **A closure is a VALUE.** The captured values are copied when the function value is built, so
     * reassigning the local afterwards cannot be seen through it.
     *
     * If it captured the variable rather than the value, both lines below would print the same number, and
     * a lambda's meaning would depend on when it happened to be called rather than on where it was written.
     */
    @Test
    fun `a capture is copied, not referenced`() {
        assertEquals(
            listOf(1L, 3L),
            said(
                """
                graph "t"

                $LIST

                on start {
                    var limit = 2
                    val few = [1, 2, 3, 4].filter { it < limit }
                    limit = 4
                    val more = [1, 2, 3, 4].filter { it < limit }
                    say(message: _listCount(list: few))
                    say(message: _listCount(list: more))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A graph variable is NOT captured — there is one cell for the whole run and the body reads it. */
    @Test
    fun `a graph variable is read, not captured`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "t"

                export var Limit: INT = 4

                $LIST

                on start {
                    say(message: _listCount(list: [1, 2, 3, 9].filter { it < Limit }))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A capture that is itself the receiver's element type — the generic path, end to end. */
    @Test
    fun `a capture composes with the receiver's type variable`() {
        assertEquals(
            listOf("bb"),
            said(
                """
                graph "t"

                $LIST

                on start {
                    val want = 2
                    val found = ["a", "bb", "ccc"].firstWhere { _listCount(list: [it]) > 0 && it != "a" }
                    say(message: found ?: "none")
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A lambda inside a FUNCTION, capturing that function's parameter.
     *
     * A parameter is a pin on the box, and the lambda's body runs in its own frame — so this is the same
     * problem as capturing a local, arriving from the other of the two things a name can be bound to.
     */
    @Test
    fun `a lambda captures the enclosing function's parameter`() {
        assertEquals(
            listOf(2L, 1L),
            said(
                """
                graph "t"

                $LIST

                export fn over(xs: LIST<INT>, n: INT) -> LIST<INT> = xs.filter { it > n }

                on start {
                    say(message: _listCount(list: over(xs: [1, 5, 9], n: 3)))
                    say(message: _listCount(list: over(xs: [1, 5, 9], n: 6)))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A lambda inside a lambda, capturing through it.
     *
     * The inner one reads a name that is free in BOTH: it is captured by the outer lambda, becomes a
     * parameter of it, and is captured again from there. Nothing special makes that work — the scope the
     * inner one is lowered in is the outer one's, and its captures are looked up there like any other.
     */
    @Test
    fun `a lambda captures through another lambda`() {
        assertEquals(
            // The inner filter keeps 1 and 2, so it counts 2 — that number is only right if `limit`
            // reached it, two frames down from the `let`. The outer then keeps what 2 is greater than,
            // which is 1 alone.
            listOf(2L, 1L),
            said(
                """
                graph "t"

                $LIST

                on start {
                    val limit = 3
                    say(message: _listCount(list: [1, 2, 9].filter { it < limit }))
                    val kept = [1, 2, 9].filter { outer -> _listCount(list: [1, 2, 9].filter { it < limit }) > outer }
                    say(message: _listCount(list: kept))
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the graph it makes ----------------------------------------------------------------------------

    /** It really is an anonymous function plus a reference — not a fourth kind of node. */
    @Test
    fun `a lambda is a function and a reference`() {
        val g = graphOf(
            """
            graph "t"

            $LIST

            on start {
                val n = 2
                say(message: _listCount(list: [1, 2, 3].filter { it > n }))
            }
            """.trimIndent(),
        )
        val made = g.functions.filter { BuiltinNodes.isAnonymous(it.name) }
        assertEquals(1, made.size, "one lambda, one function")
        // The captured local is a trailing PARAMETER — that is the whole calling convention.
        assertEquals(listOf("it", "n"), made.single().params.map { it.name })
        val ref = g.nodes.single { it.type == BuiltinNodes.FUNCTION_REF }
        assertEquals(listOf("n"), BuiltinNodes.capturesOf(ref))
        assertTrue(g.linkInto(ref.id, "n") != null, "the capture should be wired from the local")
    }

    /** And it survives the persisted form, which is the other boundary a document crosses. */
    @Test
    fun `the lambda survives the persisted form`() {
        val g = graphOf(
            """
            graph "t"

            $LIST

            on start {
                val n = 2
                say(message: _listCount(list: [1, 2, 3].filter { it > n }))
            }
            """.trimIndent(),
        )
        val reread = GraphDoc.fromJson(GraphDoc.toJson(g))
        val ref = reread.nodes.single { it.type == BuiltinNodes.FUNCTION_REF }
        assertEquals(listOf("n"), BuiltinNodes.capturesOf(ref))
        assertEquals(text.write(g), text.write(reread), "and prints the same on both sides")
    }

    // ---- the printer -----------------------------------------------------------------------------------

    /** The rule sugar in this language lives or dies by: it comes back the way it was written. */
    @Test
    fun `a lambda prints back as it was typed`() {
        val src = """
            graph "t"

            export fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)

            on start {
                val n = 2
                val big = [1, 2, 3].filter { it > n }
                val named = [1, 2, 3].filter { v -> v > 1 }
                say(message: _listCount(list: big))
                say(message: _listCount(list: named))
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim(), "round trip")
    }

    /** With other arguments the parens stay, and the lambda still goes last. */
    @Test
    fun `a lambda beside other arguments prints back`() {
        val src = """
            graph "t"

            on start {
                val big = filtered(list: [1, 2, 3]) { it > 1 }
                say(message: _listCount(list: big))
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim(), "round trip")
    }

    /** A reference to a NAMED function is untouched — it is not a lambda and must not print as one. */
    @Test
    fun `a named function reference still prints as its name`() {
        val src = """
            graph "t"

            export fn even(n: INT) -> BOOL = n % 2 == 0

            on start {
                say(message: _listCount(list: filtered(list: [1, 2], keeping: even)))
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim(), "round trip")
    }

    // ---- what is refused -------------------------------------------------------------------------------

    /**
     * A lambda needs a destination to take its parameter types from — and `val f = …` is not one.
     *
     * **The refusal stayed; what it says changed.** It used to read "a lambda may only be written after a
     * call", which was not the rule being enforced (a named argument works too) and named the one spelling
     * a record field cannot use. A destination is what is required, and there are now four kinds — see
     * `LambdaDestinationTest`. This binding has none: nothing says what `it` is.
     */
    @Test
    fun `a lambda on its own is refused with the reason`() {
        val e = errors(
            """
            graph "t"

            on start {
                val f = { it * 2 }
                say(message: 1)
            }
            """.trimIndent(),
        )
        assertTrue(
            e.any { "needs something to say what its parameters are" in it },
            "expected the placement refusal, got $e",
        )
    }

    /** A call with nothing function-shaped to put it in. */
    @Test
    fun `a trailing lambda on a call that takes no function is refused`() {
        val e = errors(
            """
            graph "t"

            on start {
                say(message: 1) { it }
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "takes no function" in it }, "expected the no-pin refusal, got $e")
    }

    /** The wrong number of parameters, which `it` cannot paper over. */
    @Test
    fun `a lambda of the wrong arity is refused`() {
        val e = errors(
            """
            graph "t"

            on start {
                say(message: _listCount(list: filtered(list: [1, 2]) { a, b -> a > b }))
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "argument(s)" in it }, "expected the arity refusal, got $e")
    }

    /**
     * A lambda whose body ACTS is accepted, like any other function.
     *
     * It used to be refused by its type — a lambda with steps in it was an `act(…)` and `keeping:` wanted
     * a pure `fn(…)` — and the care taken then was that the message never quoted `@lambda1`, a name the
     * author did not write. There is one kind of function now, so there is nothing to refuse; what
     * survives from that episode is the rule about the synthesised name, which still must never surface.
     */
    @Test
    fun `a lambda that acts is accepted, and never surfaces its internal name`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)

            export fn shout(n: INT) { say(message: n) }

            on start {
                val kept = [1, 2].filter { shout(n: it) == 0 }
                say(message: _listCount(list: kept))
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), e, "a lambda with steps in it is an ordinary function value")
    }

    /** And it may not be given twice. */
    @Test
    fun `a pin fed by name and by lambda is refused`() {
        val e = errors(
            """
            graph "t"

            export fn even(n: INT) -> BOOL = n % 2 == 0

            on start {
                say(message: _listCount(list: filtered(list: [1, 2], keeping: even) { it > 1 }))
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "given twice" in it }, "expected the double-feed refusal, got $e")
    }

    // ---- a lambda that takes nothing -----------------------------------------------------------------

    /**
     * `{ f() }` into a pin typed `fn() -> …`.
     *
     * **A lambda that names nothing takes its ARITY from the pin**, the same place it already took its
     * parameter TYPES from — Kotlin's rule. Before this a bare lambda was always the implicit `it` and so
     * always arity one, which left `fn() -> BOOL` with no inline spelling at all: every one-line predicate
     * had to be lifted out into a named function beside the call it belonged to.
     */
    @Test
    fun `a lambda may take no parameters at all`() {
        assertEquals(
            listOf(9L),
            said(
                """
                graph "t"

                fn pick(n: INT, make: fn() -> INT) -> INT = make() + n

                on start {
                    say(message: pick(n: 4) { 5 })
                }
                """.trimIndent(),
            ),
        )
    }

    /** And it prints back with NO arrow — there is nothing to write down, so nothing is written. */
    @Test
    fun `a zero-parameter lambda prints back with no arrow`() {
        val out = text.write(
            graphOf(
                """
                graph "t"

                fn pick(n: INT, make: fn() -> INT) -> INT = make() + n

                on start {
                    say(message: pick(n: 4) { 5 })
                }
                """.trimIndent(),
            ),
        )
        assertTrue("{ 5 }" in out, "expected the lambda to print as '{ 5 }':\n$out")
        assertTrue("{ -> " !in out, "a zero-parameter lambda must not print an arrow:\n$out")
    }

    /**
     * `{ -> … }` is refused rather than accepted as a synonym.
     *
     * Two spellings that make one graph is the thing this language does not do: the printer would have to
     * guess which was typed, and it has nothing stored to guess from.
     */
    @Test
    fun `an arrow with nothing before it is refused`() {
        val e = errors(
            """
            graph "t"

            fn pick(n: INT, make: fn() -> INT) -> INT = make() + n

            on start {
                say(message: pick(n: 4) { -> 5 })
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "the '->' is only for naming them" in it }, "expected the arrow refusal, got $e")
    }

    /** The implicit `it` still means one parameter where the pin takes one. */
    @Test
    fun `a one-parameter pin still gets the implicit it`() {
        assertEquals(
            listOf(20L),
            said(
                """
                graph "t"

                fn twice(n: INT, f: fn(INT) -> INT) -> INT = f(n) + f(0)

                on start {
                    say(message: twice(n: 10) { it * 2 })
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- written at the argument, not trailing -------------------------------------------------------

    /**
     * `f(keeping: { it > 3 })` — a lambda in the argument list.
     *
     * The trailing slot is Kotlin's sugar for the LAST function parameter, and it is genuinely all a call
     * with one function parameter needs. It is not enough for the others: a call with two function pins
     * could only ever fill one inline, and a reader of `useAt(at:, action:, done:, tries:, timeoutMs:)`
     * has to know that `done` is the trailing one before the call makes sense.
     */
    @Test
    fun `a lambda may be written as a named argument`() {
        assertEquals(
            listOf(9L),
            said(
                """
                graph "t"

                fn pick(n: INT, make: fn() -> INT) -> INT = make() + n

                on start {
                    say(message: pick(n: 4, make: { 5 }))
                }
                """.trimIndent(),
            ),
        )
    }

    /** And it STAYS there — the printer must not quietly move it to the trailing slot. */
    @Test
    fun `a named-argument lambda prints back where it was written`() {
        val src = """
            graph "t"

            fn pick(n: INT, make: fn() -> INT) -> INT = make() + n

            on start {
                say(message: pick(n: 4, make: { 5 }))
            }
        """.trimIndent()
        val out = text.write(graphOf(src))
        assertTrue("make: { 5 }" in out, "expected the lambda to stay in the argument list:\n$out")
        assertTrue("4) { 5 }" !in out, "it must not be rewritten as a trailing lambda:\n$out")
    }

    /** The trailing form still prints trailing — the two spellings coexist and neither moves. */
    @Test
    fun `a trailing lambda still prints trailing`() {
        val src = """
            graph "t"

            fn pick(n: INT, make: fn() -> INT) -> INT = make() + n

            on start {
                say(message: pick(n: 4) { 5 })
            }
        """.trimIndent()
        assertTrue("4) { 5 }" in text.write(graphOf(src)), "the trailing spelling must survive")
    }

    /** More than one function pin, each filled inline — impossible with the trailing slot alone. */
    @Test
    fun `two lambdas in one call`() {
        assertEquals(
            listOf(11L),
            said(
                """
                graph "t"

                fn both(a: fn() -> INT, b: fn() -> INT) -> INT = a() + b()

                on start {
                    say(message: both(a: { 5 }, b: { 6 }))
                }
                """.trimIndent(),
            ),
        )
    }

}
