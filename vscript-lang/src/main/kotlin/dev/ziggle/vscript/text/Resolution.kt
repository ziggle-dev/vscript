package dev.ziggle.vscript.text

import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.lang.AssignStmt
import dev.ziggle.vscript.lang.CallExpr
import dev.ziggle.vscript.lang.Expr
import dev.ziggle.vscript.lang.ForStmt
import dev.ziggle.vscript.lang.FnDecl
import dev.ziggle.vscript.lang.LambdaExpr
import dev.ziggle.vscript.lang.LetStmt
import dev.ziggle.vscript.lang.MemberExpr
import dev.ziggle.vscript.lang.NameExpr
import dev.ziggle.vscript.lang.Program
import dev.ziggle.vscript.lang.Span
import dev.ziggle.vscript.lang.Stmt
import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef

/** Something wrong with the source, at a place in it. Collected rather than thrown — see [Resolver]. */
class TextDiagnostic(
    val span: Span,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    override fun toString(): String = "$span: $message"
}

/**
 * One parameter of anything callable.
 *
 * [hasDefault] rather than "the default is null", because null IS a legitimate default for an optional
 * parameter and the two have to stay apart: an omitted required argument is an error and an omitted
 * optional one is not.
 */
class Param(
    val name: String,
    val type: TypeRef = TypeRef.WILDCARD,
    val default: Any? = null,
    val hasDefault: Boolean = false,
    /**
     * The EXPRESSION a field defaults to, when it is not a plain value.
     *
     * `byName: MAP<STRING, Activity> = emptyMap()` is a call, and a call cannot be a constant. Carrying the
     * expression rather than flattening it to a value is what lets a record with a computed default be
     * built at all — the alternative is a field that silently starts as null and a script that discovers
     * it much later.
     */
    val defaultExpr: Expr? = null,
)

/**
 * What a call site is checked against — a host function and a `fn` the document declares are the same
 * question wearing two hats, and the resolver should not have to ask it twice.
 *
 * [results] is a LIST because the language has always had several: `val (exists, distance) =
 * entityInfo(entity: t)` binds two, and a signature that could only describe one would have to describe
 * that as something else.
 */
class Signature(
    val name: String,
    val params: List<Param> = emptyList(),
    /**
     * What it hands back, IN ORDER AND BY NAME.
     *
     * Names, because a destructuring binds by them: `val (exists, distance) = entityInfo(…)` names two
     * pins and does not care what order they were declared in. A list of bare types could only ever be
     * matched by position, which is the reading that silently takes the wrong value.
     */
    val results: List<Param> = emptyList(),
) {
    /** The type an expression gets from calling this — [TypeRef.NOTHING] when it hands nothing back. */
    val resultType: TypeRef get() = results.firstOrNull()?.type ?: TypeRef.NOTHING
}

/**
 * What a name means, once the resolver has decided.
 *
 * **This is the thing the graph pipeline has nowhere to put**, and the reason a type reached through two
 * imports could become two types (GAPS 22) or a folded record's type could be a per-document string that
 * resolved for seven documents and not the eighth. A binding is an object; two references to one
 * declaration are the same object, and no amount of requalification can make them differ.
 */
sealed class Binding {
    abstract val name: String
    abstract val type: TypeRef
    abstract val span: Span

    /** Whether a write to this name is allowed — `var` yes, `val`/`const` no. */
    open val mutable: Boolean get() = false
}

/** A `let`/`val`/`var` inside a body, or a function's parameter. */
class LocalBinding(
    override val name: String,
    override val type: TypeRef,
    override val span: Span,
    override val mutable: Boolean = false,
    /** A parameter arrives in a register the frame already holds; a local is allocated one. */
    val isParameter: Boolean = false,
) : Binding()

