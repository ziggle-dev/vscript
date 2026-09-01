package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.sameDeclaredType
import dev.ziggle.vscript.model.TypeRef

/**
 * Whether a value of [from] may be used where [to] is wanted — the text language's own rule.
 *
 * **Deliberately not `model.canConnect`.** That function answers a question about a WIRE, and a wire is
 * drawn in an editor where "this pin is generic, let whatever connects decide" is a useful answer. Two of
 * its branches are that accommodation rather than a type rule: a WILDCARD accepts everything in both
 * directions, and so does an unbound type variable. Inheriting them here would mean the text language's
 * checker agreed to anything the moment a native's pin was left generic — which is most of them.
 *
 * So the shape is the same and the strictness is not, and where the two deliberately differ it is written
 * down below rather than discovered later.
 *
 * The rules, in the order they are applied:
 *
 *  - **Nothing is assignable to nothing.** `NOTHING` is what a call that hands nothing back produces, and
 *    using it as a value is an error rather than a silent null.
 *  - **`T` flows into `T?`; `T?` does not flow into `T`.** Everything optionals buy is these two lines.
 *  - **null goes into any optional** and nowhere else.
 *  - **INT widens to FLOAT** and the compiler emits the conversion. Narrowing is refused, because which
 *    way to round is a decision — `floor`/`ceil`/`round` are how it gets made out loud.
 *  - **Containers compare their contents**, and an unconstrained one accepts and is accepted. `LIST` with
 *    no element type is what an empty literal has before anything says otherwise.
 *  - **Functions are contravariant in their parameters** and covariant in their result, which is not a
 *    typo: one that copes with anything can stand in where one that copes with tiles was wanted.
 *  - **A declared type matches by DECLARATION and arguments** — see [sameDeclaredType]. Two records with
 *    identical fields are two types, and one record reached by two different spellings is one type.
 *  - **WILDCARD is unknown, not "anything"** — see [Unknown].
 */
fun assignable(from: TypeRef, to: TypeRef): Boolean = when {
    // A value that does not exist cannot be used as one, whichever side it is on.
    isNothing(from) || isNothing(to) -> false

    // **UNKNOWN, both ways.** A wildcard here means the resolver could not work the type out — an
    // unannotated native pin, or an expression it does not understand yet. Refusing would turn every gap
    // in the checker into a false error in somebody's script; accepting silently is how a checker becomes
    // decorative. It accepts, and [Unknown.isUnknown] is what a caller checks when it needs to know that
    // an answer was a shrug rather than a yes.
    from.isWildcard || to.isWildcard -> true
    from.variable || to.variable -> true

    isNull(from) -> to.optional

    from.optional && !to.optional -> false
    from.optional || to.optional -> assignable(from.required(), to.required())

    from.isList && to.isList -> {
        val f = from.of
        val t = to.of
        f == null || t == null || assignable(f, t)
    }

    from.isMap && to.isMap ->
        from.args.isEmpty() || to.args.isEmpty() ||
            (from.args.size == to.args.size && from.args.indices.all { assignable(from.args[it], to.args[it]) })

    from.isFunction && to.isFunction ->
        from.args.isEmpty() || to.args.isEmpty() ||
            (
                from.args.size == to.args.size &&
                    // **NOTHING has to be compared here, not refused.** A function that hands nothing back
                    // is an ordinary shape — `fn(Activity)` is most of the callbacks in the corpus — and
                    // the blanket "nothing is assignable to nothing" rule above made every one of them
                    // incompatible with itself.
                    resultsMatch(from.resultOf, to.resultOf) &&
                    to.paramsOf.indices.all { i -> assignable(to.paramsOf[i], from.paramsOf[i]) }
                )

    // **A host record may widen into another** — `NpcRef` into `EntityRef`, so the verbs that take any
    // live thing keep one signature while the three references keep their own identities. One direction
    // only: an `EntityRef` is not an `NpcRef`. See `HostRecord.widensTo`.
    widensToHost(from, to) -> true

    // **The same DECLARATION, not the same spelling.** Two documents may each declare a `Config`, and one
    // declaration may be reached by names no reader shares; `sameDeclaredType` reads `TypeRef.owner`, which
    // is decided once at the declaration and never re-derived from whoever is asking.
    sameDeclaredType(from, to) && from.args.size == to.args.size ->
        from.args.indices.all { assignable(from.args[it], to.args[it]) }

    from.builtin == PinType.INT && to.builtin == PinType.FLOAT -> true

    // **The last of the name-shaped bridges.** `SKILL` used to be here, so `skillReal(skill: "Attak")`
    // typechecked and answered nothing at run time — the fixed list that would have caught it existed and
    // was consulted by nobody. `Skill` is an ordinary host enum now and `Skill.Attack` is how you write
    // one, so the bridge has nothing left to carry there.
    //
    // `ENUM` keeps it for now: those are the pins still declared with `PinSpec.options`, a STRING the
    // canvas happens to draw as a dropdown, and they go the same way as their options become real enums.
    from.builtin == PinType.STRING && to.builtin == PinType.ENUM -> true

    else -> false
}

