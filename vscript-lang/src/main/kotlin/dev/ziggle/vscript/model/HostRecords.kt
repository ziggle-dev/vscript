package dev.ziggle.vscript.model

/**
 * One readable field of a type the HOST provides.
 *
 * [get] is the whole implementation. A host record is not a `StructValue` and is not converted into one:
 * the value travelling through the VM is whatever the host handed over — an `EntityRef`, an `ItemRef` —
 * and a field read calls this on it. So there is no marshalling at the boundary, no copy, and no second
 * representation of the same thing to keep in step.
 *
 * It also means a field can be a **live read**. `distance` and `clickable` are not stored on anything: they
 * are questions about the world right now, which is exactly what the corpus wants (`Entity Info`'s own doc
 * says Clickable "changes every frame, and that is the point rather than a caveat"). A stored snapshot
 * would have been the wrong answer wearing a nicer spelling.
 */
class HostField(
    val name: String,
    val type: TypeRef,
    /** One line for a tooltip and for the manifest. Empty is honest. */
    val describe: String = "",
    /**
     * Read the field off a value of the owning record. Never null-safe — the caller guarantees the type.
     *
     * **Defaulted for a [HostRecord.isData] record, where it is never called anyway.** A data record's
     * values are plain structs of exactly these fields, so the compiler emits a `GETFIELD` and no
     * accessor runs. The default is still the right answer rather than a throw, because the manifest and
     * the debugger read fields off values generically and should not have to know which kind they hold.
     */
    val get: (Any?) -> Any? = { (it as? dev.ziggle.vscript.vm.StructValue)?.get(name) },
)

/**
 * A record the HOST provides — `Entity`, `ItemRef` — as a type a document can read fields off.
 *
 * **The third thing a node library can contribute, and the one that was missing.** It could already offer
 * functions (`NodeDef`) and, since the enum work, closed sets of names (`HostEnum`). What it could not do
 * was hand back something with *structure*: [Prelude] calls `ENTITY` and `ITEM_REF` "a name over a
 * primitive", so they had no fields at all, and the only way to get at what was inside one was a node with
 * a column of outputs —
 *
 * ```
 * val (oid, okind, oname, owhere) = entityInfo(o)
 * ```
 *
 * — which is a record spelled as an adjacent mapping, with the names carried positionally and `okind` a
 * `String` where an enum existed in Kotlin all along. Fifty-eight sites in the corpus are written that way.
 *
 * ### Not a `PinType`
 *
 * `CLAUDE.md` states the rule: a new host type is DATA, and adding an enum constant for it costs an IDE
 * plugin release every time, because the plugin hot-reloads `dev.ziggle.nodes.**` and keeps its own copy of
 * the language. A registered name rides the nodes jar. `Types.register("Json", …)` was already the
 * precedent; this is the same move for a type that has fields.
 */
