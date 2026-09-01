package dev.ziggle.vscript.model

import dev.ziggle.vscript.vm.HostKind

/**
 * The type of a pin.
 *
 * [EXEC] is the control-flow wire (Blueprints' white links); everything else carries data.
 *
 * **No game types.** `ITEM`, `NPC` and `OBJECT` used to be here, defended on the grounds that "an item id
 * and an npc id are both ints, and letting them cross-connect is exactly the mistake a visual editor
 * should refuse rather than discover at runtime". That is a good argument for *nominal types over INT*
 * and none at all for the LANGUAGE shipping three of one game's. A host record over the host's own value
 * refuses the cross-wiring identically, and the node pack declares them — so the same mistake is still
 * refused, by a type this file has never heard of.
 */
enum class PinType(
    /**
     * What a pin of this type carries, in prose — **the runtime-visible documentation**.
     *
     * A constructor argument rather than KDoc above each constant, and that is the whole point: KDoc is a
     * source comment, so an IDE hovering a pin could not read it. Coverage of per-pin `doc` is thin and
     * always will be — most pins are called `Item` and hold an item, and writing that 550 times is noise —
     * so the TYPE is what answers "what goes here" for the pins nobody usefully documents one by one.
     *
     * One text, visible in source and at runtime, so the two cannot drift.
     */
    val doc: String,
) {
    EXEC(
        "The control-flow wire — the order things happen in, not a value.\n\n" +
            "A node with an exec input does not run until something upstream hands it control. Data pins " +
            "are pulled when a node runs; exec pins are what makes it run at all. In text this is the " +
            "statement order you already wrote, so it has no spelling of its own."
    ),
    BOOL(
        "True or false. Written `true` / `false`.\n\n" +
            "An unwired Bool pin is FALSE rather than an error, because almost every one of them is an " +
            "option that defaults to off — `reachable`, `exact`, `fill`."
    ),
    INT(
        "A whole number. The language's one integer type, and it is Long-wide.\n\n" +
            "That width matters more than it sounds: `now()` returns milliseconds, which overflow a 32-bit " +
            "int by three orders of magnitude, so an Int that silently saturated would be a clock that " +
            "stopped. An INT widens to FLOAT on its own; going back is one of `floor` / `ceil` / `round` / " +
            "`toInt`, spelled out because rounding is a decision and picking one for you is how a rounding " +
            "bug gets written."
    ),
    FLOAT(
        "A real number, double-width. Written with a point: `1.5`, `0.0`.\n\n" +
            "A whole-number literal is accepted in a FLOAT slot and is STORED as a float — `4` in a float " +
            "field reads back `4.0`, not `4`. The reverse is refused: a float literal in an INT slot is an " +
            "error, since dropping the fraction is a decision the cast nodes exist to make out loud."
    ),
    STRING(
        "Text. Written in double quotes.\n\n" +
            "The game DECORATES some names it sends — content that colours its interactables ships them as " +
            "`\"<col=00ffff>Spirit pool</col>\"` — so a name read from the world will not always match the " +
            "one you typed. Where a node compares names it strips those tags first; where you compare them " +
            "yourself, be aware the raw text may carry them."
    ),
    LIST(
        "Several values of one type, in order. See [PinSpec.elementType] for which.\n\n" +
            "Scene lists come nearest-first, because a list of scene things is nearly always walked until " +
            "one works. An unconnected List pin reads as EMPTY rather than as an error — \"nothing plugged " +
            "in holds nothing\" is the answer a loop wants — so a ForEach over one simply does not run."
    ),
    FUNCTION(
        "A function, as a value — `fn(INT) -> INT` is \"something that turns one whole number into " +
            "another\".\n\n" +
            "Written as the NAME of a function with no call: `map(list: xs, f: double)` passes `double` " +
            "rather than calling it. What travels is an index into the compiled program, so a reference " +
            "to a named function costs what passing a number costs. A LAMBDA may also read the locals " +
            "around it, and those are COPIED in when the value is built — so a closure is a value, and " +
            "reassigning what it read cannot be seen through one already made.\n\n" +
            "The function must be an EXPRESSION (a `=` body), because it is called from inside another " +
            "expression and there is no exec chain there to run a step on."
    ),
    MAP(
        "A lookup from one value to another — `MAP<TILE, INT>` says how many times each tile was tried.\n\n" +
            "A VALUE, like a list and like a record: `with` hands back a new map and nothing writes through " +
            "one another wire is holding. The compiler turns the accumulator shape " +
            "`m = with(map: m, key: k, value: v)` into an in-place write where it can prove nobody else has " +
            "the old map, so the semantics are uniform and the cost is not paid. An unconnected Map pin " +
            "reads as EMPTY, for the same reason a List one does."
    ),
    ENUM(
        "One of a fixed set of names, carried as a String.\n\n" +
            "A String pin with a convention would compile identically and be wrong in the editor: the point " +
            "is that the canvas can offer the choices instead of asking you to remember them, and that a " +
            "typo is impossible rather than a runtime surprise. See [PinSpec.options]."
    ),
    WILDCARD(
        "Whatever it is connected to decides — the generic pin.\n\n" +
            "How one node serves every type: ForEach iterates a list of anything, and the arithmetic nodes " +
            "add two INTs, two FLOATs or two STRINGs. The type is resolved from the wire feeding it, so an " +
            "unconnected wildcard has no type at all and the editor will say so rather than guess."
    ),
    ;

    val isExec: Boolean get() = this == EXEC
}

/**
 * Are [a] and [b] the SAME declared type — one declaration, however each side happens to spell it?
 *
 * **The question a name cannot answer.** A type's spelling is relative to whoever is reading it: the graph
 * stores `@1::Point` and `@2::Point` for one `type Point` reached two ways, and the text side stores a bare
 * `Point` that two unrelated documents can both produce. Comparing spellings therefore fails in both
 * directions at once — it splits one type into two, and it merges two types into one. `TypeRef.owner` is
 * the declaration's identity, and this is the one place that reads it.
 *
 * The order of the arms is load-bearing:
 *
 *  - **A built-in wins on its kind.** A document may declare `type Item`, and [TypeRef.named] interns that
 *    straight onto [PinType.ITEM] — so it must keep wiring into every ITEM pin in the catalogue. Stamping
 *    an owner on it without this arm first would quietly stop that, and it would read as a type-checker
 *    regression rather than as this change.
 *  - **Two owners decide it**, compared on the BARE name, because the two front ends spell a qualified
 *    name differently and neither spelling is the identity.
 *  - **An unknown owner falls back to the spelling** — a host pin, a hand-built ref in a node library, one
 *    parsed back from a persisted name. Refusing those would break everything that has never been through
 *    a resolver, which is most of the catalogue.
 *
 * That last tolerance is why this is a function and not [TypeRef.equals]: "unknown matches anything" is not
 * transitive, and `equals` has to be. Nothing hashes this.
 */
fun sameDeclaredType(a: TypeRef, b: TypeRef): Boolean = when {
    a.builtin != null || b.builtin != null -> a.builtin == b.builtin || a.name == b.name
    a.owner != null && b.owner != null -> a.owner == b.owner && a.simpleName == b.simpleName
    else -> a.name == b.name
}

/**
 * Whether [from] may be wired into [to].
 *
 * Exec only connects to exec. [PinType.WILDCARD] accepts anything (the editor resolves it once connected).
 *
 * **[PinType.INT] widens to [PinType.FLOAT], and the value really is converted.** Widening used to be
 * permitted here and nowhere else: nothing changed the value on the way through, so a FLOAT-typed field
 * fed by an INT held an `Int` for the whole run — `"" + vec.x` printed `4` where a float had been
 * declared, and the type was a claim nothing enforced. The permission is the same; what is new is
 * [Op.TOF], which the compiler emits on exactly this boundary. Convenience without the lie.
 *
 * Narrowing is still refused, because it loses data and which way to round is a decision:
 * [BuiltinNodes.FLOOR] and its siblings are how it gets made out loud.
 *
 * A whole-number LITERAL typed into a float pin is a third thing again — no wire, so no conversion
 * instruction. `Lower.accepts` allows it and `GraphCompiler.asDeclared` stores it as `4.0`.
 */
private val WILD = TypeRef(PinType.WILDCARD)

fun canConnect(from: TypeRef, to: TypeRef): Boolean = when {
    from.isExec || to.isExec -> from.isExec && to.isExec
    from.isWildcard || to.isWildcard -> true
    // An UNBOUND type variable stands for whatever the receiver holds, and inside the function's own body
    // there is nothing that could say what that is — so it accepts, exactly as a wildcard does. At a call
    // site it is never seen: `resolveNode` binds it from what is wired into `self` and substitutes it away
    // before any pin is compared. Above the optional rule for the same reason the wildcard is: a variable
    // is "not decided yet", not a claim that a value is present.
    from.variable || to.variable -> true
    // **The whole optional rule.** `T` flows into `T?`; `T?` does not flow into `T`. Everything Tier 1
    // buys — the eight fake `(found: Bool, tile: Tile)` returns, the thirteen `Have*` flags, the
    // `tile(0, 0, 0)` sentinel — is downstream of these two lines.
    //
    // Deliberately BELOW the wildcard branch: a wildcard is "whatever is connected decides", not a claim
    // that a value is present, and most host pins are wildcards. Refusing an optional there would mean an
    // optional could not be logged.
    from.optional && !to.optional -> false
    from.optional || to.optional -> canConnect(from.required(), to.required())
    // A list of items is not a list of tiles. This used to compare only the outer kind, so it was not —
    // and the mis-wiring survived into a run, where the host casting the element is where it finally went
    // wrong, a long way from the wire that caused it. An UNCONSTRAINED list still accepts and is accepted
    // by any list: that is what a `ForEach` over a wire whose element type nobody wrote down needs.
    from.isList && to.isList -> {
        val f = from.of
        val t = to.of
        f == null || t == null || canConnect(f, t)
    }
    // The same rule with two arguments instead of one. An unconstrained map accepts and is accepted by any
    // map, which is what a `MAP` pin on a node that does not care about the element types needs.
    // Parameters are compared the OTHER WAY ROUND, which is not a typo. A function that accepts anything
    // can stand in wherever one that accepts tiles was wanted — it will cope with the tile. One that
    // accepts only tiles cannot stand in where anything might arrive. The result compares the usual way.
    // An unconstrained `fn` accepts and is accepted by any function, as the containers do.
    from.isFunction && to.isFunction ->
        // **There is no kind check here any more.** A function used to carry whether its body was a step,
        // and a pin wanting a pure one refused an acting one — because `mapped`/`filtered`/`firstWhere`
        // are pure nodes, re-expanded at every use site, so an action reaching one runs its effects once
        // per read of the result. That was a real hazard and it is still real; what changed is the price
        // that was being paid for it, which was two spellings of "function" in every signature. `fn` is
        // now every function, and the hazard is a thing to know rather than a thing the wire refuses.
        (
                from.args.isEmpty() || to.args.isEmpty() ||
                    (
                        from.args.size == to.args.size &&
                            canConnect(from.resultOf ?: WILD, to.resultOf ?: WILD) &&
                            to.paramsOf.indices.all { i -> canConnect(to.paramsOf[i], from.paramsOf[i]) }
                        )
                )
    from.isMap && to.isMap ->
        from.args.isEmpty() || to.args.isEmpty() ||
            from.args.indices.all { i ->
                val t = to.args.getOrNull(i) ?: return@all true
                canConnect(from.args[i], t)
            }
    // The same rule once more, for a type a DOCUMENT declared — `Pair<INT, STRING>`. The three branches
    // above are the built-in containers; without this one a generic record compared by identity alone, so
    // `Pair<INT, WILDCARD>` did not fit `Pair<INT, STRING>` and a field whose value could not be typed
    // (a string literal is deliberately untyped — it is also how a tile and a skill are stored) made the
    // whole record unwireable. An unconstrained `Pair` accepts and is accepted by any `Pair`, as the
    // containers do.
    from.name == to.name && (from.args.isNotEmpty() || to.args.isNotEmpty()) ->
        from.args.isEmpty() || to.args.isEmpty() ||
            (from.args.size == to.args.size &&
                from.args.indices.all { canConnect(from.args[it], to.args[it]) })
    from == to -> true
    from.builtin == PinType.INT && to.builtin == PinType.FLOAT -> widens(from, to)
    else -> false
}

/**
 * How a value of this type is actually STORED — which is a coarser question than what it means.
 *
 * `literalTypeOf`'s note already states the facts this collects: a Kotlin `String` is how the language
 * holds a STRING and also a TILE, a SKILL and an ENUM member; an `Int` holds an INT and also an ITEM, an
 * NPC, an OBJECT and a COLOR. Reading either back as one specific type is what that function refuses to do.
 *
 * Null for the types where the question does not apply — containers, functions, exec, and the wildcard,
 * whose whole meaning is "not decided".
 *
 * Used by the one caller that needs to compare two types WITHOUT deciding they are the same: a list
 * literal's guessed element type against the element type of the pin it is being handed to. See
 * `Lower.retypeList`.
 */
fun storageOf(t: PinType): String? = when (t) {
    PinType.INT -> "number"
    PinType.STRING, PinType.ENUM -> "text"
    PinType.FLOAT -> "float"
    PinType.BOOL -> "bool"
    else -> null
}

/**
 * Does a value crossing from [from] to [to] have to be CONVERTED on the way?
 *
 * The compiler's half of the widening rule, kept beside [canConnect] rather than in `GraphCompiler` so the
 * permission and the obligation are one edit apart. A rule allowed in one place and converted in another
 * is how the widening came to be a lie in the first place.
 */
fun widens(from: TypeRef, to: TypeRef): Boolean =
    !from.isList && !to.isList && from.builtin == PinType.INT && to.builtin == PinType.FLOAT

/**
 * The conversion node that would make [from] fit [to], for a diagnostic to suggest.
 *
 * Only narrowing reaches here now that INT widens on its own — and narrowing is precisely the case where
 * naming the cure matters, because there are four of them and the refusal exists to make the author pick.
 */
fun conversionFor(from: TypeRef, to: TypeRef): String? = when {
    from.isList || to.isList || from.isWildcard || to.isWildcard -> null
    from.builtin == PinType.FLOAT && to.builtin == PinType.INT -> "round, floor, ceil or toInt"
    else -> null
}

/**
 * The type a pin actually carries on THIS node, as against what the catalog declares.
 *
 * Only variable nodes differ, and they have to: `Get`/`Set` are declared with a [PinType.WILDCARD] value
 * pin because one node type serves every variable, and a wildcard connects to anything. Left at that, a
 * variable is untyped in practice — you can declare `counter` a boolean, wire it into arithmetic, and
 * nothing anywhere objects, which is exactly the mistake the pin types exist to catch.
 *
 * Resolved through a lookup rather than off the node, because the same question is asked by the validator
 * (which has a [Graph]) and by the canvas (which has an editor document), and neither should have to know
 * about the other.
 */
fun effectivePinType(node: Node, spec: PinSpec, variableType: (String) -> TypeRef?): TypeRef =
    effectivePinType(node, spec, variableType) { _, _ -> null }

/**
 * The same question, for a caller that can also say what feeds a pin.
 *
 * A second overload rather than a defaulted parameter, because most call sites pass [variableType] as a
 * trailing lambda — and a defaulted parameter added after it silently steals that lambda, leaving
 * [variableType] unfilled. Two overloads keep every existing call site meaning exactly what it did.
 */
fun effectivePinType(
    node: Node,
    spec: PinSpec,
    variableType: (String) -> TypeRef?,
    /**
     * The type carried by the wire feeding a node's input pin, for a node whose type comes from what it was
     * given rather than from a declaration. Answering null is the old behaviour exactly: the wildcard stands.
     */
    feeding: (Int, String) -> TypeRef?,
): TypeRef {
    if (!spec.type.isWildcard) return spec.type
    // A Hold's wildcard is a placeholder for "whatever this was handed", not a claim that anything goes.
    // Left unresolved it is the silent widening [TypeRef.named] warns about: a wildcard connects to
    // everything, so a record that had been through a `let` stopped being a record and the graph went on
    // compiling while quietly meaning less. Both its pins are called Value — the one in and the one out —
    // and both carry the same thing, which is the whole point of the node.
    if (node.type == BuiltinNodes.HOLD) {
        if (spec.name != "Value") return spec.type
        // What the author WROTE beats what happened to feed it.
        // `parse`, not `named`: a declared type is stored as it was WRITTEN, so `LIST<TILE>` and `TILE?`
        // both arrive here whole. `named` would take either for the name of a type nobody declares.
        (node.literals[BuiltinNodes.HOLD_TYPE] as? String)?.takeIf { it.isNotBlank() }
            ?.let { return TypeRef.parse(it) }
        // What feeds it, or — when nothing does — what was typed into it. A Hold fed by a bare literal had
        // no wire to follow, so it stayed WILDCARD and every read of it was unchecked.
        feeding(node.id, "Value")?.let { return it }
        // Then what the SOURCE said the initialiser was, for the values whose stored form cannot say —
        // a tile, a skill, a plain string. See [BuiltinNodes.HOLD_INFERRED].
        (node.literals[BuiltinNodes.HOLD_INFERRED] as? String)?.takeIf { it.isNotBlank() }
            ?.let { return TypeRef.parse(it) }
        return literalTypeOf(node.literals["Value"]) ?: spec.type
    }
    // A folded `val` that was WRITTEN with a type says so — see [Lower.CONST_TYPE]. Without this the
    // annotation stopped at the fold: the literal reports WILDCARD, so `Impl.ready` is a field read on a
    // value whose shape nothing knows, and it is refused with "this has no field". That is the whole
    // reason an annotated `val` used to be denied the fold rather than carried through it, and the denial
    // is what made `val Impl: Hooks = …` unusable everywhere a constant is allowed.
    //
    // `parse`, not `named`: a declared type is stored as it was WRITTEN, so `LIST<TILE>` and `TILE?` both
    // arrive here whole — the same rule, and the same comment, as the Hold above.
    if (node.type in BuiltinNodes.LITERALS && spec.name == "Value") {
        (node.literals[BuiltinNodes.CONST_TYPE] as? String)?.takeIf { it.isNotBlank() }
            ?.let { return TypeRef.parse(it) }
    }
    // A cast's Value pin carries whatever it is reading — the record the fields come out of. Declared
    // WILDCARD because it accepts any record, so without this the type stopped at the pin and every
    // caller had to rediscover it.
    if (node.type == BuiltinNodes.CAST && spec.name == "Value") {
        return feeding(node.id, "Value") ?: spec.type
    }
    // Arithmetic carries its OPERANDS' type. The pins are WILDCARD because one node type serves INT,
    // FLOAT and — for Add — string concatenation, and a wildcard connects to anything: `var f: FLOAT =
    // a / b` on two INTs was accepted and stored an Int, which is the one hole removing the implicit
    // widening did not close, because there was no declared type on either side to notice.
    //
    // Promotion is the VM's own rule (`Values.arith`) read a step earlier: a String anywhere makes it
    // concatenation, then a Double wins over an Int. An operand whose type cannot be established leaves
    // the answer WILDCARD — permissive, and the same as before — because guessing INT for something
    // unknown would refuse correct code on no evidence.
    if (node.type in BuiltinNodes.ARITHMETIC && spec.name == "Result") {
        val a = operandType(node, "A", feeding)
        val b = operandType(node, "B", feeding)
        return promote(node.type, a, b) ?: spec.type
    }
    // A ForEach's Element carries the ELEMENT TYPE of the list feeding it. Declared WILDCARD because one
    // node iterates every kind of list, and without this rule the wildcard simply stood: `for t in tiles`
    // gave `t` no type at all, so `t.x` reported "this has no field 'x'" on a list whose element type the
    // document had declared right there.
    //
    // That is the same silent widening the Hold case above exists to stop, one construct along — a record
    // that goes through a loop stops being a record. The workaround was to pass the element straight into
    // a typed function and read the fields there, which works and is not a language anybody should have to
    // learn: it makes `for` the one binding form whose variable has no type.
    //
    // `.of` and not the list type itself: `LIST<TILE>` iterates TILEs. Null when the feeding wire says
    // nothing, or says only `LIST` with no element — the wildcard then stands exactly as before, which
    // keeps every untyped list working.
    if (node.type == BuiltinNodes.FOR_EACH && spec.name == "Element") {
        return feeding(node.id, "List")?.of ?: spec.type
    }
    // `xs[i]` hands back an ELEMENT, so it carries the list's element type — the same rule as the loop
    // above, on the other way of getting one item out. Without it `entities[0].name` was unchecked on a
    // `LIST<ENTITY>`: the wildcard connects to anything, so the read compiled and meant nothing.
    //
    // **Not optional, and that is what separates this from [BuiltinNodes.LIST_FIRST].** `at` stops the
    // script when there is no such position — it is the spelling for "I know it is there" — so the answer
    // is a `T`. `first` says "or nothing when the list is empty" in its own summary, so under strict null
    // it wants a `T?`, and that cascades through every caller. They are two nodes precisely because they
    // answer different questions, and this is where the difference finally shows in the types.
    if (node.type == BuiltinNodes.LIST_AT && spec.name == "Item") {
        return feeding(node.id, "List")?.of ?: spec.type
    }
    // ...and `first` is the same element, OPTIONAL — which is the whole difference between the two nodes
    // finally showing in the types. Its own summary has always said "or nothing when the list is empty",
    // and the pin said WILDCARD, and a wildcard connects to anything: `fn LIST<ENTITY>.closest(self) ->
    // ENTITY` built on it therefore handed back NULL through a type that says it cannot, and the crash
    // landed three frames away in whatever read the field.
    //
    // `smallest` and `largest` say the same sentence about themselves and get the same treatment.
    if (node.type in BuiltinNodes.EMPTY_HANDED && spec.name in setOf("Item", "Value")) {
        return feeding(node.id, "List")?.of?.orNull() ?: spec.type
    }
    // `a ?: b` hands back what `a` holds with the `?` taken off — a `Tile?` in, a `Tile` out. **That is
    // the whole feature**: narrowing lives on a NEW pin, because a graph cannot re-type the wire that fed
    // it (the wire into a branch is the same wire as the one outside it). The fallback is the answer when
    // the value is absent, so it decides nothing about the type when it is present.
    if (node.type == BuiltinNodes.OR_ELSE && spec.name == "Result") {
        val v = feeding(node.id, BuiltinNodes.OR_ELSE_VALUE)?.required()
        // A fallback of the same shape says nothing new; a wildcard value falls back to the fallback's
        // type, which is what makes `something() ?: 43` an INT rather than an unknown.
        if (v != null && !v.isWildcard) return v
        return feeding(node.id, BuiltinNodes.OR_ELSE_FALLBACK)?.required() ?: spec.type
    }
    // The value an `if val` binds is the option with its `?` removed — the same rule, on the branch form.
    // The proved value: whatever fed it, with the `?` taken off. Only the OUTPUT — the input has to go on
    // accepting an optional, since an optional is the only thing anyone narrows.
    if (node.type == BuiltinNodes.NARROW && spec.name == BuiltinNodes.NARROW_OUT) {
        return feeding(node.id, "Value")?.required() ?: spec.type
    }
    if (node.type == BuiltinNodes.IF_SOME && spec.name == BuiltinNodes.IF_SOME_VALUE) {
        return feeding(node.id, BuiltinNodes.IF_SOME_OPTION)?.required() ?: spec.type
    }
    if (node.type == BuiltinNodes.IF_PRESENT) {
        // `It` is the receiver, known to be present — so `a?.x` can read a field off a `Spot?` at all.
        if (spec.name == BuiltinNodes.IF_PRESENT_IT) {
            return feeding(node.id, BuiltinNodes.IF_PRESENT_VALUE)?.required() ?: spec.type
        }
        // And the ANSWER is optional again, because the whole access did not happen when the receiver was
        // absent. That is what makes `a?.b.c` a reported mistake and `a?.b?.c` the way to write it.
        if (spec.name == "Result") {
            return feeding(node.id, BuiltinNodes.IF_PRESENT_THEN)?.orNull() ?: spec.type
        }
    }
    // A local's assignment carries whatever the local holds — asked of the HOLD it targets, which is what
    // makes `var n = 0` then `n = "two"` a reported mistake rather than a register quietly changing kind.
    if (node.type == BuiltinNodes.LOCAL_SET && spec.name == "Value") {
        // The local's DECLARED type, copied here at lowering — `feeding` would follow the wire into the
        // Hold and report what initialised it instead, which is an INT `0` for a `: FLOAT` local.
        // `parse`, not `named`: a declared type is stored as it was WRITTEN, so `LIST<TILE>` and `TILE?`
        // both arrive here whole. `named` would take either for the name of a type nobody declares.
        (node.literals[BuiltinNodes.HOLD_TYPE] as? String)?.takeIf { it.isNotBlank() }
            ?.let { return TypeRef.parse(it) }
        val target = (node.literals[BuiltinNodes.LOCAL_TARGET] as? Number)?.toInt() ?: return spec.type
        return feeding(target, "Value") ?: spec.type
    }
    if (node.type != BuiltinNodes.VAR_GET && node.type != BuiltinNodes.VAR_SET) return spec.type
    if (spec.name != "Value") return spec.type
    val name = node.variable ?: return spec.type
    return variableType(name) ?: spec.type
}

/** What feeds one side of an arithmetic node — a wire's type, else what was typed in. */
private fun operandType(node: Node, pin: String, feeding: (Int, String) -> TypeRef?): TypeRef? =
    feeding(node.id, pin) ?: literalTypeOf(node.literals[pin])

/**
 * The type an arithmetic node produces, or null when its operands do not say.
 *
 * `Values.arith`'s rule, one step earlier: a String on either side of an Add is concatenation, then a
 * Double wins over an Int. Anything else — a wildcard operand, a record, a list — answers null and leaves
 * the result as permissive as it was.
 */
private fun promote(type: String, a: TypeRef?, b: TypeRef?): TypeRef? {
    if (a == null || b == null) return null
    if (a.isList || b.isList || a.declared || b.declared) return null
    val ka = a.builtin
    val kb = b.builtin
    if (type == BuiltinNodes.ADD && (ka == PinType.STRING || kb == PinType.STRING)) {
        return TypeRef(PinType.STRING)
    }
    // **An operand that does not say leaves the answer unsaid**, which is what the note above promises and
    // what the two lines below quietly broke: a WILDCARD is "not established", not "some other number".
    //
    // The case that found it is a `+` chain with a float in it. `"x" + 3 + 0.5` nests, and the inner Add's
    // result is WILDCARD — a string literal is deliberately untyped, because a String is also how a TILE, a
    // SKILL and an ENUM are stored (see [literalTypeOf]). The outer Add then saw WILDCARD beside FLOAT,
    // took the FLOAT branch, and the whole expression was refused into a string. Every INT case worked,
    // which is why no script ever tripped it: it looked exactly like "this language cannot concatenate a
    // float".
    //
    // Answering null here leaves the wildcard standing, the wire is accepted, and the VM's own `Values.arith`
    // does what it always did — a String on either side is concatenation.
    if (ka == PinType.WILDCARD || kb == PinType.WILDCARD) return null
    if (ka == PinType.FLOAT || kb == PinType.FLOAT) return TypeRef(PinType.FLOAT)
    if (ka == PinType.INT && kb == PinType.INT) return TypeRef(PinType.INT)
    return null
}