/** Whether [from] is a host record that may be used where [to] is wanted. */
private fun widensToHost(from: TypeRef, to: TypeRef): Boolean {
    if (from.owner != null || to.owner != null) return false
    val record = HostRecords.of(from) ?: return false
    return record.widening().drop(1).any { it.name == to.name }
}

/** Two function results, where "hands nothing back" is a legitimate answer on both sides. */
private fun resultsMatch(from: TypeRef?, to: TypeRef?): Boolean {
    val f = from ?: TypeRef.WILDCARD
    val t = to ?: TypeRef.WILDCARD
    if (isNothing(f) && isNothing(t)) return true
    // One returns something and the other does not: fine in the direction that DISCARDS it, which is what
    // passing `fn(T) -> BOOL` where `fn(T)` was wanted means.
    if (isNothing(t)) return true
    if (isNothing(f)) return false
    return assignable(f, t)
}

/** `NOTHING` — what a call with no results produces. Not a type anything can hold. */
fun isNothing(t: TypeRef): Boolean = t === TypeRef.NOTHING || t.name == TypeRef.NOTHING.name

/** The type of the `null` literal: absent, and compatible with any optional. */
val NULL: TypeRef = TypeRef.named("NULL").orNull()

fun isNull(t: TypeRef): Boolean = t.name == "NULL"

/**
 * Whether a type is the resolver shrugging rather than deciding.
 *
 * Kept apart from [assignable] on purpose: a shrug must not BLOCK anything (that would make every gap in
 * the checker an error in somebody's script), but a caller that is about to make a decision on the answer
 * — which arithmetic opcode to emit, whether a field exists — needs to know it did not get one.
 */
object Unknown {
    fun isUnknown(t: TypeRef): Boolean = t.isWildcard || t.variable
}

/**
 * The type `a op b` produces for the arithmetic operators.
 *
 * `+` is the one that is not merely arithmetic: a STRING on either side makes it concatenation, which is
 * how every message in the corpus is built. Null when the pair has no answer, which is the caller's cue
 * to complain with both types in the message.
 */
fun arithResult(op: String, a: TypeRef, b: TypeRef): TypeRef? {
    if (Unknown.isUnknown(a) || Unknown.isUnknown(b)) return TypeRef.WILDCARD
    val string = TypeRef(PinType.STRING)
    if (op == "+" && (a.builtin == PinType.STRING || b.builtin == PinType.STRING)) return string
    val ka = a.builtin
    val kb = b.builtin
    if (ka !in NUMERIC || kb !in NUMERIC) return null
    return if (ka == PinType.FLOAT || kb == PinType.FLOAT) TypeRef(PinType.FLOAT) else TypeRef(PinType.INT)
}

private val NUMERIC = setOf(PinType.INT, PinType.FLOAT)

/** Whether two types may be compared with `==` / `!=` — either direction assignable is enough. */
fun comparable(a: TypeRef, b: TypeRef): Boolean = assignable(a, b) || assignable(b, a)

/** Whether `<`, `<=`, `>`, `>=` mean anything for this pair. Numbers and strings only. */
fun ordered(a: TypeRef, b: TypeRef): Boolean {
    if (Unknown.isUnknown(a) || Unknown.isUnknown(b)) return true
    val orderable = setOf(PinType.INT, PinType.FLOAT, PinType.STRING)
    return a.builtin in orderable && b.builtin in orderable &&
        (a.builtin == b.builtin || (a.builtin in NUMERIC && b.builtin in NUMERIC))
}
