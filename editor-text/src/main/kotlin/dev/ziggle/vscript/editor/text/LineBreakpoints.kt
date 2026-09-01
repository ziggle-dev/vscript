package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.Span

/**
 * Breakpoints on a `.vs`, kept as **lines** and resolved to sites at run time.
 *
 * ### Why a line and not a site id
 *
 * The VM breaks on an authoring id, and for a text run that id comes from the front end's `Sites` table.
 * Storing one of those would be the obvious thing and is wrong twice over. `Sites.idOf` keys on AST
 * identity and hands out `next++`, so **compiling the same file twice mints two sets of ids** — the
 * runtime's own note says as much — and a breakpoint armed against one set while the other runs is a
 * breakpoint that silently never fires. A site id is also meaningless to the author: what they clicked was
 * a line.
 *
 * So a line is what is stored, what is drawn, and what survives a reload. The site is looked up against
 * whichever compilation is actually about to run, which is the only moment the mapping is true.
 *
 * ### A line that cannot hold one says so
 *
 * Not every line has a site. A blank line, a comment, a closing brace and a `var` declaration all compile
 * to nothing that can be stopped at. The old canvas gutter could not have this problem — a node either
 * existed or did not — and the code view's own note is emphatic that *"offering a breakpoint that lands
 * somewhere else is worse than offering none"*. [armable] is how the gutter answers that: ask before
 * drawing, and a click on a line with no site is refused rather than accepted and quietly dropped.
 *
 * ### They follow the code
 *
 * [follow] moves them when the text above them changes, which is what every editor does and what makes a
 * breakpoint feel attached to a statement rather than to a number. See its note for the three cases.
 */
class LineBreakpoints {

    private val armed = sortedSetOf<Int>()

    /** The armed lines, ascending. */
    val lines: Set<Int> get() = armed

    val isEmpty: Boolean get() = armed.isEmpty()

    operator fun contains(line: Int): Boolean = line in armed

    /** Arm or disarm [line]. Returns true when it is now armed. */
    fun toggle(line: Int): Boolean =
        if (!armed.remove(line)) { armed.add(line); true } else false

    /** State it, rather than flip it — for a caller that cannot see the gutter. */
    fun set(line: Int, on: Boolean) {
        if (on) armed.add(line) else armed.remove(line)
    }

    fun clear() = armed.clear()

    /** Replace the set wholesale — for restoring a sidecar. */
    fun restore(lines: Collection<Int>) {
        armed.clear()
        armed.addAll(lines.filter { it > 0 })
    }

    // ---- following the text -----------------------------------------------------------------------

    /**
     * Move the armed lines to account for [change], an edit from [before] to [after].
     *
     * ### It counts NEWLINES consumed, not line numbers at the region's ends
     *
     * The obvious version — `lineAt(change.oldEnd) - lineAt(change.newEnd)` — is wrong, and wrong in a way
     * that looks right until you delete something. Deleting the whole of line 2 replaces the offsets
     * `[startOf(2), startOf(3))`, so `oldEnd` lands exactly on the *start of line 3*: the arithmetic then
     * reports line 3 as the last line touched and drops the breakpoint on `c` while keeping the one on the
     * `b` that actually went. What was removed is a run of newlines, so that is what gets counted.
     *
     * ### Whether the first line survives is a question about the COLUMN
     *
     * An edit beginning at the very start of line 5 that removes a newline removes line 5 — its text is
     * gone and what followed moves up into its place. An edit beginning in the middle of line 5 leaves
     * line 5 there, as its first half, and consumes what came after. Same newline count, different
     * outcome, and the only thing separating them is whether the edit starts at the line's first column.
     *
     * A breakpoint inside the removed run is **dropped**, not slid to the top of it. The statement the
     * author attached it to is gone; landing it on whatever moved up into that space would fire on
     * something they never marked, which is the failure this whole design is arranged to avoid.
     */
    fun follow(change: TextChange, before: LineIndex, after: LineIndex) {
        if (change.isEmpty || armed.isEmpty()) return

        val startLine = before.lineAt(change.start)
        val atLineStart = change.start == before.startOf(startLine)
        val removedLines = change.removedNewlines(before)
        val delta = change.insertedNewlines(after) - removedLines
        if (removedLines == 0 && delta == 0) return

        // Lines wholly consumed, and the first line that survives to be shifted.
        val firstGone = if (atLineStart) startLine else startLine + 1
        val shiftFrom = firstGone + removedLines

        val moved = sortedSetOf<Int>()
        for (line in armed) {
            when {
                line >= shiftFrom -> moved.add(line + delta)
                line >= firstGone -> Unit // inside the removed run -- gone with the code it was on
                else -> moved.add(line)
            }
        }
        armed.clear()
        armed.addAll(moved.filter { it > 0 })
    }

    // ---- the mapping ------------------------------------------------------------------------------

    /**
     * Which lines [spans] can actually stop on — `ScriptRuntime.textSpans`, site id → span.
     *
     * The gutter asks this to decide what is clickable. Cheap enough to ask per repaint, but it changes
     * only when a compilation does, so a caller with somewhere to put it should keep it.
     */
    fun armable(spans: Map<Int, Span>): Set<Int> =
        spans.values.mapTo(sortedSetOf()) { it.line }

    /**
     * The armed lines as site ids, against [spans].
     *
     * **The FIRST site on the line wins**, by source offset. A line like `log(a()); log(b())` has several,
     * and stopping at the earliest is what "break here" means to someone reading left to right — stopping
     * at whichever the compiler happened to number lowest would be arbitrary, and stopping at all of them
     * would break several times on one line.
     *
     * A line with no site is dropped. It cannot be armed through [armable], but a sidecar restored from a
     * file that has since been edited can hold one, and that must not throw.
     */
    fun resolve(spans: Map<Int, Span>): Map<Int, Int> {
        if (armed.isEmpty() || spans.isEmpty()) return emptyMap()
        val firstOnLine = HashMap<Int, Pair<Int, Int>>() // line -> (offset, site)
        for ((site, span) in spans) {
            val best = firstOnLine[span.line]
            if (best == null || span.start < best.first) firstOnLine[span.line] = span.start to site
        }
        val out = LinkedHashMap<Int, Int>()
        for (line in armed) firstOnLine[line]?.let { out[line] = it.second }
        return out
    }
}