/**
 * The type a typed-in value carries, or null when its kind does not decide one.
 *
 * **A representation is not always a type**, and which cases that costs was measured rather than guessed.
 * A Kotlin `String` is how this language stores a STRING — and also a TILE (`"3200,3200,0"`), a SKILL and
 * an ENUM. Reading one as STRING breaks real code: `let spot = tile(3200, 3200, 0)` wired into a Walk To
 * is correct and would be refused, which is one of the fourteen scripts in the client's folder today. So a
 * String answers null and stays a wildcard, exactly as it did before anything was inferred.
 *
 * An `Int` is likewise an INT and also an ITEM, an NPC, an OBJECT and a COLOR — but nothing in the corpus
 * binds an id to a name before using it, and typing an INT is what closes the gap this exists for (`var
 * n = 0` then `n = "two"`). If `let id = 995` into an item pin ever does turn up, the answer is a widening
 * rule for the id types, not a retreat to wildcards.
 *
 * A list stays null too: the kind says LIST but not of what, and a half-answer here is worse than none —
 * `canConnect` compares element types.
 */
fun literalTypeOf(value: Any?): TypeRef? = when (value) {
    is Boolean -> TypeRef(PinType.BOOL)
    is Double, is Float -> TypeRef(PinType.FLOAT)
    is Int, is Long -> TypeRef(PinType.INT)
    else -> null
}

/**
 * The descriptor to use for THIS node, with any signature-shaped pins filled in.
 *
 * The catalog maps a type to a fixed set of pins, which is right for every node whose shape is decided when
 * it is written. A user function's is decided by its author instead, so a Call node's pins are a property of
 * the *document*, not of the catalog — and every consumer that reaches for `catalog[node.type]` needs to come
 * through here or it will draw, validate or compile the wrong pins.
 *
 * A node whose function is unknown (not yet named, or naming one that has been deleted) keeps its bare exec
 * pins rather than throwing: an unfinished graph should be something you can still look at, and the validator
 * is where it gets reported.
 */
fun resolveNode(
    node: Node,
    desc: NodeDescriptor,
    function: (String) -> GraphFunction?,
    /** What this DOCUMENT declares. A list rather than a lookup: the naming pin offers them all. */
    types: () -> List<StructType> = { emptyList() },
    /** Whether THIS node is an expression rather than a step — see [expressionCalls]. */
    pure: (Node, GraphFunction) -> Boolean = { _, _ -> false },
    /**
     * The enums this DOCUMENT declares — see [EnumType].
     *
     * Last, and after [pure], purely so adding it broke no caller: several pass [pure] positionally, and a
     * parameter inserted in the middle would have silently become the purity predicate.
     */
    enums: () -> List<EnumType> = { emptyList() },
    /**
     * The type carried by the wire feeding one of THIS node's input pins — the same question
     * [effectivePinType] asks, and asked for the same reason.
     *
     * **Only `self` is read, and only for a generic call.** A `fn List<T>.first(self) -> T?` called on a
     * `List<Entity>` has to hand back an `Entity?`, and the only evidence for that is what is wired into
     * the receiver. Answering null is the old behaviour exactly: the variables stand, they behave as
     * wildcards (`canConnect`), and the call is as permissive as it was before generics existed — which is
     * what lets a caller that cannot answer simply not pass this.
     *
     * Last, and defaulted, so no existing call site moves. Several pass the parameters above positionally
     * and one inserted in the middle would silently become the wrong lambda — the same trap the note on
     * [enums] records.
     */
    feeding: (Int, String) -> TypeRef? = { _, _ -> null },
): NodeDescriptor {
    if (desc.type !in BuiltinNodes.SIGNATURE_SHAPED) return desc
    // A template's pins come from its own text, so it needs no lookup at all.
    if (desc.type == BuiltinNodes.FORMAT) {
        val template = node.literals["Template"]?.toString()
            ?: desc.input("Template")?.default?.toString().orEmpty()
        val holes = Templates.placeholders(template).map { PinSpec(it, PinType.WILDCARD) }
        return if (holes.isEmpty()) desc else desc.withPins(inputs = desc.inputs + holes)
    }
    // A list's pins come from how many items it was asked for, and what kind. Each slot being a REAL PIN
    // is the whole point: it gets that type's own field or picker for free, and it can be wired instead
    // of typed — neither of which a bespoke list widget would have given without reimplementing both.
    if (desc.type == BuiltinNodes.LITERAL_LIST) {
        val of = BuiltinNodes.listElementType(node.literals[BuiltinNodes.LIST_OF]?.toString())
        val count = (node.literals[BuiltinNodes.LIST_COUNT] as? Number)?.toInt() ?: 0
        val slots = (1..count.coerceIn(0, BuiltinNodes.LIST_MAX)).map { PinSpec(it.toString(), of) }
        return desc.withPins(
            inputs = desc.inputs + slots,
            // The output carries the element type so what the node produces is legible — on the pin's
            // tooltip, and to anything downstream that asks — [canConnect] now compares element types, so
            // a list of items no longer wires into a list-of-tiles pin.
            outputs = listOf(PinSpec("Value", TypeRef.list(of))),
        )
    }
    // A map node's pins come from the MAP wired into it — the same idea as a ForEach's Element, but it has
    // to live here rather than in `effectivePinType` because most of these pins are not wildcards.
    // `effectivePinType` opens with `if (!spec.type.isWildcard) return spec.type`, so a rule for a `LIST`
    // or `MAP` typed pin could never fire there; here the pins are being BUILT, which is the only place
    // that reaches both sides. Trap 11, the same one Level 1's substitution ran into.
    //
    // [BuiltinNodes.MAP_OF] is deliberately NOT in this set: nothing is wired into it, and its type comes
    // from the destination — an unconstrained `MAP` connects to any map, exactly as an unconstrained
    // `LIST` does, so `var seen: MAP<TILE, INT> = emptyMap()` needs no retyping at all.
    // A function reference's output type IS the named function's signature, read straight off it. Nothing
    // is wired into this node, so it is shaped from its own literal — the LITERAL_LIST case, not the
    // MAP_SHAPED one.
    if (desc.type == BuiltinNodes.FUNCTION_REF) {
        val fn = node.literals[BuiltinNodes.FUNCTION_REF_NAME]?.toString()?.let(function)
        // A lambda's captures are trailing parameters of the synthesised function, and they are NOT part
        // of the value's type: the caller supplies the item, the closure supplies the rest. Counting them
        // off is what keeps `{ it > n }` a `fn(INT) -> BOOL` rather than a two-argument function that
        // nothing would accept.
        val captures = BuiltinNodes.capturesOf(node)
        val visible = fn?.params.orEmpty().let { if (captures.isEmpty()) it else it.dropLast(captures.size) }
        val t = if (fn == null) TypeRef(PinType.FUNCTION) else TypeRef.function(
            // `self` included, and on purpose: a reference to `fn LIST<T>.first(self)` is a function of one
            // argument, and hiding the receiver would make the value's type disagree with what calling it
            // costs. The extension spelling is sugar at the CALL site; a reference is not a call.
            visible.map { it.type },
            // One result. A function that hands back several is not a value you can put on a wire, because
            // a wire carries one thing — the validator says so rather than this silently taking the first.
            // NONE is a result too: one that hands nothing back is `fn(T)`, and saying that in the type
            // is what stops `let x = …` binding a wildcard to something that never produced one.
            fn.results.firstOrNull()?.type ?: TypeRef.NOTHING,
        )
        // One input per captured name, typed from the parameter it feeds — so the canvas draws the wire
        // from the local into the lambda, and the reader can see what it closed over.
        val closed = captures.mapIndexed { i, name ->
            PinSpec(name, fn?.params?.getOrNull(visible.size + i)?.type ?: TypeRef(PinType.WILDCARD))
        }
        return desc.withPins(inputs = closed, outputs = listOf(PinSpec("Value", t)))
    }
    // Invoke: how MANY arguments comes from the literal, what TYPE each is comes from the function wired
    // in. Both are needed — the count so the pins exist before anything is connected (a graph is built one
    // wire at a time, and a pin that only appears once the function is attached could never be attached
    // to), the types so the call is checked once it is.
    if (desc.type == BuiltinNodes.INVOKE) {
        val f = feeding(node.id, BuiltinNodes.INVOKE_FN)?.takeIf { it.isFunction }
        val params = f?.paramsOf.orEmpty()
        val n = BuiltinNodes.invokeArgs(node)
        val args = (1..n).map { i ->
            PinSpec(BuiltinNodes.invokeArg(i), params.getOrNull(i - 1) ?: TypeRef(PinType.WILDCARD))
        }
        // No Result pin at all when the function is known to hand nothing back. A pin typed "nothing" that
        // still accepts a wire would be the honest-looking version of the same mistake.
        val result =
            if (f != null && f.args.isNotEmpty() && f.returnsOf == null) emptyList()
            else listOf(PinSpec("Result", f?.returnsOf ?: TypeRef(PinType.WILDCARD)))
        return desc.withPins(
            inputs = desc.inputs.filter { it.type.isExec || it.name == BuiltinNodes.INVOKE_FN } + args,
            outputs = desc.outputs.filter { it.type.isExec } + result,
        )
    }
    // The higher-order list nodes. Element type comes from the list, the function pin's PARAMETER comes
    // from that element, and the result comes back off whatever function was actually wired in — so
    // `mapped(list: tiles, using: describe)` is a `LIST<STRING>` with nothing written down anywhere.
    if (desc.type in BuiltinNodes.FN_SHAPED) {
        val pin = BuiltinNodes.FN_PIN.getValue(desc.type)
        val elem = feeding(node.id, "List")?.takeIf { it.isList }?.of
        val f = feeding(node.id, pin)?.takeIf { it.isFunction }
        val listPin = PinSpec("List", TypeRef.list(elem))
        // The DESTINATION's result is a wildcard for map — anything may be mapped to anything — and BOOL
        // for the two that ask a question. The parameter is the element either way, and `canConnect`
        // compares parameters contravariantly, so a `fn(Any) -> …` still fits a list of tiles.
        fun taking(result: TypeRef) =
            PinSpec(pin, TypeRef.function(listOf(elem ?: WILD), result))
        return when (desc.type) {
            BuiltinNodes.LIST_MAP -> desc.withPins(
                inputs = listOf(listPin, taking(WILD)),
                outputs = listOf(PinSpec("Result", TypeRef.list(f?.resultOf))),
            )
            BuiltinNodes.LIST_FILTER -> desc.withPins(
                inputs = listOf(listPin, taking(TypeRef(PinType.BOOL))),
                outputs = listOf(PinSpec("Result", TypeRef.list(elem))),
            )
            // Optional, like every other "find me one" on a list: there may be no match, and saying so in
            // the type is what stops the caller from having to invent a sentinel.
            else -> desc.withPins(
                inputs = listOf(listPin, taking(TypeRef(PinType.BOOL))),
                outputs = listOf(PinSpec("Item", (elem ?: WILD).orNull())),
            )
        }
    }
    if (desc.type in BuiltinNodes.MAP_SHAPED) {
        val m = feeding(node.id, BuiltinNodes.MAP_PIN)?.takeIf { it.isMap }
        val k = m?.keyOf
        val v = m?.valueOf
        val mapPin = PinSpec(BuiltinNodes.MAP_PIN, m ?: TypeRef(PinType.MAP))
        val keyPin = PinSpec(BuiltinNodes.MAP_KEY_PIN, k ?: TypeRef(PinType.WILDCARD))
        return when (desc.type) {
            BuiltinNodes.MAP_WITH -> desc.withPins(
                inputs = listOf(mapPin, keyPin, PinSpec(BuiltinNodes.MAP_VALUE_PIN, v ?: TypeRef(PinType.WILDCARD))),
                outputs = listOf(PinSpec("Result", m ?: TypeRef(PinType.MAP))),
            )
            BuiltinNodes.MAP_WITHOUT -> desc.withPins(
                inputs = listOf(mapPin, keyPin),
                outputs = listOf(PinSpec("Result", m ?: TypeRef(PinType.MAP))),
            )
            // **Optional, and that is the feature.** A key that has never been set is the ordinary case,
            // so the answer admits nothing and `?:` or `if val` is how you get back to something solid.
            BuiltinNodes.MAP_AT -> desc.withPins(
                inputs = listOf(mapPin, keyPin),
                outputs = listOf(PinSpec(BuiltinNodes.MAP_VALUE_PIN, v?.orNull() ?: TypeRef(PinType.WILDCARD))),
            )
            BuiltinNodes.MAP_KEYS -> desc.withPins(
                inputs = listOf(mapPin),
                outputs = listOf(PinSpec("Keys", TypeRef.list(k))),
            )
            BuiltinNodes.MAP_VALUES -> desc.withPins(
                inputs = listOf(mapPin),
                outputs = listOf(PinSpec("Values", TypeRef.list(v))),
            )
            // The loop's Key and Value carry the map's OWN argument types, which is what makes
            // `for (k, v) in m { … v.name … }` a checked read rather than a wildcard.
            BuiltinNodes.MAP_FOR_EACH -> desc.withPins(
                inputs = listOf(PinSpec("Exec", PinType.EXEC), mapPin),
                outputs = listOf(
                    PinSpec("Body", PinType.EXEC),
                    PinSpec(BuiltinNodes.MAP_KEY_PIN, k ?: TypeRef(PinType.WILDCARD)),
                    PinSpec(BuiltinNodes.MAP_VALUE_PIN, v ?: TypeRef(PinType.WILDCARD)),
                    PinSpec("Completed", PinType.EXEC),
                ),
            )
            else -> desc
        }
    }
    // The type picker on `is` offers the built-in kinds AND whatever this document declares — the same
    // trick the struct nodes play, because the answer is a property of the document rather than of the
    // catalog.
    // A cast's OUTPUT is the type it names, which is what lets the result be used as one — and its
    // picker offers whatever this document declares, like every other type choice here.
    if (desc.type == BuiltinNodes.CAST) {
        val declared = types()
        val named = node.literals[BuiltinNodes.CAST_OF]?.toString()?.trim().orEmpty()
        // Parsed rather than matched by name, so `MAP<STRING, Tally>` is a type here and not a miss. A
        // target with arguments is never a declared record — it is a container — so the lookup is skipped
        // and the ref itself becomes the result type.
        val ref = TypeRef.parse(named)
        val target = if (ref.args.isEmpty()) declared.firstOrNull { it.name.equals(named, ignoreCase = true) } else null
        // **Reading NOTHING as a Layout is nothing.** `readJson` on a file that is not there gives no
        // document, and the honest type of `readJson(…) as Layout` is therefore `Layout?` — which is what
        // makes the ordinary first-run case an `?:` or an `if val` instead of a stop. Carried from the
        // source rather than declared on the node, because the same cast is not optional when it reads a
        // record: `p as Vec2i` had a `p`.
        val optional = feeding(node.id, "Value")?.optional == true
        val result = when {
            ref.args.isNotEmpty() -> ref
            target != null -> TypeRef.named(target.name)
            else -> TypeRef(PinType.WILDCARD)
        }
        return desc.withPins(
            inputs = desc.inputs.map {
                if (it.name != BuiltinNodes.CAST_OF) it
                else PinSpec(it.name, it.type, default = it.default, options = declared.map { t -> t.name }, typeChoice = true)
            },
            outputs = listOf(PinSpec("Result", if (optional) result.orNull() else result)),
        )
    }
    if (desc.type == BuiltinNodes.IS_TYPE) {
        val names = BuiltinNodes.IS_TESTABLE + types().map { it.name }
        return desc.withPins(
            inputs = desc.inputs.map {
                if (it.name != BuiltinNodes.IS_OF) it
                else PinSpec(it.name, it.type, default = it.default, options = names, typeChoice = true)
            },
        )
    }
    // A `when`'s arms are decided by how many cases were written, the same way a list literal's slots are.
    //
    // The case pins are WILDCARD rather than the subject's type, and that is a limit of what can be known
    // here: which form this is depends on whether `Subject` is WIRED, and this function is given a node and
    // its literals but never the links. Guessing from a flag on the node would let the flag and the wiring
    // disagree, so the question is asked where it can be answered — the compiler and the validator both have
    // the graph, and `checkWhen` is what refuses a case of the wrong type.
    if (desc.type == BuiltinNodes.WHEN) {
        val n = BuiltinNodes.whenCount(node)
        return desc.withPins(
            inputs = desc.inputs + (1..n).map { PinSpec(BuiltinNodes.whenCase(it), PinType.WILDCARD) },
            outputs = (1..n).map { PinSpec(BuiltinNodes.whenThen(it), PinType.EXEC) } +
                PinSpec(BuiltinNodes.WHEN_ELSE, PinType.EXEC),
        )
    }
    // A choice's members are its declaration's, the same trick the struct nodes play. Its OUTPUT carries the
    // enum's own type rather than STRING, which is the point of declaring one: `canConnect` then refuses a
    // `Phase` where a `Skill` belongs, and refuses either where a bare string is expected.
    if (desc.type == BuiltinNodes.ENUM_OF) {
        val declared = enums()
        val named = node.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim().orEmpty()
        val naming = PinSpec(
            BuiltinNodes.ENUM_TYPE, PinType.ENUM,
            default = declared.firstOrNull()?.name,
            options = declared.map { it.name },
            typeChoice = true,
        )
        val t = declared.firstOrNull { it.name.equals(named, ignoreCase = true) }
        // Unresolved: keep the name that was WRITTEN rather than widening to a wildcard. A wildcard connects
        // to anything, so the graph would keep compiling and quietly stop meaning what it said — the failure
        // `TypeRef.named` exists to prevent. The validator is where a missing enum gets reported.
        val out = if (t != null) TypeRef.named(t.name)
        else if (named.isNotEmpty()) TypeRef.named(named)
        else TypeRef(PinType.WILDCARD)
        val member = PinSpec(
            BuiltinNodes.ENUM_MEMBER, PinType.ENUM,
            default = t?.members?.firstOrNull(),
            options = t?.members.orEmpty(),
        )
        return desc.withPins(
            inputs = listOf(naming, member),
            outputs = listOf(PinSpec("Value", out)),
        )
    }
    // Every member of an enum. One shape pin and a LIST output whose ELEMENT is the enum's own type, so
    // `for p in Phase.values()` binds a `Phase` and not a string — which is the whole reason this is a node
    // rather than a hand-written list of names.
    if (desc.type == BuiltinNodes.ENUM_VALUES) {
        val declared = enums()
        val named = node.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim().orEmpty()
        val naming = PinSpec(
            BuiltinNodes.ENUM_TYPE, PinType.ENUM,
            default = declared.firstOrNull()?.name,
            options = declared.map { it.name },
            typeChoice = true,
        )
        val t = declared.firstOrNull { it.name.equals(named, ignoreCase = true) }
        // The name that was WRITTEN when it resolves to nothing, never a wildcard — the same reasoning
        // Choice gives: a wildcard connects to anything and the graph would quietly stop meaning what it
        // said. The validator reports the missing enum.
        val elem = if (t != null) TypeRef.named(t.name)
        else if (named.isNotEmpty()) TypeRef.named(named)
        else TypeRef(PinType.WILDCARD)
        return desc.withPins(
            inputs = listOf(naming),
            outputs = listOf(PinSpec("Values", TypeRef.list(elem))),
        )
    }
    // One column of an enum's table. The same shape as a record's Get Field: two shape pins saying which
    // enum and which column, a value pin taking the member, and an output carrying the FIELD's own declared
    // type — which is what makes `t.count + 1` type-check and `t.anchor` wire into a Tile pin.
    if (desc.type == BuiltinNodes.ENUM_FIELD) {
        val declared = enums()
        val named = node.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim().orEmpty()
        val naming = PinSpec(
            BuiltinNodes.ENUM_TYPE, PinType.ENUM,
            default = declared.firstOrNull()?.name,
            options = declared.map { it.name },
            typeChoice = true,
        )
        // Unresolved enum, or one with no fields: the naming pin and nothing else. There is no honest
        // output to offer, and inventing a wildcard one would let the graph compile and mean nothing.
        val t = declared.firstOrNull { it.name.equals(named, ignoreCase = true) }
            ?.takeIf { it.fields.isNotEmpty() }
            ?: return desc.withPins(inputs = listOf(naming), outputs = emptyList())
        val value = PinSpec("Value", TypeRef.named(t.name))
        val chosenName = node.literals[BuiltinNodes.STRUCT_FIELD]?.toString()?.trim()
        val field = PinSpec(
            BuiltinNodes.STRUCT_FIELD, PinType.ENUM,
            default = t.fields.firstOrNull()?.name,
            options = t.fields.map { it.name },
        )
        val chosen = t.field(chosenName) ?: t.fields.first()
        return desc.withPins(
            inputs = listOf(naming, value, field),
            outputs = listOf(PinSpec(chosen.name, chosen.type)),
        )
    }
    // A struct node's shape is its declaration's fields, which is the same trick a Call node plays with a
    // signature — the pins are a property of the DOCUMENT, not of this catalog. Make takes them in and
    // hands out one value; Break takes that value and hands the fields back.
    if (desc.type in BuiltinNodes.STRUCT_SHAPED) {
        val declared = types()
        val named = node.literals[BuiltinNodes.STRUCT_OF]?.toString()?.trim()
        // The naming pin offers what this document declares. Filled in HERE rather than listed in the
        // catalog for the same reason a Call node's pins are: the answer is a property of the document, and
        // a descriptor built once at startup would have been a snapshot of an empty list.
        val naming = PinSpec(
            BuiltinNodes.STRUCT_OF, PinType.ENUM,
            default = declared.firstOrNull()?.name,
            options = declared.map { it.name },
            typeChoice = true,
        )
        val t = declared.firstOrNull { it.name.equals(named, ignoreCase = true) }
            ?: return desc.withPins(inputs = listOf(naming), outputs = emptyList())
        // A generic record's arguments, at THIS node. `struct.make` learns them from the field values it
        // was given; the three that take a record already have one, so they read them off it. Empty for
        // every non-generic record, and `substitute` on an empty map hands the type straight back.
        val bound = when {
            t.params.isEmpty() -> emptyMap()
            desc.type == BuiltinNodes.STRUCT_MAKE -> bindFields(t, node.id, feeding)
            else -> bindReceiver(declaredTypeOf(t), feeding(node.id, "Value"))
        }
        // The value pin carries the arguments when they are known — that is what makes `p.first` an INT
        // rather than a wildcard downstream. Unknown ones stay unconstrained rather than becoming
        // wildcards positionally, so `Pair` still connects to `Pair<INT, STRING>` exactly as an
        // unconstrained `LIST` connects to a `LIST<INT>`.
        val instance =
            if (t.params.isEmpty() || bound.isEmpty()) TypeRef.named(t.name)
            else TypeRef.of(TypeRef.named(t.name), t.params.map { bound[it] ?: WILD })
        val self = PinSpec("Value", instance)
        // The DEFAULT travels with the pin, so a record literal that leaves a field out gets the
        // declared value rather than the type's zero — which is the whole of record field defaults
        // at the build site, since an unsupplied pin already falls back to its default.
        val fields = t.fields.map { PinSpec(it.name, substitute(it.type, bound), default = it.default) }
        // Which FIELD, for the two nodes that work on one. Its choices are the record's own field names,
        // and it decides the value pin's type, which is why it is a shape pin like the naming one.
        val chosenName = node.literals[BuiltinNodes.STRUCT_FIELD]?.toString()?.trim()
        val field = PinSpec(
            BuiltinNodes.STRUCT_FIELD, PinType.ENUM,
            default = t.fields.firstOrNull()?.name,
            options = t.fields.map { it.name },
        )
        val chosen = t.fields.firstOrNull { it.name.equals(chosenName, ignoreCase = true) }
            ?: t.fields.firstOrNull()
        return when (desc.type) {
            BuiltinNodes.STRUCT_MAKE -> desc.withPins(inputs = listOf(naming) + fields, outputs = listOf(self))
            BuiltinNodes.STRUCT_SPLIT -> desc.withPins(inputs = listOf(naming, self), outputs = fields)
            BuiltinNodes.STRUCT_GET -> desc.withPins(
                inputs = listOf(naming, self, field),
                outputs = listOfNotNull(chosen?.let { PinSpec(it.name, substitute(it.type, bound)) }),
            )
            // The value pin is named for the field, so the node reads as "set x to …" rather than as a
            // generic slot — and it carries the field's own type, so it gets that type's editor and the
            // validator refuses a mis-wire.
            else -> desc.withPins(
                inputs = listOfNotNull(naming, self, field, chosen?.let { PinSpec(it.name, substitute(it.type, bound)) }),
                outputs = listOf(PinSpec("Result", instance)),
            )
        }
    }
    // A Return INSIDE a body carries that function's results — one pin each, so every return can hand back
    // its own values. Wiring them to the box instead makes every path share one expression: the box has one
    // input per result, so "return here" and "return there" cannot differ, and a literal written twice just
    // overwrote itself. At top level there is no signature to take, so it keeps its single Value pin and
    // goes on meaning "end the fiber".
    if (desc.type == BuiltinNodes.RETURN) {
        val owner = node.function?.let(function) ?: return desc
        return desc.withPins(
            inputs = desc.inputs.filter { it.type.isExec } + owner.results.map { PinSpec(it.name, it.type) },
        )
    }
    val name = if (desc.type == BuiltinNodes.CALL) node.callee else node.function
    val fn = name?.let(function) ?: return desc
    // **Phase E, and the whole of it that anything downstream can see.** A generic signature's variables
    // are bound from the type actually wired into `self` and substituted through every parameter and
    // result, so `xs.first()` on a `LIST<ENTITY>` has an `ENTITY?` output pin rather than a `T?` one.
    //
    // This is the hook and not `effectivePinType`, which is where the obvious guess goes: that function
    // opens with `if (!spec.type.isWildcard) return spec.type`, and a `T`-typed pin is not a wildcard, so
    // a rule keyed there could never fire. Here the pins are being BUILT from the signature, which is the
    // only place the substitution can reach both sides.
    //
    // A Call inside the function's own body — the box — has no receiver to read, so its variables stay
    // unbound and go on behaving as wildcards. That is correct: inside `fn List<T>.first`, `T` genuinely
    // is unknown.
    //
    // Phase F widened it from the receiver alone to every parameter — the difference between
    // "substitution from one binding site" and inference. Only for a CALL: the box is the same signature
    // seen from inside, where a variable is genuinely unknown.
    val bound = if (desc.type != BuiltinNodes.CALL || !isGeneric(fn)) emptyMap()
    else bindCall(fn, node.id, feeding)
    // The default rides along, so an argument nobody supplied falls back to it exactly as an ordinary
    // node's unwired pin does — see FunctionPin.default for why that is the whole mechanism.
    fun pins(from: List<FunctionPin>) =
        from.map { PinSpec(it.name, substitute(it.type, bound), default = it.default) }

    // A function whose body only COMPUTES is an expression, and its Call is a pure node: no exec pins, and
    // re-evaluated wherever it is read. Derived rather than declared — see [isPureFunction] for why — so
    // the exec pins simply are not there rather than being there and ignored.
    if (pure(node, fn)) {
        return when (desc.type) {
            BuiltinNodes.FUNCTION -> desc.withPins(inputs = pins(fn.results), outputs = pins(fn.params))
            else -> desc.withPins(inputs = pins(fn.params), outputs = pins(fn.results), kind = NodeKind.PURE)
        }
    }
    return when (desc.type) {
        // THE BOX IS THE BOUNDARY, seen from inside. A parameter is something the body reads, so it is an
        // OUTPUT of the box; a result is something the body writes, so it is an INPUT. The canvas draws
        // those two sides mirrored — parameters down the left edge, results down the right — so data still
        // runs left to right through the box, which is the only reading that makes sense from in there.
        BuiltinNodes.FUNCTION ->
            desc.withPins(inputs = desc.inputs + pins(fn.results), outputs = desc.outputs + pins(fn.params))
        else -> desc.withPins(inputs = desc.inputs + pins(fn.params), outputs = desc.outputs + pins(fn.results))
    }
}

