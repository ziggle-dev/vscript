package dev.ziggle.vscript.tools

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.ModuleNames
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.TextSource
import java.io.File

/**
 * How a host tells a build tool what its nodes are.
 *
 * The language ships builtins and nothing else — every game verb, every draw verb, every domain function
 * belongs to whoever embeds it. A tool that compiles documents therefore cannot know the catalogue; it has
 * to be handed one, and this is the handle. An implementation is a class with a no-argument constructor,
 * named to the tool by its fully-qualified name and loaded off the tool's own classpath.
 *
 * A host that already assembles a catalogue for its editor should return **that same one**. Two assemblies
 * that could disagree mean a script the build accepts and the client rejects, which is the one failure a
 * build-time check exists to prevent.
 */
interface CatalogProvider {
    fun catalog(): NodeCatalog
}

/**
 * The documents under a folder, as a [TextSource] — the import root a build tool compiles against.
 *
 * **The naming rules are [ModuleNames]', not this class's**, which is the whole reason this is worth having
 * rather than a map from relative path to file. A document answers to more references than its path:
 *
 *  - its path (`core/pace`),
 *  - the name it DECLARES (`graph "pace"`) — imports resolve by that, so renaming a file changes nothing,
 *  - and its folder, when it is the folder's front door (`core/items/mod.vs` answers to `core/items`).
 *
 * A naive path map has none of the last two. Miss the front door and every import of a package fails to
 * resolve, so a whole tree reports as broken when nothing is — which is exactly how an offline checker
 * comes to cry wolf and get ignored.
 *
 * The two-pass order is [DocumentSource]'s and is deliberate: barrels go on SECOND, so an explicit
 * `core/loadout.vs` sitting beside a `core/loadout/` folder keeps the reference and the folder does not
 * take it out from under a file that spells it exactly. In one pass the winner would be whichever the walk
 * reached first — alphabetical order, and therefore an answer nobody chose.
 */
class FolderSource(
    /** The import ROOT(s) — references resolve against these. First root wins on a collision. */
    private val roots: List<File>,
    /** Extra documents overlaid by reference, winning over anything on disk. See [stub]. */
    private val extra: Map<String, String> = emptyMap(),
) : TextSource {

    constructor(root: File, extra: Map<String, String> = emptyMap()) : this(listOf(root), extra)

    /** Every reference this source answers to. */
    val refs: Set<String> get() = extra.keys + index.keys

    /** Which file a reference resolves to — for a tool that wants to report a path. */
    fun fileOf(ref: String): File? = index[ref]

    private val index: Map<String, File> by lazy {
        val names = LinkedHashMap<String, File>()
        val barrels = ArrayList<Pair<String, File>>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "vs" }
                // Sorted, so an index built twice is the same index and a build is reproducible.
                .sortedBy { it.invariantSeparatorsPath }
                .forEach { f ->
                    val path = f.relativeTo(root).invariantSeparatorsPath.removeSuffix(".vs")
                    for (name in ModuleNames.namesOf(path, declaredName(f))) names.putIfAbsent(name, f)
                    ModuleNames.barrelName(path)?.let { barrels += it to f }
                }
        }
        for ((name, f) in barrels) names.putIfAbsent(name, f)
        names
    }

    override fun load(ref: String): String? =
        extra[ref] ?: index[ref]?.let { runCatching { it.readText() }.getOrNull() }

    /**
     * What a document calls itself, or null when it does not say — or cannot be parsed.
     *
     * A half-written file keeps its path-based names and any import that actually points at it reports
     * itself unresolved, which is the honest answer and not a reason to fail the whole index.
     */
    private fun declaredName(f: File): String? = runCatching {
        Parser(Lexer(f.readText()).lex()).parse().program.name
    }.getOrNull()

    companion object {
        /**
         * An empty document, for a reference nothing answers to.
         *
         * A corpus can carry a dead import that no file resolves, and every document importing it then
         * fails for a reason that has nothing to do with the document. Stubbing one turns "forty files are
         * broken" back into "these are the real errors", which is what a checker is for. Use it knowingly
         * and narrowly: a stub hides a genuinely missing file exactly as well as a dead one.
         */
        fun stub(ref: String): Pair<String, String> = ref to ""
    }
}
