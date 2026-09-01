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
 * `try { … } catch e { … }` — the language's exceptions, without exception classes.
 *
 * **There is nothing to match on**, and that is the design rather than a shortcut: no classes means no
 * hierarchy, so a handler catches everything raised under it and the message — a STRING — is the whole of
 * what it gets. Every question a `catch` clause list exists to answer disappears with it.
 *
 * **A range table, not an arm/disarm pair.** `Chunk.handlers` records the instructions a `try` guards, and
 * the range is checked when something is raised. That is the JVM's design and it is here for the JVM's
 * reason: `return`, `break` and `continue` all leave a block without passing its bottom, so an instruction
 * that armed the handler would need a matching one on every exit — and the exits are not enumerable.
 */
class TryCatchTest {

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
        val chunk = GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)
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

    // ---- it catches ----------------------------------------------------------------------------------

    @Test
    fun `an error raised in the body reaches the catch, with its message`() {
        val out = run(
            """
            graph "probe"

            on start {
                try {
                    say(message: "trying")
                    error(message: "it went wrong")
                    say(message: "not reached")
                } catch e {
                    say(message: e)
                }
                say(message: "carried on")
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "a caught error must not fail the fiber: ${out.error}")
        assertEquals(listOf("trying", "it went wrong", "carried on"), out.said)
    }

    /** A body that does not raise never enters the handler, and the fiber never sees an error. */
    @Test
    fun `a body that does not raise skips the catch`() {
        val out = run(
            """
            graph "probe"

            on start {
                try {
                    say(message: "fine")
                } catch e {
                    say(message: "should not run")
                }
                say(message: "after")
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf("fine", "after"), out.said)
    }

    /**
     * **Anywhere under it**, including several calls down.
     *
     * This is the whole reason the lookup walks FRAMES rather than checking one range: a `try` that only
     * caught what was written between its own braces would be useless, since the thing that fails is
     * almost always something you called.
     */
    @Test
    fun `an error raised inside a call is caught by the try that led there`() {
        val out = run(
            """
            graph "probe"

            fn inner() {
                error(message: "deep")
            }

            fn outer() {
                inner()
            }

            on start {
                try {
                    outer()
                } catch e {
                    say(message: e)
                }
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf("deep"), out.said)
    }

    /** The innermost `try` wins, and the outer one is left armed for what comes after. */
    @Test
    fun `a nested try catches first, and the outer one still guards what follows`() {
        val out = run(
            """
            graph "probe"

            on start {
                try {
                    try {
                        error(message: "inner")
                    } catch a {
                        say(message: a)
                    }
                    error(message: "outer")
                } catch b {
                    say(message: b)
                }
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf("inner", "outer"), out.said)
    }

    /** An error raised INSIDE a handler is not caught by that handler — it goes out. */
    @Test
    fun `a handler that itself fails does not catch itself`() {
        val out = run(
            """
            graph "probe"

            on start {
                try {
                    error(message: "first")
                } catch e {
                    say(message: e)
                    error(message: "second")
                }
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.FAILED, out.state, "the handler's own failure has nowhere to go")
        assertEquals("second", out.error)
        assertEquals(listOf("first"), out.said)
    }

    // ---- it catches more than `error(…)` -------------------------------------------------------------

    /**
     * Anything the VM raises, not only what the script raised on purpose.
     *
     * `error(…)` is the deliberate one, but the useful property is that a `try` guards the accidents too —
     * the host that was not registered, the number that was not a number. Those are exactly the failures
     * a bot script wants to survive rather than die on.
     */
    @Test
    fun `a failure the script did not raise is caught too`() {
        val out = run(
            """
            graph "probe"

            on start {
                try {
                    say(message: 1 / 0)
                } catch e {
                    say(message: "caught it")
                }
                say(message: "still here")
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.DONE, out.state, "${out.error}")
        assertEquals(listOf("caught it", "still here"), out.said)
    }

    // ---- every way OUT of a guarded body -------------------------------------------------------------

    /**
     * `return` out of a `try` must not leave the handler armed.
     *
     * The reason the table exists. With an arm/disarm pair this is the case that breaks: the `return`
     * leaves without passing the bottom of the block, so the handler survives into the caller and catches
     * something it never guarded.
     */
    @Test
    fun `returning out of a try leaves nothing armed`() {
        val out = run(
            """
            graph "probe"

            fn guarded() -> INT {
                try {
                    return 1
                } catch e {
                    return 2
                }
            }

            on start {
                say(message: guarded())
                error(message: "after, and uncaught")
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.FAILED, out.state, "the try was left behind and must not catch this")
        assertEquals("after, and uncaught", out.error)
        assertEquals(listOf(1L), out.said)
    }

    /** ...and `break` out of one, which leaves the same way. */
    @Test
    fun `breaking out of a try leaves nothing armed`() {
        val out = run(
            """
            graph "probe"

            var N: INT = 0

            on start {
                while true {
                    try {
                        break
                    } catch e {
                        say(message: "not this")
                    }
                }
                error(message: "after, and uncaught")
            }
            """.trimIndent(),
        )
        assertEquals(FiberState.FAILED, out.state, "the try was left behind and must not catch this")
        assertEquals("after, and uncaught", out.error)
        assertEquals(emptyList(), out.said)
    }

    // ---- it round-trips ------------------------------------------------------------------------------

    @Test
    fun `it prints back as it was written`() {
        val src = """
            graph "probe"

            on start {
                try {
                    error(message: "nope")
                } catch e {
                    say(message: e)
                }
            }
        """.trimIndent()
        assertEquals(src, printed(src))
    }

    @Test
    fun `the caught name is the one that was written`() {
        val src = """
            graph "probe"

            on start {
                try {
                    error(message: "nope")
                } catch reason {
                    say(message: reason)
                }
            }
        """.trimIndent()
        assertTrue("catch reason {" in printed(src), printed(src))
        assertEquals(src, printed(src))
    }

    @Test
    fun `a catch with no name is refused`() {
        val r = VsText(catalog).read(
            """
            graph "probe"

            on start {
                try {
                    error(message: "nope")
                } catch {
                    say(message: "?")
                }
            }
            """.trimIndent(),
        )
        assertTrue(!r.ok, "the message has to be bound to something")
    }
}
