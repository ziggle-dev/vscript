package dev.ziggle.vscript.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stopping, stepping and inspecting.
 *
 * All of this rests on one property of the runtime, and it is worth naming: execution is **resumable**
 * because it is an explicit machine rather than a recursive evaluator. A pause is a value the debugger
 * holds, not a blocked stack frame, which is why the frame list can be enumerated, a step can be a request
 * consumed later, and a fault can be held instead of unwound. A generator-based evaluator would give the
 * first of those and none of the rest.
 */
class DebuggerTest {

    /** Three "nodes" in a row, each marked the way the compiler marks one. */
    private fun threeNodes() = chunk("nodes") {
        emit(Op.TRACE, 1, TraceKind.NODE_ENTER)
        emit(Op.TRACE, 2, TraceKind.NODE_ENTER)
        emit(Op.TRACE, 3, TraceKind.NODE_ENTER)
        emit(Op.HALT)
    }

    private fun run(c: Chunk, interp: Interpreter): Fiber {
        val f = Fiber(1, "t", c)
        interp.resume(f, Long.MAX_VALUE)
        return f
    }

    // ---- breakpoints -------------------------------------------------------------------------------

    @Test
    fun `execution stops at an armed node and resumes past it`() {
        val interp = Interpreter(HostRegistry())
        interp.breakpoints.add(2)
        val f = run(threeNodes(), interp)

        assertEquals(FiberState.PAUSED, f.state)
        assertEquals(2, f.currentNode, "it should stop ON the node, not after it")
        assertEquals(PauseReason.BREAKPOINT, f.pauseReason)

        interp.resumeFromPause(f)
        interp.resume(f, Long.MAX_VALUE)
        assertEquals(FiberState.DONE, f.state)
    }

    @Test
    fun `a hit condition stops on the Nth arrival, not the first`() {
        // The reason activations have to be counted from the start: "break on the 47th time round the loop"
        // is unanswerable without them.
        val loop = chunk("loop") {
            val i = reg()
            emit(Op.CONST, i, constant(0))
            val top = here()
            emit(Op.TRACE, 7, TraceKind.NODE_ENTER)
            emit(Op.ADD, i, i, i)
            emit(Op.JMP, top)
        }
        val interp = Interpreter(HostRegistry())
        interp.breakpoints.add(7, hitCount = 3)
        val f = run(loop, interp)

        assertEquals(FiberState.PAUSED, f.state)
        assertEquals(3, interp.breakpoints[7]!!.hits)
    }

    @Test
    fun `a disabled breakpoint still counts arrivals`() {
        // So a hit condition means "the 47th time control reached here", not "the 47th time you had this
        // switched on" — the former is what you were counting when you set it.
        val bps = Breakpoints()
        bps.add(4, enabled = false)
        repeat(5) { assertFalse(bps.shouldBreak(4)) }
        assertEquals(5, bps[4]!!.hits)
    }

    @Test
    fun `toggle adds then removes`() {
        val bps = Breakpoints()
        assertTrue(bps.toggle(9))
        assertTrue(9 in bps)
        assertFalse(bps.toggle(9))
        assertFalse(9 in bps)
    }

    // ---- stepping ----------------------------------------------------------------------------------

    @Test
    fun `a step stops at the next node and is consumed`() {
        val interp = Interpreter(HostRegistry())
        interp.breakpoints.add(1)
        val f = run(threeNodes(), interp)
        assertEquals(1, f.currentNode)

        interp.requestStep(f, StepMode.INTO)
        interp.resume(f, Long.MAX_VALUE)
        assertEquals(FiberState.PAUSED, f.state)
        assertEquals(2, f.currentNode)
        assertEquals(PauseReason.STEP, f.pauseReason)
        assertEquals(StepMode.NONE, f.stepMode, "a step is one request, not a mode left switched on")

        // Continuing after a step runs to the end rather than stopping again.
        interp.resumeFromPause(f)
        interp.resume(f, Long.MAX_VALUE)
        assertEquals(FiberState.DONE, f.state)
    }

