package dev.ziggle.vscript.vm

/**
 * A compiled unit of bytecode — one graph entry point or one graph function.
 *
 * [code] is the flat instruction array (four ints per instruction, see [Op]). [constants] holds every
 * non-immediate literal. [maxRegs] is the register window this chunk needs, which the fiber uses to size
 * its stack before pushing a frame.
 *
 * [nodeIds] maps instruction index → authoring node id (or -1), which is how a runtime error, a breakpoint
 * and a stack frame all point back at something the user can see on the canvas. It is the *only* debug
 * metadata the VM itself needs; everything richer lives in the editor and is keyed by the same node id.
 */
/**
 * Which register holds which authored value — the inspector's whole debug format.
 *
 * Only values with a **stable** slot appear here. An impure node's data outputs get a register for the life
 * of the chunk (that is how a later node reads them), and so do graph variables; a PURE node is re-expanded
 * at every use site into scratch registers that are immediately released, so it has no slot to name. That
 * is a real property of the semantics rather than a gap — a pure node is an expression, not a variable —
 * and the inspector says so instead of showing whatever last occupied the register.
 */
class SlotMap(
    /** `nodeId to pin` -> register, for impure and entry node data outputs. */
    val outputs: Map<Pair<Int, String>, Int> = emptyMap(),
    /**
     * Document variable name -> its slot in the run's GLOBALS.
     *
     * Not a register, whatever the old wording said: the only reader indexes the run's globals with it
     * (`DebugSession.scopes`), and a register number there shows an unrelated variable's value.
     */
    val variables: Map<String, Int> = emptyMap(),
    /**
     * The first pc at which an entry of [outputs] means anything — absent entries are always meaningful.
     *
     * **A register exists before the thing it holds does.** A text local is allocated when its statement
     * runs, so a frame paused earlier shows whatever the last expression left in that register: pausing on
     * `val n = 2` reported `n` as the string a `log` two lines up had passed. Empty for the graph front
     * end, whose node outputs hold their register for the life of the chunk and are meaningful throughout.
     */
    val liveFrom: Map<Pair<Int, String>, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = outputs.isEmpty() && variables.isEmpty()

    /** Whether [key]'s register holds its value yet, at [pc]. */
    fun isLive(key: Pair<Int, String>, pc: Int): Boolean = pc > (liveFrom[key] ?: Int.MIN_VALUE)
}

