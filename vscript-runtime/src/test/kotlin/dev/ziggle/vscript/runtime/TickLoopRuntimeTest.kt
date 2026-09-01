package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.compile.graph
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TickMode.LOOP] through the runtime: the loops start after `on start`, run once per [ScriptRuntime.tick],
 * and end at a pass boundary when the host asks the script to sleep.
 */
class TickLoopRuntimeTest {

    private val countNode = hostNode(
        "test.count", "count", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val markNode = hostNode(
        "test.mark", "mark", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(countNode, markNode), exclude = setOf(BuiltinNodes.ENTRY_RENDER))

    private var marked = false
    private var counts = 0
    private var countedBeforeMark = false

    private fun hosts(): HostRegistry = BuiltinHosts.registry().also { r ->
        r.register("mark", HostKind.INLINE, arity = 0, results = 0) { marked = true; null }
        r.register("count", HostKind.INLINE, arity = 0, results = 0) {
            if (!marked) countedBeforeMark = true
            counts++
            null
        }
    }

    private val clock = FakeClock()

    private fun runtime() = ScriptRuntime(catalog, hosts(), clock = clock, tickMode = TickMode.LOOP)

    @Test
    fun `the loop starts after on start has finished and runs once per tick`() {
        val doc = EditorDoc(graph("loop") {
            val start = node(BuiltinNodes.ENTRY)
            val delay = node(BuiltinNodes.DELAY, literals = mapOf("Ms" to 100))
            val mark = node("test.mark")
            link(start, "Exec", delay, "Exec")
            link(delay, "Exec", mark, "Exec")

            val tick = node(BuiltinNodes.ENTRY_TICK)
            val count = node("test.count")
            link(tick, "Exec", count, "Exec")
        })
        val rt = runtime()
        assertNull(rt.run(doc))

        rt.tick()                              // start parks on its delay; the loop must not have begun
        assertEquals(0, counts)
        clock.now = 100
        rt.tick()                              // start finishes; the loop is spawned this tick
        rt.tick()
        rt.tick()
        assertTrue(marked)
        assertTrue(!countedBeforeMark, "no pass may run before on start is done")
        assertTrue(counts >= 2, "then one pass per tick: $counts")
        assertTrue(rt.isRunning, "a loop keeps the script running")
    }

    @Test
    fun `a document that is only a tick loop runs`() {
        val doc = EditorDoc(graph("only-tick") {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val count = node("test.count")
            link(tick, "Exec", count, "Exec")
        })
        val rt = runtime()
        assertNull(rt.run(doc))
        repeat(4) { rt.tick() }
        assertEquals(4, counts)
        assertTrue(rt.isRunning)
    }

    @Test
    fun `a sleep request ends the loop at a pass boundary and the script goes to sleep`() {
        val doc = EditorDoc(graph("sleeper") {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val count = node("test.count")
            link(tick, "Exec", count, "Exec")
        })
        val rt = runtime()
        assertNull(rt.run(doc))
        repeat(3) { rt.tick() }
        assertEquals(3, counts)

        assertTrue(rt.requestSleep("test"))
        repeat(3) { rt.tick() }
        assertTrue(rt.isAsleep, "phase is ${rt.phase}")
        assertEquals(3, counts, "no pass runs once the sleep was asked for at a boundary")
    }

    @Test
    fun `gameTick has nothing to drive in loop mode`() {
        val doc = EditorDoc(graph("gt") {
            val tick = node(BuiltinNodes.ENTRY_TICK)
            val count = node("test.count")
            link(tick, "Exec", count, "Exec")
        })
        val rt = runtime()
        assertNull(rt.run(doc))
        rt.gameTick()
        rt.gameTick()
        assertEquals(0, counts, "the staged pass is not what runs a loop")
        rt.tick()
        assertEquals(1, counts)
    }
}
