package dev.ziggle.vscript.vm

import dev.ziggle.vscript.host.Clock
import dev.ziggle.vscript.host.SystemClock
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Why [Interpreter.resume] handed control back. */
enum class StepResult {
    /** The instruction budget ran out mid-execution; the fiber is still [FiberState.RUNNABLE]. */
    PREEMPTED,

    /** The fiber parked ([Op.YIELD] / [Op.SLEEP]). */
    PARKED,

    /** The fiber is waiting on an [Op.ACT] result. */
    AWAITING_ACT,

    /** The fiber stopped on an [Op.BREAK]. */
    PAUSED,

    /** The fiber ran to completion. */
    FINISHED,

    /** The fiber ended on an uncaught [VmError]. */
    FAILED,
}

/**
 * The bytecode interpreter.
 *
 * Executes one fiber at a time, up to a **nanosecond deadline** supplied by the caller. It never runs a
 * fiber to completion on its own, because it runs on the client thread: `PluginTicks` warns above 8ms and
 * force-stops a plugin whose hook exceeds 16ms five ticks running, so *preemptibility is a correctness
 * requirement*, not a performance nicety. Preemption happens between instructions, so a host call is never
 * interrupted mid-flight.
 *
 * The deadline is checked every [BUDGET_CHECK_INTERVAL] instructions rather than every instruction —
 * `System.nanoTime()` costs on the order of a simple opcode, so checking it each time would roughly halve
 * throughput to buy precision the 3ms budget does not need.
 */