/** A `var` or `const` the document declares — run state, shared by every body in it. */
class DocumentBinding(
    override val name: String,
    /**
     * **Filled in from the initialiser when the declaration does not name one.**
     *
     * A `var` is always written with its type, but a `val` and a `const` need not be — and an unannotated
     * one only got a type when its value was a LITERAL. `val hook = Hooks { … }` is a struct literal, not
     * a literal, so `hook` was WILDCARD: `hook.prepare(…)` then resolved to no callee at all and came back
     * from the compiler as "does not compile a call to 'hook.prepare' yet", which reads like a missing
     * feature and is a missing type.
     *
     * A `var` rather than a second pass, because the answer is not known when the binding is made: the
     * initialiser may name something declared further down, which is why it is deferred to `pendingInits`
     * in the first place. That pass already computes the value's type in order to CHECK it; this keeps it.
     * It only ever replaces an unknown, so nothing that had a type can lose or change one.
     */
    override var type: TypeRef,
    override val span: Span,
    override val mutable: Boolean = false,
) : Binding()

/** A `fn` the document declares. */
class FunctionBinding(
    override val name: String,
    override val span: Span,
    val signature: Signature,
    val decl: FnDecl,
    /**
     * `fn LIST<T>.add(self, value: T) { self = withItemAdded(…) }` — an extension that WRITES ITS RECEIVER.
     *
     * The whole meaning of the form: `xs.add(3)` is `xs = xs.add(3)`, so the call hands the new receiver
     * back and the call site rebinds what it was written on. Without it the convenient spelling would be
     * the one that silently did nothing.
     */
    val writesReceiver: Boolean = false,
) : Binding() {
    override val type: TypeRef
        get() = TypeRef.function(signature.params.map { it.type }, signature.resultType)
}

/** A host function — the game verbs, and everything else the catalogue offers. */
class NativeBinding(val fn: NativeFn) : Binding() {
    override val name: String get() = fn.name
    override val span: Span get() = Span.NONE
    override val type: TypeRef
        get() = TypeRef.function(fn.params.map { it.type }, fn.signature.resultType)
}

/** A `type` the document declares — a record. */
class RecordType(
    val name: String,
    /**
     * `var`, and only so a type can mention ITSELF.
     *
     * `type Activity { ready: fn(Activity) -> BOOL }` is ordinary and was impossible: the record was
     * registered after its fields were resolved, so the field naming it looked up a type that did not
     * exist yet. The name is registered first with no fields and filled in immediately after — the same
     * two-phase shape declarations already use, for the same reason.
     */
    var fields: List<Param>,
    val span: Span,
    /**
     * `single Registry { … }` — the type has exactly one value, and it is named after the type.
     *
     * Two declarations in one: a record, and a document variable of it. That is what makes
     * `Registry.byName` a field read rather than a namespace lookup, and `fn Registry.register(self, …)`
     * an ordinary extension on the type.
     */
    val isSingle: Boolean = false,
    /**
     * `type Pair<A, B>` — the variables this record is generic in, in declaration order.
     *
     * They line up positionally with a written `Pair<INT, STRING>`'s arguments, which is what makes
     * substitution a zip. Empty for the overwhelming majority, and everything below then costs nothing.
     *
     * **Erased at run time.** A `StructValue` carries a type name and its fields and no arguments, so the
     * VM never hears about these — the resolver answers every question about them and the emitter is
     * unchanged. That is the same bargain `Generics.kt` made for functions.
     */
    val params: List<String> = emptyList(),
    /**
     * The module that declared this — [Resolution.ref]. Null for a host or prelude type, which no
     * document declares and which every document therefore sees identically.
     *
     * Stamped here, once, and carried on every [TypeRef] this hands out. That is the whole of the fix for
     * "a type is whatever the reader's import list says it is": a reader that never imported the declaring
     * module still recognises the type, and two modules that both declare a `Config` no longer collide.
     */
    val owner: String? = null,
) {
    fun field(name: String): Param? = fields.firstOrNull { it.name == name }
    val ref: TypeRef get() = TypeRef.named(name).ownedBy(owner)

    /** How this declaration is addressed across modules — see [dev.ziggle.vscript.model.sameDeclaredType]. */
    val key: String get() = if (owner == null) name else "$owner#$name"
}

/**
 * Everything the resolver worked out about one document.
 *
 * **Side tables, not a second tree.** The AST stays the one tree and this hangs off it, because two trees
 * that describe the same program are two things that can disagree — which is the failure this whole
 * front end exists to stop repeating. Keys are AST nodes and the maps are identity-keyed (no `Expr`
 * overrides equals), so an annotation belongs to one occurrence and not to everything that looks like it.
 */
