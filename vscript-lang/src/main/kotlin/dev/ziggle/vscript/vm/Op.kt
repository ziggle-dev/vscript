package dev.ziggle.vscript.vm

/**
 * The visual-script VM's instruction set.
 *
 * **Register machine, not a stack machine.** Every node output pin in a graph compiles to a register, so a
 * data wire is just a register reference and the compiler is close to a direct translation of the graph —
 * no push/pop bookkeeping to derive from topology. It also makes the debugger cheap: "what is the value on
 * this wire?" is "read register n", which is what lets the editor draw live values inline on the wires.
 *
 * **Fixed-width encoding.** One instruction is four ints — `[op, a, b, c]` — laid out flat in
 * [Chunk.code]. A program counter is an *instruction index*, so the operand offset is `pc * 4`. Fixed width
 * costs a little space against a variable-length encoding and buys a decode loop with no length table.
 *
 * Calls follow the Lua convention: arguments sit in a **contiguous register window** starting at `b`, and
 * results are written back over that same window. That is why a call needs only three operands however many
 * arguments it takes.
 *
 * `const val` (not `@JvmField val`) is deliberate: a `when` over compile-time constants lowers to a
 * `tableswitch`, which is the whole point of a dispatch loop. The inlining hazard noted in the plan applies
 * to constants crossing a *published artifact* boundary — these never leave `vscript-core`, so every consumer
 * recompiles with them. Moving this file into the SDK would make the hazard real.
 */
object Op {
    /** End the fiber. The implicit last instruction of a top-level chunk. */
    const val HALT = 0

    /** `a = constants[b]` */
    const val CONST = 1

    /** `a = b` */
    const val MOVE = 2

    /**
     * `GETG dst, i` / `SETG i, src` — read and write graph variable [i].
     *
     * Graph variables are GLOBAL to a run, not registers in whichever chunk happened to declare them. They
     * have to be: a function body runs in its own frame and a stop handler in its own FIBER, so anything
     * frame-local would hand each of them a private copy and the writes would go nowhere anyone could see.
     */
    const val GETG = 3
    const val SETG = 4

    // ---- arithmetic (Int/Long/Double, promoted per [Values.arith]) ----
    /** `a = b + c` — also string concatenation when either side is a String. */
    const val ADD = 10
    const val SUB = 11
    const val MUL = 12
    const val DIV = 13
    const val MOD = 14

    /** `a = -b` */
    const val NEG = 15

    /**
     * `TOF a, b` — `a` = `b` as a Double.
     *
     * **The implicit INT → FLOAT widening, made real.** `canConnect` lets an INT-typed output feed a FLOAT
     * pin, which is the ergonomics everyone wants; without this instruction that is all it was, and the
     * value stayed an `Int` in a slot the author had declared FLOAT. `"" + p.x` then printed `4` where a
     * float had been declared, and the type was a claim nothing enforced.
     *
     * An opcode rather than a call to the `toFloat` host, because a language-level coercion should not
     * depend on a host being registered — and because this is emitted on a boundary that may be crossed in
     * a loop, where a host lookup and its argument window would be real cost for one `toDouble`.
     */
    const val TOF = 16

    // ---- comparison / logic (always yield Boolean) ----
    const val EQ = 20
    const val NE = 21
    const val LT = 22
    const val LE = 23
    const val GT = 24
    const val GE = 25

    /** `a = !b` */
    const val NOT = 26

    /**
     * `AND dst, a, b` / `OR dst, a, b` — both operands are always evaluated.
     *
     * Not short-circuiting, and it cannot be: these lower a PURE node, whose inputs are expressions with no
     * side effects and no order to preserve. Branch is where short-circuiting lives, because that is where
     * the language has somewhere for the skipped work to not happen.
     */
    const val AND = 27
    const val OR = 28

    // ---- control flow (targets are instruction indices) ----
    /** `pc = a` */
    const val JMP = 30

