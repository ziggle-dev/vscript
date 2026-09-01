package dev.ziggle.vscript.model

/**
 * Generics, phase E: a parameterized receiver, and one type variable bound by it.
 *
 * ```vs
 * fn List<Entity>.closest(self) -> Entity?     // Level 0 — no variables at all
 * fn List<T>.first(self) -> T?                 // Level 1 — one variable, bound by the receiver
 * ```
 *
 * **Substitution, not inference.** There is one binding site — the receiver — and every occurrence of a
 * variable elsewhere in the signature is replaced by what the receiver bound it to. No unification pass,
 * no constraint solving, no partial bindings to propagate. That is the whole reason phase E stops here.
 *
 * **Erased at run time.** The VM has no type tags but a struct's own name, so nothing below emits an
 * opcode, a constant or a format change. All of it moves PINS, which is what the validator compares and
 * what the canvas draws.
 *
 * Three questions, in the order they are asked:
 *
 * 1. [typeParametersOf] — which names in this signature are variables? Answered at the receiver, once.
 * 2. [bindReceiver] — given the actual receiver at a call site, what is each variable?
 * 3. [substitute] — rewrite the rest of the signature with those answers.
 */

/**
 * The type parameters a receiver introduces — its arguments that name no type in scope.
 *
 * **The receiver's argument list is a BINDING SITE**, which is what makes this decidable at all. Every
 * other position in a signature *uses* a name and so can be checked against what is declared; this one
 * introduces one, and a binding site cannot be typo-checked — `fn List<Etity>.f(self)` is a generic
 * function over `Etity` for the same reason `fn f(etity: Int)` is a parameter called `etity`. The
 * validator's answer to that is [unusedTypeParameters], which catches the realistic version of the
 * mistake: a name introduced and then never used.
 *
 * The test is deliberately narrow, so nothing that IS a type can slip through:
 *
 * - **a built-in** is not a variable — `LIST<ENTITY>` is Level 0, and `Any` is a wildcard;
 * - **a qualified name** is not — `geo::Point` names another document's record, and a variable is never
 *   written with a module;
 * - **a name with arguments of its own** is not — `LIST<LIST<Int>>`;
 * - **a name this document declares** is not, which is the case that makes the rule safe: a record or an
 *   enum called `Target` stays `Target` rather than quietly becoming a variable that accepts anything.
 *
 * [declares] is asked of the DOCUMENT — its records and its enums, plus the built-in records. Imported
 * types are excluded on purpose and cost nothing: they are always written qualified, so the second rule
 * already caught them.
 */
fun typeParametersOf(receiver: TypeRef?, declares: (String) -> Boolean): Set<String> =
    receiver?.args.orEmpty()
        .filter { isTypeParameterName(it) && !declares(it.name) }
        .map { it.name }
        .toSet()

/**
 * The OTHER binding site: a conventionally-spelled name anywhere else in the signature.
 *
 * A receiver is not the only place a type parameter can be introduced. `fn LIST<T>.map(self, f: fn(T) -> U)
 * -> LIST<U>` introduces `U` in a parameter's type and uses it in the result, and `fn identity(x: T) -> T`
 * introduces one with no receiver at all — neither is reachable from [typeParametersOf]'s receiver rule,
 * and before this both reported "typed 'U', which this graph does not declare".
 *
 * **This rule is narrower than the receiver's, and deliberately so.** A receiver's type arguments are a
 * position that can hold nothing but a type parameter or a concrete type, so "unrecognised name there" is
 * evidence on its own. A parameter's type is not: `p: Tille` is overwhelmingly a typo for a record, and a
 * rule that read it as a type variable would silently make it accept anything and throw away the one
 * diagnostic that would have caught it. So OUTSIDE the receiver a name must also LOOK like a variable —
 * [CONVENTIONAL], which is `T`, `K`, `V`, `T2` and nothing else.
 *
 * It is a convention rather than a declaration (`fn <U> …`), and that is a real trade: the compiler cannot
 * tell a deliberate `U` from a record called `U` that a later edit deletes. What it buys is that no
 * signature in the language grows a second place to say the same thing, and the spelling being enforced is
 * the one every author already uses.
 */
