package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.FunctionValue
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ScriptRuntime.call] — the host's door into a running script.
 *
 * A script hands a host a function through a node that takes one, and the host calls it back later when
 * it has news. The properties that matter are that the call actually runs the right body with the right
 * arguments, that a closure's captures survive the trip, and that nothing a host queued can outlive the
 * run it was queued against.
 */
class HostCallTest {

    /**
     * `register(handler: …)` — a verb that takes a function and keeps it.
     *
     * Typed `fn(INT)` rather than a bare `FUNCTION`, and that is the difference between a pin a lambda
     * can be written at and one only a named function can reach: a lambda infers its parameters from its
     * destination, and an unconstrained function type is a destination that says nothing.
     */
    private val registerNode = hostNode(
        "test.register", "register", NodeKind.IMPURE,
        inputs = listOf(
            PinSpec("Exec", PinType.EXEC),
            PinSpec("Handler", TypeRef.function(listOf(TypeRef(PinType.INT)), TypeRef.NOTHING)),
        ),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** `note(value: …)` — how the script tells the test it ran. */
    private val noteNode = hostNode(
        "test.note", "note", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.INT)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(
        listOf(registerNode, noteNode),
        exclude = setOf(BuiltinNodes.ENTRY_RENDER),
    )

    private var handler: FunctionValue? = null
    private val noted = ArrayList<Long>()

    private fun hosts(): HostRegistry = BuiltinHosts.registry().also { r ->
        r.register("register", HostKind.INLINE, arity = 1, results = 0) { args ->
            handler = args[0] as? FunctionValue
            null
        }
        r.register("note", HostKind.INLINE, arity = 1, results = 0) { args ->
            noted += (args[0] as? Number)?.toLong() ?: -1L
            null
        }
    }

    private val clock = FakeClock()

    /** Compile [source] as text and start it. Returns the runtime, already ticked once. */
    private fun start(source: String): ScriptRuntime {
        val rt = ScriptRuntime(catalog, hosts(), clock = clock)
        val frontEnd = TextFrontEnd(catalog.natives())
        val compiled = frontEnd.compile(source)
        assertTrue(
            compiled.diagnostics.none { it.severity.name == "ERROR" },
            compiled.diagnostics.joinToString(" | ") { d -> "${d.severity} line ${d.span.line}: ${d.message}" },
        )
        assertNull(rt.runText(compiled, frontEnd.sites, "test", "test"))
        rt.tick()
        return rt
    }

    // ---- the happy path ----------------------------------------------------------------------------

    /** A named function handed over, then called by the host with an argument it chose. */
    @Test
    fun `a host can call a function the script handed it`() {
        val rt = start(
            """
            graph "cb"

            fn bump(n: INT) {
                note(value: n)
            }

            on start {
                register(handler: bump)
            }
            """.trimIndent(),
        )
        val fn = assertNotNull(handler, "the script should have handed over its function")

        assertTrue(rt.call(fn, listOf(7L)))
        assertEquals(emptyList(), noted, "the call must not run inline — it is a fiber, scheduled")

        rt.tick()
        assertEquals(listOf(7L), noted)
    }

    /** Each call is its own fiber, and they all run. */
    @Test
    fun `several queued calls all run`() {
        val rt = start(
            """
            graph "cb"

            fn bump(n: INT) {
                note(value: n)
            }

            on start {
                register(handler: bump)
            }
            """.trimIndent(),
        )
        val fn = assertNotNull(handler)

        assertTrue(rt.call(fn, listOf(1L)))
        assertTrue(rt.call(fn, listOf(2L)))
        assertTrue(rt.call(fn, listOf(3L)))
        assertEquals(3, rt.pendingCallCount)

        rt.tick()
        assertEquals(listOf(1L, 2L, 3L), noted.sorted())
        assertEquals(0, rt.pendingCallCount)
    }

    /**
     * A lambda's captures are trailing parameters the call site does not supply — the value carries them.
     * Getting this wrong is an arity error at best and the wrong values at worst.
     *
     * **Note the empty parentheses.** `register() { … }` parses; `register { … }` does not. The rule that
     * "the parentheses disappear when the lambda is the only argument" holds for a method-style call with
     * a receiver (`xs.each { … }`), not for a bare one — there the parser has no call to attach the block
     * to and reads the body as a single expression, which then rejects the first statement in it. Real
     * event verbs pass a kind alongside the handler, so they are written with parentheses anyway.
     */
    @Test
    fun `a closure's captured values survive the call`() {
        val rt = start(
            """
            graph "cb"

            on start {
                val base = 100
                register() { note(value: it + base) }
            }
            """.trimIndent(),
        )
        val fn = assertNotNull(handler, "a lambda is a function value too")
        assertTrue(fn.captured.isNotEmpty(), "the lambda closed over 'base', so it should carry it")

        assertTrue(rt.call(fn, listOf(5L)))
        rt.tick()
        assertEquals(listOf(105L), noted)
    }

    /**
     * The call runs even with nothing else live — a script that finished `on start` and is only
     * listening is exactly the case this exists for.
     */
    @Test
    fun `a call runs when no other fiber is left`() {
        val rt = start(
            """
            graph "cb"

            fn bump(n: INT) {
                note(value: n)
            }

            on start {
                register(handler: bump)
            }
            """.trimIndent(),
        )
        rt.tick()
        rt.tick()
        val fn = assertNotNull(handler)

        assertTrue(rt.call(fn, listOf(42L)))
        rt.tick()
        assertEquals(listOf(42L), noted)
    }

    // ---- what must be refused ----------------------------------------------------------------------

    /** Wrong arity fails at the call site, where the host still knows which handler it was. */
    @Test
    fun `a call with the wrong number of arguments is refused`() {
        val rt = start(
            """
            graph "cb"

            fn bump(n: INT) {
                note(value: n)
            }

            on start {
                register(handler: bump)
            }
            """.trimIndent(),
        )
        val fn = assertNotNull(handler)

        assertFalse(rt.call(fn, emptyList()), "bump takes one argument")
        assertFalse(rt.call(fn, listOf(1L, 2L)), "bump takes one argument")
        assertEquals(0, rt.pendingCallCount)
    }

    /** A stop is a stop: a call queued just before it must not run after it. */
    @Test
    fun `queued calls are dropped when the run stops`() {
        val rt = start(
            """
            graph "cb"

            fn bump(n: INT) {
                note(value: n)
            }

            on start {
                register(handler: bump)
            }
            """.trimIndent(),
        )
        val fn = assertNotNull(handler)
        assertTrue(rt.call(fn, listOf(9L)))

        rt.stop()
        rt.tick()

        assertEquals(emptyList(), noted, "a call queued before the stop must not run after it")
        assertEquals(0, rt.pendingCallCount)
    }

    /** A host holding a stale function value from a finished run cannot restart anything with it. */
    @Test
    fun `a call against a stopped run is refused`() {
        val rt = start(
            """
            graph "cb"

            fn bump(n: INT) {
                note(value: n)
            }

            on start {
                register(handler: bump)
            }
            """.trimIndent(),
        )
        val fn = assertNotNull(handler)
        rt.stop()

        assertFalse(rt.call(fn, listOf(1L)))
        rt.tick()
        assertEquals(emptyList(), noted)
    }

    /**
     * The queue is capped. An unbounded one turns a host firehose the script cannot keep up with into
     * heap exhaustion, and the refusal belongs at the door where the host can still decide what to do.
     */
    @Test
    fun `the pending queue is capped rather than unbounded`() {
        val rt = start(
            """
            graph "cb"

            fn bump(n: INT) {
                note(value: n)
            }

            on start {
                register(handler: bump)
            }
            """.trimIndent(),
        )
        val fn = assertNotNull(handler)

        var accepted = 0
        repeat(ScriptRuntime.MAX_PENDING_CALLS + 50) { if (rt.call(fn, listOf(it.toLong()))) accepted++ }

        assertEquals(ScriptRuntime.MAX_PENDING_CALLS, accepted)
        assertFalse(rt.call(fn, listOf(0L)), "further calls are refused, not silently dropped later")
    }
}
