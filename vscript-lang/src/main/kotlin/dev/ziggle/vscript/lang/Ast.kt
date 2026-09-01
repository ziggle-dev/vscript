package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.ImportItem

/**
 * The syntax tree.
 *
 * **Deliberately dumb.** Nothing here resolves a name, knows a pin type, or has an opinion about which node
 * a call becomes — that is `Lower`'s job, and it needs a `NodeCatalog` and the document's own declarations to
 * do it. Keeping the tree ignorant is what lets the parser be tested on its own and what stops catalog
 * questions leaking into syntax ones.
 *
 * **Every node carries a [Span].** The text surface's whole debugging story is the side table `Lower` builds
 * from these — node id to source span — so "break on line 42" is a lookup rather than a change to a protocol
 * that is addressed by node id everywhere else.
 */

// ---- annotations ------------------------------------------------------------------------------------

/**
 * Everything written with an `@` before a declaration or statement, in the order it was written.
 *
 * **One bag, several views.** The system defines a few names ([AnnotationSpec]) and a graph may write
 * whatever else it likes; both are stored here identically, because which ones mean something is a question
 * for lowering and not for syntax. The typed accessors below are *derived* — there is one representation,
 * read several ways, rather than two representations that could disagree.
 *
 * All absent is the normal case: when [x]/[y] are missing the importer arranges headlessly instead (see
 * VSCRIPT_LANG_PLAN.md §8), which is what lets the fidelity choice be "semantic only" without losing the
 * ability to pin a layout down where it matters.
 */
class Annotations(val all: List<Annotation> = emptyList()) {

    val isEmpty: Boolean get() = all.isEmpty()

    fun first(name: String): Annotation? = all.firstOrNull { it.name == name }
    fun has(name: String): Boolean = first(name) != null

    /** Everything the system does not define — a graph's own notes, carried through untouched. */
    val extras: List<Annotation> get() = all.filter { AnnotationSpec.of(it.name) == null }

    private fun num(name: String, i: Int): Float? =
        (first(name)?.args?.getOrNull(i) as? Number)?.toFloat()

    val id: Int? get() = num("id", 0)?.toInt()

    companion object { val NONE = Annotations() }
}

/**
 * One annotation: its name without the `@`, and whatever literals were written in its parens.
 *
 * **The compiler never reads one.** An annotation is metadata; anything that changes what runs is a node.
 * See VSCRIPT_LANG_PLAN.md §8b — it is the same rule that made `break` a node rather than sugar, and `Hold`
 * a node rather than a `let` convention, and it exists because a flag beside a thing can disagree with the
 * thing while being invisible on the canvas.
 */
class Annotation(val name: String, val args: List<Any?>, val span: Span = Span.NONE)

/**
 * The annotations the system itself defines — a registry, not a `when`.
 *
 * These are the ones that bind to real `Node` state rather than being carried as notes, which is the only
 * thing separating them from anything a graph invents. Making that a **table** rather than branches in the
 * parser is what keeps there being one annotation mechanism instead of two: the parser reads every
 * annotation the same way and checks arity here, and `Lower` asks this which ones it must bind. A new
 * intrinsic is then a row, not a change to the grammar.
 */
object AnnotationSpec {

    class Spec(
        val name: String,
        /** How many literals it takes. Checked at parse time, where there is a span to point at. */
        val arity: IntRange,
        val about: String,
    )

    val ALL: List<Spec> = listOf(
        Spec("id", 1..1, "pin this node's id, so breakpoints survive a round trip. Never written by the printer"),
        Spec("note", 1..1, "a free-text note on this node"),
    )

    private val byName = ALL.associateBy { it.name }

    fun of(name: String): Spec? = byName[name]

    /** The names, for the message that tells someone what they could have written. */
    val names: List<String> get() = ALL.map { it.name }
}

// ---- types ------------------------------------------------------------------------------------------

/**
 * A type as written: a name, optionally with an element type — `Int`, `List<Item>`, `Coordinate`.
 *
 * A name and not a resolved `TypeRef`, because what a name means depends on the document asking, and the
 * parser has no document. `TypeRef.parse` does the resolution later, which is the same rule the JSON format
 * follows and the reason a declared type survives being read by a client that has never heard of it.
 */
class TypeExpr(
    val name: String,
    /**
     * The type arguments — one for a `LIST`, two for a `MAP`, none for everything else.
     *
     * It was a single `of: TypeExpr?` until phase F. `TypeRef` had already been generalised in phase D
     * precisely so this could follow, and until it did `MAP<Tile, Job>` did not parse: the second argument
     * had nowhere to go. [of] still answers for the list case, so no reader moved.
     */
    val args: List<TypeExpr> = emptyList(),
    val span: Span = Span.NONE,
    /**
     * The import alias qualifying [name] — `banking` in `banking::Account`, null when local.
     *
     * Carried into the persisted spelling rather than resolved away, so a `TypeRef` naming an imported
     * record still says which document declared it. Two documents may each declare an `Account`, and a
     * name that dropped its qualifier would resolve to whichever the reader happened to ask first.
     */
    val module: String? = null,
    /**
     * `Tile?` — the value may be absent.
     *
     * Written outermost and only outermost: `LIST<TILE>?` is an optional list, and a list of optional
     * tiles is `LIST<TILE?>`, which is the inner [of] carrying its own flag. There is no spelling that
     * makes the two ambiguous, which is why the parser can take it as a plain suffix.
     */
    val optional: Boolean = false,
) {
    /** The single-argument reading of [args] — a list's element type, which is what most callers mean. */
    val of: TypeExpr? get() = args.firstOrNull()

    companion object {
        /** The result of an `act(…)` that hands nothing back — see [toString] and `TypeRef.NOTHING`. */
        const val NOTHING = "NOTHING"
    }

    /**
     * The persisted spelling — `LIST<ITEM>`, `MAP<TILE, INT>`, `banking::Account?` — so this round-trips
     * through `TypeRef.parse`.
     *
     * **This is the whole of the lowering side**, and deliberately: `Lower.typeRef` is literally
     * `TypeRef.parse(t.toString())`, so the moment this writes `A, B` the parse side already reads it —
     * `TypeRef.parse` has split on top-level commas since phase D.
     */
    override fun toString(): String {
        // A function type prints in its own shape, matching `TypeRef.toString` exactly so the round trip
        // through `TypeRef.parse` holds — the result is LAST in [args], as it is there.
        if (module == null && name == "fn") {
            if (args.isEmpty()) return if (optional) "fn?" else "fn"
            val last = args.last()
            // "Hands nothing back" is spelled by a result named [NOTHING] rather than by a short list —
            // the result is LAST in [args], the invariant every walker relies on — so this can tell the
            // two shapes apart without asking anybody.
            val void = last.module == null && last.args.isEmpty() && last.name == NOTHING
            val written = args.dropLast(1).joinToString(", ", "fn(", ")") + if (void) "" else " -> $last"
            // Parenthesised when optional AND there is a result, or reading it back would give the RESULT
            // the mark — see `TypeRef.parse`. The same ambiguity Kotlin solves the same way. With no
            // result there is nothing for the mark to belong to, so it needs no parentheses.
            return when {
                !optional -> written
                void -> "$written?"
                else -> "($written)?"
            }
        }
        val head = if (module == null) name else "$module::$name"
        return (if (args.isEmpty()) head else args.joinToString(", ", "$head<", ">")) +
            if (optional) "?" else ""
    }
}