private fun conventionalVariables(t: TypeRef, declares: (String) -> Boolean, into: MutableSet<String>) {
    if (isTypeParameterName(t) && CONVENTIONAL.matches(t.name) && !declares(t.name)) into += t.name
    t.args.forEach { conventionalVariables(it, declares, into) }
}

private fun isTypeParameterName(t: TypeRef): Boolean =
    t.builtin == null && t.args.isEmpty() && !t.name.contains(QualName.SEP) && t.name.isNotBlank()

/** [t] with every occurrence of a name in [vars] marked as a variable. Recurses into type arguments. */
fun markTypeVariables(t: TypeRef, vars: Set<String>): TypeRef {
    if (vars.isEmpty()) return t
    if (t.name in vars && isTypeParameterName(t)) return t.asVariable()
    if (t.args.isEmpty()) return t
    val args = t.args.map { markTypeVariables(it, vars) }
    return if (args == t.args) t else TypeRef.of(t, args)
}

/**
 * [fn] with its type variables marked — the one normalisation that turns a parsed or decoded signature
 * into one the type rules can use.
 *
 * **Applied at both boundaries and nowhere else.** `Lower` calls it when it builds a signature from
 * source; `GraphDoc.decode` calls it when it reads one back from JSON. Both know exactly the same thing —
 * this document's own declarations — so the two agree by construction, which is what lets the persisted
 * form stay the bare name the author wrote (see [TypeRef.toString]).
 *
 * A function with no receiver, or one whose receiver takes no arguments, is handed straight back: the
 * overwhelming majority of signatures allocate nothing here.
 */
fun withTypeVariables(fn: GraphFunction, declares: (String) -> Boolean): GraphFunction {
    val vars = HashSet(typeParametersOf(fn.receiver, declares))
    // Both binding sites, unioned. The receiver's rule is the permissive one and runs first, so a `T` it
    // introduces stays a variable everywhere it appears even where it would not have matched [CONVENTIONAL]
    // on its own — `fn LIST<Elem>.first(self) -> Elem?` still works.
    for (t in fn.params.map { it.type } + fn.results.map { it.type }) {
        conventionalVariables(t, declares, vars)
    }
    if (vars.isEmpty()) return fn
    return GraphFunction(
        fn.name,
        fn.params.map { FunctionPin(it.name, markTypeVariables(it.type, vars), it.default) },
        fn.results.map { FunctionPin(it.name, markTypeVariables(it.type, vars), it.default) },
        fn.isExported,
        receiver = fn.receiver?.let { markTypeVariables(it, vars) },
    )
}

/**
 * Variables the signature introduces, never mentions again, and does not SPELL like a variable.
 *
 * The one check that catches a mistyped receiver argument. `fn List<Entiity>.closest(self) -> Entity?`
 * is, under [typeParametersOf]'s rule, a perfectly good generic function over `Entiity` — and it is almost
 * certainly a typo for a type that exists, because a variable nothing else uses buys nothing. Reported as
 * a warning rather than an error: it IS legal, and a half-written signature should not go red while it is
 * being typed.
 *
 * **"Unused" alone was too blunt, and `core/list.vs` is the proof:** `fn List<T>.isEmpty(self) -> Bool`
 * mentions `T` nowhere but the receiver and is perfectly correct — the receiver is generic and the answer
 * is not. Warning there is noise on shipped code, and noise is how a warning stops being read.
 *
 * So the convention decides: a single capital, optionally numbered — `T`, `U`, `K`, `V`, `T1` — is taken
 * at its word and never reported. **That is a rule about a LINT, not about the type system.** Nothing
 * anywhere else consults the spelling of a name, and nothing may: which names are variables is answered
 * structurally, at the binding site, by [typeParametersOf]. This only decides whether to say something
 * about one, and for that a naming convention is exactly the right kind of evidence — nobody writes
 * `List<Etity>` meaning a variable, and nobody writes `List<T>` meaning a type.
 */
