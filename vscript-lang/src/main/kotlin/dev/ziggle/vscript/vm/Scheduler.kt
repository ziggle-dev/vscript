package dev.ziggle.vscript.vm

import dev.ziggle.vscript.host.Clock
import dev.ziggle.vscript.host.SystemClock

/**
 * Runs every live fiber, one scheduler pass per client tick.
 *
 * The pass is given a wall-clock **budget** and hands it out round-robin. Two things make this the shape it
 * is rather than "run each fiber until it parks":
 *
 *  - **The client thread is not ours.** `PluginTicks` warns above 8ms and force-stops the host plugin whose
 *    hook exceeds 16ms for five consecutive ticks. The default 3ms budget leaves generous headroom for the
 *    editor's own render work in the same frame.
 *  - **Fairness.** The starting fiber rotates each pass, so a fiber that always consumes the whole budget
 *    cannot starve the ones behind it.
 *
 * Everything here runs on the client thread, which is also where the editor renders — so the debugger reads
 * fiber state directly, with no snapshot and no locking.
 */
class Scheduler(
    private val interpreter: Interpreter,
    private val clock: Clock = SystemClock,
    /** Wall-clock nanoseconds one [tick] may spend interpreting. */
    var budgetNanos: Long = DEFAULT_BUDGET_NANOS,
    /**
     * Consecutive passes a fiber may be preempted without ever parking before it is treated as a runaway
     * loop and suspended. A graph with `While(true)` and no wait inside is easy to build by accident, and
     * without this it would silently consume the whole budget every tick forever.
     */
    /**
     * Consecutive budget preemptions, with no park in between, before a fiber is failed as runaway.
     *
     * **Raised from 100 when `while` stopped yielding per iteration.** A preemption is one 3ms budget, so
     * 100 of them is 300 milliseconds of actual computation — which was ample while every computational
     * `while` yielded after one iteration and could therefore never accumulate them, and is far too tight
     * now that a loop is allowed to just run. A quadratic pass over a few hundred items is ordinary work
     * and would have been killed as a runaway.
     *
     * 1000 is three seconds of solid compute, which no reasonable script needs and every genuine infinite
     * loop still exceeds — it simply takes longer to say so.
     */
    var runawayPreemptionLimit: Int = 1_000,
    /** Called when a fiber is suspended as a runaway, so the editor can point at the offending node. */
    private val onRunaway: ((Fiber) -> Unit)? = null,
) {
    private val _fibers = ArrayList<Fiber>()
    private var nextId = 1
    private var rotation = 0

    /** Every fiber the scheduler knows about, finished ones included until [reap]. */
    val fibers: List<Fiber> get() = _fibers

    /** Start [entry] as a new fiber and return it. */
    fun spawn(name: String, entry: Chunk, args: List<Any?> = emptyList()): Fiber {
        val f = Fiber(nextId++, name, entry, args)
        _fibers.add(f)
        return f
    }

    /** Stop [fiber] immediately, without running any more of it. */
    fun kill(fiber: Fiber) {
        if (!fiber.isFinished) {
            fiber.state = FiberState.DONE
            fiber.pendingAct = null
        }
    }

    /** Drop finished fibers and return how many were removed. */
    fun reap(): Int {
        val before = _fibers.size
        _fibers.removeAll { it.isFinished }
        return before - _fibers.size
    }

    /**
     * One scheduler pass. Call from the host plugin's client-thread hook.
     *
     * Returns the number of fibers that actually ran, which is a useful "is anything happening" signal for
     * the editor and costs nothing to produce.
     */
    fun tick(): Int {
        if (_fibers.isEmpty()) return 0
        val deadline = System.nanoTime() + budgetNanos
        val now = clock.nowMs()
        var ran = 0

        // Rotate the starting point so the same fiber does not always get the fresh budget.
        val n = _fibers.size
        rotation = if (n == 0) 0 else (rotation + 1) % n

        for (i in 0 until n) {
            val fiber = _fibers[(rotation + i) % n]
            if (fiber.isFinished) continue

            // An importer waits for its libraries to have had their turn — see [Fiber.waitsFor]. Checked
            // before the state switch, so a fiber that is merely RUNNABLE is held rather than resumed.
            if (fiber.waitsFor.any { !it.isSettled }) continue

            when (fiber.state) {
                FiberState.PAUSED -> continue // only the debugger releases it

                FiberState.PARKED -> {
                    if (now < fiber.resumeAtMs) continue
                    fiber.state = FiberState.RUNNABLE
                }

                FiberState.AWAITING_ACT -> {
                    // Re-offers internally when the result has not landed — see PendingAct.
                    if (!interpreter.tryCompleteAct(fiber)) continue
                }

                FiberState.RUNNABLE -> Unit
                FiberState.DONE, FiberState.FAILED -> continue
            }

            // Out of budget: leave the rest runnable for the next pass rather than overrunning.
            //
            // `ran > 0` guarantees forward progress. Without it a budget smaller than the time taken to
            // reach this check means the deadline has *already* passed on the first iteration, every
            // fiber is skipped, and the VM silently does nothing forever. At least one fiber always gets
            // resumed per pass; the interpreter's own budget check then preempts it promptly.
            if (ran > 0 && System.nanoTime() >= deadline) break

            ran++
            when (interpreter.resume(fiber, deadline)) {
                StepResult.PREEMPTED ->
                    if (fiber.consecutivePreemptions >= runawayPreemptionLimit) {
                        fiber.error = VmError(
                            "runaway fiber '${fiber.name}': ran for $runawayPreemptionLimit consecutive " +
                                "ticks without waiting — a loop with no Delay/Wait in it?",
                            fiber.top?.chunk?.name, fiber.top?.pc ?: -1, fiber.currentNodeId(), fiber.stackTrace(),
                        )
                        fiber.state = FiberState.FAILED
                        onRunaway?.invoke(fiber)
                    }

                StepResult.PARKED, StepResult.AWAITING_ACT, StepResult.PAUSED,
                StepResult.FINISHED, StepResult.FAILED -> Unit
            }
        }
        return ran
    }

    /** Milliseconds until the earliest parked fiber is due, or null when nothing is waiting on the clock. */
    fun nextWakeMs(): Long? = _fibers
        .filter { it.state == FiberState.PARKED }
        .minOfOrNull { (it.resumeAtMs - clock.nowMs()).coerceAtLeast(0L) }

    companion object {
        /**
         * 3ms — comfortably under `PluginTicks`' 8ms warn threshold, leaving room for the editor's render
         * pass and everything else on the client thread in the same frame.
         */
        const val DEFAULT_BUDGET_NANOS = 3_000_000L
    }
}