/** One named, typed entry in a signature or a record. */
/**
 * `name: Type`, and on a PARAMETER optionally `= value`.
 *
 * Shared by parameters, results and record fields, so the default is parsed everywhere and accepted only
 * where it means something — a record field default would need a zero-value story of its own.
 */
class Field(
    val name: String,
    val type: TypeExpr,
    val span: Span = Span.NONE,
    /** A parameter's default. A literal, because there is nowhere to hang a computation off a signature. */
    val default: Expr? = null,
)

// ---- program and declarations -----------------------------------------------------------------------

class Program(
    /** From `graph "…"`, or null when the file does not name itself. */
    val name: String?,
    val decls: List<Decl>,
    val span: Span = Span.NONE,
    /** `export default …` — the name of the one declaration this document is FOR. See `Graph.defaultExport`. */
    val defaultExport: String? = null,
)

/**
 * The name a nameless `export default` declaration is given — `export default single { … }`.
 *
 * **Not a legal identifier**, deliberately: nothing an author can write collides with it, nothing can
 * import it by name, and it cannot be spelled from inside the document that declares it. Which is the
 * intent — a default export's shape is named by whoever imports it (`import Roster from "…"`), so a name
 * on the declaration is a name nobody says and one more thing to collide with.
 *
 * It is the SAME string the default-import lookup uses (`Resolver.DEFAULT_EXPORT`), and that is what makes
 * the anonymous case need no machinery of its own: the declaration registers under this name, and a
 * default import is already a lookup of exactly this name.
 */
const val ANONYMOUS_DEFAULT = "@default"

/**
 * The name a declaration introduces, or null for the ones that introduce none.
 *
 * `export default fn run(…)` has to record WHICH declaration was marked, and the base [Decl] has no name
 * because two of its subclasses genuinely have none. One reader here rather than a `when` at each site.
 */
val Decl.declaredName: String?
    get() = when (this) {
        is FnDecl -> name
        is VarDecl -> name
        is ValDecl -> name
        is ConstDecl -> name
        is TypeDecl -> name
        is SingleDecl -> name
        is EnumDecl -> name
        else -> null
    }

sealed class Decl {
    abstract val span: Span
}

/**
 * `import * as banking from "banking"`, `import { abs as absolute } from "x"`, `import run from "x"`.
 *
 * TypeScript's three shapes and their two combinations, meaning what they mean there:
 *
 * - `* as ns` — a NAMESPACE. Nothing enters the local namespace; every name is `ns::x`, which is what buys
 *   the qualified form its absence of collision rules.
 * - `{ a, b as c }` — named, each under its local spelling.
 * - a bare name — the other document's DEFAULT export, under whatever this file calls it.
 * - `d, { a }` and `d, * as ns` — a default alongside either of the others.
 *
 * [alias] is null unless `* as` was written; `Lower` synthesises one (`@1`, `@2`, …) for the other forms,
 * because the alias is the key every later stage uses to say which document a name came from.
 */
class ImportDecl(
    val alias: String?,
    val ref: String,
    override val span: Span,
    val named: List<ImportItem> = emptyList(),
    /** `import run from "x"` — the local name bound to the other document's `export default`. */
    val default: String? = null,
    /** `import "core/list"` — everything the document exports, under its own name. */
    val star: Boolean = false,
) : Decl()

class TypeDecl(
    val name: String,
    val fields: List<Field>,
    override val span: Span,
    /** `private type` — declared here, not reachable through an import. */
    val isExported: Boolean = false,
    /** `type Pair<A, B>` — the names this record is generic in. See [dev.ziggle.vscript.model.StructType]. */
    val params: List<String> = emptyList(),
) : Decl()

/**
 * `enum Phase { Chop, Bank, Walk }` — a closed set of names.
 *
 * Members are bare identifiers with no type and no value, which is what keeps this a separate declaration
 * from [TypeDecl] rather than a record whose fields happen to be empty: there is nothing to write after the
 * name, so a `Field` would carry two slots that must always be null.
 */
class EnumDecl(
    val name: String,
    val members: List<EnumMember>,
    override val span: Span,
    /** `private enum` — declared here, not reachable through an import. */
    val isExported: Boolean = false,
    /**
     * The columns each member carries, when the enum declares any — `enum Target(count: INT, …)`.
     *
     * Empty is exactly today's enum, so every existing file means the same thing. What it buys is a TABLE
     * where a script would otherwise write a `when` with an arm per member returning constants.
     */
    val fields: List<Field> = emptyList(),
) : Decl()