class Interpreter(
    private val hosts: HostRegistry,
    private val clock: Clock = SystemClock,
    /**
     * Where [Op.ACT] work is handed off. **Null runs blocking functions inline**, which is the right
     * behaviour for headless tests and for a bare pump with no actuator behind it — it mirrors
     * `ScriptRunner.act`'s legacy mode. In the client this is always wired to the plugin's drain.
     */
    private val actuator: ActuatorSink? = null,
    /** Where the debugger wants to stop. Consulted at [Op.TRACE] boundaries. */
    val breakpoints: Breakpoints = Breakpoints(),
    /** Receives [Op.TRACE] events — drives the editor's exec-wire flow animation. */
    /** [value] is non-null only for [TraceKind.PURE_EXIT] — the result a pure node just produced. */
    private val tracer: ((nodeId: Int, kind: Int, value: Any?) -> Unit)? = null,
) {
    /** Host bindings resolved per chunk on first use; identity-keyed since chunks are not value types. */
    private val bindings = IdentityHashMap<Chunk, Array<HostFunction>>()

    /** Frame-depth cap. A visual language makes accidental recursion easy, so this must exist. */
    var maxCallDepth: Int = 64

    /**
     * Graph variables, shared by every fiber of a run.
     *
     * Owned here rather than by a fiber because that is what "graph variable" means: a stop handler is its
     * own fiber and a function body its own frame, and both have to see what the main chain wrote. Held
     * frame-locally, each would get a private copy and every write would go somewhere nobody could read.
     *
     * Sized and seeded when a run starts — see [resetGlobals].
     */
    var globals: Array<Any?> = emptyArray()
        private set

    /** Size and seed the globals for a fresh run, from each variable's declared default. */
    fun resetGlobals(defaults: List<Any?>) {
        globals = defaults.toTypedArray()
        pending = null
        ranPrologues.clear()
    }

    /**
     * Documents whose initialiser prologue has already run in THIS run — see [Chunk.prologueOf].
     *
     * Per run, and cleared with the globals it guards, because that is the thing it is protecting: a
     * prologue seeds variables, and seeding them twice throws away whatever happened in between.
     */
    private val ranPrologues = HashSet<String>()

    /**
     * Writes staged by a render pass, or null outside one.
     *
     * A render pass may be abandoned part-way — it gets a few milliseconds and is cut off if it runs
     * over. Writing straight to [globals] would leave the ones it managed before the cut standing and the
     * rest not, so a frame that took slightly too long would silently corrupt state: a counter that skips,
     * or two variables that disagree because only the first was reached. Staging makes a pass
     * all-or-nothing — [commitPending] on a pass that finished, [discardPending] on one that did not.
     *
     * Reads consult it first, so a pass still sees its own writes; nothing else can, because nothing else
     * runs while it does.
     */
    private var pending: HashMap<Int, Any?>? = null

    /**
     * Prologues first reached inside the CURRENT staged pass, so a discard can un-record them.
     *
     * **A discard throws the writes away and must throw the record away with them.** A prologue seeds a
     * document's variables and is marked as run so nothing seeds them twice; staged and then abandoned,
     * the writes vanish and the mark stayed — so every later entry skipped a document that had never
     * actually been seeded, and its variables sat at their defaults. On a script whose render pass is the
     * first thing to reach a prologue that is silent, total, and looks like a compiler bug: half the
     * closure's records are null and nothing says why.
     */
    private var stagedPrologues: MutableList<String>? = null

    /** Begin staging writes. Pairs with [commitPending] or [discardPending]. */
    fun beginStaged() {
        pending = HashMap()
        stagedPrologues = ArrayList()
    }

    /** Apply a completed pass's writes. */
    fun commitPending() {
        stagedPrologues = null
        pending?.forEach { (i, v) -> if (i in globals.indices) globals[i] = v }
        pending = null
    }

    /** Throw away an abandoned pass's writes. */
    fun discardPending() {
        pending = null
        // Whatever this pass was the first to seed did not, in the end, get seeded — so it is not run.
        stagedPrologues?.forEach { ranPrologues.remove(it) }
        stagedPrologues = null
    }

    /**
     * Hold a faulting fiber at the failing instruction instead of unwinding it.
     *
     * Off by default so a headless pump behaves as it always did; the editor turns it on while the
     * debugger is attached. See [PauseReason.ERROR].
     */
    var breakOnError: Boolean = false

    private fun hostsFor(chunk: Chunk): Array<HostFunction> =
        bindings.getOrPut(chunk) { hosts.bind(chunk) }

    /**
     * Advance [fiber] until it parks, suspends, finishes, traps, or [deadlineNanos] passes.
     *
     * Returns *why* it stopped; [Fiber.state] is left consistent with that either way.
     */
    fun resume(fiber: Fiber, deadlineNanos: Long): StepResult {
        if (fiber.isFinished) return StepResult.FINISHED
        if (fiber.state == FiberState.PAUSED) return StepResult.PAUSED

        var frame = fiber.top ?: run {
            fiber.state = FiberState.DONE
            return StepResult.FINISHED
        }
        fiber.state = FiberState.RUNNABLE

        var chunk = frame.chunk
        var code = chunk.code
        var hostFns = hostsFor(chunk)
        var base = frame.base
        var pc = frame.pc
        var stack = fiber.stack
        var countdown = BUDGET_CHECK_INTERVAL

        // `frame.pc = pc` is written out at every exit from the loop rather than hidden behind a local
        // helper: a local function capturing `pc` and `frame` would make Kotlin box them into Ref objects,
        // turning every `pc++` in the dispatch loop into a field access. Missing one of these writes leaves
        // the fiber resuming at a stale instruction, so they are load-bearing — grep before adding an exit.
        try {
            while (true) {
                if (--countdown <= 0) {
                    countdown = BUDGET_CHECK_INTERVAL
                    if (System.nanoTime() >= deadlineNanos) {
                        frame.pc = pc
                        fiber.consecutivePreemptions++
                        return StepResult.PREEMPTED
                    }
                }

                if (pc < 0 || pc >= chunk.size) {
                    throw VmError("pc $pc out of range for chunk '${chunk.name}' (${chunk.size} insns)")
                }

                val o = pc * 4
                val op = code[o]
                val a = code[o + 1]
                val b = code[o + 2]
                val c = code[o + 3]
                pc++
                fiber.instructionsRetired++

                when (op) {
                    Op.HALT -> {
                        frame.pc = pc
                        fiber.state = FiberState.DONE
                        fiber.consecutivePreemptions = 0
                        return StepResult.FINISHED
                    }

                    Op.CONST -> stack[base + a] = chunk.constants[b]
                    Op.MOVE -> stack[base + a] = stack[base + b]
                    // A lambda that reads a local: the template says which function, the window says what
                    // it closed over. Copied now, so what the closure sees cannot change afterwards.
                    Op.CLOSURE -> {
                        val template = chunk.constants[b] as? FunctionValue
                            ?: throw VmError("closure ${chunk.name}@$pc names no function")
                        val from = base + Op.argCount(c)
                        val count = Op.retCount(c)
                        stack[base + a] = FunctionValue(
                            template.index,
                            template.name,
                            (0 until count).map { stack[from + it] },
                        )
                    }
                    Op.GETG -> {
                        val staged = pending
                        stack[base + a] =
                            if (staged != null && staged.containsKey(b)) staged[b] else globals.getOrNull(b)
                    }
                    Op.SETG -> {
                        val staged = pending
                        if (staged != null) staged[a] = stack[base + b]
                        else if (a in globals.indices) globals[a] = stack[base + b]
                    }

                    Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD ->
                        stack[base + a] = Values.arith(op, stack[base + b], stack[base + c])

                    Op.NEG -> stack[base + a] = Values.negate(stack[base + b])
                    // A null passes through untouched rather than faulting: an unfed pin arriving null is
                    // already reported by the validator, and a widening step is the wrong place to be the
                    // first to complain about it — "expected a number, got null" pointing at a conversion
                    // nobody wrote would be worse than the null reaching the arithmetic that wanted it.
                    Op.TOF -> stack[base + a] = stack[base + b]?.let { Values.toDouble(it) }
                    Op.NOT -> stack[base + a] = !Values.truth(stack[base + b])
                    Op.AND -> stack[base + a] = Values.truth(stack[base + b]) && Values.truth(stack[base + c])
                    Op.OR -> stack[base + a] = Values.truth(stack[base + b]) || Values.truth(stack[base + c])

                    Op.EQ -> stack[base + a] = Values.eq(stack[base + b], stack[base + c])
                    Op.NE -> stack[base + a] = !Values.eq(stack[base + b], stack[base + c])
                    Op.LT -> stack[base + a] = Values.compare(stack[base + b], stack[base + c]) < 0
                    Op.LE -> stack[base + a] = Values.compare(stack[base + b], stack[base + c]) <= 0
                    Op.GT -> stack[base + a] = Values.compare(stack[base + b], stack[base + c]) > 0
                    Op.GE -> stack[base + a] = Values.compare(stack[base + b], stack[base + c]) >= 0

                    Op.JMP -> pc = a
                    Op.JMPF -> if (!Values.truth(stack[base + a])) pc = b
                    Op.JMPT -> if (Values.truth(stack[base + a])) pc = b

                    Op.NEWLIST -> stack[base + a] = ArrayList<Any?>()
                    // APPEND stays strict — adding to nothing is a real mistake. The READS treat null as
                    // empty, because null is what an unconnected List pin supplies, and "a For Each with
                    // nothing plugged in visits nothing" is the answer an author expects. Failing there
                    // instead made an incomplete graph look broken rather than unfinished.
                    Op.APPEND -> asList(stack[base + a]).add(stack[base + b])
                    Op.SETKEY -> asMutableMap(stack[base + a])[stack[base + b]] = stack[base + c]
                    Op.NEWMAP -> stack[base + a] = LinkedHashMap<Any?, Any?>()
                    // The READS tolerate a null map for the reason APPEND's note gives about lists: an
                    // unconnected pin supplies null, and "nothing holds nothing" is the answer an author
                    // expects. Only the WRITES are strict.
                    Op.GETKEY -> stack[base + a] = asMapOrEmpty(stack[base + b])[stack[base + c]]
                    Op.HASKEY -> stack[base + a] = asMapOrEmpty(stack[base + b]).containsKey(stack[base + c])
                    Op.MAPLEN -> stack[base + a] = asMapOrEmpty(stack[base + b]).size
                    Op.DELKEY -> asMutableMap(stack[base + a]).remove(stack[base + b])
                    Op.SETINDEX -> {
                        val list = asList(stack[base + a])
                        val i = Values.toInt(stack[base + b])
                        if (i < 0 || i >= list.size) {
                            throw VmError("list index $i out of bounds (size ${list.size})")
                        }
                        list[i] = stack[base + c]
                    }
                    Op.LEN -> stack[base + a] = asListOrEmpty(stack[base + b]).size
                    Op.INDEX -> {
                        val list = asListOrEmpty(stack[base + b])
                        val i = Values.toInt(stack[base + c])
                        if (i < 0 || i >= list.size) {
                            throw VmError("list index $i out of bounds (size ${list.size})")
                        }
                        stack[base + a] = list[i]
                    }
                    Op.ITER -> stack[base + a] = Cursor(asListOrEmpty(stack[base + b]))
                    Op.ITERNEXT -> {
                        val cur = stack[base + b] as? Cursor
                            ?: throw VmError("ITERNEXT on ${Values.typeName(stack[base + b])}, expected an iterator")
                        if (cur.i >= cur.list.size) {
                            pc = c
                        } else {
                            stack[base + a] = cur.list[cur.i]
                            cur.i++
                        }
                    }

                    // The shape is a constant — one per declared type, holding its name and its field
                    // names — so the record is built from `c` registers at `b` without the instruction
                    // having to carry them. A FRESH one each time, for the reason a list literal is: a
                    // pure node is re-expanded at every use site.
                    Op.NEWSTRUCT -> {
                        val shape = chunk.constants[b] as? StructShape
                            ?: throw VmError("NEWSTRUCT with no shape at constant $b")
                        val fields = arrayOfNulls<Any?>(shape.names.size)
                        for (i in shape.names.indices) fields[i] = stack[base + c + i]
                        stack[base + a] = StructValue(shape.type, shape.names, fields)
                    }

                    Op.GETFIELD -> {
                        val v = stack[base + b] as? StructValue
                            ?: throw VmError("GETFIELD on ${Values.typeName(stack[base + b])}, expected a record")
                        stack[base + a] = v[c]
                    }

                    Op.SETFIELD -> {
                        val v = stack[base + a] as? StructValue
                            ?: throw VmError("SETFIELD on ${Values.typeName(stack[base + a])}, expected a record")
                        stack[base + a] = v.with(b, stack[base + c])
                    }

                    Op.TRACE -> {
                        // PURE_EXIT is an END marker, not a boundary: it must not move `currentNode`, trip a
                        // breakpoint, or satisfy a step. Stopping there would land between a value being
                        // produced and being used — not a place an author can point at on the canvas.
                        val isNode = b != TraceKind.EXEC_EDGE && b != TraceKind.PURE_EXIT
                        if (isNode && a >= 0) fiber.currentNode = a
                        // Only PURE_EXIT carries a value, and it reads `c` as a register in this frame.
                        tracer?.invoke(a, b, if (b == TraceKind.PURE_EXIT) stack[base + c] else null)
                        // A breakpoint is armed on a node id and trips at that node's entry marker, so it
                        // lands on a statement boundary rather than mid-expression. Stepping is checked at
                        // the same place for the same reason.
                        if (isNode && a >= 0) {
                            val reason = when {
                                breakpoints.shouldBreak(a) -> PauseReason.BREAKPOINT
                                stepStopsHere(fiber, b) -> PauseReason.STEP
                                else -> null
                            }
                            if (reason != null) {
                                frame.pc = pc
                                fiber.state = FiberState.PAUSED
                                fiber.pauseReason = reason
                                // Consumed: a step is a single request, not a mode you have to switch off.
                                fiber.stepMode = StepMode.NONE
                                return StepResult.PAUSED
                            }
                        }
                    }

                    Op.BREAK -> {
                        if (a >= 0) fiber.currentNode = a
                        frame.pc = pc
                        fiber.state = FiberState.PAUSED
                        fiber.pauseReason = PauseReason.BREAKPOINT
                        return StepResult.PAUSED
                    }

                    Op.YIELD -> {
                        // **Meaningless inside a one-shot pass, and fatal there.** A render or tick pass
                        // gets a few milliseconds and is all-or-nothing: unfinished, it is killed and
                        // everything it wrote is discarded. Yielding guarantees it never finishes, so a
                        // loop that yields in one is not slow — it never completes, ever. A panel whose
                        // tick handler sized a list with `while count(xs) < n` therefore never sized it,
                        // never drew, and reported only "did not finish in 5ms".
                        //
                        // Giving up the rest of the pass is also incoherent here: there is nobody else to
                        // give it to. A yield exists so a long loop on the MAIN scheduler lets other
                        // fibers run and does not read as a runaway; a staged pass has one fiber and a
                        // deadline, and the deadline is what bounds it.
                        if (pending == null) {
                            frame.pc = pc
                            fiber.state = FiberState.PARKED
                            fiber.resumeAtMs = clock.nowMs()
                            fiber.consecutivePreemptions = 0
                            return StepResult.PARKED
                        }
                    }

                    Op.SLEEP -> {
                        frame.pc = pc
                        val ms = Values.toLong(stack[base + a]).coerceAtLeast(0L)
                        fiber.state = FiberState.PARKED
                        fiber.resumeAtMs = clock.nowMs() + ms
                        fiber.consecutivePreemptions = 0
                        return StepResult.PARKED
                    }

                    Op.CALL -> {
                        val hf = hostFns[a]
                        val argBase = base + b
                        val n = Op.argCount(c)
                        val rets = Op.retCount(c)
                        val args = Array<Any?>(n) { stack[argBase + it] }
                        val out = try {
                            hf.fn.invoke(args)
                        } catch (e: VmError) {
                            throw e
                        } catch (e: Throwable) {
                            throw VmError("host function '${hf.name}' threw: ${e.message}", cause = e)
                        }
                        writeResults(stack, argBase, rets, out)
                    }

                    Op.ACT -> {
                        val hf = hostFns[a]
                        val argBase = base + b
                        val n = Op.argCount(c)
                        val rets = Op.retCount(c)
                        val args = Array<Any?>(n) { stack[argBase + it] }

                        val sink = actuator
                        if (sink == null) {
                            // No actuator: run inline. Legacy/bare mode, matching ScriptRunner.act.
                            val out = try {
                                hf.fn.invoke(args)
                            } catch (e: VmError) {
                                throw e
                            } catch (e: Throwable) {
                                throw VmError("host function '${hf.name}' threw: ${e.message}", cause = e)
                            }
                            if (out is HostAwait) {
                                // **The host will answer later** — see [HostAwait]. Park exactly as the
                                // actuator path does, with the host's own box in the pending act: the
                                // scheduler polls it each pass through `tryCompleteAct`, which already
                                // lands a value, routes a failure through `catch`, and holds a fault for
                                // the debugger. `started` is true because the work has already run; the
                                // no-op `work` is what a null actuator would be re-offered.
                                frame.pc = pc
                                fiber.pendingAct = PendingAct(hf.name, {}, AtomicBoolean(true), out.box, argBase, rets)
                                fiber.state = FiberState.AWAITING_ACT
                                fiber.consecutivePreemptions = 0
                                return StepResult.AWAITING_ACT
                            }
                            writeResults(stack, argBase, rets, out)
                        } else {
                            frame.pc = pc // resume AFTER this instruction once the result lands
                            val box = AtomicReference<Result<Any?>?>(null)
                            val started = AtomicBoolean(false)
                            val work = {
                                if (started.compareAndSet(false, true)) box.set(runCatching { hf.fn.invoke(args) })
                            }
                            fiber.pendingAct = PendingAct(hf.name, work, started, box, argBase, rets)
                            fiber.state = FiberState.AWAITING_ACT
                            fiber.consecutivePreemptions = 0
                            sink.offer(hf.name, work)
                            return StepResult.AWAITING_ACT
                        }
                    }

                    Op.CALLG, Op.CALLV -> {
                        // The ONLY difference between the two, and it is one line: a static call names its
                        // callee in the instruction, a dynamic one reads it out of a register. Everything
                        // below — the arity check, the window, the frame — is shared, because a function
                        // value is a program index and a program index is what CALLG already had.
                        val fv = if (op == Op.CALLG) null else {
                            stack[base + a] as? FunctionValue
                                ?: throw VmError(
                                    "nothing to call: register $a holds '${stack[base + a]}', not a function"
                                )
                        }
                        val target = fv?.index ?: a
                        val sub = chunk.program.getOrNull(target)
                            ?: throw VmError("no function $target in the program of '${chunk.name}'")
                        // A prologue runs ONCE per run. Every start entry calls the imported ones at its
                        // head — see [Chunk.prologueOf] — so with more than one start entry the later
                        // calls would re-seed variables the earlier entries had already written.
                        if (sub.prologueOf != null) {
                            if (!ranPrologues.add(sub.prologueOf)) {
                                pc += 0
                                continue
                            }
                            // Reached first by a pass that may yet be abandoned — see [stagedPrologues].
                            stagedPrologues?.add(sub.prologueOf)
                        }
                        val argBase = base + b
                        val n = Op.argCount(c)
                        // A closure's captures are arguments the CALL SITE does not know about — it passes
                        // the item, and the values the lambda closed over arrive from the value itself.
                        // So arity is what the site supplied PLUS what the value carries.
                        val closed = fv?.captured ?: emptyList()
                        if (n + closed.size != sub.paramCount) {
                            throw VmError(
                                "'${sub.name}' takes ${sub.paramCount} args, called with ${n + closed.size}"
                            )
                        }
                        if (fiber.frames.size >= maxCallDepth) {
                            throw VmError("call depth limit ($maxCallDepth) exceeded calling '${sub.name}' — infinite recursion?")
                        }
                        frame.pc = pc
                        // The callee's window starts at the argument base, so the arguments are already
                        // sitting in its registers 0..n-1 — nothing is copied.
                        fiber.ensureCapacity(argBase + sub.maxRegs)
                        stack = fiber.stack
                        // ...and they land straight after them, which is where the lambda's synthesised
                        // signature puts its captured names. Written after `ensureCapacity` because that is
                        // what guarantees the room, and `maxRegs` covers every parameter by construction.
                        for (i in closed.indices) stack[argBase + n + i] = closed[i]
                        val newFrame = Frame(sub, base = argBase, pc = 0, retBase = argBase, retCount = Op.retCount(c))
                        fiber.frames.addLast(newFrame)
                        frame = newFrame; chunk = sub; code = chunk.code; hostFns = hostsFor(sub)
                        base = argBase; pc = 0
                    }

                    Op.RET -> {
                        val retStart = base + a
                        val provided = b
                        val finished = fiber.frames.removeLast()
                        if (fiber.frames.isEmpty()) {
                            fiber.result = (0 until provided).map { stack[retStart + it] }
                            fiber.state = FiberState.DONE
                            fiber.consecutivePreemptions = 0
                            return StepResult.FINISHED
                        }
                        // Copy into the caller's result window, padding with null if the callee returned
                        // fewer values than the call site asked for.
                        for (i in 0 until finished.retCount) {
                            stack[finished.retBase + i] = if (i < provided) stack[retStart + i] else null
                        }
                        frame = fiber.frames.last()
                        chunk = frame.chunk; code = chunk.code; hostFns = hostsFor(chunk)
                        base = frame.base; pc = frame.pc
                    }

                    else -> throw VmError("unknown opcode $op")
                }
            }
        } catch (e: Throwable) {
            frame.pc = pc
            val nodeId = chunk.nodeIdAt((pc - 1).coerceAtLeast(0))
            val err = if (e is VmError && e.chunkName == null) {
                VmError(e.rawMessage, chunk.name, pc - 1, nodeId, fiber.stackTrace(), e.cause)
            } else if (e is VmError) {
                e
            } else {
                VmError(e.message ?: e.javaClass.simpleName, chunk.name, pc - 1, nodeId, fiber.stackTrace(), e)
            }
            fiber.error = err
            if (caught(fiber, err, pc - 1)) {
                // PREEMPTED, not a state change: the fiber is still RUNNABLE and its pc now points at the
                // catch block, so the scheduler comes back and carries on from there. There is no "resumed"
                // result because resuming is the ordinary case — this is the same exit a fiber takes when
                // its instruction budget runs out mid-block.
                return StepResult.PREEMPTED
            }
            if (breakOnError) {
                // Held, not unwound. The frame stack, the registers and the current node are all still
                // exactly as they were when the instruction failed, which is the entire point — you are
                // looking at the fault rather than at a description of it written after the fact.
                fiber.state = FiberState.PAUSED
                fiber.pauseReason = PauseReason.ERROR
                fiber.stepMode = StepMode.NONE
                return StepResult.PAUSED
            }
            fiber.state = FiberState.FAILED
            return StepResult.FAILED
        }
    }

    /**
     * Whether an outstanding step request lands on this node boundary.
     *
     * [kind] distinguishes the two evaluation domains — see [TraceKind.PURE_ENTER].
     */
    private fun stepStopsHere(fiber: Fiber, kind: Int): Boolean {
        val exec = kind == TraceKind.NODE_ENTER
        return when (fiber.stepMode) {
            StepMode.NONE -> false
            StepMode.INTO -> exec
            StepMode.OVER -> exec && fiber.frames.size <= fiber.stepDepth
            StepMode.OUT -> exec && fiber.frames.size < fiber.stepDepth
            StepMode.INTO_DATA -> true
        }
    }

    /** Arm a step. The fiber resumes and stops again at the first boundary that matches [mode]. */
    fun requestStep(fiber: Fiber, mode: StepMode) {
        if (fiber.isFinished) return
        fiber.stepMode = mode
        fiber.stepDepth = fiber.frames.size
        resumeFromPause(fiber)
    }

    /**
     * Give [err] to a `try` that covers it, if there is one. True when one took it.
     *
     * **Look out through the frames** for a handler whose range covers where that frame currently is.
     * Innermost first, so a handler nearer the raise wins — and across frames, so an error several calls
     * down is caught by the `try` that led there, which is the only reading of `try` anybody expects.
     *
     * **Searched without unwinding**, and that is not a detail: when nothing catches it the frame stack
     * has to be exactly as it was, because that is what `breakOnError` stops on. Popping frames while
     * looking would leave the debugger a fault with no stack under it.
     *
     * A frame's own pc is used rather than the raising one, because after the innermost frame each caller
     * is sitting at its CALL — which is the instruction its guard covers.
     *
     * Shared by the two ways a fiber can fault, which is the whole reason it is a function. A synchronous
     * fault comes back through `step`'s own catch; a BLOCKING one arrives later, from the actuator drain,
     * through [tryCompleteAct] — and that path never looked for a handler at all. So `try` worked for
     * everything except the verbs a script most needs it for, since every game action is blocking.
     */
    private fun caught(fiber: Fiber, err: VmError, raisedAt: Int): Boolean {
        var depth = fiber.frames.size
        var found: HandlerRange? = null
        var at = raisedAt
        while (depth > 0) {
            val f = fiber.frames[depth - 1]
            found = f.chunk.handlers.filter { it.covers(at) }.minByOrNull { it.end - it.start }
            if (found != null) break
            depth--
            at = if (depth > 0) fiber.frames[depth - 1].pc - 1 else -1
        }
        val handler = found ?: return false
        while (fiber.frames.size > depth) fiber.frames.removeLast()
        val f = fiber.frames.last()
        fiber.stack[f.base + handler.messageReg] = err.rawMessage
        f.pc = handler.catchPc
        fiber.error = null
        return true
    }

    /**
     * Complete a fiber's in-flight [Op.ACT] if the drain has produced a result.
     *
     * Returns true when the fiber became runnable. When the result has *not* landed, the pending work is
     * **re-offered** — see [PendingAct] for why a single offer is not enough.
     */
    fun tryCompleteAct(fiber: Fiber): Boolean {
        val act = fiber.pendingAct ?: return false
        val done = act.box.get()
        if (done == null) {
            actuator?.offer(act.label, act.work)
            return false
        }
        fiber.pendingAct = null
        val ex = done.exceptionOrNull()
        if (ex != null) {
            val frame = fiber.top
            // **A `VmError` is the host saying something a script can read**, so it is passed through as
            // it is — the same rule the inline path follows. Wrapping it would mean the same verb
            // throwing the same thing reached `catch e` differently depending on whether it happened to
            // run on the drain, which is not something an author can see or reason about.
            val message = if (ex is VmError) ex.rawMessage else "host function '${act.label}' threw: ${ex.message}"
            val err = VmError(
                message,
                frame?.chunk?.name, frame?.pc ?: -1, fiber.currentNodeId(), fiber.stackTrace(), ex,
            )
            fiber.error = err
            // **A blocking verb's failure is an ordinary failure**, and it took this route without ever
            // asking whether anything was catching. `try { depositAll() } catch e { … }` around a game
            // action — the case `try` exists for — never caught, because every game action is blocking and
            // its error arrives here rather than through `step`. The fiber is parked one past the ACT, so
            // the instruction a handler's range has to cover is the one before its pc.
            if (caught(fiber, err, (frame?.pc ?: 0) - 1)) {
                fiber.state = FiberState.RUNNABLE
                return true
            }
            // Uncaught, and held rather than unwound where a debugger is watching — the same choice the
            // synchronous path makes, for the same reason: you want to be looking at the fault, not at a
            // description of it written afterwards.
            if (breakOnError) {
                fiber.state = FiberState.PAUSED
                fiber.pauseReason = PauseReason.ERROR
                fiber.stepMode = StepMode.NONE
                return false
            }
            fiber.state = FiberState.FAILED
            return false
        }
        writeResults(fiber.stack, act.retBase, act.retCount, done.getOrNull())
        fiber.state = FiberState.RUNNABLE
        return true
    }

    /**
     * Release a fiber stopped at a breakpoint.
     *
     * A fiber held by [PauseReason.ERROR] cannot actually continue — the instruction it stopped on failed —
     * so releasing it completes the failure rather than pretending the fault did not happen.
     */
    fun resumeFromPause(fiber: Fiber) {
        if (fiber.state != FiberState.PAUSED) return
        if (fiber.pauseReason == PauseReason.ERROR) {
            fiber.state = FiberState.FAILED
            fiber.pauseReason = PauseReason.NONE
            return
        }
        fiber.pauseReason = PauseReason.NONE
        fiber.state = FiberState.RUNNABLE
    }

    /** Stop [fiber] at its next node boundary — the "pause" button. */
    fun requestPause(fiber: Fiber) {
        if (fiber.isFinished || fiber.state == FiberState.PAUSED) return
        fiber.stepMode = StepMode.INTO
        fiber.stepDepth = fiber.frames.size
    }

    /**
     * Spread a host function's return value across the caller's result window.
     *
     * A multi-result function returns an `Array<Any?>`; a single-result one returns the value itself. The
     * array form is only unpacked when [count] > 1, so a node whose single output *is* an array still works.
     */
    private fun writeResults(stack: Array<Any?>, retBase: Int, count: Int, out: Any?) {
        if (count == 0) return
        if (count == 1) {
            stack[retBase] = adopt(out)
            return
        }
        val arr = out as? Array<*>
            ?: throw VmError("expected $count results as an Array, got ${Values.typeName(out)}")
        for (i in 0 until count) stack[retBase + i] = adopt(arr.getOrNull(i))
    }

    /**
     * A host's value, made fit for the VM to hold.
     *
     * **Kotlin's read-only `List` is not a `MutableList` at RUN time**, and the VM's list ops need one. A
     * node ending in `.toList()` — which most of the scene queries do — hands back `emptyList()` when it
     * found nothing, and that is `kotlin.collections.EmptyList`: `is List<*>` is true, `as? MutableList` is
     * null. So the VM reported "expected a List, got List", a message that reads like a bug in the message
     * and is really the distinction being invisible from inside.
     *
     * Worse, it only happened on the EMPTY result — one match comes back as a Java singleton list, which
     * passes — so a scene query worked until the thing it looked for was not there, which is the moment a
     * script most needs it to answer honestly.
     *
     * Copied once here rather than inside each list op: a host result is adopted a single time, and the
     * value a register holds then answers every op the same way. Nothing is lost by copying — the list
     * nodes are all PURE and hand back new lists rather than editing one — so this is only about the VM's
     * values being uniform by the time anything reads them.
     */
    private fun adopt(v: Any?): Any? = when {
        v is List<*> && v !is MutableList<*> -> ArrayList<Any?>(v)
        // The same rule for maps, and it became load-bearing the moment [Op.SETKEY] existed: a host that
        // hands back an immutable map would give the VM something an in-place write cannot touch, and the
        // failure would be an UnsupportedOperationException from inside a `withEntry` that looks correct.
        v is Map<*, *> && v !is MutableMap<*, *> -> LinkedHashMap<Any?, Any?>(v)
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    private fun asList(v: Any?): MutableList<Any?> = v as? MutableList<Any?>
        ?: throw VmError("expected a List, got ${Values.typeName(v)}")

    @Suppress("UNCHECKED_CAST")
    private fun asMutableMap(v: Any?): MutableMap<Any?, Any?> = v as? MutableMap<Any?, Any?>
        ?: throw VmError("expected a Map, got ${Values.typeName(v)}")

    /** A list read, tolerating the null an unconnected List pin produces — see the note at [Op.APPEND]. */
    @Suppress("UNCHECKED_CAST")
    private fun asListOrEmpty(v: Any?): MutableList<Any?> =
        if (v == null) ArrayList() else asList(v)

    /** The map counterpart, and tolerant for the same reason — see the note at [Op.GETKEY]. */
    @Suppress("UNCHECKED_CAST")
    private fun asMapOrEmpty(v: Any?): MutableMap<Any?, Any?> =
        if (v == null) LinkedHashMap() else asMutableMap(v)

    /** A list cursor for [Op.ITER] / [Op.ITERNEXT]. Mutable position, so iteration survives a park. */
    private class Cursor(val list: MutableList<Any?>) {
        var i = 0
        override fun toString(): String = "Cursor(${i}/${list.size})"
    }

    companion object {
        /**
         * Instructions between deadline checks. Small enough that a 3ms budget is not meaningfully
         * overshot (64 simple instructions is well under a microsecond), large enough that `nanoTime`
         * does not dominate the dispatch loop.
         */
        const val BUDGET_CHECK_INTERVAL = 64
    }
}