class HostRecord(
    val name: String,
    val fields: List<HostField>,
    val describe: String = "",
    /**
     * How the rest of the system types a value of this.
     *
     * Defaults to a nominal type over [name], which is what a new one wants. An override exists for the
     * same reason [HostEnum.type] has one: a record replacing a type that already has a [PinType] must
     * keep the pin type, or every pin in the catalogue declared with it would refuse the very values it
     * exists to carry.
     */
    val type: TypeRef = TypeRef.named(name),
    /**
     * What a value of this is actually REPRESENTED as, when it is a nominal type over something simpler.
     *
     * `Item` is an `INT` that refuses to be confused with an npc id; `Tile` is not — it has three fields
     * and no simpler form. Null means "no simpler form", which is the honest answer for most records.
     *
     * ### Why the language needs to be told
     *
     * It cannot work this out. A host record's fields are opaque accessors over a value the host owns, so
     * "what is it underneath" is knowledge only the declarer has. Everything generic the language does
     * with a value needs it:
     *
     *  - **Reading one from JSON.** `as Layout` over a record with an `Item` field was exact while `ITEM`
     *    was a builtin whose run-time form was known. Without this it became "a Item cannot be read from
     *    JSON — it has no written form", which broke a live script the moment the type moved to the pack.
     *  - **Comparing storage** without deciding two types are the same, which is what a list literal's
     *    guessed element type is checked against.
     *  - **Choosing an editor.** A nominal INT wants a number box, or a catalogue if the host has one.
     *
     * It is deliberately NOT assignability: `over = INT` does not make an `INT` an `Item`. That is the
     * whole point of the type, and the one thing this must not undo.
     */
    val over: TypeRef? = null,
    /**
     * A host record this one may be used AS — the one bit of subtyping the language has.
     *
     * **Why it exists, stated where it can be argued with.** Splitting the scene reference into `NpcRef`,
     * `ObjectRef` and `GroundItemRef` is right for identity: an NPC is a server index that follows it
     * around, scenery is an id plus a tile because it does not move, and a field that means nothing for
     * one of them should not be offered on it. But the VERBS do not split the same way — `interact`,
     * `useOn` and the drawing nodes take any live thing and always did, and `Refs.kt` makes exactly that
     * argument for having one type in the first place: they "share a domain — they have a tile, a
     * distance, a name, and can be drawn".
     *
     * Three copies of `interact` would be the price of having no answer here. So the three keep their own
     * types and widen into `EntityRef`, which is what the shared verbs ask for. One rule in
     * `text/Assign.kt`, and none of it reaches the VM: at run time all three are the same class already.
     *
     * Deliberately NOT general subtyping. There is no user-facing spelling for it, a document cannot
     * declare one, and it does not go the other way — an `EntityRef` is not an `NpcRef`, because that is
     * the question `kind` used to answer badly.
     */
    val widensTo: TypeRef? = null,
    /**
     * **A DATA record: its values are plain structs of exactly these fields, and the language owns them.**
     *
     * The default is the other kind, an ACCESSOR record: a value is a host object — an `NpcRef`, an
     * `ItemRef` — that the language cannot see inside, so reading a field calls [HostField.get] on it and
     * BUILDING one is not something the language could do at all. That is right for a handle to something
     * live, and it is what most host records are.
     *
     * It is wrong for a record that is only ever its fields. A tile is three ints; there is no object
     * behind it and no question to ask the host. Declaring one as data buys three things at once, all of
     * which it used to get from being a `PinType`:
     *
     *  - **It can be written.** `Tile { x: …, y: …, plane: … }` resolves and emits a `NEWSTRUCT`, exactly
     *    as a document's own `type` declaration does. An accessor record has no such spelling, because
     *    there would be nothing to make.
     *  - **A field read is a `GETFIELD`**, not a call through a lambda — so `t.plane` costs what it always
     *    cost, and the 92 corpus sites that write a tile literal did not become 92 host calls.
     *  - **It folds.** A record built from constants IS a constant, which is how a tile in a `val` at
     *    document scope stays one value rather than being rebuilt per read.
     *
     * The trade is that the host gives up owning the representation: a data record's values are
     * `StructValue`s of this shape, and a host verb handed one must read it as such. [read] is where a
     * host says how its OWN objects convert in.
     */
    val isData: Boolean = false,
    /**
     * How a value of this is made from the other forms it arrives in — or null when there are none.
     *
     * **Three forms reach a pin and only one of them is the record.** A document STORES what its pickers
     * edit, which for a tile is `"3200,3200,0"` and for a colour is an ARGB int, and those files are not
     * going to be rewritten. A host verb hands back its OWN object, which the language has never seen.
     * And a value that has already been through here arrives as itself.
     *
     * The language cannot reconcile those: only the declarer knows that `"3200,3200,0"` is a tile or that
     * `dev.ziggle.api.scene.Tile` is the same thing wearing a different coat. So it asks, at the two places
     * a foreign value crosses into a typed slot — a literal being read out of a document, and a pin being
     * given a constant — and stores what comes back.
     *
     * Returning null means "not one of mine", and leaves the value alone. That is deliberately the same
     * answer as "there is no reader": a type with nothing to convert and a value that does not convert
     * should behave identically, or the difference shows up as a pin that silently keeps its default.
     */
    val read: ((Any?) -> Any?)? = null,
    /**
     * How a value of this is WRITTEN in source — [read]'s inverse, and null when it has no spelling of
     * its own.
     *
     * A record can always be printed as `Name { field: …, … }`, and that is what the printer falls back
     * to. It is not always what an author wrote. A colour's stored form is a packed int, and printing one
     * as `0xFF3C8CE0` says what it is at a glance where `Color { r: 60, g: 140, b: 224, a: 255 }` makes
     * the reader do the arithmetic. Rewriting the first into the second on a round trip is churn that
     * loses information nobody asked to lose.
     *
     * Only the declarer can know that. The language has no way to guess that four ints called `r`, `g`,
     * `b` and `a` are conventionally written as eight hex digits, and should not learn: this is the hook
     * that lets the domain say so.
     *
     * Returning null falls back — to the type's positional constructor if it declared one, and to the
     * record spelling otherwise.
     */
    val write: ((Any?) -> String?)? = null,
) {
    fun field(name: String): HostField? = fields.firstOrNull { it.name == name }

    /** Every type a value of this may be used as, itself first. Terminates on a cycle by construction. */
    fun widening(): List<TypeRef> {
        val out = ArrayList<TypeRef>()
        var here: HostRecord? = this
        val seen = HashSet<String>()
        while (here != null && seen.add(here.name)) {
            out += here.type
            here = here.widensTo?.let { HostRecords.of(it) }
        }
        return out
    }

    /**
     * The host function a read of [field] compiles to — `Entity.tile`.
     *
     * A name per field, resolved at COMPILE time, rather than one generic `readField(value, "tile")`
     * resolved at run time. The compiler knows both halves statically, `HostRegistry` already binds names
     * to implementations at load, and a per-field name means a missing accessor fails loudly at load
     * rather than returning null somewhere far away.
     */
    fun hostFor(field: String): String = "$name.$field"
}

