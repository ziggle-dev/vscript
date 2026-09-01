package dev.ziggle.vscript.vm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import dev.ziggle.vscript.vm.FiberState
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Execution semantics of the bytecode interpreter, exercised against hand-assembled chunks.
 *
 * Hand-assembly is the point of this milestone: with no compiler yet, a failure here is unambiguously a VM
 * bug. Once the compiler lands, these fixtures stay as the layer that says which side of the boundary broke.
 */
class InterpreterTest {

    @Test
    fun `arithmetic promotes int to long to double`() {
        assertEquals(5, evalBinary(Op.ADD, 2, 3))
        assertEquals(6L, evalBinary(Op.MUL, 2L, 3))
        assertEquals(2.5, evalBinary(Op.DIV, 5.0, 2))
        assertEquals(1, evalBinary(Op.MOD, 7, 3))
        assertEquals(-4, evalBinary(Op.SUB, 3, 7))
    }

    @Test
    fun `ADD concatenates when either side is a string`() {
        assertEquals("hp: 42", evalBinary(Op.ADD, "hp: ", 42))
        assertEquals("42 hp", evalBinary(Op.ADD, 42, " hp"))
    }

    @Test
    fun `integer division by zero is a VmError, not an ArithmeticException`() {
        val r = drive(chunk("divzero") {
            val out = reg(); val x = reg(); val y = reg()
            emit(Op.CONST, x, constant(1))
            emit(Op.CONST, y, constant(0))
            emit(Op.DIV, out, x, y)
            emit(Op.RET, out, 1)
        })
        assertEquals(FiberState.FAILED, r.fiber.state)
        assertContains(r.fiber.error!!.message, "division by zero")
    }

    @Test
    fun `equality compares numbers across types`() {
        // An Int literal wired into a pin fed by a Long-returning node must still match; otherwise the
        // graph reads as correct and silently never fires.
        assertEquals(true, evalBinary(Op.EQ, 1, 1L))
        assertEquals(true, evalBinary(Op.EQ, 2.0, 2))
        assertEquals(false, evalBinary(Op.EQ, 1, 2))
        assertEquals(true, evalBinary(Op.NE, "a", "b"))
    }

    @Test
    fun `conditional jump on a non-boolean fails loudly as a compiler bug`() {
        val r = drive(chunk("badcond") {
            val v = reg()
            emit(Op.CONST, v, constant(7))
            emit(Op.JMPF, v, 0)
            emit(Op.HALT)
        })
        assertEquals(FiberState.FAILED, r.fiber.state)
        assertContains(r.fiber.error!!.message, "compiler bug")
    }

    @Test
    fun `a counted loop runs to its bound`() {
        val r = drive(countTo(5))
        assertEquals(5, r.value())
    }

    @Test
    fun `host functions receive args and return a value`() {
        val hosts = HostRegistry().register("double", arity = 1) { args -> (args[0] as Int) * 2 }
        val r = drive(
            chunk("call") {
                val h = host("double")
                val base = regs(1)
                emit(Op.CONST, base, constant(21))
                emit(Op.CALL, h, base, Op.packCounts(1, 1))
                emit(Op.RET, base, 1)
            },
            hosts,
        )
        assertEquals(42, r.value())
    }

    @Test
    fun `a multi-result host function is spread across the result window`() {
        val hosts = HostRegistry().register("pair", results = 2) { arrayOf<Any?>("a", 7) }
        val r = drive(
            chunk("multi") {
                val h = host("pair")
                val base = regs(2)
                emit(Op.CALL, h, base, Op.packCounts(0, 2))
                emit(Op.RET, base, 2)
            },
            hosts,
        )
        assertEquals(listOf<Any?>("a", 7), r.fiber.result)
    }

    @Test
    fun `an unknown host function is caught when the chunk is bound, not mid-run`() {
        val e = assertFailsWith<VmError> {
            Interpreter(HostRegistry()).resume(
                Fiber(1, "f", chunk("c") { host("missing"); emit(Op.HALT) }),
                Long.MAX_VALUE,
            )
        }
        assertContains(e.message, "unknown host function 'missing'")
    }