class Chunk(
    val name: String,
    val code: IntArray,
    /**
     * The constant pool — `var`, and deliberately so. See [setLiteral].
     *
     * Volatile because it is read by the fiber thread and replaced by whichever thread is driving the
     * editor. The write publishes a whole new array, so a reader either sees every old value or every new
     * one; a reader that cached the reference would go on reading the old pool forever.
     */
    @Volatile var constants: Array<Any?>,
    val maxRegs: Int,
    /** Parameter count — the first [paramCount] registers are the arguments on entry. */
    val paramCount: Int = 0,
    /** Per-instruction authoring node id, or -1. Empty when compiled without debug info. */
    val nodeIds: IntArray = IntArray(0),
    /** Host function names by index, for diagnostics and disassembly. */
    val hostNames: Array<String> = emptyArray(),
    /** Register assignments the debugger reads to show values. Empty without debug info. */
    val slots: SlotMap = SlotMap(),
    /**
     * The graph variables' starting values, in slot order.
     *
     * Carried on the chunk so anything that spawns one can seed the run without having to be handed the
     * document — a test driving a chunk directly would otherwise read every variable as null, which is a
     * confusing way to discover that globals exist.
     */
    val globals: List<Any?> = emptyList(),
    /**
     * Constant-pool index of each authored literal, keyed `"<nodeId>/<pin>"`.
     *
     * Only pins get an entry, and each gets its OWN slot even when another pin holds an equal value —
     * the pool otherwise interns by value, so two nodes that both say `1500` would share one slot and
     * editing either would move both.
     */
    val literalSlots: Map<String, Int> = emptyMap(),
    /**
     * The exception table — one entry per `try`, covering the instructions its body compiled to.
     *
     * **A TABLE, not a push/pop pair**, which is the same choice the JVM makes and for the same reason:
     * arming a handler with an instruction means every way OUT of the body has to remember to disarm it,
     * and the ways out are not enumerable — `return`, `break`, `continue` and a nested jump all leave
     * without passing the bottom of the block. A range is checked against the program counter at the
     * moment something is raised, so there is nothing to keep in step and nothing to leak.
     */
    val handlers: List<HandlerRange> = emptyList(),
    /**
     * Set when this chunk is a document's INITIALISER PROLOGUE, to that document's key.
     *
     * **A prologue must run once per RUN, not once per entry that calls it.** Every start entry calls the
     * imported prologues at its head — which is what makes each entry self-sufficient and keeps the
     * ordering guarantee an instruction rather than a convention. That was exactly right while a run had
     * one start entry; `always on start` gave it several, and the second one's prologue re-seeded a
     * variable the first had already filled in. A registry written by an imported document was empty by
     * the time its importer read it, having been logged as written.
     *
     * So the guard is at run time, in [dev.ziggle.vscript.vm.Op.CALLG]: the first call runs it, the rest are
     * skipped. Nothing about the emitted code changes, and neither does the ordering.
     */
    val prologueOf: String? = null,
) {
    /**
     * Every chunk compiled together, shared — what [Op.CALLG]'s `a` operand indexes.
     *
     * **A function is compiled ONCE for a whole program and named by a global index**, rather than being
     * nested inside each chunk that calls it. That is what makes mutual recursion expressible (an index is
     * reserved before the body exists, so neither chunk has to contain the other) and what stops a helper
     * called from five places being compiled five times.
     *
     * `var`, and published exactly once by [ProgramBuilder.link] — the array cannot be built until every
     * chunk in it has been, so a chunk is constructed first and told about its program immediately after.
     * `@Volatile` for the same reason [constants] is: it is written by whichever thread compiled and read
     * by the fiber thread.
     */
    @Volatile
    var program: Array<Chunk> = emptyArray()

    /**
     * Rewrite one authored literal in a chunk that may already be running. True if this chunk (or a nested
     * one) owned that pin.
     *
     * This is what makes a value editable WITHOUT restarting the script: `CONST` reads the pool at the
     * moment it executes rather than baking the value into the instruction, so the next evaluation picks
     * up whatever is in the slot. Copy-on-write because the reads are constant and hot while the writes
     * are a person typing.
     *
     * Scope worth being exact about: this changes a VALUE, never the shape of anything. Wiring a link,
     * adding a node or changing a pin's type all rearrange the code itself and still need a recompile.
     */
    fun setLiteral(nodeId: Int, pin: String, value: Any?): Boolean {
        // This chunk, then every function in the program — a function's pins live in its own pool, and the
        // pin being edited may belong to a helper rather than to this chain.
        //
        // **A flat loop, with no visited set.** Sub-chunks used to be nested, and a recursive function's
        // chunk therefore contained itself, so the walk needed an identity-keyed guard or it recursed until
        // the stack went. One chunk per function removes the cycle rather than guarding it.
        var hit = write(nodeId, pin, value)
        for (c in program) if (c !== this && c.write(nodeId, pin, value)) hit = true
        return hit
    }

    /** Retune this chunk's own pool. False when it does not own that pin. */
    private fun write(nodeId: Int, pin: String, value: Any?): Boolean {
        val idx = literalSlots["$nodeId/$pin"] ?: return false
        if (idx !in constants.indices) return false
        val next = constants.copyOf()
        next[idx] = value
        constants = next
        return true
    }

    /** Number of instructions (not ints). */
    val size: Int get() = code.size / 4

    fun op(pc: Int): Int = code[pc * 4]
    fun a(pc: Int): Int = code[pc * 4 + 1]
    fun b(pc: Int): Int = code[pc * 4 + 2]
    fun c(pc: Int): Int = code[pc * 4 + 3]

    /** The authoring node id at [pc], or -1 when this chunk carries no debug info. */
    fun nodeIdAt(pc: Int): Int = if (pc < nodeIds.size) nodeIds[pc] else -1

    override fun toString(): String = "Chunk($name, ${size} insns, $maxRegs regs)"
}

/**
 * The program's flat function table — every chunk compiled together, and the linker that publishes it.
 *
 * **Reserve, then compile, then fill.** An index is claimed for a function *before* its body is compiled,
 * so a call reached while that body is still being emitted already has somewhere to point. Direct recursion
 * is the case where that call is the function itself; mutual recursion is the case where it is a second
 * function that calls back. Both work for the same reason, and neither needs a chunk to exist before it has
 * been built.
 *
 * Keyed rather than positional, because "have we compiled this already" is the whole point: a helper called
 * from five sites is one entry, compiled once, with **one** constant pool — so a literal inside it is one
 * editable slot rather than five that drift apart.
 */
class ProgramBuilder {

    private val chunks = ArrayList<Chunk?>()
    private val byKey = HashMap<Any, Int>()