/**
 * One member, with its own span so a duplicate or an unknown one can be pointed at precisely.
 *
 * [values] are the member's row, positional against [EnumDecl.fields]. Literals rather than expressions,
 * for the reason a graph variable's default is a literal: this is document data, and there is nowhere in a
 * declaration to hang a node that would work something out.
 */
class EnumMember(val name: String, val span: Span, val values: List<Expr> = emptyList())

class VarDecl(
    val name: String,
    val type: TypeExpr,
    /** A literal, never an expression: a graph variable's default is document data, not something run. */
    val default: Expr?,
    override val span: Span,
    /** `private var` — this document's state, not part of what it offers. */
    val isExported: Boolean = false,
) : Decl()

/**
 * A named constant — one literal node with a wire to each reader.
 *
 * Not the same as writing the number out at each use, and the difference is not style. A literal is keyed
 * `"nodeId/pin"` in the constant pool so it can be rewritten while the script runs (`Chunk.setLiteral`), so
 * one literal node feeding three pins is one tunable knob where three inline literals are three. This is how
 * you say "one knob".
 *
 * Also the exec-free counterpart to `let`: being pure, it is legal inside an expression-bodied function,
 * which `let` is not.
 */
class ConstDecl(
    val name: String,
    val value: Expr,
    override val span: Span,
    val ann: Annotations = Annotations.NONE,
    /** `export const` — a const IS part of the surface: `alias::LIMIT` resolves to its literal. */
    val isExported: Boolean = false,
) : Decl()

/**
 * `export { a, b as c } from "y"` / `export * from "y"` — names passed straight on to whoever imports this.
 *
 * **Not sugar for an import plus an export**, and the difference is what makes barrels work: nothing here
 * enters this document's scope. The names go past it. A file that re-exports `chebyshev` still cannot call
 * it, which is TypeScript's rule and the one that keeps a barrel from quietly acquiring a vocabulary.
 *
 * Carried as a [GraphImport] with [GraphImport.reExported] set, because a re-export DOES have to load the
 * document it names — so the import closure, the globals layout and the document format all need it to be
 * an edge like any other.
 */
class ReExportDecl(
    val ref: String,
    override val span: Span,
    val items: List<ImportItem> = emptyList(),
    /** `export * from "y"` — everything the target offers. */
    val all: Boolean = false,
) : Decl()

/**
 * `export { a, b }` with no `from` — this document's own names, exported in a list.
 *
 * The same thing as writing `export` on each declaration, said in one place instead. Which some authors
 * prefer and which costs nothing: `Lower` marks the named declarations and there is no second mechanism.
 */
class ExportListDecl(val names: List<String>, override val span: Span) : Decl()

/**
 * `export default { run, setup }` — a bundle, and the one default that is not an existing declaration.
 *
 * **A record, synthesised.** `Lower` makes a type and one instance of it, both called `@default`, whose
 * fields hold the named declarations — so an importer writing `import m from "x"` gets a VALUE, reads
 * `m.run` as an ordinary field and calls it as an ordinary function value. Nothing new runs and nothing
 * new compiles, and the bundle can be passed around and stored in a table like anything else.
 *
 * The cost, stated: a function reached through a field is called POSITIONALLY — `m.run(r)`, not
 * `m.run(rumor: r)` — because a function value carries a signature and not argument labels. A named
 * import keeps the labels; this is the trade for having the module as a value.
 */
class DefaultBundleDecl(val names: List<String>, override val span: Span) : Decl()

/**
 * `val Name = value` at document level — a name bound once, that nothing may assign to.
 *
 * **One word covering what `const` and a never-reassigned `var` each did half of**, and which of the two it
 * becomes is decided by the VALUE, in `Lower`, not here. A value that is written out folds to a literal node
 * exactly as a `const` does — which is what keeps it usable where only a literal will do, in an enum row or
 * a parameter default. Anything that has to *run* becomes a graph variable plus a one-time initialiser,
 * reusing the machinery a `var`'s computed default already uses, with assignment refused.
 *
 * The parser cannot make that call: whether `Base` folds depends on the const and enum tables, which is
 * `Lower`'s knowledge. So one declaration goes in and two shapes come out.
 *
 * [type] is optional and only *required* for the computed case — a graph variable is always typed, and a
 * folded literal needs no more type than the value it holds. `Lower` reports the difference, because it is
 * the one that knows which case this is.
 */
/**
 * `single State { phase: Phase = Phase.Idle, laps: INT = 0 }` — a record with exactly one of it.
 *
 * **Sugar over two declarations that already work**: a [TypeDecl] and a document-level `var` of that type
 * under the same name, the variable initialised from the field defaults. Reads and writes are the field
 * access and field assignment that exist already — `State.laps`, `State.laps = State.laps + 1` — so nothing
 * new runs and nothing new compiles.
 *
 * What it buys is that the two halves cannot drift: the state a script keeps was two declarations and a
 * naming convention kept in step by hand, and a convention is exactly the kind of thing that is right until
 * somebody adds a field to one half.
 *
 * A field with no default takes its type's zero, which is the rule a record-typed `var` already follows.
 * `private single` behaves like `private type`. Like extensions, it needs a recognizer in `Print` or it
 * comes back as its two pieces.
 */
class SingleDecl(
    val name: String,
    val fields: List<Field>,
    override val span: Span,
    val isExported: Boolean = false,
    val ann: Annotations = Annotations.NONE,
) : Decl()

class ValDecl(
    val name: String,
    val type: TypeExpr?,
    val value: Expr,
    override val span: Span,
    val isExported: Boolean = false,
    val ann: Annotations = Annotations.NONE,
) : Decl()