/**
 * Is [name] an expression rather than a step?
 *
 * **Derived, not declared.** A flag on the function would be a second statement of something the body
 * already says, and the two can disagree — which is the failure this project has spent real effort not
 * repeating. The body is the answer: a function is pure when everything in it is pure and it produces
 * something to read. Nothing else could make it pure, and nothing else could make it impure.
 *
 * Note what "pure" means here, because it is Blueprints' meaning and not the mathematical one: no exec
 * pins, re-evaluated at every use. A pure function may read the world and read graph variables — so may a
 * pure NODE, and `Total Experience` is one. What it may not do is anything that has to happen in an order.
 *
 * A function with no results is not pure however little it does: there would be nothing to pull on, so the
 * Call could neither be reached nor read, and it would silently never run. Nor is one with an empty body:
 * that is not an expression, it is a function nobody has written yet — and reading it as one would take
 * the exec pins off a Call the moment it was pointed at a freshly declared function, which is the order
 * everyone works in.
 *
 * **And its box must not be wired through exec.** A body that only computes but whose boundary is wired
 * `Exec -> … -> Exec` is one whose author said it is a step, and that has to keep meaning what it meant:
 * turning it into an expression would delete the exec pins on every existing call site. It is also the
 * opt-out worth having — a pure function is re-evaluated at every read, so an expensive body is sometimes
 * better run once and cached, which is exactly what an impure one does. The signal is inside the function
 * rather than beside it, so unlike a flag it cannot disagree with the body.
 */
/**
 * Is this a MUTATING extension — one a caller may write `xs.add(3)` against?
 *
 * **Read off the signature, because that is where the property lives.** A mutating extension hands its
 * receiver back through an implicit result named `self`, so there is no flag to store, nothing to keep in
 * step, and the fact crosses an import, survives a JSON round trip and shows up on the canvas for free.
 * The alternative — a boolean on [GraphFunction] — would have been a second statement of one fact, and the
 * second one goes stale.
 *
 * A function that is not an extension can never be one: there is no receiver to write back to.
 */
fun isMutating(fn: GraphFunction): Boolean =
    fn.self != null && fn.results.size == 1 && fn.results[0].name == GraphFunction.SELF_RESULT

fun isPureFunction(
    name: String,
    catalog: NodeCatalog,
    nodes: List<Node>,
    function: (String) -> GraphFunction?,
    links: List<Link> = emptyList(),
    seen: Set<String> = emptySet(),
    /**
     * Purity of a QUALIFIED callee, answered by whoever holds the import closure; null for a local one.
     *
     * Without it this recursion walks the wrong document: a Call to `geo::chebyshev` is looked for among
     * the CALLER's body nodes, is not found, and the function containing it is judged impure. What that
     * looks like is a correct expression-bodied function being told "'Call.Result' is read here, but
     * nothing runs 'Call'" — so an imported pure function could be called from a block body and from
     * nowhere else.
     */
    across: (String) -> Boolean? = { null },
): Boolean {
    // Already asking about this one further up — so this is a call back into a function whose purity is
    // still being decided. Assume PURE rather than refusing.
    //
    // Purity is "the body computes and never sequences", and a recursive call to a pure function is a
    // computation like any other; answering `false` here made a recursive expression-bodied function look
    // impure, which put exec pins on it and had the validator complain that nothing runs a value.
    // Co-inductive, and safe because the property being decided is really about the BOX's exec wires,
    // checked below and not affected by this. A ring through *another* function reaches here too and gets
    // the same answer, which costs nothing: the validator refuses mutual recursion outright.
    if (name in seen) return true
    val fn = function(name) ?: return false
    if (fn.results.isEmpty()) return false
    val box = nodes.firstOrNull { it.type == BuiltinNodes.FUNCTION && it.function == name }
    if (box != null && links.any {
            (it.fromNode == box.id && it.fromPin == "Exec") || (it.toNode == box.id && it.toPin == "Exec")
        }
    ) {
        return false
    }
    var body = 0
    for (n in nodes) {
        if (n.function != name) continue
        val kind = catalog[n.type]?.kind ?: return false
        if (kind == NodeKind.FUNCTION || kind == NodeKind.COMMENT) continue
        body++
        if (kind == NodeKind.PURE) continue
        // A Call is impure in the catalogue and may not be in this document — ask about ITS body.
        if (n.type == BuiltinNodes.CALL) {
            val callee = n.callee ?: return false
            // An imported callee's body is in the other document, so its purity has to be asked of the
            // scope that can see it rather than derived from the nodes in hand.
            val imported = across(callee)
            if (imported != null) {
                if (imported) continue else return false
            }
            if (isPureFunction(callee, catalog, nodes, function, links, seen + name, across)) continue
        }
        return false
    }
    // An EMPTY body is not an expression, it is an unfinished function. Reading it as one would take the
    // exec pins off a Call the moment it was pointed at a freshly declared function — which is the order
    // everyone works in: declare it, wire the call, then fill it in.
    if (body > 0) return true
    // Unless its results are FED. `fn id(n: INT) -> INT = n` wires the box's own parameter straight into
    // its own result and needs no node in between — it computes, it just does not compute much. Counting
    // nodes alone called that unfinished and put exec pins on every call to it, so a pass-through could
    // not be an expression at all.
    if (box == null) return false
    return fn.results.any { r ->
        // Wired, or TYPED IN. `fn answer() -> INT = 42` puts the literal straight onto the box's result
        // pin and creates no node at all, so a link test alone still called it unfinished.
        links.any { it.toNode == box.id && it.toPin == r.name } || box.literals.containsKey(r.name)
    }
}

/**
 * "Is this node an expression?", for [resolveNode].
 *
 * Two conditions, and the second is what makes the first safe to derive.
 *
 * The FUNCTION must only compute — [isPureFunction]. And a Call must not already be wired into the exec
 * chain: one that is, is a step, and it stays one. Without that, turning a computing function into an
 * expression would delete the exec pins under every call site that had them, which is a graph rewrite
 * dressed up as a detection. Per call site rather than per function, so the same helper can be run once
 * and cached in one place and read as an expression in another — which is a real thing to want, since an
 * expression is re-evaluated at every read.
 */
fun expressionCalls(
    catalog: NodeCatalog,
    nodes: List<Node>,
    links: List<Link>,
    function: (String) -> GraphFunction?,
    /**
     * Purity of a QUALIFIED callee, answered by whoever holds the import closure; null for a local one.
     *
     * Needed because purity is DERIVED FROM A BODY, and an imported function's body is in the other
     * document — asking this graph's nodes about it always answered "not pure". A Call to an imported
     * expression-bodied function then went on the exec chain, and the validator reported
     * "'Call.Result' is read here, but nothing runs 'Call'" for a call that was correct. In practice a
     * pure function could not be imported at all.
     *
     * A callback rather than a document, because deriving purity inside that document needs ITS import
     * scope too — a library's pure function may itself call through an import, and a bare `Graph::function`
     * cannot resolve that.
     */
    across: (String) -> Boolean? = { null },
): (Node, GraphFunction) -> Boolean {
    val cache = HashMap<String, Boolean>()
    return { node, fn ->
        val written = node.callee ?: fn.name
        val pure = cache.getOrPut(written) {
            // A LOCAL function may itself call an imported one, so the predicate has to go down with it.
            across(written) ?: isPureFunction(fn.name, catalog, nodes, function, links, emptySet(), across)
        }
        pure && (node.type != BuiltinNodes.CALL || links.none { execTouches(it, node.id) })
    }
}

private fun execTouches(l: Link, nodeId: Int): Boolean =
    (l.fromNode == nodeId && l.fromPin == "Exec") || (l.toNode == nodeId && l.toPin == "Exec")

/** One pin on a node. */
class PinSpec(
    val name: String,
    val type: TypeRef,
    /** Value used when an input pin is left unconnected and the node carries no literal for it. */
    val default: Any? = null,
    /** For [PinType.ENUM], the choices the editor offers. Empty elsewhere. */
    val options: List<String> = emptyList(),
    /**
     * This pin names a TYPE, so its choices come from [Types] rather than from [options].
     *
     * A flag rather than a fixed option list because the registry can grow after this catalog is built —
     * a descriptor is constructed once, and a snapshot of the type names taken at that moment would be
     * exactly wrong for the user-defined types that are the point of having a registry.
     */
    val typeChoice: Boolean = false,
    /**
     * Draw a value editor for this pin even though it is an OUTPUT.
     *
     * Inputs get one automatically when nothing is wired to them. An output normally cannot — its value
     * comes from the node — but a Literal *is* its value, and with no way to type one the node was
     * decorative. This is the flag that says "this output is where the author types".
     */
    val editable: Boolean = false,
    /**
     * What this pin MEANS, for a tooltip on the canvas and for parameter documentation in an editor.
     *
     * Separate from [NodeDescriptor.summary], which says what the node does. The two answer different
     * questions and an IDE asks them at different moments: the summary when you are choosing a node, this
     * when your cursor is already inside its argument list and the only thing left in doubt is what to put
     * there. Empty is honest — better a pin with nothing to say than a restatement of its own name.
     */
    val doc: String = "",
) {
    /**
     * The built-in spelling, so the couple of hundred pins declared across the catalogue and the game node
     * library read exactly as they did before types could be named.
     *
     * [elementType] folds into [type] rather than sitting beside it: a list's element type is part of what
     * the pin IS, and keeping it separate is why [canConnect] could not tell a list of items from a list of
     * tiles. Declaring it here is still the natural way to write one.
     */
    constructor(
        name: String,
        type: PinType,
        elementType: PinType? = null,
        default: Any? = null,
        options: List<String> = emptyList(),
        typeChoice: Boolean = false,
        editable: Boolean = false,
        doc: String = "",
    ) : this(
        name,
        if (type == PinType.LIST && elementType != null) TypeRef.list(TypeRef(elementType)) else TypeRef(type),
        default, options, typeChoice, editable, doc,
    )

    /** What a list pin holds, for the places that ask the pin rather than the type. */
    val elementType: TypeRef? get() = type.of
}

/**
 * How a node participates in execution.
 *
 * The pure/impure split is Blueprints', and copying it is deliberate: a *pure* node has no exec pins and is
 * re-evaluated at every use site, while an *impure* node executes once in exec order and its outputs are
 * cached. Authors already carry that intuition, and it makes the compiler's job unambiguous.
 */
enum class NodeKind {
    /** Starts a fiber. Exec output(s), no exec input. */
    ENTRY,

    /** Sequenced by exec wires; outputs cached in stable registers. */
    IMPURE,

    /** No exec pins. An expression, re-evaluated wherever it is read. */
    PURE,

    /**
     * A user function's container box. Like [COMMENT] it is annotation: it names and frames a body, and
     * compiles to nothing itself. What runs is the body inside it, and only when something calls it.
     */
    FUNCTION,

    /**
     * Pure annotation — a comment container. Has no pins, never compiles to anything, and exists to group
     * and label a region of the canvas. The validator and compiler both skip these entirely, so a comment
     * can never break a graph.
     */
    COMMENT,
    ;

    /**
     * A box that FRAMES other nodes rather than being one.
     *
     * Comments and function boxes want identical handling nearly everywhere on the canvas — dragging one
     * moves what is inside it, clicks fall through to the nodes on top, only the header and the corner grip
     * are grabbable. Asking this rather than naming COMMENT is what let functions inherit all of it instead
     * of growing a parallel copy.
     */
    val isContainer: Boolean get() = this == COMMENT || this == FUNCTION
}

/**
 * A node type in the catalog.
 *
 * [host] names the VM host function for a node that compiles to a call; built-in control-flow and operator
 * nodes leave it null and are lowered directly to bytecode by the compiler (see [BuiltinNodes]).
 */
class NodeDescriptor(
    val type: String,
    val title: String,
    val category: String,
    val kind: NodeKind,
    val inputs: List<PinSpec> = emptyList(),
    val outputs: List<PinSpec> = emptyList(),
    val host: String? = null,
    val hostKind: HostKind = HostKind.INLINE,
    /** One-line description for the palette and the node's tooltip. */
    val summary: String = "",
    /**
     * What this is written ON in text, when it is an extension — `fn INT.toItem() -> Item`.
     *
     * Null for the overwhelming majority: a node is called by name. When set, the FIRST input pin is the
     * receiver, so the node is an ordinary one on the canvas — a pin is a pin — and only its text
     * spelling differs. See `NativeFn.receiver`, which this becomes.
     *
     * It exists because a host had no way to declare what a document has been able to write since
     * generics landed, and the absence had put seven of one game's verbs into the language's own
     * intrinsic table.
     */
    val receiver: TypeRef? = null,
    /**
     * How this is written in text, when the derived spelling is not the one wanted.
     *
     * A node is normally written as its type's last segment — `scene.nearestNpc` is `nearestNpc` — and
     * that is right almost always. It is not right when a domain wants a name the derivation cannot
     * reach: `tile(x, y, plane)` is how a position has been written since before it was a node, and a
     * derived spelling would either be `tile` by luck or `scene.tile` by collision.
     *
     * The language has had this for its OWN nodes since `value.format` needed to be `text` — a table of
     * overrides in `Names`. This is the same thing for a host's, so a pack does not need an entry in a
     * table that lives in the language.
     *
     * Claimed in the same pass as those overrides, so a derived name never takes one out from under it.
     */
    val textName: String? = null,
) {
    /** A copy with different pins — how [resolveNode] applies a function signature. */
    fun withPins(
        inputs: List<PinSpec> = this.inputs,
        outputs: List<PinSpec> = this.outputs,
        kind: NodeKind = this.kind,
    ): NodeDescriptor =
        NodeDescriptor(
            type, title, category, kind, inputs, outputs, host, hostKind, summary, receiver, textName,
        )

    fun input(name: String): PinSpec? = inputs.firstOrNull { it.name == name }
    fun output(name: String): PinSpec? = outputs.firstOrNull { it.name == name }

    /**
     * The same lookups, forgiving how the name was spelled — `InCombat`, `in_combat` and `incombat` all
     * find the pin called `In Combat`.
     *
     * For the command line, where an argument list is split on spaces and there is nowhere to put a
     * quote: `In Combat` arrives as two arguments and every pin whose name has a space in it is simply
     * unreachable. Which pins those are is an accident of prose — `In Combat` reads better on the canvas
     * than `Combat` does — so the fix belongs at the lookup rather than in the pin names.
     *
     * The exact match is tried first, always: a pin spelled precisely must never lose to a loose
     * collision with a differently-spelled neighbour. Callers should store the returned [PinSpec.name],
     * not what the caller typed — a document records canonical pin names or a reopened graph finds none.
     */
    fun inputLoosely(name: String): PinSpec? = input(name) ?: inputs.firstOrNull { looseName(it.name) == looseName(name) }

    /** Output-side [inputLoosely]. */
    fun outputLoosely(name: String): PinSpec? = output(name) ?: outputs.firstOrNull { looseName(it.name) == looseName(name) }

    /** Exec input pins, in declaration order. */
    val execInputs: List<PinSpec> get() = inputs.filter { it.type.isExec }

    /** Exec output pins, in declaration order — a Branch's True/False, a Sequence's Then0..N. */
    val execOutputs: List<PinSpec> get() = outputs.filter { it.type.isExec }

    val dataInputs: List<PinSpec> get() = inputs.filterNot { it.type.isExec }
    val dataOutputs: List<PinSpec> get() = outputs.filterNot { it.type.isExec }
}

/** A pin name with the parts nobody can type through a space-split argument list taken out. */
private fun looseName(name: String): String =
    name.filterNot { it.isWhitespace() || it == '_' || it == '-' }.lowercase()

/** The set of node types a graph may use. */
class NodeCatalog @JvmOverloads constructor(
    descriptors: List<NodeDescriptor> = emptyList(),
    /**
     * Built-in node types this host does not offer — `entry.render` on a server that draws no frames, say.
     *
     * The palette lists [all], so an excluded kind is simply not there to pick; a document that names one
     * anyway is reported as an unknown node type, rather than compiled into a handler nothing will ever
     * drive. Only builtins can be excluded: a host that does not want one of its own nodes leaves it out.
     */
    exclude: Set<String> = emptySet(),
) {
    private val byType = LinkedHashMap<String, NodeDescriptor>()

    init {
        BuiltinNodes.all.filterNot { it.type in exclude }.forEach { register(it) }
        descriptors.forEach { register(it) }
    }

    fun register(d: NodeDescriptor): NodeCatalog {
        require(byType.put(d.type, d) == null) { "duplicate node type '${d.type}'" }
        return this
    }

    operator fun get(type: String): NodeDescriptor? = byType[type]

    val types: Set<String> get() = byType.keys
    val all: List<NodeDescriptor> get() = byType.values.toList()
}

/**
 * The built-in node types the compiler lowers directly, rather than compiling to a host call.
 *
 * These are the ones that cannot be host functions because they need control over *when* their inputs are
 * evaluated or where control goes next — a Branch must not evaluate both sides, a While must re-evaluate
 * its condition each iteration.
 */
object BuiltinNodes {
    const val ENTRY = "entry.start"

    /**
     * Runs when the script ENDS — every fiber finished, or Stop pressed.
     *
     * Its own fiber, started after the others are done, which is why graph variables had to become real
     * globals: recording the time and the XP at the start is worthless if the handler that reports them
     * cannot see what was written.
     */
    const val ENTRY_STOP = "entry.stop"
    const val ENTRY_RENDER = "entry.render"

    /**
     * Runs once per GAME TICK — the ~600ms beat the server actually moves on.
     *
     * The counterpart to [ENTRY_RENDER] on the other clock. A frame is for drawing what is already known;
     * a tick is for NOTICING — sampling the game exactly as often as it changes, and no more.
     */
    const val ENTRY_TICK = "entry.tick"

    /**
     * Runs BEFORE the loop, every run — the preparation phase.
     *
     * "Get into a state where work can begin": walk to where the work is, bank what is in the way, put
     * the right gear on, and read back any state a previous [ENTRY_SLEEP] saved. [ENTRY] then does the
     * work, with the world already arranged.
     *
     * **It runs on a cold start too, not only after a sleep**, which is the whole of its value — a
     * script reaches "ready" the same way every time, whether it has ever slept or not. A script that has
     * never run is asleep; running it wakes it.
     *
     * It may block, and it must finish before [ENTRY] is spawned. That ordering cannot be a
     * `Fiber.waitsFor` edge, because a fiber waiting on the actuator counts as *settled* — the work
     * fibers would be released mid-bank-trip and the script would contend with itself for the one drain.
     */
    const val ENTRY_WAKE = "entry.wake"

    /**
     * Runs when the script is asked to hand over — AFTER its loops have finished, while it can still act.
     *
     * The counterpart to [ENTRY_WAKE], and a different thing from [ENTRY_STOP]. A stop is "you are done,
     * now"; a sleep is "finish what you are doing, then put yourself away so somebody else can have the
     * account". The host raises a flag, the script sees it through [SLEEP_REQUESTED] and breaks its loop
     * at a point IT chooses, and only then does this run — which is what makes "finish the round, then
     * bank" expressible at all. [ENTRY_STOP] cannot: the actuator is cancelled before it is fired.
     *
     * It may block. That is the point.
     */
    const val ENTRY_SLEEP = "entry.sleep"

    /**
     * The entries the HOST pumps, rather than spawning as fibers at Run.
     *
     * **This set answers ONE question — "is it spawned at Run" — and it is not the same question as "may
     * it wait".** Render and tick each run as a bounded pass on someone else's clock, with their own
     * budget and staged writes, and started as an ordinary fiber as well one would run twice over with
     * the fiber copy committing writes the pass had just rolled back. Stop, wake and sleep are kept out
     * for the other reason: they are ordinary fibers, but spawned at a MOMENT — before the work, or after
     * it — rather than alongside it. Those three may block; the cannot-wait rule is
     * `Validator.checkDrivenEntries`, which covers render and tick alone. If a seventh kind arrives and
     * the two questions have still not been split, split them.
     *
     * A set rather than a chain of `!=` because there are now three places that have to agree on it, and
     * a fourth entry kind added to two of them is a bug that only shows up under load.
     */
    val DRIVEN_ENTRIES: Set<String> = setOf(ENTRY_STOP, ENTRY_RENDER, ENTRY_TICK, ENTRY_WAKE, ENTRY_SLEEP)

    /**
     * Entry node type → the word that follows `on`.
     *
     * **The one place the entry kinds are spelled**, and the source of their membership in the printer's
     * `STATEMENT_FORMS` and `BODY_FORMS` as well. Those were three hand-kept lists that all had to agree,
     * and an entry missing from one of them fails differently in each: missing from the spelling table it
     * used to print back as `on start` (a round trip that changes what the program does), and missing
     * from the other two it loses its braces. Derived from one map, they cannot disagree.
     *
     * Kept here rather than in `Print` because it is a fact about the node types, and because `Print`'s
     * companion is private — which is right for the printer's own internals and wrong for a table anything
     * else might reasonably want to read.
     */
    val ENTRY_WORDS: Map<String, String> = mapOf(
        ENTRY to "start",
        ENTRY_STOP to "stop",
        ENTRY_RENDER to "render",
        ENTRY_TICK to "tick",
        ENTRY_WAKE to "wake",
        ENTRY_SLEEP to "sleep",
    )

    /**
     * Has the host asked this script to sleep?
     *
     * The cooperative half of [ENTRY_SLEEP]: nothing can preempt a `while true`, so a handoff only
     * happens if the loop asks. Read it where the script is in a state somebody else could take over
     * from — between rounds, after a bank trip — and `break`.
     *
     * Not a wait of any kind, and not related to Delay. It is a question, answered from the run's own
     * phase, and it stays true through [ENTRY_SLEEP] so a library handler guarding on it does not skip
     * itself.
     */
    const val SLEEP_REQUESTED = "script.sleepRequested"
    const val BRANCH = "flow.branch"
    const val SEQUENCE = "flow.sequence"
    const val WHILE = "flow.while"
    const val FOR_EACH = "flow.forEach"
    const val DELAY = "flow.delay"
    const val RETURN = "flow.return"

    /**
     * Leave the innermost loop, and carry on after it.
     *
     * A NODE rather than a lowering, for the reason every other construct here is one: the compiler must be
     * able to see it. `break` cannot be expressed by rewiring — it is a jump out of a body to an address
     * that is not known until the body has been compiled — so making it sugar in the text surface would give
     * the canvas no way to say the same thing, and the two would stop being the same language.
     */
    const val BREAK = "flow.break"

    /** Skip to the next iteration of the innermost loop. See [BREAK]. */
    const val CONTINUE = "flow.continue"
    const val VAR_GET = "var.get"
    const val VAR_SET = "var.set"

    /**
     * On a [VAR_SET]: this Set computes a variable's DEFAULT rather than being a statement somebody wrote.
     *
     * `var x: T = <expression>` has nowhere to store a value — a declaration is not a place a node can hang
     * off — so `Lower.emitInits` turns it into a Set at the head of the document's `on start`, in
     * declaration order, and marks it with this. Three readers, which is why the mark lives down here in
     * the model rather than beside the one that writes it: the printer writes it back on the declaration
     * and skips it in the chain, the compiler uses it to find where a document's **initialiser prologue**
     * ends, and `Lower.SYNTACTIC_LITERALS` keeps it out of the editor's literal panel.
     */
    const val INIT_MARK = "@init"

    /** Is [node] one of those — the marker above, on the node type that can carry it? */
    fun isInitialiser(node: Node): Boolean =
        node.type == VAR_SET && node.literals.containsKey(INIT_MARK)

    /**
     * Evaluate something ONCE, here, and hand the same answer to everyone who reads it.
     *
     * A pure node is re-expanded at every use site — that is the bargain that lets it have no exec pin — so
     * wiring one output into three places is three evaluations. Usually that is what you want and it is
     * free. Sometimes it is neither: a query that walks the whole scene, or one whose answer must not change
     * between two readers that are meant to agree. Today the only way to say so is a graph variable, which
     * is global to the run and needs a Set node beside every use.
     *
     * This is that, locally. Being [NodeKind.IMPURE] is the entire mechanism: the compiler already gives
     * every impure node's outputs a stable register for the life of the chunk, so the value is computed
     * where this node sits in the exec chain and every reader is a register read. It compiles to a single
     * `MOVE`.
     *
     * [HOLD_NAME] labels it. A held value is worth naming — that is most of why you held it — and the name
     * is what the text surface spells as `let`.
     */
    const val HOLD = "value.hold"

