package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
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
 * `fn(T)` and `invoke(f, x)` — calling a function value that ACTS.
 *
 * **The restriction this lifts was never about the value.** A step-bodied function could not be passed at
 * all, and the reason given was that "a function value is called from inside an expression". That was a
 * fact about the only three things able to call one — `mapped`, `filtered`, `firstWhere` — which are PURE
 * nodes, re-expanded at every use site, so an action reaching one would run its effects a number of times
 * nothing in the source shows. The VM never cared: `Op.CALLG` and `Op.CALLV` are one branch in the
 * interpreter, and `CALLG` calls step-bodied functions constantly.
 *
 * So the kind moved into the TYPE. A function whose body has steps is an `fn(…)`, derived from the same
 * predicate a Call's exec pins are derived from, and the refusal happens at the WIRE, where the
 * destination is known — one line in `canConnect`. What was missing then was somewhere to put the call,
 * which is [dev.ziggle.vscript.model.BuiltinNodes.INVOKE]: a STEP, with exec pins, compiling to `CALLV`.
 * No opcode and no VM change.
 */
class ActionValueTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** Blocking, so the render-entry check has something real to refuse. */
    private val waitNode = hostNode(
        "test.wait", "wait", NodeKind.IMPURE, hostKind = HostKind.BLOCKING,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(listOf(sayNode, waitNode))
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
        hosts.register("wait", HostKind.BLOCKING, arity = 0) { _ -> null }
        val r = drive(compile(src), hosts, maxTicks = 20000)
        assertNull(r.fiber.error, "vm error: ${r.fiber.error}")
        assertTrue(r.fiber.isFinished, "did not finish")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    // ---- it works --------------------------------------------------------------------------------------

    /** The whole feature: a step-bodied function, passed as a value, and run. */
    @Test
    fun `a step-bodied function is a value and can be invoked`() {
        assertEquals(
            listOf("hello"),
            said(
                """
                graph "t"

                export fn greet(who: STRING) {
                    say(message: who)
                }

                on start {
                    val f = greet
                    invoke(f, "hello")
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **The shape `entry.vs` needs.** A table of handlers, one picked at run time, then invoked.
     *
     * This is what nine arms of `when target { … -> someModule::run(rumor: r) }` collapses to, and it is
     * what function references alone could not do: `run` acts, so it could not be a value at all.
     */
    @Test
    fun `a table of actions dispatches`() {
        assertEquals(
            listOf("pit", "net"),
            said(
                """
                graph "t"

                export fn pit(n: INT) { say(message: "pit") }
                export fn net(n: INT) { say(message: "net") }

                export fn handlerFor(kind: INT) -> fn(INT) = kind == 0 ? pit : net

                on start {
                    invoke(handlerFor(kind: 0), 1)
                    invoke(handlerFor(kind: 1), 1)
                }
                """.trimIndent(),
            ),
        )
    }

    /** An action may BLOCK, which is the thing a pure caller could never allow. */
    @Test
    fun `an invoked action may wait`() {
        assertEquals(
            listOf("done"),
            said(
                """
                graph "t"

                export fn slow() {
                    wait()
                    say(message: "done")
                }

                on start {
                    invoke(slow)
                }
                """.trimIndent(),
            ),
        )
    }

    /** It hands a value back when it has one, and the result is typed from the function. */
    @Test
    fun `an action that returns a value is read through Result`() {
        assertEquals(
            listOf(7L),
            said(
                """
                graph "t"

                export fn twice(n: INT) -> INT {
                    say(message: n + 4)
                    return n
                }

                on start {
                    invoke(twice, 3)
                }
                """.trimIndent(),
            ),
        )
    }

    /** A closure works through Invoke exactly as it does through the list verbs. */
    @Test
    fun `an invoked lambda still closes over its locals`() {
        assertEquals(
            listOf(5L),
            said(
                """
                graph "t"

                export fn apply(f: fn(INT) -> INT, n: INT) -> INT = mapped(list: [n], using: f)[0]

                on start {
                    val bump = 2
                    say(message: apply(n: 3) { it + bump })
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **The shape the hunter corpus wanted all along**: the handler is a COLUMN of the enum.
     *
     * `entry.vs` dispatches nine targets to five methods with a nine-arm `when`. With a handler column
     * that whole thing is `invoke(t.run, …)` — the table says which, and adding a target is a row.
     *
     * The value stored in the document is the function's NAME, because the index it occupies belongs to
     * the linked program and no program exists while a declaration is being read. `GraphCompiler` resolves
     * it on the way into the constant pool, at the same point a TILE's string becomes a record.
     */
    @Test
    fun `an enum row carries a handler, and dispatch is one call`() {
        assertEquals(
            listOf("pit", "net", "pit"),
            said(
                """
                graph "t"

                export fn pit(n: INT) {
                    say(message: "pit")
                }

                export fn net(n: INT) {
                    say(message: "net")
                }

                export enum Method(run: fn(INT)) {
                    PitTrap(pit),
                    NetTrap(net),
                }

                on start {
                    invoke(Method.PitTrap.run, 1)
                    invoke(Method.NetTrap.run, 1)
                    val m = Method.PitTrap
                    invoke(m.run, 1)
                }
                """.trimIndent(),
            ),
        )
    }

    /** And the table prints back as the names that were written, not as quoted strings. */
    @Test
    fun `a handler column round-trips`() {
        val src = """
            graph "t"

            export enum Method(run: fn(INT)) {
                PitTrap(pit),
            }

            export fn pit(n: INT) {
                say(message: n)
            }

            on start {
                invoke(Method.PitTrap.run, 1)
            }
        """.trimIndent()
        val g = graphOf(src)
        assertEquals(src, text.write(g).trim(), "round trip")
        assertEquals(text.write(g), text.write(GraphDoc.fromJson(GraphDoc.toJson(g))), "and through JSON")
    }

    /**
     * `f(x)` — dispatch written the way every other call is written.
     *
     * The check is on the binding's TYPE, which is what makes this safe to try before any name resolution:
     * a local holding an INT leaves `f(…)` meaning the function called `f`, so no existing script changes
     * meaning. It builds the same node `invoke(f, x)` builds.
     */
    @Test
    fun `a function value is callable by name`() {
        assertEquals(
            listOf("pit", "net", 9L),
            said(
                """
                graph "t"

                export fn pit(n: INT) {
                    say(message: "pit")
                }

                export fn net(n: INT) {
                    say(message: "net")
                }

                export fn triple(n: INT) -> INT = n * 3

                on start {
                    val f = pit
                    f(1)
                    val g = net
                    g(2)
                    val h = triple
                    say(message: h(3))
                }
                """.trimIndent(),
            ),
        )
    }

    /** A graph variable holds one too, and an enum column is reached through a `let`. */
    @Test
    fun `a handler from a table is callable by name`() {
        assertEquals(
            listOf("loud"),
            said(
                """
                graph "t"

                export enum Method(run: fn(INT)) {
                    Loud(shout),
                }

                export fn shout(n: INT) {
                    say(message: "loud")
                }

                on start {
                    val handler = Method.Loud.run
                    handler(1)
                }
                """.trimIndent(),
            ),
        )
    }

    /** Both spellings make ONE graph, so only the marker can tell them apart — and each prints as typed. */
    @Test
    fun `each dispatch spelling prints back as it was typed`() {
        val src = """
            graph "t"

            export fn pit(n: INT) {
                say(message: n)
            }

            export fn handlerFor(k: INT) -> fn(INT) = pit

            on start {
                val f = pit
                f(1)
                invoke(f, 2)
                invoke(handlerFor(k: 0), 3)
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim(), "round trip")
    }

    /** A name holding something that is not a function still means the function of that name. */
    @Test
    fun `a non-function binding does not shadow a call`() {
        assertEquals(
            listOf(12L),
            said(
                """
                graph "t"

                export fn size(n: INT) -> INT = n * 4

                on start {
                    val size = 3
                    say(message: size(size))
                }
                """.trimIndent(),
            ),
        )
    }

    /** Its arguments are positional, and a label is refused by name rather than silently mismatched. */
    @Test
    fun `a named argument to a value call is refused`() {
        val e = errors(
            """
            graph "t"

            export fn pit(n: INT) {
                say(message: n)
            }

            on start {
                val f = pit
                f(n: 1)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "positional" in it }, "expected the positional refusal, got $e")
    }

    // ---- what the type refuses -------------------------------------------------------------------------

    /**
     * **The rule `act` exists for.** An action may not reach a pin that wants a pure function.
     *
     * `filtered` is a PURE node, re-expanded at every use site — so an action there would run its effects
     * once per read of the result. Before this, the refusal was a special case in the validator keyed on
     * the reference; now it is `canConnect`, so every pin in the catalogue inherits it.
     */
    @Test
    fun `a step-bodied function may now be passed where any function is wanted`() {
        val e = errors(
            """
            graph "t"

            export fn shout(n: INT) -> BOOL {
                say(message: n)
                return true
            }

            on start {
                say(message: _listCount(list: filtered(list: [1, 2], keeping: shout)))
            }
            """.trimIndent(),
        )
        // **This used to be refused, and the refusal was deliberately removed.** A function's body being
        // a step was part of its TYPE — `act(INT) -> BOOL` — and `keeping:` wanted a pure one, because
        // `filtered` is a PURE node, re-expanded at every use site, so an acting function reaching it runs
        // its effects once per read of the result.
        //
        // The hazard is unchanged; what changed is the price, which was two spellings of "function" in
        // every signature that carried one. There is one word now, and this is the thing to know rather
        // than the thing the wire refuses.
        assertEquals(emptyList(), e, "one `fn` means one function: a step-bodied one is an ordinary value")
    }

    /** And a lambda that acts is refused the same way, by the same rule. */
    @Test
    fun `a lambda that acts cannot be passed to a pure verb`() {
        val e = errors(
            """
            graph "t"

            export fn shout(n: INT) -> BOOL {
                say(message: n)
                return true
            }

            on start {
                say(message: _listCount(list: filtered(list: [1, 2]) { shout(n: it) }))
            }
            """.trimIndent(),
        )
        assertTrue(e.isNotEmpty(), "an acting lambda must not reach a pure function pin, got $e")
    }

    /** The other direction is fine: a pure function called as a statement simply computes. */
    @Test
    fun `a pure function may be invoked`() {
        assertEquals(
            listOf(6L),
            said(
                """
                graph "t"

                export fn double(n: INT) -> INT = n * 2

                on start {
                    say(message: double(n: 3))
                    invoke(double, 3)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **The gap this design closed on the way past.** A blocking call reached through a function VALUE.
     *
     * `checkCannotWait` walks exec chains from a render entry and follows a Call's callee — it did not
     * follow a wire into the function a value names. So an action that waits, invoked inside `on render`,
     * went past the one check that exists to refuse it and would have parked a frame pass.
     */
    @Test
    fun `a blocking action invoked from a render entry is refused`() {
        val e = errors(
            """
            graph "t"

            export fn slow() {
                wait()
            }

            on render {
                invoke(slow)
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "wait" in it.lowercase() }, "expected the render refusal, got $e")
    }

    // ---- the type, and the document --------------------------------------------------------------------

    /** `fn(T)` is a written type: it parses, it prints, and it survives being saved. */
    @Test
    fun `the action type round-trips`() {
        val src = """
            graph "t"

            export fn pit(n: INT) {
                say(message: n)
            }

            export fn handlerFor(kind: INT) -> fn(INT) = pit

            on start {
                invoke(handlerFor(kind: 0), 1)
            }
        """.trimIndent()
        val g = graphOf(src)
        assertEquals(src, text.write(g).trim(), "round trip")
        assertEquals(text.write(g), text.write(GraphDoc.fromJson(GraphDoc.toJson(g))), "and through JSON")
    }

    /** A void action's type writes no arrow, because there is nothing on the other side of one. */
    @Test
    fun `a void action prints without an arrow`() {
        val src = """
            graph "t"

            export fn shout() {
                say(message: 1)
            }

            export fn pick() -> fn() = shout

            on start {
                invoke(pick())
            }
        """.trimIndent()
        assertEquals(src, text.write(graphOf(src)).trim(), "round trip")
    }
}
