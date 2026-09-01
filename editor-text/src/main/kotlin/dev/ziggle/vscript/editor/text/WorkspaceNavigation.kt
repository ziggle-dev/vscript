package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.text.natives
import java.io.File

/**
 * Go to declaration and find usages **across the workspace**, not just the open document.
 *
 * ### Why this is separate from [Navigation]
 *
 * [Navigation] answers about one resolution — the file in front of you — and that is the answer wanted on
 * every keystroke, so it must stay a lookup over maps that already exist. This asks a question about every
 * document, which means resolving every document, and that is an explicit action a person takes rather
 * than something a hover can afford.
 *
 * ### Usages are matched by DECLARATION, not by name
 *
 * Two documents can each declare a `helper`, and a `helper` in one is not a usage of the other's. So a
 * usage counts only when the name resolves back to the same declaration: the same document reference and
 * the same declared name. Matching on the name alone is the version that looks like it works and quietly
 * reports every unrelated `count` in the corpus.
 *
 * ### It resolves candidates, not everything
 *
 * A document that does not contain the name as TEXT cannot use it, whatever its imports say. Filtering on
 * that first turns "resolve 117 documents" into "resolve the handful that mention it" — cheap enough for a
 * keypress, and exact because the filter can only ever be too generous.
 */
class WorkspaceNavigation(
    private val workspace: Workspace,
    private val catalog: NodeCatalog,
) {

    /** A place in the workspace: which document, which line, and what that line says. */
    class Location(val file: File, val ref: String, val line: Int, val text: String)

    /** Every `.vs` in the workspace, by the reference a document would import it as. */
    private fun documents(): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        for (node in workspace.tree().files()) {
            val ref = workspace.relative(node.file).removeSuffix(".vs")
            out[ref] = node.file
            // `mod.vs` is its folder's front door — `core/loadout/mod` is imported as `core/loadout`.
            if (ref.endsWith("/mod")) out.putIfAbsent(ref.removeSuffix("/mod"), node.file)
        }
        return out
    }

    private fun sourceOver(docs: Map<String, File>): TextSource =
        TextSource { ref -> docs[ref]?.let { f -> runCatching { f.readText() }.getOrNull() } }

    /**
     * Where [ref]'s [name] is declared.
     *
     * The document is resolved rather than scanned, so the answer is the declaration the compiler would
     * use — including when the name is re-exported through a barrel and declared somewhere else again.
     */
    fun declaration(ref: String, name: String): Location? {
        val docs = documents()
        val file = docs[ref] ?: return null
        val src = runCatching { file.readText() }.getOrNull() ?: return null
        val analysis = TextFrontEnd(catalog.natives(), imports = sourceOver(docs), rootRef = ref).analyse(src)
        val resolution = analysis.resolution ?: return null

        val lines = LineIndex.of(src)
        val span = resolution.exports[name]?.span
            ?: Outline.of(resolution.document, lines).flatten().firstOrNull { it.name == name }
                ?.let { return Location(file, ref, it.line, lines.textOf(src, it.line).trim()) }
            ?: return null
        val line = lines.lineAt(span.start)
        return Location(file, ref, line, lines.textOf(src, line).trim())
    }

    /**
     * Every use of [ref]'s [name], anywhere in the workspace, including its declaration.
     *
     * [limit] bounds the work rather than the answer's usefulness: a symbol with four hundred usages is
     * one nobody reads a list of, and resolving four hundred documents to build that list is time the
     * author is sitting through.
     */
    fun usages(ref: String, name: String, limit: Int = 200): List<Location> {
        val docs = documents()
        val source = sourceOver(docs)
        val out = ArrayList<Location>()

        // **The declaration is added explicitly, because it is not a usage.** A function's name in
        // `fn bump(...)` is a declaration, not a `NameExpr`, so asking the document's own navigation about
        // that offset correctly answers nothing. The in-file list includes the declaration and this one
        // should agree — "find usages" that omits the thing being asked about reads as a bug every time.
        declaration(ref, name)?.let { out += it }

        for ((docRef, file) in docs) {
            if (out.size >= limit) break
            val src = runCatching { file.readText() }.getOrNull() ?: continue
            // The cheap filter: a document that never spells the name cannot use it.
            if (name !in src) continue

            val analysis = runCatching {
                TextFrontEnd(catalog.natives(), imports = source, rootRef = docRef).analyse(src)
            }.getOrNull() ?: continue
            val resolution = analysis.resolution ?: continue
            val lines = LineIndex.of(src)
            val nav = Navigation(resolution, analysis.comments, catalog)

            // Ask the document's OWN navigation about each occurrence of the name, so a hit counts only
            // when it resolves to the declaration being asked about.
            var at = src.indexOf(name)
            val seen = HashSet<Int>()
            // The declaration line, if this is the declaring document, is already in.
            out.filter { it.ref == docRef }.forEach { seen.add(it.line) }
            while (at >= 0 && out.size < limit) {
                val target = nav.at(at)
                if (target != null && target.name == name && declares(target, docRef, ref)) {
                    val line = lines.lineAt(at)
                    if (seen.add(line)) {
                        out += Location(file, docRef, line, lines.textOf(src, line).trim())
                    }
                }
                at = src.indexOf(name, at + 1)
            }
        }
        return out.sortedWith(compareBy({ it.ref }, { it.line }))
    }

    /**
     * Does [target], seen in [inDocument], resolve to a declaration in [declaringRef]?
     *
     * A target with no `declarationRef` is declared in the document it was found in — that is what "here"
     * means — so the comparison has to substitute the containing document rather than treat null as a
     * wildcard. Treating it as a wildcard is how every local `count` becomes a usage of an imported one.
     */
    private fun declares(target: Navigation.Target, inDocument: String, declaringRef: String): Boolean =
        (target.declarationRef ?: inDocument) == declaringRef
}