fun unusedTypeParameters(fn: GraphFunction): Set<String> {
    val declared = fn.receiver?.args.orEmpty()
        .filter { it.variable && !CONVENTIONAL.matches(it.name) }
        .map { it.name }.toSet()
    if (declared.isEmpty()) return emptySet()
    val used = HashSet<String>()
    fun walk(t: TypeRef) {
        if (t.variable) used += t.name
        t.args.forEach { walk(it) }
    }
    // The receiver's own arguments are the declaration, not a use — so only the rest of the signature counts.
    fn.params.filter { it.name != GraphFunction.SELF }.forEach { walk(it.type) }
    fn.results.forEach { walk(it.type) }
    return declared - used
}

/**
 * What the receiver's type variables are at THIS call site, from the type actually wired into `self`.
 *
 * Matched structurally and positionally: `LIST<T>` against `LIST<ENTITY>` binds `T` to `ENTITY`. A
 * mismatch in shape simply binds nothing — the pins then keep their variables, which behave as wildcards,
 * so an unresolvable receiver leaves the call exactly as permissive as it was before generics existed.
 * That is the degradation every caller of [resolveNode] that does not know what feeds a pin relies on.
 *
 * The `?` is dropped on the way in: `LIST<ENTITY>?` still binds `T` to `ENTITY`, because what a list HOLDS
 * does not change with whether the list itself might be absent.
 */
/** How a type variable is conventionally spelled — see [unusedTypeParameters]. A lint's rule, nothing else's. */
private val CONVENTIONAL = Regex("[A-Z][0-9]*")

/** Is there anything here to bind? The guard that keeps a non-generic call from walking the graph at all. */
fun hasTypeVariables(t: TypeRef?): Boolean =
    t != null && (t.variable || t.args.any { hasTypeVariables(it) })

fun bindReceiver(declared: TypeRef?, actual: TypeRef?): Map<String, TypeRef> {
    if (declared == null || actual == null) return emptyMap()
    val out = HashMap<String, TypeRef>()
    bind(declared, actual, out)
    return out
}

private fun bind(declared: TypeRef, actual: TypeRef, out: MutableMap<String, TypeRef>) {
    if (declared.variable) {
        val v = if (declared.optional) actual.required() else actual
        // A wildcard is "nothing here says", which is not a constraint. Recording it would make the
        // variable resolve to WILDCARD and quietly stop checking every other position.
        if (v.isWildcard) return
        out[declared.name] = join(out[declared.name], v)
        return
    }
    if (actual.isWildcard) return
    declared.args.forEachIndexed { i, d -> actual.args.getOrNull(i)?.let { bind(d, it, out) } }
}

/**
 * Two constraints on one variable, resolved to the one that admits both.
 *
 * **First-sight binding is wrong, and this is the case that proves it** (`docs/VSCRIPT_ERGONOMICS_PLAN.md`
 * §5.4c). vs has no subtyping, which normally makes unification trivial — but it has two coercions, and a
 * left-to-right binder gets the first of them backwards every time: `T` against an `INT` argument and then
 * a `FLOAT` one must resolve to `FLOAT`, because that is the type that accepts both, and committing to
 * `INT` on sight refuses a call that is correct.
 *
 * Genuinely incompatible constraints keep the FIRST rather than widening to a wildcard. That is what makes
 * the diagnostic good: `["a"].has(value: 1)` binds `T` from the receiver, so the INT wire into a STRING pin
 * is what gets reported — a mismatch at the argument the author wrote, rather than "could not infer T".
 */
private fun join(a: TypeRef?, b: TypeRef): TypeRef = when {
    a == null -> b
    a == b -> a
    widens(a, b) -> b
    widens(b, a) -> a
    // The other coercion: `T` flows into `T?`. A slot that may be absent has to keep being able to say so.
    a.required() == b.required() -> if (a.optional) a else b
    else -> a
}

/**
 * Every constraint this call site puts on the signature's type variables.
 *
 * The receiver first, because it is where the variables are DECLARED and so is the reading an author
 * expects to win; then every other parameter, in order. Level 1 asked only the receiver, which is exactly
 * the "substitution, not unification" it was defined as — this is the same machinery given the rest of the
 * pins to look at.
 *
 * Asked only of a CALL. The FUNCTION box is the signature seen from INSIDE, where nothing can say what a
 * variable is and nothing should try: `T` stays unbound there and behaves as a wildcard, which is what
 * makes a generic body writable at all.
 */
