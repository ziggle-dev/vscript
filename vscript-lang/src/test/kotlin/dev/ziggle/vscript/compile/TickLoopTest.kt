package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.Interpreter
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.Scheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `on tick` compiled as a LOOP — see `GraphCompiler.tickLoop` and `Validator.tickMayWait`.
 *
 * The body runs once per scheduler pass; a wait inside a pass stretches that pass across passes; a
 * `return` ends the pass, not the fiber; and the rule that a tick cannot wait is lifted for it.
 */
class TickLoopTest {

    private val countNode = hostNode(
        "test.count", "count", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(countNode))

    private class Counting {
        var calls = 0
        val hosts: HostRegistry = HostRegistry().also { r ->
            r.register("count", HostKind.INLINE, arity = 0, results = 0) { calls++; null }
        }
    }

    private fun loopChunk(graph: dev.ziggle.vscript.model.Graph) =
        GraphCompiler(catalog, tickLoop = true).compileEntries(graph, EntryGroup.TICK).entries.single().chunk

    @Test
    fun `a tick loop ends in YIELD and a jump to its head`() {
        val g = graph {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val count = node("test.count")
            link(tick, "Exec", count, "Exec")
        }
        val chunk = loopChunk(g)
        val ops = chunk.opcodes()
        assertEquals(Op.YIELD, ops[ops.size - 2], "the pass boundary parks")
        assertEquals(Op.JMP, ops.last(), "and the next pass begins at the head")
        assertEquals(0, chunk.a(chunk.size - 1), "the head of a loop with no prologue is instruction 0")
        assertTrue(Op.HALT !in ops, "a loop never halts on its own")
    }

    @Test
    fun `the body runs once per scheduler pass and the fiber never finishes`() {
        val g = graph {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val count = node("test.count")
            link(tick, "Exec", count, "Exec")
        }
        val counting = Counting()
        val clock = FakeClock()
        val scheduler = Scheduler(Interpreter(counting.hosts, clock), clock)
        val fiber = scheduler.spawn("tick", loopChunk(g))

        repeat(5) { scheduler.tick() }
        assertEquals(5, counting.calls, "one pass per tick, no more")
        assertEquals(FiberState.PARKED, fiber.state)
        assertTrue(fiber.isAtPassBoundary, "between passes the fiber rests on the boundary")
    }

    @Test
    fun `a wait inside a pass stretches that pass across ticks`() {
        val g = graph {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val count = node("test.count")
            val delay = node(BuiltinNodes.DELAY, literals = mapOf("Ms" to 100))
            link(tick, "Exec", count, "Exec")
            link(count, "Exec", delay, "Exec")
        }
        val counting = Counting()
        val clock = FakeClock()
        val scheduler = Scheduler(Interpreter(counting.hosts, clock), clock)
        val fiber = scheduler.spawn("tick", loopChunk(g))

        scheduler.tick()                         // count, then park on the delay
        assertEquals(1, counting.calls)
        assertTrue(!fiber.isAtPassBoundary, "parked on the delay is mid-pass, not the boundary")
        clock.now = 50; scheduler.tick()         // still waiting: no second pass
        assertEquals(1, counting.calls)
        clock.now = 100; scheduler.tick()        // the delay ends, the pass finishes, the boundary parks
        assertEquals(1, counting.calls)
        assertTrue(fiber.isAtPassBoundary)
        scheduler.tick()                         // the next pass
        assertEquals(2, counting.calls)
    }

    @Test
    fun `a return ends the pass, not the fiber`() {
        val g = graph {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val ret = node(BuiltinNodes.RETURN)
            val count = node("test.count")
            link(tick, "Exec", ret, "Exec")
            // Reachable only by being wired; the return means it never runs.
            link(ret, "Exec", count, "Exec")
        }
        val counting = Counting()
        val clock = FakeClock()
        val scheduler = Scheduler(Interpreter(counting.hosts, clock), clock)
        val fiber = scheduler.spawn("tick", loopChunk(g))

        repeat(3) { scheduler.tick() }
        assertEquals(0, counting.calls)
        assertEquals(FiberState.PARKED, fiber.state, "a return in a pass parks at the boundary rather than ending the fiber")
        assertTrue(fiber.isAtPassBoundary)
    }

    @Test
    fun `the cannot-wait rule is lifted for a tick loop and kept for a frame`() {
        val g = graph {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val delay = node(BuiltinNodes.DELAY, literals = mapOf("Ms" to 100))
            link(tick, "Exec", delay, "Exec")
        }
        assertTrue(
            Validator(catalog).validate(g).any { it.severity == Severity.ERROR && "Delay inside On Tick" in it.message },
            "a staged tick pass still may not wait",
        )
        assertTrue(Validator(catalog, tickMayWait = true).validate(g).none { it.severity == Severity.ERROR })

        val frame = graph {
            val render = node(BuiltinNodes.ENTRY_RENDER)
            val delay = node(BuiltinNodes.DELAY, literals = mapOf("Ms" to 100))
            link(render, "Exec", delay, "Exec")
        }
        assertTrue(
            Validator(catalog, tickMayWait = true).validate(frame).any { it.severity == Severity.ERROR },
            "a frame is a frame on every host",
        )
    }

    @Test
    fun `a start entry is compiled as before even when ticks loop`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val count = node("test.count")
            link(entry, "Exec", count, "Exec")
        }
        val chunk = GraphCompiler(catalog, tickLoop = true).compileEntries(g, EntryGroup.START).entries.single().chunk
        assertEquals(Op.HALT, chunk.opcodes().last())
    }

    @Test
    fun `a catalogue can leave a builtin out`() {
        val without = NodeCatalog(listOf(countNode), exclude = setOf(BuiltinNodes.ENTRY_RENDER))
        assertEquals(null, without[BuiltinNodes.ENTRY_RENDER])
        assertTrue(without[BuiltinNodes.ENTRY_TICK] != null)
        assertTrue(without.all.none { it.type == BuiltinNodes.ENTRY_RENDER })
    }
}
