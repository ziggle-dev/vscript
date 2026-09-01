package dev.ziggle.vscript.model

/**
 * One `import banking from "banking"` on a document.
 *
 * **Two ways to name the same document, and both are needed.** [ref] is what a person wrote and what the
 * printer emits, so the text stays readable and diffable. [docId] is [Graph.id] — the stable GUID whose
 * whole purpose, per its own KDoc, is that "cross-document references survive renaming". Resolution tries
 * the id first and falls back to the ref, so renaming a library breaks nothing that already imports it,
 * while a fresh `.vs` file that has only ever had the ref still resolves.
 *
 * [docId] is therefore null exactly when the import came from text that has not yet been through a
 * document: lowering fills it in the moment the reference resolves.
 *
 * The [alias] is **mandatory** and every imported name is qualified by it at every use
 * (`banking::withdraw`). That is not ceremony, it is the reason this design needs no collision rules at
 * all: there is no shadowing question, no "which `withdraw` did you mean", and no import-order
 * significance. Unqualified imports could be added later on top of this; they could not be taken away.
 */
class GraphImport(
    /**
     * The namespace this document was brought in under — `bank` in `import * as bank from "banking"`.
     *
     * Always present, even when nothing was written: the forms that introduce LOCAL names get a
     * SYNTHESISED alias (`@1`, `@2`, …) that is never printed. That is what keeps those forms to a parser,
     * a resolver and a printer — a Call node still names its callee `@1::withdraw`, so the compiler, the
     * validator, the document format and the import closure are untouched. See [isAnonymousAlias].
     */
    val alias: String,
    val ref: String,
    val docId: String? = null,
    /** `import {abs, max as biggest} from "x"` — named imports, each under its local spelling. */
    val named: List<ImportItem> = emptyList(),
    /**
     * `import run from "x"` — the local name bound to the other document's `export default`.
     *
     * Resolved as a renamed named import: whatever the target declares as its default is looked up, and
     * this name is bound to it. So a default costs no new lookup path, only the indirection that finds
     * which declaration it is.
     */
    val default: String? = null,
    /**
     * `export { a, b as c } from "y"` — names this document passes STRAIGHT ON to whoever imports it.
     *
     * Beside [named] rather than instead of it, because the two are different questions with the same
     * spelling: a named import puts `a` in this file's scope, a re-export puts it in this file's SURFACE
     * and nowhere else. TypeScript's split, and the reason `export … from` is not sugar for an import
     * followed by an export.
     */
    val reExported: List<ImportItem> = emptyList(),
    /** `export * from "y"` — everything the target offers, passed on under its own spelling. */
    val reExportAll: Boolean = false,
    /**
     * `import "core/list"` — **everything the document exports, under its own name.**
     *
     * The one form that names nothing. It exists because the utility documents are entirely EXTENSIONS —
     * `core/list`, `core/tile` and `core/objects` declare eleven, two and three of them and no plain
     * functions at all — and listing eleven verbs to get a list library is ceremony that says nothing.
     *
     * **It never takes a name that is already spoken for.** A node, another import or this document's own
     * declaration keeps its meaning, and the name simply does not arrive; two of these offering one name
     * bind neither, and using it is an error naming both. Reported where the name is USED rather than at
     * the import, because one unlucky name — `contains` is a node — would otherwise poison a whole line
     * somebody wanted for the other twenty. That is `import java.util.*`'s rule, for `import java.util.*`'s
     * reason.
     */
    val star: Boolean = false,
) {
    fun withId(id: String?): GraphImport =
        GraphImport(alias, ref, id, named, default, reExported, reExportAll, star)

    /** Does this import put names into the local namespace, rather than behind an alias? */
    val isUnqualified: Boolean get() = named.isNotEmpty() || default != null || star

    /** Was a namespace actually written, or is [alias] one the language invented? */
    val hasNamespace: Boolean get() = !isAnonymousAlias(alias)

    /** Does this line pass anything on to whoever imports THIS document? */
    val isReExport: Boolean get() = reExportAll || reExported.isNotEmpty()

    /**
     * Does this line put anything in this document's own scope?
     *
     * False for a pure `export … from`, which is the whole of what makes it different from an import: the
     * names go past this file rather than into it, so nothing here may call them and — the part that has
     * to be checked rather than assumed — nothing here inherits its extensions either.
     */
    val bindsLocally: Boolean get() = hasNamespace || isUnqualified

    override fun toString(): String = "import ${spelling()} from \"$ref\""

    /** `export { a, b as c } from "y"` / `export * from "y"` — how the re-export half was written. */
    fun reExportSpelling(): String =
        if (reExportAll) "*" else reExported.joinToString(", ", "{ ", " }") { it.spelling() }

    /** What was WRITTEN before `from` — in the order TypeScript writes it: default, namespace, names. */
    fun spelling(): String {
        val parts = ArrayList<String>()
        default?.let { parts += it }
        if (hasNamespace) parts += "* as $alias"
        if (named.isNotEmpty()) parts += named.joinToString(", ", "{ ", " }") { it.spelling() }
        // `import "core/list"` writes nothing at all before `from`, so the printer has nothing to join —
        // and `Print` drops the `from` with it. See its import loop.
        return parts.joinToString(", ")
    }

    companion object {
        /**
         * The alias an unqualified import is given, which nobody can type.
         *
         * `@`-prefixed for the same reason a lambda's function name is: it marks a name the language
         * invented, so nothing mistakes it for one an author could have written or could collide with.
         */
        fun anonymousAlias(n: Int): String = "@$n"

        fun isAnonymousAlias(alias: String): Boolean = alias.startsWith("@")
    }
}

/**
 * One name taken out of a document — `abs`, or `abs as absolute`.
 *
 * [local] is what this file calls it and [name] is what the other document calls it; they are the same
 * unless `as` was written. Resolution goes one way (local → name) and printing the other, which is the
 * whole of per-item aliasing.
 */
