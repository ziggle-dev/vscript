package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.lang.Span

/**
 * Where an `import` is looked up — source text, by the reference written.
 *
 * Deliberately narrower than the graph side's `GraphSource`, which answers with a `Graph`: a text module
 * is text, and handing back a lowered document would mean the text front end depended on lowering for the
 * one thing it exists to stop depending on.
 */
fun interface TextSource {
    /** The source of the document [ref] names, or null when nothing answers to it. */
    fun load(ref: String): String?

    companion object {
        /** Resolves nothing — the default wherever imports are not expected. */
        val NONE: TextSource = TextSource { null }

        /** Every document in [sources], by the reference it is imported as. */
        fun of(sources: Map<String, String>): TextSource = TextSource { sources[it] }

        /**
         * Several roots as one, asked **in order, first wins** — how a test tree sits beside a source tree.
         *
         * A project has more than one place documents come from: `src/` for the code and `test/` for the
         * documents that exercise it, and later plausibly a vendored library. Each is its own index; this
         * is how they become one lookup without any of them having to know the others exist.
         *
         * **In order and first-wins, stated rather than incidental**, because the alternative is a merge
         * whose result depends on which root happened to be scanned first — the failure `ModuleNames`
         * already refuses for `mod`. Note this is why a test document's ref carries `ModuleNames`'
         * test suffix: two roots holding `scheduler/goal` would otherwise be one name with two answers,
         * and the loser would be shadowed silently.
         */
        fun chain(vararg sources: TextSource): TextSource = TextSource { ref ->
            sources.firstNotNullOfOrNull { it.load(ref) }
        }
    }
}

/** One resolved document, and the reference it was reached by. */
class Module(val ref: String, val resolution: Resolution)

/**
 * The slots a run's globals occupy, shared by every document in it.
 *
 * **One layout across the closure, allocated once.** Every module's variables live in the same array at
 * run time — that is what `GETG`/`SETG` index — so two modules each numbering their own from zero would
 * have the second silently reading and writing the first's. The graph side arrives at the same answer
 * through `ImportClosure`; this is the same fact with nothing graph-shaped attached.
 */
class GlobalSlots {
    private val defaults = ArrayList<Any?>()

    fun allocate(initial: Any?): Int {
        defaults.add(initial)
        return defaults.size - 1
    }

    fun snapshot(): List<Any?> = defaults.toList()
}

/**
 * A document and everything it imports, resolved innermost first.
 *
 * **Leaves before importers**, because an importer needs its callees' signatures to check a call at all —
 * the same order `ImportClosure` visits in, and for the same reason. A document part-way through being
 * resolved is refused rather than waited for: a ring of imports has no innermost, so there is no order
 * that could work, and saying so beats recursing until the stack goes.
 */