/**
 * A user function.
 *
 * **There is one body form.** `fn f(n: INT) -> INT = n * 2` is read as `fn f(n: INT) -> INT { return n * 2 }`
 * and nothing below the parser can tell them apart — Kotlin's rule, and for Kotlin's reason: an author
 * choosing the short spelling is choosing a spelling, not a different kind of function.
 *
 * It did once mean something. `= …` marked the function an EXPRESSION (no exec chain, re-evaluated at every
 * read) and `{ … }` marked it a step, so a one-line wrapper became illegal the moment the thing it wrapped
 * had to run — reported as `'Call.Result' is read here, but nothing runs 'Call'`, at no line, once per call
 * site. Nothing in `-> STRING` said which kind you were getting, so the cascade was discovered rather than
 * predicted (GAPS 19).
 *
 * Purity survives, still derived and still `isPureFunction`'s answer — but derived from what the body turns
 * out to NEED rather than from how it was written. A body that is one `return` of something that only
 * computes wires straight into the box, leaves its exec pins unwired, and is an expression; the same body
 * with a step in it gets the exec chain a step needs. See `Lower.function`.
 */
class FnDecl(
    val name: String,
    /**
     * `fn List.add(…)` — the type this extends, or null for an ordinary function.
     *
     * A receiver is a parameter that was written before the name, so lowering prepends it to [params] as
     * `self` and everything downstream is an ordinary call. See [GraphFunction.receiver].
     */
    val receiver: TypeExpr? = null,
    val params: List<Field>,
    val results: List<Field>,
    val body: Block,
    override val span: Span,
    val ann: Annotations = Annotations.NONE,
    /** `private fn` — callable here, not through an import. */
    val isExported: Boolean = false,
    /**
     * `fn first<T>(xs: LIST<T>) -> T?` — the type parameters this function introduces.
     *
     * **A second binding site, and the one the language was missing.** An extension could always bind a
     * variable through its receiver (`fn List<T>.first(self)`) because that is what a canvas needs: it
     * knows what is plugged into `self` before it needs to know anything else. A plain function has no
     * receiver, so a generic one could not be written at all — and `LIST` and `MAP` stayed special cases
     * in the type rules rather than instances of one.
     *
     * Introduced rather than used, so — like `TypeDecl`'s — these are bare names with no arguments and no
     * bounds, and cannot be typo-checked. What catches the realistic mistake is a parameter that is
     * never used, the same answer `unusedTypeParameters` already gives for a receiver's.
     */
    val typeParams: List<String> = emptyList(),
    /**
     * `inline fn` — splice the body at every call site instead of emitting a `CALL`.
     *
     * **A declaration the compiler can FAIL on, which is the whole reason it is a keyword.** The VM is
     * instruction-bound at a 3ms budget per scheduler pass, so a call frame around a one-instruction body
     * is most of that body's cost — and the two mechanisms that used to hide that cost (`Inliner` and
     * `AppendPass`) were inferences that declined in silence and only ever ran on the graph front end.
     * Saying it outright means an author gets an error naming the reason rather than a slow script with
     * nothing to point at.
     *
     * Two rules the compiler enforces: a recursive `inline` is refused, since inlining it cannot
     * terminate; and arguments are bound to registers before substitution, so an argument used twice in
     * the body is still evaluated once.
     */
    val isInline: Boolean = false,
    /**
     * `op fn List<T>.get(self, index: Int) -> T` — the declaration behind a piece of SYNTAX.
     *
     * **This moves an operator out of the compiler and into the library.** `xs[i]` is a hardcoded
     * `Op.INDEX` today, typed by a rule in the resolver that knows about lists and nothing else — so a
     * map is indexed by `valueAt(map:, key:)` and a record not at all, and adding either meant editing
     * the compiler. As a declaration it is a name like any other: readable, documentable, and reachable
     * through an import.
     *
     * **`op` implies [isInline]**, and the compiler enforces it. Without that this trades a compiler
     * special case for a `CALL`/`RET` pair on the hottest operation in the language, which is the
     * opposite of the point.
     *
     * The names are a closed set — see `OperatorName` — because an operator is syntax, and syntax that
     * any declaration could invent is syntax nobody can read.
     */
    val isOperator: Boolean = false,
    /**
     * `infix fn A.to(self, other: B)` — what implements one of the language's infix WORDS.
     *
     * The same bargain [isOperator] makes for `[`: the word is syntax the language owns and a closed set
     * (`Parser.INFIX_WORDS`), and this says which function it calls. It cannot be the general "any
     * identifier declared infix" rule other languages use — vs allows a bare single-statement body on the
     * same line, so `if ready log(…)` would be indistinguishable from an infix call.
     */
    val isInfix: Boolean = false,

    /**
     * `fake panel.amount(id: String, default: Int) -> Int = default` — the HOST this stands in for.
     *
     * Null for every ordinary function, which is all of them outside a test document.
     *
     * A fake IS a function — it has parameters, a result and a body, and it is typechecked and compiled
     * like one — so it lives here rather than in a declaration of its own. What the extra name buys is the
     * only thing that differs: while a test runs, a call to that host is compiled as a call to THIS chunk
     * instead. See `TextCompiler`'s `fakes`.
     *
     * [name] is the same dotted string, which is deliberately not a spelling any call can have: a fake is
     * reached by standing in for a host, never by being called by name.
     */
    val fakes: String? = null,
) : Decl()

/**
 * The six moments a document can hang code off.
 *
 * Three of them are the run's own shape, in the order they happen: [WAKE] gets ready, [START] does the
 * work, [SLEEP] hands over. [STOP] is the run ending rather than a phase of it, and [RENDER] / [TICK]
 * ride clocks the client owns.
 */
/**
 * The six handlers a document can declare, and which end of the import chain each starts from.
 *
 * [innermostFirst] is a language rule, not a compiler's: it says what `import` MEANS for a handler, and
 * both front ends have to give the same answer or the same script behaves differently depending on how it
 * was written. `EntryGroup` on the graph side reads it from here.
 */
