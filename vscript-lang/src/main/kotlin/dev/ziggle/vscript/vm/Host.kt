package dev.ziggle.vscript.vm

/**
 * A host function — the bridge from a bytecode call to a real node implementation.
 *
 * Arguments arrive in declaration order. A function with one output returns it directly; one with several
 * returns an `Array<Any?>` which the VM spreads across the caller's result window. Returning null for a
 * zero-result call is fine.
 */
fun interface HostFn {
    fun invoke(args: Array<Any?>): Any?
}

/**
 * Whether a host function may run on the client thread.
 *
 * This classification is the whole threading model of the VM, and getting it wrong is the primary way to
 * stutter the game: a blocking function invoked via [Op.CALL] runs *on the client thread* and will trip the
 * 16ms hard budget that force-stops the host plugin. The node catalog must classify every function, and the
 * compiler picks the opcode from this — authors never choose.
 */
enum class HostKind {
    /** A live read or cheap computation. Runs inline on the client thread via [Op.CALL]. */
    INLINE,

    /** Drives input, walks, banks — anything that blocks. Marshalled to the actuator drain via [Op.ACT]. */
    BLOCKING,

    /**
     * A REINTERPRETATION — the result is argument zero, unchanged, and no call happens.
     *
     * ### What it is for
     *
     * A domain often has nominal types over one representation: an item id, an npc id and an object id
     * are all ints, and the whole reason to give them separate types is that mixing them is a bug that
     * reads perfectly. Converting between them has to be *written* — `n.toItem()` — and should cost
     * nothing to *run*, because nothing happens: the value is already the int it was.
     *
     * These were language intrinsics for exactly that reason, and the comment on them said so: "It costs
     * nothing at run time. The conversion exists to be WRITTEN, not to be executed." Making a domain
     * declare them as ordinary host calls would have put a real call into 199 corpus sites to achieve
     * nothing. This keeps the property and hands the vocabulary to whoever owns it.
     *
     * ### What it does not do
     *
     * It is not a cast in the checking sense — the CHECKER has already decided the call is legal by the
     * ordinary rules, from the declared receiver and result types. This only says what to emit, which is
     * a move.
     *
     * A `CAST` host takes exactly one argument and returns exactly one result; anything else is a
     * declaration error, since there is nothing sensible to move.
     */
    CAST,

    /**
     * A CONSTRUCTION — the result is a record built from the arguments, and no call happens.
     *
     * ### What it is for
     *
     * [CAST]'s sibling, and it exists for the same reason. A domain with a record type wants a positional
     * spelling for building one: `tile(3200, 3200, 0)` reads better than
     * `Tile { x: 3200, y: 3200, plane: 0 }` and is how a position has been written since before either was
     * a node. The record spelling still works — this is a second way to write the same value, not a
     * different value.
     *
     * That was a language INTRINSIC, because nothing else could emit a `NEWSTRUCT`, which meant the
     * language shipped a constructor for one game's coordinate. This hands the spelling to whoever owns
     * the type while keeping every property the intrinsic had:
     *
     *  - a `NEWSTRUCT`, so the 167 corpus sites cost what they always cost rather than becoming host
     *    calls,
     *  - constant folding, so a tile in a `val` is one value and not a rebuild per read,
     *  - and a missing argument taking the FIELD's default, which is what makes `tile(3200, 3200)` mean
     *    ground level rather than being an arity error.
     *
     * ### What it requires
     *
     * Exactly one result, whose type is a [dev.ziggle.vscript.model.HostRecord] with
     * [dev.ziggle.vscript.model.HostRecord.isData] set — an accessor record has no shape the language could
     * build. Arguments are matched to FIELDS positionally, so the parameters should be declared in field
     * order; anything else is a declaration error, since there is nothing sensible to construct.
     */
    CONSTRUCT,
}

/** One callable host function: its identity, its threading classification, and its implementation. */
class HostFunction(
    val name: String,
    val kind: HostKind,
    val arity: Int,
    val results: Int,
    val fn: HostFn,
)

/**
 * The set of host functions a chunk may call, resolved by the index a [Chunk] stores in its call
 * instructions.
 *
 * A chunk carries host function *names*; the registry binds those names to implementations at load time, so
 * the same bytecode can be run against the real node catalog in the client and against fakes in a test.
 */
