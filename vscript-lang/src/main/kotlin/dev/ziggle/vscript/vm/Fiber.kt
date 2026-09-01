package dev.ziggle.vscript.vm

/**
 * Why a fiber is not currently executing.
 *
 * The states a *scheduler* must distinguish are the ones with different resume conditions:
 * [PARKED] resumes on a clock deadline, [AWAITING_ACT] on a result box being filled, [RUNNABLE] on the
 * next tick. [PAUSED] never resumes on its own — only the debugger releases it.
 */
enum class FiberState {
    /** Ready to execute on the next scheduler pass. */
    RUNNABLE,

    /** Sleeping until [Fiber.resumeAtMs]. */
    PARKED,

    /** Waiting for an [Op.ACT] result from the actuator drain. */
    AWAITING_ACT,

    /** Stopped at an [Op.BREAK]; resumes only when the debugger says so. */
    PAUSED,

    /** Ran to completion. */
    DONE,

    /** Ended on an uncaught [VmError]; see [Fiber.error]. */
    FAILED,
}

/**
 * What the debugger asked a fiber to do next.
 *
 * A node graph has two evaluation domains, so "step" is genuinely ambiguous and has to be more than one
 * command. [OVER] and [INTO] walk exec nodes — the statement boundaries — running each node's data pull
 * silently; [INTO_DATA] walks that pull one pure node at a time. Defaulting to the first matters: stepping
 * through nine Literals to get from one Branch to the next is how a debugger becomes something people stop
 * using.
 */
enum class StepMode {
    NONE,

    /** Next exec node at this frame depth or shallower — over a subgraph call. */
    OVER,

    /** Next exec node anywhere, including inside a subgraph call. */
    INTO,

    /** Next exec node in a shallower frame — run out of this subgraph. */
    OUT,

    /** Next node boundary of ANY kind, pure evaluation included. */
    INTO_DATA,
}

/** Why a fiber is sitting in [FiberState.PAUSED]. */
enum class PauseReason {
    NONE,
    BREAKPOINT,
    STEP,

    /**
     * The fiber faulted and was held instead of killed.
     *
     * Break-on-error is the highest-value thing a debugger does for almost no cost once pausing works: you
     * land on the failing node with the state still live, rather than reading a log line about it
     * afterwards. Resuming from here cannot continue — the instruction genuinely failed — so it completes
     * the failure instead.
     */
    ERROR,

    /** The user asked everything to stop. */
    REQUEST,
}

/**
 * One activation record. Registers are a *window* into the owning fiber's value stack rather than a
 * per-frame array, so a call passes its arguments simply by leaving them where it built them —
 * the callee's window starts at the argument base (the Lua convention).
 */
class Frame(
    val chunk: Chunk,
    /** Absolute index in [Fiber.stack] of this frame's register 0. */
    val base: Int,
    var pc: Int,
    /** Absolute stack index where this frame's results are written on [Op.RET]. */
    val retBase: Int,
    /** How many results the caller wants. */
    val retCount: Int,
)

/**
 * An independent thread of execution inside the VM — one per graph entry point (event handler, tick
 * routine, manual run).
 *
 * Fibers hold an **explicit** frame stack rather than a Kotlin continuation. That is the reason the VM
 * exists at all: a continuation cannot be enumerated, inspected, single-stepped or resumed from a chosen
 * point, and those operations *are* the debugger. Everything the editor shows while paused — the call
 * stack, live register values, the current node — is read straight off this object, on the same thread
 * that wrote it.
 */
