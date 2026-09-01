package dev.ziggle.vscript.nodes

import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostFn
import dev.ziggle.vscript.vm.HostKind

/**
 * Declaring what the language can DO — as functions, which is what they are.
 *
 * ### Why this exists
 *
 * The other DSL in this file's neighbour ([query], [command], [action]) describes **pins**: a
 * [PinSpec] is a thing an editor draws, and the catalogue used it to define a language's functions. Every
 * half-migration this codebase has had — `Tile`, `Color`, `Skill`, `EntityRef`, `ItemRef` each becoming a
 * real type while the pins declaring them were left behind — is that mismatch surfacing in turn. Three
 * consequences, all measured rather than supposed:
 *
 * - **a default was the text an editor would type**, so 139 pins declared `default = "false"` for a Bool
 *   and `"#FF00FFFF"` for a Color, and a host got a different shape depending on whether the caller
 *   passed the argument;
 * - **bodies read arguments by INDEX** — 320 `Args.x(a, 0)` — so inserting a parameter silently re-points
 *   every read below it, and a compound node's outputs could only ever be *appended* (`SceneNodes` carries
 *   the scar: *"LAST, so every existing positional destructure keeps the pins it already named"*);
 * - **results were a positional array matching a list written elsewhere** — two lists, kept in step by
 *   hand, checked by nothing.
 *
 * Here a function is a name, prose, typed [param]s, typed [result]s and a body that reads them **through
 * their declarations**. A default is a value of the declared type, so `default = "false"` for a Bool stops
 * compiling. Nothing names a [PinType].
 *
 * ### What it produces
 *
 * A [NodeDef] — the same thing the old verbs produce. Authoring says *function*; the output says *node*,
 * because a node is what the canvas draws and what the manifest lists, and the word survives exactly where
 * it is still true. The editor loses nothing: everything [PinSpec] carries beyond a signature is either
 * derivable (a wire's colour and its inline editor come from the type; a dropdown's options come from the
 * enum's members; a pin's position is the order fields were declared) or was never used by the game
 * catalogue at all (`typeChoice` and `editable` belong to builtin nodes).
 *
 * ### Purity is still derived from the verb
 *
 * [FuncBuilder.query] / [FuncBuilder.command] / [FuncBuilder.action] carry the same meanings they always
 * have, and an author still cannot state `INLINE` versus `BLOCKING` directly. That part of the old DSL was
 * right, and it is why no node in the catalogue has ever had the wrong threading.
 */

// ---------------------------------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------------------------------

/**
 * A language type, paired with the Kotlin type a body works with.
 *
 * **The pairing is the whole point, and it is why this is a value rather than a `reified T`.** The map from
 * Kotlin types to language types is not injective and never can be: `Item`, `Npc` and `Object` are all an
 * `Int` at run time and are three different types to a script — that is the entire reason they were split
 * — so `typeRefOf<Int>()` has no answer. Passing the type as a value states it once, at the declaration,
 * where the author already knows.
 *
 * Three functions, and each one closes a hole the old DSL had:
 *
 * - [ref] is what the pin declares. No author writes a [PinType] again.
 * - [read] turns what arrives in the register into a [T]. This is where the 320 hand-written
 *   `Args.tile(a, 0)` calls go: written once per TYPE instead of once per use, and the field's name comes
 *   with it so a decoder that has to fail can say which argument it was.
 * - [store] turns a default — a real value the Kotlin compiler checked — into the form a pin carries.
 *   Usually identity; the two record types render the storage spelling the canvas has always used, so the
 *   manifest and every saved document are unchanged by an author writing `Tile(3200, 3210, 0)` instead of
 *   `"3200,3210,0"`.
 */