    /**
     * Entry chunks that call into this program without being part of it.
     *
     * Remembered rather than passed once, because a run is compiled a GROUP AT A TIME — `on start` and
     * `on tick` are separate calls sharing one table — and each call links. Without this the second link
     * would publish a longer array to the table and leave the first group's entries pointing at the
     * shorter one, so a helper reached from a later group would resolve to "no function n".
     */
    private val roots = ArrayList<Chunk>()

    /**
     * [key]'s index, compiling it with [body] if this is the first mention.
     *
     * The index is registered **before** [body] runs, so a call back into the same key from inside that
     * body resolves to the reserved slot instead of compiling a second copy or recursing forever.
     */
    fun indexOf(key: Any, body: () -> Chunk): Int {
        byKey[key]?.let { return it }
        val idx = chunks.size
        chunks.add(null)
        byKey[key] = idx
        chunks[idx] = body()
        return idx
    }

    /** Add an unkeyed chunk — for hand-assembled fixtures, which have no document to key on. */
    fun add(chunk: Chunk): Int {
        chunks.add(chunk)
        return chunks.size - 1
    }

    /**
     * Resolve the table and publish it to every chunk in it, plus [roots] — the entry chunks, which call
     * into the program without being part of it.
     *
     * Refuses a hole for the reason [ChunkBuilder.build] always has: a null reaching the VM surfaces as
     * "no function 3" at the moment of the call and a long way from whatever failed to fill it.
     */
    fun link(vararg entries: Chunk): Array<Chunk> {
        roots += entries
        val all = Array(chunks.size) { i ->
            chunks[i] ?: error("function $i was reserved and never filled")
        }
        for (c in all) c.program = all
        // Every root ever registered, not just this call's — see [roots].
        for (r in roots) r.program = all
        return all
    }
}

/**
 * Hand-assembles a [Chunk].
 *
 * This is the compiler's emit target, but it exists first so the VM can be built and tested against
 * hand-written fixtures before any graph or compiler exists — the phase-1 milestone. Tests read as small
 * assembly programs, which keeps VM bugs distinguishable from compiler bugs later.
 *
 * Jump targets are handled with [label]/[here]: emit a placeholder, then patch it once the target is known.
 *
 * ```
 * val chunk = chunk("countdown") {
 *     val i = reg(); val zero = reg()
 *     konst(i, 3); konst(zero, 0)
 *     val top = here()
 *     val done = jmpf(le(reg(), i, zero))   // placeholder target
 *     sub(i, i, konstReg(1))
 *     yieldTick()
 *     jmp(top)
 *     patch(done)
 *     halt()
 * }
 * ```
 */
/**
 * One `try` — the instructions it guards, where to resume, and where to put the message.
 *
 * [start] is inclusive and [end] exclusive, and the range covers the BODY only: an error raised inside the
 * catch block must not be caught by the same handler, or a failing handler would spin forever.
 */
class HandlerRange(
    val start: Int,
    val end: Int,
    val catchPc: Int,
    val messageReg: Int,
) {
    fun covers(pc: Int): Boolean = pc in start until end
}