fun bindCall(fn: GraphFunction, nodeId: Int, feeding: (Int, String) -> TypeRef?): Map<String, TypeRef> {
    val out = HashMap<String, TypeRef>()
    if (fn.self != null && hasTypeVariables(fn.receiver)) {
        feeding(nodeId, GraphFunction.SELF)?.let { bind(fn.receiver!!, it, out) }
    }
    for (p in fn.params) {
        if (p.name == GraphFunction.SELF) continue
        if (!hasTypeVariables(p.type)) continue
        feeding(nodeId, p.name)?.let { bind(p.type, it, out) }
    }
    return out
}

/** Does anything in this signature need binding at all? The guard that keeps ordinary calls free. */
/**
 * A record's own declared type — `Pair<A, B>` with `A` and `B` marked, or just `Pair`.
 *
 * The left-hand side of every binding a generic record does: what a `struct.get` compares its incoming
 * value against, and what [substitute] rewrites once the arguments are known.
 */
fun declaredTypeOf(t: StructType): TypeRef =
    if (t.params.isEmpty()) TypeRef.named(t.name)
    else TypeRef.of(TypeRef.named(t.name), t.params.map { TypeRef.named(it).asVariable() })

/**
 * [t] with its field types marked, so `first: A` is a variable rather than a record nobody declared.
 *
 * The record's counterpart to [withTypeVariables], and applied at exactly the same two boundaries — `Lower`
 * from source, `GraphDoc.decode` from JSON. Simpler than the function's, because a record SAYS what its
 * parameters are: there is no rule to infer them by and therefore no [CONVENTIONAL] test to apply.
 */
fun withFieldVariables(t: StructType): StructType {
    if (t.params.isEmpty()) return t
    val vars = t.params.toSet()
    return StructType(
        t.name,
        t.fields.map { FunctionPin(it.name, markTypeVariables(it.type, vars), it.default) },
        t.isExported,
        t.params,
    )
}

/**
 * What a generic record's parameters are AT THIS CONSTRUCTION — bound from the field values wired in.
 *
 * `Pair { first: 1, second: "a" }` is a `Pair<INT, STRING>` and nothing had to say so. The alternative was
 * to make the author write `Pair<INT, STRING> { … }`, which is a second place to state a fact the values
 * already carry — and the first place would go stale.
 */
fun bindFields(t: StructType, nodeId: Int, feeding: (Int, String) -> TypeRef?): Map<String, TypeRef> {
    val out = HashMap<String, TypeRef>()
    for (f in t.fields) {
        if (!hasTypeVariables(f.type)) continue
        feeding(nodeId, f.name)?.let { bind(f.type, it, out) }
    }
    return out
}

fun isGeneric(fn: GraphFunction): Boolean =
    hasTypeVariables(fn.receiver) || fn.params.any { hasTypeVariables(it.type) } ||
        fn.results.any { hasTypeVariables(it.type) }

/**
 * [t] with each variable replaced by what [bound] says it is. An unbound one is left alone.
 *
 * `T?` substituted with `Entity` is `Entity?` — the `?` belongs to the position, not to what filled it,
 * so `fn List<T>.first(self) -> T?` on a `List<Entity>` returns `Entity?` and `if val` narrows it the
 * ordinary way.
 */
fun substitute(t: TypeRef, bound: Map<String, TypeRef>): TypeRef {
    if (bound.isEmpty()) return t
    if (t.variable) {
        val b = bound[t.name] ?: return t
        return if (t.optional) b.orNull() else b
    }
    if (t.args.isEmpty()) return t
    val args = t.args.map { substitute(it, bound) }
    return if (args == t.args) t else TypeRef.of(t, args)
}

/**
 * How specific a receiver is, for picking between extensions of the same name.
 *
 * `LIST<ENTITY>` (2) beats `LIST` (1) beats `Any` (0), and `LIST<T>` (1) sits with the unconstrained list
 * — which is right, because a variable really does say no more about the element than leaving it off
 * does. Ties are broken by the ordinary rule, in `Lower.extensionCall`: this document's own wins.
 */
fun receiverSpecificity(t: TypeRef?): Int = when {
    t == null -> 0
    t.isWildcard || t.variable -> 0
    else -> 1 + t.args.sumOf { receiverSpecificity(it) }
}
