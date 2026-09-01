package dev.ziggle.vscript.vm

/**
 * Renders a [Chunk] as readable assembly.
 *
 * Pulls double duty: it makes hand-written test fixtures verifiable at a glance, and it is what the
 * editor's "show bytecode" view will render once the compiler exists — being able to see what a graph
 * compiled to is the difference between debugging a compiler and guessing at it.
 */
object Disassembler {

    /**
     * [chunk], then every function in its program.
     *
     * The program is FLAT, so each function is printed exactly once however many places call it — and the
     * listing no longer duplicates a helper per call site, or recurse forever on a function that calls
     * itself. [chunk] is skipped in the function list when it is itself part of the program.
     */
    fun disassemble(chunk: Chunk, indent: String = ""): String = buildString {
        append(one(chunk, indent))
        for ((i, fn) in chunk.program.withIndex()) {
            if (fn === chunk) continue
            append(indent).append("  -- function ").append(i).append(" --\n")
            append(one(fn, "$indent  "))
        }
    }

    /** One chunk's header and instructions, with no program listing. */
    private fun one(chunk: Chunk, indent: String): String = buildString {
        append(indent).append("chunk '").append(chunk.name).append("'  ")
            .append(chunk.paramCount).append(" params, ")
            .append(chunk.maxRegs).append(" regs, ")
            .append(chunk.size).append(" insns\n")

        for (pc in 0 until chunk.size) {
            append(indent).append(line(chunk, pc)).append('\n')
        }
    }

    /** One instruction, formatted with its operands resolved to something meaningful. */
    fun line(chunk: Chunk, pc: Int): String {
        val op = chunk.op(pc)
        val a = chunk.a(pc)
        val b = chunk.b(pc)
        val c = chunk.c(pc)
        val node = chunk.nodeIdAt(pc)

        val operands = when (op) {
            Op.HALT, Op.YIELD -> ""
            Op.CONST -> "r$a <- k$b (${render(chunk.constants.getOrNull(b))})"
            Op.MOVE, Op.NEG, Op.NOT, Op.LEN, Op.ITER -> "r$a <- r$b"
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD,
            Op.EQ, Op.NE, Op.LT, Op.LE, Op.GT, Op.GE,
            Op.INDEX -> "r$a <- r$b, r$c"
            Op.JMP -> "-> $a"
            Op.JMPF, Op.JMPT -> "r$a -> $b"
            Op.NEWLIST -> "r$a <- []"
            Op.APPEND -> "r$a += r$b"
            Op.SETKEY -> "r$a[r$b] = r$c"
            Op.ITERNEXT -> "r$a <- next(r$b), exhausted -> $c"
            Op.SLEEP -> "r$a ms"
            Op.CALL, Op.ACT -> {
                val name = chunk.hostNames.getOrNull(a) ?: "?$a"
                "$name  args@r$b x${Op.argCount(c)} -> ${Op.retCount(c)}"
            }
            Op.CALLG -> {
                val name = chunk.program.getOrNull(a)?.name ?: "?$a"
                "$name  args@r$b x${Op.argCount(c)} -> ${Op.retCount(c)}"
            }
            Op.RET -> "r$a x$b"
            Op.BREAK -> "node $a"
            Op.TRACE -> "node $a " + if (b == TraceKind.EXEC_EDGE) "(exec edge)" else "(enter)"
            else -> "$a, $b, $c"
        }

        val suffix = if (node >= 0 && op != Op.TRACE && op != Op.BREAK) "    ; node $node" else ""
        return "%4d  %-9s %s%s".format(pc, Op.name(op), operands, suffix)
    }

    private fun render(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        else -> v.toString()
    }
}