    /** The pin naming a [HOLD]. Configuration: read while editing, never at run time. See [CONFIG_PINS]. */
    const val HOLD_NAME = "Name"

    /**
     * On a [HOLD]: this local may be **reassigned**, so it was written `var x = …` rather than `let x = …`.
     *
     * The distinction is the language's existing one, arriving where it was missing: `var` has always meant
     * assignable and `let` has always meant named-once, and until now only the first existed at top level
     * and only the second inside a body. So an accumulator had to be a graph variable — shared by every
     * call, which breaks recursion and re-entrancy in a way nothing reports.
     */
    const val MUTABLE = "@mutable"

    /**
     * This set was written `xs.add(3)` rather than `xs = xs.add(value: 3)`.
     *
     * A marker the PRINTER reads and the compiler never does — the third of its kind, after [MUTABLE] and
     * `@wroteField`. The two spellings lower to exactly the same graph, so there is nothing else that could
     * tell them apart, and there is nothing for the compiler to do differently.
     */
    const val WROTE_RECEIVER = "@wroteReceiver"

    /**
     * On a [VAR_SET] or [LOCAL_SET]: the author wrote `s.f = v`, not `s = s with { f: v }`.
     *
     * **The two are the same graph.** A field rebind IS a `struct.set` feeding a set — that is what makes
     * it a rebind rather than a write, records being values — so the lowering desugars one spelling into
     * the other and nothing distinguishes them afterwards. Which is exactly the collision §6.7 gives as
     * the reason `x += 1` was refused: two spellings, one graph, so the printer must pick and the other
     * stops round-tripping.
     *
     * The same answer as [MUTABLE], for the same reason. `let` and `var` are also one graph — a Hold — and
     * a marker is what lets the printer give back the words that were written. A canvas graph carries
     * neither, so a hand-wired Set Field prints as `with`, which is the honest default: nobody wrote an
     * assignment there.
     */
    const val WROTE_FIELD = "@wroteField"

    /**
     * A value with its `?` taken off, because something PROVED it is there — `if x != null { … }`.
     *
     * **Transparent: it prints as its own input and compiles to nothing.** A narrowing has no spelling of
     * its own — the author wrote a comparison, and the narrowing is what the comparison MEANS — so a node
     * that printed as anything at all would put words into the document nobody typed.
     *
     * It exists because a type here belongs to a PIN and not to a point in the exec chain, so "x is not
     * null from here on" has nowhere to live. Giving the proved value its own pin is the smallest thing
     * that does: inside the arm the test proved, the name is rebound to this node's output, and everything
     * downstream sees a `T` because that is genuinely what the pin carries.
     *
     * The soundness argument is the laziness of what surrounds it, in both places it is used: a Branch only
     * reaches its True chain when the condition held, and `Select` jumps over the arm it did not take.
     */
    const val NARROW = "value.narrow"

    /** What was proved — the pin the narrowed name reads from. Non-optional; the input is not. */
    const val NARROW_OUT = "Required"

    /**
     * On a [VAR_SET] or [LOCAL_SET]: the author wrote `n += 1`, not `n = n + 1`.
     *
     * **The refusal this lifts is recorded one paragraph up.** [WROTE_FIELD]'s note cites `x += 1` as the
     * example of a spelling that could not be had — "two spellings, one graph, so the printer must pick and
     * the other stops round-tripping" — and then solves exactly that problem for field assignment with a
     * marker. The argument against `+=` was never about `+=`; it was about sugar with nowhere to record
     * itself, and a Set node has somewhere.
     *
     * Holds the operator's own spelling (`+`, `-`, `*`, `/`, `%`) rather than a boolean, because a Set
     * carries one mark and there are five forms. The printer verifies the SHAPE before trusting it — the
     * value has to be that operator applied to a read of this same name — so a canvas edit that rewired the
     * node prints as a plain assignment instead of a compound one that no longer means what it says.
     *
     * A canvas graph carries no mark at all and prints `n = n + 1`, which is the honest default: nobody
     * wrote a compound assignment there.
     */
    const val ASSIGN_OP = "@assignOp"

    /**
     * On an ENTRY node: `always on render` — fires however this document was reached, imports included.
     *
     * The opt-in for imported entries. A library that runs its own handlers needs a way to say which of
     * them are for whoever imports it, and which are for developing the library — a debug overlay that is
     * useful in its own file and noise in every document that depends on it.
     *
     * **Its own word, not `export`.** An entry has no name for anyone to say, so there is nothing for
     * `export` to make nameable; the question is whether it RUNS. Two different questions that happen to
     * share a shape, and collapsing them would mean `export` meant one thing on a `fn` and another here.
     *
     * **A marker, so no format bump of its own.** [literals] is a generic map that already round-trips.
     * A document being run DIRECTLY runs all of its entries whatever they say — the plain form is for
     * exactly that case — so this key only ever decides what an importer inherits.
     */
    const val ALWAYS_ENTRY = "@always"

    /**
     * On a [HOLD]: the type its binding was DECLARED as, when one was written.
     *
     * A local's type otherwise comes from whatever fed it, which is right nearly always and wrong in one
     * common case — `var total = 0` is an INT, so a float accumulator started as an integer and every
     * later assignment had to be one too. Written out, the declaration wins and the initialiser is
     * converted to it like any other literal.
     */
    const val HOLD_TYPE = "@type"

    /**
     * The type a local's INITIALISER had at the source, when the stored value cannot say.
     *
     * **A value that is text does not know what kind of text it is.** A tile is stored `"3200,3200,0"`, a
     * skill and an enum member are stored as their names, and a plain string is stored as itself — so
     * `literalTypeOf` cannot answer for any of them and a Hold fed by one stayed WILDCARD. That is what
     * made `let t = tile(3200, 3200, 0)` impossible to take apart: `t.x` reported "this has no field 'x'"
     * on a value that is a record, and the language reference listed it under Known limits.
     *
     * The ambiguity is real in the VALUE and absent in the SOURCE — `tile(…)` is unmistakably a tile where
     * it is written — so the answer is recorded at lowering, which is the one place both are in view.
     *
     * Kept apart from [HOLD_TYPE] because the printer writes that one back: a declared type changes what
     * the binding MEANS and must survive the round trip, while this is something nobody typed and printing
     * it would put `: TILE` into a document whose author wrote no such thing. [HOLD_TYPE] therefore still
     * wins — what the author wrote beats what was worked out.
     */
    const val HOLD_INFERRED = "@inferred"

    /**
     * The same answer as [HOLD_INFERRED], for a literal typed into some OTHER node's wildcard pin.
     *
     * A Hold records what its initialiser was because the stored value cannot say — `"3200,3200,0"` is a
     * tile and also a perfectly good string. That works for `let spot = tile(…)` and does nothing for
     * `tile(1, 2, 0) == tile(1, 2, 0)`, where there is no Hold: the comparison's pins are WILDCARD, the
     * text goes straight into them, and the printer had only the pin type to go on — so it wrote back
     * `if "1,2,0" == "1,2,0"`. Correct, and the reader has lost the fact that these were tiles.
     *
     * Keyed per pin so one node can hold several, and `@`-prefixed like every other piece of metadata, so
     * nothing mistakes it for a pin. Only written when the destination is genuinely WILDCARD — a pin that
     * says TILE already answers the question, and a second answer that could disagree is worse than none.
     */
    fun pinInferred(pin: String): String = "@inferred:$pin"

    /**
     * `x is Int` — does this value's RUN-TIME form match the named type?
     *
     * A test, never a narrowing. `if x is Int { … }` does not make `x` an INT inside the branch, and it
     * deliberately cannot: a type here belongs to a PIN, not to a point in the exec chain, so there is
     * nowhere to record "but in this branch it is something else" — and on the canvas the wire into the
     * branch is the same wire as the one outside it. Narrowing needs flow-sensitive types this graph has
     * no way to express; a boolean needs none of that.
     *
     * **Only where the run time can tell.** An ITEM, an NPC and an OBJECT are all `Int`, and a SKILL and
     * an ENUM are both `String` — asking about those would return an answer that means nothing, so the
     * validator refuses them rather than letting a script branch on a coin toss. TILE and COLOR ARE
     * testable, because they became records.
     */
    const val IS_TYPE = "value.isType"

    /** The type being asked about — an [IS_TESTABLE] name, or one of the document's records. */
    const val IS_OF = "Of"

    /** `!is` rather than `is`. A flag rather than wrapping in Not, so both spellings print back exactly. */
    const val IS_NOT = "Not"

    /**
     * The built-in types whose run-time form is their own.
     *
     * Everything else either shares a representation with one of these — an ITEM is an `Int` — or is a
     * host handle whose class is not the language's business.
     */
    val IS_TESTABLE: List<String> = listOf("Int", "Float", "Bool", "String", "List")

    /**
     * `p as Vec2i` — one record read as another, matched BY NAME.
     *
     * Every field of the TARGET must be satisfied by a same-named field of the source, or by an explicit
     * rename. The source may be wider — the extra fields are dropped, which is the narrowing half — and it
     * may never be narrower, because the missing fields would have to be invented. TypeScript's rule, and
     * essentially every structurally-typed language's: matching is by name, and nobody zero-fills.
     *
     * **By name rather than by position** because a record's field ORDER is otherwise cosmetic. Nobody
     * expects reordering a declaration to change behaviour, and under positional matching it would
     * silently turn every cast of that type into garbage — `Point { x, y }` read as `Point2 { y, x }`
     * type-checks perfectly and swaps the coordinates. It is also the one place order would matter here:
     * `let {x, y} = p`, `with { x: 5 }` and struct literals are all by name already.
     *
     * A rename is how you say the two names mean the same thing — `t as Vec3i { z: plane }` — which is
     * exactly the case a by-name rule cannot guess and a positional one would guess wrongly half the time.
     */
    const val CAST = "value.cast"

    /** The type being cast TO. */
    const val CAST_OF = "Of"

    /** `target=source` pairs, comma separated. See [CAST]. */
    const val CAST_RENAMES = "@renames"

