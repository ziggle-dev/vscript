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
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four things `docs/GAPS.md` numbered 18–21, each with the script that used to fail.
 *
 * They were found together, migrating one real script (`graahk.vs`), and they share a shape worth keeping
 * in one file: every one of them was a rule the language enforced without ever stating it, discovered by an
 * author who had read the documentation and written what it said. So each test here is the code from the
 * gap report, run — not a narrower thing that happens to exercise the same branch.
 */
class GapsTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** A step that also produces a value — what a read outside the exec chain needs to be reported. */
    private val pullNode = hostNode(
        "test.pull", "test.pull", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.INT)),
    )

    private val catalog = NodeCatalog(listOf(sayNode, pullNode))
    private val text = VsText(catalog)

    /** `core/list.vs`, cut to what these scripts call. */
    private val LIST = """
        fn LIST<T>.filter(self, f: fn(T) -> BOOL) -> LIST<T> = filtered(list: self, keeping: f)
        fn LIST<T>.firstWhere(self, f: fn(T) -> BOOL) -> T? = firstWhere(list: self, matching: f)
        fn LIST<T>.has(self, value: T) -> BOOL = _listContains(list: self, value: value)
    """.trimIndent()

    private fun graphOf(src: String): Graph {
        val read = text.read(src)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span.line}: ${it.message}" }}")
        return assertNotNull(read.graph)
    }

    private fun errors(src: String): List<String> = text.read(src).errors.map { it.message }

    private fun compile(src: String): Chunk {
        val g = graphOf(src)
        return GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)
    }

    private fun said(src: String): List<Any?> {
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        hosts.register("test.pull", HostKind.INLINE, arity = 0) { 7L }
        val r = drive(compile(src), hosts, maxTicks = 20000)
        assertNull(r.fiber.error, "vm error: ${r.fiber.error}")
        assertTrue(r.fiber.isFinished, "did not finish")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    /** [src] read and printed back. */
    private fun printed(src: String): String = text.write(graphOf(src)).trim()

    // ---- 18: a captured local as an extension-call receiver ---------------------------------------------

    /**
     * The report's own script. `armed` is read by calling an extension ON it, which is the one position a
     * capture was invisible in — a call whose head is a bare name has its dots collected into the node
     * TYPE, so the receiver was never among the names the lambda looked for.
     */
    @Test
    fun `a captured local may be the receiver of an extension call`() {
        assertEquals(
            listOf(2L),
            said(
                """
                graph "probe"

                $LIST

                on start {
                    val armed = [2, 3]
                    val hit = [1, 2, 3].firstWhere { armed.has(value: it) }
                    if val found = hit {
                        say(message: found)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** And nested inside another call on it, which is the `it < limit.twice()` shape from the report. */
    @Test
    fun `a capture is found under a dot at any depth`() {
        // 1 and 3 are <= 3; 9 is not.
        assertEquals(
            listOf(2L),
            said(
                """
                graph "probe"

                $LIST

                export fn LIST<INT>.biggest(self) -> INT = _listLargest(list: self) ?: 0

                on start {
                    val bound = [1, 3]
                    val kept = [1, 3, 9].filter { it <= bound.biggest() }
                    say(message: _listCount(list: kept))
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- 19: an expression body calling a step ----------------------------------------------------------

    /**
     * The report's script, run. `stepped` has to run; `wrapper` is one line that calls it. That used to be
     * 26 copies of "'Call.Result' is read here, but nothing runs 'Call'" in a real migration.
     */
    @Test
    fun `an expression body may call a block-bodied function`() {
        assertEquals(
            listOf("step", 5L),
            said(
                """
                graph "probe"

                export fn stepped(n: INT) -> INT {
                    say(message: "step")
                    return n * 2
                }

                export fn wrapper(n: INT) -> INT = stepped(n: n) + 1

                on start {
                    say(message: wrapper(n: 2))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * And it is a STEP now, which is the honest consequence: it runs once where it is called rather than
     * at every read. The printer says so by giving the braces back — nothing stores which form was typed,
     * so the spelling that comes out is the one the graph means.
     */
    @Test
    fun `a one-line function that has to run prints as a block`() {
        val src = """
            graph "probe"

            export fn stepped(n: INT) -> INT {
                say(message: "step")
                return n * 2
            }

            export fn wrapper(n: INT) -> INT = stepped(n: n) + 1

            on start {
                say(message: wrapper(n: 2))
            }
        """.trimIndent()
        assertTrue(
            printed(src).contains("fn wrapper(n: INT) -> INT {"),
            "wrapper computes by running something, so it is a step:\n${printed(src)}",
        )
    }

    /** The other direction: nothing in it has to run, so it stays an expression and keeps the short form. */
    @Test
    fun `a one-line function that only computes is still an expression`() {
        val src = """
            graph "probe"

            export fn twice(n: INT) -> INT {
                return n * 2
            }

            export fn wrapper(n: INT) -> INT = twice(n: n) + 1

            on start {
                say(message: wrapper(n: 2))
            }
        """.trimIndent()
        // Written with braces, printed without — `= n * 2` and `{ return n * 2 }` are one function.
        assertTrue(printed(src).contains("fn twice(n: INT) -> INT = n * 2"), printed(src))
        assertEquals(listOf(5L), said(src))
    }

    /** A body whose step is nested deep inside the expression still gets the chain it needs. */
    @Test
    fun `a step inside a ternary in an expression body still runs`() {
        assertEquals(
            listOf("ran", 8L),
            said(
                """
                graph "probe"

                export fn loud() -> INT {
                    say(message: "ran")
                    return 8
                }

                export fn pick(yes: BOOL) -> INT = yes ? loud() : 0

                on start {
                    say(message: pick(yes: true))
                }
                """.trimIndent(),
            ),
        )
    }

    /** Recursion is the case the purity fixpoint has to reach a decision about rather than loop on. */
    @Test
    fun `a recursive one-line function settles`() {
        assertEquals(
            listOf(120L),
            said(
                """
                graph "probe"

                export fn fact(n: INT) -> INT = n <= 1 ? 1 : n * fact(n: n - 1)

                on start {
                    say(message: fact(n: 5))
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- 20: narrowing that survives an early return and an `||` ---------------------------------------

    /**
     * The guard clause, which is how a long function stays flat. The `then` cannot fall through, so the
     * code below it is only reached the other way — and down there the optional is proved.
     */
    @Test
    fun `an early return narrows the code after it`() {
        assertEquals(
            listOf(5L),
            said(
                """
                graph "probe"

                $LIST

                export type P { x: INT }

                on start {
                    val hit = [P { x: 1 }, P { x: 5 }].firstWhere { it.x > 3 }
                    if hit == null {
                        say(message: -1)
                        return
                    }
                    say(message: hit.x)
                }
                """.trimIndent(),
            ),
        )
    }

    /** The dual: the `else` is what leaves, so falling out of the `then` proves the condition held. */
    @Test
    fun `an else that returns narrows the code after it`() {
        assertEquals(
            listOf(5L),
            said(
                """
                graph "probe"

                $LIST

                export type P { x: INT }

                on start {
                    val hit = [P { x: 1 }, P { x: 5 }].firstWhere { it.x > 3 }
                    if hit != null {
                        say(message: hit.x)
                    } else {
                        return
                    }
                    say(message: hit.x)
                }
                """.trimIndent(),
            ).drop(1),
        )
    }

    /** `||` is the dual of the `&&` chain: its right side is only reached when the left was false. */
    @Test
    fun `the right side of an or-else is narrowed by a null test on the left`() {
        assertEquals(
            listOf(5L),
            said(
                """
                graph "probe"

                $LIST

                export type P { x: INT }

                on start {
                    val hit = [P { x: 1 }, P { x: 5 }].firstWhere { it.x > 3 }
                    if hit == null || hit.x > 100 {
                        say(message: -1)
                        return
                    }
                    say(message: hit.x)
                }
                """.trimIndent(),
            ),
        )
    }

    /** An `&&` in expression position, which is not the `if` statement's flattened chain. */
    @Test
    fun `the right side of an and-then is narrowed outside an if`() {
        assertEquals(
            listOf(true),
            said(
                """
                graph "probe"

                $LIST

                export type P { x: INT }

                on start {
                    val hit = [P { x: 1 }, P { x: 5 }].firstWhere { it.x > 3 }
                    val big = hit != null && hit.x > 3
                    say(message: big)
                }
                """.trimIndent(),
            ),
        )
    }

    /** With an `&&` chain the code below a returning `then` is reached when ANY of them failed. */
    @Test
    fun `an early return proves nothing when the condition was a chain`() {
        val errs = errors(
            """
            graph "probe"

            $LIST

            export type P { x: INT }

            on start {
                val a = [P { x: 5 }].firstWhere { it.x > 3 }
                val b = [P { x: 5 }].firstWhere { it.x > 4 }
                if a == null && b == null {
                    return
                }
                say(message: a.x)
            }
            """.trimIndent(),
        )
        assertTrue(errs.any { it.contains("P?") }, "should still refuse the optional: $errs")
    }

    // ---- 21: a validation error that names a line -------------------------------------------------------

    /**
     * A lambda body is an expression and stays one, so a step written inside one is still refused — and
     * that error now carries the line it is on. It used to carry a node id and nothing else, which for a
     * migration producing them in bulk is not a diagnostic at all.
     */
    @Test
    fun `a validation error carries the line it is on`() {
        val src = """
            graph "probe"

            $LIST

            on start {
                val kept = [1, 2, 3].filter { it < pull() }
                say(message: _listCount(list: kept))
            }
        """.trimIndent()
        val read = text.read(src)
        assertTrue(!read.ok, "should be refused")
        val bad = read.errors.single { it.message.contains("nothing runs") }
        assertEquals(
            src.lines().indexOfFirst { it.contains("pull()") } + 1,
            bad.span.line,
            "should point at the step written in the lambda",
        )
    }

    /** The same fact through the validator's own API, which is what the client and the compiler read. */
    @Test
    fun `the issue itself carries the span, not just the diagnostic`() {
        val src = """
            graph "probe"

            $LIST

            on start {
                val kept = [1, 2, 3].filter { it < pull() }
                say(message: _listCount(list: kept))
            }
        """.trimIndent()
        val low = Lower(catalog).lower(Parser(Lexer(src).lex()).parse().program)
        val issues = Validator(catalog, dev.ziggle.vscript.model.GraphSource.NONE, low.spans, low.declSpans)
            .validate(low.graph)
        val bad = issues.single { it.severity == Severity.ERROR && it.message.contains("nothing runs") }
        assertEquals(src.lines().indexOfFirst { it.contains("pull()") } + 1, bad.span.line)
        assertTrue(bad.toString().contains("(${bad.span.line}:"), "toString should say where: $bad")
    }
}