class Resolution(
    val document: Program,
    val typeOf: Map<Expr, TypeRef>,
    val bindingOf: Map<NameExpr, Binding>,
    val calleeOf: Map<CallExpr, Callee>,
    /**
     * Each call's arguments in PARAMETER order, with null where the call left one out for its default.
     *
     * Recorded here so the compiler never repeats label matching. Doing it twice is doing it two ways
     * eventually, and the second way is the one nobody tested.
     */
    val argumentsOf: Map<CallExpr, List<Expr?>>,
    val records: Map<String, RecordType>,
    /**
     * What this document offers an importer — its `export`ed names, and nothing else.
     *
     * Visibility is a property of the DECLARATION, so this is derived rather than a second list that could
     * disagree with it. A name absent from here is not missing; it is private, and an importer being told
     * that is more use than being told it does not exist.
     */
    val exports: Map<String, Binding>,
    val exportedRecords: Map<String, RecordType>,
    /** The enums this document declares, and the ones it lets an importer see. */
    val enums: Map<String, EnumType>,
    val exportedEnums: Map<String, EnumType>,
    /**
     * Extensions, by the NAME of the type they extend — `LIST` for `fn List<T>.first(self)`.
     *
     * Keyed by name and held as a list, because several types may offer the same verb and a receiver
     * decides which one. It is also why a bare-star import exists: an extension has no qualified spelling
     * at the call site, so `xs.first()` can only work if the name came in unqualified.
     */
    val extensions: Map<String, List<FunctionBinding>>,
    /** Functions declared ON a type rather than on a value of it — `Want.carry(…)`. */
    val typeLevel: Map<String, Map<String, FunctionBinding>>,
    val exportedExtensions: Map<String, List<FunctionBinding>>,
    /** The modules this document imported, by the shape they were imported in. */
    val imported: ImportedNames,
    /**
     * The modules this document RE-EXPORTS from.
     *
     * Reachable for names and, until this existed, unreachable for anything else: a front door forwards a
     * barrel's functions without importing it, so the barrel was outside the import graph entirely and a
     * body declared in it was compiled against whichever resolution happened to be asking.
     */
    val reExports: List<Module>,
    /**
     * The binding each `let`/`val`/`var` statement introduced.
     *
     * [bindingOf] answers "what does this NAME mean", which only covers names somebody wrote down. A
     * local that is declared and never read has no entry there and still needs a register, so the
     * declaration site gets its own table rather than the compiler re-deriving one.
     */
    val localOf: Map<LetStmt, LocalBinding>,
    /**
     * The bindings a function's parameters introduced, in declaration order.
     *
     * Exposed for the same reason [localOf] is: registers are keyed by the binding OBJECT, so the
     * compiler has to be given the very bindings the body's names resolved to. Rebuilding equivalent ones
     * would be two objects for one parameter, and every read of it would miss.
     */
    val paramsOf: Map<FnDecl, List<LocalBinding>>,
    /**
     * `xs[i]` on something that is not a LIST — the synthetic `get(…)` call it desugars to.
     *
     * **The operator form is a call, so it is resolved as one.** Rewriting the index into an ordinary
     * extension call here means the compiler needs no case for it: the existing extension path finds the
     * declaration, checks the arguments, and splices it because `op` implies `inline`. A list keeps its
     * `Op.INDEX`, which is one instruction and needs no declaration to exist.
     */
    val indexCallOf: Map<dev.ziggle.vscript.lang.IndexExpr, CallExpr> = emptyMap(),
    /** `xs[i] = v` on something that is not a LIST — the synthetic `set(…)` call it desugars to. */
    val indexSetOf: Map<dev.ziggle.vscript.lang.IndexAssignStmt, CallExpr> = emptyMap(),
    /** The element (and index) binding each `for` introduced — registers are keyed by binding. */
    val loopBindingsOf: Map<ForStmt, List<LocalBinding>>,
    /** What each destructuring `let` and each `if val` bound — registers are keyed by binding. */
    val boundOf: Map<Stmt, List<LocalBinding>>,
    /**
     * Which RESULT slot each destructured name took, in slot order — null where nothing bound that slot.
     *
     * Kept apart from [boundOf] because a destructuring may bind by NAME and so need not take the first
     * results: `val (distance) = entityInfo(…)` binds the second, and a compiler copying result 0 into it
     * would hand back the wrong value.
     */
    val destructuredSlots: Map<Stmt, List<LocalBinding?>>,
    /**
     * `entityInfo(e).distance` — which RESULT a member access on a multi-result call selects.
     *
     * A call's TYPE is its first result, which is right for the overwhelming majority and wrong for the
     * one case a reader would expect to work: naming another of them. Recorded rather than re-derived so
     * the compiler asks for the right number of results and copies the right one.
     */
    val resultPick: Map<MemberExpr, Int>,
    /** The values a template call substitutes, in written order — see [NativeFn.fromTemplate]. */
    val templateArgs: Map<CallExpr, List<Expr>>,
    /** A lambda's own parameters, in order — `it` included, which is a real parameter with a real name. */
    val lambdaParamsOf: Map<LambdaExpr, List<LocalBinding>>,
    /**
     * The enclosing locals a lambda reads, in the order its synthesised signature expects them.
     *
     * A lambda's free names live in the enclosing frame's registers, which the callee's frame cannot
     * reach — so they are COPIED into the function value when it is built. That is what makes a closure a
     * value: reassigning the local afterwards cannot change what the closure sees.
     */
    val capturesOf: Map<LambdaExpr, List<LocalBinding>>,
    /** The binding each assignment writes to — the same question [bindingOf] answers for a read. */
    val targetOf: Map<AssignStmt, Binding>,
    /**
     * Which slot of the run's globals each document-level `var`/`const` lives in.
     *
     * Globals rather than registers because a document's variables outlive any one frame: a function body
     * and an `on stop` handler run in different frames — different FIBERS, for the handler — and anything
     * frame-local would hand each of them a private copy.
     */
    val globalSlots: Map<DocumentBinding, Int>,
    /** The starting value of each global, by slot — see [globalSlots]. */
    val globalDefaults: List<Any?>,
    /**
     * The variables whose starting value has to be COMPUTED, in declaration order.
     *
     * A literal can ride in [globalDefaults] and cost nothing; an expression has to be evaluated by
     * something that runs, which is what a prologue is. Kept in declaration order because one may read
     * another, and reading a variable that has not been seeded yet is the failure this ordering avoids.
     */
    val globalInits: List<GlobalInit>,
    val diagnostics: List<TextDiagnostic>,
    /**
     * The reference this document was imported by — `"core/pace"`, or `"<root>"` for the one being run.
     *
     * Not the `graph` header name, which is a label two files may share: the reference is how the
     * source was reached, so a debugger can turn an authoring site back into a file. See [Sites].
     */
    /**
     * Every binding this document declares, exported or not — what a TEST of it is allowed to see.
     *
     * Beside [exports] rather than replacing it: the two answer different questions, and the ordinary one
     * must stay the default so that visibility is still a real rule. `records`, `enums` and `extensions`
     * were already here in their unfiltered form; only the bindings were not, which is the whole of why
     * this field exists.
     */
    val allBindings: Map<String, Binding> = emptyMap(),
    val ref: String = ROOT,
) {
    val errors: List<TextDiagnostic> get() = diagnostics.filter { it.severity == Severity.ERROR }
    val warnings: List<TextDiagnostic> get() = diagnostics.filter { it.severity == Severity.WARNING }

    /** True when nothing refuses to compile. Warnings do not withhold it. */
    val ok: Boolean get() = errors.isEmpty()

    /** The type of [e], or [TypeRef.WILDCARD] when the resolver could not decide — never null. */
    fun type(e: Expr): TypeRef = typeOf[e] ?: TypeRef.WILDCARD

    companion object {
        /** The reference of the document being run — the one nothing imports. */
        const val ROOT = "<root>"
    }
}

