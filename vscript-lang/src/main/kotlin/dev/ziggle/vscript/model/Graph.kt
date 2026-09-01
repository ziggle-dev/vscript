package dev.ziggle.vscript.model

/**
 * One node placed on a canvas.
 *
 * Pins are NOT stored here — they come from the [NodeDescriptor] named by [type]. Keeping them in the
 * catalog rather than the document means a graph stays small, and a catalog change (a renamed pin, a new
 * optional input) reaches every existing graph instead of leaving each one frozen with a stale copy.
 *
 * [literals] holds values typed directly into unconnected input pins, keyed by pin name.
 * [variable] names the graph variable a `var.get` / `var.set` node targets.
 */
class Node(
    /**
     * Identity. Handed out monotonically and never reused within a session, because imgui-node-editor caches
     * per-id state and reusing a just-deleted node's id resurrects it on the replacement.
     *
     * `var` for exactly one caller: `Lower.renumber`, applying an `@id` written before an *expression*, whose
     * node is built several layers down and so cannot be given the id at construction. That happens during
     * lowering, before the graph is handed to anyone. Treat it as immutable everywhere else.
     */
    var id: Int,
    val type: String,
    /** Canvas position. Mutable because dragging a node is an edit to the document, and the canvas is
     *  the authority on where a node is while the editor is open. */
    var x: Float = 0f,
    var y: Float = 0f,
    /** Values typed into unconnected input pins, keyed by pin name. Mutable for the same reason as
     *  [x]/[y]: editing a pin's default is an ordinary document edit made through the canvas. */
    val literals: MutableMap<String, Any?> = LinkedHashMap(),
    /** Which graph variable a `var.get` / `var.set` targets. Mutable: re-pointing a node at a different
     *  variable is an ordinary edit, and renaming one has to rewrite every node that names it. */
    var variable: String? = null,
    /** For a comment container, its heading — the text in the title strip. Optional. */
    var comment: String? = null,
    /** For a comment container, its body text: the note itself, word-wrapped inside the box. */
    var body: String? = null,
    /**
     * The user function whose BODY this node is part of, or null for the main graph.
     *
     * Stored rather than derived from what currently sits inside a function box. Spatial membership is how
     * comments work and it is wrong here: nudging a node a few pixels past an edge would silently drop it
     * out of the function, and the graph would still compile — it would just quietly do less.
     *
     * On a function BOX this names the function the box defines, so a box is a member of its own body.
     */
    var function: String? = null,
    /** For a Call node, the user function it invokes — as against [function], the body it lives in. */
    var callee: String? = null,
    /**
     * The container this node was put INTO — a comment or a function box — by node id, or null for none.
     *
     * Recorded for the same reason [function] is, and learned the same way: worked out from rectangles at
     * the moment of asking, a container owns whatever it happens to be over. Dragging one comment across
     * another's contents therefore stole them, and dragging one over a function's body pulled those nodes
     * out of the function entirely. A node belongs to what it was dropped into until it is dragged out.
     *
     * Distinct from [function], which is semantic: this is where a node SITS, that is what it is PART of.
     * A node dropped into a comment nested in a function box has both, and they say different things.
     */
    var group: Int? = null,
    /** For a container, whether it is folded down to its title bar. */
    var folded: Boolean = false,
    /** Size, used only by container nodes (comment boxes). Zero means "size to content". */
    var w: Float = 0f,
    var h: Float = 0f,
)

/** A wire from one node's output pin to another node's input pin. */
class Link(
    val id: Int,
    val fromNode: Int,
    val fromPin: String,
    val toNode: Int,
    val toPin: String,
)

/**
 * One parameter or result of a user function.
 *
 * Deliberately not a [PinSpec]: a signature is document data an author edits, while a PinSpec carries editor
 * concerns (choices, whether the pin is typed into) that a function's own pins have no use for. The pins the
 * editor draws are derived from these — see [resolveNode].
 */
class FunctionPin(
    val name: String,
    val type: TypeRef,
    /**
     * A parameter's default, or null when it has none.
     *
     * **This is the whole of what a default costs**, because a Call node's pins are built from the
     * signature and `PinSpec` has carried a default all along: an argument nobody supplied already falls
     * back to its pin's default when the chunk is compiled. So the feature is a field, one line where the
     * pins are built, and the validator no longer complaining that a pin with an answer has none.
     */
    val default: Any? = null,
) {
    constructor(name: String, type: PinType) : this(name, TypeRef(type))
}