class VsType<T> internal constructor(
    val ref: TypeRef,
    internal val read: (String, Any?) -> T,
    internal val store: (T) -> Any?,
) {

    /** A LIST of these — element reader and all, so a `LIST<Tile>` decodes its elements as tiles. */
    fun list(): VsType<List<T>> = VsType(
        TypeRef.list(ref),
        { name, v -> (v as? Collection<*>)?.map { read(name, it) } ?: emptyList() },
        { it },
    )

    /**
     * The same type, admitting null.
     *
     * Reading it never calls [read] on a null, so a decoder written for a present value does not have to
     * defend against the absent one.
     */
    fun orNull(): VsType<T?> = VsType(
        ref.orNull(),
        { name, v -> if (v == null) null else read(name, v) },
        { v -> if (v == null) null else store(v) },
    )

    companion object {
        /** [store] defaults to identity: a value written by an author is already the value. */
        fun <T> of(ref: TypeRef, read: (String, Any?) -> T): VsType<T> = VsType(ref, read, { it })

        fun <T> of(ref: TypeRef, read: (String, Any?) -> T, store: (T) -> Any?): VsType<T> =
            VsType(ref, read, store)
    }
}

/**
 * The types the LANGUAGE itself provides — the ones with no game behind them.
 *
 * A host adds its own beside these; see the SDK's `dev.ziggle.nodes.Types`. Lower-case names on purpose:
 * these are values, not classifiers, and `int` cannot be confused with `Int` the way a top-level
 * `val Int` could.
 */
object Vs {

    /**
     * A whole number, read at full width.
     *
     * `Long` and not `Int`, because the language's integer is Long-wide and the one place that matters is
     * the clock: epoch-millis passed `Int`'s ceiling in 1970-something, and a narrowing read of one does
     * not fail — it hands back a plausible number with the top bits gone. A body wanting a count can say
     * `.toInt()` where it needs one; nothing can recover a truncated timestamp.
     */
    val int: VsType<Long> = VsType.of(TypeRef(PinType.INT)) { _, v -> num(v)?.toLong() ?: 0L }

    val float: VsType<Double> = VsType.of(TypeRef(PinType.FLOAT)) { _, v -> num(v)?.toDouble() ?: 0.0 }

    val string: VsType<String> = VsType.of(TypeRef(PinType.STRING)) { _, v -> v?.toString().orEmpty() }

    val bool: VsType<Boolean> = VsType.of(TypeRef(PinType.BOOL)) { _, v ->
        when (v) {
            is Boolean -> v
            null -> false
            // A saved document may still hold the editor's text. Accepted here rather than at every
            // reader, and it costs nothing once no author writes one.
            else -> v.toString().trim().equals("true", ignoreCase = true)
        }
    }

    /** Anything at all — for the handful of verbs that genuinely do not care. */
    val any: VsType<Any?> = VsType.of(TypeRef(PinType.WILDCARD)) { _, v -> v }

    /** A LIST whose element type is unstated — what a verb taking "some values" declares. */
    val anyList: VsType<List<Any?>> = VsType.of(TypeRef(PinType.LIST)) { _, v ->
        (v as? Collection<*>)?.toList().orEmpty()
    }

    /** A member of a host enum, as the name it is at run time. */
    fun enum(e: HostEnum): VsType<String> = VsType.of(e.type) { _, v -> v?.toString().orEmpty() }

    /**
     * A host record, read as whatever class the host represents it with.
     *
     * Unchecked, and deliberately so: the language cannot know that an `EntityRef` in a register is an
     * `dev.ziggle.vscript.model.EntityRef`, because deciding that is exactly what registering the record
     * did. A value of the wrong class arrives as null rather than as a class-cast three frames later.
     */
    inline fun <reified T : Any> record(r: HostRecord): VsType<T?> =
        VsType.of(r.type) { _, v -> v as? T }

    private fun num(v: Any?): Number? =
        (v as? Number) ?: v?.toString()?.trim()?.toLongOrNull() ?: v?.toString()?.trim()?.toDoubleOrNull()
}

// ---------------------------------------------------------------------------------------------------
// Fields
// ---------------------------------------------------------------------------------------------------

/** Which half of a signature a [Field] is. They are the same shape; only the direction differs. */
enum class Role { PARAM, RESULT }