    /** `if (!a) pc = b` */
    const val JMPF = 31

    /** `if (a) pc = b` */
    const val JMPT = 32

    /**
     * Call host function `a` with the register window at `b`; `c` packs arg/result counts (see [packCounts]).
     *
     * **Non-blocking.** The host function runs inline on the client thread, so it must be a live read or a
     * cheap computation. Anything that drives input, walks, or otherwise blocks must be [ACT] instead —
     * emitting it as `CALL` is the primary way to trip the client-thread watchdog.
     */
    const val CALL = 40

    /**
     * Call blocking host function `a` — the register window and count packing match [CALL].
     *
     * The work is offered to the actuator drain (a background thread) and the fiber suspends until the
     * result lands. This is the VM's counterpart to `ScriptScope.act`.
     */
    const val ACT = 41

    /** Call the graph function in `chunks[a]` — window at `b`, counts in `c`. Pushes a frame. */
    const val CALLG = 42

    /**
     * Call the function a REGISTER holds, rather than one the instruction names.
     *
     * `a` is the register holding a [dev.ziggle.vscript.vm.FunctionValue]; `b` and `c` are [CALLG]'s exactly —
     * the argument window and the packed counts. Everything after resolving which chunk to enter is the
     * same code, and deliberately so: a dynamic call is not a different calling convention, it is the same
     * one with the callee arriving late.
     */
    const val CALLV = 44

    /**
     * Build a [dev.ziggle.vscript.vm.FunctionValue] that closes over registers — what a lambda emits.
     *
     * `a` is the destination register, `b` the constant slot holding the TEMPLATE value (the program index
     * and the name, with nothing captured), and `c` packs the base register of the captured window and how
     * many there are, via [packCounts].
     *
     * **It is [CONST] with a copy step.** A reference to a named function stays a constant, because there
     * is nothing to copy; this exists only because a lambda's free names live in the enclosing frame's
     * registers, which the callee's frame cannot reach. The values are copied HERE rather than read later,
     * so reassigning the local afterwards does not change what the closure sees — a closure is a value.
     */
    const val CLOSURE = 45

    /** Return `b` values starting at register `a` to the caller. */
    const val RET = 43

    // ---- suspension ----
    /** Park until the next scheduler tick. */
    const val YIELD = 50

    /** Park for `regs[a]` milliseconds. */
    const val SLEEP = 51

    // ---- lists ----
    /** `a = []` */
    const val NEWLIST = 60

    /** `a.add(b)` */
    const val APPEND = 61

    /** `a = b[c]` */
    const val INDEX = 62

    /** `a = b.size` */
    const val LEN = 63

    /** `a = iterator over list b` (a cursor, not a JDK Iterator — it is a plain VM value). */
    const val ITER = 64

    /** Advance iterator `b`: on an element, `a = element` and fall through; when exhausted, `pc = c`. */
    const val ITERNEXT = 65

    // ---- maps ----
    /**
     * `SETKEY a, b, c` — `regs[a][regs[b]] = regs[c]`, **in place**.
     *
     * The map half of [APPEND], and it exists for the same reason: a map is a VALUE, so `withEntry` copies,
     * and an accumulator that copies is O(n²). `AppendPass` proves nobody else holds the old map and the
     * compiler emits this instead. Value semantics in the language, an in-place write in the compiler.
     *
     * Strict about a null map, exactly as [APPEND] is: writing into nothing is a real mistake, while the
     * READS treat null as empty because that is what an unconnected Map pin supplies.
     */
    const val SETKEY = 69

    /**
     * `NEWMAP a` — `regs[a] = {}`.
     *
     * The map half of [NEWLIST]. Its absence is why `emptyMap()` was a host call: one native round trip to
     * produce a value the VM can make in a single instruction.
     */
    const val NEWMAP = 72