/**
 * A user function: a named, reusable body with its own signature.
 *
 * The body is whichever nodes name it (see [Node.function]); the signature is what a Call node shows. Both
 * halves live in the document, so a function survives being saved, reopened and diffed.
 */
class GraphFunction(
    val name: String,
    val params: List<FunctionPin> = emptyList(),
    val results: List<FunctionPin> = emptyList(),
    /** `export fn` — reachable through an import. Nothing is, unless it says so; see [GraphImport]. */
    val isExported: Boolean = false,
    /**
     * The type this extends, when it was declared `fn List.add(…)` — otherwise null.
     *
     * **An extension is an ordinary function with a receiver, and the receiver is its first parameter.**
     * `fn List.add(value: Any)` IS `fn add(self: List, value: Any)`: [params] holds `self` first, this says
     * where it came from, and `xs.add(v)` lowers to the `function.call` node that already exists. Nothing
     * new runs — no node type, no opcode, no VM change — which is the whole argument for the feature.
     *
     * So this field is not machinery, it is a RECORD OF THE SPELLING, and two things read it. The printer,
     * which writes a call to one in dot form; and the resolver, which refuses to call it any other way.
     * Without that refusal `xs.add(v)` and `add(self: xs, value: v)` would be two spellings of one graph —
     * the collision VSCRIPT_LANG_PLAN.md §6.7 rejects sugar for.
     */
    val receiver: TypeRef? = null,
) {
    val isExtension: Boolean get() = receiver != null

    /**
     * The receiver parameter, when there is one — the FIRST parameter, and only if it is called `self`.
     *
     * `takeIf` rather than "the first parameter of any extension", and that is the whole of type-level
     * functions: `fn Vec2.new(x: Int, y: Int)` extends `Vec2` and declares no `self`, so it has no
     * receiver to bind and is called on the TYPE — `Vec2.new(5, 3)`. One that declares `self` is called on
     * a VALUE of the type, as before.
     *
     * **Derived, never stored.** That is what makes this cheaper than the `impl` block it replaces: an
     * `impl` needed a `grouped` bit on this class purely so the printer could reproduce a spelling, and
     * storing a bit to remember syntax is the thing every other decision here was shaped to avoid. Here
     * the answer is already in `params`, so the two spellings cannot disagree.
     */
    val self: FunctionPin? get() = params.firstOrNull()?.takeIf { it.name == SELF }

    /** Extends a type but takes no receiver — `Vec2.new(5, 3)`, called on the type itself. */
    val isTypeLevel: Boolean get() = receiver != null && self == null

    companion object {
        /** What an extension's receiver parameter is called, in its signature and inside its body. */
        const val SELF = "self"

        /**
         * The implicit result a MUTATING extension hands its receiver back through.
         *
         * **Not `self`**, because the box would then carry a parameter and a result of that name and
         * "two pins called 'self'" is a guard worth keeping. Spelled with an `@` so no author can declare
         * a result that collides with it — the same trick the printer markers use, for the same reason:
         * it has to be unmistakable, and it has to be un-writable.
         */
        const val SELF_RESULT = "@self"
    }
}

/**
 * A type this document declares — a record with named, typed fields.
 *
 * **Document data, not registry data.** [Types] holds what the CLIENT provides, which is the same in every
 * graph. This is declared by an author, so it lives here beside [Graph.functions] and [Graph.variables] and
 * resolves the way they do — by asking the document. That is not tidiness: once a graph can import from a
 * library graph, what a name means depends on which document is asking, and an imported function's body has
 * to resolve in its OWN document's scope rather than the importer's. A singleton cannot answer both.
 *
 * Fields are [FunctionPin]s because that is already exactly "a named, typed entry in a signature" — the
 * editor for a function's parameters and the editor for a struct's fields are the same editor, and cloning
 * the class would have meant cloning that too.
 */