/** A document variable and the expression that starts it — see [Resolution.globalInits]. */
class GlobalInit(val binding: DocumentBinding, val value: Expr)

/** What a call actually calls. */
sealed class Callee {
    abstract val signature: Signature
}

class NativeCallee(val fn: NativeFn) : Callee() {
    override val signature: Signature get() = fn.signature
}

class FunctionCallee(val binding: FunctionBinding) : Callee() {
    override val signature: Signature get() = binding.signature
}

/**
 * A call through a VALUE — a local or a variable holding a function, rather than a name the document
 * declared.
 *
 * The callee is not known until the call runs, which is the whole difference: `CALLV` reads the target out
 * of a register where `CALLG` names it in the instruction. Everything after that is the same calling
 * convention, and deliberately so.
 */
class ValueCallee(val binding: Binding, override val signature: Signature) : Callee()

/**
 * A call into another document.
 *
 * Carries the MODULE as well as the binding, because the compiler needs the resolution that owns the
 * declaration: a body resolves its names, its types and its own variables against the document that
 * declared it, never the one that called it. Getting that wrong does not fail to compile — it silently
 * resolves the callee's `helper` to the caller's.
 */
class ImportedCallee(val module: Module, val binding: FunctionBinding) : Callee() {
    override val signature: Signature get() = binding.signature
}