    /**
     * `GETKEY a, b, c` — `regs[a] = regs[b][regs[c]]`, or null when the key is absent.
     *
     * **Null for a missing key is the answer, not a failure.** `valueAt` is typed optional precisely
     * because "there is no entry" is the ordinary case, so this mirrors [LEN]'s tolerance of a null
     * container rather than [SETKEY]'s strictness about one: reading nothing out of nothing is nothing.
     */
    const val GETKEY = 73

    /** `a = regs[b].containsKey(regs[c])` — the question `valueAt` cannot answer when null is a value. */
    const val HASKEY = 74

    /** `a = regs[b].size`. [LEN] is the list; a map needs its own because the two are different types. */
    const val MAPLEN = 75

    /** `DELKEY a, b` — drop `regs[b]` from the map in `regs[a]`, in place. */
    const val DELKEY = 76

    /**
     * `SETINDEX a, b, c` — `regs[a][regs[b]] = regs[c]`, in place.
     *
     * The list half of [SETKEY], and it could not exist while a list was a value: writing one position
     * would have had to rebuild the whole list, which is what `withItemAt` does and why `xs[i] = v` was
     * refused outright. A list is a reference now, so the position is simply written.
     *
     * Strict about a null list, exactly as [APPEND] is: writing into nothing is a real mistake.
     */
    const val SETINDEX = 77

    // ---- declared types ----
    /**
     * `NEWSTRUCT dst, shape, base` — build the record described by `constants[shape]` from the `n` registers
     * at `base`.
     *
     * The shape carries the type's name and its field names, so one constant serves every construction of
     * that type and the instruction itself stays three operands wide. Built fresh each time, never handed
     * out from the constant pool: a pure node is re-expanded at every use site, and a shared record would
     * be one that writing to anywhere changed everywhere.
     */
    const val NEWSTRUCT = 66

    /** `a = b.field[c]` — `c` is the field's INDEX, resolved when the graph was compiled. */
    const val GETFIELD = 67

    /**
     * `SETFIELD a, b, c` — `regs[a] = regs[a].with(field b, regs[c])`.
     *
     * The destination is also the source, which is what fits a four-operand idea onto three: the compiler
     * puts the record in `a` first, so this reads "replace field b of what is in a". The copy happens
     * inside [StructValue.with], so nothing writes through a record another wire is still holding — and
     * with value semantics that is not an optimisation to preserve, it is the whole meaning of the node.
     */
    const val SETFIELD = 68

    // ---- debug ----
    /** Debugger trap; `a` is the authoring node id. Halts the fiber as [FiberState.PAUSED]. */
    const val BREAK = 70

    /**
     * Flow instrumentation: `a` is the authoring node id, `b` a [TraceKind]. Feeds the editor's exec-wire
     * animation and the step debugger. Elided entirely when a chunk is compiled without debug info.
     *
     * For [TraceKind.PURE_EXIT] the third operand `c` carries the REGISTER the result landed in, which is
     * the only way that value is ever observable — see the note there. The fixed-width encoding already
     * pays for `c`, so this costs nothing the other kinds were not already carrying.
     */
    const val TRACE = 71

// `try`/`catch` needs no opcode. See Chunk.handlers: a range table is checked when something is raised,
    // which is what makes `return`/`break`/`continue` out of a guarded block cost nothing to get right.

