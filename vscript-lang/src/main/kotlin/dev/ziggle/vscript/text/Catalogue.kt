package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.Names
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Literals
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.PinSpec

/**
 * The node catalogue, as a set of function signatures — phase 3 of `docs/TEXT_FRONTEND.md`.
 *
 * **Adapted, not retyped.** `vscript-nodes` is 8,971 lines of declarations, several with an account-loss
 * story in their comments, and a `NodeDescriptor` already IS a signature wearing pin clothes: a name, its
 * inputs, its outputs, the host it binds to and whether that host blocks. Everything else it carries —
 * a title, a category, a pin's position, the options an editor offers, whether to draw a field on an
 * output — is about drawing a node, and the text surface has never had a use for any of it.
 *
 * So this is the whole of what the text front end needs from the catalogue, and it is a projection rather
 * than a second source of truth. Redesigning the authoring DSL is a separate and optional job now: after
 * this, the canvas is its only consumer.
 */
fun NodeCatalog.natives(): NativeTable {
    val names = Names(this)
    val fns = ArrayList<NativeFn>()
    for (d in names.callable) {
        // **Only what a host can actually run.** `flow.delay`, `logic.and` and `logic.or` carry no host
        // because the compiler lowers them itself, and the three list verbs that take a function cannot be
        // hosts at all — see [Intrinsic]. A signature with nothing behind it would typecheck and then fail
        // to emit, which is the worst of both.
        val host = d.host ?: continue
        if (Intrinsic[names.textName(d.type)] != null) continue
        // Registered under its TEXT name, and — when they differ — under the node type as well.
        // `Names` emits the shortest unambiguous spelling and the parser "accepts any suffix that
        // resolves", so a script may write `draw.line` where the short `line` was assigned. Registering
        // only the short name left the full one unknown, and a local called `draw` then looked like the
        // more plausible reading.
        val text = names.textName(d.type)
        val shaped = generic(d.type, d.dataInputs.map { it.asParam() }, d.dataOutputs.map { NativeParam(it.name, it.type) })
        fns += NativeFn(
            name = text,
            params = shaped?.first ?: d.dataInputs.map { it.asParam() },
            // EXEC pins are not values: control flow is statement order in text, so a node's exec output
            // is not something a call hands back.
            results = shaped?.second ?: d.dataOutputs.map { NativeParam(it.name, it.type) },
            kind = d.hostKind,
            host = host,
            // The one node whose parameters are written in its own first argument.
            fromTemplate = d.type == dev.ziggle.vscript.model.BuiltinNodes.FORMAT,
            // An extension is written on a value rather than called by name. Its first input pin is the
            // receiver, which is what `checkCall` prepends.
            receiver = d.receiver,
        )
        if (text != d.type) fns += fns.last().let { under ->
            NativeFn(d.type, under.params, under.results, under.kind, under.host, receiver = under.receiver)
        }
    }
    return NativeTable(fns)
}

/**
 * The container verbs, typed with VARIABLES rather than the bare `MAP`/`LIST` their pins carry.
 *
 * A pin says `MAP` and means "whatever map is wired here" — the graph resolves it from the wire, which is
 * what `MAP_SHAPED` and `effectivePinType` are for. A signature has no wire to look at, so it says so in
 * the only way a signature can: `valueAt(map: MAP<K, V>, key: K) -> V?`, and the ordinary inference that
 * already binds `T` from a list argument does the rest.
 *
 * Without it `valueAt` answered WILDCARD however precisely the map was typed, and everything downstream of
 * a map lookup lost its type.
 */
