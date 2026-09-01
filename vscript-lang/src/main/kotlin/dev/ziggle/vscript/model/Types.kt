package dev.ziggle.vscript.model

/**
 * One type an author can name: what it is called, what it holds, and whether a value of it can be written
 * out by hand.
 *
 * [name] is the document's vocabulary and the only form that gets persisted — a list's `Of`, and one day a
 * struct's field. A name rather than an ordinal for the reason a host enum uses names: a document
 * should survive being read, diffed and hand-edited by a person, and an ordinal is meaningless in all three.
 *
 * [type] is how the rest of the system treats it — what colour it draws, what it may connect to, how the
 * compiler moves it. It is a [TypeRef] rather than a [PinType] precisely so a declared type can have one:
 * a built-in's carries its enum kind, a struct's carries only its name, and everything downstream that used
 * to switch on the enum now asks whether there IS one.
 */
class TypeInfo(
    val name: String,
    val type: TypeRef,
    /** One line on what it holds — the picker's small print, and the reason a bare name list is not enough. */
    val describe: String,
    /**
     * Can an author write a value of this by hand?
     *
     * The question behind "what may a list hold": a slot you cannot type into is a slot that can only be
     * wired, and a list of those is better built by the graph than written out. It is the same question as
     * "does this type have an inline editor", asked where the model can see it — the editor answers the
     * same one in [dev.ziggle.vscript.editor.canvas.ValueEditors] and the two must agree.
     */
    val authorable: Boolean,
    /**
     * Can an author NAME this type — for a variable, a function parameter or result, a record's field, a
     * list's element?
     *
     * A different question from [authorable], and the one the type pickers ask. A host's snapshot record
     * (an item stack, say) has no inline editor — nobody types one into a field — yet a function that takes
     * one, a record that holds one and a list of them are all ordinary things to declare; the value is built
     * by the graph and only ever wired. Defaults to [authorable], since a type you can write you can
     * certainly name.
     */
    val declarable: Boolean = authorable,
)

/**
 * The type registry — every type the editor will offer, in the order it offers them.
 *
 * A REGISTRY rather than a hardcoded list because the set is going to grow. Structs and other user-defined
 * types are planned, and they cannot be enum constants: they are declared by a document, not by this file.
 * Everything that asks "what types are there" therefore asks *here* and not `PinType.values()`, so adding a
 * kind means [register] and nothing else — the type picker, the list's element choice, the variable types
 * and the function signature editor all follow without being told.
 *
 * **Host types only.** What lives here is what the CLIENT provides — the built-ins, and anything a plugin
 * node library contributes. Those are the same in every document. A type a *document* declares does not
 * belong here and must not be registered into it: once a graph can import from a library graph, what a name
 * means depends on which document is asking, and an imported function's body has to resolve in its own
 * document's scope rather than the importer's. A singleton cannot answer both. Document-declared types live
 * on [Graph] beside its functions and variables, which is how names have always resolved here — see
 * [Graph.type].
 */
object Types {

    private val byName = LinkedHashMap<String, TypeInfo>()

    /**
     * Add a type, or replace one already registered under the same name.
     *
     * Replacement rather than refusal so a document that redefines a name wins over the built-in — the
     * alternative is a document that cannot be opened, which is a worse answer to a name clash.
     */
    fun register(info: TypeInfo) {
        byName[info.name.lowercase()] = info
    }

    /** Every registered type, registration order. */
    val all: List<TypeInfo> get() = byName.values.toList()

    /** By name, case-insensitively. Null when nothing is called that. */
    fun of(name: String?): TypeInfo? = name?.trim()?.lowercase()?.let { byName[it] }

    /**
     * The types a value can be WRITTEN as — what a list may hold, and the basis of what a variable may be.
     *
     * Derived from [TypeInfo.authorable] rather than listed, so a new authorable type is offered everywhere
     * the moment it is registered.
     */
    val authorable: List<TypeInfo> get() = all.filter { it.authorable }

    /**
     * The types a value can be NAMED as — a parameter, a field, a list element: see [TypeInfo.declarable].
     * Every authorable type is here, and the wire-only ones a host chose to offer.
     */
    val declarable: List<TypeInfo> get() = all.filter { it.declarable }

    /**
     * The types a graph VARIABLE may take.
     *
     * The declarable ones plus [PinType.LIST], which is the odd one out on purpose: you cannot type a list
     * into a default field, but a variable holding one is exactly how a graph accumulates anything. Its
     * default is simply "built by the graph".
     */
    val forVariables: List<TypeInfo> get() = declarable.let { d -> d + listOfNotNull(of("List")).filter { it !in d } }

    /**
     * The name shown for a type, for the places holding a reference rather than a [TypeInfo].
     *
     * A declared type is its own label: nothing here knows about it, and its name is what a document wrote.
     */
    fun label(type: TypeRef): String = all.firstOrNull { it.type == type }?.name ?: type.toString()

    /** What a type holds, for the same places. Empty for one this registry has never heard of. */
    fun describe(type: TypeRef): String = all.firstOrNull { it.type == type }?.describe ?: ""

    init {
        // **The LANGUAGE's own types, and only those.** Order is the order the picker lists them: the plain
        // values first, since they are what most lists hold, then the ones a graph builds rather than
        // writes.
        //
        // Nine entries used to sit in the middle of this list -- `Item`, `Npc`, `Object`, `Tile`, `Color`,
        // `Skill`, `EntityRef`, `ItemRef`, `WidgetRef` -- describing "a shark, a rune scimitar" and "a spot
        // in the world". None was ever a `PinType`; they resolved to whatever a host had declared, so this
        // was never a hole in the type system. It was the type PICKER's menu, and a registry of every type
        // the editor will offer is the host's business: an editor opened against no domain was offering
        // nine types nothing could produce.
        //
        // They are registered by `dev.ziggle.nodes.GameRecords` now, beside the records and enums that
        // actually declare them. `PanelNodes` had already set the pattern for a pack contributing a type.
        register(TypeInfo("Int", TypeRef(PinType.INT), "a whole number", authorable = true))
        register(TypeInfo("Float", TypeRef(PinType.FLOAT), "a number with a decimal point", authorable = true))
        register(TypeInfo("String", TypeRef(PinType.STRING), "any text", authorable = true))
        register(TypeInfo("Bool", TypeRef(PinType.BOOL), "true or false", authorable = true))
        register(TypeInfo("List", TypeRef(PinType.LIST), "several values, in order", authorable = false))
        register(TypeInfo("Map", TypeRef(PinType.MAP), "a lookup from one value to another", authorable = false))
        register(TypeInfo("Choice", TypeRef(PinType.ENUM), "one of a fixed set of names", authorable = false))
        // A DATA type, by name, with no `PinType` of its own — the rule in CLAUDE.md. It needs no inline
        // editor (nobody types a parsed document into a field), no picker and no opcode: it is wire-only,
        // and `as` is the one thing you do with it.
        register(
            TypeInfo(
                "Json", TypeRef.named("Json"),
                "a parsed JSON document — read it as a record with `as`", authorable = false,
            )
        )
        register(TypeInfo("Any", TypeRef(PinType.WILDCARD), "anything at all", authorable = false))
        register(TypeInfo("Exec", TypeRef(PinType.EXEC), "the order things happen in", authorable = false))
    }
}