enum class EntryKind(
    /** Imports before importers. */
    val innermostFirst: Boolean,
) {
    /** Fibers. A library initialises before the thing that imported it can use it. */
    START(innermostFirst = true),

    /**
     * Handlers, run the other way round: the importer finishes before what it depends on.
     *
     * A library's `on stop` reporting totals must not run while the document that was feeding it those
     * totals is still winding down — that is the same argument as ordering `on start` inwards, read
     * backwards.
     */
    STOP(innermostFirst = false),

    /** Per frame. Innermost first, so a library has drawn its layer before its user draws over it. */
    RENDER(innermostFirst = true),

    /** Per game tick. Innermost first, so a library's watcher has updated what its user is about to read. */
    TICK(innermostFirst = true),

    /**
     * Getting ready, before [START]. Innermost first, for [START]'s reason read one phase earlier: a
     * library provisions what it owns before the document that imported it goes looking for it.
     */
    WAKE(innermostFirst = true),

    /**
     * Handing over, after the work has stopped. Outermost first, like [STOP] and by the same argument:
     * the importer decides it is finished before the library it has been leaning on puts itself away.
     */
    SLEEP(innermostFirst = false),

    /**
     * `test "…" { … }` — a check, run by a test runner and by nothing else.
     *
     * **Not spawned by a run.** Every other kind answers "when does the host fire this"; this one is never
     * fired by the host at all, which is why it is safe for it to sit in the same enum: `ScriptRuntime`
     * asks for the kinds it pumps by name, and this is not among them. It is here rather than in a
     * declaration of its own so that the resolver typechecks a test exactly as it typechecks a handler —
     * a test that has stopped compiling should fail `graph check`, not be discovered the next time somebody
     * runs the suite.
     *
     * [innermostFirst] is meaningless for it — tests do not run as a group, each is its own fiber — and is
     * true only because that is the harmless answer.
     */
    TEST(innermostFirst = true),
}

class EntryDecl(
    val kind: EntryKind,
    val body: Block,
    override val span: Span,
    val ann: Annotations = Annotations.NONE,
    /**
     * `always on render` — fires however this document was reached, including through an import.
     *
     * Its own word rather than `export`, because this is not visibility: an entry has no name for anyone
     * to say, so there is nothing to make nameable. The question is whether it RUNS for an importer, and
     * that is a different question wearing the same shape.
     */
    val isAlways: Boolean = false,
    /**
     * What a `test "…"` calls itself. Null for every other kind, which has nothing to name.
     *
     * A test needs a name for the same reason a failure needs one: the report says which check broke, and
     * "the third one in goal.vs" is not something anybody can act on. It is a STRING rather than an
     * identifier so it can be a sentence — `test "a chain with nothing held asks for the whole lot"` — the
     * spelling every language that got this right converged on.
     */
    val label: String? = null,
) : Decl()

// ---- statements -------------------------------------------------------------------------------------

/**
 * A run of statements.
 *
 * [braced] records whether `{ }` was WRITTEN, because `if x foo()` and `if x { foo() }` are the same
 * statements and lower to the same graph — so the printer has to be told which spelling to give back, or one
 * of them stops round-tripping. It is spelling and nothing else: no stage below the printer reads it, and a
 * bare block behaves in every way like a braced block of one statement.
 */
class Block(val stmts: List<Stmt>, val span: Span = Span.NONE, val braced: Boolean = true)

/**
 * `assert count(list: tasks) == 3` — a check inside a `test`.
 *
 * **A statement, not a call**, and the difference is the whole value of it. A call can only be handed the
 * BOOLEAN its condition produced, so the best failure it could ever report is "assert got false". A
 * statement is parsed, so the written text is available ([text]) and, when the condition is a comparison,
 * both sides can be evaluated into registers and reported — which is the difference between
 *
 *     assertion failed
 *
 * and
 *
 *     assertion failed: count(list: tasks) == 3
 *       left:  2
 *       right: 3
 *
 * This is the same reason `assert_eq!` is a macro in Rust and `assert` is a keyword in Python: the useful
 * message is made of things a function never receives.
 */
class AssertStmt(
    /** The whole condition — evaluated once, and the only thing that decides pass or fail. */
    val condition: Expr,
    /** The condition exactly as written, sliced from the source. Empty when the parser had none. */
    val text: String,
    /** `assert x, "why"` — the author's own words, shown instead of the sliced text when given. */
    val message: Expr? = null,
    /**
     * The two sides, when the condition is a comparison — so the report can say what each one held.
     *
     * Null for anything else: `assert isReady()` has no sides, and inventing some would mean re-evaluating
     * a sub-expression, which for an impure condition would run it twice.
     */
    val left: Expr? = null,
    val right: Expr? = null,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
) : Stmt()

sealed class Stmt {
    abstract val span: Span
    open val ann: Annotations get() = Annotations.NONE
}

/** What a `let` binds: one name, several output pins, or several record fields. */
sealed class Binding {
    abstract val span: Span
}

/** `let x = …` — the value, held. */
class NameBinding(val name: String, override val span: Span) : Binding()

/**
 * `let (a, b) = f()` — output pins, by position; `let (n: Name, e: Entity) = f()` — by pin NAME.
 *
 * **Local first, pin second.** `name: value` elsewhere in this language SUPPLIES a pin (`log(message: x)`),
 * and this is the opposite motion: a name is being taken out, not passed in. Read aloud, `clickedName: Name`
 * is "clickedName, from Name", which is the direction the binding goes.
 *
 * By NAME is the one to reach for. Positional binding silently follows the pin ORDER, so it says nothing
 * about which pin you meant — and a node that gains an output, or has two reordered, changes what an existing
 * script binds without anything being reported.
 */
class TupleBinding(val entries: List<TupleEntry>, override val span: Span) : Binding() {
    val names: List<String> get() = entries.map { it.local }

    /**
     * Written with at least one `local: Pin`, so this list binds by name whatever its pins turn out to be.
     *
     * The OTHER way a list becomes by-name — every bare name being an output's — cannot be decided here: it
     * depends on what is being destructured, and the parser resolves nothing. `Lower` asks the full
     * question; this is the half answerable from syntax alone.
     */
    val explicitlyNamed: Boolean get() = entries.any { it.pin != null }
}

/** One entry: the pin it takes ([pin], null when positional) and the local it binds. */
class TupleEntry(val pin: String?, val local: String, val span: Span)

/** `let {tile, name} = s` — one name per *record field*. Braces and parens mean different things. */
class RecordBinding(val names: List<String>, override val span: Span) : Binding()