class ModuleSet(
    private val source: TextSource,
    private val natives: NativeTable,
    val globals: GlobalSlots = GlobalSlots(),
) {

    private val done = LinkedHashMap<String, Module>()
    private val loading = LinkedHashSet<String>()
    private val failed = LinkedHashMap<String, String>()

    /** Every module resolved so far, importers last. */
    val modules: List<Module> get() = done.values.toList()

    /**
     * A module ALREADY resolved, by the reference it was reached through. Never loads one.
     *
     * How a type is looked up once it carries its owner: `TypeRef.owner` names the declaring module, and
     * the question "what are this type's fields" is then asked of that module directly rather than of
     * whatever the *reader* happened to import. A reader three documents away from the declaration has no
     * spelling for it and never needs one.
     *
     * Deliberately non-loading. Resolution order is leaves-first, so by the time any type of a module's is
     * in play that module is in [done]; a version that could load would be able to re-enter a module part
     * way through resolving, which is the recursion [module] refuses on purpose.
     */
    fun byRef(ref: String): Module? = done[ref]

    /** Resolve [text] as the ROOT — the document being run, which nothing imports. */
    fun root(text: String): Resolution = resolve(Resolution.ROOT, text)

    /**
     * The module [ref] names, resolved.
     *
     * Null when nothing answers to the reference or the module could not be read; [problem] says which,
     * so the importer can report one honest line instead of a page of unknown names.
     */
    fun module(ref: String): Module? {
        done[ref]?.let { return it }
        if (ref in failed) return null
        if (!loading.add(ref)) {
            failed[ref] = "'$ref' imports itself, directly or through something it imports"
            return null
        }
        return try {
            val text = source.load(ref)
            if (text == null) {
                failed[ref] = "nothing answers to '$ref'"
                return null
            }
            val resolution = resolve(ref, text)
            // **A module with problems of its own is not a module you may run.** The shape is still worth
            // having — an importer needs the signatures to check its own calls — but handing it over
            // silently means compiling against a library that does not compile, whose bodies were never
            // emitted. So it is remembered as broken, and the importer reports ONE line rather than a page
            // of complaints about names the failure took with it.
            if (!resolution.ok && ref !in failed) {
                // **Say WHAT is wrong, not how much.** "has 3 error(s) of its own" sends the reader to
                // open the file and find them; the first one, with its line, usually IS the answer — and
                // when it is not, it is still the place to start.
                val first = resolution.errors.first()
                val more = if (resolution.errors.size > 1) " (and ${resolution.errors.size - 1} more)" else ""
                // Spelled the way the import line spells it, and worded the way the graph front end words
                // it — the same fact reported by two compilers should read as one sentence, not two.
                failed[ref] = "\"$ref\" has errors of its own: ${first.span} ${first.message}$more"
            }
            Module(ref, resolution).also { done[ref] = it }
        } finally {
            loading.remove(ref)
        }
    }

    fun problem(ref: String): String? = failed[ref]

    private fun resolve(ref: String, text: String): Resolution {
        // **The lexer throws rather than collecting** — see `TextFrontEnd.lex`. Uncaught here it escapes
        // the whole compilation, so one stray character in a library takes down the file that imports it
        // and reports nothing about either.
        val tokens = try {
            Lexer(text).lex()
        } catch (e: dev.ziggle.vscript.lang.VsSyntaxError) {
            failed[ref] = "\"$ref\" does not lex: ${e.span} ${e.message}"
            return empty(ref)
        }
        val parsed = Parser(tokens, text).parse()
        if (!parsed.ok) {
            val first = parsed.errors.first()
            val more = if (parsed.errors.size > 1) " (and ${parsed.errors.size - 1} more)" else ""
            failed[ref] = "\"$ref\" does not parse: ${first.span} ${first.message}$more"
            return empty(ref)
        }
        return Resolver(natives, this, globals, ref).resolve(parsed.program)
    }

    /**
     * A shape with nothing in it, for a document that could not be read.
     *
     * So the importer's own names still resolve as far as they can and the ONE error it gets is the import
     * line, rather than a complaint at every use of every name the broken library was going to provide.
     */
    private fun empty(ref: String): Resolution =
        Resolver(natives, this, globals, ref)
            .resolve(Parser(Lexer("graph \"$ref\"").lex()).parse().program)

    private companion object {
        val NOWHERE = Span.NONE
    }
}

/**
 * What one document's `import` lines make visible, and under what name.
 *
 * Three shapes, because the language has three and they mean different things:
 *
 *  - **aliased** (`import * as rand from "core/random"`) — reachable only as `rand::name`, so nothing can
 *    collide and no ordering rule is needed;
 *  - **unqualified** (`import "core/activity"`) — every exported name visible as itself, which is what
 *    makes an extension usable without asking for it by name;
 *  - **named** (`import { a, b as c } from "x"`) — those names, under those local names.
 */
class ImportedNames(
    val aliased: Map<String, Module> = emptyMap(),
    val unqualified: List<Module> = emptyList(),
    val named: Map<String, Pair<Module, String>> = emptyMap(),
) {
    companion object {
        val NONE = ImportedNames()
    }
}
