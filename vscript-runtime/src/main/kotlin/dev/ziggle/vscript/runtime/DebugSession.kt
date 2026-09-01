package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.runtime.ScriptRuntime
import dev.ziggle.vscript.vm.Breakpoints
import dev.ziggle.vscript.vm.Fiber
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.PauseReason
import dev.ziggle.vscript.vm.StepMode
import dev.ziggle.vscript.vm.Values

/**
 * One independent thread of execution — a Start node's fiber.
 *
 * Named a *context* rather than a thread because that is what it is on the canvas: a graph with two Start
 * nodes has two of these, and "paused" is a property of one of them rather than of the world.
 */
class Context(
    val id: Int,
    val name: String,
    val entryNodeId: Int,
    val state: FiberState,
    val pauseReason: PauseReason,
    /** The node this context is sitting on, or -1. */
    val nodeId: Int,
    val error: String?,
    /**
     * What the fiber returned, once it is [FiberState.DONE]. Empty until then, and empty for a fiber that
     * returns nothing — which is most of them, since only a called function has anything to hand back.
     */
    val result: List<Any?> = emptyList(),
    /**
     * How much longer a [FiberState.PARKED] fiber intends to sleep, in ms. -1 when it is not parked.
     *
     * **"PARKED" alone cannot be acted on.** A fiber asleep for 200ms and one asleep for four minutes look
     * identical in a state dump, and the difference is the whole diagnosis: the first is a script pacing
     * itself and the second is a bug. Answering "idle" with no duration sent an investigation looking at
     * the interpreter, the scheduler and the compiler in turn, for something a number would have named.
     */
    val sleepingForMs: Long = -1,
) {
    val isPaused: Boolean get() = state == FiberState.PAUSED
}

/** One frame of a context's call stack, innermost first. */
class StackFrame(
    val index: Int,
    val chunkName: String,
    val pc: Int,
    val nodeId: Int,
    /** Which execution of [nodeId] this is — see the note on hit counts in [Breakpoints]. */
    val activation: Int,
)

/** A named value the inspector can show. */
class Variable(val name: String, val value: Any?, val nodeId: Int = -1, private val shown: String? = null, private val typed: String? = null) {
    /** The value as text — rendered here, or as a remote debugger already rendered it. */
    val display: String get() = shown ?: render(value)

    val typeName: String get() = typed ?: Values.typeName(value)

    companion object {
        /** Short, honest rendering. A collection says its size rather than its contents. */
        fun render(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"" + (if (v.length > 60) v.take(59) + "…" else v) + "\""
            is Collection<*> -> "List(${v.size})"
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            else -> v.toString().let { if (it.length > 60) it.take(59) + "…" else it }
        }
    }
}

/** A group of variables, the way a debugger groups locals / globals / arguments. */
class Scope(val name: String, val variables: List<Variable>)

/** Why execution stopped, for the header line. */
enum class StoppedReason { BREAKPOINT, STEP, ERROR, PAUSE }

/**
 * The debugger's whole surface, expressed as **commands and queries** rather than as reaching into the VM.
 *
 * Everything runs in one process today, so this could have been direct method calls on [ScriptRuntime].
 * Writing it as a request/response boundary anyway buys three things and costs a thin dispatch layer:
 *
 *  - it forces the answers to be *values* — a `StackFrame` rather than a live `Frame`, a `Variable` rather
 *    than a register index — which is the same property that makes snapshots, replay and time travel
 *    possible later rather than blocked;
 *  - execution can move to a worker or another process without the UI noticing;
 *  - the vocabulary is deliberately close to the Debug Adapter Protocol (threads, stackTrace, scopes,
 *    continue, next, stepIn, stepOut), so a VS Code adapter is a mapping exercise instead of a project.
 *
 * **Pause is global by default.** Hitting a breakpoint in one context freezes the others, because a mental
 * model where the world stops is the one people can actually reason about. Per-context freezing is offered
 * as an option rather than as the default for the same reason.
 */
class DebugSession(private val runtime: ScriptRuntime) : DebugSurface {

    /** Freeze every context when one stops, rather than only the one that hit the breakpoint. */
    var pauseAll: Boolean = true

    /** Hold a faulting context on the failing node instead of unwinding it. */
    var breakOnError: Boolean
        get() = runtime.breakOnError
        set(v) { runtime.breakOnError = v }