/**
 * One named, typed slot — a parameter or a result.
 *
 * One class for both, so *"outputs have the same shape as inputs"* is true in the code rather than in a
 * comment. `input`/`output` would have been the pin words; `param`/`result` are the function words, which
 * is what these are.
 *
 * A body never sees [index]. It reads and writes through the declaration itself — `entity()`, `name set …`
 * — which is the property that makes inserting a field in the middle safe.
 */
class Field<T> internal constructor(
    val name: String,
    val type: VsType<T>,
    val doc: String,
    val default: T?,
    val hasDefault: Boolean,
    val role: Role,
    internal val index: Int,
) {
    internal fun spec(): PinSpec = PinSpec(
        name = name,
        type = type.ref,
        // Through [VsType.store], so a default written as a value renders in the form a pin carries. The
        // author's `Tile(3200, 3210, 0)` and the canvas's `"3200,3210,0"` are then the same pin.
        default = default?.let { type.store(it) },
        doc = doc,
    )
}

/**
 * What a body reads and writes. One per invocation.
 *
 * Reading is `field()` and writing is `field set value`, both typed by the field's own declaration — so a
 * result set to the wrong kind of thing is a Kotlin error at the line that wrote it, and a parameter read
 * is already decoded.
 */
interface CallScope {
    /** This call's value for [this] — the argument, or the declared default when none was passed. */
    operator fun <T> Field<T>.invoke(): T

    /** Supply a result. Setting one twice is the second value; not setting one at all leaves it null. */
    infix fun <T> Field<T>.set(value: T)
}

private class Call(
    private val args: Array<Any?>,
    val out: Array<Any?>,
    val given: BooleanArray,
) : CallScope {
    override fun <T> Field<T>.invoke(): T {
        require(role == Role.PARAM) { "'$name' is a result, not a parameter" }
        // **An ABSENT slot takes the declared default; a slot holding null does not.** The VM always
        // passes one argument per data pin and fills an unwired one from the signature, so this arm never
        // fires there — it fires for a body invoked directly, and it is what stops a direct call from
        // disagreeing with a VM call about what the default is. A slot that is PRESENT and null is a
        // different thing entirely: something upstream produced nothing, and the type's own reading of
        // nothing (0, "", false, null) is the answer, exactly as it is today.
        if (index >= args.size) default?.let { return it }
        return type.read(name, args.getOrNull(index))
    }

    override fun <T> Field<T>.set(value: T) {
        require(role == Role.RESULT) { "'$name' is a parameter, not a result" }
        out[index] = value
        given[index] = true
    }
}

// ---------------------------------------------------------------------------------------------------
// Functions
// ---------------------------------------------------------------------------------------------------

/** The exec pin every impure node carries in and out. The same shape the old verbs use. */
private val EXEC_IN = PinSpec("Exec", PinType.EXEC)
private val EXEC_OUT = PinSpec("Exec", PinType.EXEC)

/**
 * Builds one function.
 *
 * Declaration order is pin order, which is the only thing position still decides — and it decides it in
 * one place instead of in a body's `a[3]` as well.
 */