private fun generic(type: String, params: List<NativeParam>, results: List<NativeParam>): Pair<List<NativeParam>, List<NativeParam>>? {
    val k = TypeRef.named("K").asVariable()
    val v = TypeRef.named("V").asVariable()
    val e = TypeRef.named("E").asVariable()
    val map = TypeRef.map(k, v)
    return when (type) {
        BuiltinNodes.MAP_AT -> listOf(NativeParam("Map", map), NativeParam("Key", k)) to
            listOf(NativeParam("Value", v.orNull()))
        BuiltinNodes.MAP_WITH -> listOf(NativeParam("Map", map), NativeParam("Key", k), NativeParam("Value", v)) to
            listOf(NativeParam("Result", map))
        BuiltinNodes.MAP_WITHOUT -> listOf(NativeParam("Map", map), NativeParam("Key", k)) to
            listOf(NativeParam("Result", map))
        BuiltinNodes.MAP_KEYS -> listOf(NativeParam("Map", map)) to listOf(NativeParam("Keys", TypeRef.list(k)))
        BuiltinNodes.MAP_VALUES -> listOf(NativeParam("Map", map)) to listOf(NativeParam("Values", TypeRef.list(v)))
        BuiltinNodes.LIST_FIRST ->
            listOf(NativeParam("List", TypeRef.list(e))) to listOf(NativeParam("Result", e.orNull()))
        BuiltinNodes.LIST_AT ->
            listOf(NativeParam("List", TypeRef.list(e)), NativeParam("Index", TypeRef(PinType.INT))) to
                listOf(NativeParam("Result", e))
        BuiltinNodes.LIST_ADD ->
            listOf(NativeParam("List", TypeRef.list(e)), NativeParam("Value", e)) to
                listOf(NativeParam("Result", TypeRef.list(e)))
        else -> null
    }
}

private fun PinSpec.asParam(): NativeParam = NativeParam(
    name = name,
    type = type,
    default = typedDefault(type, default),
    // **An unconnected pin has always had a value**, so every catalogue parameter is optional in the sense
    // that leaving it out is not an error — a BOOL defaults to false, an INT to 0. Text keeps that: a
    // required-looking argument that the canvas would have defaulted must not become an error only because
    // the same script was written down.
    hasDefault = true,
)

/**
 * A pin's default, as a VALUE of its declared type rather than as the text an editor would put in the field.
 *
 * [PinSpec.default] is `Any?` and holds what the canvas types into an unconnected pin, so a BOOL's default
 * is the string `"false"` and a COLOR's is `"#FF00FFFF"`. A host therefore received **two different shapes
 * for the same parameter** depending on whether the caller passed it — and only survived because the hosts
 * grew branches to absorb the difference (`Args.tile` accepts a record, a host tile *and* a string). The
 * omitted argument was the odd one out; nothing else in the language hands a host a string where the
 * signature says Bool.
 *
 * **A bridge, and it dies with the authoring rewrite** (`NODE_AUTHORING_PLAN.md` phase 3): once a default is
 * written as a value of its type in the declaration, there is nothing left to convert and this goes with the
 * `Args` string branches. Until then it is the one projection every text-front-end call already passes
 * through, so fixing it here fixes every mistyped default at once instead of 139 times by hand.
 *
 * Pass-through cases are deliberate, not oversights:
 * - a non-String default is already a value — the author wrote one;
 * - STRING and ENUM pins mean their text (an enum member IS its name at run time, the invariant the whole
 *   enum design rests on), so "converting" one would be the bug;
 * - a DECLARED type has no text form and its default, if any, is a host enum member — a name, as above.
 */
private fun typedDefault(type: TypeRef, default: Any?): Any? {
    val text = default as? String ?: return default
    // A type the HOST declared may say how its stored form reads — which is where the tile's `"x,y,plane"`
    // went when `Tile` stopped being a builtin. Asked first, because a declared type has no `builtin` at
    // all and would otherwise fall out below unconverted.
    dev.ziggle.vscript.model.HostRecords.of(type)?.read?.let { return it(text) ?: default }
    val builtin = type.builtin ?: return default
    return when (builtin) {
        PinType.STRING, PinType.ENUM -> default
        // Anything the parse cannot make sense of stays as it was: a default is not a place to fail, and a
        // string that reaches a host is exactly the situation that already obtains today.
        else -> Literals.of(builtin, text) ?: default
    }
}