/**
 * `j.run()` where `run` is a FIELD holding a function, not an extension.
 *
 * **A field wins.** The reasoning is the user's own, from when this was fixed on the graph side: an
 * extension's name can be aliased at the import that brings it in, and a record's field name cannot — so
 * the field is the one with nowhere else to go. `GAPS.md` 29 is the same rule reached from the other
 * surface.
 */
class FieldCallee(val target: Expr, override val signature: Signature) : Callee()

/**
 * `xs.first()` — a call written on a value.
 *
 * The receiver is the first argument and the rest follow, which is what makes an extension an ordinary
 * function once it has been found. [module] is the document that declared it, or null for this one.
 */
class ExtensionCallee(
    val binding: FunctionBinding,
    val module: Module?,
    override val signature: Signature,
    /** What the call was written ON — needed to rebind it when the extension writes its receiver. */
    val receiver: Expr? = null,
) : Callee()

/** One of the three higher-order list verbs — see [Intrinsic]. Emitted as a loop, not called. */
class IntrinsicCallee(val intrinsic: Intrinsic) : Callee() {
    override val signature: Signature get() = intrinsic.signature
}

/**
 * A closed set of named members — `Skill`, and every `enum` a document declares.
 *
 * **A member IS its name at run time.** That is the graph's representation and this keeps it: a string
 * survives being read, diffed and hand-edited where an ordinal does not, and it means an enum needs no
 * opcode of its own. The COLUMNS a member carries are looked up by position — the member list and one
 * column are baked in as constants and `vscript.enumField` reads across them — which is the same trick
 * the graph compiler uses, and using a second one would make the two surfaces disagree about a value.
 */
class EnumType(
    val name: String,
    val members: List<String>,
    val span: Span = Span.NONE,
    /** `enum Roster(name: STRING, priority: INT)` — the columns every member carries. */
    val columns: List<Param> = emptyList(),
    /**
     * Member name → its row, as the EXPRESSIONS that were written, positional against [columns].
     *
     * Expressions rather than values because a row may name a function, or a record of functions — the
     * activity roster's `impl: Hooks` is exactly that — and a function only becomes a value once the
     * compiler knows which chunk it is. So the table is folded where that is known, which is the same
     * place `GraphCompiler.materialise` folds the graph's.
     */
    val rows: Map<String, List<Expr>> = emptyMap(),
    /**
     * Column name → the hidden document variable holding that column, filled in by the prologue.
     *
     * **A column is evaluated at run start, not baked in at compile time.** Kotlin evaluates an enum
     * entry's arguments when the class initialises, and this is the same idea with the same reach: a
     * column may call a function, read another document's variable, build a record — anything an
     * initialiser can do. Folding them meant only constants were expressible, and anything else silently
     * became null.
     *
     * One variable per COLUMN rather than per cell, because that is what the read wants: `enumField` takes
     * the member list and the column and picks by name.
     */
    var columnVars: Map<String, DocumentBinding> = emptyMap(),
    /**
     * The type a MEMBER of this enum carries, when it is not simply a nominal type over [name].
     *
     * Null for every enum a document declares, which is what `enum Phase { … }` means: the type is the
     * name and nothing else knows about it. A HOST enum may need to say otherwise — `Skill` has to hand
     * back a type the catalogue's pins already use, when the pins predate the enum and
     * a nominal `Skill` would not wire into one. See [dev.ziggle.vscript.model.HostEnum.type].
     */
    val refOverride: TypeRef? = null,
    /**
     * The module that declared this — [Resolution.ref]. Null for a host or prelude type, which no
     * document declares and which every document therefore sees identically.
     *
     * Stamped here, once, and carried on every [TypeRef] this hands out. That is the whole of the fix for
     * "a type is whatever the reader's import list says it is": a reader that never imported the declaring
     * module still recognises the type, and two modules that both declare a `Config` no longer collide.
     */
    val owner: String? = null,
) {

    val ref: TypeRef get() = refOverride ?: TypeRef.named(name).ownedBy(owner)

    /** How this declaration is addressed across modules — see [dev.ziggle.vscript.model.sameDeclaredType]. */
    val key: String get() = if (owner == null) name else "$owner#$name"

    fun column(field: String): List<Expr?>? {
        val i = columns.indexOfFirst { it.name == field }
        if (i < 0) return null
        return members.map { rows[it]?.getOrNull(i) }
    }

    fun columnType(field: String): TypeRef? = columns.firstOrNull { it.name == field }?.type

    fun has(member: String): Boolean = members.any { it.equals(member, ignoreCase = true) }

    /** The member as the runtime holds it, preserving the declared spelling. */
    fun canonical(member: String): String? = members.firstOrNull { it.equals(member, ignoreCase = true) }
}