    @Test
    fun `a throwing host function becomes a VmError carrying the node id`() {
        val hosts = HostRegistry().register("boom") { error("kaboom") }
        val r = drive(
            chunk("throws") {
                val h = host("boom")
                currentNodeId = 77
                val base = regs(1)
                emit(Op.CALL, h, base, Op.packCounts(0, 1))
                emit(Op.RET, base, 1)
            },
            hosts,
        )
        assertEquals(FiberState.FAILED, r.fiber.state)
        assertContains(r.fiber.error!!.message, "kaboom")
        assertEquals(77, r.fiber.error!!.nodeId)
    }

    @Test
    fun `graph function calls pass args in place and return through the same window`() {
        val triple = chunk("triple", paramCount = 1) {
            val out = reg(); val three = reg()
            emit(Op.CONST, three, constant(3))
            emit(Op.MUL, out, 0, three)
            emit(Op.RET, out, 1)
        }
        val r = drive(chunk("caller") {
            val idx = subChunk(triple)
            val base = regs(1)
            emit(Op.CONST, base, constant(7))
            emit(Op.CALLG, idx, base, Op.packCounts(1, 1))
            emit(Op.RET, base, 1)
        })
        assertEquals(21, r.value())
    }

    @Test
    fun `calling a graph function with the wrong arity fails`() {
        val one = chunk("one", paramCount = 1) { emit(Op.RET, 0, 1) }
        val r = drive(chunk("caller") {
            val idx = subChunk(one)
            val base = regs(2)
            emit(Op.CALLG, idx, base, Op.packCounts(2, 1))
            emit(Op.RET, base, 1)
        })
        assertEquals(FiberState.FAILED, r.fiber.state)
        assertContains(r.fiber.error!!.message, "takes 1 args, called with 2")
    }

    @Test
    fun `the call depth cap stops runaway recursion`() {
        // Built as a nested chain rather than true self-recursion, which the builder cannot express —
        // the cap is on frame depth, so a chain exercises exactly the same guard.
        var current = chunk("leaf") {
            val r = reg(); emit(Op.CONST, r, constant(0)); emit(Op.RET, r, 1)
        }
        repeat(6) { d ->
            val inner = current
            current = chunk("level$d") {
                val idx = subChunk(inner)
                val base = regs(1)
                emit(Op.CALLG, idx, base, Op.packCounts(0, 1))
                emit(Op.RET, base, 1)
            }
        }
        val hosts = HostRegistry()
        val clock = FakeClock()
        val interpreter = Interpreter(hosts, clock).also { it.maxCallDepth = 3 }
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn("deep", current)
        repeat(10) { scheduler.tick() }

        assertEquals(FiberState.FAILED, fiber.state)
        assertContains(fiber.error!!.message, "call depth limit (3) exceeded")
    }

    @Test
    fun `lists support build, index, length and iteration`() {
        val r = drive(chunk("sumlist") {
            val list = reg(); val v = reg(); val sum = reg(); val it0 = reg(); val cur = reg()
            emit(Op.NEWLIST, list)
            for (n in intArrayOf(3, 4, 5)) {
                emit(Op.CONST, v, constant(n))
                emit(Op.APPEND, list, v)
            }
            emit(Op.CONST, sum, constant(0))
            emit(Op.ITER, it0, list)
            val top = here()
            val exit = emit(Op.ITERNEXT, cur, it0, 0)
            emit(Op.ADD, sum, sum, cur)
            emit(Op.JMP, top)
            patch(exit)
            emit(Op.RET, sum, 1)
        })
        assertEquals(12, r.value())
    }