    override val breakpoints: Breakpoints get() = runtime.breakpoints

    // ---- queries ------------------------------------------------------------------------------------

    override fun contexts(): List<Context> = runtime.fibers.map { f ->
        Context(
            id = f.id,
            name = f.name,
            entryNodeId = runtime.entryNodeOf(f.id),
            state = f.state,
            pauseReason = f.pauseReason,
            nodeId = f.currentNodeId(),
            error = f.error?.rawMessage,
            result = f.result,
            sleepingForMs = if (f.state == FiberState.PARKED) f.resumeAtMs - runtime.nowMs() else -1,
        )
    }

    /** The context the UI should be showing: whichever stopped, else the first still running. */
    override fun focused(): Context? {
        val all = contexts()
        return all.firstOrNull { it.isPaused } ?: all.firstOrNull { it.state != FiberState.DONE } ?: all.firstOrNull()
    }

    override val isPaused: Boolean get() = runtime.fibers.any { it.state == FiberState.PAUSED }

    /**
     * A value that changes on every distinct stop.
     *
     * The UI needs to tell "a new stop" from "the same stop, still being looked at", and it cannot do that
     * by watching `isPaused`: a step resumes and re-pauses, and whether a render happens in between is a
     * race with the tick. Instruction count is exact — it has advanced by the time the fiber stops again.
     */
    override fun stopToken(): Long {
        val f = runtime.fibers.firstOrNull { it.state == FiberState.PAUSED } ?: return -1L
        return f.id.toLong() * 1_000_003L + f.instructionsRetired
    }

    override fun stoppedReason(): StoppedReason? {
        val f = runtime.fibers.firstOrNull { it.state == FiberState.PAUSED } ?: return null
        return when (f.pauseReason) {
            PauseReason.BREAKPOINT -> StoppedReason.BREAKPOINT
            PauseReason.STEP -> StoppedReason.STEP
            PauseReason.ERROR -> StoppedReason.ERROR
            else -> StoppedReason.PAUSE
        }
    }

    /**
     * The call stack of [contextId], innermost frame first.
     *
     * Read straight off the fiber's explicit frame list. This is the reason the VM holds one instead of
     * using Kotlin continuations: a continuation cannot be enumerated, and enumerating it *is* the panel.
     */
    override fun stackTrace(contextId: Int): List<StackFrame> {
        val f = fiber(contextId) ?: return emptyList()
        return f.frames.reversed().mapIndexed { i, frame ->
            val node = if (i == 0) f.currentNodeId() else frame.chunk.nodeIdAt(frame.pc)
            StackFrame(i, frame.chunk.name, frame.pc, node, runtime.activationOf(node))
        }
    }