/**
 * The types every document can name without importing anything.
 *
 * **This is what replaces "a pin type".** Four of them are primitives the VM has instructions for; the
 * rest are the game's own vocabulary, and the only thing that makes them special is that they are always
 * in scope. A document declaring `type Coordinate` gets something that behaves identically — which is the
 * point, and is not true of a `PinType` constant.
 *
 * ### TILE and SKILL have structure, and always did
 *
 * Both were shaped before there was a text language, and both were shaped by the EDITOR's needs:
 *
 *  - a **TILE** is stored as the string `"x,y,plane"`, because that is the form you copy off a wiki page
 *    or out of the debug console, and a picker edits it as three fields;
 *  - a **SKILL** is stored as its *name*, drawn from a fixed list, because a name survives being read,
 *    diffed and hand-edited where an index does not.
 *
 * Both are good answers to the question that was asked. Neither is a type. A tile that is a string cannot
 * be asked for its `x`, and a skill that is a string means the checker accepts `"Attak"` and every other
 * string as one — the fixed list exists, and nothing consults it.
 *
 * So the prelude gives them the structure they always had: a tile is a RECORD of three ints and a skill is
 * an ENUM of the game's skills. **The `TypeRef` is unchanged** — still `TypeRef(PinType.TILE)`, still what
 * every native's parameter is typed as — so nothing about the catalogue, the graph or the runtime moves.
 * What is new is that the resolver can now answer `t.x` and refuse `Skill.Attak`, which is the whole of
 * what was missing. The runtime representation is a separate decision, and is written up as one in
 * `docs/TEXT_FRONTEND.md`.
 */
object Prelude {

    /** What the VM has instructions for. Everything else is a name over one of these. */
    val PRIMITIVES: List<PinType> = listOf(PinType.BOOL, PinType.INT, PinType.FLOAT, PinType.STRING)

    /**
     * A name over a primitive, and nothing more.
     *
     * Empty, and kept as a concept rather than deleted. It held `ITEM`, `NPC` and `OBJECT` — nominal types
     * over INT, doing real work (an item id is not an npc id) and hiding no structure, because an id has
     * none. That is exactly what a host record over the host's own value is, so a DOMAIN declares them
     * now and the language has no built-in of the kind left.
     */
    val OPAQUE: List<PinType> = emptyList()

    /**
     * Always in scope, never imported.
     *
     * `EXEC` is deliberately absent: control flow is statement order in text and has no spelling, so a
     * document able to name the type would be naming something it cannot hold.
     */
    val TYPES: List<PinType> = PRIMITIVES + OPAQUE

    /**
     * Matched the way pin names are — underscores and case ignored.
     *
     * `ITEM_REF` is written `ItemRef`, which is the only spelling anybody would choose and did not match
     * the constant's own name. The catalogue already compares names this way for pins; types had no
     * reason to be stricter.
     */
    private fun key(name: String) = name.filterNot { it == '_' || it == '-' || it.isWhitespace() }.lowercase()