class FuncBuilder internal constructor(
    /** The catalogue-wide identity: `scene.nearestNpc`. Its `.`-prefix is its library. */
    val type: String,
    defaultCategory: String,
) {
    private var title: String = type.substringAfterLast('.')
    private var category: String = defaultCategory
    private var doc: String = ""
    private val params = ArrayList<Field<*>>()
    private val results = ArrayList<Field<*>>()

    /** What the palette calls it. Defaults to the last segment of [type] rather than being mandatory. */
    /** Set by [receiver] — what this verb is written on, or null when it is called by name. */
    private var on: TypeRef? = null

    /** Set by [spelledAs] — the name this is written as, when the derived one is not wanted. */
    private var spelling: String? = null

    fun title(text: String) { title = text }

    /**
     * How this verb is WRITTEN, when the derived spelling is not the one wanted.
     *
     * A node is normally written as its type's last segment, which is right almost always. It is not
     * right when a domain wants a name the derivation cannot reach — `tile(x, y, plane)` is how a
     * position has been written since before it was a node, and leaving it to derivation makes it either
     * `tile` by luck or `scene.tile` by collision with the drawing verb of the same name.
     *
     * Claimed rather than merely preferred: a derived name will not take this out from under it.
     */
    fun spelledAs(name: String) { spelling = name }

    /** Which palette group it sits in. Usually inherited from the enclosing [library]. */
    fun category(text: String) { category = text }

    /**
     * What it does, in prose — the palette's summary and an IDE's hover.
     *
     * Trimmed of the indentation a raw string picks up from the source, so a multi-paragraph explanation
     * can be written where it belongs instead of as a `"…" +` chain.
     */
    fun doc(text: String) { doc = text.trimIndent().trim() }

    /**
     * One argument.
     *
     * [default] is a value of [type]'s Kotlin type, checked by the compiler — which is the single change
     * that makes 139 mistyped defaults impossible rather than merely fixed. Omitting it means the pin has
     * none: an unwired pin still has a value (a Bool is false, an Int is 0), because that is what every
     * decoder already does and what the canvas has always meant by an empty field.
     */
    fun <T> param(name: String, type: VsType<T>, doc: String = "", default: T? = null): Field<T> =
        Field(name, type, doc, default, default != null, Role.PARAM, params.size)
            .also { fresh(params, name); params += it }

    /**
     * The value this verb is written ON — `fn INT.toItem() -> Item`.
     *
     * ### Why a domain wants it
     *
     * `n.toItem()` reads as what it is: a conversion belonging to whatever `n` is. Declared as an
     * ordinary parameter it would be `toItem(value: n)`, which is the same function wearing a worse
     * spelling — and the language could already write the good one for a DOCUMENT's functions
     * (`fn LIST<T>.first(self)`) while a host could not. That asymmetry is why seven of one game's
     * conversions ended up hard-coded in the language's intrinsic table.
     *
     * ### What it does
     *
     * Declares the receiver as the FIRST parameter and marks the node an extension. On the canvas nothing
     * changes — a receiver is an input pin like any other. In text the verb stops answering to its bare
     * name and is written on a value instead, which is the same rule a document's own extensions follow.
     *
     * Call it before any [param]: the receiver is argument zero, and the body reads it through the
     * returned field exactly as it reads every other.
     */
    fun <T> receiver(name: String, type: VsType<T>, doc: String = ""): Field<T> {
        require(params.isEmpty()) { "the receiver of '$type' must be declared before its parameters" }
        on = type.ref
        return param(name, type, doc)
    }

    /**
     * One value handed back.
     *
     * Supplied by name in the body, never by position — so the second and third results of a compound
     * read cannot be transposed, which two lists kept in step by hand always could.
     */
    fun <T> result(name: String, type: VsType<T>, doc: String = ""): Field<T> =
        Field(name, type, doc, null, false, Role.RESULT, results.size)
            .also { fresh(results, name); results += it }

    /**
     * A **query**: pure, and read on the client thread with no marshalling.
     *
     * Pure means no exec pins and re-evaluation wherever it is read, which is right for a read and wrong
     * for anything else — two reads of "am I moving" a second apart should give two answers.
     */
    /**
     * A REINTERPRETATION — one value in, the same value out, and no call at run time.
     *
     * For a domain's nominal types over a shared representation: an item id, an npc id and an object id
     * are all ints, and the whole reason they are separate types is that mixing them is a bug that reads
     * perfectly. `n.toItem()` has to be *written*; it should cost nothing to *run*, because nothing
     * happens — the value is already the int it was.
     *
     * The checker still decides whether the call is legal, by the ordinary rules and from the declared
     * types. This only says what to emit, which is a move. Exactly one parameter and one result: there is
     * nothing else sensible to move.
     */
    fun cast(): NodeDef {
        require(params.size == 1) { "a cast takes exactly one value, but '$type' declares ${params.size}" }
        require(results.size == 1) { "a cast hands back exactly one value, but '$type' declares ${results.size}" }
        return build(NodeKind.PURE, HostKind.CAST) { null }
    }

    /**
     * A **constructor**: the result is a record built from the parameters, and no call happens.
     *
     * [cast]'s sibling — see [HostKind.CONSTRUCT]. `tile(x, y, plane)` is a positional spelling for the
     * record `Tile { x: …, y: …, plane: … }`, and both emit the same `NEWSTRUCT`. Declaring one is how a
     * domain gets a constructor spelling of its own without the language shipping it: `tile` was a
     * language INTRINSIC for exactly as long as nothing else could emit a struct.
     *
     * Parameters are matched to the record's FIELDS positionally, so declare them in field order. Exactly
     * one result, whose type must be a data [HostRecord]; a body would never run, so none is taken.
     */
    fun construct(): NodeDef {
        require(results.size == 1) { "a constructor hands back exactly one record, but '$type' declares ${results.size}" }
        return build(NodeKind.PURE, HostKind.CONSTRUCT) { null }
    }

    fun query(body: CallScope.() -> Any?): NodeDef = build(NodeKind.PURE, HostKind.INLINE, body)

    /**
     * A **command**: impure, sequenced, but still INLINE.
     *
     * For things that change editor or overlay state rather than the avatar. The actuator's drain exists
     * to serialise what the avatar does, one at a time; writing a marker into a store is not one of those,
     * and putting it on the drain would queue it behind a walk and paint it seconds late.
     */
    fun command(body: CallScope.() -> Any?): NodeDef = build(NodeKind.IMPURE, HostKind.INLINE, body)

    /**
     * An **action**: impure, and run on the host's drain.
     *
     * No `Success` result, by design: the verbs behind these either do the thing or throw, and a throw is
     * worth more than a boolean nobody wires up — the fiber stops on the node that failed with its
     * arguments still readable.
     */
    fun action(body: CallScope.() -> Any?): NodeDef = build(NodeKind.IMPURE, HostKind.BLOCKING, body)

    /**
     * Per ROLE, not per function.
     *
     * A parameter and a result may share a name, and several in the catalogue do — `names.nearestNpcOfId`
     * takes an `NPC` (which kind) and answers an `NPC` (which one), and that is the clearest pair of names
     * for it. Nothing is ambiguous: the two sides are looked up separately (`input`/`output`), and a body
     * names them through two different Kotlin values rather than through the string.
     *
     * Two on the SAME side are refused, because the second would be unreachable — a lookup by name finds
     * the first and the pin is simply lost.
     */
    private fun fresh(among: List<Field<*>>, name: String) {
        require(among.none { it.name == name }) { "$type already has a field called '$name'" }
    }

    private fun build(kind: NodeKind, hostKind: HostKind, body: CallScope.() -> Any?): NodeDef {
        val ins = params.map { it.spec() }
        val outs = results.map { it.spec() }
        val impure = kind == NodeKind.IMPURE
        val n = results.size
        return NodeDef(
            NodeDescriptor(
                type = type,
                title = title,
                category = category,
                kind = kind,
                inputs = if (impure) listOf(EXEC_IN) + ins else ins,
                outputs = if (impure) listOf(EXEC_OUT) + outs else outs,
                host = type,
                hostKind = hostKind,
                summary = doc,
                receiver = on,
                textName = spelling,
            ),
            HostFn { args ->
                val call = Call(args, arrayOfNulls(n), BooleanArray(n))
                val returned = call.body()
                // A blocking body that hands back a promise is not producing results yet — the VM parks
                // on it and lands whatever the promise completes with, however many results there are.
                if (returned is dev.ziggle.vscript.vm.HostAwait) return@HostFn returned
                when {
                    n == 0 -> null
                    // **The single-result short form.** A function with exactly one result may simply
                    // produce it, so the overwhelmingly common `query { level set … }` can be written
                    // `query { … }`. Only when the body did not set it, so the explicit form always wins
                    // and the two can never disagree; `Unit` is what a body ending in a statement returns
                    // and means "nothing was produced".
                    n == 1 -> if (call.given[0]) call.out[0] else returned.takeUnless { it === Unit }
                    else -> call.out
                }
            },
        )
    }
}

