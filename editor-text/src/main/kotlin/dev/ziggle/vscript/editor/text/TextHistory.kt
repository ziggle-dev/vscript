package dev.ziggle.vscript.editor.text

/**
 * Undo/redo for a text buffer, by snapshot.
 *
 * Snapshots rather than a diff or a command log, because the buffer is a script — a few hundred lines at
 * the outside — and storing whole copies of it is cheaper than being clever and *much* easier to be sure
 * of. A command log would have to describe every edit the editor can make and stay correct as that set
 * grows; a snapshot cannot describe an edit wrongly.
 *
 * **The caret is part of the state.** Undoing a deletion and leaving the caret where it happened to be is
 * the difference between "put that back" and "put that back and now find your place again".
 *
 * ### Coalescing
 *
 * One undo per keystroke is unusable, so consecutive single characters typed at the caret become one
 * entry. Everything else breaks the run: a deletion, a newline, a paste, and any caret move. That rule is
 * chosen for being *predictable* rather than clever — an author should be able to guess how far one Ctrl+Z
 * goes, and "back to where I last stopped typing" is a guess people make correctly.
 */
class TextHistory(private val limit: Int = 300) {

    class Snapshot(val text: String, val caret: Int, val anchor: Int)

    private val entries = ArrayList<Snapshot>()

    /** Index of the entry the buffer currently matches. */
    private var at = -1

    /** True while the top entry is an unbroken run of typing that a further character can join. */
    private var typing = false

    val canUndo: Boolean get() = at > 0
    val canRedo: Boolean get() = at >= 0 && at < entries.size - 1

    /** Start again from [text], discarding everything. Called when the buffer is loaded from a document. */
    fun reset(text: String, caret: Int = 0) {
        entries.clear()
        entries += Snapshot(text, caret, caret)
        at = 0
        typing = false
    }

    /**
     * End the current run of typing, so the next character starts a new entry.
     *
     * Called when the caret is moved by anything other than typing — a click, an arrow key. Without it,
     * typing a word, clicking elsewhere and typing another word is one undo step covering both.
     */
    fun breakRun() {
        typing = false
    }

    /** Record the buffer's state after a change. Does nothing when the text has not actually changed. */
    fun record(text: String, caret: Int, anchor: Int) {
        val current = entries.getOrNull(at)
        if (current != null && current.text == text) {
            // A caret move is not an edit, but the position still belongs to this entry: undoing back to
            // here should land where the author actually was.
            entries[at] = Snapshot(text, caret, anchor)
            return
        }

        // Anything recorded discards a redo branch — the future being replaced is the one that was undone.
        while (entries.size > at + 1) entries.removeAt(entries.size - 1)

        if (typing && current != null && joinsRun(current, text, caret)) {
            entries[at] = Snapshot(text, caret, anchor)
            return
        }

        entries += Snapshot(text, caret, anchor)
        at = entries.size - 1
        typing = isSingleInsert(current, text, caret) && text.getOrNull(caret - 1) != '\n'

        // Oldest first: the far past is what somebody is least likely to want back.
        while (entries.size > limit) {
            entries.removeAt(0)
            at--
        }
    }

    fun undo(): Snapshot? {
        if (!canUndo) return null
        typing = false
        return entries[--at]
    }

    fun redo(): Snapshot? {
        if (!canRedo) return null
        typing = false
        return entries[++at]
    }

    /** Is [text] the previous entry with exactly one more character, typed at the caret? */
    private fun isSingleInsert(previous: Snapshot?, text: String, caret: Int): Boolean {
        if (previous == null) return false
        if (text.length != previous.text.length + 1) return false
        if (caret != previous.caret + 1) return false
        if (previous.caret != previous.anchor) return false // there was a selection; that was a replacement
        return text.substring(0, caret - 1) == previous.text.substring(0, caret - 1) &&
            text.substring(caret) == previous.text.substring(caret - 1)
    }

    /** Can this change join the run already on top? Same test, plus: a newline always ends a run. */
    private fun joinsRun(current: Snapshot, text: String, caret: Int): Boolean =
        isSingleInsert(current, text, caret) && text.getOrNull(caret - 1) != '\n'
}