class Fiber(
    val id: Int,
    val name: String,
    entry: Chunk,
    args: List<Any?> = emptyList(),
) {
    /** Value stack; frames address it through [Frame.base]. Grown on demand by [ensureCapacity]. */
    var stack: Array<Any?> = arrayOfNulls(maxOf(entry.maxRegs, INITIAL_STACK))
        private set

    val frames = ArrayDeque<Frame>()

    var state: FiberState = FiberState.RUNNABLE
        internal set

    /** When [state] is [PARKED], the clock time this fiber may resume at. */
    var resumeAtMs: Long = 0L
        internal set

    /** The uncaught error that ended this fiber, or null. */
    var error: VmError? = null
        internal set

    /** What the debugger asked for next; consumed at the next node boundary. */
    var stepMode: StepMode = StepMode.NONE
        internal set

    /** Frame depth when the step was requested — what OVER and OUT compare against. */
    internal var stepDepth: Int = 0

    var pauseReason: PauseReason = PauseReason.NONE
        internal set

    /** Result values from the entry chunk's final `RET`, if any. */
    var result: List<Any?> = emptyList()
        internal set

    /**
     * The in-flight [Op.ACT] while [state] is [AWAITING_ACT].
     *
     * Held on the fiber (not a local) because the scheduler must be able to **re-offer** it every pass —
     * see [PendingAct].
     */
    internal var pendingAct: PendingAct? = null

    /**
     * How many consecutive scheduler passes this fiber was preempted by the instruction budget without
     * ever parking. The runaway-loop guard: a graph with a `While(true)` and no wait in it would
     * otherwise burn the whole budget every tick forever.
     */
    internal var consecutivePreemptions: Int = 0

    /** Total instructions retired — a cheap cost signal for the editor's fiber panel. */
    var instructionsRetired: Long = 0L
        internal set

    init {
        require(args.size == entry.paramCount) {
            "fiber '$name': entry chunk '${entry.name}' takes ${entry.paramCount} args, got ${args.size}"
        }
        ensureCapacity(entry.maxRegs)
        args.forEachIndexed { i, v -> stack[i] = v }
        frames.addLast(Frame(entry, base = 0, pc = 0, retBase = 0, retCount = 0))
    }

    /**
     * Fibers that must have SETTLED before this one may run — an importer waiting on its libraries.
     *
     * **The ordering `EntryGroup.START` promises was never enforced.** "A library initialises before the
     * thing that imported it can use it" was implemented by spawning the fibers in that order, and the
     * scheduler rotates its starting point every tick so the same fiber does not always get the fresh
     * budget — so on the very first tick it starts at index 1, and the IMPORTER ran first. A registry
     * filled by a library's `always on start` was therefore empty for its importer roughly whenever the
     * rotation said so, which is why stopping and starting the script appeared to fix it.
     *
     * **Settled, not finished.** A library whose handler is a watchdog loop never finishes, and waiting for
     * that would hang the script it was meant to serve. Having parked, blocked, or been paused is the
     * signal that it has had its chance to set up and is now waiting for something else.
     */
    val waitsFor = ArrayList<Fiber>()

    /** Has this fiber had its turn — done, failed, or waiting on something? See [waitsFor]. */
    val isSettled: Boolean
        get() = isFinished || state == FiberState.PARKED ||
            state == FiberState.AWAITING_ACT || state == FiberState.PAUSED

    /** The frame currently executing, or null once the fiber has unwound. */
    val top: Frame? get() = frames.lastOrNull()

    /**
     * Parked on the last instruction of its entry chunk, with no call in flight.
     *
     * The moment between two passes of a handler compiled as a loop (`…; YIELD; JMP top`): the pass has
     * finished, its sequence included, and the next has not begun, so a host that ends the fiber HERE loses
     * nothing. The last instruction is the back-jump; `YIELD` parks with the pc already past itself.
     */
    val isAtPassBoundary: Boolean
        get() = state == FiberState.PARKED && frames.size == 1 &&
            frames.first().let { it.pc == it.chunk.size - 1 }

    /** True when the fiber can never run again. */
    val isFinished: Boolean get() = state == FiberState.DONE || state == FiberState.FAILED

    /**
     * The authoring node this fiber is executing, maintained by [Op.TRACE] node-entry markers.
     *
     * Tracked explicitly rather than derived from the program counter because a fiber stops *after* the
     * instruction that stopped it — so deriving from `pc` at a breakpoint reports the node it is about to
     * run, not the one the user set the breakpoint on. -1 in a chunk compiled without debug info.
     */
    var currentNode: Int = -1
        internal set

    /** The authoring node id the fiber is stopped on, or -1 — what the editor highlights. */
    fun currentNodeId(): Int {
        if (currentNode >= 0) return currentNode
        val f = top ?: return -1
        return f.chunk.nodeIdAt(f.pc)
    }

    /** Grow the value stack so absolute index [needed] - 1 is addressable. */
    internal fun ensureCapacity(needed: Int) {
        if (needed <= stack.size) return
        var n = stack.size
        while (n < needed) n *= 2
        stack = stack.copyOf(n)
    }

    /** A human-readable stack trace, innermost frame first — for errors and the debugger panel. */
    fun stackTrace(): List<String> = frames.reversed().map { f ->
        val node = f.chunk.nodeIdAt(f.pc)
        "${f.chunk.name}@${f.pc}" + if (node >= 0) " (node $node)" else ""
    }

    private companion object {
        const val INITIAL_STACK = 32
    }
}

/**
 * An [Op.ACT] awaiting its result.
 *
 * **Why this is re-offered rather than offered once.** The actuator slot holds exactly one intent, and an
 * offer at equal-or-lower priority is discarded — so a single offer can be silently swallowed and the
 * waiter blocks forever. `ScriptRunner.act` hit this live: a combat intent absorbed a following
 * `act("camera-keep")` and the script froze mid-fight for 32 seconds while HP drained to zero. The fix
 * there, and here, is to keep re-offering until the result actually lands, with [started] making the
 * duplicate offers harmless — whichever copy the drain reaches first does the work and the rest no-op.
 */
internal class PendingAct(
    val label: String,
    /** The work handed to the actuator. Idempotent across duplicate offers via [started]. */
    val work: () -> Unit,
    val started: java.util.concurrent.atomic.AtomicBoolean,
    val box: java.util.concurrent.atomic.AtomicReference<Result<Any?>?>,
    /** Absolute stack index to write results to, and how many the caller wants. */
    val retBase: Int,
    val retCount: Int,
)

/**
 * A VM-level failure, carrying enough context for the editor to point at the offending node.
 *
 * [nodeId] is the authoring node the failing instruction came from, which is what the user actually needs;
 * [pc] and [chunkName] are for our own diagnosis.
 */
class VmError(
    /**
     * The undecorated message. Kept separate from [message] so re-wrapping an error to attach location
     * (which the interpreter does once it knows the chunk and pc) cannot double-decorate it.
     */
    val rawMessage: String,
    val chunkName: String? = null,
    val pc: Int = -1,
    val nodeId: Int = -1,
    val trace: List<String> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(rawMessage, cause) {
    override val message: String
        get() = buildString {
            append(rawMessage)
            if (chunkName != null) {
                append("  [").append(chunkName).append('@').append(pc)
                if (nodeId >= 0) append(", node ").append(nodeId)
                append(']')
            } else if (nodeId >= 0) {
                append("  [node ").append(nodeId).append(']')
            }
            trace.forEach { append("\n    at ").append(it) }
        }
}
