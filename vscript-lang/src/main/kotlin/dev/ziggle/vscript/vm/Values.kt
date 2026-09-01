package dev.ziggle.vscript.vm

/**
 * Runtime value semantics for the VM.
 *
 * Values are plain JVM objects (`Int`, `Long`, `Double`, `Boolean`, `String`, `MutableList`, and opaque
 * game handles), which keeps the host boundary free of wrapping and unwrapping — a node implementation
 * receives and returns exactly what it would in Kotlin.
 *
 * **Truthiness is deliberately strict.** Only `Boolean` (and `null`, as false) can drive a conditional
 * jump. The graph is statically typed, so a conditional's operand is always a Bool pin; anything else
 * reaching [truth] means the *compiler* emitted something wrong, and a loud failure at that point is worth
 * far more than a lenient coercion that hides it until a bot misbehaves at 3am.
 */
object Values {

    /** Interpret [v] as a condition. Strict — see the class note. */
    fun truth(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        else -> throw VmError(
            "expected a Bool for a conditional, got ${typeName(v)} ($v) — " +
                "this indicates a compiler bug, not a script bug"
        )
    }

    /** A short type name for diagnostics. */
    fun typeName(v: Any?): String = when (v) {
        null -> "null"
        is Int -> "Int"
        is Long -> "Long"
        is Double -> "Double"
        is Boolean -> "Bool"
        is String -> "String"
        is List<*> -> "List"
        is Map<*, *> -> "Map"
        // Its own name, not its class's — a record's type is what a document called it.
        is StructValue -> v.type
        else -> v.javaClass.simpleName
    }

    fun toLong(v: Any?): Long = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Double -> v.toLong()
        else -> throw VmError("expected a number, got ${typeName(v)}")
    }

    fun toInt(v: Any?): Int = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        else -> throw VmError("expected a number, got ${typeName(v)}")
    }

    /**
     * Numeric binary op with the usual promotion: Double wins over Long wins over Int.
     *
     * [Op.ADD] additionally concatenates when either side is a String, which is what makes status/log nodes
     * pleasant to wire up.
     */
    fun arith(op: Int, l: Any?, r: Any?): Any {
        if (op == Op.ADD && (l is String || r is String)) return str(l) + str(r)
        if (l is Double || r is Double) {
            val a = toDouble(l); val b = toDouble(r)
            return when (op) {
                Op.ADD -> a + b
                Op.SUB -> a - b
                Op.MUL -> a * b
                Op.DIV -> a / b
                Op.MOD -> a % b
                else -> throw VmError("not an arithmetic op: ${Op.name(op)}")
            }
        }
        if (l is Long || r is Long) {
            val a = toLong(l); val b = toLong(r)
            if ((op == Op.DIV || op == Op.MOD) && b == 0L) throw VmError("division by zero")
            return when (op) {
                Op.ADD -> a + b
                Op.SUB -> a - b
                Op.MUL -> a * b
                Op.DIV -> a / b
                Op.MOD -> a % b
                else -> throw VmError("not an arithmetic op: ${Op.name(op)}")
            }
        }
        // **Two Ints are worked out in SIXTY-FOUR bits and narrowed back when the answer fits.**
        //
        // Not a widening of the type — an Int that stays an Int is still an Int, and every in-range sum
        // gives exactly what it gave before. What changes is the answer that does NOT fit: it used to wrap
        // silently, and now it is simply the right number, as a Long.
        //
        // Wrapping was never a decision anyone made; it was what the JVM does. And the way it showed up
        // was the worst kind — `gained * 3600000` for an experience rate goes negative the moment a session
        // passes 597 experience, so the panel reported a NEGATIVE rate that wrapped round again as the run
        // went on. Three scripts carry a `3600000.0` scale factor written purely to force Double promotion
        // and get away from it, with a comment calling it "a correctness fix rather than a style".
        //
        // Narrowing back matters as much as the width: leaving every result a Long would make `typeName`
        // say Long for ordinary arithmetic, and anything that reads a type off a value — a pin, a printed
        // default — would start disagreeing with the declaration for no reason the author could see.
        val a = toLong(l); val b = toLong(r)
        if ((op == Op.DIV || op == Op.MOD) && b == 0L) throw VmError("division by zero")
        val wide = when (op) {
            Op.ADD -> a + b
            Op.SUB -> a - b
            Op.MUL -> a * b
            Op.DIV -> a / b
            Op.MOD -> a % b
            else -> throw VmError("not an arithmetic op: ${Op.name(op)}")
        }
        return if (wide in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) wide.toInt() else wide
    }

    fun negate(v: Any?): Any = when (v) {
        is Int -> -v
        is Long -> -v
        is Double -> -v
        else -> throw VmError("cannot negate ${typeName(v)}")
    }

    /** Ordering comparison for LT/LE/GT/GE — numeric, or lexicographic for two Strings. */
    fun compare(l: Any?, r: Any?): Int {
        if (l is String && r is String) return l.compareTo(r)
        if (l is Double || r is Double) return toDouble(l).compareTo(toDouble(r))
        if (l is Long || r is Long) return toLong(l).compareTo(toLong(r))
        return toInt(l).compareTo(toInt(r))
    }

    /**
     * Equality for EQ/NE.
     *
     * Numbers compare by *value across types*, so `1` and `1L` are equal — otherwise an Int literal wired
     * into a pin fed by a Long-returning node would silently never match, which is exactly the class of bug
     * a visual author cannot debug by reading the graph.
     */
    fun eq(l: Any?, r: Any?): Boolean {
        if (l == null || r == null) return l == null && r == null
        if (l is Number && r is Number) {
            if (l is Double || r is Double) return toDouble(l) == toDouble(r)
            return toLong(l) == toLong(r)
        }
        return l == r
    }

    /** Public because the conversion nodes are host functions and need exactly this rule. */
    fun toDouble(v: Any?): Double = when (v) {
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Double -> v
        else -> throw VmError("expected a number, got ${typeName(v)}")
    }

    private fun str(v: Any?): String = v?.toString() ?: "null"
}