class ImportItem(val name: String, val local: String = name) {
    val isAliased: Boolean get() = local != name

    fun spelling(): String = if (isAliased) "$name as $local" else name

    override fun toString(): String = spelling()
}

/**
 * Where a [GraphImport] is looked up — supplied by the host, because the core does not know what a
 * document store is.
 *
 * The editor reads `graphsDir`; a test hands over a map; the CLI resolves beside the file it was given.
 * Keeping it a lambda is what lets `Lower`, `Validator` and `GraphCompiler` all share one resolution rule
 * without any of them gaining a dependency on the filesystem.
 */
fun interface GraphSource {
    /** The document [imp] names, or null when nothing answers to it. */
    fun load(imp: GraphImport): Graph?

    /**
     * Why the document [imp] names cannot be trusted, or null when it is fine.
     *
     * **The other half of returning a broken document's graph.** A source is asked for a callee's
     * signature, and a document with an error in one function body still has perfectly good signatures for
     * all of them — so refusing to hand it over means the importer cannot shape a single call through it
     * and reports `'sqrt' takes 0 value(s)` at every call site instead of leaving the one real error where
     * it is. Handing it over fixes the noise and opens a worse hole: the importer would then compile and
     * RUN against a library that does not compile, whose failed statements were simply never lowered.
     *
     * So a source may answer both — here is the shape, and here is why you must not run it. Sources that
     * only ever hold known-good documents (the tests, the canvas) inherit "nothing is wrong" and are
     * unaffected.
     */
    fun problem(imp: GraphImport): String? = null

    companion object {
        /** Resolves nothing. The default wherever imports are not expected — tests, and the canvas today. */
        val NONE: GraphSource = GraphSource { null }

        /**
         * Every document in [graphs], answering to its id and to its name.
         *
         * **A blank id is not an identity.** `Lower` gives every document it builds `id = ""`, because a
         * text file has no document id to carry — so keying on one collapses every text-lowered document
         * onto a single entry, and each import then resolves to whichever won. What that looks like from
         * the outside is "import cycle: a -> a" on documents that import nothing of the sort.
         * `DocumentSource` already guards this; missing it here made `of` unusable for exactly the case
         * text imports need.
         */
        fun of(graphs: List<Graph>): GraphSource {
            val byId = graphs.filter { it.id.isNotBlank() }.associateBy { it.id }
            val byName = graphs.associateBy { it.name }
            return GraphSource { imp ->
                imp.docId?.takeIf { it.isNotBlank() }?.let { byId[it] } ?: byName[imp.ref]
            }
        }
    }
}

/**
 * What identifies a document inside a closure.
 *
 * **Its id, or its NAME when it has no id.** A canvas document carries a GUID; one lowered from `.vs` text
 * carries nothing, because a text file has no id field to read one out of — `Lower` writes `id = ""`.
 * Keying the closure on the raw id therefore collapsed every text document onto a single entry: `seen`
 * held one, `aliases` was shared by all of them, and `target.id == doc.id` was true for any two, so a
 * `.vs` importing a `.vs` reported "import cycle" on documents that import nothing of the sort. Text
 * imports could not work at all.
 *
 * The name is the right fallback rather than an invented id, because the name is already what an import
 * resolves BY — `import banking from "banking"` names a document, and `DocumentSource` registers each one
 * under exactly that. Two documents sharing a name were already indistinguishable to an importer, so this
 * makes the closure agree with the resolver instead of disagreeing with it.
 */
internal fun key(graph: Graph): String = graph.id.ifBlank { graph.name }

/**
 * A type [doc] declares, spelled as an importer that knows it as [alias] must write it.
 *
 * Reaching a signature or a record ACROSS an import brings its types with it, and those types are named
 * from the declaring document's point of view — `manhattan(a: Point, b: Point)` in `geometry` is
 * `manhattan(a: geo::Point, b: geo::Point)` to everyone else. Without this the caller's values are typed
 * `geo::Point`, the callee's pins `Point`, and the two refuse to wire: "cannot wire geo::Point into
 * Point", on a call that is perfectly correct.
 *
 * One function for every place that crosses the boundary — a record's fields, a function's parameters and
 * its results — because three copies of it would eventually disagree about one of them.
 */
internal fun qualifyThrough(alias: String, doc: Graph, t: TypeRef): TypeRef {
    // **Every argument, whatever the kind.** This used to descend into a LIST and nothing else, so a
    // `MAP<Target, …>` crossed with its key still spelled `Target`, and — the one that was actually hit —
    // a field typed `fn(HunterRumor)` crossed with its PARAMETER unqualified. Invoking it then reported
    // "cannot wire @2::HunterRumor into HunterRumor" on the only call that could have been written.
    //
    // Asking "is it a list, is it a map, is it a function" is what produced that gap and would produce the
    // next one. A list's element, a map's halves and a function's parameters and result are all [args], and
    // rewriting them uniformly cannot miss a kind.
    val args = t.args.map { qualifyThrough(alias, doc, it) }
    // Records AND enums. An enum has nothing to qualify INSIDE it — its members are plain names — and that
    // is the fact this used to check, which is a different fact from the one that matters here: an enum's
    // NAME is a type, and it is written as one wherever the document declares a field or a parameter of it.
    // Missing that, `HunterRumor { target: Target }` crossed an import with its field still typed `Target`
    // while `vars::Target.WildKebbit` arrived typed `vars::Target`, and the two refused to wire on a record
    // literal that was perfectly correct.
    val declaredThere = t.declared &&
        (doc.types.any { it.name == t.name } || doc.enums.any { it.name == t.name })
    // `renamedTo`/`withArgs` rather than a fresh TypeRef, because the `?` and the type ARGUMENTS have to
    // ride along: `Point?` crossing an import is still optional, and `Pair<A, B>` still has its halves.
    // Building a new name dropped both.
    val renamed = if (declaredThere) t.renamedTo("$alias${QualName.SEP}${t.name}") else t
    return renamed.withArgs(args)
}