class StructType(
    val name: String,
    val fields: List<FunctionPin> = emptyList(),
    /** `export type` — reachable through an import. Nothing is, unless it says so; see [GraphImport]. */
    val isExported: Boolean = false,
    /**
     * `type Pair<A, B>` — the names this record is generic in, in order.
     *
     * **Erased.** There is one [dev.ziggle.vscript.vm.StructShape] for `Pair`, whatever it is instantiated
     * at: the arguments are checked where the record is built and read, and are gone by run time. That is
     * why `x is Pair` works and `x is Pair<INT, STRING>` is refused — the second is a question the running
     * program cannot answer, and answering it approximately would be worse than refusing.
     *
     * Empty for every record anyone has written so far, and the whole class behaves exactly as it did.
     */
    val params: List<String> = emptyList(),
    /**
     * Declared `single` — a record with exactly one of it, which is a [GraphVariable] of the same name.
     *
     * A flag rather than a kind of its own, because underneath there is nothing new: the type is an
     * ordinary record and the instance is an ordinary variable. It exists so [dev.ziggle.vscript.lang.Print]
     * can raise the pair back out as the one declaration that was written, instead of the two it became —
     * the same recognizer every piece of sugar in this language has to earn.
     */
    val isSingle: Boolean = false,
)

/**
 * A closed set of named alternatives a document declares — `enum Phase { Chop, Bank, Walk }`.
 *
 * **Why it is not a [StructType] with no fields.** Both are "a name a document declares", and both make a
 * [TypeRef] whose [TypeRef.builtin] is null, so the temptation to reuse the class is real. They are opposite
 * shapes, though: a record is a product (every field at once) and an enum is a sum (exactly one member), and
 * the two answer "what are its parts" with different meanings. Sharing the class would make
 * `structNamed("Phase")` succeed and `Phase { }` construct a record with no fields — which is worse than a
 * missing type, because it compiles.
 *
 * **A member is carried as its NAME.** `Phase.Chop` is the string `"Chop"` at run time, not an ordinal. That
 * is what `PinType.ENUM` pins have always done, so the two agree with no conversion; it is what makes a saved
 * document legible and diffable, the same argument a name in a document makes; and it survives a round trip
 * with no table to keep in step. An ordinal would be indistinguishable from an `Int` the moment it was
 * mis-wired, and would silently change meaning if a member were inserted rather than appended.
 *
 * The cost, recorded because it is real: comparison is string comparison, and RENAMING a member breaks
 * documents that stored the old spelling. That is the same bargain every other name in a document makes.
 */
class EnumType(
    val name: String,
    /** In declaration order — what the picker offers, and the order the printer writes them back. */
    val members: List<String> = emptyList(),
    /** `export enum` — reachable through an import, on the same terms as a record. */
    val isExported: Boolean = false,
    /**
     * The columns each member carries, in declaration order. Empty for a plain enum.
     *
     * A `FunctionPin` rather than a new class: a name, a type and an optional value is exactly what one is,
     * and it is already what a record's fields and a signature's parameters use.
     */
    val fields: List<FunctionPin> = emptyList(),
    /**
     * Member name → its row, positional against [fields].
     *
     * **Keyed by NAME, not by index**, and that is the same decision as carrying a member as its name at
     * run time rather than as an ordinal. One authority on what identifies a member means the two cannot
     * drift, and it means reordering the declaration moves nothing.
     */
    val values: Map<String, List<Any?>> = emptyMap(),
) {
    /** [member] as this enum spells it, or null when it declares no such member. Case-insensitive. */
    fun member(member: String?): String? =
        member?.trim()?.let { m -> members.firstOrNull { it.equals(m, ignoreCase = true) } }

    /** [field] as this enum spells it, or null when it declares no such field. Case-insensitive. */
    fun field(field: String?): FunctionPin? =
        field?.trim()?.let { f -> fields.firstOrNull { it.name.equals(f, ignoreCase = true) } }

    /**
     * The column for [field], one entry per member in [members] order — what the compiler bakes in.
     *
     * A missing row gives nulls rather than a shorter list, so the column always lines up with [members]
     * index for index. That is what lets the lookup be "find the name, take the same position".
     */
    fun column(field: String?): List<Any?> {
        val i = fields.indexOfFirst { it.name.equals(field?.trim(), ignoreCase = true) }
        if (i < 0) return members.map { null }
        val default = fields[i].default
        return members.map { m ->
            val row = values[m]
            // Length-checked rather than null-coalesced: a column may legitimately hold null, and `?:`
            // would silently replace a written null with the default.
            if (row != null && i < row.size) row[i] else default
        }
    }
}