class LetStmt(
    val binding: Binding,
    val value: Expr,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
    /**
     * Written `var` rather than `let`, so the name may be assigned again.
     *
     * A flag on the same statement rather than a class of its own, because the two differ in exactly one
     * thing and everything else about them — the binding forms, the initialiser, the annotations — is
     * shared. Only a plain name may be mutable; destructuring several names out of one value and then
     * reassigning one of them is not a thing this language needs.
     */
    val mutable: Boolean = false,
    /**
     * `let n: FLOAT = 0` — the type written on the binding, when one was.
     *
     * Otherwise the type comes from the initialiser, which is right nearly always and wrong in one
     * common case: `0` is an INT, so a float accumulator started as an integer and stayed one.
     */
    val declaredType: TypeExpr? = null,
) : Stmt()

class ConstStmt(val name: String, val value: Expr, override val span: Span, override val ann: Annotations = Annotations.NONE) : Stmt()

/**
 * `n = expr` — a write to a graph variable.
 *
 * [op] is set when the author wrote a compound form: `n += 1` arrives here as `n = n + 1` with [op] `ADD`.
 * The desugaring happens in the PARSER, so nothing below this line knows the spelling existed — [op] is
 * carried only so the lowering can leave a mark for the printer, the same way `var` and `s.f = v` do.
 */
/**
 * `xs[i] = v` — writing through the index form.
 *
 * **Sugar for `xs.set(index: i, value: v)`, a MUTATING extension**, which is what lets it exist while a
 * list is still a value: a mutating extension already means "call it and store the result back where the
 * receiver was", the same shape `xs.add(v)` uses. So this writes back today and becomes a genuine
 * in-place write once containers are references, with nothing changing at the call site.
 *
 * Kept as its own node rather than desugared in the parser because the receiver is an arbitrary
 * expression — `a.b[i] = v` — and a `CallExpr` names its receiver as a dotted path, which cannot spell
 * one.
 */
class IndexAssignStmt(
    val target: Expr,
    val index: Expr,
    val value: Expr,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
) : Stmt()

class AssignStmt(
    val name: String,
    val value: Expr,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
    val op: BinaryOp? = null,
    /**
     * `pace::IdleMs = 50` — the import alias, when the variable being written belongs to another document.
     *
     * Reading one was always sayable and writing one was not, which made a library exporting a `var` for
     * tuning offer no way to tune it. The same shape a qualified CALL already had, applied to the other
     * side of an `=`.
     */
    val module: String? = null,
) : Stmt()

/**
 * `course.laps = course.laps + 1` — one field of a record, replaced.
 *
 * **A record is a VALUE, so this is a rebind and not a write.** It means exactly `course = course with
 * { laps: … }`: the record is copied with one field changed and the NAME is pointed at the copy. Nothing
 * is written through a reference, because there are no references — so assigning to a field of a
 * function's parameter updates that function's own copy and the caller sees nothing.
 *
 * [target] is the record expression to the left of the last dot, so `a.b.c = v` carries `a.b` and rebuilds
 * outward: `a = a with { b: a.b with { c: v } }`.
 */
class FieldAssignStmt(
    val target: Expr,
    val field: String,
    val value: Expr,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
    /** Set when written `s.f += 1` — see [AssignStmt.op]. */
    val op: BinaryOp? = null,
) : Stmt()

/**
 * [elseBranch] is a [Block] for `else { }` and an [IfStmt] for `else if` — so a chain is a nested Branch on
 * the False arm, which is exactly the graph shape, and exactly what the printer recognises to rebuild it.
 */
class IfStmt(
    val condition: Expr,
    val then: Block,
    val elseBranch: Stmt?,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
) : Stmt()

/**
 * `if val t = nearest() { … } else { … }` — bind [name] to [value] and run [then], when it is there.
 *
 * Separate from [IfStmt] because it is a different NODE, not a different condition: the binding needs a
 * pin to come out of, and that pin's type is the option's with the `?` removed. See
 * [dev.ziggle.vscript.model.BuiltinNodes.IF_SOME].
 */
class IfLetStmt(
    val name: String,
    val value: Expr,
    val then: Block,
    val elseBranch: Stmt?,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
) : Stmt()

/**
 * An `else { }` arm — a block where the grammar wants a statement.
 *
 * A wrapper rather than typing [IfStmt.elseBranch] as block-or-if, because the `else if` case has to stay an
 * [IfStmt]: that is what the printer looks for to rebuild a chain rather than emitting a bare nested `if`.
 * Carries no annotations of its own — an `else` block is punctuation, not a node.
 */
class ExprBlockStmt(val block: Block) : Stmt() {
    override val span: Span get() = block.span
}

class WhileStmt(val condition: Expr, val body: Block, override val span: Span, override val ann: Annotations = Annotations.NONE) : Stmt()

/** [index] is null for `for x in xs` and named for `for (x, i) in xs` — whether the Index pin is wired. */
class ForStmt(
    val element: String,
    val index: String?,
    val list: Expr,
    val body: Block,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
) : Stmt()

class ReturnStmt(val values: List<Expr>, override val span: Span, override val ann: Annotations = Annotations.NONE) : Stmt()

class BreakStmt(override val span: Span, override val ann: Annotations = Annotations.NONE) : Stmt()

class ContinueStmt(override val span: Span, override val ann: Annotations = Annotations.NONE) : Stmt()

/**
 * `sequence { } { } …` — 2 to 4 arms, run in order.
 *
 * Explicit syntax rather than something inferred, because ordinary statement chains already lower to chained
 * exec wires. Without a spelling of its own a Sequence node could be lowered but never printed back, and the
 * round trip would quietly lose it.
 */
class SequenceStmt(val arms: List<Block>, override val span: Span, override val ann: Annotations = Annotations.NONE) : Stmt()

/**
 * `try { … } catch e { … }` — the language's exceptions.
 *
 * [error] is the name the message is bound to in [catch], and there is no type on it: what is caught is a
 * STRING, always, because there are no exception classes to tell apart. That is the trade the whole
 * construct is built on — no hierarchy, no `catch` clause ordering, no "which one does this match".
 */
