package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Parser
import java.io.File

/**
 * The documents on disk: a tree to browse, and an index to search.
 *
 * ### One class, because they are one question
 *
 * A file tree and a "search everywhere" box are the same set of documents shown two ways. Splitting them
 * means walking the directory twice and, worse, being able to disagree — a file the tree shows and the
 * search cannot find is a bug nobody reports because each half looks right on its own.
 *
 * ### Parsed, not resolved
 *
 * The index needs names and lines, which the parser gives. Resolving every document to build a search box
 * would mean loading each one's imports, and would fail entirely for any file that does not compile — so
 * a workspace with one broken script would lose every symbol in it. [Outline] is built from the AST for
 * exactly this reason, and this reuses it.
 *
 * ### Cached on the file's own stamp
 *
 * Re-reading and re-parsing 105 documents on every keystroke of a search box is not affordable. The cache
 * key is `(length, lastModified)`, which is what changes when a file is edited by anything — this editor,
 * another editor, a `git checkout`. A content hash would be exact and would cost a full read to decide
 * whether to read.
 */
class Workspace(val root: File) {

    /** A directory or a `.vs` file. Directories carry their contents; files do not. */
    class Node(
        val name: String,
        val file: File,
        val isDirectory: Boolean,
        val children: List<Node> = emptyList(),
    ) {
        /** Every file at or under this node, depth first — the order the tree draws them in. */
        fun files(): List<Node> =
            if (!isDirectory) listOf(this) else children.flatMap { it.files() }
    }

    /** One searchable thing: a document, or a symbol inside one. */
    class Entry(
        val name: String,
        val file: File,
        /** 1-based; 0 for a document itself, which opens at the top. */
        val line: Int,
        val kind: Outline.Kind?,
        val detail: String = "",
    ) {
        val isFile: Boolean get() = kind == null
    }

    /** A hit and how well it matched, so a caller can show the best first. */
    class Hit(val entry: Entry, val score: Int, val matched: List<Int>)

    // ---- the tree ---------------------------------------------------------------------------------

    /**
     * The `.vs` files under [root], as a tree.
     *
     * **Directories with nothing in them are dropped.** A script tree is mostly folders, and one that
     * shows every empty `build/` and `.git/` on the way down buries the four files somebody wants.
     */
    fun tree(): Node = build(root) ?: Node(root.name, root, isDirectory = true)

    private fun build(dir: File): Node? {
        val entries = dir.listFiles()?.sortedWith(
            // Folders first, then files, each alphabetically — the order every file tree uses, because it
            // makes the shape of a directory readable at a glance rather than interleaved.
            compareBy({ !it.isDirectory }, { it.name.lowercase() }),
        ) ?: return null

        val children = ArrayList<Node>()
        for (e in entries) {
            if (e.isDirectory) {
                if (e.name.startsWith(".") || e.name == "build" || e.name == "out") continue
                build(e)?.takeIf { it.children.isNotEmpty() }?.let { children += it }
            } else if (e.extension.equals("vs", ignoreCase = true)) {
                children += Node(e.name, e, isDirectory = false)
            }
        }
        return Node(dir.name, dir, isDirectory = true, children = children)
    }

    // ---- the index --------------------------------------------------------------------------------

    private class Cached(val length: Long, val modified: Long, val entries: List<Entry>)

    private val cache = HashMap<String, Cached>()

    /** Every document and every symbol in it. Cheap after the first call; see the class note. */
    fun index(): List<Entry> {
        val out = ArrayList<Entry>()
        for (node in tree().files()) {
            val f = node.file
            val key = f.path
            val hit = cache[key]
            if (hit != null && hit.length == f.length() && hit.modified == f.lastModified()) {
                out += hit.entries
                continue
            }
            val entries = read(f)
            cache[key] = Cached(f.length(), f.lastModified(), entries)
            out += entries
        }
        return out
    }

    private fun read(f: File): List<Entry> {
        val out = ArrayList<Entry>()
        out += Entry(f.nameWithoutExtension, f, line = 0, kind = null, detail = relative(f))
        val src = runCatching { f.readText() }.getOrNull() ?: return out
        val program = runCatching { Parser(Lexer(src).lex(), src).parse().program }.getOrNull() ?: return out
        for (s in Outline.of(program, LineIndex.of(src)).flatten()) {
            out += Entry(s.name, f, s.line, s.kind, s.detail)
        }
        return out
    }

    /** [f] as written relative to the workspace, for showing beside a hit. */
    fun relative(f: File): String =
        runCatching { root.toPath().relativize(f.toPath()).toString().replace('\\', '/') }
            .getOrDefault(f.name)

    // ---- search -----------------------------------------------------------------------------------

    /**
     * The best [limit] matches for [query], best first.
     *
     * Empty query returns nothing rather than everything: a search box that dumps 1,400 symbols the moment
     * it opens has answered a question nobody asked, and the list it shows is the one thing that cannot be
     * scrolled to anything useful.
     */
    fun search(query: String, limit: Int = 40): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (e in index()) {
            val m = Fuzzy.match(e.name, q) ?: continue
            hits += Hit(e, m.score, m.positions)
        }
        // Score first; then files above symbols, so typing a document's name finds the document rather
        // than a function inside it that happens to share the name; then alphabetical, so the order is
        // stable between keystrokes rather than shuffling on ties.
        hits.sortWith(
            compareByDescending<Hit> { it.score }
                .thenByDescending { it.entry.isFile }
                .thenBy { it.entry.name.lowercase() },
        )
        return if (hits.size > limit) hits.subList(0, limit) else hits
    }
}
