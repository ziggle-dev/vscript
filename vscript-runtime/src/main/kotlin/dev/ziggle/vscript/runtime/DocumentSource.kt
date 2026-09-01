package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphImport
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.ModuleNames
import dev.ziggle.vscript.model.NodeCatalog
import java.io.File

/**
 * The scripts folder, as somewhere `import` can resolve to.
 *
 * **Nothing here runs unless a document actually declares an import.** `ImportClosure` short-circuits on an
 * empty import list, so [load] is never called for the overwhelming majority of graphs — which is what
 * makes it safe to hang a directory scan off a path (`ScriptRuntime.validate`) that runs on every edit.
 *
 * Both document forms resolve. A `.json` is the saved document and is what the editor writes; a `.vs` is a
 * source file, and refusing to import one would mean a library authored in text could only be used after
 * being opened and saved once, which is a rule nobody would guess.
 *
 * **A document is named by its FOLDER plus its `graph` line**, and a file called `mod.vs` is named by its
 * folder alone. The rule itself is [ModuleNames] — it used to live here AND in the IntelliJ plugin, kept
 * in step by a comment asking the next person to keep them in step, which it twice was not. This class
 * decides where to look and what a document calls itself; what those two answers add up to is the
 * language's business, not the client's.
 *
 * Scripts grow into folders, and a flat listing used to make a nested import unresolvable while looking,
 * from the author's side, exactly like a typo. The unqualified forms still resolve as fallbacks — a bare
 * name for a flat folder, the file's own path for a document that never named itself.
 *
 * **Imports see what is ON DISK, not what is open in the editor.** A library being edited in another tab
 * therefore does not change what an importer compiles against until it is saved. That is the predictable
 * reading — the alternative is a script whose behaviour depends on which tabs happen to be open — but it
 * does mean "I changed the library and nothing happened" has an answer, and the answer is Save.
 */
