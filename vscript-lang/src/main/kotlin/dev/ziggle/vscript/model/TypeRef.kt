package dev.ziggle.vscript.model

/**
 * What a pin, a variable or a signature entry is typed as — a NAME, not an ordinal.
 *
 * **Why this exists.** [PinType] is a closed enum, and closed is exactly right for what it describes: the
 * built-in kinds are the ones the editor draws a field for, the ones the pickers know how to search, and the
 * ones the VM has opcodes for. What it cannot describe is a type a *document* declares. An enum has no
 * constant for `Coordinate`, and every workaround — borrowing `INT`, adding a `STRUCT` constant, keeping a
 * name off to one side — ends with two type systems that have to agree and eventually will not.
 *
 * So a type reference is a name. Built-ins keep [PinType] in [builtin], because everything that draws or
 * compiles one still needs to know which kind it is; a declared type simply has none, and the code that
 * dispatches on the kind treats it the way it already treats a list or an entity — wire-only, no inline
 * editor. That is not a special case bolted on: it is the branch those two already take.
 *
 * **The name is the persisted form**, and a built-in's name is its enum constant name. That is what makes
 * the format change a rename of nothing: `"INT"` written by an older client reads back as `TypeRef(INT)`,
 * and a document that never mentions a declared type round-trips byte for byte.
 *
 * **A parameterized type carries its arguments.** [args] is where they live, and they are here rather than
 * beside the pin because a list's element type is part of what the pin IS — without it `canConnect` cannot
 * tell a list of items from a list of tiles, which it could not, and the gap was noted in
 * [PinSpec.elementType] for a long time before there was anywhere to put the answer.
 *
 * It was a single `of: TypeRef?` until phase D. A list is the only consumer TODAY, and [of] still answers
 * for it — but `MAP<K, V>` needs two, and a representation that holds one argument makes "should we have
 * maps" a question about this class rather than about the language. **The persisted form is unchanged for
 * every existing document**: one argument prints exactly as it always did.
 *
 * **[optional] is the other half of the same surgery.** A `T?` accepts null and a plain `T` does not, and
 * the whole type rule is one line in `canConnect` — `T` flows into `T?`, `T?` does not flow into `T`. The
 * runtime needs nothing: null is already a value, `Values.eq` already handles it and `Values.truth`
 * already treats it as false.
 */