class ChunkBuilder(
    private val name: String,
    private val paramCount: Int = 0,
    /**
     * Keep a slot per authored pin so its value can be rewritten while the script runs.
     *
     * False is the release build, where constants are interned by value like any other compiler's pool.
     * Nothing can tell the difference at runtime: the only thing that reads [Chunk.literalSlots] is
     * [Chunk.setLiteral], which is the editor's live-edit path and is not available in a release run.
     */
    private val editableLiterals: Boolean = true,
    /**
     * The program this chunk's `CALLG`s index into.
     *
     * Null means **standalone** — the builder makes its own and links it at [build], which is what keeps a
     * hand-assembled fixture (`chunk("x") { subChunk(inner) }`) working with nothing else to arrange. The
     * compiler passes one in, shared by every chunk of the compilation, and links it itself once every
     * body is done.
     */
    program: ProgramBuilder? = null,
) {
    /** True when this builder owns its program and must therefore link it — see the constructor note. */
    private val standalone = program == null

    val program: ProgramBuilder = program ?: ProgramBuilder()

    /** Starting values for the graph's variables — see [Chunk.globals]. */
    var globals: List<Any?> = emptyList()

    private val code = ArrayList<Int>()
    private val nodeIds = ArrayList<Int>()
    private val constants = ArrayList<Any?>()
    private val literalSlots = LinkedHashMap<String, Int>()
    private val hostNames = ArrayList<String>()
    private var nextReg = paramCount
    private var highWater = paramCount

    /** The node id stamped on subsequent instructions — set it as the compiler walks each graph node. */
    var currentNodeId: Int = -1

    /** Register assignments for the inspector; the compiler fills this in as it allocates. */
    var slots: SlotMap = SlotMap()

    /** Allocate a fresh register. */
    fun reg(): Int {
        val r = nextReg++
        if (nextReg > highWater) highWater = nextReg
        return r
    }

    /** Allocate [n] *contiguous* registers and return the base — for a call's argument window. */
    fun regs(n: Int): Int {
        val base = nextReg
        nextReg += n
        if (nextReg > highWater) highWater = nextReg
        return base
    }

    /** Release every register above [mark], so a call window can be reused. Pairs with [mark]. */
    fun release(mark: Int) { nextReg = mark }

    /** The current register watermark, for [release]. */
    fun mark(): Int = nextReg

    /**
     * A constant slot owned by the authored pin [key] (`"<nodeId>/<pin>"`), so it can be rewritten while
     * the script runs. NOT interned by value — that is the whole point, see [Chunk.literalSlots].
     *
     * Interned by KEY instead, because a pure node is re-expanded at every use site: without this, one pin
     * would end up with a slot per use and editing it would update only the first.
     */
    fun literalConstant(key: String, value: Any?): Int {
        // A release build has no live editing, so an authored literal is just a constant — pooled by value
        // like everything else. This is what makes a literal NODE feeding five pins indistinguishable from
        // five typed-in values once compiled, and so removes the last thing a text round trip could change
        // about a script without changing what it does.
        if (!editableLiterals) return constant(value)
        literalSlots[key]?.let { return it }
        constants.add(value)
        val idx = constants.size - 1
        literalSlots[key] = idx
        return idx
    }

    /** Intern [value] in the constant pool and return its index. */
    fun constant(value: Any?): Int {
        val existing = constants.indexOfFirst { it == value && it?.javaClass == value?.javaClass }
        if (existing >= 0) return existing
        constants.add(value)
        return constants.size - 1
    }

    /** Declare host function [name] and return its index (idempotent). */
    fun host(name: String): Int {
        val existing = hostNames.indexOf(name)
        if (existing >= 0) return existing
        hostNames.add(name)
        return hostNames.size - 1
    }

    /** Add [chunk] to the program and return the index a `CALLG` names it by. */
    fun subChunk(chunk: Chunk): Int = program.add(chunk)

    // ---- emit -------------------------------------------------------------------------------------

    /** Emit a raw instruction; returns its index (for patching). */
    fun emit(op: Int, a: Int = 0, b: Int = 0, c: Int = 0): Int {
        val idx = code.size / 4
        code.add(op); code.add(a); code.add(b); code.add(c)
        nodeIds.add(currentNodeId)
        return idx
    }

    /** The index the next emitted instruction will get — a jump target. */
    fun here(): Int = code.size / 4

    /** Repoint the jump emitted at [insnIndex] to [target] (defaults to [here]). */
    fun patch(insnIndex: Int, target: Int = here()) {
        val base = insnIndex * 4
        // Each jumping opcode keeps its target in a different operand slot:
        //   JMP        target in a  — nothing else to carry
        //   JMPF/JMPT  condition in a, target in b
        //   ITERNEXT   dst in a, iterator in b, exhausted-target in c
        when (val op = code[base]) {
            Op.JMP -> code[base + 1] = target
            Op.JMPF, Op.JMPT -> code[base + 2] = target
            Op.ITERNEXT -> code[base + 3] = target
            else -> error("instruction $insnIndex (${Op.name(op)}) is not patchable")
        }
    }

    /** Exception ranges recorded by the compiler as it finishes each `try`. */
    private val handlers = ArrayList<HandlerRange>()

    /** See [Chunk.prologueOf] — set by the compiler when it builds a document's prologue. */
    var prologueOf: String? = null

    fun handler(start: Int, end: Int, catchPc: Int, messageReg: Int) {
        handlers += HandlerRange(start, end, catchPc, messageReg)
    }

    fun build(): Chunk {
        val chunk = Chunk(
            name = name,
            code = code.toIntArray(),
            constants = constants.toTypedArray(),
            maxRegs = highWater,
            paramCount = paramCount,
            nodeIds = nodeIds.toIntArray(),
            hostNames = hostNames.toTypedArray(),
            slots = slots,
            globals = globals,
            literalSlots = literalSlots.toMap(),
            handlers = handlers.toList(),
            prologueOf = prologueOf,
        )
        // A builder that made its own program has nobody else to link it — see the constructor note.
        if (standalone) program.link(chunk)
        return chunk
    }
}

/** Assemble a [Chunk] — see [ChunkBuilder]. */
fun chunk(name: String, paramCount: Int = 0, block: ChunkBuilder.() -> Unit): Chunk =
    ChunkBuilder(name, paramCount).apply(block).build()
