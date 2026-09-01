package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.sameDeclaredType

/**
 * Working out what a type variable stands for — the half `Generics.kt` deliberately stopped short of.
 *
 * That file states its own limit: *"Substitution, not inference. There is one binding site — the receiver
 * — and every occurrence of a variable elsewhere in the signature is replaced by what the receiver bound
 * it to. No unification pass, no constraint solving."* One binding site was enough because the thing being
 * served was a wire: the canvas knows what is plugged into `self` before it needs to know anything else.
 *
 * A call site has no privileged argument. `first(xs)` learns `T` from `xs`, `pairOf(a, b)` learns two
 * variables from two arguments, and neither is a receiver. So the rule generalises: **every parameter
 * contributes**, they are matched left to right, and a variable already bound has to agree.
 *
 * Substitution itself is NOT reimplemented here — `model.substitute` already does it, is pure `TypeRef`
 * manipulation with nothing graph-shaped about it, and having two would be two answers to one question.
 */

/**
 * Match [declared] against [actual], recording what each variable must be.
 *
 * Returns false when they cannot match at all, which is a type error the caller reports with both types in
 * the message. A variable meeting a second, different type is NOT a failure on its own: the two are
 * [widened] together, so `pick(a: 1, b: 2.5)` binds `T` to FLOAT rather than refusing.
 */
fun unify(declared: TypeRef, actual: TypeRef, into: MutableMap<String, TypeRef>): Boolean {
    // Unknown on either side tells us nothing and must not poison a binding — an argument the resolver
    // could not type would otherwise pin every variable it touches to WILDCARD.
    if (Unknown.isUnknown(actual)) return true

    if (declared.variable) {
        val name = declared.name
        // An optional parameter matched against a present value still binds the bare type: `T?` meeting
        // an INT means T is INT, not INT?.
        val bare = if (declared.optional) actual.required() else actual
        val existing = into[name]
        into[name] = if (existing == null) bare else widened(existing, bare) ?: return false
        return true
    }

    if (declared.isWildcard) return true
    if (declared.optional && !actual.optional) return unify(declared.required(), actual, into)
    if (declared.optional && actual.optional) return unify(declared.required(), actual.required(), into)
    if (!declared.optional && actual.optional) return false

    // Containers match structurally, and an UNCONSTRAINED one on either side binds nothing rather than
    // failing: `[]` has no element type yet, and requiring one would refuse the empty list everywhere.
    if (declared.args.isNotEmpty() && actual.args.isNotEmpty()) {
        if (!sameDeclaredType(declared, actual) || declared.args.size != actual.args.size) return false
        return declared.args.indices.all { unify(declared.args[it], actual.args[it], into) }
    }
    if (declared.args.isNotEmpty() || actual.args.isNotEmpty()) return sameDeclaredType(declared, actual)

    return assignable(actual, declared)
}

/**
 * The type that covers both, or null when nothing does.
 *
 * What a list literal's element type is, and what a variable bound twice settles on. INT and FLOAT meet at
 * FLOAT for the same reason an INT may be handed to a FLOAT parameter; anything else has to be one of the
 * two already.
 */
fun widened(a: TypeRef, b: TypeRef): TypeRef? = when {
    Unknown.isUnknown(a) -> b
    Unknown.isUnknown(b) -> a
    a.optional || b.optional -> widened(a.required(), b.required())?.orNull()
    assignable(a, b) -> b
    assignable(b, a) -> a
    a.builtin == PinType.INT && b.builtin == PinType.FLOAT -> b
    a.builtin == PinType.FLOAT && b.builtin == PinType.INT -> a
    else -> null
}

/**
 * The element type of a list literal, from what is in it.
 *
 * An EMPTY list has none — `LIST` unconstrained — and takes the one it is being handed to, which is why
 * `val xs: LIST<INT> = []` works and `val xs = []` is a list of nothing in particular until it is used.
 */
fun elementOf(items: List<TypeRef>): TypeRef? {
    if (items.isEmpty()) return null
    var t = items.first()
    for (next in items.drop(1)) t = widened(t, next) ?: return null
    return t
}
