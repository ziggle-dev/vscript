package dev.ziggle.vscript.vm

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.graph
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A BLOCKING host with no actuator may answer LATER by returning a [HostAwait] — see its class note.
 *
 * The fiber parks exactly as it would behind an actuator, and the result lands through the same
 * `tryCompleteAct`, so a failure reaches `try`/`catch` and a value reaches the result window.
 */
class HostAwaitTest {

    private val mineNode = hostNode(
        "test.mine", "mine", NodeKind.IMPURE, HostKind.BLOCKING,
        inputs = listOf(PinSpec("Exec", PinType.EXEC)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Drops", PinType.INT)),
    )
    private val catalog = NodeCatalog(listOf(mineNode))

    private val graph = graph {
        val entry = node(BuiltinNodes.ENTRY)
        val mine = node("test.mine")
        val ret = node(BuiltinNodes.RETURN)
        link(entry, "Exec", mine, "Exec")
        link(mine, "Exec", ret, "Exec")
        link(mine, "Drops", ret, "Value")
    }

    private fun hosts(await: HostAwait): HostRegistry = HostRegistry().also { r ->
        r.register("mine", HostKind.BLOCKING, arity = 0, results = 1) { await }
    }

    @Test
    fun `an await parks the fiber until the host completes it, then lands the value`() {
        val chunk = GraphCompiler(catalog).compile(graph, 1)
        val await = HostAwait()
        val interpreter = Interpreter(hosts(await), FakeClock())
        val scheduler = Scheduler(interpreter, FakeClock())
        val fiber = scheduler.spawn("t", chunk)

        scheduler.tick()
        assertEquals(FiberState.AWAITING_ACT, fiber.state, "the fiber must wait for the host, not run on")
        scheduler.tick()
        scheduler.tick()
        assertEquals(FiberState.AWAITING_ACT, fiber.state, "and keep waiting however many passes go by")
        assertTrue(fiber.result.isNullOrEmpty(), "nothing has landed yet")

        await.complete(7)
        scheduler.tick()
        assertEquals(FiberState.DONE, fiber.state)
        assertEquals(listOf<Any?>(7), fiber.result)
    }

    @Test
    fun `a failed await fails the fiber on the node that asked, with the host's words`() {
        val chunk = GraphCompiler(catalog).compile(graph, 1)
        val await = HostAwait()
        val interpreter = Interpreter(hosts(await), FakeClock())
        val scheduler = Scheduler(interpreter, FakeClock())
        val fiber = scheduler.spawn("t", chunk)

        scheduler.tick()
        await.fail("the block is gone")
        scheduler.tick()

        assertEquals(FiberState.FAILED, fiber.state)
        val err = assertNotNull(fiber.error)
        assertTrue("the block is gone" in err.message.orEmpty(), err.message)
        assertEquals(2, err.nodeId, "the failure is anchored on the mining node")
    }

    @Test
    fun `completing twice keeps the first answer`() {
        val await = HostAwait()
        await.complete(1)
        await.fail("late")
        assertEquals(1, await.box.get()?.getOrNull())
        assertTrue(await.isDone)
    }
}