    @Test
    fun `iterating an unwired list visits nothing instead of failing`() {
        // null is exactly what an unconnected List pin supplies. A For Each with nothing plugged into it
        // should visit nothing, not report the graph as broken — the graph is unfinished, not wrong.
        val r = drive(chunk("nulliter") {
            val list = reg(); val it0 = reg(); val cur = reg(); val n = reg()
            emit(Op.CONST, list, constant(null))
            emit(Op.CONST, n, constant(0))
            emit(Op.ITER, it0, list)
            val top = here()
            val exit = emit(Op.ITERNEXT, cur, it0, 0)
            emit(Op.ADD, n, n, cur)
            emit(Op.JMP, top)
            patch(exit)
            emit(Op.RET, n, 1)
        })
        assertEquals(0, r.value())
    }

    @Test
    fun `length of an unwired list is zero`() {
        val r = drive(chunk("nulllen") {
            val list = reg(); val out = reg()
            emit(Op.CONST, list, constant(null))
            emit(Op.LEN, out, list)
            emit(Op.RET, out, 1)
        })
        assertEquals(0, r.value())
    }

    @Test
    fun `appending to a non-list is still an error`() {
        // The reads are lenient; APPEND is not. Adding to nothing is a real mistake, not an unfinished wire.
        val r = drive(chunk("nullappend") {
            val list = reg(); val v = reg()
            emit(Op.CONST, list, constant(null))
            emit(Op.CONST, v, constant(1))
            emit(Op.APPEND, list, v)
            emit(Op.HALT)
        })
        assertEquals(FiberState.FAILED, r.fiber.state)
        assertContains(r.fiber.error!!.message, "expected a List")
    }

    @Test
    fun `an out-of-bounds list index reports the size`() {
        val r = drive(chunk("oob") {
            val list = reg(); val i = reg(); val out = reg()
            emit(Op.NEWLIST, list)
            emit(Op.CONST, i, constant(2))
            emit(Op.INDEX, out, list, i)
            emit(Op.RET, out, 1)
        })
        assertEquals(FiberState.FAILED, r.fiber.state)
        assertContains(r.fiber.error!!.message, "index 2 out of bounds (size 0)")
    }

    @Test
    fun `a VmError carries a stack trace naming each chunk`() {
        val inner = chunk("inner") {
            val a = reg(); val b = reg(); val out = reg()
            emit(Op.CONST, a, constant(1))
            emit(Op.CONST, b, constant(0))
            emit(Op.DIV, out, a, b)
            emit(Op.RET, out, 1)
        }
        val r = drive(chunk("outer") {
            val idx = subChunk(inner)
            val base = regs(1)
            emit(Op.CALLG, idx, base, Op.packCounts(0, 1))
            emit(Op.RET, base, 1)
        })
        val msg = r.fiber.error!!.message
        assertContains(msg, "inner@")
        assertContains(msg, "outer@")
    }

    @Test
    fun `the disassembler renders a chunk readably`() {
        val text = Disassembler.disassemble(countTo(3))
        assertContains(text, "CONST")
        assertContains(text, "JMPF")
        assertTrue(text.lineSequence().first().contains("chunk 'countTo'"))
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** `k(l) <op> k(r)`, returned. */
    private fun evalBinary(op: Int, l: Any?, rhs: Any?): Any? = drive(chunk("bin") {
        val out = reg(); val a = reg(); val b = reg()
        emit(Op.CONST, a, constant(l))
        emit(Op.CONST, b, constant(rhs))
        emit(op, out, a, b)
        emit(Op.RET, out, 1)
    }).value()

    /** `for (i = 0; i < n; i++); return i` — the canonical loop fixture. */
    private fun countTo(n: Int): Chunk = chunk("countTo") {
        val i = reg(); val limit = reg(); val cond = reg(); val one = reg()
        emit(Op.CONST, i, constant(0))
        emit(Op.CONST, limit, constant(n))
        emit(Op.CONST, one, constant(1))
        val top = here()
        emit(Op.LT, cond, i, limit)
        val exit = emit(Op.JMPF, cond, 0)
        emit(Op.ADD, i, i, one)
        emit(Op.JMP, top)
        patch(exit)
        emit(Op.RET, i, 1)
    }
}