/** The same, over a whole signature. */
internal fun qualifyThrough(alias: String, doc: Graph, fn: GraphFunction): GraphFunction = GraphFunction(
    fn.name,
    fn.params.map { FunctionPin(it.name, qualifyThrough(alias, doc, it.type)) },
    fn.results.map { FunctionPin(it.name, qualifyThrough(alias, doc, it.type)) },
    fn.isExported,
    // The receiver crosses too, and is renamed like any other type in the signature. Dropping it — which is
    // what this did before extensions existed — makes an imported extension arrive looking like an ordinary
    // function, so the printer writes it as one and the call it was written as stops round-tripping.
    receiver = fn.receiver?.let { qualifyThrough(alias, doc, it) },
)

/** One name a document offers, and the document it actually lives in. */
class Export(val owner: Graph, val name: String)

/**
 * The name `export { default as x } from "y"` uses for the other document's default.
 *
 * TypeScript's spelling, and it costs nothing: keywords lex as identifiers here, so `default` in a name
 * list is an ordinary item whose meaning is decided at resolution. A document that genuinely declares
 * something called `default` loses this one spelling, which is the same trade TypeScript makes.
 */
const val DEFAULT_ITEM = "default"

/**
 * Everything [doc] offers an importer — what it declared `export` on, plus what it passes on.
 *
 * **The export map is the whole of re-exports.** Every question the language asks about an imported name
 * — is it there, what is its signature, what record does that field type mean — used to be answered by
 * looking at the target document's own declarations. A barrel declares nothing, so each of those had to
 * learn to follow a chain instead; routing them all through one map is what stops that being five
 * implementations of "follow a chain" that can disagree about cycles.
 *
 * An [Export] names the OWNING document, not the one asked. That is what keeps a re-export free at run
 * time: the importer's Call resolves to the document that really declares the function, so a barrel adds
 * a hop at compile time and no hop at all afterwards.
 *
 * **Re-exports are read first and this document's own declarations second**, so a local declaration wins
 * over an `export *` that offers the same name. TypeScript's rule, and the one that makes a barrel safe to
 * add a name to: nothing downstream changes meaning because a library grew a symbol.
 *
 * [seen] is the cycle guard, and it is the reason this is a function rather than a field: a barrel may
 * re-export a document that re-exports it back, which is a ring in the export graph even when it is not
 * one in the import graph.
 */
fun exportsOf(
    doc: Graph,
    resolve: (Graph, GraphImport) -> Graph?,
    seen: Set<String> = emptySet(),
): Map<String, Export> {
    val out = LinkedHashMap<String, Export>()
    if (key(doc) in seen) return out
    val deeper = seen + key(doc)
    for (imp in doc.imports) {
        if (!imp.isReExport) continue
        val target = resolve(doc, imp) ?: continue
        val there = exportsOf(target, resolve, deeper)
        if (imp.reExportAll) out.putAll(there)
        for (item in imp.reExported) {
            val real = if (item.name == DEFAULT_ITEM) target.defaultExport ?: continue else item.name
            there[real]?.let { out[item.local] = it }
        }
    }
    // ...and this document's own, last, so they win.
    // Synthesised FIELD DEFAULTS cross too, though no author can name one: an importer that omits a
    // field has to be able to call the function that supplies it. Every other `@` name — a lambda — stays
    // where it was made. See BuiltinNodes.FIELD_DEFAULT.
    doc.functions
        .filter {
            it.isExported &&
                (!BuiltinNodes.isAnonymous(it.name) || it.name.startsWith(BuiltinNodes.FIELD_DEFAULT))
        }
        .forEach { out[it.name] = Export(doc, it.name) }
    doc.types.filter { it.isExported }.forEach { out[it.name] = Export(doc, it.name) }
    doc.enums.filter { it.isExported }.forEach { out[it.name] = Export(doc, it.name) }
    doc.variables.filter { it.isExported }.forEach { out[it.name] = Export(doc, it.name) }
    // A `const` is a literal NODE carrying its name — see BuiltinNodes.CONST_NAME.
    doc.nodes.filter { it.literals[BuiltinNodes.CONST_EXPORTED] == true }
        .mapNotNull { it.literals[BuiltinNodes.CONST_NAME] as? String }
        .forEach { out[it] = Export(doc, it) }
    // A record's COMPUTED FIELD DEFAULTS travel with it, under whatever name the record is offered as.
    //
    // They are functions, but not ones anybody wrote or can name: a literal that leaves such a field out
    // calls one, so a document offering the type without them offers a type nobody can construct. A named
    // re-export is where this bites — `export { Leg } from "shapes"` names the record and could not name
    // machinery it does not know exists. Renamed with it, so `export { Leg as Segment }` offers
    // `@default:Segment.slack` pointing at the owner's `@default:Leg.slack`.
    for ((offered, e) in out.toList()) {
        if (e.owner.structExactly(e.name) == null) continue
        val prefix = BuiltinNodes.FIELD_DEFAULT + e.name + "."
        for (fn in e.owner.functions) {
            if (!fn.name.startsWith(prefix)) continue
            out[BuiltinNodes.fieldDefaultName(offered, fn.name.removePrefix(prefix))] = Export(e.owner, fn.name)
        }
    }
    return out
}

/**
 * Names two `export *` lines both offer, from different documents.
 *
 * **Ambiguous rather than last-wins.** `export *` is the one export form that names nothing, so a document
 * can acquire a collision by a library it forwards growing a symbol — and the file that breaks is not the
 * one that changed. Refusing it is the same bargain the unqualified import forms already make: a name in
 * this language does not quietly mean two things, and the cure is on the line being pointed at.
 *
 * A name the barrel DECLARES is not a conflict: a local declaration wins over a star, which is what makes
 * a barrel safe to add to. Nor is one a named re-export picks out, for the same reason — both are choices
 * somebody wrote down.
 */
