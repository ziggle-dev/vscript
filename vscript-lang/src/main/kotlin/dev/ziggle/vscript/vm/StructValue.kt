package dev.ziggle.vscript.vm

/**
 * A value of a type the document declared — a record, at runtime.
 *
 * **Positional fields, not a map.** The declaration fixes the field order, so the compiler can turn
 * `coordinate.y` into an index and the VM never hashes a string. That is the same reasoning that made this
 * a register machine rather than a stack one: the shape is known when the graph is compiled, so pay for it
 * then. A map would also have cost the two things that matter most for debugging — a `Map` has no type
 * identity, so the inspector would say `LinkedHashMap` where it now says `Coordinate`, and a value pill on
 * a wire would read as a pile of braces.
 *
 * **Value semantics.** Nothing mutates one of these in place. The compiler already learned this lesson with
 * list literals: a constant is one object handed out at every evaluation, and a pure node is re-expanded at
 * every use site, so anything that wrote through a shared value would quietly change it everywhere it had
 * ever been read. A struct is small; copying it is not a cost worth reasoning about.
 */
class StructValue(
    /** The declared type's name — the same string the document and the pins use. */
    val type: String,
    /** Field names, in declaration order. Shared with every value of this type. */
    val names: List<String>,
    private val values: Array<Any?>,
) {
    val size: Int get() = values.size

    operator fun get(index: Int): Any? = values.getOrNull(index)

    fun get(field: String): Any? = names.indexOf(field).takeIf { it >= 0 }?.let { values[it] }

    /** A copy with one field replaced — how a field is ever "set". See the note on value semantics. */
    fun with(index: Int, value: Any?): StructValue {
        if (index !in values.indices) return this
        val next = values.copyOf()
        next[index] = value
        return StructValue(type, names, next)
    }

    /**
     * Two records are equal when they are the same type and hold the same things.
     *
     * Structural, so a struct behaves like the numbers and strings beside it rather than like a handle —
     * `Equals` on a wire carrying one should answer the question an author is actually asking.
     */
    override fun equals(other: Any?): Boolean =
        other is StructValue && other.type == type && other.values.contentEquals(values)

    override fun hashCode(): Int = type.hashCode() * 31 + values.contentHashCode()

    /** Legible enough to be a value pill on a wire, which is the only place most of these are ever seen. */
    override fun toString(): String =
        names.indices.joinToString(", ", "$type(", ")") { "${names[it]}=${values[it]}" }
}

/**
 * What a declared type looks like, held once in the constant pool.
 *
 * A constant rather than operands on the instruction: the type's name and its field names are the same for
 * every record of that type ever built, so carrying them per-construction would repeat them, and there is no
 * room on a three-operand instruction for a list of names anyway.
 */
class StructShape(val type: String, val names: List<String>) {
    override fun equals(other: Any?): Boolean =
        other is StructShape && other.type == type && other.names == names

    override fun hashCode(): Int = type.hashCode() * 31 + names.hashCode()

    override fun toString(): String = names.joinToString(", ", "$type{", "}")
}