class TryStmt(
    val body: Block,
    val error: String,
    val catch: Block,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
) : Stmt()

/**
 * `when` — the first arm that matches, and only that one.
 *
 * Two forms, as Kotlin has. With a [subject] each [WhenArm.value] is a value to EQUAL; without one each is a
 * condition to be true. Kept as one statement rather than two because they are one construct: an author
 * writes a subject when every arm asks about the same thing, and omits it when they do not.
 *
 * [elseArm] is a [Block] rather than an arm with no value, so "is there an else" is a null check rather than
 * a search for a sentinel — and so it cannot accidentally be given a value.
 */
class WhenStmt(
    /** Null for the condition form. */
    val subject: Expr?,
    val arms: List<WhenArm>,
    val elseArm: Block?,
    override val span: Span,
    override val ann: Annotations = Annotations.NONE,
) : Stmt()

/** One arm: what to match, and what to run. */
class WhenArm(val value: Expr, val body: Block, val span: Span)

/** A call written for its effect. */
class ExprStmt(val expr: Expr, override val span: Span, override val ann: Annotations = Annotations.NONE) : Stmt()

// ---- expressions ------------------------------------------------------------------------------------

sealed class Expr {
    abstract val span: Span
}

/** What was written, so the printer can put back the same spelling rather than a canonicalised one. */
enum class LiteralKind { INT, FLOAT, STRING, BOOL, COLOR, NULL }

class LiteralExpr(val value: Any?, val kind: LiteralKind, override val span: Span) : Expr()

/**
 * A bare name: a `let` binding, a graph variable, or a nullary call — `Lower` decides, in that order.
 *
 * With a [module] it is none of those three: `banking::trips` is an imported document's variable, and the
 * local resolution order never runs. That is the point of qualification — a `let` cannot shadow it.
 */
class NameExpr(val name: String, override val span: Span, val module: String? = null) : Expr()

class Arg(val name: String?, val value: Expr, val span: Span = Span.NONE)

/**
 * `f(…)` or `draw.tile(…)`.
 *
 * [target] keeps the dotted parts as written. Resolution to a node type is `Names`' job and needs the
 * catalog: the printer emits the shortest unambiguous form, and the parser accepts any suffix that resolves,
 * so `nearestObjectNamed` and `game.nearestObjectNamed` are the same call.
 */
class CallExpr(
    val target: List<String>,
    val args: List<Arg>,
    override val span: Span,
    /**
     * The import alias — `banking` in `banking::withdraw(…)`, null for a local call.
     *
     * Kept apart from [target] rather than pushed onto the front of it, which is the whole reason `::` was
     * reserved as its own token: `.` already separates the parts of a node TYPE, and one separator doing
     * both jobs would leave the parser unable to tell `draw.tile` from an imported `draw::tile` without
     * knowing the import set — a lexical question turned into a semantic one.
     */
    val module: String? = null,
    /**
     * The value an EXTENSION is called on — `21` in `21.double()`, the inner call in `x.a().b()`.
     *
     * Null for every other call, including `xs.add(v)`: a call whose head is a bare name is read by
     * `identExpression`, which collects the dots into [target] because that is how a node TYPE is spelled
     * (`draw.text`). This field is for the receivers that CANNOT be a dotted name — a literal, a call's
     * result, anything parenthesised — which reach the dot through `postfix` instead.
     *
     * Two paths for one idea is not ideal, and the alternative is worse: teaching `identExpression` to stop
     * at the first dot would need it to know whether `draw` is a namespace or a value, which is exactly the
     * resolution the parser refuses to do.
     */
    val receiver: Expr? = null,
    /**
     * A lambda written AFTER the argument list — `xs.filter { it > 3 }`.
     *
     * Kept apart from [args] rather than appended to them, and that is what makes the spelling
     * round-trip without a marker in the graph: a lambda may **only** be written here, so a call that has
     * one always had it here, and the printer puts it back in the one place it could have come from.
     * Bound to the last parameter, which must be function-typed — Kotlin's rule, and the only one that
     * needs no name at the call site.
     */
    val trailing: LambdaExpr? = null,
) : Expr() {
    val name: String get() = target.joinToString(".")

    /** How it was written, qualifier and all — for diagnostics, which should quote the source. */
    val qualified: String get() = if (module == null) name else "$module::$name"
}

enum class BinaryOp {
    ADD, SUB, MUL, DIV, MOD,
    EQ, NE, LT, LE, GT, GE,

    /**
     * `&&` and `||` — **short-circuiting**, and distinct from the `and(a, b)` / `or(a, b)` calls.
     *
     * Not redundancy: `logic.and` evaluates both sides always, and these lower to `logic.select`, which
     * does not. Two genuinely different nodes, so collapsing them to one spelling would make the language
     * lie about which one it built.
     */
    AND_THEN, OR_ELSE,
}

class BinaryExpr(val op: BinaryOp, val left: Expr, val right: Expr, override val span: Span) : Expr()

/**
 * `x is Int`, `x !is Point` — a run-time type TEST.
 *
 * Never a narrowing: it answers yes or no and hands nothing back. A type here belongs to a pin rather
 * than to a point in the exec chain, so there is nowhere to record that a value is something narrower
 * inside one branch — and on the canvas the wire into the branch is the same wire as the one outside it.
 *
 * [negated] rather than wrapping in [NotExpr], so `x !is T` and `!(x is T)` stay distinguishable and each
 * prints back as it was written.
 */
class IsExpr(
    val value: Expr,
    val typeName: String,
    val negated: Boolean,
    override val span: Span,
    val module: String? = null,
) : Expr()

/**
 * `p as Vec2i`, `t as Vec3i { z: plane }` — one record read as another, by NAME.
 *
 * [renames] is target field -> source field, for the pairs whose names differ. See `BuiltinNodes.CAST`
 * for why matching is by name and why nothing is zero-filled.
 */