    private val byName: Map<String, TypeRef> = buildMap {
        TYPES.forEach { put(key(it.name), TypeRef(it)) }
        // The spellings a person actually writes, beside the constant's own name. `tile` used to be here
        // and is not: the name belongs to whoever declares the record, and `Resolver.typeOf` reaches it
        // through `HostRecords` — below a document's own declarations, which is where a host type belongs.
        put("any", TypeRef.WILDCARD)
    }

    fun type(name: String): TypeRef? = byName[key(name)] ?: hostEnum(name)?.ref

    /**
     * The structure of a built-in type — **there are none left, and that is the whole point.**
     *
     * `Tile` and `Color` were here, and were the last two types the language described the SHAPE of. Both
     * are data [dev.ziggle.vscript.model.HostRecord]s the node pack declares now, reached through
     * [hostRecord] and [dataRecord] like any other host type. Kept as a function rather than deleted
     * because every caller asks the same question in the same order — the document's own, then the
     * prelude's, then the host's — and removing the middle step would rewrite that order in six places to
     * say the same thing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun record(t: TypeRef): RecordType? = null

    /**
     * The enum a type names, when something registered one.
     *
     * This used to read `if (t.name == "Skill") SKILL else null` — one game's vocabulary, spelled into
     * the resolver. Every host enum reaches it the same way now, which is what made `Season` work in the
     * greenhouse fixture and what makes `Skill` work once the node pack declares it.
     */
    fun enumOf(t: TypeRef): EnumType? = hostEnum(t.name)

    /**
     * Adapters for [dev.ziggle.vscript.model.HostEnums], keyed BY INSTANCE.
     *
     * By instance rather than by name so a re-registered library refreshes itself: registering replaces
     * the [HostEnum], which is a different object, so the stale adapter is simply never asked for again.
     * Keyed by name it would be a cache with no invalidation, and a client that rebuilt its node library
     * would keep answering with the first build's members.
     */
    private val hostAdapters = java.util.concurrent.ConcurrentHashMap<HostEnum, EnumType>()

    /**
     * The host enum called [name], adapted to the text model — or nothing.
     *
     * **Asked LAST, everywhere it is asked.** A document's own declarations and its imports are looked up
     * first (see `Resolver.enumNamed`), so a document declaring `enum Tab` shadows the host's rather than
     * colliding with it — the same precedence [Types] gives a document that redefines a built-in name.
     */
    fun hostEnum(name: String): EnumType? {
        val h = HostEnums.of(name) ?: return null
        return hostAdapters.computeIfAbsent(h) { EnumType(it.name, it.members, refOverride = it.type) }
    }

    /**
     * Adapters for [dev.ziggle.vscript.model.HostRecords], keyed BY INSTANCE for [hostAdapters]' reason.
     *
     * A host record is a record as far as the resolver is concerned — it has named, typed fields and
     * nothing else about it matters for typechecking. Adapting rather than teaching every lookup about a
     * second record class is what keeps `e.tile` and `p.x` one code path; where they differ is in the
     * COMPILER, which emits a host call for one and a `GETFIELD` for the other.
     *
     * The adapted record carries no owner: a host type is the same in every document, which is the whole
     * distinction `Types` draws between what the host provides and what a document declares.
     */
    private val hostRecordAdapters = java.util.concurrent.ConcurrentHashMap<HostRecord, RecordType>()

    /**
     * The host record called [name] **when it is a data record**, adapted to the text model.
     *
     * The narrower half of [hostRecord], and the distinction is load-bearing rather than tidy. A caller
     * that means to BUILD one — a `Tile { … }` literal, a positional constructor — may only do so for a
     * record whose values are plain structs. Handing it an accessor record would compile a `NEWSTRUCT`
     * that the host's own field lambdas then cannot read, which fails at run time, far from the literal,
     * as a field that reads null.
     */
    fun dataRecord(name: String): RecordType? =
        hostRecord(name)?.takeIf { HostRecords.of(name)?.isData == true }

    /** The host record called [name], adapted to the text model — or nothing. Asked LAST, as [hostEnum] is. */
    fun hostRecord(name: String): RecordType? {
        val h = HostRecords.of(name) ?: return null
        return hostRecordAdapters.computeIfAbsent(h) { r ->
            RecordType(r.name, r.fields.map { Param(it.name, it.type) }, Span.NONE)
        }
    }
}
