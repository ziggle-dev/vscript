package dev.ziggle.vscript.vm

import dev.ziggle.vscript.host.Clock

/** A hand-advanced clock, so every wait in these tests is deterministic and instant. */
class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

/**
 * An [ActuatorSink] that holds offered work until a test runs it — the stand-in for the client's actuator
 * drain thread, without a thread.
 *
 * [dropFirst] reproduces the failure that motivated the re-offer logic: the real actuator slot holds one
 * intent and silently discards an offer at equal-or-lower priority, so work can vanish and the waiter block
 * forever. Setting it makes the sink swallow the first N offers.
 */
class ManualActuator(var dropFirst: Int = 0) : ActuatorSink {
    private val queue = ArrayDeque<() -> Unit>()

    /** Every offer received, including duplicates from re-offering. */
    var offersReceived = 0
        private set

    override fun offer(label: String, work: () -> Unit) {
        offersReceived++
        if (dropFirst > 0) {
            dropFirst--
            return
        }
        queue.addLast(work)
    }

    /** True while there is queued work. */
    fun hasWork(): Boolean = queue.isNotEmpty()

    /** Run every queued item, as the drain thread would. */
    fun drain(): Int {
        var n = 0
        while (queue.isNotEmpty()) {
            queue.removeFirst().invoke()
            n++
        }
        return n
    }
}

/**
 * Drive [entry] to completion (or [maxTicks]), advancing [clock] to the next due wake whenever every fiber
 * is parked — so a `SLEEP` costs no real time.
 */
fun drive(
    entry: Chunk,
    hosts: HostRegistry = HostRegistry(),
    clock: FakeClock = FakeClock(),
    actuator: ActuatorSink? = null,
    maxTicks: Int = 10_000,
    /** Arguments for a chunk that takes them — a function body run on its own, as `graph call` does. */
    args: List<Any?> = emptyList(),
    configure: (Scheduler) -> Unit = {},
): DriveResult {
    val interpreter = Interpreter(hosts, clock, actuator)
    // Graph variables live in the run's globals, so a chunk driven straight from a test needs them seeded
    // exactly as ScriptRuntime does it — otherwise every variable reads null.
    interpreter.resetGlobals(entry.globals)
    val scheduler = Scheduler(interpreter, clock).also(configure)
    val fiber = scheduler.spawn("test", entry, args)
    var ticks = 0
    while (!fiber.isFinished && ticks < maxTicks) {
        ticks++
        scheduler.tick()
        if (fiber.state == FiberState.PAUSED) break
        if (!fiber.isFinished) scheduler.nextWakeMs()?.let { clock.now += it }
    }
    return DriveResult(fiber, scheduler, interpreter, ticks)
}

class DriveResult(
    val fiber: Fiber,
    val scheduler: Scheduler,
    val interpreter: Interpreter,
    val ticks: Int,
) {
    /** The fiber's single return value. Fails loudly if the fiber did not finish cleanly. */
    fun value(): Any? {
        check(fiber.state == FiberState.DONE) {
            "fiber did not complete: ${fiber.state}" + (fiber.error?.let { " — ${it.message}" } ?: "")
        }
        return fiber.result.firstOrNull()
    }
}