class AsExpr(
    val value: Expr,
    val typeName: String,
    val renames: List<FieldInit>,
    override val span: Span,
    val module: String? = null,
    /**
     * The target's type arguments, for `json as LIST<Item>` / `json as MAP<STRING, Tally>`.
     *
     * Empty for a record-to-record cast, where arguments mean nothing and the validator refuses them.
     * Carried here rather than dropped because a decode's target is a SHAPE, not just a name, and the
     * shape is the whole of what the reader checks the document against.
     */
    val typeArgs: List<TypeExpr> = emptyList(),
) : Expr()

/** `!x`. The only prefix operator — there is no unary minus; see VSCRIPT_LANG_PLAN.md §6.7. */
class NotExpr(val operand: Expr, override val span: Span) : Expr()

/** `c ? a : b` — `logic.select`, which short-circuits because it has a jump to skip the untaken arm with. */
class TernaryExpr(val condition: Expr, val ifTrue: Expr, val ifFalse: Expr, override val span: Span) : Expr()

/**
 * `a ?: b` — [value] unless it is nothing, then [fallback].
 *
 * Its own node rather than a [BinaryExpr] with one more operator, because it does not lower like one: a
 * binary operator becomes an arithmetic or comparison node with `A`/`B` pins, and this becomes
 * `value.orElse` with `Value`/`Fallback` and a jump between them.
 */
class ElvisExpr(val value: Expr, val fallback: Expr, override val span: Span) : Expr()

/**
 * `a?.b`, `a?.f(x)` — do [access] only when [receiver] is there, and answer nothing when it is not.
 *
 * [access] is an ordinary access expression written against [SafeItExpr], which stands in for the receiver
 * once it is known to be present. Building it that way rather than as a flag on `MemberExpr` is what keeps
 * every access form working through `?.` without each needing its own case: a field read, a method call and
 * an index are all just "an expression whose target is the placeholder".
 */
class SafeAccessExpr(val receiver: Expr, val access: Expr, override val span: Span) : Expr()

/** The receiver of a [SafeAccessExpr], inside its access. Never written; only ever built by the parser. */
class SafeItExpr(override val span: Span) : Expr()

/**
 * `x.name` — a record field, or one output pin of a multi-output call.
 *
 * One syntax for both, resolved by type in `Lower`, which has the pin types the parser does not. Reads the
 * same either way, which is the argument for not splitting it: both are "the part of it called `name`".
 */
class MemberExpr(val target: Expr, val member: String, override val span: Span) : Expr()

/** `xs[i]` — `list.at`, which stops the script when there is no such position. */
class IndexExpr(val target: Expr, val index: Expr, override val span: Span) : Expr()

class FieldInit(val name: String, val value: Expr, val span: Span = Span.NONE)

/** `T { a: 1 }`, or `banking::Account { … }` — `struct.make`. */
class StructLitExpr(
    val type: String,
    val fields: List<FieldInit>,
    override val span: Span,
    val module: String? = null,
) : Expr()

/** `s with { a: 2 }` — `struct.set`: a COPY with one field replaced, never a write through the wire. */
class WithExpr(val target: Expr, val fields: List<FieldInit>, override val span: Span) : Expr()

/** `[a, b, c]` — `value.list`, whose `Of` and `Count` are shape pins written from the contents. */
class ListLitExpr(val items: List<Expr>, override val span: Span) : Expr()

/**
 * `{ it * 2 }` / `{ x -> x * 2 }` / `{ a, b -> a + b }` — a function written where it is used.
 *
 * **It is an anonymous function and nothing more exotic.** `Lower` synthesises a real function from it,
 * under a name beginning with `@` that no author can type, and puts an ordinary function reference on the
 * wire. So the canvas shows a function box and a reference to it, the same two things a named function
 * shows; only the printer knows the box was written inline, and it knows it from the name.
 *
 * The body is an EXPRESSION, never a block: a function value is called from inside an expression, where
 * there is no exec chain for a statement to sequence on. That is the same rule `fn f(x) = …` already obeys
 * and the same reason [dev.ziggle.vscript.model.BuiltinNodes.FUNCTION_REF] refuses a step-bodied function.
 *
 * [params] empty means the implicit one — `it`. It is a real parameter with a real name; nothing about it
 * is special except that it was not written down.
 */
class LambdaExpr(
    val params: List<String>,
    /**
     * The expression it evaluates to — its LAST one — or nothing at all.
     *
     * **Null is the do-nothing body**, which is the only spelling a `fn(T)` has for one: a function type
     * that hands nothing back is ordinary (§3.7), and `{ }` is how you write it. Null also covers a body
     * whose last statement is not an expression — `{ log(x)  for y in ys { } }` runs and returns nothing.
     * Allowed only where the destination has no result; see `Lower.lambdaRef`.
     */
    val body: Expr?,
    override val span: Span,
    /**
     * Whether an `->` was written, which is the ONLY thing separating `{ -> f() }` from `{ f() }`.
     *
     * Both have no [params], and they mean different things: the second is the implicit `it` and takes one
     * argument, the first takes none. Without this flag a pin typed `fn() -> BOOL` had no lambda spelling
     * at all — a bare `{ … }` was always arity one — and could only ever be fed a named function.
     */
    val arrow: Boolean = false,
    /**
     * What runs before [body] — the lambda's own block.
     *
     * **A lambda is a scope, not an expression with a value pinned to it.** It used to be exactly one
     * expression, and the reason was the graph's: a function value is called from inside an expression,
     * where there is no exec chain a statement could sequence on. That is a fact about a node canvas. The
     * text front end compiles a lambda into a real chunk with a real body, so the restriction bought
     * nothing there and cost the obvious thing — no `val` inside a `filter`, so anything that needed to
     * name an intermediate had to be lifted out into a named function.
     *
     * The graph front end still cannot represent these and refuses them by name rather than by accident.
     */
    val stmts: List<Stmt> = emptyList(),
) : Expr() {
    companion object {
        /** The name of the parameter a lambda that declares none gets. */
        const val IT = "it"
    }
}