    /**
     * The values visible in one frame.
     *
     * Two scopes, and the split is the compiler's, not a presentation choice: document variables live in
     * the run's globals and are visible from everywhere, and everything else lives in a register of this
     * frame. Pure nodes are absent because they own no register at all — they are expressions re-expanded
     * at each use, and inventing a slot for them would mean showing whatever happened to be in a scratch
     * register.
     *
     * **A register also exists before the thing it holds does**, which is the same mistake one statement
     * earlier. A text local is allocated when its statement runs, so a frame paused above it would show
     * the scratch left by the last expression — `val n = 2` reported `n` as a string a `log` two lines up
     * had passed. `SlotMap.liveFrom` says from where each one means anything, and the ones that do not yet
     * are left out rather than guessed at.
     */
    override fun scopes(contextId: Int, frameIndex: Int): List<Scope> {
        val f = fiber(contextId) ?: return emptyList()
        val frame = f.frames.asReversed().getOrNull(frameIndex) ?: return emptyList()
        val slots = frame.chunk.slots
        if (slots.isEmpty) return emptyList()

        // Variables come from the run's GLOBALS, not from this frame — that is what makes them visible to
        // a function body and to the stop handler, and reading them off the frame would show a register
        // that happens to sit at that index and belongs to something else entirely.
        val vars = slots.variables.entries.sortedBy { it.key }.map { (name, slot) ->
            Variable(name, runtime.globals.getOrNull(slot))
        }
        val outputs = slots.outputs.entries
            .filter { slots.isLive(it.key, frame.pc) }
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, reg) ->
                Variable("${key.second}", f.stack.getOrNull(frame.base + reg), key.first)
            }
        // A text frame's registers hold LOCALS; a canvas frame's hold node outputs. Same slot map, and
        // calling them the same thing in the inspector would make one of the two a lie.
        val label = if (runtime.textSites != null) "Locals" else "Node outputs"
        return buildList {
            if (vars.isNotEmpty()) add(Scope("Variables", vars))
            if (outputs.isNotEmpty()) add(Scope(label, outputs))
        }
    }

    /** The current value of one node's output pin in the focused frame, for the watch list. */
    override fun valueOf(contextId: Int, nodeId: Int, pin: String): Variable? {
        val f = fiber(contextId) ?: return null
        for (frame in f.frames.asReversed()) {
            val reg = frame.chunk.slots.outputs[nodeId to pin] ?: continue
            return Variable(pin, f.stack.getOrNull(frame.base + reg), nodeId)
        }
        return null
    }

    /**
     * Every output value the debugger can currently see, keyed by `nodeId to pin`.
     *
     * Captured **on pause only**. Recording every pin on every activation is unbounded and would dominate
     * the runtime — and the graph is already the variables panel, so what this feeds is the pills drawn on
     * the wires themselves rather than a list nobody reads.
     */
    fun visibleValues(contextId: Int): Map<Pair<Int, String>, Any?> {
        val f = fiber(contextId) ?: return emptyMap()
        val out = HashMap<Pair<Int, String>, Any?>()
        // Outermost frame first, so an inner frame's value wins for a node visible in both.
        for (frame in f.frames) {
            for ((key, reg) in frame.chunk.slots.outputs) {
                out[key] = f.stack.getOrNull(frame.base + reg)
            }
        }
        return out
    }

    /**
     * Freeze every other context once one has stopped.
     *
     * Called each tick rather than at the moment of stopping, because a context only notices a pause
     * request at its next node boundary — there is no way to halt one mid-instruction, and there should not
     * be. Pause-all is the default because a mental model where the world stops is the one people can
     * actually reason about; per-context freezing is available by turning [pauseAll] off.
     */
    fun enforcePauseAll() {
        if (!pauseAll || !isPaused) return
        for (f in runtime.fibers) {
            if (f.isFinished || f.state == FiberState.PAUSED) continue
            runtime.pauseFiber(f.id)
        }
    }

    // ---- commands -----------------------------------------------------------------------------------

    fun resume(contextId: Int?) = forEachTarget(contextId) { runtime.resumeFiber(it) }
    override fun resume() = resume(null)

    /** Kill every context. The transport's stop button, and the only command that is never per-context. */
    override fun stop() = runtime.stop()

    fun pause(contextId: Int? = null) = forEachTarget(contextId) { runtime.pauseFiber(it) }

    fun stepOver(contextId: Int?) = step(contextId, StepMode.OVER)
    override fun stepOver() = stepOver(null)

    fun stepInto(contextId: Int?) = step(contextId, StepMode.INTO)
    override fun stepInto() = stepInto(null)

    fun stepOut(contextId: Int?) = step(contextId, StepMode.OUT)
    override fun stepOut() = stepOut(null)

    fun stepIntoData(contextId: Int?) = step(contextId, StepMode.INTO_DATA)
    override fun stepIntoData() = stepIntoData(null)

    /**
     * Step one context and release the rest.
     *
     * The others are resumed rather than stepped: stepping means "advance the thing I am looking at", and
     * advancing every context by one node each would be a different operation that nobody wants.
     */
    private fun step(contextId: Int?, mode: StepMode) {
        val target = contextId ?: focused()?.id ?: return
        runtime.stepFiber(target, mode)
        if (pauseAll) return
        runtime.fibers.filter { it.id != target }.forEach { runtime.resumeFiber(it.id) }
    }

    private fun forEachTarget(contextId: Int?, action: (Int) -> Unit) {
        if (contextId != null) {
            action(contextId)
            return
        }
        if (pauseAll) runtime.fibers.forEach { action(it.id) } else focused()?.let { action(it.id) }
    }

    private fun fiber(contextId: Int): Fiber? = runtime.fibers.firstOrNull { it.id == contextId }
}

private fun <T> Array<T>.getOrNull(i: Int): T? = if (i in indices) this[i] else null
