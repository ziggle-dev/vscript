package dev.ziggle.vscript.vm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Suspension, preemption and scheduling — the properties that let the VM live on the client thread.
 *
 * These are the tests that matter most for this milestone. Arithmetic being right is table stakes; a fiber
 * that cannot be interrupted, or that hangs waiting on a dropped actuator offer, breaks the whole client.
 */
class SchedulerTest {

    @Test
    fun `YIELD parks the fiber until the next scheduler pass`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "yielder",
            chunk("yielder") {
                val out = reg()
                emit(Op.YIELD)
                emit(Op.CONST, out, constant(9))
                emit(Op.RET, out, 1)
            },
        )

        scheduler.tick()
        assertEquals(FiberState.PARKED, fiber.state, "YIELD should park, not finish")

        scheduler.tick()
        assertEquals(FiberState.DONE, fiber.state)
        assertEquals(9, fiber.result.single())
    }

    @Test
    fun `SLEEP holds the fiber until its deadline on the clock`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "sleeper",
            chunk("sleeper") {
                val ms = reg(); val out = reg()
                emit(Op.CONST, ms, constant(500L))
                emit(Op.SLEEP, ms)
                emit(Op.CONST, out, constant("awake"))
                emit(Op.RET, out, 1)
            },
        )

        scheduler.tick()
        assertEquals(FiberState.PARKED, fiber.state)
        assertEquals(500L, scheduler.nextWakeMs())

        clock.now = 499
        scheduler.tick()
        assertEquals(FiberState.PARKED, fiber.state, "must not wake a millisecond early")

        clock.now = 500
        scheduler.tick()
        assertEquals(FiberState.DONE, fiber.state)
        assertEquals("awake", fiber.result.single())
    }

    @Test
    fun `a long computation is preempted rather than overrunning the budget`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        // A budget of 0 expires at the first check, which happens one BUDGET_CHECK_INTERVAL in.
        val scheduler = Scheduler(interpreter, clock, budgetNanos = 0)
        val fiber = scheduler.spawn("spin", spinFor(100_000))

        scheduler.tick()

        assertEquals(FiberState.RUNNABLE, fiber.state, "preemption leaves the fiber runnable")
        assertTrue(fiber.instructionsRetired > 0, "should have made progress before being preempted")
        assertTrue(
            fiber.instructionsRetired <= Interpreter.BUDGET_CHECK_INTERVAL + 1,
            "should stop at the first budget check, retired ${fiber.instructionsRetired}",
        )
    }

    @Test
    fun `a preempted fiber resumes exactly where it stopped`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock, budgetNanos = 0)
        val fiber = scheduler.spawn("spin", spinFor(300))

        var passes = 0
        while (!fiber.isFinished && passes < 10_000) {
            scheduler.tick()
            passes++
        }

        assertEquals(FiberState.DONE, fiber.state)
        assertEquals(300, fiber.result.single(), "the loop must not lose or repeat iterations across preemptions")
        assertTrue(passes > 1, "the fixture should have needed several passes")
    }

    @Test
    fun `a loop with no wait is suspended as a runaway instead of burning every tick forever`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock, budgetNanos = 0, runawayPreemptionLimit = 3)
        val fiber = scheduler.spawn(
            "spinner",
            chunk("spinner") {
                currentNodeId = 12
                val top = here()
                emit(Op.JMP, top)
            },
        )

        repeat(5) { scheduler.tick() }

        assertEquals(FiberState.FAILED, fiber.state)
        assertContains(fiber.error!!.message, "runaway fiber")
        assertEquals(12, fiber.error!!.nodeId, "the error must point at the offending node")
    }

    @Test
    fun `parking resets the runaway counter`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock, budgetNanos = 0, runawayPreemptionLimit = 3)
        // A loop that DOES yield each pass is legitimate and must never be flagged.
        val fiber = scheduler.spawn(
            "polite",
            chunk("polite") {
                val i = reg(); val limit = reg(); val one = reg(); val cond = reg()
                emit(Op.CONST, i, constant(0))
                emit(Op.CONST, limit, constant(20))
                emit(Op.CONST, one, constant(1))
                val top = here()
                emit(Op.LT, cond, i, limit)
                val exit = emit(Op.JMPF, cond, 0)
                emit(Op.ADD, i, i, one)
                emit(Op.YIELD)
                emit(Op.JMP, top)
                patch(exit)
                emit(Op.RET, i, 1)
            },
        )

        repeat(200) { scheduler.tick() }

        assertEquals(FiberState.DONE, fiber.state)
        assertEquals(20, fiber.result.single())
    }

    @Test
    fun `ACT suspends the fiber and resumes it with the drain's result`() {
        val clock = FakeClock()
        val actuator = ManualActuator()
        val hosts = HostRegistry().register("walkTo", HostKind.BLOCKING, arity = 1) { args -> "walked to ${args[0]}" }
        val interpreter = Interpreter(hosts, clock, actuator)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "walker",
            chunk("walker") {
                val h = host("walkTo")
                val base = regs(1)
                emit(Op.CONST, base, constant("bank"))
                emit(Op.ACT, h, base, Op.packCounts(1, 1))
                emit(Op.RET, base, 1)
            },
        )

        scheduler.tick()
        assertEquals(FiberState.AWAITING_ACT, fiber.state, "a blocking call must not run on the client thread")
        assertTrue(actuator.hasWork(), "the work should be sitting on the drain")

        // Nothing happens while the drain has not run — the fiber must not spin ahead.
        scheduler.tick()
        assertEquals(FiberState.AWAITING_ACT, fiber.state)

        actuator.drain()
        scheduler.tick()
        assertEquals(FiberState.DONE, fiber.state)
        assertEquals("walked to bank", fiber.result.single())
    }

    @Test
    fun `a dropped actuator offer is re-offered instead of hanging the fiber forever`() {
        // Reproduces the live failure that motivated the re-offer logic: the actuator slot holds one intent
        // and discards an equal-priority offer, so a single offer can vanish. ScriptRunner froze mid-fight
        // for 32 seconds on exactly this.
        val clock = FakeClock()
        val actuator = ManualActuator(dropFirst = 3)
        val hosts = HostRegistry().register("eat", HostKind.BLOCKING) { "ate" }
        val interpreter = Interpreter(hosts, clock, actuator)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "eater",
            chunk("eater") {
                val h = host("eat")
                val base = regs(1)
                emit(Op.ACT, h, base, Op.packCounts(0, 1))
                emit(Op.RET, base, 1)
            },
        )

        var passes = 0
        while (!fiber.isFinished && passes < 50) {
            scheduler.tick()
            actuator.drain()
            passes++
        }

        assertEquals(FiberState.DONE, fiber.state, "the fiber must survive dropped offers")
        assertEquals("ate", fiber.result.single())
        assertTrue(actuator.offersReceived > 3, "should have re-offered past the drops")
    }

    @Test
    fun `duplicate offers execute the work exactly once`() {
        val clock = FakeClock()
        val actuator = ManualActuator()
        var invocations = 0
        val hosts = HostRegistry().register("tally", HostKind.BLOCKING) { invocations++; invocations }
        val interpreter = Interpreter(hosts, clock, actuator)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "counter",
            chunk("counter") {
                val h = host("tally")
                val base = regs(1)
                emit(Op.ACT, h, base, Op.packCounts(0, 1))
                emit(Op.RET, base, 1)
            },
        )

        scheduler.tick()          // offers once, suspends
        scheduler.tick()          // re-offers (result not in yet)
        scheduler.tick()          // re-offers again
        assertTrue(actuator.offersReceived >= 3)

        actuator.drain()          // runs every queued copy
        scheduler.tick()

        assertEquals(FiberState.DONE, fiber.state)
        assertEquals(1, invocations, "the started-guard must make duplicate offers no-ops")
    }

    @Test
    fun `with no actuator a blocking call runs inline`() {
        // The bare/headless mode, matching ScriptRunner.act's legacy behaviour.
        val hosts = HostRegistry().register("read", HostKind.BLOCKING) { "value" }
        val r = drive(
            chunk("inline") {
                val h = host("read")
                val base = regs(1)
                emit(Op.ACT, h, base, Op.packCounts(0, 1))
                emit(Op.RET, base, 1)
            },
            hosts,
        )
        assertEquals("value", r.value())
    }

    @Test
    fun `an ACT that throws on the drain fails the fiber rather than losing the error`() {
        val clock = FakeClock()
        val actuator = ManualActuator()
        val hosts = HostRegistry().register("bad", HostKind.BLOCKING) { error("drain blew up") }
        val interpreter = Interpreter(hosts, clock, actuator)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "bad",
            chunk("bad") {
                val h = host("bad")
                val base = regs(1)
                emit(Op.ACT, h, base, Op.packCounts(0, 1))
                emit(Op.RET, base, 1)
            },
        )

        scheduler.tick()
        actuator.drain()
        scheduler.tick()

        assertEquals(FiberState.FAILED, fiber.state)
        assertContains(fiber.error!!.message, "drain blew up")
    }

    @Test
    fun `BREAK pauses the fiber and only the debugger releases it`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "paused",
            chunk("paused") {
                val out = reg()
                emit(Op.BREAK, 5)
                emit(Op.CONST, out, constant("after"))
                emit(Op.RET, out, 1)
            },
        )

        scheduler.tick()
        assertEquals(FiberState.PAUSED, fiber.state)

        repeat(5) { scheduler.tick() }
        assertEquals(FiberState.PAUSED, fiber.state, "a paused fiber must not resume on its own")

        interpreter.resumeFromPause(fiber)
        scheduler.tick()
        assertEquals(FiberState.DONE, fiber.state)
        assertEquals("after", fiber.result.single())
    }

    @Test
    fun `a breakpoint armed on a node id trips at that node's entry marker`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        interpreter.breakpoints.add(42)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(
            "traced",
            chunk("traced") {
                val out = reg()
                emit(Op.TRACE, 41, TraceKind.NODE_ENTER)
                emit(Op.CONST, out, constant(1))
                emit(Op.TRACE, 42, TraceKind.NODE_ENTER)
                emit(Op.CONST, out, constant(2))
                emit(Op.RET, out, 1)
            },
        )

        scheduler.tick()
        assertEquals(FiberState.PAUSED, fiber.state)
        assertEquals(42, fiber.currentNodeId())

        interpreter.resumeFromPause(fiber)
        scheduler.tick()
        assertEquals(2, fiber.result.single(), "execution should continue past the breakpoint")
    }

    @Test
    fun `the tracer sees every exec edge in order`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock, tracer = { id, kind, _ -> seen.add(id to kind) })
        val scheduler = Scheduler(interpreter, clock)
        scheduler.spawn(
            "flow",
            chunk("flow") {
                emit(Op.TRACE, 1, TraceKind.NODE_ENTER)
                emit(Op.TRACE, 100, TraceKind.EXEC_EDGE)
                emit(Op.TRACE, 2, TraceKind.NODE_ENTER)
                emit(Op.HALT)
            },
        )
        scheduler.tick()

        assertEquals(
            listOf(1 to TraceKind.NODE_ENTER, 100 to TraceKind.EXEC_EDGE, 2 to TraceKind.NODE_ENTER),
            seen,
        )
    }

    @Test
    fun `concurrent fibers all make progress and the start point rotates`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock)
        val fibers = (1..3).map { n -> scheduler.spawn("f$n", yieldingCounter(3)) }

        var passes = 0
        while (fibers.any { !it.isFinished } && passes < 100) {
            scheduler.tick()
            passes++
        }

        assertTrue(fibers.all { it.state == FiberState.DONE }, "every fiber should finish")
        fibers.forEach { assertEquals(3, it.result.single()) }
    }

    @Test
    fun `reap drops finished fibers and keeps live ones`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock)
        val quick = scheduler.spawn("quick", chunk("quick") { emit(Op.HALT) })
        val slow = scheduler.spawn("slow", yieldingCounter(50))

        scheduler.tick()
        assertEquals(FiberState.DONE, quick.state)
        assertNotEquals(FiberState.DONE, slow.state)

        assertEquals(1, scheduler.reap())
        assertEquals(listOf(slow), scheduler.fibers)
    }

    @Test
    fun `killing a fiber stops it mid-flight`() {
        val clock = FakeClock()
        val interpreter = Interpreter(HostRegistry(), clock)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn("victim", yieldingCounter(100))

        scheduler.tick()
        scheduler.kill(fiber)
        val retiredAtKill = fiber.instructionsRetired
        repeat(5) { scheduler.tick() }

        assertEquals(FiberState.DONE, fiber.state)
        assertEquals(retiredAtKill, fiber.instructionsRetired, "a killed fiber must not execute further")
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    /** A pure spin loop of [n] iterations — no waits, so it can only advance by being resumed. */
    private fun spinFor(n: Int): Chunk = chunk("spin") {
        val i = reg(); val limit = reg(); val one = reg(); val cond = reg()
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

    /** Counts to [n], yielding once per iteration — one tick per step. */
    private fun yieldingCounter(n: Int): Chunk = chunk("counter") {
        val i = reg(); val limit = reg(); val one = reg(); val cond = reg()
        emit(Op.CONST, i, constant(0))
        emit(Op.CONST, limit, constant(n))
        emit(Op.CONST, one, constant(1))
        val top = here()
        emit(Op.LT, cond, i, limit)
        val exit = emit(Op.JMPF, cond, 0)
        emit(Op.ADD, i, i, one)
        emit(Op.YIELD)
        emit(Op.JMP, top)
        patch(exit)
        emit(Op.RET, i, 1)
    }
}
