package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Function references — phase F's third piece, and the one the ergonomics gate actually needed.
 *
 * **A function value is an index into the linked program.** That single sentence is the whole design: there
 * is no closure, because the thing being named is a top-level function and a top-level function has no
 * environment to capture; and there is no new calling convention, because `Op.CALLV` differs from
 * `Op.CALLG` by where it reads the callee from and in nothing else.
 *
 * The consequence worth testing is the one that is easy to get wrong: `mapped`/`filtered`/`firstWhere` are
 * compiled INLINE, as a loop with a call in it, rather than being hosts. A host is a Kotlin function the VM
 * calls, so a host that called back into the VM would put a second interpreter on the Kotlin stack — and a
 * fiber parked in there could not be parked at all. Emitted inline, the call is an ordinary frame.
 */
class FunctionRefTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(listOf(sayNode))
    private val text = VsText(catalog)

    // ---- harness -------------------------------------------------------------------------------------

    private fun graphOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
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

    private fun run(chunk: Chunk): List<Any?> {
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 8000)
        assertNull(r.fiber.error, "vm error: ${r.fiber.error}")
        assertTrue(r.fiber.isFinished, "did not finish")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun said(src: String): List<Any?> = run(compile(src))

    private fun roundTrip(src: String) {
        assertEquals(src.trim(), text.write(graphOf(src)).trim(), "round trip")
    }

    // ---- the type ------------------------------------------------------------------------------------

    /**
     * `fn(A) -> B` is the one type whose written form is not `NAME<args>`, and it round-trips through the
     * persisted form because there IS no second form: [TypeRef.toString] writes what the parser reads.
     */
    @Test
    fun `a function type parses and prints back`() {
        assertEquals("fn(INT) -> INT", TypeRef.parse("fn(INT) -> INT").toString())
        assertEquals("fn(Tile, INT) -> BOOL", TypeRef.parse("fn(Tile, INT) -> BOOL").toString())
        assertEquals("fn() -> INT", TypeRef.parse("fn() -> INT").toString())
        assertEquals("fn", TypeRef.parse("fn").toString())
        // A parameter that is itself a function: two arrows, and the shape rather than the arrow decides.
        assertEquals("fn(fn(INT) -> INT) -> BOOL", TypeRef.parse("fn(fn(INT) -> INT) -> BOOL").toString())
    }

    /**
     * The `?` after a function type belongs to the RESULT.
     *
     * There is no null function, so reading it the other way could only ever be wrong — and the alternative
     * spelling that would disambiguate is a parenthesis nobody would ever need to type.
     */
    @Test
    fun `a trailing question mark is the result's`() {
        val t = TypeRef.parse("fn(Tile) -> INT?")
        assertEquals("fn(Tile) -> INT?", t.toString())
        assertTrue(t.resultOf!!.optional)
        assertTrue(!t.optional)
    }

    /**
     * A function type nested inside a container.
     *
     * The reason this is worth its own test: the top-level comma split used to track only `<` and `>`, so
     * the `>` of an ARROW decremented the depth and a following comma stopped looking like a separator.
     */
    @Test
    fun `a function type nests inside a list and a map`() {
        assertEquals("LIST<fn(INT) -> STRING>", TypeRef.parse("LIST<fn(INT) -> STRING>").toString())
        val m = TypeRef.parse("MAP<STRING, fn(INT) -> BOOL>")
        assertEquals("MAP<STRING, fn(INT) -> BOOL>", m.toString())
        assertEquals(TypeRef(PinType.STRING), m.keyOf)
        assertTrue(m.valueOf!!.isFunction)
    }

    // ---- references ----------------------------------------------------------------------------------

    /** The whole feature, end to end: a name with no call, passed, called, answered. */
    @Test
    fun `a function is passed by name and called`() {
        assertEquals(
            listOf(2L, 4L, 6L),
            said(
                """
                graph "t"

                export fn double(n: INT) -> INT = n * 2

                on start {
                    for x in mapped(list: [1, 2, 3], using: double) {
                        say(message: x)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `filtered keeps what the function says yes to`() {
        assertEquals(
            listOf(2L, 4L),
            said(
                """
                graph "t"

                export fn even(n: INT) -> BOOL = n % 2 == 0

                on start {
                    for x in filtered(list: [1, 2, 3, 4, 5], keeping: even) {
                        say(message: x)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * `firstWhere` stops at the match, and answers NOTHING when there is none.
     *
     * The count of calls is the half that matters: "first" is a promise about what is not asked, and a
     * version that filtered and then took `[0]` would pass every other assertion here.
     */
    @Test
    fun `firstWhere stops at the first match and is optional`() {
        assertEquals(
            listOf(4L, 1L, -1L, 0L),
            said(
                """
                graph "t"

                export fn big(n: INT) -> BOOL = n > 3

                // Asked about a later element, this reads past the end of a two-item list and stops the
                // script. So the run finishing at all is the proof that it was never asked.
                export fn firstOrBust(n: INT) -> BOOL = [10, 20][n] > 5

                on start {
                    say(message: firstWhere(list: [1, 4, 9], matching: big) ?: -1)
                    say(message: firstWhere(list: [1, 4, 9], matching: tooBig) ?: 1)
                    say(message: firstWhere(list: [], matching: big) ?: -1)
                    say(message: firstWhere(list: [0, 7], matching: firstOrBust) ?: -1)
                }

                export fn tooBig(n: INT) -> BOOL = n > 99
                """.trimIndent(),
            ),
        )
    }

    /** The element type comes out the other side, so what `mapped` produced is a real `LIST<STRING>`. */
    @Test
    fun `the result carries the function's result type`() {
        assertEquals(
            listOf("n=1", "n=2"),
            said(
                """
                graph "t"

                export fn label(n: INT) -> STRING = text("n={v}", v: n)

                on start {
                    for s in mapped(list: [1, 2], using: label) {
                        say(message: s)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** A function value in a variable, and one in a signature — the type is an ordinary type. */
    @Test
    fun `a function value travels through a parameter`() {
        assertEquals(
            listOf(3L, 6L),
            said(
                """
                graph "t"

                export fn triple(n: INT) -> INT = n * 3

                export fn each(xs: LIST<INT>, f: fn(INT) -> INT) -> LIST<INT> = mapped(list: xs, using: f)

                on start {
                    for x in each(xs: [1, 2], f: triple) {
                        say(message: x)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A type variable introduced by a FUNCTION TYPE, and used in the result.
     *
     * `T` came from the receiver and always would have — `markTypeVariables` recurses through type
     * arguments, and a function type's parameters live there. `U` is the new half: nothing introduces it
     * but the parameter's own type, so before phase F it was a record this graph does not declare.
     */
    @Test
    fun `a function type introduces a type variable of its own`() {
        assertEquals(
            listOf("n=1", "n=2"),
            said(
                """
                graph "t"

                export fn label(n: INT) -> STRING = text("n={v}", v: n)

                export fn LIST<T>.through(self, f: fn(T) -> U) -> LIST<U> = mapped(list: self, using: f)

                on start {
                    for s in [1, 2].through(f: label) {
                        say(message: s)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** The same, with no receiver at all — a generic function that is not an extension. */
    @Test
    fun `a generic function with no receiver binds from its parameters`() {
        assertEquals(
            listOf(1L, "a"),
            said(
                """
                graph "t"

                export fn each(xs: LIST<T>, f: fn(T) -> U) -> LIST<U> = mapped(list: xs, using: f)

                export fn same(n: INT) -> INT = n

                export fn name(n: INT) -> STRING = "a"

                on start {
                    say(message: each(xs: [1], f: same)[0])
                    say(message: each(xs: [1], f: name)[0])
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The bound result is a REAL type downstream, not a wildcard that happened to work.
     *
     * A wildcard would have let this wire and the test would have passed for the wrong reason — so the
     * assertion is that a wrong use is REFUSED, which only a bound `LIST<STRING>` can do.
     */
    @Test
    fun `the bound result type is enforced downstream`() {
        val e = errors(
            """
            graph "t"

            export fn name(n: INT) -> STRING = "a"

            export fn each(xs: LIST<T>, f: fn(T) -> U) -> LIST<U> = mapped(list: xs, using: f)

            export fn wants(xs: LIST<INT>) -> INT = _listCount(list: xs)

            on start {
                say(message: wants(xs: each(xs: [1], f: name)))
            }
            """.trimIndent(),
        )
        assertTrue(e.isNotEmpty(), "a LIST<STRING> should not fit a LIST<INT> parameter")
    }

    // ---- what is refused ------------------------------------------------------------------------------

    /**
     * A function with a body of STEPS is an ordinary value.
     *
     * It was refused for a while, first as a purity check on the reference and then as a wire check once
     * the kind rode in the type as `act`. Both are gone: one word, `fn`, for every function. What the
     * refusal was protecting is still true — `filtered` is a PURE node and is re-expanded at every use
     * site, so a step-bodied function passed to one runs its effects once per read of the result — and it
     * is now a thing to know rather than a thing the type system states.
     */
    @Test
    fun `a step-bodied function is an ordinary value`() {
        val e = errors(
            """
            graph "t"

            export fn shout(n: INT) -> BOOL {
                say(message: n)
                return true
            }

            on start {
                say(message: _listCount(list: filtered(list: [1], keeping: shout)))
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), e, "there is one kind of function now")
    }

    /** A function whose parameter does not match the list's element type. */
    @Test
    fun `a function of the wrong shape does not wire`() {
        val e = errors(
            """
            graph "t"

            export fn wordy(s: STRING) -> BOOL = s == "x"

            on start {
                say(message: _listCount(list: filtered(list: [1, 2], keeping: wordy)))
            }
            """.trimIndent(),
        )
        assertTrue(
            e.any { "STRING" in it && "INT" in it },
            "expected the refusal to name both types, got $e",
        )
    }

    /** A local binding still shadows a function of the same name — the ordinary rule, unchanged. */
    @Test
    fun `a local shadows a function of the same name`() {
        assertEquals(
            listOf(7L),
            said(
                """
                graph "t"

                export fn n() -> INT = 1

                on start {
                    val n = 7
                    say(message: n)
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the printer ----------------------------------------------------------------------------------

    /**
     * A reference is spelled as the function's own name, with no call — and the printer raises exactly
     * that back out of the graph. The printer rule is what makes the sugar admissible at all.
     */
    @Test
    fun `references and function types survive the round trip`() {
        roundTrip(
            """
            graph "t"

            export fn double(n: INT) -> INT = n * 2

            export fn twice(xs: LIST<INT>, f: fn(INT) -> INT) -> LIST<INT> = mapped(list: xs, using: f)

            on start {
                say(message: _listCount(list: twice(xs: [1, 2], f: double)))
            }
            """.trimIndent(),
        )
    }

    // ---- how many it takes -------------------------------------------------------------------------

    /**
     * Invoking a function VALUE checks the count, which nothing did.
     *
     * The argument PINS are derived from the signature — see `effectivePinType`'s INVOKE case — so writing
     * too few made fewer pins and writing too many made extra WILDCARD ones. Either way every wire fitted
     * and the call compiled: `rumor.runnable()` on a `fn(HunterRumor)` was silently a call with nothing in
     * it, which then failed at run time somewhere else entirely.
     */
    @Test
    fun `invoking a function value with too few arguments is refused`() {
        val e = errors(
            """
            graph "t"

            type Rumor { n: INT, runnable: fn(Rumor) }

            export fn use(r: Rumor) {
                r.runnable()
            }

            on start {
                say(message: 1)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "takes 1 argument(s) and 0" in it }, "expected an arity refusal, got $e")
    }

    @Test
    fun `invoking a function value with too many arguments is refused`() {
        val e = errors(
            """
            graph "t"

            export fn use(f: fn(INT)) {
                f(1, 2)
            }

            on start {
                say(message: 1)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "takes 1 argument(s) and 2" in it }, "expected an arity refusal, got $e")
    }

    /** A local holding one is checked the same way — the type is the type, wherever it came from. */
    @Test
    fun `a local holding a function is checked too`() {
        val e = errors(
            """
            graph "t"

            export fn bump(n: INT) {
                say(message: n)
            }

            on start {
                val f = bump
                f()
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "takes 1 argument(s) and 0" in it }, "expected an arity refusal, got $e")
    }

    /** ...and an UNCONSTRAINED `fn` has nothing to be wrong about, so it is left alone. */
    @Test
    fun `an unconstrained function type is not arity-checked`() {
        val e = errors(
            """
            graph "t"

            export fn twice(n: INT) -> INT = n * 2

            export fn use(f: fn) -> INT = 0

            on start {
                say(message: use(f: twice))
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), e, "`fn` is 'some function, any shape'")
    }

    // ---- `= call(…)` on a function that hands nothing back --------------------------------------------

    /**
     * `fn f() = call(…)` with no result RUNS the call rather than returning it.
     *
     * It was refused in the PARSER — "computes a value but says no result type" — which fires before
     * anything knows whether the expression yields anything. Invoking a `fn(T)` yields nothing, so there
     * was no value to return and no way to write the one-line form. Leaving the arrow off already means
     * "hands nothing back"; the short body form now agrees with it, exactly as `fun f() = println(…)` does.
     */
    @Test
    fun `an expression body may run a call that hands nothing back`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "t"

                type Rumor { n: INT, runnable: fn(Rumor) }

                fn show(r: Rumor) {
                    say(message: r.n)
                }

                fn Rumor.run(self) = self.runnable(self)

                on start {
                    val r = Rumor { n: 3, runnable: show }
                    r.run()
                }
                """.trimIndent(),
            ),
        )
    }

    /** ...and it comes back spelled the way it was written, not braced. */
    @Test
    fun `a void expression body round-trips as one line`() {
        roundTrip(
            """
            graph "t"

            type Rumor { n: INT, runnable: fn(Rumor) }

            fn Rumor.run(self) = self.runnable(self)

            on start {
                say(message: 1)
            }
            """.trimIndent(),
        )
    }

    /**
     * A body that DOES produce a value still needs a result type, and says so usefully.
     *
     * The general "works something out but nothing is done with it" is right in a block and wrong here:
     * written `= …` the author plainly meant to hand it back, so the fix is a result type.
     */
    @Test
    fun `an expression body producing a value is told to declare one`() {
        val e = errors(
            """
            graph "t"

            fn twice(n: INT) -> INT = n * 2

            fn f() = twice(n: 1)

            on start {
                say(message: 1)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "add '-> Type'" in it }, "expected the result-type fix, got $e")
    }

    /** A non-call expression body was never ambiguous, and keeps the message it had. */
    @Test
    fun `a non-call expression body still says add a result type`() {
        val e = errors(
            """
            graph "t"

            fn f() = 1 + 1

            on start {
                say(message: 1)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "add '-> Type'" in it }, "expected the result-type fix, got $e")
    }
}