    /** Mnemonics by opcode, for [Disassembler]. Sparse — unused slots are null. */
    /**
     * Sized from the highest opcode rather than a written number.
     *
     * It was `arrayOfNulls(72)`, which was exactly right until an opcode numbered 72 existed — and then
     * the failure was an `ArrayIndexOutOfBoundsException` from a static initialiser, surfacing as
     * `NoClassDefFoundError` on `BuiltinHosts` in tests that never mention an opcode. A constant that has
     * to be edited in step with a list two hundred lines above it will not be.
     */
    private val NAMES = arrayOfNulls<String>(SETINDEX + 1).also { n ->
        n[HALT] = "HALT"; n[CONST] = "CONST"; n[MOVE] = "MOVE"
        n[ADD] = "ADD"; n[SUB] = "SUB"; n[MUL] = "MUL"; n[DIV] = "DIV"; n[MOD] = "MOD"; n[NEG] = "NEG"
        n[TOF] = "TOF"
        n[EQ] = "EQ"; n[NE] = "NE"; n[LT] = "LT"; n[LE] = "LE"; n[GT] = "GT"; n[GE] = "GE"; n[NOT] = "NOT"
        n[JMP] = "JMP"; n[JMPF] = "JMPF"; n[JMPT] = "JMPT"
        n[CALL] = "CALL"; n[ACT] = "ACT"; n[CALLG] = "CALLG"
        n[CALLV] = "CALLV"; n[CLOSURE] = "CLOSURE"; n[RET] = "RET"
        n[YIELD] = "YIELD"; n[SLEEP] = "SLEEP"
        n[NEWLIST] = "NEWLIST"; n[APPEND] = "APPEND"; n[INDEX] = "INDEX"; n[LEN] = "LEN"
        n[ITER] = "ITER"; n[ITERNEXT] = "ITERNEXT"
        n[NEWSTRUCT] = "NEWSTRUCT"; n[GETFIELD] = "GETFIELD"; n[SETFIELD] = "SETFIELD"
        n[SETKEY] = "SETKEY"
        n[NEWMAP] = "NEWMAP"
        n[GETKEY] = "GETKEY"
        n[HASKEY] = "HASKEY"
        n[MAPLEN] = "MAPLEN"
        n[DELKEY] = "DELKEY"
        n[SETINDEX] = "SETINDEX"
        n[BREAK] = "BREAK"; n[TRACE] = "TRACE"
    }

    fun name(op: Int): String = NAMES.getOrNull(op) ?: "OP_$op"

    /** Pack an argument count and a result count into one operand. */
    fun packCounts(argCount: Int, retCount: Int): Int {
        require(argCount in 0..0xFFFF) { "argCount out of range: $argCount" }
        require(retCount in 0..0xFFFF) { "retCount out of range: $retCount" }
        return (argCount and 0xFFFF) or (retCount shl 16)
    }

    fun argCount(packed: Int): Int = packed and 0xFFFF
    fun retCount(packed: Int): Int = (packed ushr 16) and 0xFFFF
}

/** What a [Op.TRACE] marks, so the editor can distinguish stepping points from exec-wire traversals. */
object TraceKind {
    /** Control entered this node — the step-debugger's statement boundary. */
    const val NODE_ENTER = 0

    /** An exec wire was taken; the editor pulses it via `NodeEditor.flow`. `a` is the *link* id here. */
    const val EXEC_EDGE = 1

    /**
     * A PURE node was expanded during a data pull.
     *
     * Separate from [NODE_ENTER] because a node graph has two evaluation domains and they need different
     * stepping. Exec pins push imperatively and are the statement boundaries you normally step between;
     * data pins pull lazily, and between two visible exec steps there can be a whole hidden tree of pure
     * evaluation. Marking them distinctly is what lets "step" skip that tree and "step into data" walk it.
     */
    const val PURE_ENTER = 2

    /**
     * A PURE node finished evaluating; `c` on the [Op.TRACE] holds the register its result landed in.
     *
     * The only moment that value exists. A pure node is re-expanded at every use site into scratch
     * registers that are released immediately after, so unlike an impure node's output it has no stable
     * slot for the inspector to read later — which is why the wire leaving a Text node, a comparison or any
     * query drew no value pill while an impure node's did. Sampling here, at the instant of consumption, is
     * what makes those observable at all.
     *
     * NOT a stepping boundary: it marks the end of an expression, not the start of one, and stopping here
     * would land the debugger between a value being produced and being used, which is not a place an author
     * can point at on the canvas.
     */
    const val PURE_EXIT = 3
}