fun starConflicts(doc: Graph, resolve: (Graph, GraphImport) -> Graph?): List<String> {
    val stars = doc.imports.filter { it.reExportAll }
    if (stars.size < 2) return emptyList()
    val chosen = HashSet<String>()
    doc.functions.filter { it.isExported }.forEach { chosen += it.name }
    doc.types.filter { it.isExported }.forEach { chosen += it.name }
    doc.enums.filter { it.isExported }.forEach { chosen += it.name }
    doc.variables.filter { it.isExported }.forEach { chosen += it.name }
    doc.imports.flatMap { it.reExported }.forEach { chosen += it.local }
    val owners = LinkedHashMap<String, MutableSet<String>>()
    for (imp in stars) {
        val target = resolve(doc, imp) ?: continue
        for ((name, e) in exportsOf(target, resolve, setOf(key(doc)))) {
            owners.getOrPut(name) { LinkedHashSet() } += key(e.owner)
        }
    }
    return owners.filter { (name, from) -> from.size > 1 && name !in chosen }.keys.toList()
}

/**
 * Every document one run spans, and the one globals layout they all agree on.
 *
 * **This exists because a variable's slot is its POSITION.** `GraphCompiler` maps each variable to its
 * index in `graph.variables` and `Op.GETG`/`SETG` index a single flat array per run — so two documents
 * both believe their first variable is slot 0, and the second one to run would quietly write over the
 * first. Handing every document a base offset is what makes one array able to hold all of them.
 *
 * Documents are deduplicated **by [Graph.id]**, and that is the load-bearing detail for imported state. A
 * diamond — A imports B and C, both of which import D — must give D's variables **one** cell each, not
 * two. Anything else is by-value import: the library's counter forks, each importer sees its own copy
 * advancing, and nothing anywhere says so. By reference is the only semantics under which a library that
 * keeps state and a caller that reads it are talking about the same thing.
 */
/**
 * One entry node and the document that declares it.
 *
 * The pair rather than the node alone, because a node id is only unique WITHIN a document — two documents
 * in one closure routinely both have a node 3. Anything that keys entries by id across a closure is
 * silently keeping one of them.
 */
class DocEntry(val document: Graph, val node: Node)

class ImportClosure private constructor(
    /** Every document reachable from the root, root first, in a deterministic order. */
    val documents: List<Graph>,
    /** Graph id → the first globals slot belonging to that document. */
    private val bases: Map<String, Int>,
    /** Graph id → (alias → the document it names), for every document in the closure. */
    private val aliases: Map<String, Map<String, Graph>>,
    /** Why the closure is incomplete. Empty means every import resolved and there are no cycles. */
    val errors: List<ImportError>,
) {
    val ok: Boolean get() = errors.isEmpty()

    val root: Graph get() = documents.first()

    /** The document [alias] names inside [inDoc], or null when it names nothing there. */
    fun resolve(inDoc: Graph, alias: String): Graph? = aliases[key(inDoc)]?.get(alias)

    /**
     * The first globals slot belonging to [doc].
     *
     * A document outside the closure answers 0 rather than throwing: the compiler asks this while
     * assembling a chunk, and a closure that failed to resolve has already reported why — a second failure
     * here would bury the first under a stack trace from a different layer.
     */
    fun globalsBase(doc: Graph): Int = bases[key(doc)] ?: 0

    /** Total slots across every document — the size of the run's globals array. */
    val globalsSize: Int get() = documents.sumOf { it.variables.size }

    /**
     * Starting values for every document's variables, in slot order.
     *
     * Each document's defaults are computed against **its own** declarations, because a record-typed
     * variable's zero is built from the struct that document declares — asking the importer would find
     * nothing, or worse, find a different type of the same name.
     */
    fun startingGlobals(zero: (Graph, GraphVariable) -> Any?): List<Any?> =
        documents.flatMap { doc -> doc.variables.map { v -> zero(doc, v) } }

    /** The documents in [documents] other than the root — what an importer actually pulled in. */
    val imported: List<Graph> get() = documents.drop(1)

    /**
     * Every entry [select] finds, anywhere in the closure, tagged with the document that declares it.
     *
     * **Entries were the one thing an import did not carry.** A document's functions, types, enums,
     * extensions and variables all survive being imported; its `on start` / `on render` / `on tick` /
     * `on stop` did not, so a library could do nothing on its own behalf — it could only be called. That
     * is why `graahk` knew what its lure looked like and could not draw it, and the `on render` had to be
     * hoisted into `entry`, which knows nothing about lures.
     *
     * **Deduplicated already.** [documents] holds each document once, keyed by [key], so a library reached
     * through two different aliases contributes its entries once rather than twice — which is the bug this
     * would otherwise have, and a silent one: the handler would simply run twice per tick.
     *
     * [innermostFirst] reverses the walk, which puts every document ahead of the one that imported it.
     * That is the order for anything that SETS UP — a library initialises before its user — and the wrong
     * one for tearing down, where the user should finish before what it depends on. Reversing a
     * root-first depth-first walk is exactly "every descendant before its ancestor"; no second walk.
     */
    fun entriesAcross(innermostFirst: Boolean, select: (Graph) -> List<Node>): List<DocEntry> =
        (if (innermostFirst) documents.asReversed() else documents)
            .flatMap { doc ->
                // `always on` — see [BuiltinNodes.ALWAYS_ENTRY]. The ROOT runs all of its entries however
                // they were written: a document being run directly is the case the plain form is for. An
                // IMPORTED document contributes only the ones that said `always`, which is the same
                // opt-in shape `export` gives a name, in the one place where the thing being decided is
                // whether something RUNS rather than whether it can be said.
                select(doc)
                    .filter { doc === root || it.literals[BuiltinNodes.ALWAYS_ENTRY] == true }
                    .map { DocEntry(doc, it) }
            }

    companion object {
        /**
         * Walk [root]'s imports transitively.
         *
         * Depth-first in declaration order, which is what makes the slot layout **stable**: the same
         * document set always produces the same offsets, so a chunk compiled a moment ago and a chunk
         * compiled now agree without being told. Adding an import to the end of a document therefore only
         * ever appends slots; adding one in the middle renumbers, which is fine because everything is
         * recompiled together.
         *
         * Errors are collected rather than thrown. A missing library and a cycle are both things a person
         * fixes by editing, and reporting all of them beats reporting the first.
         */
        fun resolve(root: Graph, source: GraphSource): ImportClosure {
            val documents = ArrayList<Graph>()
            val seen = HashMap<String, Graph>()
            val aliases = HashMap<String, MutableMap<String, Graph>>()
            val errors = ArrayList<ImportError>()

            fun walk(doc: Graph, path: List<Graph>) {
                if (seen.put(key(doc), doc) == null) documents += doc
                val here = aliases.getOrPut(key(doc)) { LinkedHashMap() }
                if (here.isNotEmpty()) return // already expanded — a document's imports are the same everywhere

                for (imp in doc.imports) {
                    // Two unqualified imports of the SAME document deliberately share one synthesised
                    // alias — that is what keeps its `Point` one type across `import * from "geo"` and
                    // `import {chebyshev as distance} from "geo"`. Not a duplicate: an alias still names
                    // one thing, and here both name the same one.
                    val sharedAnon = GraphImport.isAnonymousAlias(imp.alias) &&
                        here[imp.alias]?.let { it.name == imp.ref || it.id == imp.docId } == true
                    if (here.containsKey(imp.alias) && !sharedAnon) {
                        errors += ImportError.DuplicateAlias(doc, imp.alias)
                        continue
                    }
                    val target = source.load(imp)
                    if (target == null) {
                        errors += ImportError.Unresolved(doc, imp)
                        continue
                    }
                    // Resolved, but not sound. Reported against the IMPORT rather than left to surface as
                    // whatever the broken library does at run time — and the closure still walks into it,
                    // so its signatures shape the caller's Call nodes and the caller gets no second wave
                    // of complaints about damage this one line already explains.
                    source.problem(imp)?.let { errors += ImportError.Broken(doc, imp, it) }
                    // A cycle is refused at the DOCUMENT level, which is a far simpler rule than the one
                    // mutual recursion needs and the right rule anyway: a library that imports its own
                    // importer is not a library. Reported with the whole path, because "there is a cycle"
                    // without the route is a hunt through every import in the closure.
                    val cycle = path.indexOfFirst { key(it) == key(target) }
                    if (cycle >= 0 || key(target) == key(doc)) {
                        errors += ImportError.Cycle(path.drop(cycle.coerceAtLeast(0)) + doc + target)
                        continue
                    }
                    here[imp.alias] = target
                    walk(target, path + doc)
                }
            }

            walk(root, emptyList())

            var next = 0
            val bases = LinkedHashMap<String, Int>()
            for (doc in documents) {
                bases[key(doc)] = next
                next += doc.variables.size
            }
            return ImportClosure(documents, bases, aliases, errors)
        }

        /** A closure over one document that imports nothing — what every existing caller means. */
        fun single(graph: Graph): ImportClosure =
            ImportClosure(listOf(graph), mapOf(key(graph) to 0), mapOf(key(graph) to emptyMap()), emptyList())
    }
}

