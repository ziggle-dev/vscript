package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.drive
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Hold node: evaluate once, read many times.
 *
 * The assertions that matter here count **host calls**, not values. A Hold that re-evaluated its input
 * would still produce the right answer for any pure query — that is what pure means — so a value assertion
 * would pass against a node that does nothing at all. The number of times the world was asked is the only
 * thing that distinguishes holding from not holding, so that is what is asserted, and every test does it
 * against a control graph wired the ordinary way.
 */
class HoldTest {

    /** A pure query with no inputs, so the only reason to call it twice is that the graph asked twice. */
    private val counterNode = hostNode(
        "test.counter", "counter", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.INT)),
    )

    /** An impure step that reads one number, so a graph can read a value from a known point in the chain. */
    private val sinkNode = hostNode(
        "test.sink", "sink", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("A", PinType.INT, default = 0)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Result", PinType.INT)),
    )

    /** Three items, so a ForEach over it gives a loop that ends — see the re-evaluation test. */
    private val listNode = hostNode(
        "test.list", "makeList", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", PinType.LIST)),
    )

    private val catalog = NodeCatalog(listOf(counterNode, sinkNode, listNode))

    /** [calls] counts how often the world was asked; `counter` answers 1, 2, 3… so re-reads are visible. */
    private class Host {
        var calls = 0
        val seen = ArrayList<Int>()
        fun registry(): HostRegistry {
            val r = HostRegistry()
            r.register("counter", HostKind.INLINE) { ++calls }
            r.register("sink", HostKind.INLINE, arity = 1) { args -> (args[0] as Int).also { seen += it } }
            r.register("makeList", HostKind.INLINE) { mutableListOf<Any?>(1, 2, 3) }
            return r
        }
    }

    // ---- the control: without a Hold, a pure node is re-expanded per reader ------------------------

    @Test
    fun `without a hold a pure node is evaluated once per reader`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val counter = node("test.counter")
            val a = node("test.sink")
            val b = node("test.sink")
            link(entry, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
            link(counter, "Value", a, "A")
            link(counter, "Value", b, "A")
        }
        val host = Host()
        drive(GraphCompiler(catalog, debug = false).compile(g, 1), host.registry())

        // Two readers, two calls — and the two readers disagree, which is the failure a Hold prevents.
        assertEquals(2, host.calls)
        assertEquals(listOf(1, 2), host.seen)
    }

    // ---- the point of the node --------------------------------------------------------------------

    @Test
    fun `a hold evaluates its input once however many read it`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val counter = node("test.counter")
            val hold = node(BuiltinNodes.HOLD, literals = mapOf(BuiltinNodes.HOLD_NAME to "n"))
            val a = node("test.sink")
            val b = node("test.sink")
            val c = node("test.sink")
            link(entry, "Exec", hold, "Exec")
            link(counter, "Value", hold, "Value")
            link(hold, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
            link(b, "Exec", c, "Exec")
            link(hold, "Value", a, "A")
            link(hold, "Value", b, "A")
            link(hold, "Value", c, "A")
        }
        val host = Host()
        drive(GraphCompiler(catalog, debug = false).compile(g, 1), host.registry())

        assertEquals(1, host.calls, "three readers should not be three evaluations")
        assertEquals(listOf(1, 1, 1), host.seen, "every reader should see the same answer")
    }

    @Test
    @Timeout(10)
    fun `a held value is re-evaluated each time control passes through it`() {
        // Held is not cached-forever: the node is a step, so going round a loop reaches it again. That is
        // the difference between this and a constant, and it is what makes it usable inside a loop body.
        //
        // The loop is a ForEach over a three-item list rather than an exec wire looped back on itself. A
        // bare back-edge never terminates, and `maxTicks` does not bound it: it limits scheduler TICKS,
        // while the interpreter's 3ms budget preempts mid-loop and resumes, so the fiber stays runnable
        // and the counter fires hundreds of thousands of times per tick. Nothing hangs in the VM — the
        // assertions do, chunking a list of millions. A loop that ends on its own is also the only way to
        // assert an exact call count, which is the whole point of the test.
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val list = node("test.list")
            val loop = node(BuiltinNodes.FOR_EACH)
            val counter = node("test.counter")
            val hold = node(BuiltinNodes.HOLD, literals = mapOf(BuiltinNodes.HOLD_NAME to "n"))
            val a = node("test.sink")
            val b = node("test.sink")
            link(entry, "Exec", loop, "Exec")
            link(list, "Value", loop, "List")
            link(loop, "Body", hold, "Exec")
            link(counter, "Value", hold, "Value")
            link(hold, "Exec", a, "Exec")
            link(a, "Exec", b, "Exec")
            link(hold, "Value", a, "A")
            link(hold, "Value", b, "A")
        }
        val host = Host()
        drive(GraphCompiler(catalog, debug = false).compile(g, 1), host.registry())

        // Three passes: asked once per pass, and the two readers within a pass always agree.
        assertEquals(3, host.calls, "one evaluation per pass through the hold")
        assertEquals(listOf(1, 1, 2, 2, 3, 3), host.seen)
    }

    @Test
    fun `a hold compiles to a single move`() {
        // The cost claim in the plan, asserted rather than assumed: holding a value adds one instruction.
        fun ops(withHold: Boolean): List<Int> {
            val g = graph {
                val entry = node(BuiltinNodes.ENTRY)
                val counter = node("test.counter")
                val sink = node("test.sink")
                if (withHold) {
                    // The hold goes ON the chain, between the entry and the sink. An exec output drives
                    // exactly one node, so the entry cannot also wire straight to the sink — wiring both
                    // is what the validator refuses, and it was this test that was wrong, not the rule.
                    val hold = node(BuiltinNodes.HOLD, literals = mapOf(BuiltinNodes.HOLD_NAME to "n"))
                    link(entry, "Exec", hold, "Exec")
                    link(hold, "Exec", sink, "Exec")
                    link(counter, "Value", hold, "Value")
                    link(hold, "Value", sink, "A")
                } else {
                    link(entry, "Exec", sink, "Exec")
                    link(counter, "Value", sink, "A")
                }
            }
            return GraphCompiler(catalog, debug = false).compile(g, 1).opcodes()
        }

        val plain = ops(false)
        val held = ops(true)
        assertEquals(
            plain.count { it == Op.MOVE } + 1, held.count { it == Op.MOVE },
            "a hold should cost exactly one extra MOVE",
        )
        // And it must not have turned the query into a second call.
        assertEquals(plain.count { it == Op.CALL }, held.count { it == Op.CALL })
    }

    // ---- the label is configuration, not a wire ---------------------------------------------------

    @Test
    fun `wiring the name pin is refused`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val counter = node("test.counter")
            val hold = node(BuiltinNodes.HOLD)
            link(entry, "Exec", hold, "Exec")
            link(counter, "Value", hold, "Value")
            // Nothing reads Name at run time, so a wire into it would draw, compile, and be ignored.
            link(counter, "Value", hold, BuiltinNodes.HOLD_NAME)
        }
        val issues = Validator(catalog).validate(g).errors()
        assertTrue(
            issues.any { "is a label" in it.message },
            "expected the label-pin rule to fire, got: $issues",
        )
    }

    // ---- a hold is typed like whatever it holds ----------------------------------------------------

    /** A pure source of a record, so a hold carrying one can be wired somewhere that wants a number. */
    private val tileNode = hostNode(
        "test.tile", "tileOf", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", TypeRef.named("Coordinate"))),
    )
    private val typedCatalog = NodeCatalog(listOf(counterNode, sinkNode, tileNode))

    @Test
    fun `a hold carries its input's type, so a mis-wire through one is still caught`() {
        // The wildcard on a Hold's pins is a placeholder for "whatever this was handed", not a claim that
        // anything goes. Unresolved it is the silent widening TypeRef.named warns about: the wire draws,
        // the graph compiles, and the mistake surfaces at a host cast a long way away.
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val tile = node("test.tile")
            val hold = node(BuiltinNodes.HOLD, literals = mapOf(BuiltinNodes.HOLD_NAME to "t"))
            val sink = node("test.sink")
            link(entry, "Exec", hold, "Exec")
            link(tile, "Value", hold, "Value")
            link(hold, "Exec", sink, "Exec")
            link(hold, "Value", sink, "A")   // A is an INT, and a tile is not one
        }
        val issues = Validator(typedCatalog).validate(g).errors()
        assertTrue(issues.any { "cannot wire" in it.message }, "a tile reached an Int pin unchallenged: $issues")
    }

    @Test
    fun `a hold carrying the right type is accepted`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val counter = node("test.counter")
            val hold = node(BuiltinNodes.HOLD, literals = mapOf(BuiltinNodes.HOLD_NAME to "n"))
            val sink = node("test.sink")
            link(entry, "Exec", hold, "Exec")
            link(counter, "Value", hold, "Value")
            link(hold, "Exec", sink, "Exec")
            link(hold, "Value", sink, "A")
        }
        assertEquals(emptyList(), Validator(typedCatalog).validate(g).errors())
    }

    @Test
    fun `a hold with no name still compiles`() {
        // The name is for the reader, not the compiler — an unnamed hold is untidy, never broken.
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val counter = node("test.counter")
            val hold = node(BuiltinNodes.HOLD)
            val sink = node("test.sink")
            link(entry, "Exec", hold, "Exec")
            link(counter, "Value", hold, "Value")
            link(hold, "Exec", sink, "Exec")
            link(hold, "Value", sink, "A")
        }
        val host = Host()
        drive(GraphCompiler(catalog, debug = false).compile(g, 1), host.registry())
        assertEquals(listOf(1), host.seen)
    }
}
