package dev.ziggle.vscript.vm

/**
 * A function, as a value — what `mapped(list: xs, using: double)` puts on the wire.
 *
 * **An index into the linked program, plus whatever it closed over.** A reference to a named function is
 * the index alone: a top-level function has no environment, so [captured] is empty and passing one costs
 * what passing a number costs. A **lambda** may read the locals of the body it was written in, and those
 * cannot be reached from inside the callee's frame — a frame is one call's registers — so they are copied
 * in when the value is built and handed back to the callee on entry. See [Op.CLOSURE].
 *
 * It is a class rather than a bare Int for one reason beyond that: a bare Int is indistinguishable from an
 * INT at run time, so `say(f)` would print `7` and `f == 7` would be true. Both are nonsense the static
 * types already refuse, but the VM is also the debugger's window onto a running script, and "7" is a worse
 * answer there than "fn double" is.
 *
 * [name] is carried for diagnostics and takes no part in identity — two references to the same function are
 * the same value whatever spelling reached them. [captured] IS part of identity, and has to be: two
 * closures over the same lambda but different values do different things, so calling them equal would be
 * a lie the debugger repeats.
 */
class FunctionValue(
    val index: Int,
    val name: String,
    /**
     * The values this closed over, in the order the callee's trailing parameters expect them.
     *
     * **Copied at the moment the value is built**, which is what makes a closure here a value rather than
     * a reference to a frame: the local it came from may be reassigned, or its frame may have returned,
     * and neither can be observed through this. That is the same choice lists and records already make.
     */
    val captured: List<Any?> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        other is FunctionValue && other.index == index && other.captured == captured

    override fun hashCode(): Int = 31 * index + captured.hashCode()

    override fun toString(): String = if (captured.isEmpty()) "fn $name" else "fn $name +${captured.size}"
}