/**
 * A name that may carry an import alias — `banking::withdraw`, or plain `withdraw`.
 *
 * **The storage convention for the whole feature.** Wherever the graph already keeps a name — `Node.callee`,
 * `Node.variable`, a struct literal's type, a `TypeRef`'s name — a qualified one is kept in exactly the same
 * place, spelled `alias::name`. That is what let imports land without a second field on `Node` and without a
 * format change beyond the import list itself: every existing store of a name already holds this, and every
 * reader that needs to care splits here.
 *
 * The separator cannot appear in an identifier (the lexer makes `::` its own token), so the split is
 * unambiguous and a local name is unchanged by round-tripping through it.
 */
class QualName(val module: String?, val name: String) {
    val isQualified: Boolean get() = module != null

    override fun toString(): String = if (module == null) name else "$module$SEP$name"

    override fun equals(other: Any?): Boolean =
        other is QualName && other.module == module && other.name == name

    override fun hashCode(): Int = 31 * (module?.hashCode() ?: 0) + name.hashCode()

    companion object {
        const val SEP = "::"

        fun parse(s: String): QualName {
            val i = s.indexOf(SEP)
            return if (i < 0) QualName(null, s) else QualName(s.take(i), s.substring(i + SEP.length))
        }

        fun of(module: String?, name: String): QualName = QualName(module, name)
    }
}

/**
 * What names mean *from inside one document* — the resolver the plan (§7b) said every lookup should go
 * through instead of reaching into [Graph] directly.
 *
 * A scope is a pair: the closure (which documents exist and where their variables live) and the document
 * doing the asking. The second half is the whole reason this is not a global table — an imported
 * function's body resolves in **its own** document's scope, not its caller's, so `banking`'s idea of what
 * `helper` means must not leak into the graph that imported it. Compiling a body therefore rebases the
 * scope onto the defining document, which is one line at the call site and the reason a library can
 * itself import a library.
 */
class ImportScope(val closure: ImportClosure, val here: Graph) {

    /** The same closure, asking from [doc] instead — how a call crosses into an imported body. */
    fun inside(doc: Graph): ImportScope = if (key(doc) == key(here)) this else ImportScope(closure, doc)