    @Test
    fun `ordinary stepping skips the data pull, and step-into-data walks it`() {
        // The distinction that is specific to node graphs: exec pins push, data pins pull, and between two
        // visible exec steps there is a whole hidden tree of pure evaluation. Stepping through nine
        // Literals to get from one Branch to the next is how a debugger becomes unusable.
        val mixed = chunk("mixed") {
            emit(Op.TRACE, 1, TraceKind.NODE_ENTER)
            emit(Op.TRACE, 50, TraceKind.PURE_ENTER)
            emit(Op.TRACE, 51, TraceKind.PURE_ENTER)
            emit(Op.TRACE, 2, TraceKind.NODE_ENTER)
            emit(Op.HALT)
        }

        val over = Interpreter(HostRegistry()).also { it.breakpoints.add(1) }
        val a = run(mixed, over)
        over.requestStep(a, StepMode.OVER)
        over.resume(a, Long.MAX_VALUE)
        assertEquals(2, a.currentNode, "a plain step lands on the next EXEC node")

        val into = Interpreter(HostRegistry()).also { it.breakpoints.add(1) }
        val b = run(mixed, into)
        into.requestStep(b, StepMode.INTO_DATA)
        into.resume(b, Long.MAX_VALUE)
        assertEquals(50, b.currentNode, "stepping into data lands on the first pure node")
    }

    @Test
    fun `a breakpoint on a pure node trips during the pull`() {
        val mixed = chunk("mixed") {
            emit(Op.TRACE, 1, TraceKind.NODE_ENTER)
            emit(Op.TRACE, 50, TraceKind.PURE_ENTER)
            emit(Op.HALT)
        }
        val interp = Interpreter(HostRegistry())
        interp.breakpoints.add(50)
        val f = run(mixed, interp)
        assertEquals(FiberState.PAUSED, f.state)
        assertEquals(50, f.currentNode)
    }

    // ---- break on error ----------------------------------------------------------------------------

    @Test
    fun `a fault is held on the failing node when break-on-error is on`() {
        val bad = chunk("bad") {
            val list = reg()
            val idx = reg()
            val out = reg()
            emit(Op.TRACE, 11, TraceKind.NODE_ENTER)
            emit(Op.NEWLIST, list)
            emit(Op.CONST, idx, constant(3))
            emit(Op.INDEX, out, list, idx)
            emit(Op.HALT)
        }
        val interp = Interpreter(HostRegistry())
        interp.breakOnError = true
        val f = run(bad, interp)

        assertEquals(FiberState.PAUSED, f.state)
        assertEquals(PauseReason.ERROR, f.pauseReason)
        assertEquals(11, f.currentNode, "you land on the node that faulted")
        assertNotNull(f.error)
        assertTrue(f.frames.isNotEmpty(), "the stack is still there to inspect")

        // Continuing cannot un-fault the instruction, so it completes the failure instead of pretending.
        interp.resumeFromPause(f)
        assertEquals(FiberState.FAILED, f.state)
        assertNotNull(f.error)
    }

    @Test
    fun `without break-on-error a fault unwinds as before`() {
        val bad = chunk("bad") {
            val out = reg()
            emit(Op.INDEX, out, reg(), reg())
            emit(Op.HALT)
        }
        val f = run(bad, Interpreter(HostRegistry()))
        assertEquals(FiberState.FAILED, f.state)
    }

    // ---- inspection --------------------------------------------------------------------------------

    @Test
    fun `the slot map names the registers holding node outputs`() {
        // The whole debug format: nodeId+pin -> register. Everything the inspector shows is a read through
        // this, which is why it has to come out of the compiler rather than be guessed at afterwards.
        val slots = SlotMap(outputs = mapOf((3 to "Result") to 5), variables = mapOf("count" to 1))
        val c = chunk("x") { emit(Op.HALT) }
        val withSlots = Chunk(c.name, c.code, c.constants, maxRegs = 8, slots = slots)
        val f = Fiber(1, "t", withSlots)
        f.stack[5] = 42
        f.stack[1] = 7

        assertEquals(5, withSlots.slots.outputs[3 to "Result"])
        assertEquals(42, f.stack[withSlots.slots.outputs.getValue(3 to "Result")])
        assertEquals(7, f.stack[withSlots.slots.variables.getValue("count")])
    }

    @Test
    fun `the frame stack is enumerable while paused`() {
        // The reason the VM keeps an explicit frame list rather than using continuations: enumerating it IS
        // the call stack panel.
        val interp = Interpreter(HostRegistry())
        interp.breakpoints.add(2)
        val f = run(threeNodes(), interp)

        val trace = f.stackTrace()
        assertEquals(1, trace.size)
        assertTrue(trace.single().contains("nodes"), trace.toString())
    }
}
