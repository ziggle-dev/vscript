package dev.ziggle.vscript.editor.text

/**
 * What changed between two versions of a buffer, as one replaced region.
 *
 * ### Why a diff rather than a journal of edits
 *
 * The obvious design is for the editor to record each edit as it makes one — a real journal. That is the
 * better structure and it is not available here: the edits happen inside `dev.ziggle.imgui.TextEditState`,
 * which is the widget kit's shared text field, and giving it an event stream would put an editor concern
 * into a widget the canvas also uses. `:editor-text` sees only the text before and the text after.
 *
 * Between two versions of a buffer that is enough, because editor-scale changes are *contiguous*. A
 * keystroke, a paste, a backspace, an undo — each replaces one span with another, so trimming the common
 * prefix and the common suffix recovers exactly the region that moved. It is wrong only for a change that
 * edits two distant places at once, which nothing here can produce.
 *
 * [start], [oldEnd] and [newEnd] are offsets: `old[start until oldEnd]` became `new[start until newEnd]`.
 */
class TextChange(val start: Int, val oldEnd: Int, val newEnd: Int) {

    /** Nothing moved — the two versions were identical. */
    val isEmpty: Boolean get() = start == oldEnd && start == newEnd

    /**
     * How many line breaks the replaced region took out of [before].
     *
     * Counted rather than derived from the line numbers at the region's ends: `oldEnd` sits one past the
     * last replaced character, which for a whole-line deletion is the *start of the following line*, and
     * arithmetic on that reports the wrong line as removed. See [LineBreakpoints.follow].
     */
    fun removedNewlines(before: LineIndex): Int =
        before.lineAt(oldEnd.coerceAtLeast(start)) - before.lineAt(start)

    /** How many line breaks the replacement put into [after]. */
    fun insertedNewlines(after: LineIndex): Int =
        after.lineAt(newEnd.coerceAtLeast(start)) - after.lineAt(start)

    override fun toString(): String = "[$start, $oldEnd) -> [$start, $newEnd)"

    companion object {

        /**
         * The one region that differs between [old] and [new].
         *
         * **The suffix scan must not cross the prefix**, or a change like `"aa" -> "a"` reports a negative
         * span: both ends agree about the same single `a` and each claims it. The `coerceAtLeast(start)`
         * pair is what keeps the region well-formed for the degenerate cases, which are precisely the
         * one-character edits an editor makes most often.
         */
        fun between(old: String, new: String): TextChange {
            if (old === new || old == new) return TextChange(0, 0, 0)

            var start = 0
            val max = minOf(old.length, new.length)
            while (start < max && old[start] == new[start]) start++

            var oldEnd = old.length
            var newEnd = new.length
            while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
                oldEnd--
                newEnd--
            }
            return TextChange(start, oldEnd.coerceAtLeast(start), newEnd.coerceAtLeast(start))
        }
    }
}