    /**
     * The document a name's qualifier points at: this one when unqualified, null when the alias is unknown.
     *
     * Spelled as an `if` rather than `q.module?.let { resolve(…) } ?: here`, which reads identically and is
     * wrong: an alias that resolves to nothing makes the `let` produce null, and the elvis then hands back
     * **this** document — so `banking::withdraw` with no `banking` imported quietly looks for a local
     * `withdraw` instead of reporting the missing import. It compiles, and it calls the wrong function.
     */
    fun documentOf(q: QualName): Graph? =
        if (q.module == null) here else closure.resolve(here, q.module)

    /**
     * Is the QUALIFIED callee [name] an expression? Null when it is local — see [expressionCalls].
     *
     * Derived in the declaring document, through THAT document's scope, because a library's pure
     * function may itself call through an import and a bare `Graph::function` would not resolve it.
     */
    fun pureAcrossImport(catalog: NodeCatalog, name: String): Boolean? = QualName.parse(name).let { q ->
        val module = q.module ?: return@let null
        val doc = closure.resolve(here, module) ?: return@let false
        // The document that DECLARES it, which across a barrel is not the one the alias names. Purity is
        // derived from body NODES and a barrel has none, so asking it answered "impure" for every function
        // reached through one — and the call site then got exec pins nothing wires, reported as
        // "'Call.Result' is read here, but nothing runs 'Call'".
        val e = exports(doc)[q.name] ?: return@let false
        val there = inside(e.owner)
        // Recursing through THAT document's scope, so a library whose pure function calls another
        // library's is answered correctly too.
        isPureFunction(e.name, catalog, e.owner.nodes, there::function, e.owner.links) {
            there.pureAcrossImport(catalog, it)
        }
    }

    /**
     * **Visibility is enforced in RESOLUTION, not in a separate check.**
     *
     * An unexported symbol is simply not there when the question comes through an alias, so every consumer
     * — the validator, the compiler, the printer, the canvas's pickers — inherits the rule without knowing
     * it exists. A check bolted on beside resolution would have to be repeated at each of those, and the
     * one that got forgotten would be a hidden function that compiles and runs.
     *
     * Unqualified lookups are unaffected: `export` says what leaves the document, and a document can
     * always see its own declarations.
     */
    private val exportCache = HashMap<String, Map<String, Export>>()

    /** What [doc] offers, re-exports followed — see [exportsOf]. Memoised: a barrel is asked about often. */
    private fun exports(doc: Graph): Map<String, Export> =
        exportCache.getOrPut(key(doc)) {
            exportsOf(doc, resolve = { d, imp -> closure.resolve(d, imp.alias) })
        }

    /**
     * Where a QUALIFIED name really lives, or null when the document does not offer it.
     *
     * The single door every cross-document lookup goes through. An unqualified name is this document's own
     * business and answers `here`, unfiltered — `export` says what LEAVES, and a document can always see
     * what it declared.
     */
    /**
     * Where a name lives, IGNORING whether the document exports it.
     *
     * **For references the compiler made rather than ones a person wrote.** A folded record carries its
     * hooks as qualified names, and those functions are usually PRIVATE — that is the whole shape of an
     * activity: the record crosses the import and the hooks it holds do not. Asked through [owner] the
     * lookup fails at the export table and reports "no document", which blames the document for a name
     * that is right there and merely unexported.
     *
     * Visibility still governs what an AUTHOR may name: this is reached only as a fallback, after the
     * strict lookup, and by then the validator has already refused anything a person wrote that it should.
     */
    fun ownerIgnoringVisibility(q: QualName): Export? {
        val doc = documentOf(q) ?: return null
        if (!q.isQualified) return Export(doc, q.name)
        return if (doc.function(q.name) != null) Export(doc, q.name) else null
    }

    private fun found(q: QualName): Export? {
        val doc = documentOf(q) ?: return null
        if (!q.isQualified) return Export(doc, q.name)
        return exports(doc)[q.name]
    }

    fun function(name: String): GraphFunction? = QualName.parse(name).let { q ->
        val e = found(q) ?: return null
        val fn = e.owner.function(e.name) ?: return null
        // Reached through an alias, so its parameter and result types have to be renamed into the
        // importer's vocabulary — see [qualifyThrough]. Against the OWNING document, which for a
        // re-exported function is not the one the alias names.
        if (q.module == null) fn else respell(e.owner, qualifyThrough(q.module, e.owner, fn))
    }

    /**
     * A signature spelled in [from]'s vocabulary, respelled in THIS document's.
     *
     * [qualifyThrough] renames what the OWNER declares. It cannot touch what the owner IMPORTED, and that
     * is the commoner half in practice: a leaf script's `fn run(rumor: rumor::HunterRumor)` names the
     * shared vocabulary through its OWN alias, and handing that function to a registrar produced
     * `cannot wire fn(rumor::HunterRumor) into fn(@2::HunterRumor)` — one type, two documents' words for
     * it, on the arrangement the whole dispatch-table pattern is built out of.
     *
     * So a qualified name is followed: which document does [from] mean by that alias, and what does THIS
     * document call the same document? A type reached through a document we do not import ourselves is
     * left alone — there is nothing here it could be spelled as, and the wire refusal says so.
     */
    private fun respell(from: Graph, fn: GraphFunction): GraphFunction = GraphFunction(
        fn.name,
        fn.params.map { FunctionPin(it.name, respell(from, it.type)) },
        fn.results.map { FunctionPin(it.name, respell(from, it.type)) },
        fn.isExported,
        receiver = fn.receiver?.let { respell(from, it) },
    )