/**
 * Declare one function.
 *
 * [type] is the catalogue-wide name — `scene.nearestNpc`. Inside a [library] block the prefix comes from
 * the library and this is just the last segment.
 *
 * **The block returns a [NodeDef], and the type system is what makes that so**: `query`, `command` and
 * `action` each finish the builder and hand one back, so the last expression of the block is the verb.
 * A declaration that named its parameters and forgot to give them a body does not compile, which is
 * better than one that compiles into a node doing nothing.
 */
fun func(type: String, category: String = "", build: FuncBuilder.() -> NodeDef): NodeDef =
    FuncBuilder(type, category).build()

// ---------------------------------------------------------------------------------------------------
// Libraries
// ---------------------------------------------------------------------------------------------------

/**
 * What one domain of the catalogue contributes: its functions, and the types they are declared with.
 *
 * **Three doors, one block.** Today a library contributes through unrelated seams registered at different
 * times — enums and records on [NodeLibrary]'s construction, functions at [NodeLibrary.install], name-only
 * types through a static registry somewhere else. The split is principled (a type has to be true before
 * anything can resolve a pin naming it; a function needs a VM to bind into) but it is not an author's
 * problem, and a domain that adds a record has no obvious place to put it.
 *
 * This is a plain value with no global side effects. Registration still happens in exactly one place —
 * [NodeLibrary]'s constructor — which is what keeps a headless manifest build and a live client agreeing
 * about what types exist.
 */