class HostRegistry {
    private val byName = LinkedHashMap<String, HostFunction>()

    fun register(fn: HostFunction): HostRegistry {
        require(byName.put(fn.name, fn) == null) { "duplicate host function '${fn.name}'" }
        return this
    }

    /** Convenience registration for tests and simple catalog entries. */
    fun register(
        name: String,
        kind: HostKind = HostKind.INLINE,
        arity: Int = 0,
        results: Int = 1,
        fn: HostFn,
    ): HostRegistry = register(HostFunction(name, kind, arity, results, fn))

    operator fun get(name: String): HostFunction? = byName[name]

    val names: Set<String> get() = byName.keys

    /**
     * Resolve a chunk's host-name table into a lookup array indexed by the operand the bytecode carries.
     *
     * Done once at load rather than per call, and it is where a missing or mis-arity'd function is caught —
     * a graph referencing a node the catalog no longer has should fail loudly at load, not mid-run.
     */
    fun bind(chunk: Chunk): Array<HostFunction> = Array(chunk.hostNames.size) { i ->
        val name = chunk.hostNames[i]
        byName[name] ?: throw VmError("unknown host function '$name' referenced by chunk '${chunk.name}'")
    }
}

/**
 * What a BLOCKING host hands back when its result will land LATER — and the host, not an actuator, is the
 * one that will land it.
 *
 * The inline path of [Op.ACT] (no [ActuatorSink]) runs the host on the VM thread and expects a value. A host
 * that cannot answer yet — "mine this block", which takes thirty ticks of the world moving — returns one of
 * these instead, and the fiber parks in `AWAITING_ACT` exactly as it would behind an actuator: the same
 * [PendingAct], the same `tryCompleteAct`, the same `try`/`catch` reach. Whoever holds the [box] completes it
 * with a [Result] when the work is done, on whatever thread owns the world, and the next scheduler pass lands
 * it. Nothing here is a thread: it is a promise the VM already knew how to wait on.
 *
 * Only an `action` may return one. A `query` or `command` compiles to [Op.CALL], which lands whatever it is
 * given as a value — an await there is a bug in the host, not a wait.
 */
class HostAwait(
    val box: java.util.concurrent.atomic.AtomicReference<Result<Any?>?> = java.util.concurrent.atomic.AtomicReference(null),
) {
    /** The work finished with [value]. Idempotent: the first completion wins. */
    fun complete(value: Any?) { box.compareAndSet(null, Result.success(value)) }

    /** The work failed; [message] is what the script's `catch` reads. */
    fun fail(message: String) { box.compareAndSet(null, Result.failure(VmError(message))) }

    val isDone: Boolean get() = box.get() != null
}

/**
 * Where [Op.ACT] work is handed off for execution.
 *
 * In the client this is `ClientThreadPlugin.offer`, whose drain thread runs one intent at a time through
 * the normal humanized interaction layer. In tests it is a fake that runs the work immediately or on
 * command, which is what makes the suspend path deterministically testable.
 *
 * Implementations must tolerate the *same* work being offered repeatedly — see [PendingAct].
 */
interface ActuatorSink {
    fun offer(label: String, work: () -> Unit)

    /**
     * Abandon queued work and unwind anything in flight.
     *
     * Part of the sink rather than of whoever built it, because stopping a script has to reach the work
     * already handed over — a verb is a plain call on another thread and cannot be killed, only asked to
     * notice. A sink that runs work inline has nothing to unwind, hence the no-op default.
     */
    fun cancel() {}

    /**
     * Is there nothing queued and nothing in flight?
     *
     * Asked after a [cancel], by a host that wants to hand the same avatar to something else and needs to
     * know the last verb has actually unwound. **A cancel is a request, not a stop**: it asks the running
     * verb to notice at its next check, and an implementation that also uses a "cancelling" flag to abort
     * newly offered work will refuse the next verb until its queue has drained — so offering one in that
     * window silently loses it.
     *
     * Defaults to true, which is the right answer for a sink that runs work inline: there is no queue and
     * nothing in flight by the time anyone can ask.
     */
    val isIdle: Boolean get() = true
}