    private fun respell(from: Graph, t: TypeRef): TypeRef {
        val args = t.args.map { respell(from, it) }
        val q = QualName.parse(t.name)
        val module = q.module ?: return t.withArgs(args)
        val reached = closure.resolve(from, module) ?: return t.withArgs(args)
        // Followed to the DECLARER — the document the owner names may be a barrel too. See the twin of this
        // in `Lower.respellFromOwner`.
        val target = exports(reached)[q.name]?.owner ?: reached
        // **A barrel counts, which is the half this used to miss.** Matching only an import whose DIRECT
        // target is the owner's document works while everyone imports the declaring file; the moment the
        // type is reached through a package front door, our import names the BARREL and no direct match
        // exists — so the owner's spelling survived untranslated and produced
        // `cannot wire @4::Loadout into @1::Loadout` on a correct call. Asking what an import OFFERS
        // follows the re-export chain, which is exactly how we reach the type in the first place.
        val ours = here.imports.firstOrNull { imp ->
            if (!imp.bindsLocally) return@firstOrNull false
            val d = closure.resolve(here, imp.alias) ?: return@firstOrNull false
            key(exports(d)[q.name]?.owner ?: d) == key(target)
        } ?: return t.withArgs(args)
        return t.renamedTo("${ours.alias}${QualName.SEP}${q.name}").withArgs(args)
    }

    fun variable(name: String): GraphVariable? = QualName.parse(name).let { q ->
        val e = found(q) ?: return null
        val v = e.owner.variable(e.name) ?: return null
        // Its TYPE is renamed the same way a signature is, and for the same reason. Without it an imported
        // record-typed variable arrived typed in the OTHER document's words — `@default` where this
        // document says `@1::@default` — and reading a field off it was refused as a type mismatch on a
        // wire that was perfectly correct.
        if (q.module == null) v
        else GraphVariable(
            v.name, qualifyThrough(q.module, e.owner, v.type), v.default, v.isExported, v.isImmutable,
        )
    }

    fun struct(name: String?): StructType? = name?.let { n ->
        QualName.parse(n).let { q -> found(q)?.let { e -> e.owner.struct(e.name) } }
    }

    fun enum(name: String?): EnumType? = name?.let { n ->
        QualName.parse(n).let { q -> found(q)?.let { e -> e.owner.enum(e.name) } }
    }

    /**
     * The same lookups ignoring visibility — for DIAGNOSTICS only.
     *
     * "no function named 'banking::secret'" is true and unhelpful when the function is right there and
     * merely unexported. The validator asks this second question to tell the two apart — and under an
     * opt-in surface a missing `export` is the commonest mistake there is, so all four kinds answer it
     * rather than only functions. Getting that message wrong makes the whole feature feel broken.
     */
    fun functionIgnoringVisibility(name: String): GraphFunction? = QualName.parse(name).let { q ->
        documentOf(q)?.function(q.name)
    }

    fun variableIgnoringVisibility(name: String): GraphVariable? = QualName.parse(name).let { q ->
        documentOf(q)?.variable(q.name)
    }

    fun structIgnoringVisibility(name: String?): StructType? = name?.let { n ->
        QualName.parse(n).let { q -> documentOf(q)?.struct(q.name) }
    }

    fun enumIgnoringVisibility(name: String?): EnumType? = name?.let { n ->
        QualName.parse(n).let { q -> documentOf(q)?.enum(q.name) }
    }

    /**
     * Where a name really lives — the public half of [found].
     *
     * For the compiler, which has to reach a BODY. A re-export forwards a name and never a body, so the
     * document an alias names is not necessarily the one to compile against.
     */
    fun owner(q: QualName): Export? = found(q)

    /** Everything an import offers this document, re-exports followed — for the callers that list names. */
    fun offeredBy(imp: GraphImport): Map<String, Export> =
        closure.resolve(here, imp.alias)?.let { exports(it) }.orEmpty()

    /**
     * The globals slot a variable name occupies in this run.
     *
     * The base comes from the **defining** document, so `banking::trips` lands on banking's cell no matter
     * who is reading it, and two documents that each declare a `trips` never collide. Null when the name
     * resolves to nothing, which the validator reports rather than the compiler guessing at.
     */
    fun variableSlot(name: String): Int? {
        val q = QualName.parse(name)
        // The document that DECLARES it, which across a barrel is not the one the alias names. A slot is
        // a position in the owning document's block, so asking the barrel — which declares nothing — found
        // no variable at all.
        val e = found(q) ?: return null
        val i = e.owner.variables.indexOfFirst { it.name == e.name }
        return if (i < 0) null else closure.globalsBase(e.owner) + i
    }

    /**
     * What a TYPE name means here — this document's declarations, then an imported one's, then the host's.
     *
     * The middle step is the "imports go in the middle" line [Graph.type]'s KDoc promised, arriving in the
     * one place that has an import set to consult.
     */
    fun type(name: String?): TypeInfo? {
        val clean = name?.trim() ?: return null
        val q = QualName.parse(clean)
        if (q.isQualified) {
            val doc = closure.resolve(here, q.module!!) ?: return null
            doc.struct(q.name)?.let {
                return TypeInfo(clean, TypeRef.named(clean), describeStruct(it), authorable = false)
            }
            doc.enum(q.name)?.let {
                return TypeInfo(clean, TypeRef.named(clean), describeEnum(it), authorable = true)
            }
            return null
        }
        return here.type(clean)
    }

    /**
     * Every record type nameable from here: this document's, plus each imported document's under its alias.
     *
     * **An imported record's FIELD types are qualified too, and that is not decoration.** If `banking`
     * declares `Account { holder: Person }` and `Person` is also banking's, then an importer reading the
     * field type `Person` verbatim resolves it in its OWN document — finding nothing, or finding a
     * different record that happens to share the name. Neither fails loudly. So a document's types are
     * rewritten on the way out, with every reference to one of its own records qualified by the alias the
     * importer knows it as.
     *
     * Built here rather than in the compiler because the canvas asks the same question when it offers the
     * naming pin's choices, and two implementations of "what types can I see" would drift.
     */
    fun visibleTypes(): List<StructType> = here.types + HostRecords.dataStructs() +
        here.imports.flatMap { imp ->
        // A pure `export … from` binds nothing HERE — its names go past this document — so it contributes
        // no vocabulary to it. See GraphImport.bindsLocally.
        if (!imp.bindsLocally) return@flatMap emptyList()
        offeredBy(imp).mapNotNull { (offered, e) ->
            val s = e.owner.structExactly(e.name) ?: return@mapNotNull null
            StructType(
                "${imp.alias}${QualName.SEP}$offered",
                // **The DEFAULT crosses with the field.** Rebuilding a field from its name and type alone
                // dropped it, and a default is the one property of a field that exists to be used by
                // somebody else's document — a contract grows a field with a default precisely so the
                // literals that other documents already wrote keep compiling unchanged. Losing it here
                // meant they compiled and then read back null, which the type says cannot happen. Same
                // omission, same shape and the same silence as the one [Lower.importedTypes] documents.
                s.fields.map { f ->
                    FunctionPin(f.name, qualifyThrough(imp.alias, e.owner, f.type), default = f.default)
                },
            )
        }
    }