class TypeRef private constructor(
    /** The built-in kind, or null when a document declared this type. */
    val builtin: PinType?,
    /** The persisted name. A built-in's is its [PinType] constant name. */
    val name: String,
    /** The type arguments: one for a `LIST`, two for a `MAP`, none for everything else. */
    val args: List<TypeRef> = emptyList(),
    /** `T?` — this may be null. See the class note. */
    val optional: Boolean = false,
    /**
     * `T` in `fn List<T>.first(self) -> T?` — a type VARIABLE, standing for whatever the receiver holds.
     *
     * **Marked, never recognised by name**, and that is not a stylistic choice. [named] turns any
     * unrecognised name into a *declared* type on purpose, so a bare `T` discovered later would be a
     * phantom record called `T` and the validator would report "typed 'T', which this graph does not
     * declare". Which names are variables is decided once, where the evidence is — at the receiver, which
     * is the binding site — and carried here. See `Generics.kt`.
     *
     * Behaviourally a variable is a wildcard until it is bound: [canConnect] lets anything through one,
     * which is exactly what an unresolved one has to do inside the function's own body, where nothing can
     * say what it is. At a CALL site it is substituted away before any pin is compared.
     */
    val variable: Boolean = false,
    /**
     * WHICH document declared this type — `Resolution.ref` on the text side, `key(graph)` on the graph
     * side. Null when nobody has decided: a built-in, a host type, a type variable, a ref built by hand in
     * a node library, or one read back from a persisted spelling before anything resolved it.
     *
     * **Identity, never spelling.** [name] is what a person reads and what gets persisted; this is which
     * declaration it IS. The two are different questions and one string cannot answer both — a document
     * that never imported `geo` still has to recognise a `Point` that came from there, which is exactly
     * what a spelling cannot do (`text/Resolver.kt:610`).
     *
     * A STRING rather than the declaration object, for three reasons and each is on its own sufficient:
     * `model` may not see `text`, where `RecordType` lives; a `TypeRef` outlives the resolution that made
     * it, going into cached `NodeDescriptor` pins, the manifest and `Chunk` constants, so holding a
     * declaration would pin every module's AST for the life of the process; and the two front ends
     * already agree on this string — `Imports.key(graph)` and `Resolution.ref` are both "how the document
     * was reached", and both compilers already key the chunk table on it.
     *
     * **Not in [equals].** See the note there.
     */
    val owner: String? = null,
) {
    /**
     * For a list, what it holds. Null when unconstrained, and on everything with no arguments.
     *
     * The single-argument reading of [args], kept because a list is what almost every caller means and
     * `t.of` says that far better than `t.args.firstOrNull()` does.
     */
    val of: TypeRef? get() = args.firstOrNull()

    val isExec: Boolean get() = builtin == PinType.EXEC
    val isWildcard: Boolean get() = builtin == PinType.WILDCARD
    val isList: Boolean get() = builtin == PinType.LIST
    val isMap: Boolean get() = builtin == PinType.MAP
    val isFunction: Boolean get() = builtin == PinType.FUNCTION

    /**
     * **There is no second kind of function.**
     *
     * There used to be: an ACTION — one with a body of steps — was a distinct name under [PinType.FUNCTION],
     * and [dev.ziggle.vscript.model.canConnect] refused one where a pure function was wanted, because
     * `mapped`/`filtered`/`firstWhere` are pure nodes that are re-expanded at every use site. Two spellings
     * for one idea was the price, and it was judged too high: `fn` is now every function, and whether a
     * function's BODY is a step is still derived — it decides how the function is CALLED — it just no
     * longer travels in the type.
     *
     * The consequence is stated rather than hidden: passing a step-bodied function to a pure list verb is
     * no longer refused, and its effects run once per read of that verb's result.
     */
    val hasNoResult: Boolean get() = isFunction && args.isNotEmpty() && returnsOf == null

    /**
     * A function type's result, or null when it hands nothing back — `act(HunterRumor)`.
     *
     * [resultOf] answers [NOTHING] there rather than null, because the invariant that keeps [paramsOf]
     * unambiguous is "the last argument is the result". This is the reading callers actually want.
     */
    val returnsOf: TypeRef? get() = resultOf?.takeIf { it !== NOTHING && it.name != NOTHING.name }

    /**
     * A function type's parameters, and what it hands back.
     *
     * Both read out of [args], with the RESULT LAST — one list rather than two fields, so every existing
     * walker over [args] (substitution, variable-marking, the printer's recursion) reaches a function's
     * parts with no new case. The cost is that `fn() -> INT` and an unconstrained `fn` are told apart by
     * whether [args] is empty at all, which is why a function type always carries its result.
     */
    val paramsOf: List<TypeRef> get() = if (args.isEmpty()) emptyList() else args.dropLast(1)
    val resultOf: TypeRef? get() = args.lastOrNull()

    /** A map's key type, and its value type. Null on anything unconstrained — see [of]. */
    val keyOf: TypeRef? get() = args.getOrNull(0)
    val valueOf: TypeRef? get() = args.getOrNull(1)

    /** This type, but accepting null. Idempotent. */
    fun orNull(): TypeRef = if (optional) this else TypeRef(builtin, name, args, true, variable, owner)

    /**
     * This type with its arguments replaced and everything else — the kind, the name, the `?` — kept.
     *
     * For rewriting what is INSIDE a type without having to know which kind it is: a list's element, a
     * map's key and value, a function's parameters and result are all just [args], and a caller that has to
     * ask "is it a list, is it a map, is it a function" will one day forget one. See
     * `dev.ziggle.vscript.model.qualifyThrough`, which forgot two.
     */
    fun withArgs(args: List<TypeRef>): TypeRef =
        if (args == this.args) this else TypeRef(builtin, name, args, optional, variable, owner)

    /** This type under a different name — the `?` and the arguments ride along. */
    fun renamedTo(name: String): TypeRef =
        if (name == this.name) this else TypeRef(builtin, name, args, optional, variable, owner)

    /**
     * This type with the `?` taken off — what a `if val` binding produces, and what an optional's value IS.
     *
     * The counterpart to [orNull], and the reason narrowing a WIRE is never needed: `flow.ifsome` makes a
     * NEW pin of this type rather than re-typing the one that fed it, which is the rule a graph model
     * forces (`docs/VSCRIPT_ERGONOMICS_PLAN.md` §2 constraint 4).
     */
    fun required(): TypeRef = if (!optional) this else TypeRef(builtin, name, args, false, variable, owner)

    /**
     * This name, read as a type VARIABLE rather than as a type nobody declared. See [variable].
     *
     * Only ever applied to an unqualified, argument-less, non-built-in name — everything else is a type
     * that exists, and marking one would silently make it accept anything.
     */
    fun asVariable(): TypeRef =
        if (variable || builtin != null) this else TypeRef(null, name, args, optional, variable = true, owner = null)

    /** True when a document declared this rather than the host — a struct, today. */
    val declared: Boolean get() = builtin == null

    /**
     * This type, attributed to the document that declared it. Idempotent; null clears it.
     *
     * Stamped ONCE, where the declaration is read, and carried unchanged thereafter — which is the whole
     * point. A type that is re-attributed on the way through an import is a type whose identity depends on
     * who is asking, and that is the defect this field exists to end.
     */
    fun ownedBy(owner: String?): TypeRef =
        if (owner == this.owner) this else TypeRef(builtin, name, args, optional, variable, owner)

    /**
     * The name with any module qualifier taken off — `Point` for `@1::Point`.
     *
     * The graph side stores an alias-qualified spelling and the text side a bare one, so a comparison that
     * has an [owner] on both sides must compare the BARE names or two spellings of one declaration would
     * still differ. See [sameDeclaredType].
     */
    val simpleName: String get() = QualName.parse(name).name

    /**
     * What this type IS, in prose — empty for a type a document declared.
     *
     * A pin's type is the only thing that can explain most pins: per-pin `doc` coverage is thin and always
     * will be, since most pins are named after what they hold. So a built-in answers from [PinType.doc].
     *
     * A DECLARED type answers from its own declaration instead, and it has to be asked there rather than
     * here: the prose is the doc comment above `type Point { … }` in whichever document declared it, and
     * this class deliberately knows nothing but the name — resolving a name to a document is the caller's
     * job precisely because the same name may be declared in more than one. Empty is the honest answer at
     * this level, not a missing one.
     */
    val doc: String get() = builtin?.doc.orEmpty()

    /**
     * Structural, and **deliberately blind to [owner]**.
     *
     * The tempting version folds the owner in and tolerates a null on either side, so that a hand-built or
     * host ref still matches a resolved one. That relation is **not transitive** — `Point@geo` equals a
     * bare `Point`, a bare `Point` equals `Point@bank`, and the two owned ones differ — and [withArgs]
     * compares `args` with `List.equals`, so the breakage would propagate through every nested type and
     * into any hash container.
     *
     * So identity lives in [sameDeclaredType], which is free to be intransitive because nothing hashes it,
     * and this stays the plain structural comparison it has always been. [builtin] is excluded for the
     * same reason it always was: `TypeRef.named("INT")` and `TypeRef(PinType.INT)` are one interned
     * instance, and the interning [named] does depends on them comparing equal.
     */
    override fun equals(other: Any?): Boolean =
        other is TypeRef && other.name == name && other.args == args &&
            other.optional == optional && other.variable == variable

    override fun hashCode(): Int =
        ((name.hashCode() * 31 + args.hashCode()) * 31 + optional.hashCode()) * 31 + variable.hashCode()

    /**
     * The persisted form: `INT`, `LIST<ITEM>`, `MAP<TILE, INT>`, `Coordinate?`. Round-trips through [parse].
     *
     * **A type variable prints as its bare name and reads back as a declared type**, which is deliberate:
     * `fn LIST<T>.first(self) -> T?` is exactly what the author wrote, and inventing a sigil for the
     * stored form would put a second spelling of one thing into every document. What [parse] cannot know
     * is re-derived where it can be — from the receiver, at the one place that introduces the name.
     */
    override fun toString(): String = buildString {
        if (isFunction) {
            // The one type whose written form is not `NAME<args>`, because `fn(TILE) -> BOOL` is what an
            // author reads as a function and `FUNCTION<TILE, BOOL>` is what a serializer reads as one. One
            // spelling, so the source form and the persisted form cannot drift: [parse] reads this back.
            // An OPTIONAL function with a result needs parentheses, or reading it back would give the
            // result the `?` — see [parse]. One with no result does not, and keeps the short spelling.
            val parens = optional && returnsOf != null
            if (parens) append('(')
            append("fn")
            if (args.isNotEmpty()) {
                paramsOf.joinTo(this, ", ", "(", ")")
                // One that hands nothing back writes no arrow at all — `fn(HunterRumor)`, which is how the
                // declaration reads too.
                returnsOf?.let { append(" -> ").append(it) }
            }
            if (parens) append(')')
            if (optional) append('?')
            return@buildString
        }
        append(name)
        if (args.isNotEmpty()) args.joinTo(this, ", ", "<", ">")
        if (optional) append('?')
    }

    companion object {
        /** Interned, so the overwhelmingly common case allocates nothing and compares by identity. */
        private val builtins = PinType.entries.associateWith { TypeRef(it, it.name) }

        /**
         * How a written name is matched: case-insensitive, and underscores ignored.
         *
         * `ITEM_REF` is the enum constant and `ItemRef` is what [Types] calls it — in the picker, in the
         * reference, in every error message. Matching only the constant made the friendly spelling a
         * DECLARED type, so `-> List<ItemRef>` reported "which this graph does not declare" and then
         * refused to wire `LIST<ITEM_REF>` into `LIST<ItemRef>` — two names for one type, disagreeing.
         */
        private fun key(name: String): String = name.trim().lowercase().replace("_", "")

        private val byName: Map<String, TypeRef> = buildMap {
            builtins.values.forEach { put(key(it.name), it) }
            // The two names [Types] shows that are not the constant's at all. A reader who writes what the
            // picker calls it has written the right thing, and this is where that becomes true.
            put(key("Any"), builtins.getValue(PinType.WILDCARD))
            put(key("Choice"), builtins.getValue(PinType.ENUM))
            // What an unconstrained function prints as, so `fn` round-trips.
            put(key("fn"), builtins.getValue(PinType.FUNCTION))
        }

        operator fun invoke(p: PinType): TypeRef = builtins.getValue(p)

        /** A list of [of], or an unconstrained list when null. */
        fun list(of: TypeRef?): TypeRef =
            if (of == null) invoke(PinType.LIST) else TypeRef(PinType.LIST, PinType.LIST.name, listOf(of))

        /**
         * `MAP<K, V>`, or an unconstrained map when either half is unknown.
         *
         * Both or neither: a half-known map would print as `MAP<TILE>`, which [parse] would read back as a
         * map with ONE argument — a shape nothing else in the system produces and every reader of
         * [valueOf] would then get null from. An unconstrained map is the honest answer and behaves the
         * way an unconstrained list already does.
         */
        fun map(key: TypeRef?, value: TypeRef?): TypeRef =
            if (key == null || value == null) invoke(PinType.MAP)
            else TypeRef(PinType.MAP, PinType.MAP.name, listOf(key, value))

        /**
         * [base] with type arguments — `MAP<TILE, INT>`, `LIST<ITEM>`.
         *
         * No arguments hands [base] straight back, so this never turns an interned built-in into a copy of
         * itself that compares equal but is not identical.
         */
        /**
         * `fn(A, B) -> R`.
         *
         * The result is required, and an absent one is spelled by passing WILDCARD rather than by leaving
         * [args] short — see [paramsOf] for why the list has to stay unambiguous.
         */
        fun function(params: List<TypeRef>, result: TypeRef): TypeRef =
            TypeRef(PinType.FUNCTION, PinType.FUNCTION.name, params + result)

        /**
         * The name an ACTION used to be carried under. **Read only** — see [hasNoResult].
         *
         * Kept so a graph saved before the two kinds merged still loads: its stored type strings say
         * `act(…)`, and [parse] answers a plain function for them. Nothing writes it.
         */
        const val ACTION = "ACTION"

        /**
         * An unconstrained function — "some function", of any shape.
         *
         * What Invoke's function pin is typed as, and the reason it accepts everything: an unconstrained
         * function type short-circuits [canConnect]'s arity comparison exactly as an unconstrained `LIST`
         * does, so the shape is checked where it is known — against the argument pins — rather than here.
         */
        fun action(): TypeRef = invoke(PinType.FUNCTION)

        /**
         * The result of a function that hands nothing back.
         *
         * A real type rather than a shorter [args] list, so "the last argument is the result" stays true
         * and every walker over [args] — substitution, variable-marking, the printer's recursion — reaches
         * a function's parts with no new case.
         *
         * **A marker, not a refusing type.** It is wildcard-backed, so it stays permissive everywhere
         * rather than producing errors nobody asked for; what reads it is [returnsOf], which answers null
         * and lets the Invoke node leave its Result pin off entirely. That is where "there is nothing to
         * read" is actually enforced — on the pin, not in `canConnect`.
         */
        val NOTHING: TypeRef = TypeRef(PinType.WILDCARD, "NOTHING")

        fun of(base: TypeRef, args: List<TypeRef>): TypeRef =
            if (args.isEmpty()) base
            else TypeRef(base.builtin, base.name, args, base.optional, base.variable, base.owner)

        /**
         * A type nobody has declared yet, by name.
         *
         * Deliberately NOT a failure. A reference is resolved against a document, and a document that names
         * a type it has since deleted has to still open — the validator is where that gets reported, the
         * same way an unknown node type or a call to a missing function is. Silently widening it to
         * [PinType.WILDCARD] instead, which is what reading through the enum used to do, is the one answer
         * that must not be given: a wildcard connects to anything, so the graph keeps compiling and quietly
         * stops meaning what it said.
         */
        fun named(name: String): TypeRef {
            val clean = name.trim()
            byName[key(clean)]?.let { return it }
            // **A HOST type is spelled canonically, the way a builtin is.**
            //
            // [key] ignores case and underscores, so `TILE`, `Tile` and `tile` were one builtin and every
            // document that wrote any of them meant the same type. A type that MOVES to a host lost that
            // for nothing: `TypeRef.named("TILE")` became a declared type called `TILE`, [equals] compares
            // names exactly, and the graph front end started reporting `cannot wire TILE into Tile` — a
            // message in which the two names are visibly the same word.
            //
            // Normalises the SPELLING only. The canonical name is taken and a nominal ref built from it,
            // rather than handing back the record's own [HostRecord.type]: that property is defined AS
            // `named(name)`, so returning it here would recurse, and a record that overrides it means to
            // override what the type IS, which is not this function's business.
            canonicalHostName(clean)?.let { if (it != clean) return TypeRef(null, it) }
            return TypeRef(null, clean)
        }

        /** What the host calls a type it registered under some spelling of [name], or null. */
        private fun canonicalHostName(name: String): String? =
            HostRecords.of(name)?.name ?: HostEnums.of(name)?.name

        /**
         * Read a persisted form. Unknown names come back declared, per [named].
         *
         * Two things this used to get wrong, both fixed in phase D and both silent:
         *
         * - **Arguments were DROPPED on anything that is not a list.** `MAP<Tile, Job>` parsed to a bare
         *   `MAP`, which is not a parse failure but a type quietly forgetting what it was.
         * - **There was no top-level comma split**, so a second argument would have been read as part of
         *   the first argument's NAME — a declared type called `Tile, Job`, which nothing declares and
         *   nothing would ever report.
         *
         * Neither could bite while `LIST` was the only parameterized type, which is exactly why they had
         * to be fixed before there was a second one.
         */
        fun parse(text: String?): TypeRef {
            var s = text?.trim().orEmpty()
            if (s.isEmpty()) return invoke(PinType.WILDCARD)
            // `(fn(INT) -> STRING)?` — an OPTIONAL FUNCTION, which needs the parentheses because a bare
            // trailing `?` is the RESULT'S: `fn(INT) -> STRING?` is a function that returns an optional
            // string, and that reading came first. Kotlin's answer to exactly the same ambiguity.
            //
            // A function value genuinely can be absent — a table of handlers with no row for this key
            // hands back nothing — so "there is no null function", which is what this used to say, was
            // wrong rather than merely restrictive.
            if (s.startsWith("(")) {
                var depth = 0
                var close = -1
                for (i in s.indices) {
                    when (s[i]) {
                        '(' -> depth++
                        ')' -> if (--depth == 0) { close = i; break }
                    }
                }
                if (close > 0) {
                    val inner = parse(s.substring(1, close))
                    return if (s.substring(close + 1).trim() == "?") inner.orNull() else inner
                }
            }
            // Before the `?` strip: the bare trailing `?` on a function shape is the RESULT'S, except on
            // `act(T)?`, which has no result to compete for it — see [parseFunction].
            parseFunction(s)?.let { return it }
            // The `?` binds outermost: `LIST<INT>?` is an optional list, never a list of optional ints.
            var opt = false
            while (s.endsWith("?")) {
                opt = true
                s = s.dropLast(1).trimEnd()
            }
            if (s.isEmpty()) return invoke(PinType.WILDCARD)
            val open = s.indexOf('<')
            val base = if (open > 0 && s.endsWith(">")) {
                val outer = named(s.substring(0, open))
                val args = split(s.substring(open + 1, s.length - 1)).map { parse(it) }
                // A list keeps going through `list`, so an unconstrained `LIST<>` stays the interned one.
                if (outer.isList) list(args.firstOrNull()) else of(outer, args)
            } else {
                named(s)
            }
            return if (opt) base.orNull() else base
        }

        /**
         * `fn(A, B) -> R`, or null when [s] is not one.
         *
         * Matched by SHAPE rather than by splitting on `->`, because a parameter may itself be a function
         * and `fn(fn(INT) -> INT) -> BOOL` has two arrows. The parenthesis that closes the parameter list
         * is the one at depth 0, and everything after it is the result.
         */
        private fun parseFunction(s: String): TypeRef? {
            // `act(…)` is accepted and never produced: a graph saved before the two kinds merged stores
            // its types as text, and those documents have to keep loading. It reads as a plain function.
            val legacy = s.startsWith("act")
            if (!legacy && !s.startsWith("fn")) return null
            val rest = s.substring(if (legacy) 3 else 2).trimStart()
            if (rest.isEmpty()) return invoke(PinType.FUNCTION)
            if (!rest.startsWith("(")) return null
            var depth = 0
            var close = -1
            for (i in rest.indices) {
                when (rest[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) { close = i; break }
                }
            }
            if (close < 0) return null
            val tail = rest.substring(close + 1).trimStart()
            val params = split(rest.substring(1, close)).map { parse(it) }
            // `fn(Rumor)` — no arrow means it hands nothing back.
            if (tail.isEmpty()) return function(params, NOTHING)
            // `fn(Rumor)?` — unambiguous without parentheses, because there is no result the mark could
            // have belonged to. With a result, `(fn(A) -> B)?` is the spelling.
            if (tail == "?") return function(params, NOTHING).orNull()
            if (!tail.startsWith("->")) return null
            return function(params, parse(tail.substring(2)))
        }

        /** Split on the commas at nesting depth 0 — `MAP<Tile, LIST<Job>>` is two arguments, not three. */
        private fun split(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { i, c ->
                when (c) {
                    '<', '(' -> depth++
                    ')' -> depth--
                    // Not the `>` of an arrow. `LIST<fn(INT) -> STRING>` used to leave depth at -1 here,
                    // after which a comma at the real top level no longer looked like one.
                    '>' -> if (i == 0 || text[i - 1] != '-') depth--
                    ',' -> if (depth == 0) { out += text.substring(start, i); start = i + 1 }
                }
            }
            out += text.substring(start)
            return out.map { it.trim() }.filter { it.isNotEmpty() }
        }

        val EXEC: TypeRef = invoke(PinType.EXEC)
        val WILDCARD: TypeRef = invoke(PinType.WILDCARD)
    }
}
