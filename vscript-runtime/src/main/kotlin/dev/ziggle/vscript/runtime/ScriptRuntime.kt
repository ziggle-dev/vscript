package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.runtime.EditorDoc
import dev.ziggle.vscript.host.Clock
import dev.ziggle.vscript.host.SystemClock
import dev.ziggle.vscript.runtime.EditorLog
import dev.ziggle.vscript.runtime.RunLifecycle
import dev.ziggle.vscript.compile.GraphCompileException
import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Issue
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.log.LogLevel
import dev.ziggle.vscript.log.ScriptLog
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.vm.ActuatorSink
import dev.ziggle.vscript.vm.Breakpoints
import dev.ziggle.vscript.vm.StepMode
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.Fiber
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.Interpreter
import dev.ziggle.vscript.vm.Scheduler
import dev.ziggle.vscript.vm.TraceKind

/**
 * Owns the running side of the editor: compile a document, spawn fibers for its entry nodes, and pump the
 * scheduler.
 *
 * [tick] must be called from the client thread — that is the whole point of the VM's design, and it is
 * also why the canvas can read [activeNodes] and fiber state directly with no locking: the editor renders
 * on the same thread.
 */
class ScriptRuntime(
    val catalog: NodeCatalog,
    hosts: HostRegistry,
    clock: Clock = SystemClock,
    private val actuator: ActuatorSink? = null,
    /**
     * Where `import` declarations resolve — see [DocumentSource].
     *
     * Costs nothing for a document that imports nothing: [ImportClosure] short-circuits on an empty
     * import list, so the folder is never scanned.
     */
    private val source: dev.ziggle.vscript.model.GraphSource = dev.ziggle.vscript.model.GraphSource.NONE,
    /**
     * Where a TEXT `import` resolves — see [DocumentSource.asTextSource].
     *
     * Its own seam rather than a projection of [source], because the two answer different questions: the
     * graph side wants a lowered document and the text side wants the file's source, which is the one
     * thing the text front end exists to stop depending on. Both read the same folders and the same
     * naming rule, so a reference means the same file whichever surface asked.
     */
    private val textSource: dev.ziggle.vscript.text.TextSource = dev.ziggle.vscript.text.TextSource.NONE,
    /**
     * The flag `sleepRequested()` reads — the SAME instance the host registry was built with.
     *
     * Passed in rather than created here because the registry is assembled before this class exists (see
     * `ScriptsPanel`), and two instances would be two answers to one question: the script would poll a
     * flag nothing ever set and never yield.
     */
    val runPhase: dev.ziggle.vscript.host.RunPhase = dev.ziggle.vscript.host.RunPhase(),
    /**
     * Per-run and per-frame state the DOMAIN owns — see [RunLifecycle].
     *
     * Defaulted, because a runtime with no domain attached is a legitimate configuration (a test, a
     * headless compile, the standalone shell) and not one that should have to name a no-op.
     */
    private val lifecycle: RunLifecycle = RunLifecycle.NONE,
    /**
     * What an `on tick` handler IS on this host — see [TickMode].
     *
     * [TickMode.PASS] is the client's staged pass, driven through [gameTick]. [TickMode.LOOP] spawns each
     * handler as a long-lived fiber that runs its body once per [tick], after the start fibers are done,
     * and ends it at a pass boundary when asked to sleep. [gameTick] has nothing to do in that mode.
     */
    private val tickMode: TickMode = TickMode.PASS,
    /** What one staged tick pass gets — see [gameTick]. A host sharing one tick among many runtimes tunes it. */
    private val tickBudgetNanos: Long = TICK_BUDGET_NANOS,
) {

    /** The documents [graph] spans, and the one globals layout they agree on. */
    private fun closureOf(graph: dev.ziggle.vscript.model.Graph) =
        if (graph.imports.isEmpty()) dev.ziggle.vscript.model.ImportClosure.single(graph)
        else dev.ziggle.vscript.model.ImportClosure.resolve(graph, source)
    /** Nodes entered / links crossed recently — the canvas's activity highlight and wire flow pulse. */
    private val recentNodes = LinkedHashMap<Int, Long>()
    private val recentLinks = LinkedHashMap<Int, Long>()

    /**
     * The last value each PURE node produced, by node id.
     *
     * Keyed by node alone because a pure node has exactly one data output — that is what makes it an
     * expression. Kept because the inspector cannot find these any other way: a pure node is re-expanded at
     * every use site into a scratch register that is released immediately, so by the time anything is
     * paused and looking, the value is gone. Bounded by the number of nodes in the graph, and cleared with
     * every run.
     *
     * "Last", not "at the pause" — and that is the honest answer for an expression, which does not have a
     * value between evaluations the way a variable does.
     */
    private val pureOutputs = HashMap<Int, Any?>()

    /** What each pure node last evaluated to — the canvas labels their wires with it. */
    val pureValues: Map<Int, Any?> get() = pureOutputs

    /** Structured run output. The engine appends; the console subscribes. See [ScriptLog]. */
    val log = ScriptLog()

    /**
     * node id -> where in the `.vs` source it came from, for the open document — null for a canvas graph.
     *
     * Held here so every [Validator] and [GraphCompiler] this class builds is given it, which is what puts
     * a LINE on a validation error in the problems strip. Without it the panel reported the node id alone,
     * and a file with twenty of the same complaint had nothing to tell them apart. Written by the panel
     * whenever a document is read from text; see `ScriptsPanel.textSpans`.
     */
    var textSpans: Map<Int, dev.ziggle.vscript.lang.Span>? = null

    /**
     * The site table of the current TEXT run — null for a canvas run, which has node ids instead.
     *
     * Richer than [textSpans] and not a replacement for it: this also says which DOCUMENT a site is in,
     * which is what turns "break on line 42" into a breakpoint when the run spans a library and its
     * importer. See `Sites.siteAt`.
     */
    var textSites: dev.ziggle.vscript.text.Sites? = null

    /**
     * The node currently executing, and which execution of it this is.
     *
     * Maintained from the [TraceKind.NODE_ENTER] marker the compiler emits **before** a node's body, which
     * is what lets a host function attribute its output without the VM having to thread a context through
     * every call. The activation counter is what makes "the fifth time round the loop" answerable — see
     * [dev.ziggle.vscript.log.LogRecord.activation].
     */
    private var currentNode = -1
    private var currentActivation = -1
    private val activations = HashMap<Int, Int>()

    /** The document being run, so records can say which graph they came from. */
    private var graphId = ""

    /** Latched at [run] from the setting — see the tracer for why it is not read per node. */
    private var tracing = false

    /** Entry node per fiber, so "finished" is attributed where "began" was. */
    private val entryNodeOf = HashMap<Int, Int>()

    /**
     * The SDK's clock, as the language's own.
     *
     * The adapter lives HERE, on the client side of the seam, because the language declares its own
     * one-method [dev.ziggle.vscript.host.Clock] rather than depending on `vscript-api` — a lexer and a
     * register VM should not drag the game's API onto every consumer's classpath. Both are `fun interface`s
     * over `nowMs()`, so bridging them is this line, and every existing call site still hands over the SDK
     * clock it always did.
     */
    private val vmClock = dev.ziggle.vscript.host.Clock { clock.nowMs() }

    /** The clock a parked fiber's wake time is measured against — see `DebugSession.Context`. */
    internal fun nowMs(): Long = vmClock.nowMs()

    private val interpreter = Interpreter(
        hosts, vmClock, actuator,
        tracer = { id, kind, value ->
            val now = System.nanoTime()
            // The one moment a pure node's result exists — its register is reused immediately after. Kept
            // so the canvas can label the wire leaving a Text node or a comparison, which have no stable
            // slot for the inspector to read at a pause. See [TraceKind.PURE_EXIT].
            if (kind == TraceKind.PURE_EXIT) {
                pureOutputs[id] = value
            } else if (kind != TraceKind.EXEC_EDGE) {
                recentNodes[id] = now
                currentNode = id
                currentActivation = (activations[id] ?: 0) + 1
                activations[id] = currentActivation
                // Read from a field rather than the settings file: this runs once per node executed, and a
                // property lookup on that path would be a cost paid by everyone to serve the few who asked.
                if (tracing) log.add(LogLevel.INFO, "executed", id, currentActivation, graphId, nowNanos = now)
            } else {
                recentLinks[id] = now
            }
        },
    )

    init {
        // Registered here rather than by the caller because it needs THIS runtime's sink and its notion of
        // which node is executing — a host function assembled elsewhere has neither.
        hosts.register("vscript.log", HostKind.INLINE, arity = 2, results = 0) { args ->
            log.add(
                level = LogLevel.of(args.getOrNull(1)),
                message = args.getOrNull(0)?.toString() ?: "",
                nodeId = currentNode,
                activation = currentActivation,
                graphId = graphId,
            )
            null
        }
    }

    val scheduler = Scheduler(interpreter, vmClock, onRunaway = { f ->
        EditorLog.w(TAG, "visual script '${f.name}' suspended: ${f.error?.message}")
    })

    /**
     * Retune one authored literal in the RUNNING script. True if a live chunk owned that pin.
     *
     * So a delay, a colour, a tile can be corrected against what the script is actually doing instead of
     * stop, edit, start, and wait to get back to the same state. Only a value: anything that changes the
     * shape of the graph — a link, a node, a pin's type — still needs a fresh Run, and reports false here.
     *
     * Returning false is not a failure. A pin that is wired has no literal to change, and a pin whose node
     * never made it into the compiled code (an unreachable branch) has no slot; both are ordinary.
     */
    fun setLiteral(nodeId: Int, pin: String, value: Any?): Boolean {
        if (!isRunning) return false
        // A release build interned its constants, so there is no per-pin slot to write and a change would
        // land on every pin that happened to share the value. Refused, not attempted.
        if (!isDebugBuild) return false
        return liveChunks.any { it.setLiteral(nodeId, pin, value) }
    }

    /**
     * Write a graph variable while the graph is running.
     *
     * The debugger's "set value", and a different thing from [setLiteral]: a literal belongs to the
     * DOCUMENT and is retuned in the compiled chunk, so it survives the run and is saved with the script.
     * A variable is RUN STATE — one slot in [globals], shared by every fiber — so writing it changes what
     * happens next and leaves the script on disk alone. That is the right way round for a debugger:
     * nudging a counter to reproduce a case must not quietly edit the program.
     *
     * Returns false rather than throwing when the name has no slot. A variable nothing reads is not
     * compiled into the layout at all, and that is ordinary rather than an error.
     */
    /**
     * Read a graph variable by name, or null when nothing is holding one.
     *
     * The counterpart [setVariable] never had. Null is ambiguous on purpose — a variable nothing reads is
     * not compiled into the layout at all, and a variable that genuinely holds nothing is the same answer
     * — because the alternative is a second return channel for a case no caller can act on differently.
     *
     * Works while ASLEEP, and deliberately: the globals are still there until the next run re-seeds them,
     * and "what did it know when it went to sleep" is exactly the question worth asking then.
     */
    fun variable(name: String): Any? {
        val slot = liveChunks.firstNotNullOfOrNull { it.slots.variables[name] } ?: return null
        return globals.getOrNull(slot)
    }

    fun setVariable(name: String, value: Any?): Boolean {
        if (!isRunning) return false
        val slot = liveChunks.firstNotNullOfOrNull { it.slots.variables[name] } ?: return false
        val g = globals
        if (slot !in g.indices) return false
        g[slot] = value
        return true
    }

    /** Issues from the last compile, for the canvas outline and the problems list. */
    var issues: List<Issue> = emptyList()
        private set

    /** Fibers started by the last [run], newest first. */
    var fibers: List<Fiber> = emptyList()
        private set

    /**
     * The On Stop handlers, compiled at [run] and spawned when the script ends.
     *
     * Compiled up front so a broken handler is a compile error you see on Run, not a surprise at the one
     * moment you were relying on it to report something.
     */
    private var stopChunks: List<RunEntry> = emptyList()

    /** Chunks from the last [run], kept so [setLiteral] can retune them mid-flight. */
    private var liveChunks: List<dev.ziggle.vscript.vm.Chunk> = emptyList()

    /** The per-frame draw entries, compiled at [run]. See [renderFrame]. */
    private var renderChunks: List<RunEntry> = emptyList()

    /** The per-game-tick entries, compiled at [run]. See [gameTick]. */
    private var tickChunks: List<RunEntry> = emptyList()

    /**
     * The tick handlers compiled as LOOPS — [TickMode.LOOP] — and the fibers they became.
     *
     * Held back until the start fibers have finished, like [pendingWork] is held behind the wake, and for
     * the same reason: `on start` is the one-time preparation in this mode, and a loop reading what it set
     * up must not run its first pass before it is done. See [spawnTickLoops].
     */
    private var tickLoops: List<RunEntry> = emptyList()
    private var loopsPending = false
    private val loopFiberIds = HashSet<Int>()

    /**
     * The On Wake handlers, compiled at [run] and spawned BEFORE the work — see [Phase.WAKING].
     *
     * Compiled with everything else for [stopChunks]' reason, and one more: a preparation phase that will
     * not compile should stop the Run rather than leave a script standing in a bank with no loop.
     */
    private var wakeChunks: List<RunEntry> = emptyList()

    /** The On Sleep handlers, compiled at [run] and spawned once the work has quiesced. */
    private var sleepChunks: List<RunEntry> = emptyList()

    /**
     * The START entries, held back while [Phase.WAKING] runs.
     *
     * They are compiled with everything else and spawned by [beginWork], which is what makes "the wake
     * finishes before the loop starts" true. It cannot be a `Fiber.waitsFor` edge: [Fiber.isSettled]
     * counts `AWAITING_ACT` as settled, so the first time a wake handler asked the actuator to walk
     * somewhere, every work fiber would be released to run beside it — one script, two hands, one drain.
     */
    private var pendingWork: List<RunEntry> = emptyList()

    /**
     * Where this run is in its own life.
     *
     * Sleep is not a new suspension model — it is [stop] and [run] with a phase inserted between them, so
     * everything here is about WHEN fibers are spawned rather than about how they suspend.
     */
    enum class Phase {
        /** Nothing loaded, or the last run was stopped. */
        IDLE,

        /** On Wake handlers are running. The work has been compiled and is waiting. */
        WAKING,

        /** The work is running. */
        RUNNING,

        /**
         * A sleep has been asked for and the work has not finished yet.
         *
         * **Nothing is cancelled here.** The script is still acting, and finishing what it was doing is
         * the entire point of asking rather than stopping.
         */
        QUIESCING,

        /** The work was killed on the quiesce deadline; waiting for the drain before handing over. */
        DRAINING,

        /** On Sleep handlers are running. They may walk and bank. */
        SLEEPING,

        /** Down, with its state wherever On Sleep put it. [wake] starts it again. */
        ASLEEP,
    }

    var phase: Phase = Phase.IDLE
        private set

    /** Why the current sleep was asked for, for the log and the panel. */
    var sleepReason: String = ""
        private set

    /** When the phase deadline expires, or 0 when the current phase has none. */
    private var phaseDeadlineMs: Long = 0L

    /** When the script went to sleep, so the panel can say how long ago. */
    var sleptAtMs: Long = 0L
        private set

    /** False when the last sleep was forced past its deadline rather than yielded to. */
    var sleptCleanly: Boolean = true
        private set

    /** Set once per sleep, so the handlers are spawned exactly once. See [stopFired] for the pattern. */
    private var sleepFired = false

    /** The sleep handler being run, so they go one at a time. See [beginSleepHandlers]. */
    private var sleepHandlerAt = 0

    /** The wake handler being run, so they go one at a time. */
    private var wakeHandlerAt = 0

    /** True while a run is asleep and could be woken. */
    val isAsleep: Boolean get() = phase == Phase.ASLEEP

    /**
     * Every fiber this run started has finished.
     *
     * Deliberately NOT `!isRunning`, which is true only when there is also no handler-only work — a
     * watchdog makes [isRunning] true for ever, and one asked to sleep has nothing to finish and must be
     * allowed to quiesce immediately rather than never.
     */
    private val workDone: Boolean get() = fibers.none { !it.isFinished }

    /** A debugger pause must not burn a handoff deadline — see [tick]. */
    private val anyPaused: Boolean get() = fibers.any { it.state == FiberState.PAUSED }

    /**
     * Its own scheduler, with its own budget.
     *
     * Separate from the main one because the two answer to different clocks: the main pass may spend its
     * budget across several fibers over many ticks, while a render pass has to finish inside the frame it
     * is drawing or not be shown at all.
     */
    private val renderScheduler by lazy {
        dev.ziggle.vscript.vm.Scheduler(interpreter, budgetNanos = RENDER_BUDGET_NANOS)
    }

    /**
     * Its own scheduler again, and for the same reason: a different clock.
     *
     * A tick pass is not a frame pass with a longer budget — they interleave, each has to be abandoned on
     * its own terms, and a fiber left half-run in a shared scheduler by one would be resumed by the other
     * on the wrong clock entirely.
     */
    private val tickScheduler by lazy {
        dev.ziggle.vscript.vm.Scheduler(interpreter, budgetNanos = tickBudgetNanos)
    }

    /** Reported once per run, not per frame — at 50fps a level-triggered message is a wall of text. */
    private var renderFaultReported = false

    /** The same edge for the tick pass. Rarer than a frame, still ~100 lines a minute if levelled. */
    private var tickFaultReported = false

    /**
     * Run every On Render entry, once, for this frame.
     *
     * Called from the render pass rather than the tick, which is the whole point: the loop draws at its
     * own rate — a pass every few hundred milliseconds, longer while it walks — and anything animated at
     * that rate stutters. Here a pulse or a sweep is computed from the frame.
     *
     * A pass is all-or-nothing. It gets [RENDER_BUDGET_NANOS] and is abandoned if it runs over, and then
     * both what it drew and what it wrote are thrown away: a half-drawn frame is not a smaller truth, and
     * half-applied writes are worse than none. The script itself keeps running — a debug overlay that is
     * too expensive should cost you the overlay, never the run.
     */
    fun renderFrame() {
        val chunks = renderChunks
        if (chunks.isEmpty() || !isRunning) {
            // **The immediate layer lives only as long as a pass feeds it.** An `on render` draw carries no
            // lease — a frame IS its lifetime — so nothing expires it; what keeps it honest is being
            // replaced by the next pass. The moment there are no passes, the last completed one would stay
            // on screen for ever, which is what a finished script's panel hanging about actually was.
            //
            // Re-asserted every frame rather than caught at the end of a run, because "the run ended" is
            // not a single moment this can be hooked to: a fiber can finish, crash, be stopped, or be
            // replaced, and the last render pass may land on the other thread after any of them. Cheap
            // when there is nothing to clear.
            lifecycle.clearDrawing()
            return
        }
        var ok = true
        lifecycle.beginRenderFrame()
        interpreter.beginStaged()
        try {
            for (entry in chunks) {
                val nodeId = entry.site
                val f = renderScheduler.spawn("render#$nodeId", entry.chunk)
                // One pass with the whole budget. Unfinished means it did not fit in a frame, and
                // resuming it next frame would draw half of one state and half of another.
                renderScheduler.tick()
                if (!f.isFinished) {
                    ok = false
                    val parked = f.state == FiberState.PARKED || f.state == FiberState.AWAITING_ACT
                    renderScheduler.kill(f)
                    // **"Over budget" and "it waited" are different faults**, and reporting the second as
                    // the first sends the reader to optimise a pass that parked in its first microsecond.
                    // A one-shot pass cannot wait: unfinished is discarded, so a wait means the pass never
                    // lands and the frame draws nothing, for ever.
                    report(
                        entry, nodeId,
                        if (parked) "render waited — a render pass cannot wait, so nothing it drew was kept"
                        else "render did not finish in ${RENDER_BUDGET_NANOS / 1_000_000}ms — it is drawing too much, or reading too much scene, for one frame",
                    )
                } else f.error?.let {
                    ok = false
                    report(entry, it.nodeId, it.rawMessage)
                }
            }
        } catch (t: Throwable) {
            ok = false
            report(null, -1, t.message ?: t.toString())
        } finally {
            renderScheduler.reap()
            // In a finally, because staged writes left open would swallow every write the MAIN fibers
            // make on the next tick — a failure here must not become a failure everywhere.
            if (ok) interpreter.commitPending() else interpreter.discardPending()
            lifecycle.endRenderFrame(ok)
        }
    }

    /** One render complaint per run: the same fault recurs every frame and says nothing new after the first. */
    private fun report(entry: RunEntry?, nodeId: Int, message: String) {
        if (renderFaultReported) return
        renderFaultReported = true
        log.add(LogLevel.ERROR, message, nodeId, graphId = graphIdOf(entry))
        EditorLog.w(TAG, "render entry fault: $message")
    }

    /**
     * Which document a fault belongs to.
     *
     * An imported handler's node id means nothing in the ROOT document — the console would anchor the
     * complaint to whatever node happens to share that number there, which is worse than not anchoring it,
     * because it points confidently at innocent code.
     */
    private fun graphIdOf(entry: RunEntry?): String =
        entry?.documentId?.ifBlank { null } ?: graphId

    /**
     * Run every On Tick entry, once, for this game tick.
     *
     * **Driven by the SERVER tick, not by a timer.** A 600ms timer and the game's tick are the same length
     * and not the same beat: they drift within a minute, and a handler that fires 600ms after the last one
     * it fired is answering a question about a world that moved at some other moment. Counting these has to
     * be counting ticks, or the whole point of having this rather than a Delay is gone.
     *
     * It is the twin of [renderFrame] and the differences are the interesting part:
     *
     * - **No [RunLifecycle] render frame.** A tick is for noticing, not for drawing. Anything painted
     *   here would be painted outside a frame and belong to no lease.
     * - **A bigger budget** — [TICK_BUDGET_NANOS] against the frame's, because this fires ~30× less often.
     *   Still well under the 8ms at which `dev.ziggle.plugin.PluginTicks` calls a client-thread hook slow,
     *   which is the honest ceiling here: this rides the same thread the game does.
     *
     * The rest is identical, deliberately. A pass is all-or-nothing: over budget and both what it did and
     * what it wrote are thrown away, because half a decision is worse than none. The script keeps running —
     * a watchdog too expensive to afford should cost you the watchdog, never the run.
     */
    fun gameTick() {
        val chunks = tickChunks
        if (chunks.isEmpty() || !isRunning) return
        var ok = true
        // Safe beside the render pass's staging without any coordination because both run on the CLIENT
        // THREAD — the frame, the client tick and the game tick are dispatched from the same one, so two
        // passes can never be open at once. If that ever stops being true this is the first thing to break.
        interpreter.beginStaged()
        try {
            for (entry in chunks) {
                val nodeId = entry.site
                val f = tickScheduler.spawn("tick#$nodeId", entry.chunk)
                // One pass with the whole budget, as for a frame: resuming next tick would spread one
                // decision across two states of the world, which is the bug this is meant to prevent.
                tickScheduler.tick()
                if (!f.isFinished) {
                    ok = false
                    val parked = f.state == FiberState.PARKED || f.state == FiberState.AWAITING_ACT
                    tickScheduler.kill(f)
                    // See [report]: a pass that waited did not run out of time, and saying it did sends
                    // the reader looking for work to remove that is not there.
                    reportTick(
                        entry, nodeId,
                        if (parked) "On Tick waited — a tick pass cannot wait, so everything it wrote was discarded"
                        else "On Tick did not finish in ${TICK_BUDGET_NANOS / 1_000_000}ms — it is doing too " +
                            "much, or reading too much scene, for one tick",
                    )
                } else f.error?.let {
                    ok = false
                    reportTick(entry, it.nodeId, it.rawMessage)
                }
            }
        } catch (t: Throwable) {
            ok = false
            reportTick(null, -1, t.message ?: t.toString())
        } finally {
            tickScheduler.reap()
            // In a finally for the same reason as the render pass: staged writes left open would swallow
            // every write the main fibers make afterwards.
            if (ok) interpreter.commitPending() else interpreter.discardPending()
        }
    }

    /** One tick complaint per run — see [report], which is the same edge for the other clock. */
    private fun reportTick(entry: RunEntry?, nodeId: Int, message: String) {
        if (tickFaultReported) return
        tickFaultReported = true
        log.add(LogLevel.ERROR, message, nodeId, graphId = graphIdOf(entry))
        EditorLog.w(TAG, "tick entry fault: $message")
    }

    /**
     * One group's entries, across the closure, with any dropped library said OUT LOUD.
     *
     * A skipped document is the quiet failure this whole path can have: an imported handler that will not
     * validate simply stops happening, and "my overlay disappeared" is a bad way to find out that a
     * library has a fault in it. Reported once per run, at WARN — the run itself is fine, which is the
     * point of skipping rather than failing.
     */
    private fun compiled(
        compiler: GraphCompiler,
        graph: dev.ziggle.vscript.model.Graph,
        group: dev.ziggle.vscript.compile.EntryGroup,
        /**
         * The run's ONE program — see [run].
         *
         * A helper called from `on start` and from `on tick` is one function, and it has to compile to one
         * chunk with one constant pool or [setLiteral] retunes whichever copy it reaches first and leaves
         * the other holding the old value.
         */
        table: dev.ziggle.vscript.vm.ProgramBuilder,
    ): List<RunEntry> {
        val result = compiler.compileEntries(graph, group, table)
        for (dropped in result.skipped) {
            val why = dropped.errors.firstOrNull()?.message ?: "it does not validate"
            log.add(
                LogLevel.WARN,
                "'${dropped.document.name}' has an error, so its handlers will not run: $why",
                graphId = graphId,
            )
            EditorLog.w(TAG, "skipped ${group.name} entries from '${dropped.document.name}': $why")
        }
        return result.entries.map { RunEntry.of(it, graph) }
    }

    /** Fired once per run. Stopping twice, or finishing and then being stopped, must not run it twice. */
    private var stopFired = false

    /**
     * Whether this run's paint has already been dropped. An EDGE, like [stopFired].
     *
     * `!isRunning` is true on every tick after the last fiber ends, so clearing on the level would wipe the
     * screen ~50 times a second — and with it anything an On Stop handler drew, since those are fibers that
     * start after this point.
     */
    private var drawsDropped = false

    /**
     * This run has no fibers at all — every entry it has is a handler. See [run].
     *
     * Kept as its own flag rather than recomputed from `tickChunks.isNotEmpty()`, because the question is
     * not "are there handlers" (a normal script has those too, and must still end when its work ends) but
     * "was there ever any work". A watchdog runs until Stop; a script with an `on start` that finishes is
     * over, and its handlers go quiet with it.
     */
    private var handlersOnly = false

    /** True while anything is still going, the stop handlers included. */
    val isRunning: Boolean get() = fibers.any { !it.isFinished } || handlersOnly

    /** The run's graph variables. Shared by every fiber — see [dev.ziggle.vscript.vm.Interpreter.globals]. */
    val globals: Array<Any?> get() = interpreter.globals

    /** Breakpoints — shared with the interpreter, so arming one takes effect immediately. */
    val breakpoints: Breakpoints get() = interpreter.breakpoints

    /**
     * Whether a run should TRACE — log every node it enters, not just what nodes explicitly emit.
     *
     * **A question the runtime is given, not one it goes and asks.** This read the editor's persisted
     * preferences directly, which put the runtime downstream of them — and that preferences object in turn
     * imports from the canvas, the run views and this package, so the runtime was reaching around a cycle
     * to find a boolean. It was the last thing keeping this module from depending on nothing but the
     * language.
     *
     * A function rather than a `Boolean`, because the answer may change between runs and the point is that
     * whoever owns the setting keeps owning it. Defaults to off, which is what a headless run wants: a
     * trace is for a graph doing something you cannot account for, and a tick routine produces thousands
     * of rows a second the rest of the time.
     */
    var traceRequested: () -> Boolean = { false }

    /** Hold a faulting fiber on the failing node rather than unwinding it. */
    var breakOnError: Boolean
        get() = interpreter.breakOnError
        set(v) { interpreter.breakOnError = v }

    /** The entry node a fiber was started from — the identity a context is known by. */
    fun entryNodeOf(fiberId: Int): Int = entryNodeOf[fiberId] ?: -1

    /** How many times [nodeId] has been entered this run. */
    fun activationOf(nodeId: Int): Int = activations[nodeId] ?: 0

    fun resumeFiber(fiberId: Int) {
        fibers.firstOrNull { it.id == fiberId }?.let { interpreter.resumeFromPause(it) }
    }

    fun pauseFiber(fiberId: Int) {
        fibers.firstOrNull { it.id == fiberId }?.let { interpreter.requestPause(it) }
    }

    fun stepFiber(fiberId: Int, mode: StepMode) {
        fibers.firstOrNull { it.id == fiberId }?.let { interpreter.requestStep(it, mode) }
    }

    /**
     * Validate without compiling — cheap enough to run every frame the document changes.
     *
     * [report] writes the issues into the console. Off by default because this also runs on undo, on open
     * and after every edit; echoing the same three warnings into the log on every keystroke would bury the
     * run output it exists to show. The Check button asks for it explicitly.
     */
    fun validate(doc: EditorDoc, report: Boolean = false): List<Issue> {
        issues = Validator(catalog, source, textSpans.orEmpty()).validate(doc.toGraph())
        if (report) {
            issues.forEach {
                val level = if (it.severity == Severity.ERROR) LogLevel.ERROR else LogLevel.WARN
                // The LINE first when there is one. A document imported from text produces its problems in
                // bulk, and twenty copies of one sentence with only a node id to tell them apart is not
                // something an author can act on.
                val where = it.span.takeIf { s -> s.line > 0 }?.let { s -> "line ${s.line}: " } ?: ""
                log.add(level, where + it.message, it.nodeId ?: -1, graphId = doc.id)
            }
            if (issues.isEmpty()) log.add(LogLevel.INFO, "no problems", graphId = doc.id)
        }
        return issues
    }

    /**
     * Compile and start every entry node in [doc].
     *
     * @return null on success, or a message describing why it would not compile.
     */
    /**
     * Whether the last [run] was a **debug** build.
     *
     * Release builds carry no `TRACE` markers, so breakpoints and the flow animation have nothing to fire
     * on, and their literals are pooled by value, so live editing has no slot to write. Both are reported
     * rather than silently ignored — see [setLiteral] and [run].
     */
    var isDebugBuild: Boolean = true
        private set

    /**
     * Spawn a compiled run — the half of [run] that never knew what a graph was.
     *
     * By the time anything gets here the six handler groups are compiled into one program and sitting in
     * their fields; all that is left is deciding whether there is anything to run, publishing the chunks
     * the debugger reads, and starting either the wake or the work. None of that asks which front end
     * produced the entries, which is what lets a text-compiled program reach it unchanged.
     *
     * @return an error message, or null when the run started.
     */
    private fun begin(startEntries: List<RunEntry>, label: String): String? {
        stopFired = false
        drawsDropped = false
        renderFaultReported = false
        tickFaultReported = false
        val chunks = startEntries.associate { it.site to it.chunk }
        // A script whose ONLY entries are handlers is a real script — a watchdog, an overlay — and it
        // runs until you stop it. Checked after the handlers are compiled, not before, because "no
        // entry node" used to mean "no FIBER entry" and so refused to run a perfectly good document
        // whose whole job was to watch. It is still an error to run a document with no entries at all.
        // A wake counts as something to run. A document whose whole job is to arrange the world — put
        // the gear on, walk somewhere, open a bank — is a script, and refusing it "no entry node"
        // would be the same mistake `handlersOnly` was added to fix, one entry kind later.
        handlersOnly = chunks.isEmpty() && (tickChunks.isNotEmpty() || renderChunks.isNotEmpty())
        // A tick LOOP is a fiber, so a document that is only `on tick` has work in the ordinary sense and
        // is not handlers-only; it just has nothing to run before the loop starts.
        if (chunks.isEmpty() && !handlersOnly && wakeChunks.isEmpty() && tickLoops.isEmpty()) {
            log.add(LogLevel.ERROR, "no entry node to run", graphId = graphId)
            log.endRun()
            return "no entry node to run"
        }
        liveChunks = startEntries.map { it.chunk } + stopChunks.map { it.chunk } +
            renderChunks.map { it.chunk } + tickChunks.map { it.chunk } + tickLoops.map { it.chunk } +
            wakeChunks.map { it.chunk } + sleepChunks.map { it.chunk }
        // Part of the trace, not of the log. With tracing off the console holds only what the graph
        // actually said and what actually went wrong — a pair of bookend rows on every run is exactly
        // the kind of engine chatter that trains you to stop reading the panel.
        if (tracing) chunks.keys.forEach { log.add(LogLevel.INFO, "execution began", it, 0, graphId) }
        // Preparation first, and the work does not exist as a fiber until it is done — see
        // [pendingWork]. With no On Wake this falls straight through to [beginWork] and the run starts
        // exactly as it always has.
        pendingWork = startEntries
        if (wakeChunks.isEmpty()) {
            beginWork()
        } else {
            phase = Phase.WAKING
            phaseDeadlineMs = vmClock.nowMs() + WAKE_MS
            spawnNextWakeHandler()
        }
        EditorLog.i(TAG, "started '$label' — ${fibers.size} fiber(s), phase $phase")
        return null
    }

    /**
     * Everything a run clears before it compiles anything — the same for either front end.
     *
     * Split out of [run] when text stopped arriving as a `Graph`. Nothing here reads a document; it reads
     * [id], [debug] and whether this is a [resume], and every line of it was already true of a graph run.
     */
    private fun resetForRun(id: String, debug: Boolean, resume: Boolean) {
        stop(runHandlers = false)
        reported.clear()
        lastError = null
        graphId = id
        activations.clear()
        pureOutputs.clear()
        tickLoops = emptyList()
        loopsPending = false
        loopFiberIds.clear()
        entryNodeOf.clear()
        currentNode = -1
        currentActivation = -1
        isDebugBuild = debug
        tracing = debug && traceRequested()
        // Whatever asked for the last sleep is answered; leaving it set would have the loop we are about
        // to start quiesce on its very first pass, which reads as "wake does nothing".
        runPhase.clear()
        sleepReason = ""
        sleepFired = false
        sleepHandlerAt = 0
        wakeHandlerAt = 0
        sleptCleanly = true
        // A hit condition counts arrivals within ONE run; carrying them over would make "break on the 47th"
        // mean something different every time you pressed Run.
        breakpoints.resetHits()
        // A fresh run starts a fresh record: keeping the previous run's lines would make relative
        // timestamps meaningless, and "which run was that from" is the first thing you would then ask.
        //
        // A RESUME is not a fresh run. A script that sleeps hourly would otherwise lose its console every
        // hour, and the sleep/wake pair is one story — so the record carries on and says so instead.
        if (!resume) {
            log.clear()
            log.beginRun()
        } else {
            log.add(LogLevel.INFO, "woke", graphId = graphId)
        }
        // AFTER the log is cleared for this run, or the warning is wiped by the very next line. TRACE is
        // what a breakpoint fires on, so a release build cannot honour one; said out loud because an armed
        // breakpoint that never hits reads as a broken debugger rather than as a choice.
        if (!debug && breakpoints.entries().isNotEmpty()) {
            log.add(
                LogLevel.WARN,
                "release build: ${breakpoints.entries().size} breakpoint(s) will not fire, and values " +
                    "cannot be edited while it runs — use Debug for that",
                graphId = graphId,
            )
        }
    }

    /**
     * Start the script. [debug] builds for the editor; false is the release build.
     *
     * Two modes, on purpose, and the split is the one every IDE makes: the debugging apparatus costs real
     * instructions — a `TRACE` per node AND per exec edge — and a script that is merely meant to run should
     * not pay for a canvas nobody is watching.
     */
    @JvmOverloads
    fun run(doc: EditorDoc, debug: Boolean = true, resume: Boolean = false): String? {
        resetForRun(doc.id, debug, resume)
        val graph = doc.toGraph()
        // Seed the variables before anything can read one. Their INDEX is their position in the document,
        // offset by where that document sits in the import closure — which is what lets every chunk agree
        // on which slot is which without being told, ACROSS documents as well as within one. Seeding from
        // the graph alone would give an importing run only the root's variables, and every imported one
        // would read null from a slot nothing had sized.
        interpreter.resetGlobals(dev.ziggle.vscript.compile.startingGlobals(closureOf(graph)))
        issues = Validator(catalog, source, textSpans.orEmpty(), tickMayWait = tickMode == TickMode.LOOP).validate(graph)
        if (issues.any { it.severity == Severity.ERROR }) {
            val n = issues.count { it.severity == Severity.ERROR }
            issues.filter { it.severity == Severity.ERROR }
                .forEach { log.add(LogLevel.ERROR, it.message, it.nodeId ?: -1, graphId = graphId) }
            log.endRun()
            return "graph has $n error(s)"
        }
        return try {
            val compiler = GraphCompiler(catalog, debug, source, textSpans.orEmpty(), tickLoop = tickMode == TickMode.LOOP)
            // ONE program for the whole run, shared by every group below. The groups are compiled
            // separately — they are four different questions — but the functions they call are the same
            // functions, and compiling a helper once per group is what used to give a shared helper a
            // literal slot per group. See docs/LINKER_PLAN.md §2.2.
            val table = dev.ziggle.vscript.vm.ProgramBuilder()
            // Every entry across the IMPORT CLOSURE, not just the root's. A library's own `on render` /
            // `on tick` / `on stop` is the one thing an import never carried, so a document that wanted to
            // draw or watch on its own behalf had to have that hoisted into whatever ran it.
            val startEntries = compiled(compiler, graph, dev.ziggle.vscript.compile.EntryGroup.START, table)
            // Compiled now, run later. A handler that will not compile should fail the Run, not the stop.
            stopChunks = compiled(compiler, graph, dev.ziggle.vscript.compile.EntryGroup.STOP, table)
            renderChunks = compiled(compiler, graph, dev.ziggle.vscript.compile.EntryGroup.RENDER, table)
            tickChunks = compiled(compiler, graph, dev.ziggle.vscript.compile.EntryGroup.TICK, table)
            // In LOOP mode a tick handler is a fiber, not a pass: it leaves [tickChunks] so that nothing
            // drives it as one, and waits in [tickLoops] for [beginWork].
            if (tickMode == TickMode.LOOP) {
                tickLoops = tickChunks
                tickChunks = emptyList()
            }
            wakeChunks = compiled(compiler, graph, dev.ziggle.vscript.compile.EntryGroup.WAKE, table)
            sleepChunks = compiled(compiler, graph, dev.ziggle.vscript.compile.EntryGroup.SLEEP, table)
            begin(startEntries, doc.name)
        } catch (e: GraphCompileException) {
            issues = e.issues
            e.issues.forEach { log.add(LogLevel.ERROR, it.message, it.nodeId ?: -1, graphId = graphId) }
            log.endRun()
            "compile failed: ${e.issues.size} issue(s)"
        } catch (e: Throwable) {
            EditorLog.e(TAG, "compile of '${doc.name}' failed", e)
            log.add(LogLevel.ERROR, "compile failed: ${e.message}", graphId = graphId)
            log.endRun()
            "compile failed: ${e.message}"
        }
    }

    /**
     * Run a `.vs` SOURCE file, compiled by the text front end.
     *
     * The twin of [run], and the differences are the whole of what the second front end costs the runtime:
     * there is no `Graph`, no `Validator` pass (the resolver already refused what a validator would have)
     * and no node ids from a canvas — an authoring site is a `Sites` id over the source text, which is the
     * same opaque int the VM has always carried.
     *
     * Everything after compiling is [begin], shared with the graph path: the same phase machine, the same
     * wake-before-work rule, the same debugger.
     *
     * Takes a compilation rather than source, because compiling is not idempotent in the one way that
     * matters: sites are keyed by AST identity, so compiling the same file twice mints two sets of ids,
     * and a breakpoint armed against one set while the other runs is a breakpoint that never fires. The
     * panel compiles at import and keeps it — see `ScriptsPanel.TextScript`.
     *
     * @param id what the console and the breakpoint sidecar key this script by.
     * @param name what its fibers are called.
     * @return an error message, or null when the run started.
     */
    fun runText(
        compiled: dev.ziggle.vscript.text.TextFrontEnd.Compilation,
        sites: dev.ziggle.vscript.text.Sites,
        id: String,
        name: String,
        resume: Boolean = false,
    ): String? {
        // Whether the debug apparatus is there was decided when this was compiled — see
        // `TextCompiler.debug`. Told rather than asked, so the console's release warning is honest.
        val debug = compiled.debug
        resetForRun(id, debug, resume)
        // Published before anything can fail: a diagnostic's line is the useful half of it, and the sites
        // are what the editor arms breakpoints against.
        textSpans = sites.spans()
        textSites = sites
        if (!compiled.ok) {
            // The line goes in the message. Every other complaint in this console is anchored to an
            // authoring id and rendered through [textSpans]; a compile error has no id, because the thing
            // it would name is what failed to compile.
            compiled.errors.forEach {
                log.add(LogLevel.ERROR, "line ${it.span.line}: ${it.message}", graphId = graphId)
            }
            log.endRun()
            return "script has ${compiled.errors.size} error(s)"
        }
        return try {
            // Sized for the whole closure before anything can read a slot — see [run] for why the root's
            // own variables are not enough.
            interpreter.resetGlobals(compiled.globals)
            fun group(kind: dev.ziggle.vscript.lang.EntryKind) =
                compiled.entries[kind].orEmpty().map { RunEntry.of(it) }
            stopChunks = group(dev.ziggle.vscript.lang.EntryKind.STOP)
            renderChunks = group(dev.ziggle.vscript.lang.EntryKind.RENDER)
            tickChunks = group(dev.ziggle.vscript.lang.EntryKind.TICK)
            wakeChunks = group(dev.ziggle.vscript.lang.EntryKind.WAKE)
            sleepChunks = group(dev.ziggle.vscript.lang.EntryKind.SLEEP)
            begin(group(dev.ziggle.vscript.lang.EntryKind.START), name)
        } catch (e: Throwable) {
            EditorLog.e(TAG, "starting '$name' failed", e)
            log.add(LogLevel.ERROR, "could not start: ${e.message}", graphId = graphId)
            log.endRun()
            "could not start: ${e.message}"
        }
    }

    /**
     * Run ONE user function on its own, with arguments you supply.
     *
     * The graph's entry nodes are not started — only this function's body — so it is the function that is
     * under test rather than the script around it. Everything else is a normal run: the variables are
     * seeded, the log records, the debugger can break inside it.
     *
     * No On Stop handlers fire when it finishes. They report on a *run*, and this is not one; firing them
     * would write a run report about a script that never started.
     *
     * @return an error message, or null when the fiber was spawned. Its result arrives later — the call
     *   returns as soon as the fiber exists, because a function that walks somewhere takes minutes.
     */
    fun runFunction(doc: EditorDoc, name: String, args: List<Any?>): String? {
        stop(runHandlers = false)
        reported.clear()
        lastError = null
        graphId = doc.id
        activations.clear()
        pureOutputs.clear()
        tickLoops = emptyList()
        loopsPending = false
        loopFiberIds.clear()
        entryNodeOf.clear()
        currentNode = -1
        currentActivation = -1
        tracing = traceRequested()
        breakpoints.resetHits()
        log.clear()
        log.beginRun()
        val graph = doc.toGraph()
        val fn = graph.function(name) ?: return "no function named '$name'"
        if (args.size != fn.params.size) {
            return "'$name' takes ${fn.params.size} argument(s) — " +
                fn.params.joinToString { "${it.name}: ${it.type}" }.ifEmpty { "none" }
        }
        interpreter.resetGlobals(dev.ziggle.vscript.compile.startingGlobals(closureOf(graph)))
        issues = Validator(catalog, source, textSpans.orEmpty(), tickMayWait = tickMode == TickMode.LOOP).validate(graph)
        if (issues.any { it.severity == Severity.ERROR }) {
            val n = issues.count { it.severity == Severity.ERROR }
            issues.filter { it.severity == Severity.ERROR }
                .forEach { log.add(LogLevel.ERROR, it.message, it.nodeId ?: -1, graphId = graphId) }
            log.endRun()
            return "graph has $n error(s)"
        }
        return try {
            // Always a debug build. Running ONE function on its own is an inspection tool — you are here to
            // watch it, step it, or read what it returned — so the apparatus it needs is the point.
            isDebugBuild = true
            val chunk = GraphCompiler(catalog, debug = true, source = source, spans = textSpans.orEmpty()).compileFunction(graph, name)
            // No stop handlers: see the note above.
            stopChunks = emptyList()
            liveChunks = listOf(chunk)
            stopFired = true
            drawsDropped = false
            val entryNode = graph.entryOf(name)?.id ?: -1
            fibers = listOf(
                scheduler.spawn("$name()", chunk, args).also { entryNodeOf[it.id] = entryNode }
            )
            EditorLog.i(TAG, "called '$name' with ${args.size} argument(s)")
            null
        } catch (e: GraphCompileException) {
            issues = e.issues
            e.issues.forEach { log.add(LogLevel.ERROR, it.message, it.nodeId ?: -1, graphId = graphId) }
            log.endRun()
            "compile failed: ${e.issues.size} issue(s)"
        } catch (e: Throwable) {
            EditorLog.e(TAG, "call of '$name' failed", e)
            log.add(LogLevel.ERROR, "call failed: ${e.message}", graphId = graphId)
            log.endRun()
            "call failed: ${e.message}"
        }
    }

    fun stop() = stop(runHandlers = true)

    /**
     * Kill the run, optionally giving the On Stop handlers their turn.
     *
     * [runHandlers] is false when a NEW run is about to replace this one: firing the old handlers and then
     * immediately discarding their fibers would report on a run nobody is looking at any more, and leave
     * them running in the scheduler after this list has moved on.
     */
    private fun stop(runHandlers: Boolean) {
        // Whatever the script drew goes with it. Leases would expire on their own within the second, but a
        // stopped script should leave nothing behind AT ALL — including a FOREVER marker, which is the one
        // case the lease cannot clean up by itself.
        lifecycle.endRun()
        renderChunks = emptyList()
        tickChunks = emptyList()
        drawsDropped = true
        reported.clear()
        // Before killing the fibers, not after: a blocking verb is running on another thread and killing the
        // fiber that is waiting on it does nothing to the verb. Stop means the avatar stops, so the drain
        // has to be told — otherwise Stop leaves you still walking to wherever the graph was sending you.
        actuator?.cancel()
        // A handler-only run counts as "there was something here" even with no fibers to kill, or its log
        // run is opened by [run] and never closed — and the next Run's records would append to it.
        val had = fibers.isNotEmpty() || handlersOnly
        handlersOnly = false
        loopsPending = false
        loopFiberIds.clear()
        fibers.forEach { scheduler.kill(it) }
        scheduler.reap()
        fibers = emptyList()
        recentNodes.clear()
        // Stop is the end of the run in every phase, including a sleep that was half-way through. An
        // asleep script that is stopped still gets its On Stop handlers, reporting against the variables
        // the run left behind — the last thing it actually knew, which is the honest answer.
        phase = Phase.IDLE
        phaseDeadlineMs = 0L
        pendingWork = emptyList()
        runPhase.clear()

        // AFTER the teardown, or they would be killed by the very loop above. They still see everything the
        // run wrote, because graph variables outlive the fibers that set them.
        if (runHandlers) fireStopHandlers()
        if (had && !isRunning) log.endRun()
    }

    /** Release a fiber stopped at a breakpoint. */
    fun resumePaused() {
        fibers.filter { it.state == FiberState.PAUSED }.forEach { interpreter.resumeFromPause(it) }
    }

    /** Fiber ids whose outcome has already been logged — see [tick]. */
    private val reported = HashSet<Int>()

    /** The last failure, surfaced in the panel so an error is visible without reading the log. */
    var lastError: String? = null
        private set

    /**
     * One scheduler pass. Call from the client thread.
     *
     * Outcomes are reported **once per fiber**. The first version logged whenever every fiber was
     * finished, which is true on every subsequent tick too — so a single failed script printed the same
     * warning ~50 times a second for as long as the client stayed up. The scheduler was not re-running it;
     * only the logging repeated. Anything that reports inside a per-tick call needs an edge, not a level.
     */
    fun tick() {
        // DRAINING is the one phase that has to advance with nothing running — its whole job is to wait for
        // the actuator after the fibers were killed. Every other phase either has a live fiber or has
        // finished, and returning for them keeps this the cheap no-op it has always been on an idle client.
        //
        // Getting this wrong in the other direction is worse than a stall: falling through while ASLEEP
        // reaches the ordinary ending below and fires the On Stop handlers of a script that is coming back.
        if (fibers.isEmpty() && phase != Phase.DRAINING) return
        // ---- the tick loops, [TickMode.LOOP]: a sleep ends a loop BETWEEN passes ----------------------
        //
        // Before the scheduler pass, not after it: a loop resting at its boundary when the sleep was asked
        // for must not start one more pass first. A loop mid-pass — parked on a wait — finishes that pass,
        // its sequence included, and is ended here on a later tick; there is never a next one. A loop
        // cannot leave itself, so this is what a polled `sleepRequested()` is on a host whose tick is the
        // program. [workDone] then advances the phase exactly as it would for a loop that broke and left.
        if (phase == Phase.QUIESCING && (loopsPending || loopFiberIds.isNotEmpty())) {
            loopsPending = false
            for (f in fibers) if (f.id in loopFiberIds && f.isAtPassBoundary) scheduler.kill(f)
        }
        scheduler.tick()
        for (f in fibers) {
            if (!f.isFinished || !reported.add(f.id)) continue
            val err = f.error
            if (err == null) {
                // Anchored on the ENTRY node, matching "began", so the pair brackets the run rather than
                // implying the last node reached was somehow the point of it.
                if (tracing) log.add(LogLevel.INFO, "execution finished", entryNodeOf[f.id] ?: -1, graphId = graphId)
                continue
            }
            lastError = err.message
            // The RAW message plus the node, not the decorated one: the console already shows which node a
            // record came from, so repeating "[chunk@41, node 7]" in the text is noise the reader has to
            // look past. The full decorated form still goes to the client log.
            log.add(LogLevel.ERROR, err.rawMessage, err.nodeId, graphId = graphId)
            EditorLog.w(TAG, "fiber '${f.name}': ${err.message}")
        }
        // ---- the tick loops, [TickMode.LOOP]: they follow the start fibers -----------------------------
        if (loopsPending && phase == Phase.RUNNING && fibers.none { it.id !in loopFiberIds && !it.isFinished }) {
            if (fibers.any { it.error != null }) {
                // A failed preparation must not be followed by the loop — the rule a failed wake follows.
                log.add(LogLevel.ERROR, "the start handler failed, so the tick loop will not start", graphId = graphId)
                loopsPending = false
            } else {
                spawnTickLoops()
            }
        }
        // ---- the phases, before the ordinary ending ------------------------------------------------------
        //
        // A deadline is only spent while something could actually be making progress. A fiber held at a
        // breakpoint is the operator's doing, and escalating a handoff they deliberately stopped would
        // take the account away mid-inspection.
        if (phaseDeadlineMs > 0L && !anyPaused && vmClock.nowMs() > phaseDeadlineMs) {
            when (phase) {
                Phase.WAKING -> {
                    escalate("getting ready")
                    // Preparation that never finished means the world was never arranged, so the work must
                    // NOT start against it. Down, not running.
                    pendingWork = emptyList()
                }
                Phase.QUIESCING -> escalate("handing over")
                Phase.SLEEPING -> escalate("the sleep handlers")
                Phase.DRAINING -> {
                    // The drain would not go idle. Nothing more can be done for the state file.
                    EditorLog.w(TAG, "'$graphId': the actuator never went idle; sleeping anyway")
                    beginSleepHandlers()
                }
                else -> Unit
            }
        }
        if (workDone) {
            when (phase) {
                // A handler finished: the next one, or on to the following phase.
                Phase.WAKING -> {
                    if (fibers.any { it.error != null }) {
                        // A failed preparation must not be followed by the work — see [wake].
                        log.add(LogLevel.ERROR, "getting ready failed, so the script will not start", graphId = graphId)
                        pendingWork = emptyList()
                        finishSleep(cleanly = false)
                    } else {
                        spawnNextWakeHandler()
                    }
                    return
                }
                Phase.QUIESCING -> { beginSleepHandlers(); return }
                Phase.DRAINING -> {
                    if (actuator?.isIdle != false) beginSleepHandlers()
                    return
                }
                Phase.SLEEPING -> {
                    if (sleepHandlerAt < sleepChunks.size) spawnNextSleepHandler() else finishSleep(sleptCleanly)
                    return
                }
                else -> Unit
            }
        }
        // Ran out of work rather than being stopped: same ending, same handlers. Re-checked after firing,
        // since a handler that just started IS the script still running.
        if (!isRunning) {
            // Ran out of work rather than being stopped, so drop the paint here too. stop() already does
            // this, which meant "a finished script leaves nothing behind" held only for the button and not
            // for a script that simply reached its end — and a FOREVER marker, which no lease can reclaim,
            // stayed on screen until the next run.
            if (!drawsDropped) {
                drawsDropped = true
                // **The per-frame entries stop HERE, with the work that ended.** Before the On Stop
                // handlers, not after, and that ordering is the whole fix: firing a handler makes
                // [isRunning] true again, so a render entry left armed fires in that window and repaints
                // everything the clear below just dropped — and [drawsDropped] is an edge, so nothing ever
                // clears it a second time. The panel of a finished script then sat there until the next
                // run. A script that has ended does not get to keep drawing while it says goodbye.
                renderChunks = emptyList()
                tickChunks = emptyList()
                lifecycle.endRun()
            }
            fireStopHandlers()
            if (!isRunning) log.endRun()
        }
    }

    /**
     * Start the On Stop handlers, once.
     *
     * They are ordinary fibers spawned after the others are done, so they see the graph variables the run
     * wrote — start time, starting xp — and can report on them. Once per run: finishing and then being
     * stopped is one ending, not two.
     */
    private fun fireStopHandlers() {
        if (stopFired || stopChunks.isEmpty()) return
        stopFired = true
        val started = stopChunks.map { entry ->
            scheduler.spawn("stop#${entry.site}", entry.chunk)
                .also { entryNodeOf[it.id] = entry.site }
        }
        // Added to the fiber list so the panel shows them, the debugger can step them, and `tick` reports
        // their outcome the same as any other.
        fibers = fibers + started
    }

    // ---- wake / sleep ------------------------------------------------------------------------------------

    /**
     * Spawn the next On Wake handler, or start the work when they are done.
     *
     * **One at a time**, unlike the On Stop handlers. Those mostly report; these ACT — walk somewhere, draw
     * from a bank — and there is one drain and one avatar, so three at once is three interleaved trips.
     */
    private fun spawnNextWakeHandler() {
        if (wakeHandlerAt >= wakeChunks.size) {
            beginWork()
            return
        }
        val entry = wakeChunks[wakeHandlerAt++]
        fibers = fibers + scheduler.spawn("wake#${entry.site}", entry.chunk)
            .also { entryNodeOf[it.id] = entry.site }
    }

    /**
     * Preparation is over: spawn the work.
     *
     * The spawn is the one [run] always did, moved behind the wake — imported fibers first, with the
     * ROOT's waiting on them (`Fiber.waitsFor`). Spawn order alone never enforced that: the scheduler
     * rotates its starting point each tick, so on the very first tick it began at the second fiber and the
     * importer ran before the library it depends on. A registry filled by a library's `always on start`
     * was then empty for the importer, which is exactly the shape of "it works if I stop and start it".
     */
    private fun beginWork() {
        phase = Phase.RUNNING
        phaseDeadlineMs = 0L
        val imported = ArrayList<Fiber>()
        val started = pendingWork.map { entry ->
            scheduler.spawn("${entry.documentName}#${entry.site}", entry.chunk)
                .also {
                    entryNodeOf[it.id] = entry.site
                    if (!entry.isRoot) imported += it else it.waitsFor += imported
                }
        }
        fibers = fibers + started
        pendingWork = emptyList()
        if (tracing) started.forEach { log.add(LogLevel.INFO, "execution began", entryNodeOf[it.id] ?: -1, 0, graphId) }
        // The loops follow the start fibers — now, when there are none to wait for, else from [tick].
        if (tickLoops.isNotEmpty()) {
            if (started.isEmpty()) spawnTickLoops() else loopsPending = true
        }
    }

    /**
     * Start the tick loops — [TickMode.LOOP] — once the start fibers are done.
     *
     * After them, not beside them: `on start` is the one-time preparation in this mode, and a loop reading
     * what it set up must not run its first pass before it has finished. `Fiber.waitsFor` cannot say that
     * (a start fiber parked on a wait counts as settled), so [tick] watches for the moment instead — the
     * same reason [pendingWork] is held behind the wake rather than wired to it.
     */
    private fun spawnTickLoops() {
        loopsPending = false
        val started = tickLoops.map { entry ->
            scheduler.spawn("${entry.documentName}#${entry.site}", entry.chunk)
                .also {
                    entryNodeOf[it.id] = entry.site
                    loopFiberIds += it.id
                }
        }
        fibers = fibers + started
        if (tracing) started.forEach { log.add(LogLevel.INFO, "execution began", entryNodeOf[it.id] ?: -1, 0, graphId) }
    }

    /**
     * Ask the script to finish what it is doing and hand over.
     *
     * **Nothing is cancelled, killed or dropped here.** The flag goes up and that is all: the script's own
     * loop reads `sleepRequested()` and leaves at a point it chose, and cancelling the actuator now would
     * abort the very bank trip the request exists to allow.
     *
     * Idempotent, and a second ask does not restart the clock — a caller that spams it must not be able to
     * hold a script in QUIESCING for ever.
     *
     * @return false when there is nothing running to ask.
     */
    fun requestSleep(reason: String = "", withinMs: Long = QUIESCE_MS): Boolean {
        when (phase) {
            Phase.IDLE, Phase.ASLEEP -> return false
            Phase.QUIESCING, Phase.DRAINING, Phase.SLEEPING -> return true
            Phase.WAKING, Phase.RUNNING -> Unit
        }
        phase = Phase.QUIESCING
        sleepReason = reason
        phaseDeadlineMs = vmClock.nowMs() + withinMs
        runPhase.requestSleep()
        log.add(LogLevel.INFO, "sleep requested${if (reason.isBlank()) "" else ": $reason"}", graphId = graphId)
        EditorLog.i(TAG, "sleep requested for '$graphId'${if (reason.isBlank()) "" else " ($reason)"}")
        return true
    }

    /**
     * The work is over; run the handlers that hand the account on.
     *
     * The mirror of [fireStopHandlers] and spawned the same way, with the one difference that matters: the
     * actuator has NOT been cancelled, so these can still act. It is cancelled in [finishSleep], after
     * they are done.
     */
    private fun beginSleepHandlers() {
        phase = Phase.SLEEPING
        phaseDeadlineMs = vmClock.nowMs() + SLEEP_HANDLER_MS
        if (sleepFired) return
        sleepFired = true
        spawnNextSleepHandler()
    }

    /** One at a time, for [spawnNextWakeHandler]'s reason. */
    private fun spawnNextSleepHandler() {
        if (sleepHandlerAt >= sleepChunks.size) return
        val entry = sleepChunks[sleepHandlerAt++]
        fibers = fibers + scheduler.spawn("sleep#${entry.site}", entry.chunk)
            .also { entryNodeOf[it.id] = entry.site }
    }

    /**
     * Put the run down, keeping nothing.
     *
     * [stop]'s teardown minus the On Stop handlers, and in the same order — the actuator is told first,
     * because a blocking verb runs on another thread and killing the fiber waiting on it does nothing to
     * the verb.
     *
     * **`ScriptUi` is deliberately NOT cleared.** A tick box belongs to the person reading the panel, not
     * to the run, and the script declares the same controls again the moment it wakes; wiping them would
     * make every handoff reset the operator's own settings. `ScriptDraw` IS cleared, because nothing is
     * drawing any more and a FOREVER marker cannot expire on its own.
     */
    private fun finishSleep(cleanly: Boolean) {
        renderChunks = emptyList()
        tickChunks = emptyList()
        drawsDropped = true
        lifecycle.clearDrawing()
        actuator?.cancel()
        fibers.forEach { scheduler.kill(it) }
        scheduler.reap()
        fibers = emptyList()
        recentNodes.clear()
        pendingWork = emptyList()
        loopsPending = false
        loopFiberIds.clear()
        phase = Phase.ASLEEP
        phaseDeadlineMs = 0L
        sleptAtMs = vmClock.nowMs()
        sleptCleanly = cleanly
        log.add(
            if (cleanly) LogLevel.INFO else LogLevel.ERROR,
            if (cleanly) "asleep" else "asleep — FORCED, so its state may not have been saved",
            graphId = graphId,
        )
        EditorLog.i(TAG, "'$graphId' asleep (clean=$cleanly)")
    }

    /**
     * A phase ran out of time. Take the account back.
     *
     * Kills whatever would not finish, then waits for the drain to go IDLE before the sleep handlers are
     * spawned. That wait is not politeness: `ScriptActuator.cancel()` sets `stopping` and then queues the
     * camera release, and the drain only clears `stopping` once its queue empties — so a handler offered a
     * verb in that window is aborted before it runs an instruction. The one handler this happens to is the
     * one that was about to write the state file.
     */
    private fun escalate(what: String) {
        log.add(LogLevel.ERROR, "$what did not finish in time — taking the account back", graphId = graphId)
        EditorLog.w(TAG, "'$graphId': $what overran, forcing")
        actuator?.cancel()
        fibers.forEach { scheduler.kill(it) }
        scheduler.reap()
        fibers = emptyList()
        sleptCleanly = false
        phase = Phase.DRAINING
        phaseDeadlineMs = vmClock.nowMs() + DRAIN_MS
    }

    /**
     * Start an asleep script again: preparation, then the work.
     *
     * An ordinary [run] with `resume` set, so it recompiles from the document on disk and re-seeds every
     * variable from its declared default. **What the last run knew is gone** — On Sleep wrote down
     * whatever the next On Wake is going to need, or it did not, and either way this is the same path a
     * client restart takes. That is the point: the rare case and the common one are one piece of code.
     */
    fun wake(doc: EditorDoc, debug: Boolean = true): String? {
        if (phase != Phase.ASLEEP) return "'${doc.name}' is not asleep"
        return run(doc, debug, resume = true)
    }

    /**
     * Node ids executed within [windowNanos] — the canvas's activity highlight.
     *
     * Time-windowed rather than "the current node" because at 50 passes a second the current node changes
     * far faster than the eye can follow; a short tail is what makes execution legible.
     */
    fun activeNodes(windowNanos: Long = 400_000_000L): Set<Int> {
        val now = System.nanoTime()
        recentNodes.entries.removeIf { now - it.value > windowNanos }
        return recentNodes.keys.toSet()
    }

    /** Link ids control crossed within [windowNanos] — each gets a flow pulse on the canvas. */
    fun activeLinks(windowNanos: Long = 400_000_000L): Set<Int> {
        val now = System.nanoTime()
        recentLinks.entries.removeIf { now - it.value > windowNanos }
        return recentLinks.keys.toSet()
    }

    companion object {
        const val RENDER_BUDGET_NANOS: Long = 2_000_000L

        /**
         * What one On Tick pass gets. See [gameTick].
         *
         * Larger than a frame's because a tick comes ~30× less often, and still under the 8ms at which
         * `dev.ziggle.plugin.PluginTicks` calls a client-thread hook slow — this rides the same thread the
         * game does, so that threshold is the real ceiling and not a suggestion.
         */
        const val TICK_BUDGET_NANOS: Long = 5_000_000L

        /**
         * How long getting ready may take before the account is taken back.
         *
         * The most generous of the three, because preparation is the one that legitimately walks across
         * the map — and unlike the others, overrunning it costs nothing that was not already lost: the
         * script has not started, so there is no state to save.
         */
        const val WAKE_MS: Long = 300_000L

        /**
         * How long a script gets to notice a sleep request and leave its loop.
         *
         * Long enough for a GOTR round's tail plus a bank trip; short enough that a script which simply
         * does not poll cannot hold a live account indefinitely. There has to be a number: the
         * alternative is a hung handoff nobody is watching, which is the worst failure this has.
         */
        const val QUIESCE_MS: Long = 120_000L

        /** How long the On Sleep handlers get, once the loops have stopped. */
        const val SLEEP_HANDLER_MS: Long = 180_000L

        /**
         * How long to wait for the drain to go idle after a forced cancel.
         *
         * Short, because it is measuring one verb unwinding rather than any real work — and see
         * [escalate] for why waiting at all is what lets the forced sleep handler write its file.
         */
        const val DRAIN_MS: Long = 5_000L

        const val TAG = "VScript"
    }
}