    /**
     * Every enum nameable from here — this document's, plus each imported one's under its alias.
     *
     * Simpler than [visibleTypes] and for a structural reason worth stating: an enum's members are plain
     * names, not types, so there is nothing INSIDE one to qualify. The rewriting that a record's field types
     * need has no analogue here — which is the same asymmetry that made [EnumType] a class of its own.
     *
     * Its own NAME still crosses like any other type, though — a record field or a parameter declared of it
     * is renamed by [qualifyThrough] exactly as a record-typed one is. The two statements are easy to read
     * as one and they are not, which is how enum-typed fields went unqualified.
     */
    /**
     * Every extension nameable from here — this document's, plus each imported document's.
     *
     * **The one kind of name that arrives UNQUALIFIED.** Imports buy their absence of collision rules by
     * qualifying everything (§7b), and an extension cannot be: `xs.add(v)` has nowhere to put an alias. So
     * this is the first list in the language where two documents can genuinely collide, and the caller's job
     * is to refuse an ambiguous call rather than pick — see `Lower.extensionCall`.
     *
     * Paired with the alias so a caller can say WHICH document each came from, both in the error message and
     * in the qualified call that is the way out of one.
     */
    fun visibleExtensions(): List<Pair<String?, GraphFunction>> =
        here.functions.filter { it.isExtension }.map { null to it } +
            here.imports.flatMap { imp ->
                if (!imp.bindsLocally) return@flatMap emptyList()
                offeredBy(imp).mapNotNull { (_, e) ->
                    val fn = e.owner.function(e.name)?.takeIf { it.isExtension } ?: return@mapNotNull null
                    imp.alias to qualifyThrough(imp.alias, e.owner, fn)
                }
            }

    /**
     * Every enum nameable here, imported ones spelled `alias::Name`.
     *
     * **Carrying [EnumType.fields] and [EnumType.values] is the point, not a detail.** This is the list the
     * validator checks an `enum.field` against and the list the compiler bakes a column out of, so a
     * qualified copy built from the name and the members alone left both of them looking at a table-less
     * enum: the validator reported "has no field", and had it not, the compiler would have baked a column
     * of nulls. Renamed, not rebuilt — everything except the name survives the crossing. See the twin of
     * this in `Lower.importedEnums`; the two lists have to agree, because Lower decides the read resolves
     * and these two decide what it means.
     */
    fun visibleEnums(): List<EnumType> = here.enums + here.imports.flatMap { imp ->
        if (!imp.bindsLocally) return@flatMap emptyList()
        offeredBy(imp).mapNotNull { (offered, ex) ->
            val e = ex.owner.enumExactly(ex.name) ?: return@mapNotNull null
            EnumType(
                "${imp.alias}${QualName.SEP}$offered", e.members,
                fields = e.fields, values = e.values,
            )
        }
    }

    companion object {
        /** A document that imports nothing — what every caller predating imports means. */
        fun single(graph: Graph): ImportScope = ImportScope(ImportClosure.single(graph), graph)
    }
}

/** An enum's one-line summary: the names it offers. */
internal fun describeEnum(e: EnumType): String =
    if (e.members.isEmpty()) "a choice with no members yet" else "one of: ${e.members.joinToString(", ")}"

/** A struct's one-line summary, for the pickers: what it holds, written from its own fields. */
internal fun describeStruct(t: StructType): String =
    if (t.fields.isEmpty()) "a record with no fields yet"
    else t.fields.joinToString(", ") { "${it.name}: ${Types.label(it.type)}" }

/** Why an import closure is incomplete, in terms a person can act on. */
sealed class ImportError {
    abstract val message: String

    /**
     * The import this is about, as `import:<alias>`.
     *
     * A handle rather than a position, because the closure works on graphs and has never seen a line.
     * `VsText` turns it into a span through the table `Lower` keeps, which is what puts the squiggle on
     * the import instead of on the first character of the file.
     */
    open val declaration: String? = null

    class Unresolved(val inDoc: Graph, val imp: GraphImport) : ImportError() {
        override val message: String
            get() = "'${inDoc.name}' imports \"${imp.ref}\" as '${imp.alias}', and no document answers to it"
        override val declaration: String get() = "import:${imp.alias}"
    }

    /**
     * The document is there and does not compile.
     *
     * Distinct from [Unresolved] because the cure is different and so is where to look: nothing is wrong
     * with this import, and the file it names is the one to open.
     */
    class Broken(val inDoc: Graph, val imp: GraphImport, val why: String) : ImportError() {
        override val message: String
            get() = "\"${imp.ref}\" has errors of its own — fix them there ($why)"
        override val declaration: String get() = "import:${imp.alias}"
    }

    class DuplicateAlias(val inDoc: Graph, val alias: String) : ImportError() {
        override val message: String
            get() = "'${inDoc.name}' imports two documents as '$alias' — an alias names one thing"
        override val declaration: String get() = "import:$alias"
    }

    class Cycle(val path: List<Graph>) : ImportError() {
        override val message: String
            get() = "import cycle: " + path.joinToString(" -> ") { it.name }
    }
}