/** A graph-scoped variable: named, typed, mutable state that survives across the whole run. */
class GraphVariable(
    val name: String,
    val type: TypeRef,
    val default: Any? = null,
    /**
     * `export var` — reachable through an import. See [GraphImport].
     *
     * **Nothing crosses a boundary unless it says so.** The polarity used to be the other way, and what it
     * cost was visible in the corpus: 135 of 143 library declarations were public, so "what this document
     * offers" was never a decision anybody made. A surface you have to opt into is one somebody chose.
     */
    val isExported: Boolean = false,
    /**
     * Written `val` — bound once by an initialiser, and refused to every assignment after it.
     *
     * A property of the DECLARATION rather than a separate kind of thing, because underneath it is an
     * ordinary graph variable: it has a slot, the prologue writes it, and every reader is a Get. Only two
     * things differ, and both are decided here — `Lower` refuses an assignment to it, and `Print` writes it
     * back as `val` rather than as a `var` with an `@init`.
     *
     * A folded `val` never reaches this: a value written out becomes a literal node, exactly as a `const`
     * does, and there is no variable to mark.
     */
    val isImmutable: Boolean = false,
) {
    constructor(name: String, type: PinType, default: Any? = null) : this(name, TypeRef(type), default)
}

/**
 * A visual script document.
 *
 * [id] is a stable GUID so cross-document references (a graph calling a library function) survive renaming.
 */
