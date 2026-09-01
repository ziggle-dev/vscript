package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.Span

/**
 * Authoring sites — the identity a breakpoint, a stack frame and a runtime error all point back at.
 *
 * The VM addresses everything debuggable by an opaque int (`Chunk.nodeIds`, `Breakpoints`, `TRACE`,
 * `graph break <n>` over the socket), and `Ast.kt` says the text surface's whole debugging story is the
 * side table mapping that int to a source span. This is that table. Nothing here knows what a node is; the
 * ints are the same ints the graph hands out and the runtime cannot tell which front end minted one.
 *
 * **One counter for the whole compilation, not one per document.** [dev.ziggle.vscript.vm.Breakpoints] is a
 * flat set of ids with no chunk beside them, and a graph node id is only unique *within* a document (see
 * `model/Imports.kt`) — so on that side a breakpoint armed in a library can in principle fire in the script
 * that imported it. Numbering the closure once costs nothing and that whole family cannot happen here.
 *
 * **Keyed by AST identity, not by span.** Two `pace::beat()` calls on one line are two sites, and a macro
 * of a language that gets one later would make that common rather than rare.
 */
class Sites {

    private val ids = HashMap<IdentityKey, Int>()
    private val spans = HashMap<Int, Span>()
    private val documents = HashMap<Int, String>()
    private var next = 1

    /** How many sites have been handed out. */
    val size: Int get() = ids.size

    /**
     * The id for [node], minting one on first sight.
     *
     * [document] is the reference the source was reached by, so a debugger can answer "which file" without
     * a second table — the chunk name says which document a *frame* is in, but an inlined body's sites
     * belong to the document that wrote them.
     */
    fun idOf(node: Any, span: Span, document: String): Int {
        val key = IdentityKey(node)
        ids[key]?.let { return it }
        val id = next++
        ids[key] = id
        spans[id] = span
        documents[id] = document
        return id
    }

    /** Where site [id] was written, or null when nothing was minted for it. */
    fun spanOf(id: Int): Span? = spans[id]

    /** Which document site [id] was written in. */
    fun documentOf(id: Int): String? = documents[id]

    /** Every site, id to span — what a debugger loads to turn "line 42" into a breakpoint. */
    fun spans(): Map<Int, Span> = spans.toMap()

    /**
     * The site to break on for [line] in [document] — the first one that starts on it.
     *
     * "Break on line 42" is a line, and a line holds several sites; the earliest is the one the user
     * pointed at. Null when the line has no code on it, which is a breakpoint the editor should refuse
     * rather than silently move.
     */
    fun siteAt(document: String, line: Int): Int? =
        spans.entries
            .filter { documents[it.key] == document && it.value.line == line }
            .minByOrNull { it.value.start }
            ?.key

    /**
     * Identity, not equality.
     *
     * AST nodes are ordinary classes and two structurally identical expressions — `x + 1` written twice —
     * are equal to each other under any `equals` a data class would generate. They are still two places in
     * the file, and a breakpoint on one must not arm the other.
     */
    private class IdentityKey(val node: Any) {
        override fun hashCode(): Int = System.identityHashCode(node)
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.node === node
    }
}