class DocumentSource(
    private val catalog: NodeCatalog,
    /**
     * Every folder tree an import may resolve against.
     *
     * More than one, because a script does not have to live in the client's own graphs folder. The IDE
     * runs a file straight off disk — `graph import C:\proj\scripts\tour.vs` — and that document's
     * libraries sit BESIDE it, not under `~/.ziggle/graphs`. Searching only the client's folder meant a
     * project that imported anything simply would not run, with the failure reported as an unresolved
     * import rather than as the missing search path it was.
     *
     * Each root qualifies its own documents, so `util/banks` means the same thing whichever tree it came
     * from.
     */
    private val roots: () -> List<File> = { listOf(EditorDoc.graphsDir()) },
) : GraphSource {

    private var stamp: String? = null

    /**
     * Every name a document answers to → the FILE holding it, built without reading any of them.
     *
     * **The index has to exist before anything is lowered.** It used to be filled as each document was
     * lowered, in directory order — so a library that itself imports another was lowered while the index
     * was still empty, its own imports resolved to nothing, and it came back broken. One level of import
     * worked by luck (a leaf library needs no index) and a chain never worked at all: `main` → `vector` →
     * `math` failed on `vector`, and said so in terms of `main`.
     *
     * Naming a document needs only its `graph` line, which is a parse and not a lowering — no imports are
     * consulted to answer it. So the whole index is built first and the documents are read on demand.
     */
    private var byName: Map<String, File> = emptyMap()
    private var byId: Map<String, File> = emptyMap()

    /**
     * Absolute path → the reference that document is imported BY — the first name it answers to.
     *
     * The index read backwards, and it exists because the compiler needs it. A document's identity in the
     * chunk table and in a debugger frame is its reference (`TextCompiler.docRef`), and the ROOT of a run
     * arrives as bare text with no reference at all — so the caller that opened the file is the only one
     * that can say what it is called. See [refOf].
     */
    private var refByPath: Map<String, String> = emptyMap()

    /** Absolute path → the document, once something has actually asked for it. Cleared on rescan. */
    private val graphs = HashMap<String, Graph?>()

    /** Absolute path → that document's own first error, for [problem]. */

    /**
     * Documents part-way through being read, so a `.vs` library that imports another cannot recurse
     * forever.
     *
     * The closure's own cycle check cannot help here: that one runs over already-loaded documents, and
     * this is the loading itself. A file reached while it is still being read answers null, which the
     * closure then reports as an unresolved import — the right shape of complaint for a ring of files.
     */
    private val loading = HashSet<String>()

    override fun load(imp: GraphImport): Graph? = fileFor(imp)?.let { graphOf(it) }

    /**
     * Why the document [imp] names cannot be trusted — its own first error, or null when it is clean.
     *
     * [load] hands back a document's SHAPE even when it has errors, so an importer can still see what its
     * functions take; without that, one mistake in a library made every call through it report
     * "'sqrt' takes 0 value(s)" and hid the real problem entirely. This is the half that stops the shape
     * being mistaken for soundness: the closure turns it into one error on the import line, and the run is
     * refused there rather than proceeding into a body whose failed statements were never lowered.
     */
    /**
     * Why an import did not resolve — now always null, and honestly so.
     *
     * It used to report a `.vs` that lowered with errors: readable enough to shape a call, broken enough
     * that running it would be wrong. That state is gone with the lowering. A `.json` either reads or it
     * does not, and "does not" is an unresolved import, which the closure already says.
     */
    override fun problem(imp: GraphImport): String? = null

    /**
     * The same folders, as somewhere a TEXT `import` can resolve to.
     *
     * The text front end wants source, not a lowered document — that is the one thing it exists to stop
     * depending on — so it gets the file's text through the index this class already builds. The naming
     * rule (folder plus the `graph` line), the rescan policy and the search roots are shared, which is
     * what stops `import "core/pace"` meaning two different files depending on who asked.
     *
     * **A `.json` canvas document does not answer.** Importing one from text is a cross-surface call, and
     * that is deliberately not built yet; the reference comes back unresolved rather than half-working.
     */
    fun asTextSource(): dev.ziggle.vscript.text.TextSource =
        dev.ziggle.vscript.text.TextSource { ref -> sourceOf(ref) }

    /** The source of the document [ref] names, or null when nothing under the roots answers to it. */
    fun sourceOf(ref: String): String? {
        val f = fileOf(ref) ?: return null
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Which FILE a text reference names — for a debugger, which has a site and needs somewhere to put the
     * caret.
     *
     * The same index `sourceOf` reads, so "the file this import resolved to" and "the file this frame is
     * in" cannot come apart. A `.json` does not answer: importing a canvas document from text is a
     * cross-surface call and is deliberately not built.
     */
    fun fileOf(ref: String): File? {
        refresh()
        return byName[ref]?.takeIf { it.name.endsWith(".vs") }
    }

    /**
     * What [file] is called — the reference an `import` would name it by.
     *
     * For the caller that is about to compile it as the ROOT of a run: the root has no reference of its
     * own, and left unnamed every root in a shared program is `<root>` and collides with every other. The
     * answer comes from the same index `import` reads, so "the name this file is imported by" and "the
     * name its stack frames carry" cannot come apart.
     */
    fun refOf(file: File): String? {
        refresh()
        return refByPath[file.absolutePath]
    }

    /** Which file a reference names — by document id first, then by any of its names. */
    private fun fileFor(imp: GraphImport): File? {
        refresh()
        return imp.docId?.takeIf { it.isNotBlank() }?.let { byId[it] } ?: byName[imp.ref]
    }

    /** The document in [f], read once and remembered. */
    private fun graphOf(f: File): Graph? {
        val key = f.absolutePath
        if (graphs.containsKey(key)) return graphs[key]
        if (!loading.add(key)) return null
        return try {
            read(f).also { graphs[key] = it }
        } finally {
            loading.remove(key)
        }
    }

    /** Re-read the folders when their listing or any timestamp has changed, and not otherwise. */
    private fun refresh() {
        // Distinct, because the IDE's project may legitimately BE the graphs folder and scanning it twice
        // would make every document shadow itself.
        val allRoots = roots().distinctBy { it.absolutePath }
        val found = allRoots.flatMap { root -> scriptsUnder(root).map { root to it } }
        val now = found.joinToString("|") { (root, f) -> "${relativeName(root, f)}:${f.lastModified()}" }
        if (now == stamp) return
        stamp = now
        graphs.clear()

        val ids = LinkedHashMap<String, File>()
        val names = LinkedHashMap<String, File>()
        val barrels = ArrayList<Pair<String, File>>()
        for ((root, f) in found) {
            val path = relativeName(root, f)
            val declared = declaredName(f)
            declared?.id?.takeIf { it.isNotBlank() }?.let { ids.putIfAbsent(it, f) }
            // Which references this document answers to is [ModuleNames]'s question, not this class's —
            // see the note on the class. All this knows is where the file is and what it calls itself.
            for (name in ModuleNames.namesOf(path, declared?.name)) names.putIfAbsent(name, f)
            ModuleNames.barrelName(path)?.let { barrels += it to f }
        }
        // **The barrel names go on in a SECOND pass**, so an explicit `core/loadout.vs` beside the folder
        // keeps the reference `core/loadout` and a `core/loadout/mod.vs` does not take it out from under a
        // file that spells it exactly. In one pass the winner would be whichever the directory walk
        // reached first — alphabetical order, and therefore an answer nobody chose.
        for ((name, f) in barrels) names.putIfAbsent(name, f)
        byId = ids
        byName = names
        // The index backwards — the name a document is KNOWN by, which for a `mod.vs` is its folder and
        // for everything else is the most specific name it answers to.
        val refs = LinkedHashMap<String, String>()
        for ((root, f) in found) {
            val path = relativeName(root, f)
            val primary = ModuleNames.barrelName(path)
                ?: ModuleNames.namesOf(path, declaredName(f)?.name).firstOrNull()
                ?: path
            refs.putIfAbsent(f.absolutePath, primary)
        }
        refByPath = refs
    }

    /** What a document calls itself, and its id when it has one. */
    private class Declared(val name: String, val id: String?)

    /**
     * A document's own name, WITHOUT lowering it.
     *
     * For a `.vs` that is its `graph` line, which the parser can answer on its own — no imports are
     * consulted, which is the whole point: this runs while the index that resolves imports is still being
     * built. A saved `.json` is already a document and simply says.
     */
    private fun declaredName(f: File): Declared? = try {
        if (f.name.endsWith(".vs")) {
            val parsed = dev.ziggle.vscript.lang.Parser(dev.ziggle.vscript.lang.Lexer(f.readText()).lex()).parse()
            parsed.program.name?.let { Declared(it, null) }
        } else {
            // Cached as it is read: a `.json` needs no lazy loading, since reading one resolves no imports.
            GraphDoc.read(f)?.also { graphs[f.absolutePath] = it }?.let { Declared(it.name, it.id) }
        }
    } catch (e: Exception) {
        // Half-written, or a format this client does not know. It keeps its path-based names and any
        // import that actually points at it reports itself unresolved, which is the honest answer.
        null
    }

    /**
     * Every document under [root], nested folders included.
     *
     * Scripts grow into folders — a `util/` of helpers, a folder per activity — and a flat listing made
     * `import banks from "util/banks"` unresolvable while looking, from the author's side, exactly like a
     * typo. Depth is capped and symlinks are not followed, because this runs off `ScriptRuntime.validate`
     * on every edit and a cycle there would hang the client thread rather than fail.
     */
    private fun scriptsUnder(root: File): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            val entries = dir.listFiles() ?: return
            for (f in entries.sortedBy { it.name }) {
                when {
                    f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".vs")) -> out += f
                    f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath()) -> walk(f, depth + 1)
                }
            }
        }
        walk(root, 0)
        return out
    }

    /** `util/banks` — the path below the scripts folder, extension dropped, always forward slashes. */
    private fun relativeName(root: File, file: File): String {
        val rel = file.relativeToOrNull(root)?.path ?: file.name
        return rel.replace(File.separatorChar, '/').substringBeforeLast('.')
    }

    /**
     * The document itself. The `loading` guard lives in [graphOf], which is the only caller.
     */
    /**
     * A graph document, or null.
     *
     * **`.json` only.** This used to lower a neighbouring `.vs` to a graph so a canvas document could
     * import one, which is a cross-surface call: the two authoring surfaces are separate and do not
     * convert into each other. A canvas imports canvas documents; a `.vs` imports `.vs` through
     * [asTextSource], which reads the file's SOURCE and is what the text front end wanted all along.
     */
    private fun read(f: File): Graph? = try {
        if (f.name.endsWith(".vs")) null else GraphDoc.read(f)
    } catch (e: Exception) {
        // A folder is allowed to contain a document this client cannot read — a newer format, or
        // something half-written. Refusing to resolve ANY import because one neighbour is broken would
        // be a worse failure than the one import that actually names it coming back unresolved.
        null
    }

    private companion object {
        /**
         * How deep a scripts folder may nest before this stops looking.
         *
         * A limit rather than none: this runs off a path that fires on every edit, so an accidentally
         * deep tree should cost a bounded scan rather than the client thread.
         */
        const val MAX_DEPTH = 8
    }
}