class Contribution(
    val defs: List<NodeDef> = emptyList(),
    val enums: List<HostEnum> = emptyList(),
    val records: List<HostRecord> = emptyList(),
) {
    operator fun plus(other: Contribution): Contribution =
        Contribution(defs + other.defs, enums + other.enums, records + other.records)

    companion object {
        /** Every domain's, merged — what the client hands to a single [NodeLibrary]. */
        fun of(parts: List<Contribution>): Contribution =
            Contribution(parts.flatMap { it.defs }, parts.flatMap { it.enums }, parts.flatMap { it.records })
    }
}

/**
 * Builds one domain.
 *
 * [prefix] is the namespace every function in the block gets — `library("scene")` and `func("nearestNpc")`
 * make `scene.nearestNpc`. Stating it once is the point: it was written into all 310 declarations by hand,
 * where the file already said which domain it was.
 */
class LibraryBuilder internal constructor(val prefix: String, private var category: String) {

    private val defs = ArrayList<NodeDef>()
    private val enums = ArrayList<HostEnum>()
    private val records = ArrayList<HostRecord>()

    /** The palette group for every function declared after this line. A function may still override it. */
    fun category(text: String) { category = text }

    /**
     * One function in this library.
     *
     * [name] is the last segment — the [prefix] is added. A name that already contains a `.` is taken as
     * written, which is the escape hatch for the one file that contributes to two namespaces (bank tags
     * add a `ui.` verb) and stops `func("ui.tabOpen")` from becoming `banktag.ui.tabOpen`.
     */
    fun func(name: String, build: FuncBuilder.() -> NodeDef): NodeDef {
        val type = if ('.' in name) name else "$prefix.$name"
        return FuncBuilder(type, category).build().also { defs += it }
    }

    /** A type this library contributes. Registered with the library, before any pin naming it resolves. */
    fun enum(e: HostEnum): HostEnum = e.also { enums += it }

    /** As [enum], for a record. */
    fun record(r: HostRecord): HostRecord = r.also { records += it }

    internal fun done() = Contribution(defs.toList(), enums.toList(), records.toList())
}

/**
 * Declare one domain of the catalogue.
 *
 * ```kotlin
 * fun contribution(host: NodeHost) = library("skills", category = "Skills") {
 *     func("skillLevel") {
 *         doc("The BOOSTED level — what the game will actually let you do right now.")
 *         val skill = param("Skill", skill, default = Skill.ATTACK)
 *         result("Level", int)
 *         query { host.live().skills.boosted(skill()).toLong() }
 *     }
 * }
 * ```
 */
fun library(prefix: String, category: String = "", build: LibraryBuilder.() -> Unit): Contribution =
    LibraryBuilder(prefix, category.ifEmpty { prefix.replaceFirstChar { it.uppercase() } })
        .apply(build)
        .done()