/**
 * Every record the host provides, by name.
 *
 * Same shape and same boundary as [HostEnums]: what lives here is what the CLIENT provides and is therefore
 * identical in every document. A record a *document* declares does not belong here — those live on the
 * document's own resolution and are looked up first, so a document declaring `type Entity` shadows this one
 * rather than colliding with it.
 */
object HostRecords {

    private val byKey = LinkedHashMap<String, HostRecord>()

    /** Matched as [HostEnums] matches — underscores, dashes and case ignored, so `ITEM_REF` is `ItemRef`. */
    private fun key(name: String) =
        name.filterNot { it == '_' || it == '-' || it.isWhitespace() }.lowercase()

    /** Add one, or replace whatever is registered under the same name. */
    fun register(r: HostRecord) {
        byKey[key(r.name)] = r
    }

    fun registerAll(rs: Iterable<HostRecord>) = rs.forEach(::register)

    /** Every registered record, registration order. */
    val all: List<HostRecord> get() = byKey.values.toList()

    /** By name, on [key]'s terms. Null when nothing is called that. */
    fun of(name: String?): HostRecord? = name?.trim()?.let { byKey[key(it)] }

    /** The record a type refers to, if the host declared one. */
    fun of(type: TypeRef): HostRecord? = of(type.name)

    /**
     * [value] as a value of [type], when [type] is a host record that says how to read one.
     *
     * The single entry point for the conversion, so the two callers that need it — a literal coming out
     * of a document and a constant going into a pin — cannot disagree about what a stored tile is. Hands
     * back [value] untouched for anything else, which is what makes it safe to call unconditionally.
     */
    /**
     * Every DATA record, as the [StructType] the graph front end understands.
     *
     * **The handover `BuiltinTypes` used to be, and this replaced.** The canvas, the validator and the lowering all
     * ask "which records may this document name", and the answer was a document's own plus the language's
     * builtins — so a type that stopped being a builtin stopped being nameable, and a field read on one
     * reported "this has no field 'x'" on a record that plainly has one.
     *
     * Only the data ones, and for the same reason [dataRecord] exists: an accessor record has no shape the
     * graph could construct or read directly, and offering it here would let a `Make` node build a struct
     * the host's own field lambdas cannot read.
     */
    fun dataStructs(): List<StructType> = all.filter { it.isData }.map { r ->
        StructType(r.name, r.fields.map { FunctionPin(it.name, it.type) })
    }

    fun read(type: TypeRef?, value: Any?): Any? {
        val record = type?.let { of(it) } ?: return value
        val reader = record.read ?: return value
        return reader(value) ?: value
    }

    /**
     * Forget whatever a node library registered.
     *
     * For tests, which must not carry one library into the next. Empties outright, unlike [HostEnums.reset]
     * — the language declares no host record of its own, so empty is a state that really occurs (a
     * headless build with no node library).
     */
    fun reset() = byKey.clear()
}