    /** The renames on [node], as target field name -> source field name. */
    fun castRenames(node: Node): Map<String, String> {
        val raw = node.literals[CAST_RENAMES]?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return raw.split(',').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.take(i).trim() to pair.substring(i + 1).trim()
        }.toMap()
    }

    /** How [renames] is stored — one place, so writing and reading it cannot disagree. */
    fun castRenamesOf(renames: Map<String, String>): String =
        renames.entries.joinToString(",") { "${it.key}=${it.value}" }

    /**
     * Which renames the author wrote in QUOTES — `as Doc { itemCount: "item_count" }`.
     *
     * Metadata, and the compiler never reads it: the mapping is identical either way. It exists so the
     * printer can give back the spelling that was written, which the round-trip contract requires and
     * which cannot be derived — `item_count` is a perfectly legal bare name, so "quote it when it has to
     * be quoted" would silently rewrite the source form.
     *
     * The same comma-joined shape [castRenamesOf] uses, so a document does not grow a third way to write
     * a small collection.
     */
    const val CAST_QUOTED = "@quoted"

    /** Was the rename filling [field] written as a quoted JSON key? */
    fun castKeyQuoted(node: Node, field: String): Boolean =
        node.literals[CAST_QUOTED]?.toString()
            ?.splitToSequence(',')
            ?.any { it.trim().equals(field, ignoreCase = true) } == true

    /**
     * The characters a quoted JSON key may not contain.
     *
     * [castRenamesOf] joins on `,` and splits each pair on the first `=`, so a key holding either would
     * come back as a different key — or as two. Refused at the source, where the author can see it, rather
     * than corrupting the literal and surfacing as a missing key at run time.
     */
    fun castKeyProblem(key: String): String? = when {
        key.isEmpty() -> "a JSON key cannot be empty"
        key.contains(',') -> "a JSON key here cannot contain a comma"
        key.contains('=') -> "a JSON key here cannot contain an '='"
        else -> null
    }

    /**
     * Write a new value into a local — the `x = …` half of `var x = …`.
     *
     * **A [HOLD] already owns a register for the life of its chunk**, and a chunk is one call's frame. So
     * assignment needs no new storage concept at all: it evaluates into the register the Hold owns, and
     * every later read of the name is the same register read it always was. Per-frame is what makes it
     * right where a graph variable was wrong — two calls, two frames, two registers, and recursion works.
     */
    const val LOCAL_SET = "value.setLocal"

    /**
     * On a [LOCAL_SET]: the node id of the [HOLD] it writes into.
     *
     * The id rather than the name, because names nest: `if a { var x = 1 } else { var x = 2 }` has two
     * holds called `x`, and only the one in scope at the assignment is the one meant. Lowering knows
     * exactly which — it has the scope stack — so it records the answer instead of leaving the compiler to
     * re-derive it from information it does not have. The name rides along in `Node.variable` for the
     * printer, which needs to write it back and cannot read a register.
     */
    const val LOCAL_TARGET = "@local"

    const val LITERAL = "value.literal"
    const val LITERAL_INT = "value.int"
    const val LITERAL_FLOAT = "value.float"
    const val LITERAL_STRING = "value.string"
    const val LITERAL_BOOL = "value.bool"
    const val LITERAL_ITEM = "value.item"
    const val LITERAL_NPC = "value.npc"
    const val LITERAL_OBJECT = "value.object"
    const val LITERAL_SKILL = "value.skill"
    const val LITERAL_TILE = "value.tile"
    const val LITERAL_COLOR = "value.color"

    /** Every node that is just a value. The compiler lowers them all the same way. */
    /**
     * A `const`/folded `val`'s name, and whether it said `export`, both on its own literal node.
     *
     * Here rather than beside the rest of `Lower`'s keys because the EXPORT SET has to read them: a const
     * is the one exportable thing with no record on the document, so "what does this document offer" would
     * otherwise have to reach up into the lang package to find out. `Lower` re-exports both names, so every
     * existing reader is unmoved.
     */
    /**
     * The prefix of a synthesised field-default function — `@default:Point.y`.
     *
     * A field default that does not fold to a literal becomes a real zero-argument function in the
     * document that declares the record, and a literal omitting that field is wired from a Call to it.
     * Which buys four things for nothing: whether the record literal is a STEP falls out of the purity
     * derivation already in place, the default may name the declaring document's own unexported helpers
     * because it lives there, it crosses an import like any other function, and the document format
     * already persists functions.
     *
     * `@`-prefixed so no author can type one, and named after the field so two defaults on one record do
     * not collide. Exported when the record is, or an importer omitting the field could not call it.
     */
    const val FIELD_DEFAULT = "@default:"

    fun fieldDefaultName(type: String, field: String): String = "$FIELD_DEFAULT$type.$field"

    const val CONST_NAME = "@name"

    const val CONST_EXPORTED = "@exported"

    const val CONST_TYPE = "@declaredType"

    val LITERALS = setOf(
        LITERAL, LITERAL_INT, LITERAL_FLOAT, LITERAL_STRING, LITERAL_BOOL,
        LITERAL_ITEM, LITERAL_NPC, LITERAL_OBJECT, LITERAL_SKILL, LITERAL_TILE, LITERAL_COLOR,
    )

    /**
     * A list written out by hand — the several ids a Drop Any wants, the tiles of a patrol route.
     *
     * Deliberately NOT in [LITERALS]: those lower to one `CONST`, and a list must not. A constant is one
     * object handed out at every evaluation, so a graph that ever appended to it would be mutating a value
     * the whole chunk shares. This builds a fresh list each time (`NEWLIST` + `APPEND`), which is what those
     * opcodes are for.
     */
    const val LITERAL_LIST = "value.list"

    /** The pin naming what the list holds — an [ELEMENT_TYPES] name. Its element pins follow. */
    const val LIST_OF = "Of"

    /** The pin saying how many slots to draw. */
    const val LIST_COUNT = "Count"

    /**
     * Ceiling on slots.
     *
     * Not a storage limit — a guard on the *editor*. Count is a typed number, and a slipped keypress
     * turning 12 into 12000 would otherwise generate twelve thousand pins and take the canvas down with it.
     */
    const val LIST_MAX = 64

    /**
     * What a list can hold — asked of [Types], never listed here.
     *
     * A registered type that an author can name becomes a list element type by existing, which is what
     * lets a user-defined type work everywhere without this file learning about it. Named, not written:
     * a list of a host's wire-only records is built by the graph, slot by slot, and is a list all the same.
     */
    val elementTypes: List<TypeInfo> get() = Types.declarable

    /**
     * The [LIST_OF] literal's value -> the type it names.
     *
     * Unknown or absent reads as Int, so a half-made node — or one naming a type that has gone away — still
     * draws instead of taking the canvas down with it.
     */
    /**
     * What a list holds, from the name on its `Of` pin.
     *
     * Built-ins resolve through [Types], which is where their editors and pickers come from. A name it
     * does not know is a DECLARED type — a record the document (or one it imports) named — and comes back
     * as itself rather than collapsing to INT. It used to collapse, so a list of records typed every one
     * of its own element pins as INT and then refused the records being put in them.
     */
    fun listElementType(name: String?): TypeRef {
        Types.of(name)?.type?.let { return it }
        val clean = name?.trim().orEmpty()
        return if (clean.isEmpty()) TypeRef(PinType.INT) else TypeRef.named(clean)
    }
    /**
     * Anything, as text.
     *
     * Exists so the conversion is a THING ON THE CANVAS rather than something the wires do quietly. Plenty
     * of string pins are not display text — an Interact Object's Action must match a menu entry, a Drop Any's
     * Items is an id list — so a wire that silently stringified whatever it was given would turn a mis-wiring
     * the validator catches into a script that runs and does nothing. It is also the only sensible home for
     * formatting (decimal places, how a tile reads) when that is wanted.
     */
    const val TO_TEXT = "value.toText"

    /**
     * The inverse of [TO_TEXT]: text back into a number, or nothing when it is not one.
     *
     * **The game hands out numbers as text and there was no way back.** A widget's contents are a STRING —
     * the run orb's energy, a shop's stock, a countdown, a score — and every one of those is a number a
     * script wants to compare. `Numbers Heard` reads the CHAT LOG rather than a string you hold, and
     * `To Int` narrows a FLOAT, so neither closes the gap.
     *
     * Optional rather than zero-on-failure, because "the text was not a number" and "the number was zero"
     * are different facts and a script branching on energy must not confuse them. Pair it with `?:`.
     */
    const val PARSE_INT = "value.parseInt"

    /** [PARSE_INT] for a fractional number. Same rules, same optional answer. */
    const val PARSE_FLOAT = "value.parseFloat"

    // ---- text ------------------------------------------------------------------------------------------
    //
    // **The language had no string library at all.** It could build text (`+`, [FORMAT]) and read a number
    // out of a whole string ([PARSE_INT]), and nothing in between — no length, no index, no slice, no
    // split, no trim. So a script holding `"Golovanova seed (level 34)"` could test that it contained
    // "level" and then had nowhere to go: [PARSE_INT] refuses anything with characters left over, which is
    // the right rule and leaves the number unreachable.
    //
    // These live here rather than in the SDK for the reason every builtin does: they touch no avatar and
    // know nothing about a game, so a graph using them runs anywhere the VM does.

    /** How many characters. */
    const val TEXT_LENGTH = "text.length"

    /** Where [TEXT_INDEX_OF]'s needle first appears, or -1. */
    const val TEXT_INDEX_OF = "text.positionOf"

    /** Where it appears LAST, or -1 — the tail-end twin of [TEXT_INDEX_OF]. */
    const val TEXT_LAST_INDEX_OF = "text.lastPositionOf"

    /** Characters `From` up to `To`, clamped rather than thrown. */
    const val TEXT_SLICE = "text.slice"

    /**
     * The text between two markers.
     *
     * The one that makes the common job a single node instead of four. Pulling `34` out of
     * `"Golovanova seed (level 34)"` is `between(after: "level ", before: ")")` and then [PARSE_INT] —
     * rather than index, index, slice, trim, and an off-by-one at each step.
     */
    const val TEXT_BETWEEN = "text.between"

    /** Surrounding whitespace off. */
    const val TEXT_TRIM = "text.trim"

    /** Every occurrence swapped. */
    const val TEXT_REPLACE = "text.replace"

    /** Cut on a separator into a list of pieces. */
    const val TEXT_SPLIT = "text.splitOn"

    /** The way back from [TEXT_SPLIT] — a list of pieces into one string. */
    const val TEXT_JOIN = "text.joinWith"

    /** Does it begin with this? */
    const val TEXT_STARTS_WITH = "text.startsWith"

    /** Does it end with this? */
    const val TEXT_ENDS_WITH = "text.endsWith"

    /** Case, both ways. */
    const val TEXT_UPPER = "text.upper"
    const val TEXT_LOWER = "text.lower"

    /**
     * The first whole number ANYWHERE in the text, ignoring everything around it.
     *
     * [PARSE_INT]'s forgiving sibling, and deliberately a separate node rather than a flag: "this string IS
     * a number" and "this string CONTAINS a number" are different questions, and quietly widening the first
     * into the second would make `"12 of 20"` read as 12 for every existing caller.
     */
    const val TEXT_NUMBER_IN = "text.numberIn"

    /**
     * A message with holes in it. The holes are its pins — see [Templates].
     *
     * Its shape comes from the TEXT TYPED INTO IT rather than from this catalog, which is the same trick a
     * function's Call node plays with its signature, and for the same reason: what the pins should be is a
     * property of the document, not of the node type.
     */
    const val FORMAT = "value.format"

    /** Wall-clock milliseconds. Two of these subtracted is how long something took. */
    const val NOW = "value.now"

    /**
     * The **local calendar**, which is a different question from [NOW] and cannot be derived from it.
     *
     * Milliseconds answer "how long since"; these answer "when is it" — and a script that plays like a
     * person needs the second one: a daily budget resets at midnight, a bedtime is an hour, a weekend is
     * a day of the week. Deriving any of that from an epoch count means reimplementing a calendar in the
     * script, including its timezone and its daylight saving.
     */
    const val LOCAL_DATE = "value.localDate"
    const val HOUR_OF_DAY = "value.hourOfDay"
    const val MINUTE_OF_HOUR = "value.minuteOfHour"
    const val DAY_OF_WEEK = "value.dayOfWeek"
    const val MS_UNTIL_LOCAL = "value.msUntilLocal"

    /**
     * **Stop, with a reason** — the expression that never hands anything back.
     *
     * It exists for one shape, `x ?: error("…")`, and that shape is what turns an optional into a plain
     * value without inventing a fallback that means nothing. Its output is a WILDCARD because there is no
     * value to type: control does not reach the wire. `?:` already narrows — `a ?: b` is `a`'s type with
     * the `?` taken off — so the whole feature was somewhere to put the right-hand side, not a new rule.
     *
     * **Pure, so it may sit inside an expression**, which is where the need is: a record literal filling a
     * non-optional field from a lookup that might miss. `?:` compiles to a jump, so the fallback is reached
     * only when the value really was absent; a pure node that is never reached costs nothing.
     *
     * **Not an exception** — nothing catches it. It throws a `VmError`, which the interpreter turns into a
     * failed fiber carrying the chunk, the pc, the node id and a stack trace, and which a debugger session
     * PAUSES on at the faulting instruction with the frames still standing. For a script that drives a
     * game client that is the useful behaviour: the alternative to a wrong value is not a caught exception,
     * it is stopping where the wrongness is.
     */
    const val FAIL = "value.error"

    /**
     * `error(…)` **as a statement** — the same stop, on the exec chain.
     *
     * [FAIL] is pure, which is what lets it sit inside an expression, and a pure node in statement position
     * computes a value nobody reads and never runs. So the statement form is its own node: exec IN and no
     * exec OUT, exactly like [RETURN], because control does not continue past it either.
     *
     * It has no spelling of its own — see `Names.SYNTACTIC`. Both nodes are written `error(…)` and the
     * lowering picks by POSITION, which is the only thing that distinguishes them; a reader should never
     * learn there are two.
     */
    const val FAIL_STEP = "flow.error"

    // ---- lists ----
    /** How many items. Lowers to [Op.LEN]. */
    const val LIST_COUNT_OF = "list.count"

    /**
     * The item at an index. Lowers to [Op.INDEX], and THROWS when the index is past the end.
     *
     * Deliberately different from [LIST_FIRST]. Naming an index is a claim about what is in the list, so
     * being wrong about it is a mistake worth stopping on — whereas "whatever is nearest" has an obvious
     * and common answer when there is nothing: nothing.
     */
    const val LIST_AT = "list.at"
    const val LIST_SET = "list.set"

    /** A copy of the list with one more item on the end. */
    const val LIST_ADD = "list.add"

    /**
     * The MUTATING trio — they edit the list they are given and hand nothing back.
     *
     * The list nodes above are all [NodeKind.PURE] and copy, and the reason is written on [LIST_ADD]: a pure
     * node that quietly mutated its input would change what every reader sees with nothing in the flow to
     * show for it. These are [NodeKind.IMPURE] and sit on an exec wire, so there IS something in the flow to
     * show for it — the objection is to a silent edit, not to editing.
     *
     * They are also what an accumulator actually wants. `_listAdd` has always done this on the text side
     * (`Op.APPEND` writes in place); the canvas simply had no way to say it, so a graph that gathered
     * things had to copy the whole list once per item.
     */
    const val LIST_APPEND = "list.append"
    const val LIST_CLEAR = "list.clear"
    const val LIST_DELETE_AT = "list.deleteAt"

    /** The first item, or nothing when the list is empty. */
    const val LIST_FIRST = "list.first"

    /** True when there is nothing in the list. */
    const val LIST_IS_EMPTY = "list.isEmpty"

    /** True when the list holds this value. */
    const val LIST_CONTAINS = "list.contains"

    /**
     * Where a value is, or **-1** when it is not there at all.
     *
     * -1 rather than nothing, because the answer is an index and every other index in this language is an
     * INT — an Item pin that sometimes held nothing would be a second thing to check before every use. The
     * sentinel is the one an author already writes (`var best = -1` appears in the corpus), so this makes
     * the existing idiom the language's own rather than inventing a rival to it.
     */
    const val LIST_INDEX_OF = "list.indexOf"

    /** Both lists, end to end — the join `withItemAdded` in a loop was standing in for. */
    const val LIST_CONCAT = "list.concat"

    /** The same items, last first. */
    const val LIST_REVERSED = "list.reversed"

    /**
     * The first [n] items, and everything after them.
     *
     * Both clamp instead of failing: asking for more than there is gives what there is, which is what makes
     * them usable on a list whose length you have not checked. That is deliberately unlike [LIST_AT], where
     * naming a position IS a claim about the contents — see its note.
     */
    const val LIST_TAKE = "list.take"
    const val LIST_DROP = "list.drop"

    /** Everything added together. Nothing sums to 0. */
    const val LIST_SUM = "list.sum"

    /**
     * The smallest / largest, or nothing when the list is empty — same answer-for-empty rule as [LIST_FIRST].
     *
     * **Not `list.min`**, though that is what they are. A text name is the type's last segment wherever that
     * is unique ([Names]), so `list.min` beside [MIN] would make BOTH of them ambiguous and force `math.min`
     * and `list.min` to be written out in full — a new node changing the spelling of an existing one, which
     * is the one thing adding to this catalogue must never do. Named apart at the type so the two cannot
     * compete, and "smallest" says the argument is a whole list rather than two numbers anyway.
     */
    const val LIST_SMALLEST = "list.smallest"
    const val LIST_LARGEST = "list.largest"

    /**
     * A copy without the FIRST item equal to this one, and a copy without the one at an index.
     *
     * Two nodes rather than one that guesses, for the reason [LIST_AT] and [LIST_FIRST] are two: "drop the
     * one I am holding" and "drop position 3" are different questions and only one of them has an obvious
     * answer when it does not apply. Removing by value when the value is absent is a no-op; removing an
     * index outside the list is a no-op, matching [LIST_SET].
     *
     * The FIRST match, not every match — a ledger that removes one entry and silently removed three is the
     * kind of bug parallel lists already make easy enough to write.
     */
    const val LIST_REMOVE = "list.remove"
    const val LIST_REMOVE_AT = "list.removeAt"

    /**
     * This list, ordered by a PARALLEL list of keys rather than by a comparator.
     *
     * A comparator would need a function value, which does not exist yet — but keys are also the better
     * answer here regardless, and `core/objects.byDistance` is the proof: it already measures each distance
     * ONCE into a parallel list "which is the difference between n host calls and n²". Sorting in the host
     * additionally means one call rather than a pure-node swap that copies the whole list each time, and no
     * risk of the fiber starving, which the hand-rolled selection sort's docstring warns about.
     *
     * Ascending, stable, and short keys leave their items where they are.
     */
    const val LIST_SORTED_BY = "list.sortedBy"

    /**
     * The whole numbers from one up to (**not** including) another — what a counted loop iterates.
     *
     * `for i in range(0, 16)` is the counted `for` the language does not otherwise have; LANGUAGE.md §5
     * says "there is no counted `for`, use `while` with a `var`", and this is what retires that advice.
     *
     * Half-open because `count(list: xs)` is the natural second argument and `range(0, count)` must be
     * exactly the valid indices — an inclusive end would make the commonest use the one needing `- 1`.
     * A backwards or empty range is empty rather than an error, which is what makes `range(0, count)` safe
     * on a list that turned out to be empty.
     *
     * Filed beside [LITERAL_LIST] rather than with the list verbs: like that one it MAKES a list, where
     * everything in `list.*` takes one apart.
     */
    const val RANGE = "value.range"

    /**
     * A knot on a wire: one value in, the same value out.
     *
     * Compiles to NOTHING — the compiler reads straight through it to whatever feeds it, so a graph means
     * exactly the same thing with or without one. That is what makes it safe for the formatter to create
     * and destroy them freely, which it does: they are laid out onto lanes so a long wire runs along a
     * clear track instead of disappearing behind three nodes on its way across the canvas.
     *
     * **Formatter-owned.** Not in the palette, and Arrange removes every one before deciding where knots
     * are needed this time. Hand-placed and generated ones would be indistinguishable, so re-arranging
     * would either accumulate knots forever or throw away yours — and there is no third option that does
     * not require telling them apart.
     *
     * Data only. An exec reroute is a different thing: it would have to be a real step in the chain, and
     * the exec spine is laid out adjacently anyway, so its wires are short by construction.
     */
    const val REROUTE = "value.reroute"

    const val COMMENT = "comment.box"

    // ---- maps ----
    /**
     * A lookup from one value to another. **A VALUE**, like a list and like a record.
     *
     * The obvious worry about a value-map is that it copies on every write, which is the O(n²) trap the
     * accumulator loop already demonstrated for lists. The answer is the one lists got: `AppendPass` proves
     * nobody else is holding the old map and the compiler emits an in-place [Op.SETKEY]. Value semantics in
     * the language, in-place writes in the compiler — the model stays uniform and the cost is not paid.
     *
     * The alternative, a reference map, is worse than it looks: it would make aliasing observable for the
     * first time in this language. Two names on one map with one of them writing through is a hazard
     * nothing else here can produce, and there would be no rule anywhere else to reason about it by.
     */
    /**
     * A function, named but not called — `map(list: xs, using: double)` passes `double` itself.
     *
     * A literal node, near enough: its [FUNCTION_REF_NAME] literal holds the name and there is nothing to
     * evaluate, because a function value is an index into the linked program and the linker already knows
     * it. There is no closure and there is nowhere for one to hide — the thing named is a top-level
     * function, so there is no environment to capture.
     */
    const val FUNCTION_REF = "value.function"

    /** The literal on a [FUNCTION_REF]: which function. */
    const val FUNCTION_REF_NAME = "Function"

    /**
     * On a [FUNCTION_REF]: the names this lambda closed over, comma-joined — see [capturesOf].
     *
     * A lambda may read the locals of the body it was written in, and those cannot be reached from inside
     * the callee's frame. So they become trailing PARAMETERS of the synthesised function, and this says
     * which — one input pin per name on the reference node, wired from the local it came from, and the
     * values copied into the function value when it is built (`Op.CLOSURE`).
     *
     * A string rather than a list because it round-trips through JSON with no new encoding, and the
     * `@` prefix is the metadata channel that already survives a save.
     */
    const val CAPTURES = "@captures"

    /**
     * Call the function a WIRE carries, as a STATEMENT.
     *
     * The node the language was missing. A function value could be made and passed but never invoked
     * except by `mapped`/`filtered`/`firstWhere`, which are pure and therefore refuse anything that acts —
     * so a table of handlers could be built and not used. This has exec pins, so it sits on the chain like
     * any other step, and it compiles to `Op.CALLV`, which the VM has had since function references
     * landed. No opcode, no VM change: what was missing was somewhere to put the call.
     *
     * Its arguments are POSITIONAL, and that is honest rather than a shortcut — a function TYPE carries
     * the shape of its parameters and not their names, so at the point of invoking a value there are no
     * names to use. [INVOKE_COUNT] says how many were written, exactly as a list literal's slot count does.
     */
    const val INVOKE = "function.invoke"

    /** The pin carrying the function to call. */
    const val INVOKE_FN = "Function"

    /** How many arguments were written — the shape pin, like [LIST_COUNT]. */
    const val INVOKE_COUNT = "@args"

    /**
     * On an [INVOKE]: it was written as `invoke(f, …)` rather than as `f(…)`.
     *
     * The fourth printer-only marker, and it exists for the reason the others do — two spellings make one
     * graph, so something has to say which was typed. Absent, the printer picks the shorter form when the
     * function pin is fed by a plain NAME and the longer one when it is fed by an expression, which is
     * also what a canvas-built node should read as.
     */
    const val INVOKE_WRITTEN = "@invoke"

    /** The nth argument pin, counting from 1. */
    fun invokeArg(i: Int): String = "Arg$i"

    fun invokeArgs(node: Node): Int = (node.literals[INVOKE_COUNT] as? Number)?.toInt() ?: 0

    /** The names a [FUNCTION_REF] closed over, in the order the function's trailing parameters expect. */
    fun capturesOf(node: Node): List<String> =
        (node.literals[CAPTURES] as? String)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    /**
     * Which of a call's function pins were given a lambda written AT THE ARGUMENT rather than trailing.
     *
     * **The one thing the printer cannot work out for itself.** `filter(keeping: { it > 3 })` and
     * `filter { it > 3 }` build the identical graph, so without this the printer would have to guess, and
     * it guesses trailing — which silently rewrites the first spelling into the second the next time the
     * document is written. Stored on the CALL, not on the reference, because it is a fact about how this
     * call was written and not about the function value.
     *
     * `@`-prefixed for the reason [CAPTURES] is: an author writes identifiers, and `@` is not one, so this
     * cannot collide with a real pin name.
     */
    const val INLINE_ARGS = "@inlineArgs"

    /** The pins of [node] whose lambda was written in the argument list — see [INLINE_ARGS]. */
    fun inlineArgsOf(node: Node): Set<String> =
        (node.literals[INLINE_ARGS] as? String)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /**
     * A synthesised function — one made from a lambda rather than declared.
     *
     * The `@` is what makes the name unforgeable: an author writes an identifier, and `@` is not one. So
     * the printer can tell a lambda from a named function by its NAME, with nothing stored to say so, and
     * a document that has been hand-edited into naming one cannot happen.
     */
    fun isAnonymous(name: String?): Boolean = name != null && name.startsWith("@")

    const val LIST_MAP = "list.map"
    const val LIST_FILTER = "list.filter"
    const val LIST_FIRST_WHERE = "list.firstWhere"

    /**
     * The nodes whose pins are decided by a FUNCTION — either the one they name, or the one wired in.
     *
     * Same reason [MAP_SHAPED] exists and same trap avoided: these pins are typed `LIST` and `fn`, never
     * wildcard, so a rule for them in `effectivePinType` could not fire.
     */
    val FN_SHAPED: Set<String> = setOf(FUNCTION_REF, LIST_MAP, LIST_FILTER, LIST_FIRST_WHERE)

    /**
     * The list verbs that hand back one ELEMENT and may find none — so their answer is `T?`.
     *
     * Each says so in its own summary and has always been able to return null; what was missing was the
     * `?` in the type. [LIST_AT] is deliberately absent: it stops the script when there is no such
     * position, which is what makes it the spelling for "I know it is there".
     */
    val EMPTY_HANDED: Set<String> = setOf(LIST_FIRST, LIST_SMALLEST, LIST_LARGEST)

    /**
     * What each higher-order list node calls its function pin.
     *
     * Three names rather than one, because the pin is read at the call site and `filtered(list: xs,
     * keeping: reachable)` says what it does where `using:` would not. The shape code takes the name from
     * here rather than assuming it.
     */
    val FN_PIN: Map<String, String> = mapOf(
        LIST_MAP to "Using",
        LIST_FILTER to "Keeping",
        LIST_FIRST_WHERE to "Matching",
    )

    const val MAP_OF = "map.of"

    /** A COPY with one entry set. The accumulator shape `m = withEntry(map: m, …)` is what [Op.SETKEY] serves. */
    const val MAP_WITH = "map.with"

    /** A COPY with one key taken out. */
    const val MAP_WITHOUT = "map.without"

    /** What a key maps to — **optional**, because a key that is not there is the ordinary case. */
    const val MAP_AT = "map.at"

    /** Whether a key is there at all. The question to ask when the VALUE may legitimately be nothing. */
    const val MAP_HAS = "map.has"

    /** How many entries. */
    const val MAP_SIZE = "map.size"

    /** Every key, in insertion order — so a map can be walked with an ordinary `for`. */
    const val MAP_KEYS = "map.keys"

    /** Every value, in the same order as [MAP_KEYS]. */
    const val MAP_VALUES = "map.values"

    /**
     * `for (k, v) in m` — one pass per entry, in insertion order.
     *
     * A node of its own rather than sugar over `keysOf` + `valueAt`, for two reasons. That pairing looks
     * each key up twice — once to hand it out and once to find its value, and a map lookup is a scan — and
     * a desugaring would need the printer to recognise one specific inner Hold shape to give the word back,
     * which is brittle in a way a node is not. It compiles to the list iterator that already exists, over
     * the entry pairs, so it costs one host and no opcode.
     */
    const val MAP_FOR_EACH = "flow.forEachEntry"

    /** Every entry as a two-item list, in [MAP_KEYS] order — what [MAP_FOR_EACH] iterates. */
    const val MAP_ENTRIES = "map.entries"

    /**
     * Every node a `break` or a `continue` can be inside.
     *
     * A set rather than a chain of `==`, because three passes have to agree on it — the validator
     * asking whether a jump has a loop, `AppendPass` asking where a body rejoins, and `Names`
     * keeping the node out of the callable vocabulary. A loop added to one and not the others is a
     * `break` reported as being outside any loop while the compiler happily emits the jump.
     */
    val LOOPS: Set<String> = setOf(WHILE, FOR_EACH, MAP_FOR_EACH)

    /** The map nodes whose pin types come from the map wired into them — see `resolveNode`. */
    val MAP_SHAPED: Set<String> =
        setOf(MAP_WITH, MAP_WITHOUT, MAP_AT, MAP_KEYS, MAP_VALUES, MAP_FOR_EACH)

    /** The pin every map node takes its map on. */
    const val MAP_PIN = "Map"
    const val MAP_KEY_PIN = "Key"
    const val MAP_VALUE_PIN = "Value"

    // ---- JSON and files ----
    //
    // **There is no `json.decode` node.** Decoding is spelled `as`, and it is the SAME node the record cast
    // has always been: [CAST] looks at what feeds its Value pin, and a `Json` there means "read this
    // document as that record" where a record there means "read these fields as those". One node, one
    // spelling, one recognizer in `Print` — the alternative was a second way to name a type at a use site
    // and a second `{ … }` clause that looked identical and meant something else.
    //
    // The file verbs are IMPURE for a reason that is easy to get wrong: a pure node is re-expanded at every
    // use site, so a pure `readText` wired to three pins would read the file three times, at three moments,
    // and could hand back three different answers. `fileExists` stays pure on purpose — it is a live read,
    // like every other query, and "is it there NOW" is exactly the question that should be re-asked.

    /** Text → [Json]. The parse alone; `as` is what turns the result into a record. */
    const val JSON_PARSE = "json.parse"

    /** Any value → JSON text. Needs no schema: a record already carries its own field names. */
    const val JSON_TEXT = "json.text"

    /** Pretty-print or not, on [JSON_TEXT] and [FILE_WRITE_JSON]. */
    const val JSON_PRETTY = "Pretty"

    /** Read a file and parse it in one step — the shape almost every use has. */
    const val FILE_READ_JSON = "file.readJson"

    /** Serialise and write in one step. */
    const val FILE_WRITE_JSON = "file.writeJson"

    const val FILE_READ_TEXT = "file.readText"
    const val FILE_WRITE_TEXT = "file.writeText"
    const val FILE_EXISTS = "file.exists"
    const val FILE_DELETE = "file.delete"
    const val FILE_LIST = "file.list"

    /**
     * The FOLDERS in a folder — what makes the data folder organisable rather than one flat heap.
     *
     * Its own verb rather than a flag on [FILE_LIST], so neither answer needs unpacking: a script asking
     * "what runs have I saved" wants folder names and one asking "what is in this run" wants file names,
     * and a mixed list would make both of them test each name to find out which it got.
     */
    const val FILE_FOLDERS = "file.folders"

    /** The pin every file node takes its path on. */
    const val FILE_PATH = "Path"

    /**
     * The nodes whose output is a [Json] document, so a [CAST] fed by one decodes instead of reading fields.
     *
     * A set rather than a type comparison at the use site because three passes ask the question — the
     * validator deciding which check to run, the compiler deciding which code to emit, and `resolveNode`
     * deciding what the cast's Value pin carries — and a fourth JSON source added to one and not the others
     * is a cast that validates one way and compiles the other.
     */
    val JSON_SOURCES: Set<String> = setOf(JSON_PARSE, FILE_READ_JSON)

    // ---- user functions ----
    /** The container box that DEFINES a function. Names it via [Node.function]. */
    const val FUNCTION = "function.box"
    /** Inside a body: supplies the parameters. Its outputs come from the signature, not the catalog. */
    const val FUNCTION_ENTRY = "function.in"
    /** Inside a body: hands the results back. Its inputs come from the signature. */
    const val FUNCTION_RETURN = "function.out"
    /** A call site. Its pins come from the signature of the function named by [Node.callee]. */
    const val CALL = "function.call"

    // ---- declared types ----
    /** Builds one of a declared type: its fields are the node's inputs. */
    const val STRUCT_MAKE = "struct.make"

    /**
     * Takes one apart: its fields are the node's outputs.
     *
     * Called SPLIT, not Break. Blueprints calls it Break and that would have been the familiar choice, but
     * "break" means leave the loop in every language an author of these has met, and this graph language
     * has loops and no such node YET — which is the worst possible time to spend the word. Split says the
     * same thing about a record and claims nothing about control flow.
     */
    const val STRUCT_SPLIT = "struct.split"

    /**
     * Reads ONE field. Split hands back all of them; this is for when you want one.
     *
     * Not redundant with Split, and the difference is what shows on the canvas: a Split wired to one of six
     * outputs says "this graph takes the record apart" when it does no such thing. A Get says which field
     * it wanted, in its own title.
     */
    const val STRUCT_GET = "struct.get"

    /**
     * Replaces one field, handing back a NEW record.
     *
     * Value semantics, so this cannot be "set" in the sense of writing through: the record on the wire in
     * is unchanged, and what comes out is a copy with one field different. That is the only meaning that
     * works here — a pure node is re-expanded at every use site, so anything that wrote in place would
     * change the value everywhere it had ever been read.
     */
    const val STRUCT_SET = "struct.set"

    /**
     * One member of a declared enum, named — the canvas spelling of `Phase.Chop`.
     *
     * **Why the node has to exist.** A text construct with no node cannot round-trip, which is the rule in
     * VSCRIPT_LANG_PLAN.md §1 and the one that killed several earlier ideas in §6.7. Without this, `enum`
     * would be text-only and the two surfaces would stop being one language. It is also what makes the round
     * trip need no marker annotation: `Phase.Chop` lowers to THIS node, and a node says which enum and which
     * member on its own pins, so the printer reads the spelling back off the graph rather than off a hint
     * left beside it. Compare [WROTE_FIELD], which a marker was needed for precisely because the two
     * spellings there lower to the same graph. These do not.
     *
     * PURE, and it compiles to a single constant: a member is a name, so there is nothing to evaluate.
     */
    const val ENUM_OF = "enum.of"

    /**
     * Every member of an enum, in declaration order — `Phase.values()`.
     *
     * PURE, and it compiles to a single constant list: a member is its NAME at run time, so the answer is
     * the member list itself and there is nothing to evaluate. The output is a `LIST<E>` rather than a
     * `LIST<STRING>`, which is what lets `for p in Phase.values()` give a `Phase` and read its columns.
     *
     * Written out by hand until this existed: `gotr-ids` lists all twelve altars in a `var All` purely to
     * be able to walk them, and says why — "an enum's members cannot be iterated ... a member is a
     * literal".
     */
    const val ENUM_VALUES = "enum.values"

    /** The pin naming WHICH enum. Its member choices follow, so it is a shape pin. */
    const val ENUM_TYPE = "Of"

    /** The pin naming which MEMBER. Decides the value, and its options come from the enum. */
    const val ENUM_MEMBER = "Member"

    /**
     * One column of an enum's table, read off a member — `t.anchor`.
     *
     * Shaped exactly like [STRUCT_GET] and for the same reason: which enum and which field are both
     * properties of the DOCUMENT's declaration, so they are shape pins and the output carries the field's
     * own declared type. It reuses that node's pin names ([ENUM_TYPE] is `"Of"`, [STRUCT_FIELD] is
     * `"Field"`) because they are the same two questions.
     *
     * **It does not fold to a constant even when the member is known.** `Target.WildKebbit.anchor` could be
     * worked out at compile time, and folding it would print back as the tile rather than as what was
     * written — the round-trip rule. One node covers both, and the dynamic case is the one that matters
     * anyway: it is what turns a nine-arm `when` into a table.
     *
     * Compiles to a host call over two constants — the member names and that field's column — so there is
     * no new opcode and no control flow. A member is a NAME at run time, so the lookup is "find the name,
     * take the same position", which is the invariant doing useful work rather than merely being preserved.
     */
    const val ENUM_FIELD = "enum.field"

    /** The pin naming which declared type a struct node is for. Its field pins follow. */
    const val STRUCT_OF = "Of"

    /** The pin naming WHICH field a Get or Set works on. Decides the value pin's type, so it is a shape pin. */
    const val STRUCT_FIELD = "Field"

    /** Every node type whose pins are decided by the document rather than by this catalog. */
    val SIGNATURE_SHAPED =
        setOf(
            FUNCTION, CALL, RETURN, FORMAT, LITERAL_LIST,
            // Its arms are decided by how many cases were written, like a list literal's slots.
            WHEN,
            STRUCT_MAKE, STRUCT_SPLIT, STRUCT_GET, STRUCT_SET,
            // Its member choices and its output type are both properties of the DOCUMENT's declaration.
            ENUM_OF,
            // And this one's field choices and output type likewise.
            ENUM_FIELD,
            // And this one's ELEMENT type is the enum it names.
            ENUM_VALUES,
            // Both take their shape from the DOCUMENT too: a cast's result is the type it names, and a
            // type test offers the records this document declares. `resolveNode` returns early for
            // anything not listed here, so leaving them out silently skipped both.
            CAST, IS_TYPE,
            // Its argument pins are counted from a literal and TYPED from the function wired into it —
            // both halves of what this branch does, in one node.
            INVOKE,
            // The map family takes its shape from the MAP WIRED INTO IT rather than from a literal or a
            // declaration — the first entry here that depends on the graph rather than on the node alone,
            // which is what `resolveNode`'s `feeding` probe is for. Left out, the branch below never runs
            // and every map pin silently stays a wildcard: `valueAt(map: tiles, key: 3)` on a
            // `MAP<TILE, INT>` compiles clean and goes wrong at run time, which is exactly the note above
            // about CAST and IS_TYPE, one phase later.
        ) + MAP_SHAPED + FN_SHAPED

    /** The nodes whose pins come from a declared type. */
    val STRUCT_SHAPED = setOf(STRUCT_MAKE, STRUCT_SPLIT, STRUCT_GET, STRUCT_SET)

    /**
     * Pins that decide a node's SHAPE, by node type.
     *
     * These are configuration, read at edit time to work out what the other pins are — so wiring one is
     * meaningless, and meaningless in the quiet way: the wire draws, the graph compiles, and the value is
     * simply never consulted. The validator rejects it instead. See [Validator].
     */
    val SHAPE_PINS: Map<String, Set<String>> = mapOf(
        FORMAT to setOf("Template"),
        LITERAL_LIST to setOf(LIST_OF, LIST_COUNT),
        WHEN to setOf(WHEN_COUNT),
        STRUCT_MAKE to setOf(STRUCT_OF),
        STRUCT_SPLIT to setOf(STRUCT_OF),
        STRUCT_GET to setOf(STRUCT_OF, STRUCT_FIELD),
        STRUCT_SET to setOf(STRUCT_OF, STRUCT_FIELD),
        // Both, and BOTH are typed in rather than wired: an enum member is a name chosen while editing.
        // Wiring one would draw and compile and then be ignored, which is what this set exists to refuse.
        ENUM_OF to setOf(ENUM_TYPE, ENUM_MEMBER),
        // Which enum and which column. The MEMBER is a wire here rather than a choice — that is the whole
        // difference between this node and Choice, and it is what makes a table readable from a value.
        ENUM_FIELD to setOf(ENUM_TYPE, STRUCT_FIELD),
        // Which enum, and nothing else: the answer is the whole member list.
        ENUM_VALUES to setOf(ENUM_TYPE),
        INVOKE to setOf(INVOKE_COUNT),
    )

    /**
     * Pins that are configuration but do NOT decide the node's shape.
     *
     * The same "typed in, never wired" rule [SHAPE_PINS] enforces, for the same reason — nothing reads the
     * pin at run time, so a wire into it would draw, compile, and be silently ignored — but arriving at it
     * from the other direction. A shape pin is read at edit time to work out what the other pins ARE; one of
     * these is read at edit time to work out what the node is CALLED. Kept apart from [SHAPE_PINS] rather
     * than folded in because the validator says something different and true about each, and one message
     * covering both would have to be vague enough to be unhelpful about either.
     */
    val CONFIG_PINS: Map<String, Set<String>> = mapOf(
        HOLD to setOf(HOLD_NAME),
        // Both are written, never wired: the type asked about is part of the QUESTION, and a wire into
        // it would be a question decided at run time by something the reader cannot see.
        IS_TYPE to setOf(IS_OF, IS_NOT),
    )

    const val LOG = "debug.log"

    /** Severity choices on the [LOG] node, and the values the host receives. */
    val LOG_LEVELS = listOf("info", "warn", "error")

    /** Arithmetic / comparison / logic operator types, mapped to opcodes by the compiler. */
    const val ADD = "math.add"
    const val SUB = "math.sub"
    const val MUL = "math.mul"
    const val DIV = "math.div"
    const val MOD = "math.mod"
    const val EQ = "compare.eq"
    const val NE = "compare.ne"
    const val LT = "compare.lt"
    const val LE = "compare.le"
    const val GT = "compare.gt"
    const val GE = "compare.ge"
    /** The five whose result is whatever their operands were — see `promote`. */
    val ARITHMETIC: Set<String> = setOf(ADD, SUB, MUL, DIV, MOD)

    const val NOT = "logic.not"
    const val AND = "logic.and"
    const val OR = "logic.or"

    /**
     * Numeric conversion — the language's casts.
     *
     * **Nodes rather than a `x as INT` syntax**, because the canvas is a co-equal surface: a node it can
     * drop costs nothing, where a syntax would need a canvas equivalent invented alongside it. They are
     * ordinary pure calls, so text writes `floor(x)` and the canvas draws a box, and neither had to learn
     * anything new.
     *
     * All four narrowing forms exist rather than one, because "turn this float into an int" has four
     * different right answers and picking one for the author is how a rounding bug gets written. [TO_INT]
     * is the `(int)` cast — toward zero, so it is [FLOOR] for positives and [CEIL] for negatives.
     */
    const val FLOOR = "math.floor"
    const val CEIL = "math.ceil"
    const val ROUND = "math.round"
    const val TO_INT = "math.toInt"

    /**
     * INT → FLOAT, and the reason it is not redundant: `a / b` on two INTs is INTEGER division.
     *
     * `toFloat(a) / b` is how you get 0.666 rather than 0, and that is a footgun worth a node of its own
     * even in a language that used to widen INT to FLOAT implicitly — the widening was about wires, and
     * never reached inside an arithmetic node.
     */
    const val TO_FLOAT = "math.toFloat"

    /**
     * The arithmetic a script keeps writing by hand.
     *
     * Every one of these was found hand-rolled in the corpus, which is the bar for adding a node here:
     * `core/math.vs` implements [ABS] as a multiply by -1, [SQRT] as a Newton-Raphson `while` loop and
     * [POW] as a repeated multiply that only accepts whole positive exponents. A loop in vs is not free —
     * it is bytecode the fiber has to be scheduled through — so each of these is both shorter to write and
     * cheaper to run as one host call.
     *
     * **[ABS], [MIN] and [MAX] keep the kind they were given**, like the five arithmetic operators: two
     * INTs give an INT. `sqrt` and `pow` are FLOAT both ways, because neither has an integer answer in
     * general and one that silently truncated would be worse than a cast the author can see.
     */
    const val ABS = "math.abs"
    const val MIN = "math.min"
    const val MAX = "math.max"
    const val SQRT = "math.sqrt"
    const val POW = "math.pow"

    /**
     * Trigonometry, in **radians** — and [PI] so the conversion need not be a magic number.
     *
     * `core/draw.vs` carries a sixteen-entry sine table with a note saying it exists "because the language
     * has neither sin nor cos… adding them would be the cleaner answer for the long run". This is that.
     *
     * Radians rather than degrees, though degrees would read better in a game script: it is the convention
     * every other language and every reference the author will reach for uses, and a `sin` that quietly
     * meant something else is a worse trap than one conversion. `pi()` is what makes the conversion
     * writable — `deg * pi() / 180.0`.
     */
    const val SIN = "math.sin"
    const val COS = "math.cos"
    const val PI = "math.pi"

    /**
     * One of two values, chosen by a condition — the expression form of a Branch.
     *
     * Unlike [AND] and [OR], this one DOES short-circuit: only the arm that is taken gets evaluated. It can,
     * because it has somewhere for the skipped work not to happen — a jump — which is exactly what those two
     * lack. Both arms being pure expressions, the only observable difference is cost, and there is no reason
     * to pay it.
     */
    const val SELECT = "logic.select"

    /**
     * `a ?: b` — [OR_ELSE_VALUE] unless it is nothing, in which case [OR_ELSE_FALLBACK].
     *
     * The other half of optionals, and the half that gets used far more often than `if val`: most places
     * that ask for a value that might be absent have an obvious answer for when it is, and want to say so
     * on one line rather than open a block.
     *
     * **Short-circuits, like [SELECT] and for the same reason** — the fallback sits behind a jump, so
     * `nearest() ?: expensiveDefault()` does not pay for the default it did not need.
     *
     * Its result is the value's type with the `?` taken off: `Tile?` in, `Tile` out. That is why this is a
     * node rather than a `Select` over `== null` — the narrowing lives on a NEW pin, which is the only way
     * a graph model can express it (see the note on [dev.ziggle.vscript.model.TypeRef.required]).
     */
    const val OR_ELSE = "value.orElse"

    /** The value that may be absent. */
    const val OR_ELSE_VALUE = "Value"

    /** What to use when it is. */
    const val OR_ELSE_FALLBACK = "Fallback"

    /**
     * `if val t = nearest() { … }` — run the Some arm with the value bound, or the None arm without it.
     *
     * **A [BRANCH] with one extra data output**, which is the entire design and the reason it is a node
     * rather than sugar over `!= null`. A type belongs to a PIN, and on the canvas the wire into a branch
     * is the same wire as the one outside it — so nothing can narrow `Tile?` to `Tile` by re-typing
     * something. Making a new pin is ordinary, and [IF_SOME_VALUE] is that pin.
     *
     * It compiles to a null test, a `JMPF` and a `MOVE`: no new opcode, and nothing the VM has to learn.
     */
    /**
     * `try { … } catch e { … }` — the language's exceptions.
     *
     * **No classes, so no hierarchy to match on.** What is caught is a STRING, the message, and a handler
     * catches everything reached from inside the body — including from several calls down, because the VM
     * records the FRAME DEPTH when it arms the handler ([dev.ziggle.vscript.vm.Op.PUSHH]) and unwinds to it.
     * That is the only reading of `try` anybody expects, and having no exception types is what makes it
     * simple enough to state in one sentence.
     *
     * Shaped exactly like [IF_SOME]: one exec in, two exec outs, and a value pin the second branch reads —
     * so the printer's bound-name machinery, the rejoin logic and the canvas all work on it unchanged.
     */
    const val TRY = "flow.try"

    /** The guarded body. */
    const val TRY_BODY = "Try"

    /** Where control lands when something under [TRY_BODY] raised. */
    const val TRY_CATCH = "Catch"

    /** The message that was raised, bound to the name the author wrote after `catch`. */
    const val TRY_ERROR = "Error"

    const val IF_SOME = "flow.ifSome"

    /** The value that may be absent. */
    const val IF_SOME_OPTION = "Option"

    /** Runs when it was there. */
    const val IF_SOME_THEN = "Some"

    /** Runs when it was not. Unwired is a perfectly good `if val` with no `else`. */
    const val IF_SOME_ELSE = "None"

    /** The value, with its `?` removed — see [dev.ziggle.vscript.model.TypeRef.required]. */
    const val IF_SOME_VALUE = "Value"

    /**
     * `a?.b` — [IF_PRESENT_THEN] when [IF_PRESENT_VALUE] is there, nothing when it is not.
     *
     * The expression form of [IF_SOME], and it exists for the reason that one cannot be used here: an
     * `if val` is a STATEMENT, and `a?.b` has to be writable in the middle of an expression, including
     * inside a function whose body is an expression and so has no exec chain at all.
     *
     * **[IF_PRESENT_IT] is what makes it work with one evaluation.** The access is not written against the
     * receiver — it is written against this node's own `It` pin, which the compiler binds to the register
     * the receiver was evaluated into. So `nearestObject()?.name` asks once, where the hand-written
     * `nearestObject() != null ? nearestObject().name : null` asks twice and may get two different answers.
     *
     * The guard covers ONE access. `a?.b.c` reads `.c` off something that may be nothing, and says so;
     * `a?.b?.c` is the chain.
     */
    const val IF_PRESENT = "value.ifPresent"

    /** The receiver, which may be absent. */
    const val IF_PRESENT_VALUE = "Value"

    /** The access, written against [IF_PRESENT_IT]. */
    const val IF_PRESENT_THEN = "Then"

    /** The receiver once it is known to be there — what the access is written against. */
    const val IF_PRESENT_IT = "It"

    /** Number of `Then` pins a Sequence node exposes. */
    const val SEQUENCE_PINS = 4

    /**
     * A Sequence's ARM pins, in order — everything it exposes except [SEQUENCE_DONE].
     *
     * A separate list rather than `descriptor.execOutputs`, because the completion pin is an exec output
     * too and every reader here means "the arms". Reading the descriptor treated `Completed` as a fifth
     * arm, which is precisely the confusion this exists to prevent.
     */
    val SEQUENCE_ARMS: List<String> = (0 until SEQUENCE_PINS).map { "Then$it" }

    /**
     * Where a Sequence CONTINUES once every arm has run.
     *
     * Without it the statement after a `sequence` had nowhere to attach: the lowering hung it off each
     * arm's open end instead, which duplicated it into every branch and — because the compiler emits the
     * arms consecutively — turned the second reference into a backward jump. The script ran forever.
     *
     * Spelled like a loop's, because it answers the same question: this is the pin control leaves by.
     */
    const val SEQUENCE_DONE = "Completed"

    /**
     * Take ONE of several paths — `when`.
     *
     * **Two forms, as Kotlin has.** With a subject, each case is a value and the arm taken is the first one
     * the subject EQUALS. Without one, each case is a condition and the arm taken is the first that is true.
     * They are told apart by whether [WHEN_SUBJECT] is wired, which is the same question the author answered
     * by writing a subject or not — so there is one word in the language rather than two that differ in a way
     * nobody would remember.
     *
     * **Why a node rather than a lowering.** Both forms compile to a chain of comparisons and jumps, and
     * lowering straight to Branch nodes would make the second form indistinguishable from a hand-written
     * `if`/`else if` — so a document would print back as the chain and the `when` would vanish on the first
     * round trip. That is exactly the recognizer collision VSCRIPT_LANG_PLAN.md §6.7 rejects sugar for, and
     * the reason `break` is a node and `as` is a node. Being its own node is also what lets the two forms
     * coexist with `if` at all.
     *
     * **Shaped like [LITERAL_LIST], branching like [SEQUENCE].** The case count is a shape pin, so a `when`
     * over a six-member enum gets six arms rather than being capped the way a Sequence is; each case is a
     * REAL PIN, which is what gets it its type's own editor and lets it be wired instead of typed.
     *
     * `Case1` pairs with `Then1`. Paired names rather than positional ones because a `when` with a hole in it
     * — a case wired, its arm not — is something a hand-edited file can contain, and the pairing makes that
     * legible instead of an off-by-one.
     *
     * Nothing matching and no `Else` wired runs nothing, which is the reading `if` already has with no
     * `else`. Exhaustiveness is asked about only where it can be answered: a subject whose type is a declared
     * enum — see `Validator.checkWhen`.
     */
    const val WHEN = "flow.when"

    /** How many cases a [WHEN] has. Shape: its `Case`/`Then` pins follow. */
    const val WHEN_COUNT = "Cases"

    /**
     * What a [WHEN] matches against, when it has one.
     *
     * Unwired is the CONDITION form — not a subject of null. The distinction has to be "is anything wired"
     * rather than a flag, because a flag and the wiring could disagree and then the node would say one thing
     * and do another.
     */
    const val WHEN_SUBJECT = "Subject"

    /** Where a [WHEN] goes when nothing matched. Unwired means "do nothing". */
    const val WHEN_ELSE = "Else"

    /**
     * On a [WHEN]: the author wrote an `else` arm, rather than letting unmatched values fall through.
     *
     * **The one thing the wiring genuinely cannot say.** `Else` is wired either way — to the `else` arm's
     * body when there is one, and to whatever follows the `when` when there is not, because "afterwards" in
     * a graph is a wire and nothing else. The two are told apart by where that wire ARRIVES, which is a
     * rejoin analysis: the printer does it (it needs the arms' common continuation anyway), and the
     * validator would have to duplicate it to answer a much smaller question.
     *
     * So this is a marker, for the same reason and with the same precedent as [WROTE_FIELD] and [MUTABLE]:
     * two authorings, one graph shape, and something downstream that has to tell them apart. A canvas graph
     * carries none, and the printer's rejoin still answers correctly there — which is why the printer does
     * not read this, and the exhaustiveness check does.
     */
    const val WHEN_HAS_ELSE = "@else"

    /**
     * Which of a node's bodies were written WITHOUT braces — `if x foo()`, `Phase.Chop -> chop()`.
     *
     * The same collision as [WROTE_FIELD] and [MUTABLE], one more time: a braced body of one statement and a
     * bare one are the same statements and lower to the same graph, so the printer has to be told which was
     * written or one of the two spellings stops round-tripping. Spelling and nothing else — nothing below the
     * printer reads it, and a bare body behaves in every way like a braced one.
     *
     * **One key for every construct**, because it is one idea. The parts are named after the body they mean:
     * `then` and `else` on a Branch, `body` on a While or ForEach, an arm's 1-based number or `else` on a
     * [WHEN]. Comma-joined, the way [castRenamesOf] encodes its map — a document should not grow a second
     * convention for "a small set of names in a literal".
     *
     * A canvas graph carries none, so a hand-wired node prints with braces. That is the right default: the
     * compact form is a courtesy to someone who typed it, and nobody typed that one.
     */
    /**
     * What a `let (…)` bound: `pin=local`, comma-joined — `Name=clickedName,Age=since`.
     *
     * **A tuple destructure creates no nodes**, so until this there was nowhere for its local names to live:
     * the binding is a scope entry during lowering and nothing else. The printer therefore rebuilt the names
     * from the PINS, which meant `let (a, b) = clicked()` came back as `let (id, kind) = clicked()` — the
     * author's names quietly replaced. That was tolerable only while the names could not matter; once a
     * binding can rename a pin, the name IS the point.
     *
     * It also records WHICH pins were taken, which is what lets the printer choose between the positional and
     * the named spelling. Naming every output regardless — the old rule — was the only safe answer while the
     * positional form was the only one there was: a subset had no spelling, so printing one would have read
     * back as the wrong pins.
     *
     * Same marker convention as [BARE] and [WROTE_FIELD], and the same `key=value` encoding as
     * [castRenamesOf], so a document does not grow a second way to write a small map.
     */
    const val BOUND = "@bind"

    /** [BOUND]'s value for [pins] (pin → local), or null when nothing was bound. */
    fun boundOf(pins: Map<String, String>): String? =
        pins.takeIf { it.isNotEmpty() }?.entries?.joinToString(",") { "${it.key}=${it.value}" }

    /** [BOUND] read back: pin → local, in the order written. Empty when the node carries none. */
    fun boundMap(node: Node): Map<String, String> {
        val raw = node.literals[BOUND]?.toString().orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.splitToSequence(',').mapNotNull {
            val at = it.indexOf('=')
            if (at <= 0) null else it.substring(0, at).trim() to it.substring(at + 1).trim()
        }.toMap()
    }

    const val BARE = "@bare"

    /** [BARE]'s value for [parts], or null when every body was braced and the marker should be left off. */
    fun bareOf(parts: Collection<String>): String? =
        parts.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** Was [part] of [node] written without braces? */
    fun isBare(node: Node, part: String): Boolean =
        node.literals[BARE]?.toString()
            ?.splitToSequence(',')
            ?.any { it.trim().equals(part, ignoreCase = true) } == true

    /**
     * The most cases one `when` can have.
     *
     * Generous rather than tight: the point of the count being a shape pin is that an enum can decide it, and
     * a limit an author reaches by declaring one more member would be a limit in the wrong place. 64 matches
     * [LIST_MAX] for no deeper reason than that a document should not have two different large numbers in it.
     */
    const val WHEN_MAX = 64

    /** The value or condition pin for case [i] (1-based) — pairs with [whenThen]. */
    fun whenCase(i: Int): String = "Case$i"

    /** The exec pin taken when case [i] (1-based) matched. */
    fun whenThen(i: Int): String = "Then$i"

    /** How many cases [node] declares, clamped to what is representable. */
    fun whenCount(node: Node): Int =
        ((node.literals[WHEN_COUNT] as? Number)?.toInt() ?: 0).coerceIn(0, WHEN_MAX)

    private fun literal(type: String, title: String, of: PinType, default: Any?) = NodeDescriptor(
        type = type, title = title, category = "Values", kind = NodeKind.PURE,
        outputs = listOf(PinSpec("Value", of, editable = true, default = default)),
        summary = "A constant ${of.name.lowercase()}.",
    )

    /**
     * The same, for a literal whose type is a NAME rather than a [PinType] — `Skill`, and whatever
     * follows it out of the enum. The two overloads exist only while both kinds of type do.
     */
    private fun literalOf(type: String, title: String, of: TypeRef, default: Any?) = NodeDescriptor(
        type = type, title = title, category = "Values", kind = NodeKind.PURE,
        outputs = listOf(PinSpec("Value", of, editable = true, default = default)),
        summary = "A constant ${of.name.lowercase()}.",
    )

    private fun binary(type: String, title: String, category: String, out: PinType) = NodeDescriptor(
        type = type, title = title, category = category, kind = NodeKind.PURE,
        inputs = listOf(PinSpec("A", PinType.WILDCARD), PinSpec("B", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Result", out)),
    )

    /** FLOAT → INT, four ways — see [FLOOR]. */
    private fun narrowing(type: String, title: String, host: String, summary: String) = NodeDescriptor(
        type = type, title = title, category = "Math", kind = NodeKind.PURE,
        inputs = listOf(PinSpec("Value", PinType.FLOAT, default = 0.0)),
        outputs = listOf(PinSpec("Result", PinType.INT)),
        host = host,
        summary = summary,
    )

    val all: List<NodeDescriptor> = buildList {
        add(
            NodeDescriptor(
                ENTRY, "Start", "Events", NodeKind.ENTRY,
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Where the graph begins running.",
            )
        )
        add(
            NodeDescriptor(
                ENTRY_STOP, "On Stop", "Events", NodeKind.ENTRY,
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Runs once the script ends — whether it ran out of work or you pressed Stop. " +
                    "For totals: elapsed time, xp gained, how many trips.",
            )
        )
        add(
            NodeDescriptor(
                ENTRY_RENDER, "On Render", "Events", NodeKind.ENTRY,
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Runs once per FRAME, to draw.\n\n" +
                    "The loop draws at whatever rate it loops — a pass every few hundred milliseconds, " +
                    "longer while it walks — so anything drawn there stutters and gaps. This runs on the " +
                    "frame, so a bar, a sweep or a colour computed from the clock is smooth, and keeps " +
                    "moving while the script is blocked on a walk.\n\n" +
                    "It draws IMMEDIATELY: what it draws lasts exactly this frame and no lease is " +
                    "involved, so nothing can be left behind. Draw the same thing again next frame to " +
                    "keep it on screen — which is what running every frame is for.\n\n" +
                    "It gets a few milliseconds. Anything that waits is refused before the script runs: " +
                    "no walking, no interacting, no Delay — a frame cannot wait for the game. Reading the " +
                    "scene is fine, but reading ALL of it (Nearby Objects builds thousands of entries) " +
                    "will spend the whole frame budget doing it.",
            )
        )
        add(
            NodeDescriptor(
                ENTRY_TICK, "On Tick", "Events", NodeKind.ENTRY,
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Runs once per GAME TICK — the ~600ms beat the server moves on.\n\n" +
                    "For watching rather than doing: a counter, a timer, a stuck-detector, sampling " +
                    "something that only changes when the game changes. The loop reads the world " +
                    "whenever it happens to get round to it; this reads it exactly as often as there is " +
                    "something new to read.\n\n" +
                    "It is the game's clock, not a timer — it fires with the tick, so a count of these " +
                    "is a count of ticks and stays in step however long a frame took.\n\n" +
                    "Like On Render it CANNOT WAIT: no walking, no interacting, no Delay, refused before " +
                    "the script runs. It rides the tick on the client thread, so anything that blocks " +
                    "would stall the game itself. Decide here, act in the loop.\n\n" +
                    "A pass that overruns is abandoned and what it wrote is rolled back — a watchdog that " +
                    "is too expensive should cost you the watchdog, never the run.",
            )
        )
        add(
            NodeDescriptor(
                ENTRY_WAKE, "On Wake", "Events", NodeKind.ENTRY,
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Gets ready, before the loop starts.\n\n" +
                    "Walk to where the work is, bank what is in the way, put the right gear on, read back " +
                    "anything a previous On Sleep saved. Start then does the work with the world already " +
                    "arranged, instead of opening with half a page of preparation it has to skip on every " +
                    "later pass.\n\n" +
                    "**It runs every time the script starts, not only after a sleep.** A script that has " +
                    "never run is asleep; running it wakes it. So this is where 'ready to begin' lives " +
                    "even for a script that never sleeps and saves nothing — a bank organiser whose whole " +
                    "preparation is standing at a bank wants exactly this and no On Sleep at all.\n\n" +
                    "Resuming is just a read that may find nothing: `if val saved = readJson(path: …) as " +
                    "State { State = saved }`. A missing file is null, which is a case rather than an " +
                    "error, so the same handler covers the first run and the thousandth.\n\n" +
                    "It MAY wait — walking and banking are the point — and Start does not begin until it " +
                    "has finished. If it fails, the script does not start: preparation that went wrong " +
                    "means the loop would be working against a world it never actually arranged.",
            )
        )
        add(
            NodeDescriptor(
                ENTRY_SLEEP, "On Sleep", "Events", NodeKind.ENTRY,
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Hands the account over, once the loops have finished.\n\n" +
                    "A different thing from On Stop. A stop is 'you are done, now' — it fires on a crash " +
                    "and when somebody presses the button, and by the time it runs the avatar has already " +
                    "been told to stop, so it can write a file and report totals but it cannot bank. A " +
                    "sleep is 'finish what you are doing, then put yourself away', and it runs while the " +
                    "script can still act.\n\n" +
                    "Nothing interrupts the loop to get here. The host raises a flag, Sleep Requested goes " +
                    "true, and the loop `break`s wherever ITS author decided is a clean moment — between " +
                    "rounds, after a deposit, never mid-trade. Then this runs: leave the area, and write " +
                    "down whatever On Wake will need.\n\n" +
                    "**Whatever this does not save is gone.** Waking re-runs the script from the top with " +
                    "every variable back at its declared default, which is what lets a sleep survive the " +
                    "client being closed — a sleep and a crash resume by exactly the same path.\n\n" +
                    "It MAY wait. This is not Delay — nothing waits here on its own.",
            )
        )
        add(
            NodeDescriptor(
                SLEEP_REQUESTED, "Sleep Requested", "Events", NodeKind.PURE,
                outputs = listOf(PinSpec("Requested", PinType.BOOL)),
                host = "vscript.sleepRequested",
                summary = "Has the script been asked to hand over?\n\n" +
                    "Nothing can interrupt a loop, so a handoff only happens if the loop asks. Read this " +
                    "where the script is in a state somebody else could take over from, and leave the " +
                    "loop:\n\n" +
                    "    if sleepRequested() && !inARound() {\n" +
                    "        break\n" +
                    "    }\n\n" +
                    "Not a wait, and nothing to do with Delay — it is a question. Asking it at the top of " +
                    "the loop and asking it at a safe point are different scripts: the first hands over " +
                    "mid-round, the second finishes first. Choose the moment deliberately.\n\n" +
                    "It stays true inside On Sleep, so a handler may guard on it without skipping itself. " +
                    "It is false in On Wake — waking clears it.",
            )
        )
        add(
            NodeDescriptor(
                BRANCH, "Branch", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Condition", PinType.BOOL, default = false)),
                outputs = listOf(PinSpec("True", PinType.EXEC), PinSpec("False", PinType.EXEC)),
                summary = "Takes one path or the other.",
            )
        )
        add(
            NodeDescriptor(
                IF_SOME, "If Some", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec(IF_SOME_OPTION, PinType.WILDCARD)),
                outputs = listOf(
                    PinSpec(IF_SOME_THEN, PinType.EXEC),
                    PinSpec(IF_SOME_ELSE, PinType.EXEC),
                    PinSpec(IF_SOME_VALUE, PinType.WILDCARD),
                ),
                summary = "Takes the Some path when the value is there, with the value on a pin — and " +
                    "that pin is NOT optional, so everything downstream can stop checking. Written " +
                    "`if val t = nearest() { … }`.\n\n" +
                    "Leave None unwired for an `if val` with no else.",
            )
        )
        add(
            NodeDescriptor(
                TRY, "Try", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC)),
                outputs = listOf(
                    PinSpec(TRY_BODY, PinType.EXEC),
                    PinSpec(TRY_CATCH, PinType.EXEC),
                    PinSpec(TRY_ERROR, PinType.STRING),
                ),
                summary = "Runs Try. If anything under it stops the script — an `error(…)`, a bad " +
                    "conversion, a missing host — control goes to Catch instead, with the message on the " +
                    "Error pin. Written `try { … } catch e { … }`.",
            )
        )
        add(
            NodeDescriptor(
                SEQUENCE, "Sequence", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC)),
                outputs = SEQUENCE_ARMS.map { PinSpec(it, PinType.EXEC) } +
                    PinSpec(SEQUENCE_DONE, PinType.EXEC),
                summary = "Runs each output in order, then leaves by Completed.",
            )
        )
        add(
            NodeDescriptor(
                WHEN, "When", "Flow", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec(WHEN_SUBJECT, PinType.WILDCARD),
                ),
                summary = "Takes the FIRST arm that matches. Wire a subject and each case is a value to " +
                    "equal; leave it empty and each case is a condition. Nothing matching takes Else.",
            )
        )
        add(
            NodeDescriptor(
                WHILE, "While", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Condition", PinType.BOOL, default = false)),
                outputs = listOf(
                    PinSpec("Body", PinType.EXEC),
                    PinSpec("Completed", PinType.EXEC),
                    // Steps the CONDITION needs, re-run before every test — see the note on WHILE.
                    PinSpec("Check", PinType.EXEC),
                ),
                summary = "Repeats Body while Condition holds. Put a Delay inside unless the body waits." +
                    "\n\nCheck runs before each test, for a condition that has to DO something to be " +
                    "answered — a query, or a call to a function with steps in it. A condition built only " +
                    "from pure nodes needs nothing there: those are re-read every time round on their own.",
            )
        )
        add(
            NodeDescriptor(
                FOR_EACH, "For Each", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("List", PinType.LIST)),
                outputs = listOf(
                    PinSpec("Body", PinType.EXEC),
                    PinSpec("Element", PinType.WILDCARD),
                    /** 0 for the first element. Its position in THIS list, so it lines up with any list
                     *  built alongside it — a tally beside the things being tallied. */
                    PinSpec("Index", PinType.INT),
                    PinSpec("Completed", PinType.EXEC),
                ),
                summary = "Runs Body once per element.\n\nIndex says which position the element came " +
                    "from, counting from 0, so a parallel list can be read or rebuilt in step with it.",
            )
        )
        add(
            NodeDescriptor(
                DELAY, "Delay", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Ms", PinType.INT, default = 600)),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Waits, without blocking the client thread.",
            )
        )
        add(
            NodeDescriptor(
                RETURN, "Return", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.WILDCARD)),
                summary = "Ends the graph, optionally with a value.",
            )
        )
        add(
            NodeDescriptor(
                FAIL_STEP, "Error", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.STRING)),
                host = "vscript.error",
                summary = "Stop the script with a message. No Exec out: nothing after it runs.",
            )
        )
        add(
            NodeDescriptor(
                BREAK, "Break", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Leaves the innermost loop.",
            )
        )
        add(
            NodeDescriptor(
                CONTINUE, "Continue", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Skips to the next iteration of the innermost loop.",
            )
        )
        add(
            NodeDescriptor(
                VAR_GET, "Get Variable", "Variables", NodeKind.PURE,
                outputs = listOf(PinSpec("Value", PinType.WILDCARD)),
                summary = "Reads a graph variable.",
            )
        )
        add(
            NodeDescriptor(
                VAR_SET, "Set Variable", "Variables", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Writes a graph variable.",
            )
        )
        add(
            NodeDescriptor(
                HOLD, "Hold", "Values", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec("Value", PinType.WILDCARD),
                    PinSpec(HOLD_NAME, PinType.STRING, default = ""),
                ),
                outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.WILDCARD)),
                summary = "Works something out once, here, and hands the same answer to everyone who " +
                    "reads it.\n\n" +
                    "Everything with no exec pin is worked out again at each place it is read — usually " +
                    "free, and usually what you want. This is for when it is not: an expensive search, or " +
                    "two readers that have to agree on one answer rather than ask the same question twice " +
                    "and get different ones.\n\n" +
                    "Name it, and the name is what the graph reads as downstream.",
            )
        )
        add(
            NodeDescriptor(
                CAST, "Cast", "Types", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Value", PinType.WILDCARD),
                    PinSpec(CAST_OF, PinType.ENUM, default = "", options = emptyList(), typeChoice = true),
                ),
                outputs = listOf(PinSpec("Result", PinType.WILDCARD)),
                summary = "Read one record as another, matching fields BY NAME.\n\n" +
                    "Every field of the target has to be satisfied: a same-named field of the source, or " +
                    "a rename saying which one to take it from. The source may have fields the target " +
                    "does not — those are dropped — and may never be missing one, because a cast " +
                    "reinterprets what you have rather than conjuring what you do not.",
            )
        )
        add(
            NodeDescriptor(
                IS_TYPE, "Is Type", "Logic", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Value", PinType.WILDCARD),
                    PinSpec(IS_OF, PinType.ENUM, default = IS_TESTABLE.first(), options = IS_TESTABLE, typeChoice = true),
                    PinSpec(IS_NOT, PinType.BOOL, default = false),
                ),
                outputs = listOf(PinSpec("Result", PinType.BOOL)),
                host = "vscript.isType",
                summary = "Is this value the named type, at run time?\n\n" +
                    "A test and not a cast: it answers yes or no and hands nothing back, because a type " +
                    "here belongs to a pin rather than to a point in the graph — there is nowhere to " +
                    "record that a value is something narrower inside one branch.\n\n" +
                    "Only types the run time can tell apart: an Item and an NPC are both whole numbers, " +
                    "so asking about those is refused rather than answered meaninglessly.",
            )
        )
        add(
            NodeDescriptor(
                LOCAL_SET, "Set Local", "Values", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Give a `var` local a new value.\n\n" +
                    "It writes the register its Hold owns, and a register belongs to one call — so a " +
                    "counter in a function counts that call's iterations, and a recursive call gets its " +
                    "own. A graph variable cannot do that: there is one of it for the whole run.",
            )
        )
        add(
            NodeDescriptor(
                LITERAL, "Literal", "Values", NodeKind.PURE,
                outputs = listOf(PinSpec("Value", PinType.WILDCARD, editable = true, default = 0)),
                summary = "A constant of no particular type — what you type decides. Prefer a typed one.",
            )
        )
        // Typed literals. The untyped one above is convenient and type-checks nothing; these connect only
        // where they belong, which is the entire reason the pin types exist. Each is a value with a
        // matching widget: a toggle for a bool, a number field for an int, text for a string.
        add(literal(LITERAL_INT, "Int", PinType.INT, 0))
        add(literal(LITERAL_FLOAT, "Float", PinType.FLOAT, 0.0))
        add(literal(LITERAL_STRING, "String", PinType.STRING, ""))
        // A DOMAIN's types, named rather than built in. Each is an id or a name you would otherwise have
        // to look up, so each gets the searchable catalogue rather than a number field — see ValuePicker,
        // which asks the host what it has a catalogue for.
        //
        // Typed by NAME, which is what lets the language offer the literal without owning the type: the
        // name resolves to whatever the node pack registered, and to nothing at all where no pack has.
        // `Skill` has worked this way since it left the language; these three now match it.
        add(literalOf(LITERAL_ITEM, "Item", TypeRef.named("Item"), null))
        add(literalOf(LITERAL_NPC, "NPC", TypeRef.named("Npc"), null))
        add(literalOf(LITERAL_OBJECT, "Object", TypeRef.named("Object"), null))
        add(literalOf(LITERAL_SKILL, "Skill", TypeRef.named("Skill"), "Attack"))
        add(literalOf(LITERAL_TILE, "Tile", TypeRef.named("Tile"), null))
        // Opaque cyan rather than null: a colour literal with no value would draw an empty swatch, and the
        // first thing anyone does with one is look at it.
        add(literalOf(LITERAL_COLOR, "Color", TypeRef.named("Color"), "#FF00FFFF"))
        add(
            literal(LITERAL_BOOL, "Bool", PinType.BOOL, false)
        )
        add(
            NodeDescriptor(
                FUNCTION, "Function", "Functions", NodeKind.FUNCTION,
                // The BOX carries the signature. Its exec output starts the body; wiring back into its exec
                // input ends the call. See [resolveNode] for which side each pin lands on.
                inputs = listOf(PinSpec("Exec", PinType.EXEC)),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "A reusable body with its own signature, on the box itself. Drop nodes in to make " +
                    "them part of it, and call it from anywhere with a Call node.",
            )
        )
        add(
            NodeDescriptor(
                CALL, "Call", "Functions", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC)),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Run a user function and come back with its results.",
            )
        )
        add(
            NodeDescriptor(
                NOW, "Now", "Values", NodeKind.PURE,
                outputs = listOf(PinSpec("Ms", PinType.INT)),
                host = "vscript.nowMs",
                summary = "Milliseconds on the wall clock. Record one at the start and subtract at the " +
                    "end for how long a run took.",
            )
        )
        add(
            NodeDescriptor(
                LOCAL_DATE, "Today", "Values", NodeKind.PURE,
                outputs = listOf(PinSpec("Date", PinType.INT)),
                host = "vscript.localDate",
                summary = "Today's local date as YYYYMMDD, so \"is it still the same day\" is a number " +
                    "comparison. Larger is later.",
            )
        )
        add(
            NodeDescriptor(
                HOUR_OF_DAY, "Hour", "Values", NodeKind.PURE,
                outputs = listOf(PinSpec("Hour", PinType.INT)),
                host = "vscript.hourOfDay",
                summary = "The hour on the local clock, 0-23.",
            )
        )
        add(
            NodeDescriptor(
                MINUTE_OF_HOUR, "Minute", "Values", NodeKind.PURE,
                outputs = listOf(PinSpec("Minute", PinType.INT)),
                host = "vscript.minuteOfHour",
                summary = "The minute on the local clock, 0-59.",
            )
        )
        add(
            NodeDescriptor(
                DAY_OF_WEEK, "Day Of Week", "Values", NodeKind.PURE,
                outputs = listOf(PinSpec("Day", PinType.INT)),
                host = "vscript.dayOfWeek",
                summary = "Which day it is locally: Monday is 1, Sunday is 7.",
            )
        )
        add(
            NodeDescriptor(
                MS_UNTIL_LOCAL, "Ms Until", "Values", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Hour", PinType.INT, default = 0L),
                    PinSpec("Minute", PinType.INT, default = 0L),
                ),
                outputs = listOf(PinSpec("Ms", PinType.INT)),
                host = "vscript.msUntilLocal",
                summary = "Milliseconds until the local clock next reads this time — tomorrow's if " +
                    "today's has already gone, so the answer is never negative.",
            )
        )
        add(
            NodeDescriptor(
                FORMAT, "Text", "Values", NodeKind.PURE,
                inputs = listOf(PinSpec("Template", PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.format",
                summary = "A message with holes in it: write \"Baskets full: {count}\" and a pin called " +
                    "count appears. Beats a chain of Adds — the sentence stays readable.",
            )
        )
        add(
            NodeDescriptor(
                SELECT, "Select", "Logic", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Condition", PinType.BOOL, default = false),
                    PinSpec("If True", PinType.WILDCARD),
                    PinSpec("If False", PinType.WILDCARD),
                ),
                outputs = listOf(PinSpec("Value", PinType.WILDCARD)),
                summary = "One value or the other, depending on a condition — a Branch you can wire into " +
                    "a pin. Only the arm that is taken gets worked out.",
            )
        )
        add(
            NodeDescriptor(
                OR_ELSE, "Or Else", "Values", NodeKind.PURE,
                inputs = listOf(
                    PinSpec(OR_ELSE_VALUE, PinType.WILDCARD),
                    PinSpec(OR_ELSE_FALLBACK, PinType.WILDCARD),
                ),
                outputs = listOf(PinSpec("Result", PinType.WILDCARD)),
                summary = "The value, unless it is nothing — then the fallback. Written `a ?: b`.\n\n" +
                    "The result is never nothing, which is the point: it takes a `Tile?` and hands back a " +
                    "`Tile`, so everything downstream can stop asking.\n\n" +
                    "Only the side that is needed gets worked out, so an expensive fallback costs nothing " +
                    "on the passes where the value was there.",
            )
        )
        add(
            NodeDescriptor(
                IF_PRESENT, "If Present", "Values", NodeKind.PURE,
                inputs = listOf(
                    PinSpec(IF_PRESENT_VALUE, PinType.WILDCARD),
                    PinSpec(IF_PRESENT_THEN, PinType.WILDCARD),
                ),
                outputs = listOf(
                    PinSpec("Result", PinType.WILDCARD),
                    PinSpec(IF_PRESENT_IT, PinType.WILDCARD),
                ),
                summary = "Reads something off a value that may not be there. Written `a?.b`.\n\n" +
                    "The value is worked out ONCE and handed to the access on the It pin, so a query that " +
                    "may answer nothing is not asked twice.\n\n" +
                    "The answer is optional — it is nothing when the value was — so pair it with `?:` or " +
                    "an `if val` to get back to something solid.",
            )
        )
        add(
            NodeDescriptor(
                LIST_COUNT_OF, "Count", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("Count", PinType.INT)),
                summary = "How many items the list holds.",
            )
        )
        add(
            NodeDescriptor(
                LIST_AT, "Item At", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Index", PinType.INT, default = 0)),
                outputs = listOf(PinSpec("Item", PinType.WILDCARD)),
                summary = "The item at a position, counting from 0. Stops the script if there is no such " +
                    "position — for \"whatever is there, if anything\", use First.",
            )
        )
        add(
            NodeDescriptor(
                LIST_ADD, "With Item Added", "Lists", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("List", PinType.LIST),
                    PinSpec("Value", PinType.WILDCARD),
                ),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listAdd",
                summary = "A COPY of the list with [Value] on the end. The original is untouched — feed " +
                    "the result to a Set to keep it.\n\n" +
                    "The only node that makes a list LONGER, and the reason it has to exist: With Item " +
                    "At replaces a position and leaves an out-of-range index alone, so a list can be " +
                    "edited but never grown. Anything that discovers how many things it has as it goes " +
                    "— traps laid, spots found, targets seen — needs this.\n\n" +
                    "A copy rather than an edit in place, for the same reason as With Item At: a list " +
                    "may be a variable several nodes are reading, and a pure node that quietly mutated " +
                    "its input would change what they all see with nothing in the flow to show for it.",
            )
        )
        add(
            NodeDescriptor(
                LIST_SET, "With Item At", "Lists", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("List", PinType.LIST),
                    PinSpec("Index", PinType.INT, default = 0),
                    PinSpec("Value", PinType.WILDCARD),
                ),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listSet",
                summary = "A COPY of the list with one position replaced. The original is untouched — " +
                    "feed the result to a Set to keep it.\n\n" +
                    "A copy rather than an edit in place because a list may be the value of a variable " +
                    "several nodes are reading, and a pure node that quietly mutated its input would " +
                    "change what they all see without appearing anywhere in the flow.\n\n" +
                    "An index outside the list leaves it unchanged, which is what makes it safe to drive " +
                    "from a loop over a different list.",
            )
        )
        add(
            NodeDescriptor(
                LIST_FIRST, "First", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("Item", PinType.WILDCARD)),
                host = "vscript.listFirst",
                summary = "The first item, or nothing when the list is empty. Scene lists come nearest " +
                    "first, so this is \"the closest one\".",
            )
        )
        add(
            NodeDescriptor(
                LIST_IS_EMPTY, "Is Empty", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("Empty", PinType.BOOL)),
                host = "vscript.listIsEmpty",
                summary = "True when the list holds nothing — nothing nearby, nothing left to do.",
            )
        )
        add(
            NodeDescriptor(
                LIST_CONTAINS, "Contains", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Found", PinType.BOOL)),
                host = "vscript.listContains",
                summary = "True when the value is somewhere in the list — is this fruit one of the ones " +
                    "we keep?",
            )
        )
        add(
            NodeDescriptor(
                LIST_INDEX_OF, "Index Of", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Index", PinType.INT)),
                host = "vscript.listIndexOf",
                summary = "Where the value is, counting from 0 — or -1 when the list does not hold it.\n\n" +
                    "-1 rather than nothing, so the answer is always a number you can compare. Contains " +
                    "asks the same question when all you want is yes or no.",
            )
        )
        add(
            NodeDescriptor(
                LIST_CONCAT, "Concat", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Other", PinType.LIST)),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listConcat",
                summary = "Both lists, end to end. Neither original is touched.",
            )
        )
        add(
            NodeDescriptor(
                LIST_REVERSED, "Reversed", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listReversed",
                summary = "The same items, last first. Scene lists come nearest first, so this is " +
                    "\"furthest first\".",
            )
        )
        add(
            NodeDescriptor(
                LIST_TAKE, "Take", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Count", PinType.INT, default = 0)),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listTake",
                summary = "The first few items — \"the three nearest\". Asking for more than there is " +
                    "gives what there is, so this is safe on a list you have not counted.",
            )
        )
        add(
            NodeDescriptor(
                LIST_DROP, "Drop", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Count", PinType.INT, default = 0)),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listDrop",
                summary = "Everything after the first few — the other half of Take. Dropping more than " +
                    "there is gives an empty list.",
            )
        )
        add(
            NodeDescriptor(
                LIST_SUM, "Sum", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("Total", PinType.WILDCARD)),
                host = "vscript.listSum",
                summary = "Everything added together. An empty list sums to 0.\n\n" +
                    "Whole numbers give a whole number; one Float anywhere in the list makes the total a " +
                    "Float, the same rule Add follows.",
            )
        )
        add(
            NodeDescriptor(
                LIST_SMALLEST, "Smallest", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("Value", PinType.WILDCARD)),
                host = "vscript.listMin",
                summary = "The smallest number in the list — its minimum — or nothing when it is empty.\n\n" +
                    "Called Smallest rather than Min so it cannot be confused with Math's Min, which " +
                    "compares two numbers rather than reading a list.",
            )
        )
        add(
            NodeDescriptor(
                LIST_LARGEST, "Largest", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("Value", PinType.WILDCARD)),
                host = "vscript.listMax",
                summary = "The largest number in the list — its maximum — or nothing when it is empty.",
            )
        )
        add(
            NodeDescriptor(
                LIST_REMOVE, "Without", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listRemove",
                summary = "A COPY of the list with the FIRST item equal to this one taken out. The " +
                    "original is untouched — feed the result to a Set to keep it.\n\n" +
                    "The first, not every one: removing one entry from a ledger and silently removing " +
                    "three is a bug that shows up much later. A value the list does not hold changes " +
                    "nothing.",
            )
        )
        add(
            NodeDescriptor(
                LIST_REMOVE_AT, "Without Item At", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Index", PinType.INT, default = 0)),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listRemoveAt",
                summary = "A COPY of the list with one position taken out — how a queue loses its head.\n\n" +
                    "An index outside the list leaves it unchanged, like With Item At, which is what makes " +
                    "it safe to drive from a search that came up empty.",
            )
        )
        add(
            NodeDescriptor(
                LIST_APPEND, "Add To List", "Lists", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec("List", PinType.LIST),
                    PinSpec("Value", PinType.WILDCARD),
                ),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                host = "vscript.listAppend",
                summary = "Puts [Value] on the end of the list ITSELF. Nothing to keep and nothing to " +
                    "re-Set: the list a variable holds is the list this grows.\n\n" +
                    "With Item Added is the same thing as a copy, for when other readers must not see the " +
                    "change. This is the one an accumulator wants — gathering N things with the copy costs " +
                    "N copies of the whole list, and this costs nothing.",
            )
        )
        add(
            NodeDescriptor(
                LIST_CLEAR, "Clear List", "Lists", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("List", PinType.LIST)),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                host = "vscript.listClear",
                summary = "Empties the list ITSELF, keeping whatever holds it.\n\n" +
                    "The other way to start a tick fresh is to Set the variable to a new List of Count 0, " +
                    "which is a different thing: that swaps in a NEW list and leaves anything still holding " +
                    "the old one looking at the old contents. This empties the one everything is sharing.",
            )
        )
        add(
            NodeDescriptor(
                LIST_DELETE_AT, "Remove Item At", "Lists", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec("List", PinType.LIST),
                    PinSpec("Index", PinType.INT, default = 0),
                ),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                host = "vscript.listDeleteAt",
                summary = "Takes one position out of the list ITSELF, counting from 0.\n\n" +
                    "An index outside the list changes nothing, as Without Item At does, so it is safe to " +
                    "drive from a search that came up empty.",
            )
        )
        add(
            NodeDescriptor(
                LIST_SORTED_BY, "Sorted By", "Lists", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("List", PinType.LIST),
                    PinSpec("Keys", PinType.LIST, elementType = PinType.INT),
                ),
                outputs = listOf(PinSpec("List", PinType.LIST)),
                host = "vscript.listSortedBy",
                summary = "The list ordered smallest-key first, where [Keys] holds one number per item, " +
                    "index for index — measure each thing once, then sort by what you measured.\n\n" +
                    "Sorting by a parallel list rather than by a rule you write is what keeps this cheap: " +
                    "a distance measured once per item is n host calls, and one re-read per comparison is " +
                    "n². Items past the end of [Keys] keep their order and sort last.",
            )
        )
        add(
            NodeDescriptor(
                FUNCTION_REF, "Function", "Values", NodeKind.PURE,
                outputs = listOf(PinSpec("Value", PinType.FUNCTION)),
                summary = "A function, as a value — written as its NAME with no call: " +
                    "`filtered(list: spots, keeping: reachable)` passes `reachable` rather than " +
                    "asking it anything.\n\n" +
                    "The function must be an EXPRESSION — one written `= …` rather than with a body of " +
                    "steps. It is called from inside another expression, where there is no exec chain " +
                    "for a step to run on.",
            )
        )
        add(
            NodeDescriptor(
                INVOKE, "Invoke", "Functions", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec(INVOKE_FN, TypeRef.action()),
                ),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                summary = "Run the function a wire is carrying.\n\n" +
                    "This is how a table of handlers is used: pick one, then invoke it. Unlike Mapped and " +
                    "its two siblings, this is a STEP — so the function it calls may act, walk, click and " +
                    "wait, exactly as a named call can.\n\n" +
                    "Its arguments are positional, because a function value carries the shape of its " +
                    "parameters and not their names.",
            )
        )
        add(
            NodeDescriptor(
                LIST_MAP, "Mapped", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Using", PinType.FUNCTION)),
                outputs = listOf(PinSpec("Result", PinType.LIST)),
                summary = "Every item put through a function, in order — a list of the answers.\n\n" +
                    "The result's element type is whatever the function hands back, so " +
                    "`mapped(list: trees, using: describe)` is a list of text with nothing written down.",
            )
        )
        add(
            NodeDescriptor(
                LIST_FILTER, "Filtered", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Keeping", PinType.FUNCTION)),
                outputs = listOf(PinSpec("Result", PinType.LIST)),
                summary = "The items the function says yes to, in order. The rest are dropped.\n\n" +
                    "The result holds the same kind of thing the input did — filtering never changes " +
                    "what a list is OF, only how much of it there is.",
            )
        )
        add(
            NodeDescriptor(
                LIST_FIRST_WHERE, "First Where", "Lists", NodeKind.PURE,
                inputs = listOf(PinSpec("List", PinType.LIST), PinSpec("Matching", PinType.FUNCTION)),
                outputs = listOf(PinSpec("Item", PinType.WILDCARD)),
                summary = "The first item the function says yes to — nothing, if none of them do.\n\n" +
                    "Stops at the first match, so the function is not asked about the rest. Optional, " +
                    "like First is: pair it with `?:` or an `if val`.",
            )
        )
        // ---- maps ----------------------------------------------------------------------------------
        //
        // Every one of these is PURE and hands back a COPY, exactly like the list family and for the same
        // reason: a map may be a variable several nodes are reading, and a pure node that quietly mutated
        // its input would change what they all see with nothing in the flow to show for it. `AppendPass`
        // is what makes the accumulator shape cost one instruction anyway.
        add(
            NodeDescriptor(
                MAP_OF, "Empty Map", "Maps", NodeKind.PURE,
                outputs = listOf(PinSpec(MAP_PIN, PinType.MAP)),
                host = "vscript.mapOf",
                summary = "A map with nothing in it — where a lookup starts before anything has been put " +
                    "in it.\n\n" +
                    "Its type comes from wherever it is going: a `var seen: MAP<TILE, INT> = emptyMap()` " +
                    "is a map of tiles to ints, the same way an empty list literal takes its element type " +
                    "from the slot it is handed to.",
            )
        )
        add(
            NodeDescriptor(
                MAP_WITH, "With Entry", "Maps", NodeKind.PURE,
                inputs = listOf(
                    PinSpec(MAP_PIN, PinType.MAP),
                    PinSpec(MAP_KEY_PIN, PinType.WILDCARD),
                    PinSpec(MAP_VALUE_PIN, PinType.WILDCARD),
                ),
                outputs = listOf(PinSpec("Result", PinType.MAP)),
                host = "vscript.mapWith",
                summary = "A COPY of the map with [Key] set to [Value], replacing whatever was there. The " +
                    "original is untouched — feed the result to a Set to keep it.\n\n" +
                    "`m = withEntry(map: m, key: k, value: v)` is the accumulator, and the compiler turns " +
                    "exactly that shape into a single in-place write when it can prove nothing else is " +
                    "holding the old map.",
            )
        )
        add(
            NodeDescriptor(
                MAP_WITHOUT, "Without Key", "Maps", NodeKind.PURE,
                inputs = listOf(PinSpec(MAP_PIN, PinType.MAP), PinSpec(MAP_KEY_PIN, PinType.WILDCARD)),
                outputs = listOf(PinSpec("Result", PinType.MAP)),
                host = "vscript.mapWithout",
                summary = "A COPY of the map with one key taken out. A key that was not there changes " +
                    "nothing.",
            )
        )
        add(
            NodeDescriptor(
                MAP_AT, "Value At", "Maps", NodeKind.PURE,
                inputs = listOf(PinSpec(MAP_PIN, PinType.MAP), PinSpec(MAP_KEY_PIN, PinType.WILDCARD)),
                outputs = listOf(PinSpec(MAP_VALUE_PIN, PinType.WILDCARD)),
                host = "vscript.mapAt",
                summary = "What the key maps to, or nothing when it is not there.\n\n" +
                    "**The answer is optional** — a key that has never been set is the ordinary case, not " +
                    "a mistake — so pair it with `?:` or an `if val`. Ask Has Key instead when the VALUE " +
                    "may legitimately be nothing and you need to tell the two apart.",
            )
        )
        add(
            NodeDescriptor(
                MAP_HAS, "Has Key", "Maps", NodeKind.PURE,
                inputs = listOf(PinSpec(MAP_PIN, PinType.MAP), PinSpec(MAP_KEY_PIN, PinType.WILDCARD)),
                outputs = listOf(PinSpec("Found", PinType.BOOL)),
                host = "vscript.mapHas",
                summary = "True when the map holds this key, whatever it maps to.",
            )
        )
        add(
            NodeDescriptor(
                MAP_SIZE, "Map Size", "Maps", NodeKind.PURE,
                inputs = listOf(PinSpec(MAP_PIN, PinType.MAP)),
                outputs = listOf(PinSpec("Size", PinType.INT)),
                host = "vscript.mapSize",
                summary = "How many entries the map holds.",
            )
        )
        add(
            NodeDescriptor(
                MAP_FOR_EACH, "For Each Entry", "Flow", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec(MAP_PIN, PinType.MAP)),
                outputs = listOf(
                    PinSpec("Body", PinType.EXEC),
                    PinSpec(MAP_KEY_PIN, PinType.WILDCARD),
                    PinSpec(MAP_VALUE_PIN, PinType.WILDCARD),
                    PinSpec("Completed", PinType.EXEC),
                ),
                summary = "Runs Body once per entry, in the order they were added.\n\nKey and Value are " +
                    "the entry — no second lookup, which is what separates this from walking Keys and " +
                    "asking the map for each one.",
            )
        )
        add(
            NodeDescriptor(
                MAP_ENTRIES, "Entries", "Maps", NodeKind.PURE,
                inputs = listOf(PinSpec(MAP_PIN, PinType.MAP)),
                outputs = listOf(PinSpec("Entries", PinType.LIST)),
                host = "vscript.mapEntries",
                summary = "Every entry as a two-item list — key first. What For Each Entry walks.",
            )
        )
        add(
            NodeDescriptor(
                MAP_KEYS, "Keys", "Maps", NodeKind.PURE,
                inputs = listOf(PinSpec(MAP_PIN, PinType.MAP)),
                outputs = listOf(PinSpec("Keys", PinType.LIST)),
                host = "vscript.mapKeys",
                summary = "Every key, in the order they were first put in — so a map can be walked with " +
                    "an ordinary `for`.",
            )
        )
        add(
            NodeDescriptor(
                MAP_VALUES, "Values", "Maps", NodeKind.PURE,
                inputs = listOf(PinSpec(MAP_PIN, PinType.MAP)),
                outputs = listOf(PinSpec("Values", PinType.LIST)),
                host = "vscript.mapValues",
                summary = "Every value, in the same order Keys gives its keys.",
            )
        )
        // ---- JSON ----------------------------------------------------------------------------------
        add(
            NodeDescriptor(
                JSON_PARSE, "Parse JSON", "Files", NodeKind.PURE,
                inputs = listOf(PinSpec("Text", PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Json", TypeRef.named("Json"))),
                host = "vscript.jsonParse",
                summary = "Read JSON text into a document.\n\n" +
                    "The result is a `Json` and there is nothing to do with one but read it as a record:\n\n" +
                    "    val doc = parseJson(text: s) as Layout\n\n" +
                    "That cast is checked against the record's own declaration when the script is " +
                    "compiled, so a field the file cannot fill is an error before the script runs rather " +
                    "than a null halfway through it.",
            )
        )
        add(
            NodeDescriptor(
                JSON_TEXT, "To JSON", "Files", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Value", PinType.WILDCARD),
                    PinSpec(JSON_PRETTY, PinType.BOOL, default = true),
                ),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.jsonText",
                summary = "Any value as JSON text.\n\n" +
                    "Needs no type named anywhere, unlike reading: a record already carries its field " +
                    "names, so writing one out is the names and the values it is holding. Records become " +
                    "objects in declaration order, lists become arrays, maps become objects.\n\n" +
                    "[Pretty] indents and puts one entry to a line, for a file a person will open. Turn " +
                    "it off for one only a program reads.",
            )
        )
        // ---- files ---------------------------------------------------------------------------------
        add(
            NodeDescriptor(
                FILE_READ_JSON, "Read JSON", "Files", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec(FILE_PATH, PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Json", TypeRef.named("Json").orNull())),
                host = "vscript.fileReadJson",
                summary = "Read a file and parse it, in one step.\n\n" +
                    "    val doc = readJson(path: \"layout.json\") as Layout\n\n" +
                    "**Nothing when there is no such file** — absence is an ordinary case, not a stop — so " +
                    "pair it with `?:` or an `if val` when the file may be missing. Bad JSON *is* a stop: " +
                    "the file being there and being wrong is a different thing from it not being there.\n\n" +
                    "Paths are relative to the script data folder and cannot leave it.",
            )
        )
        add(
            NodeDescriptor(
                FILE_WRITE_JSON, "Write JSON", "Files", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec(FILE_PATH, PinType.STRING, default = ""),
                    PinSpec("Value", PinType.WILDCARD),
                    PinSpec(JSON_PRETTY, PinType.BOOL, default = true),
                ),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                host = "vscript.fileWriteJson",
                summary = "Serialise a value and write it, replacing whatever was there. Folders are made " +
                    "as needed.\n\n" +
                    "Paths are relative to the script data folder and cannot leave it.",
            )
        )
        add(
            NodeDescriptor(
                FILE_READ_TEXT, "Read Text", "Files", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec(FILE_PATH, PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Text", TypeRef(PinType.STRING).orNull())),
                host = "vscript.fileReadText",
                summary = "The whole file as text, or nothing when there is no such file.",
            )
        )
        add(
            NodeDescriptor(
                FILE_WRITE_TEXT, "Write Text", "Files", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec(FILE_PATH, PinType.STRING, default = ""),
                    PinSpec("Text", PinType.STRING, default = ""),
                ),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                host = "vscript.fileWriteText",
                summary = "Write text to a file, replacing whatever was there.",
            )
        )
        add(
            NodeDescriptor(
                FILE_EXISTS, "File Exists", "Files", NodeKind.PURE,
                inputs = listOf(PinSpec(FILE_PATH, PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Found", PinType.BOOL)),
                host = "vscript.fileExists",
                summary = "Is there a file there right now?\n\n" +
                    "Pure, unlike the reads: it is a live question, and asking it twice a second apart " +
                    "should be allowed to give two answers.",
            )
        )
        add(
            NodeDescriptor(
                FILE_DELETE, "Delete File", "Files", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec(FILE_PATH, PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Removed", PinType.BOOL)),
                host = "vscript.fileDelete",
                summary = "Remove a file. False when there was nothing there — which is not an error.",
            )
        )
        add(
            NodeDescriptor(
                FILE_LIST, "List Files", "Files", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Folder", PinType.STRING, default = "")),
                outputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec("Names", TypeRef.list(TypeRef(PinType.STRING))),
                ),
                host = "vscript.fileList",
                summary = "The file names directly inside a folder, sorted. Empty when there is no such " +
                    "folder — the same answer as an empty one, on purpose: a script listing a folder it " +
                    "has not written to yet wants the loop to run zero times, not to stop.",
            )
        )
        add(
            NodeDescriptor(
                FILE_FOLDERS, "List Folders", "Files", NodeKind.IMPURE,
                inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Folder", PinType.STRING, default = "")),
                outputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec("Names", TypeRef.list(TypeRef(PinType.STRING))),
                ),
                host = "vscript.fileFolders",
                summary = "The folder names directly inside a folder, sorted.\n\n" +
                    "Paths nest as deep as you like, so this is how a script organises what it saves: a " +
                    "folder per account, per run, per task. Neither this nor List Files recurses — one " +
                    "level each, and a script that wants the whole tree walks it.",
            )
        )
        add(
            NodeDescriptor(
                LITERAL_LIST, "List", "Values", NodeKind.PURE,
                inputs = listOf(
                    PinSpec(LIST_OF, PinType.ENUM, default = "Int", typeChoice = true),
                    PinSpec(LIST_COUNT, PinType.INT, default = 0),
                ),
                outputs = listOf(PinSpec("Value", PinType.LIST, elementType = PinType.INT)),
                summary = "Several values written out by hand. Pick what it holds and how many, and a " +
                    "slot appears for each — with that type's own picker. Any slot can be wired instead.",
            )
        )
        add(
            NodeDescriptor(
                RANGE, "Range", "Values", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("From", PinType.INT, default = 0),
                    PinSpec("To", PinType.INT, default = 0),
                ),
                outputs = listOf(PinSpec("List", PinType.LIST, elementType = PinType.INT)),
                host = "vscript.range",
                summary = "The whole numbers from [From] up to but NOT including [To] — the counted loop " +
                    "this language otherwise has to spell as a `while` and a `var`:\n\n" +
                    "    for i in range(0, count(list: xs)) { … }\n\n" +
                    "Excluding the end is what makes that line exactly the valid indices of the list. " +
                    "A range that ends before it starts is empty rather than an error, so a count of 0 " +
                    "simply does not loop.",
            )
        )
        add(
            NodeDescriptor(
                STRUCT_MAKE, "Make", "Types", NodeKind.PURE,
                inputs = listOf(PinSpec(STRUCT_OF, PinType.ENUM, typeChoice = true)),
                summary = "Bundles several values into one of the types this graph declares. Pick which, " +
                    "and a slot appears for every field.",
            )
        )
        add(
            NodeDescriptor(
                STRUCT_SPLIT, "Split", "Types", NodeKind.PURE,
                inputs = listOf(PinSpec(STRUCT_OF, PinType.ENUM, typeChoice = true)),
                summary = "Takes a record apart — a pin per field.",
            )
        )
        add(
            NodeDescriptor(
                STRUCT_GET, "Get Field", "Types", NodeKind.PURE,
                inputs = listOf(PinSpec(STRUCT_OF, PinType.ENUM, typeChoice = true)),
                summary = "One field out of a record. Split gives you all of them; this says which one " +
                    "you wanted.",
            )
        )
        add(
            NodeDescriptor(
                STRUCT_SET, "Set Field", "Types", NodeKind.PURE,
                inputs = listOf(PinSpec(STRUCT_OF, PinType.ENUM, typeChoice = true)),
                summary = "A copy of a record with one field changed. The one that went in is untouched — " +
                    "records are values here, so nothing is written through a wire.",
            )
        )
        add(
            NodeDescriptor(
                ENUM_OF, "Choice", "Types", NodeKind.PURE,
                inputs = listOf(PinSpec(ENUM_TYPE, PinType.ENUM, typeChoice = true)),
                summary = "One of the named choices this graph declares. Pick which enum, and its " +
                    "members appear to choose from — the canvas spelling of `Phase.Chop`.",
            )
        )
        add(
            NodeDescriptor(
                NARROW, "Narrowed", "Flow", NodeKind.PURE,
                inputs = listOf(PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec(NARROW_OUT, PinType.WILDCARD)),
                summary = "A value something has already proved is there, with its `?` taken off.\n\n" +
                    "Written as nothing: it is what `if x != null { … }` means, not something you type. " +
                    "Compiles to nothing at all.",
            )
        )
        add(
            NodeDescriptor(
                ENUM_VALUES, "Choice Values", "Types", NodeKind.PURE,
                inputs = listOf(PinSpec(ENUM_TYPE, PinType.ENUM, typeChoice = true)),
                outputs = listOf(PinSpec("Values", PinType.LIST)),
                summary = "Every member of a choice, in declaration order — the canvas spelling of " +
                    "`Phase.values()`.\n\n" +
                    "The list's elements are the CHOICE's type, so a loop over it binds a member and can " +
                    "read its table columns. Compiles to one constant.",
            )
        )
        add(
            NodeDescriptor(
                ENUM_FIELD, "Choice Field", "Types", NodeKind.PURE,
                inputs = listOf(PinSpec(ENUM_TYPE, PinType.ENUM, typeChoice = true)),
                summary = "One column of a choice's table, read off a member — the canvas spelling of " +
                    "`t.anchor`.\n\n" +
                    "An enum that declares fields is a TABLE: every member carries a row, and this reads " +
                    "one cell of it. That is what a `when` with an arm per member returning constants was " +
                    "standing in for.\n\n" +
                    "The member arrives on a wire rather than being picked, which is the difference " +
                    "between this and Choice — it answers about whichever member you happen to be " +
                    "holding.",
            )
        )
        add(
            NodeDescriptor(
                TO_TEXT, "To Text", "Values", NodeKind.PURE,
                inputs = listOf(PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.toText",
                summary = "Anything, as text — for logging a number, naming a tile, building a message.",
            )
        )
        add(
            NodeDescriptor(
                PARSE_INT, "Parse Int", "Values", NodeKind.PURE,
                inputs = listOf(
                    PinSpec(
                        "Text", PinType.STRING, default = "",
                        doc = "The text to read a whole number out of.",
                    ),
                ),
                outputs = listOf(PinSpec("Number", TypeRef(PinType.INT).orNull())),
                host = "vscript.parseInt",
                summary = "Text as a whole number — **nothing** when it does not read as one.\n\n" +
                    "The way back from a STRING, which is the only shape the game gives most of its " +
                    "numbers in: a widget's contents. Run energy, a shop's stock, a countdown, a score. " +
                    "`Numbers Heard` is the chat-log verb and answers about a LINE THE GAME SAID; this " +
                    "reads a string you are already holding.\n\n" +
                    "Optional rather than 0 on failure, because \"that was not a number\" and \"that " +
                    "number was zero\" are different facts, and a script that treats an unreadable orb " +
                    "as empty acts on it. Pair it with `?:` or an `if val`.\n\n" +
                    "Surrounding space, thousands commas and the game's `<col=…>` markup are ignored, " +
                    "since a string read off a widget routinely carries all three and none of them " +
                    "changes what the number is. Anything left over is a miss — \"12 of 20\" is not a " +
                    "number.",
            )
        )
        add(
            NodeDescriptor(
                PARSE_FLOAT, "Parse Float", "Values", NodeKind.PURE,
                inputs = listOf(
                    PinSpec(
                        "Text", PinType.STRING, default = "",
                        doc = "The text to read a fractional number out of.",
                    ),
                ),
                outputs = listOf(PinSpec("Number", TypeRef(PinType.FLOAT).orNull())),
                host = "vscript.parseFloat",
                summary = "Text as a fractional number — **nothing** when it does not read as one. " +
                    "Parse Int's counterpart; same rules, same optional answer.",
            )
        )

        // ---- text ----------------------------------------------------------------------------------
        //
        // Case-SENSITIVE throughout, unlike the SDK's `Text Contains`. The game spells its own strings
        // consistently, and a comparison that quietly ignores case cannot be made strict again by a
        // caller who needs it — where the reverse is one `Lower` away.
        add(
            NodeDescriptor(
                TEXT_LENGTH, "Text Length", "Text", NodeKind.PURE,
                inputs = listOf(PinSpec("Text", PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Length", PinType.INT)),
                host = "vscript.textLength",
                summary = "How many characters. `Count` is the LIST verb and will not take a string.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_INDEX_OF, "Position Of", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = "", doc = "The text to search."),
                    PinSpec("Part", PinType.STRING, default = "", doc = "What to look for."),
                    PinSpec(
                        "From", PinType.INT, default = 0,
                        doc = "Start looking here. For finding the SECOND occurrence: the first one's " +
                            "index plus one.",
                    ),
                ),
                outputs = listOf(PinSpec("Index", PinType.INT)),
                host = "vscript.textIndexOf",
                summary = "Where [Part] first appears, counting from 0 — **-1 when it does not**.\n\n" +
                    "-1 rather than nothing, because the answer feeds arithmetic far more often than it " +
                    "feeds a branch, and an optional would have to be unwrapped at every one of those " +
                    "sites. Test it before slicing with it.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_LAST_INDEX_OF, "Last Position Of", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec("Part", PinType.STRING, default = ""),
                ),
                outputs = listOf(PinSpec("Index", PinType.INT)),
                host = "vscript.textLastIndexOf",
                summary = "Where [Part] appears LAST, or -1. The tail-end twin of Index Of — for a " +
                    "file extension, a trailing bracket, the last word of a line.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_SLICE, "Slice", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec("From", PinType.INT, default = 0, doc = "First character to keep, from 0."),
                    PinSpec(
                        "To", PinType.INT, default = -1,
                        doc = "One PAST the last character to keep. -1 means to the end.",
                    ),
                ),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.textSlice",
                summary = "The characters from [From] up to (not including) [To].\n\n" +
                    "**Clamped, never thrown.** Indexes here usually come out of Index Of, which answers " +
                    "-1 when it found nothing, so the arithmetic that follows goes out of range as a " +
                    "matter of course — and a crash mid-run is a far worse answer than a short string. " +
                    "Out-of-order bounds give \"\".",
            )
        )
        add(
            NodeDescriptor(
                TEXT_BETWEEN, "Between", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec(
                        "After", PinType.STRING, default = "",
                        doc = "The marker the answer starts after. Empty means the start of the text.",
                    ),
                    PinSpec(
                        "Before", PinType.STRING, default = "",
                        doc = "The marker the answer stops at — the first one FOLLOWING [After]. Empty " +
                            "means the end of the text.",
                    ),
                ),
                outputs = listOf(PinSpec("Text", TypeRef(PinType.STRING).orNull())),
                host = "vscript.textBetween",
                summary = "The text between two markers — **nothing** when either is absent.\n\n" +
                    "The reason this exists rather than leaving people to Index Of and Slice: that is " +
                    "four nodes and an off-by-one at each, and the failure case (a marker missing, so an " +
                    "index of -1 flows into the arithmetic) is silent. Here a missing marker is `nothing`, " +
                    "which a caller has to answer for.\n\n" +
                    "Pulling the level out of `\"Golovanova seed (level 34)\"` is this and Parse Int:\n\n" +
                    "```\nnumberIn(text: option) ?: 0                                    // the whole job, one node\nparseInt(text: between(text: option, after: \"level \", before: \")\") ?: \"\") ?: 0\n```\n\n" +
                    "Optional rather than \"\", because a marker that is not there and a genuinely empty " +
                    "gap between two that are — `\"(level )\"` — are different facts.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_TRIM, "Trim", "Text", NodeKind.PURE,
                inputs = listOf(PinSpec("Text", PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.textTrim",
                summary = "Surrounding whitespace off, the middle untouched.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_REPLACE, "Replace", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec("Find", PinType.STRING, default = "", doc = "Plain text, never a pattern."),
                    PinSpec("With", PinType.STRING, default = "", doc = "Empty to delete it."),
                ),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.textReplace",
                summary = "Every occurrence of [Find] swapped for [With]. Plain text on both sides — " +
                    "there is no regex here, so a `.` is a full stop.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_SPLIT, "Split On", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec("On", PinType.STRING, default = ",", doc = "The separator, as plain text."),
                ),
                outputs = listOf(PinSpec("Parts", PinType.LIST, elementType = PinType.STRING)),
                host = "vscript.textSplit",
                summary = "Cut on a separator into a list of pieces.\n\n" +
                    "The pieces are NOT trimmed and empties are kept, so `\"a,,b\"` is three parts and " +
                    "`\"a, b\"` has a leading space on the second. Both are decisions the caller can undo " +
                    "and neither is one this could undo for them.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_JOIN, "Join With", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Parts", PinType.LIST, elementType = PinType.WILDCARD),
                    PinSpec("With", PinType.STRING, default = ", ", doc = "Put between each pair."),
                ),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.textJoin",
                summary = "A list into one string, with [With] between the pieces. Anything that is not " +
                    "already text is rendered the way To Text renders it.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_STARTS_WITH, "Starts With", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec("Part", PinType.STRING, default = ""),
                ),
                outputs = listOf(PinSpec("Yes", PinType.BOOL)),
                host = "vscript.textStartsWith",
                summary = "Does it begin with this? Case-sensitive — see the note on Text Contains, " +
                    "which is not.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_ENDS_WITH, "Ends With", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec("Part", PinType.STRING, default = ""),
                ),
                outputs = listOf(PinSpec("Yes", PinType.BOOL)),
                host = "vscript.textEndsWith",
                summary = "Does it end with this? Case-sensitive.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_UPPER, "Upper Case", "Text", NodeKind.PURE,
                inputs = listOf(PinSpec("Text", PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.textUpper",
                summary = "In upper case.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_LOWER, "Lower Case", "Text", NodeKind.PURE,
                inputs = listOf(PinSpec("Text", PinType.STRING, default = "")),
                outputs = listOf(PinSpec("Text", PinType.STRING)),
                host = "vscript.textLower",
                summary = "In lower case — the usual way to compare two names the game spelled " +
                    "differently.",
            )
        )
        add(
            NodeDescriptor(
                TEXT_NUMBER_IN, "Number In", "Text", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Text", PinType.STRING, default = ""),
                    PinSpec(
                        "Skip", PinType.INT, default = 0,
                        doc = "How many numbers to pass over first. 0 is the first one, 1 the second.",
                    ),
                ),
                outputs = listOf(PinSpec("Number", TypeRef(PinType.INT).orNull())),
                host = "vscript.textNumberIn",
                summary = "The first whole number ANYWHERE in the text — **nothing** when there is " +
                    "none.\n\n" +
                    "Parse Int's forgiving sibling. That one asks \"is this string a number\" and " +
                    "refuses `\"level 34)\"`; this asks \"is there a number in this string\" and answers " +
                    "34. Both are wanted, which is why widening Parse Int was not the fix — it would " +
                    "have made `\"12 of 20\"` read as 12 for every caller that already relies on being " +
                    "told no.\n\n" +
                    "Thousands commas inside a number are ignored, so `\"1,234 coins\"` is 1234. A " +
                    "leading minus counts. [Skip] reaches the later ones: `\"3 of 20\"` with Skip 1 is 20.",
            )
        )
        add(
            NodeDescriptor(
                FAIL, "Error", "Values", NodeKind.PURE,
                inputs = listOf(PinSpec("Message", PinType.STRING)),
                outputs = listOf(PinSpec("Result", PinType.WILDCARD)),
                host = "vscript.error",
                summary = "Stop the script with a message. Hands nothing back, so it is what goes on the " +
                    "right of `?:` when there is no sensible fallback: `valueAt(map: m, key: k) ?: " +
                    "error(message: \"no handler for this key\")` is a plain value, not an optional.",
            )
        )
        add(
            NodeDescriptor(
                REROUTE, "Reroute", "Organise", NodeKind.PURE,
                inputs = listOf(PinSpec("In", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Out", PinType.WILDCARD)),
                summary = "A knot on a wire. Carries its value through unchanged; Arrange places these so " +
                    "a long wire runs along a clear lane instead of behind the nodes it passes.",
            )
        )
        add(
            NodeDescriptor(
                COMMENT, "Comment", "Organise", NodeKind.COMMENT,
                summary = "A labelled box. Drag it to move everything inside; resize from the corner.",
            )
        )
        add(
            // A first-class producer, so the console is something a graph writes to rather than only a
            // place engine diagnostics land. Host-backed rather than lowered by the compiler: it has no
            // control-flow behaviour, it just needs to reach the sink.
            NodeDescriptor(
                LOG, "Log", "Debug", NodeKind.IMPURE,
                inputs = listOf(
                    PinSpec("Exec", PinType.EXEC),
                    PinSpec("Message", PinType.STRING, default = ""),
                    PinSpec("Level", PinType.ENUM, default = "info", options = LOG_LEVELS),
                ),
                outputs = listOf(PinSpec("Exec", PinType.EXEC)),
                host = "vscript.log",
                summary = "Write a line to the console, attributed to this node.",
            )
        )
        add(binary(ADD, "Add", "Math", PinType.WILDCARD))
        add(binary(SUB, "Subtract", "Math", PinType.WILDCARD))
        add(binary(MUL, "Multiply", "Math", PinType.WILDCARD))
        add(binary(DIV, "Divide", "Math", PinType.WILDCARD))
        add(binary(MOD, "Modulo", "Math", PinType.WILDCARD))
        add(
            narrowing(
                FLOOR, "Floor", "vscript.floor",
                "The whole number at or below this one — floor(2.9) is 2, floor(-2.1) is -3.",
            )
        )
        add(
            narrowing(
                CEIL, "Ceiling", "vscript.ceil",
                "The whole number at or above this one — ceil(2.1) is 3, ceil(-2.9) is -2.",
            )
        )
        add(
            narrowing(
                ROUND, "Round", "vscript.round",
                "The nearest whole number. A tie goes AWAY from zero, so round(2.5) is 3 and " +
                    "round(-2.5) is -3 — unlike Java, which would say -2.",
            )
        )
        add(
            narrowing(
                TO_INT, "To Int", "vscript.toInt",
                "Drop the fraction, keeping the sign — toInt(2.9) is 2 and toInt(-2.9) is -2. This is " +
                    "the `(int)` cast; use Floor if you want -3.",
            )
        )
        add(
            NodeDescriptor(
                TO_FLOAT, "To Float", "Math", NodeKind.PURE,
                inputs = listOf(PinSpec("Value", PinType.INT, default = 0)),
                outputs = listOf(PinSpec("Result", PinType.FLOAT)),
                host = "vscript.toFloat",
                summary = "Widen a whole number so arithmetic on it keeps its fraction — `a / b` on two " +
                    "INTs is integer division, and `toFloat(a) / b` is how you get the rest of the answer.",
            )
        )
        add(
            NodeDescriptor(
                ABS, "Abs", "Math", NodeKind.PURE,
                inputs = listOf(PinSpec("Value", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Result", PinType.WILDCARD)),
                host = "vscript.abs",
                summary = "How far from zero, sign discarded — abs(-3) is 3.\n\n" +
                    "Keeps the kind it was given: a whole number in, a whole number out.",
            )
        )
        add(
            NodeDescriptor(
                MIN, "Min", "Math", NodeKind.PURE,
                inputs = listOf(PinSpec("A", PinType.WILDCARD), PinSpec("B", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Result", PinType.WILDCARD)),
                host = "vscript.min",
                summary = "The smaller of two numbers — how you clamp something to a ceiling.",
            )
        )
        add(
            NodeDescriptor(
                MAX, "Max", "Math", NodeKind.PURE,
                inputs = listOf(PinSpec("A", PinType.WILDCARD), PinSpec("B", PinType.WILDCARD)),
                outputs = listOf(PinSpec("Result", PinType.WILDCARD)),
                host = "vscript.max",
                summary = "The larger of two numbers — how you clamp something to a floor.",
            )
        )
        add(
            NodeDescriptor(
                SQRT, "Sqrt", "Math", NodeKind.PURE,
                inputs = listOf(PinSpec("Value", PinType.FLOAT, default = 0.0)),
                outputs = listOf(PinSpec("Result", PinType.FLOAT)),
                host = "vscript.sqrt",
                summary = "The square root. Negative input gives 0 rather than stopping the script — the " +
                    "same lenient reading the list nodes take, since the caller of a distance formula has " +
                    "nothing useful to do with a failure.",
            )
        )
        add(
            NodeDescriptor(
                POW, "Pow", "Math", NodeKind.PURE,
                inputs = listOf(
                    PinSpec("Base", PinType.FLOAT, default = 0.0),
                    PinSpec("Exponent", PinType.FLOAT, default = 0.0),
                ),
                outputs = listOf(PinSpec("Result", PinType.FLOAT)),
                host = "vscript.pow",
                summary = "[Base] raised to [Exponent]. Fractional and negative exponents work, unlike a " +
                    "repeated multiply — pow(2.0, 0.5) is the square root of 2.",
            )
        )
        add(
            NodeDescriptor(
                SIN, "Sin", "Math", NodeKind.PURE,
                inputs = listOf(PinSpec("Radians", PinType.FLOAT, default = 0.0)),
                outputs = listOf(PinSpec("Result", PinType.FLOAT)),
                host = "vscript.sin",
                summary = "The sine of an angle in RADIANS — not degrees. A whole turn is 2 × pi(), so " +
                    "degrees convert as `deg * pi() / 180.0`.",
            )
        )
        add(
            NodeDescriptor(
                COS, "Cos", "Math", NodeKind.PURE,
                inputs = listOf(PinSpec("Radians", PinType.FLOAT, default = 0.0)),
                outputs = listOf(PinSpec("Result", PinType.FLOAT)),
                host = "vscript.cos",
                summary = "The cosine of an angle in RADIANS — not degrees. Paired with Sin, this is how " +
                    "you walk a circle: x + cos(a) * r, y + sin(a) * r.",
            )
        )
        add(
            NodeDescriptor(
                PI, "Pi", "Math", NodeKind.PURE,
                outputs = listOf(PinSpec("Value", PinType.FLOAT)),
                host = "vscript.pi",
                summary = "3.14159… — so an angle can be written rather than pasted.",
            )
        )
        add(binary(EQ, "Equal", "Compare", PinType.BOOL))
        add(binary(NE, "Not Equal", "Compare", PinType.BOOL))
        add(binary(LT, "Less Than", "Compare", PinType.BOOL))
        add(binary(LE, "Less Or Equal", "Compare", PinType.BOOL))
        add(binary(GT, "Greater Than", "Compare", PinType.BOOL))
        add(binary(GE, "Greater Or Equal", "Compare", PinType.BOOL))
        add(
            NodeDescriptor(
                AND, "And", "Logic", NodeKind.PURE,
                inputs = listOf(PinSpec("A", PinType.BOOL, default = false), PinSpec("B", PinType.BOOL, default = false)),
                outputs = listOf(PinSpec("Result", PinType.BOOL)),
                summary = "True when both are. Both sides are always evaluated — use a Branch when one " +
                    "side must not run.",
            )
        )
        add(
            NodeDescriptor(
                OR, "Or", "Logic", NodeKind.PURE,
                inputs = listOf(PinSpec("A", PinType.BOOL, default = false), PinSpec("B", PinType.BOOL, default = false)),
                outputs = listOf(PinSpec("Result", PinType.BOOL)),
                summary = "True when either is.",
            )
        )
        add(
            NodeDescriptor(
                NOT, "Not", "Logic", NodeKind.PURE,
                inputs = listOf(PinSpec("A", PinType.BOOL, default = false)),
                outputs = listOf(PinSpec("Result", PinType.BOOL)),
            )
        )
    }

    /**
     * Every type registered here.
     *
     * Most are lowered by the compiler directly; [LOG] is the exception — it ships with the editor but has
     * no control-flow behaviour, so it compiles to an ordinary host call like any catalog node would.
     */
    val builtinTypes: Set<String> = all.map { it.type }.toSet()
}
