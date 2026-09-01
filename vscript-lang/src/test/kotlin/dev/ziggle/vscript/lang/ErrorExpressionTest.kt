package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `error("…")` — the expression that never hands anything back.
 *
 * **`?:` already narrowed.** `a ?: b` has always been `a`'s type with the `?` taken off, and it compiles to
 * a jump, so the right-hand side is reached only when the value really was absent. What was missing was
 * something to WRITE there when there is no sensible fallback — so a lookup that must succeed had to either
 * invent a meaningless default or leave the whole thing optional and push the question to every caller.
 *
 * That is the whole feature: one node, a `STRING` in and a WILDCARD out, because there is no value to type
 * when control does not reach the wire.
 *
 * **It is not an exception.** Nothing catches it. It throws a `VmError`, which becomes a FAILED fiber
 * carrying the chunk, the pc, the node id and a stack trace — and which a debug session pauses on at the
 * faulting instruction with the frames still standing. For a script driving a game client that is the
 * useful behaviour: the alternative to a wrong value is stopping where the wrongness is, not carrying on.
 */
class ErrorExpressionTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private class Run(val said: List<Any?>, val state: FiberState, val error: String?)

    private fun run(src: String): Run {
        val r = VsText(catalog).read(src)
        val g = assertNotNull(r.graph, "should compile: ${r.errors.map { "${it.span.line}: ${it.message}" }}")
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val chunk = GraphCompiler(catalog, debug = false)
            .compile(g, g.entries(catalog).single().id)
        val out = drive(chunk, hosts, maxTicks = 20000)
        return Run(
            said.map { if (it is Number && it !is Double) it.toLong() else it },
            out.fiber.state,
            out.fiber.error?.rawMessage,
        )
    }

    private fun printed(src: String): String {
        val vs = VsText(catalog)
        val r = vs.read(src)
        return vs.write(assertNotNull(r.graph, "${r.errors.map { "${it.span.line}: ${it.message}" }}")).trim()
    }

    private val TABLE = """
        graph "probe"

        fn twice(n: INT) -> INT = n * 2

        var Table: MAP<INT, fn(INT) -> INT> = _newMap()

        fn pick(k: INT) -> fn(INT) -> INT =
            _mapAt(map: Table, key: k) ?: error(message: "no handler for that key")
    """.trimIndent()

    // ---- it turns an optional into a plain value -----------------------------------------------------

    /**
     * The point of the whole thing: `pick` is declared to return a plain `fn(INT) -> INT`, not an optional,
     * and the body is a lookup that may miss. Without somewhere to send the miss, that signature is a lie.
     */
    @Test
    fun `a lookup that may miss can be declared to return a plain value`() {
        val out = run(
            """
            $TABLE

            on start {
                Table = _mapWith(map: Table, key: 1, value: twice)
                val f = pick(k: 1)
                say(message: f(5))
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "the fallback must not fire when the value is there: ${out.error}")
        assertEquals(listOf(10L), out.said)
    }

    /** ...and when it does miss, the script STOPS, carrying the message that was written. */
    @Test
    fun `a miss stops the script with the message`() {
        val out = run(
            """
            $TABLE

            on start {
                val f = pick(k: 9)
                say(message: f(5))
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.FAILED, out.state, "a miss has to stop, not carry on")
        assertEquals("no handler for that key", out.error)
        assertEquals(emptyList(), out.said, "nothing downstream of the failure should have run")
    }

    /**
     * Inside a record LITERAL, filling a field that is not optional.
     *
     * This is the shape that asked for it: a record whose handler column must be present by the time
     * anything holds one, built from a table that cannot promise it.
     */
    @Test
    fun `it fills a non-optional field of a record literal`() {
        val out = run(
            """
            graph "probe"

            fn twice(n: INT) -> INT = n * 2

            type Rumor { n: INT, runnable: fn(INT) -> INT }

            var Table: MAP<INT, fn(INT) -> INT> = _newMap()

            fn make(k: INT) -> Rumor = Rumor {
                n: k,
                runnable: _mapAt(map: Table, key: k) ?: error(message: "a rumor needs a backing function"),
            }

            on start {
                Table = _mapWith(map: Table, key: 1, value: twice)
                val made = make(k: 1)
                val go = made.runnable
                say(message: go(4))
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf(8L), out.said)
    }

    @Test
    fun `it works on an ordinary optional, not only a function one`() {
        val out = run(
            """
            graph "probe"

            var Names: MAP<INT, STRING> = _newMap()

            fn nameOf(k: INT) -> STRING = _mapAt(map: Names, key: k) ?: error(message: "no name for $ k")

            on start {
                Names = _mapWith(map: Names, key: 1, value: "one")
                say(message: nameOf(k: 1))
            }
            """.trimIndent().replace("$ k", "that key"),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf("one"), out.said)
    }

    // ---- it is cheap when it does not fire -----------------------------------------------------------

    /**
     * The fallback is reached through a JUMP, so an `error` that is not taken costs nothing at run time —
     * which is what makes it safe to put one in a hot path. Asserted by observation rather than by reading
     * the bytecode: a fallback that were evaluated eagerly would abort on the very first call.
     */
    @Test
    fun `the fallback does not run on the passes that did not need it`() {
        val out = run(
            """
            $TABLE

            var Total: INT = 0

            on start {
                Table = _mapWith(map: Table, key: 1, value: twice)
                for i in range(from: 0, to: 3) {
                    val f = pick(k: 1)
                    Total = Total + f(i)
                }
                say(message: Total)
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf(6L), out.said, "0 + 2 + 4")
    }

    // ---- it round-trips ------------------------------------------------------------------------------

    @Test
    fun `it prints back as it was written`() {
        val src = """
            graph "probe"

            var Names: MAP<INT, STRING> = _newMap()

            fn nameOf(k: INT) -> STRING = _mapAt(map: Names, key: k) ?: error(message: "no name for that key")

            on start {
                say(message: nameOf(k: 1))
            }
        """.trimIndent()
        assertEquals(src, printed(src))
    }

    // ---- what it must NOT do -------------------------------------------------------------------------

    /**
     * It must not make a wire accept the wrong TYPE.
     *
     * Its output is a wildcard so that it fits wherever the narrowed value fits, and a wildcard is
     * permissive by design — so the assertion worth making is that the narrowing still comes from the LEFT
     * of `?:`. `pick` returns a function; wiring that into an INT is refused exactly as it was before.
     */
    @Test
    fun `the narrowed type still comes from the value, not from the error`() {
        val r = VsText(catalog).read(
            """
            $TABLE

            fn wrong() -> INT = _mapAt(map: Table, key: 1) ?: error(message: "nope")

            on start {
                say(message: wrong())
            }
            """.trimIndent(),
        )
        val errors = r.errors.map { it.message } +
            dev.ziggle.vscript.compile.Validator(catalog)
                .validate(r.graph ?: return assertTrue(true))
                .filter { it.severity == dev.ziggle.vscript.compile.Severity.ERROR }
                .map { it.message }
        assertTrue(
            errors.any { "cannot wire" in it || "INT" in it },
            "a function narrowed by `?: error(…)` is still a function: $errors",
        )
    }

    /**
     * On its own line it STOPS the script — the plain "this cannot happen" guard.
     *
     * It is a different node from the one an expression makes: the expression form is pure, and a pure node
     * in statement position computes a value nobody reads and never runs. Position decides which is made,
     * and a reader never learns there are two — both are written `error(…)` and both print that way.
     */
    @Test
    fun `on its own line it stops the script`() {
        val out = run(
            """
            graph "probe"

            on start {
                say(message: "before")
                error(message: "unreachable, apparently")
                say(message: "after")
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.FAILED, out.state, "a bare `error(…)` has to stop the script")
        assertEquals("unreachable, apparently", out.error)
        assertEquals(listOf("before"), out.said, "the statement after it must not run")
    }

    @Test
    fun `the statement form round-trips as the same word`() {
        val src = """
            graph "probe"

            on start {
                say(message: "before")
                error(message: "that cannot happen")
            }
        """.trimIndent()
        assertEquals(src, printed(src))
    }

    /** A document that declares its own `error` still means its own — position does not override scope. */
    @Test
    fun `a document may declare its own error function`() {
        val out = run(
            """
            graph "probe"

            fn error(code: INT) {
                say(message: code)
            }

            on start {
                error(code: 4)
                say(message: "still here")
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf(4L, "still here"), out.said)
    }

    /** ...and `return error(…)` is one of the two it names, so it had better work. */
    @Test
    fun `return error is a legal body for a function with a result`() {
        val out = run(
            """
            graph "probe"

            var Names: MAP<INT, STRING> = _newMap()

            fn nameOf(k: INT) -> STRING {
                if val n = _mapAt(map: Names, key: k) {
                    return n
                }
                return error(message: "no name for that key")
            }

            on start {
                Names = _mapWith(map: Names, key: 1, value: "one")
                say(message: nameOf(k: 1))
                say(message: nameOf(k: 2))
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.FAILED, out.state, "the second lookup misses")
        assertEquals("no name for that key", out.error)
        assertEquals(listOf("one"), out.said, "the first one answered before the second stopped it")
    }

    @Test
    fun `the failure carries where it happened`() {
        val r = VsText(catalog).read(
            """
            graph "probe"

            var Names: MAP<INT, STRING> = _newMap()

            on start {
                say(message: _mapAt(map: Names, key: 1) ?: error(message: "boom"))
            }
            """.trimIndent(),
        )
        val g = assertNotNull(r.graph, "${r.errors.map { it.message }}")
        val chunk = GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { _ -> null }
        val out = drive(chunk, hosts, maxTicks = 20000)
        val err = assertNotNull(out.fiber.error, "it should have failed")
        assertEquals("boom", err.rawMessage)
        assertTrue(err.nodeId >= 0, "the failure should name the node it happened at: ${err.message}")
    }

    /**
     * Written the way it actually gets written — positionally, filling a handler column.
     *
     * `_mapAt(RunTargets, target) ?: error("…")` is the shape this was asked for, and positional arguments
     * are accepted on the way in and labelled on the way out (see `CANONICAL_FORM.md`), so both spellings
     * have to reach the same node.
     */
    @Test
    fun `the positional spelling works and prints back labelled`() {
        val src = """
            graph "probe"

            fn twice(n: INT) -> INT = n * 2

            type Rumor { n: INT, runnable: fn(INT) -> INT }

            var Table: MAP<INT, fn(INT) -> INT> = _newMap()

            fn make(k: INT) -> Rumor {
                return Rumor {
                    n: k,
                    runnable: _mapAt(Table, k) ?: error("a rumor requires a backing function"),
                }
            }

            on start {
                Table = _mapWith(map: Table, key: 1, value: twice)
                val made = make(k: 1)
                val go = made.runnable
                say(message: go(4))
            }
        """.trimIndent()
        val out = run(src)
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf(8L), out.said)
        assertTrue(
            "error(message: \"a rumor requires a backing function\")" in printed(src),
            "it should come back labelled:\n${printed(src)}",
        )
    }
}