class Graph(
    val id: String,
    val name: String,
    val nodes: List<Node> = emptyList(),
    val links: List<Link> = emptyList(),
    val variables: List<GraphVariable> = emptyList(),
    val functions: List<GraphFunction> = emptyList(),
    val types: List<StructType> = emptyList(),
    /** The closed name-sets this document declares — see [EnumType]. */
    val enums: List<EnumType> = emptyList(),
    /**
     * Other documents this one may name, each under an alias — see [GraphImport].
     *
     * A declaration, not a node, and it sits here for the same reason [functions] and [types] do: it is
     * something the document *is*, not something that runs. The "a text construct with no node cannot
     * round-trip" rule in VSCRIPT_LANG_PLAN.md §1 governs statements; `var`, `type` and `fn` are already
     * document fields and print from here rather than from any node.
     */
    val imports: List<GraphImport> = emptyList(),
    /**
     * The name of the declaration this document is FOR — `export default fn run(…)`.
     *
     * A name rather than the declaration itself, because that is all an importer needs: `import run from
     * "x"` resolves to whatever this says and then binds it exactly as `import { <that> as run }` would.
     * So a default costs no new lookup path anywhere below here — only the indirection that finds which
     * declaration it is.
     *
     * Null when the document declares no default, which is most of them.
     */
    val defaultExport: String? = null,
    /**
     * Names this document exported in a LIST at the bottom rather than on the declaration — `export { a }`.
     *
     * Spelling, exactly as `Block.braced` is: nothing below the printer reads it, and the declaration it
     * names carries `isExported` either way. It is here because the two forms are a real authorial choice
     * — a file that reads as code with its API on the last line — and a round trip that rewrote one into
     * the other would restructure the file every time anything printed it.
     *
     * Holds only the names NOT also exported at their declaration, so saying it twice prints once.
     */
    val exportList: List<String> = emptyList(),
) {
    private val nodesById = nodes.associateBy { it.id }

    fun node(id: Int): Node? = nodesById[id]

    /**
     * Every entry node that starts a FIBER, in id order.
     *
     * A function's entry node is an entry in every other sense — no exec input, one exec output, the start
     * of a chain — but it begins a *call*, not a fiber. Being inside a body is what tells them apart.
     */
    fun entries(catalog: NodeCatalog): List<Node> =
        nodes.filter {
            catalog[it.type]?.kind == NodeKind.ENTRY && it.function == null &&
                it.type !in BuiltinNodes.DRIVEN_ENTRIES
        }.sortedBy { it.id }

    /**
     * Every entry node, of every kind, in the order they were WRITTEN.
     *
     * For the printer, which has to give a file back the way it was given — so it cannot ask for the four
     * kinds separately and concatenate, or a document that opened with `on render` would come back with it
     * last.
     */
    fun allEntries(catalog: NodeCatalog): List<Node> =
        nodes.filter { catalog[it.type]?.kind == NodeKind.ENTRY && it.function == null }.sortedBy { it.id }

    /**
     * The handlers to run when the script ends.
     *
     * Entry nodes in every sense except WHEN: they start a fiber, but only once the others are done, so
     * they are kept out of [entries] rather than being started alongside the thing they are reporting on.
     *
     * [renderEntries] and [tickEntries] are kept out for the same reason and it matters more there, because
     * the two ways of running one are not equivalent: as an ordinary fiber it would run outside the pass
     * budget, outside the staged writes, and forever if it loops. Left in, a render entry ran BOTH ways at
     * once — the per-frame pass overrunning and rolling back exactly as intended, while a stray fiber
     * beside it committed the same writes straight to the globals.
     */
    fun stopEntries(): List<Node> =
        nodes.filter { it.type == BuiltinNodes.ENTRY_STOP && it.function == null }.sortedBy { it.id }

    /** The per-frame draw entries — see [BuiltinNodes.ENTRY_RENDER]. */
    fun renderEntries(): List<Node> =
        nodes.filter { it.type == BuiltinNodes.ENTRY_RENDER && it.function == null }.sortedBy { it.id }

    /** The per-game-tick entries — see [BuiltinNodes.ENTRY_TICK]. */
    fun tickEntries(): List<Node> =
        nodes.filter { it.type == BuiltinNodes.ENTRY_TICK && it.function == null }.sortedBy { it.id }

    /**
     * The handlers that get the script ready, before [entries] are spawned — see [BuiltinNodes.ENTRY_WAKE].
     *
     * Kept out of [entries] for the same reason [stopEntries] is: they start fibers, but at a moment of
     * their own rather than alongside the work. Here the moment is BEFORE, and it has to be a real phase
     * — a `waitsFor` edge would release the work the instant this one waited on the actuator.
     */
    fun wakeEntries(): List<Node> =
        nodes.filter { it.type == BuiltinNodes.ENTRY_WAKE && it.function == null }.sortedBy { it.id }

    /** The handlers that hand the account over — see [BuiltinNodes.ENTRY_SLEEP]. */
    fun sleepEntries(): List<Node> =
        nodes.filter { it.type == BuiltinNodes.ENTRY_SLEEP && it.function == null }.sortedBy { it.id }

    /**
     * The entry a computed variable default is initialised at the head of — the first one that RUNS.
     *
     * **Asked in two places that must not disagree.** `Lower.initsGoOn` decides where to EMIT the `@init`
     * prefix; `GraphCompiler.prologueChunkFor` decides where to START WALKING to find it again for an
     * importer. They were separately written and separately right until `on wake` arrived: the emitter
     * learned that a wake runs first, the finder kept asking `entries(catalog)`, which is start entries
     * only. So an imported library with a wake got an EMPTY prologue — its defaults never ran, its
     * variables sat at their type's zero, and the fault surfaced somewhere else entirely.
     *
     * One function, asked by both, so the next entry kind cannot reopen it.
     */
    fun initialiserEntry(catalog: NodeCatalog): Node? =
        wakeEntries().firstOrNull() ?: entries(catalog).firstOrNull()

    fun function(name: String): GraphFunction? = functions.firstOrNull { it.name == name }

    /** The nodes making up [name]'s body, excluding the box itself. */
    fun bodyOf(name: String, catalog: NodeCatalog): List<Node> =
        nodes.filter { it.function == name && catalog[it.type]?.kind != NodeKind.FUNCTION }

    /**
     * [name]'s box — where its body begins and where it returns.
     *
     * The box IS the boundary: its exec output starts the body, its exec input ends the call, and its
     * parameters and results are its pins. There is no separate node for either end.
     */
    fun entryOf(name: String): Node? =
        nodes.firstOrNull { it.type == BuiltinNodes.FUNCTION && it.function == name }

    /** The link feeding [nodeId]'s input pin [pin], or null when the pin is unconnected. */
    fun linkInto(nodeId: Int, pin: String): Link? =
        links.firstOrNull { it.toNode == nodeId && it.toPin == pin }

    /**
     * Links leaving [nodeId]'s output pin [pin].
     *
     * A data output may fan out to many inputs; an exec output should drive at most one (the validator
     * enforces that, since "which one runs first" has no answer).
     */
    fun linksFrom(nodeId: Int, pin: String): List<Link> =
        links.filter { it.fromNode == nodeId && it.fromPin == pin }

    /** The single node an exec output pin leads to, or null. */
    fun execTarget(nodeId: Int, pin: String): Int? =
        links.firstOrNull { it.fromNode == nodeId && it.fromPin == pin }?.toNode

    fun variable(name: String): GraphVariable? = variables.firstOrNull { it.name == name }

    /**
     * What a type NAME means here: this document's declarations first, then the host's.
     *
     * The order is the whole rule, and it is the same one [function] and [variable] follow. Later, when a
     * graph can import from a library graph, imports go in the middle — one line here rather than a change
     * everywhere a type is resolved, which is the reason resolution is a lookup on the document at all.
     */
    fun type(name: String?): TypeInfo? {
        val clean = name?.trim() ?: return null
        types.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let {
            return TypeInfo(it.name, TypeRef.named(it.name), describe(it), authorable = false)
        }
        enums.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return info(it) }
        return Types.of(clean)
    }

    /** Every type an author may pick here — the host's, plus this document's own. */
    fun declarableTypes(): List<TypeInfo> =
        Types.forVariables +
            types.map { TypeInfo(it.name, TypeRef.named(it.name), describe(it), false) } +
            enums.map { info(it) }

    fun struct(name: String?): StructType? =
        name?.let { n -> types.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

    /** The enum [name] names here, or null. Case-insensitive, like every other type lookup. */
    fun enum(name: String?): EnumType? =
        name?.let { n -> enums.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

    /**
     * The record and the enum EXACTLY called [name] — for deciding what an exported NAME is.
     *
     * [struct] and [enum] are case-insensitive on purpose, so a type POSITION may write `tile` for `Tile`.
     * Asking "what kind of thing is the export called `target`" is a different question with the same
     * shape, and the loose answer is wrong there: a document declaring `enum Target` beside `fn target`
     * had its FUNCTION match the ENUM, which minted a second enum called `@1::target` — and since the
     * lookup that reads that list is case-insensitive too, `Target.SnowyKnight` then came out typed
     * `@1::target` and refused to wire into `@1::Target`, on a document that is written correctly.
     */
    fun structExactly(name: String): StructType? = types.firstOrNull { it.name == name }

    /** See [structExactly]. */
    fun enumExactly(name: String): EnumType? = enums.firstOrNull { it.name == name }

    /** A copy with parts replaced — documents are treated as immutable. */
    fun copy(
        id: String = this.id,
        name: String = this.name,
        nodes: List<Node> = this.nodes,
        links: List<Link> = this.links,
        variables: List<GraphVariable> = this.variables,
        functions: List<GraphFunction> = this.functions,
        types: List<StructType> = this.types,
        enums: List<EnumType> = this.enums,
        imports: List<GraphImport> = this.imports,
        defaultExport: String? = this.defaultExport,
        exportList: List<String> = this.exportList,
    ): Graph = Graph(
        id, name, nodes, links, variables, functions, types, enums, imports, defaultExport, exportList,
    )

    /** The import declared under [alias] here, or null. */
    fun import(alias: String): GraphImport? = imports.firstOrNull { it.alias == alias }
}

/**
 * An enum as the pickers see it — and the one declared type that IS authorable.
 *
 * A record is not: there is no way to type `Point { x: 1, y: 2 }` into a default field, so its slot can only
 * be wired. An enum's whole value is one name from a known list, which is a dropdown — exactly what a
 * `PinType.ENUM` pin already draws. Saying so here is what lets `var State: Phase = Phase.Chop` store its
 * default as the member NAME on the declaration, instead of becoming an initialiser statement in `on start`
 * that then has to be printed back onto the declaration to round-trip.
 */
private fun info(e: EnumType): TypeInfo =
    TypeInfo(
        e.name, TypeRef.named(e.name),
        if (e.members.isEmpty()) "a choice with no members yet" else "one of: ${e.members.joinToString(", ")}",
        authorable = true,
    )

/** A struct's one-line summary, for the pickers: what it holds, written from its own fields. */
private fun describe(t: StructType): String =
    if (t.fields.isEmpty()) "a record with no fields yet"
    else t.fields.joinToString(", ") { "${it.name}: ${Types.label(it.type)}" }
