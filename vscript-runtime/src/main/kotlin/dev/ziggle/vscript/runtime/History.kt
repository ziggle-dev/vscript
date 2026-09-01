package dev.ziggle.vscript.runtime

/**
 * Undo/redo for a graph document, by whole-document snapshot.
 *
 * **Snapshots, not a command log.** The usual objection is memory, but a graph document is a few KB of
 * JSON and a hundred of them is nothing; what snapshots buy is that *every* mutation is undoable by
 * construction. A command log only covers the mutations someone remembered to write a command for, and the
 * ones that get forgotten — a multi-node drag, a pin literal edited in place, a link replaced implicitly by
 * connecting over it — are exactly the ones users hit undo after.
 *
 * **Coalescing is the whole difficulty.** Dragging a node changes its position every frame; snapshotting
 * per frame would fill the stack with 60 entries a second and make Ctrl+Z nudge a node one pixel. Edits
 * therefore carry a [label], and a repeat of the same label within [coalesceMs] extends the existing entry
 * rather than pushing a new one — so a drag from A to B is one undo step, but a drag, then a delete, then
 * another drag are three.
 *
 * **Camera moves are steps too.** An entry carries an optional [ViewState] alongside the document, so
 * panning and zooming are undoable in the same stack as editing, coalesced on their own longer window
 * ([VIEW_COALESCE_MS]) because navigating is bursty in a way editing is not. An ordinary edit records no
 * view and a camera move records no document change, which is what keeps the two from interfering: undoing
 * an edit leaves the camera alone, and undoing a pan leaves the graph alone.
 */
class History(
    private val limit: Int = 100,
    private val coalesceMs: Long = 600,
) {
    /**
     * Where the camera was, as the history sees it.
     *
     * Deliberately a plain value here rather than a reference to the canvas: the history has no business
     * knowing what a camera is, and this way an entry can be compared, stored and replayed like any other
     * piece of recorded state.
     */
    data class ViewState(val zoom: Float, val originX: Float, val originY: Float)

    /**
     * Everything one undo step restores: the document, and optionally the viewport.
     *
     * [view] is null on an ordinary edit, which is what keeps the two kinds of step from interfering —
     * undoing an edit leaves the camera exactly where it is, and undoing a camera move leaves the document
     * exactly as it is.
     */
    data class Snapshot(val json: String, val view: ViewState? = null)

    private class Entry(val label: String, val snapshot: Snapshot, val atMs: Long)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Label of the edit Ctrl+Z would reverse, for the menu/tooltip. */
    fun undoLabel(): String? = undoStack.lastOrNull()?.label

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /**
     * Record the state *before* an edit labelled [label].
     *
     * [before] is the serialized document as it stands right now; call this immediately before mutating.
     *
     * [coalesce] must be set only for a **continuous gesture** — a drag, or typing into one pin's value —
     * where the intermediate states are not things the user would ever want to return to. Discrete actions
     * never coalesce, however fast they arrive: connecting two wires in quick succession is two edits and
     * must be two undo steps, even though both are labelled "connect".
     */
    fun record(
        label: String,
        before: String,
        coalesce: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) = record(label, coalesce, coalesceMs, nowMs) { Snapshot(before) }

    fun record(
        label: String,
        before: Snapshot,
        coalesce: Boolean = false,
        windowMs: Long = coalesceMs,
        nowMs: Long = System.currentTimeMillis(),
    ) = record(label, coalesce, windowMs, nowMs) { before }

    /**
     * Record the state before an edit, taking the snapshot **lazily**.
     *
     * The by-name form exists because camera moves are recorded every frame of a pan, and serialising the
     * whole document per frame to then throw it away in the coalescing branch is exactly the kind of cost
     * that turns a smooth drag into a stuttery one. When the gesture merges, [before] is never called.
     */
    fun record(
        label: String,
        coalesce: Boolean,
        windowMs: Long,
        nowMs: Long,
        before: () -> Snapshot,
    ) {
        val last = undoStack.lastOrNull()
        if (coalesce && last != null && last.label == label && nowMs - last.atMs <= windowMs) {
            // Same continuing gesture: keep the older snapshot, just refresh its clock so a long drag
            // stays one step instead of splitting every coalesce window.
            redoStack.clear()
            undoStack.removeLast()
            undoStack.addLast(Entry(label, last.snapshot, nowMs))
            return
        }
        val snapshot = before()
        // Already on the stack. The top entry can only hold this exact state if whatever came between made
        // no difference, so a second entry for it would be an undo step that visibly does nothing. Checked
        // before the redo branch is cleared, so a no-op cannot silently cost you your redo history either.
        if (last != null && last.snapshot == snapshot) return
        redoStack.clear() // a fresh edit invalidates any redo branch
        undoStack.addLast(Entry(label, snapshot, nowMs))
        while (undoStack.size > limit) undoStack.removeFirst()
    }

    /** Step back. [current] is the live state; returns what to restore, or null if nothing to undo. */
    fun undo(current: Snapshot): Snapshot? {
        val e = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(Entry(e.label, current, System.currentTimeMillis()))
        return e.snapshot
    }

    /** Step forward. Returns what to restore, or null if nothing to redo. */
    fun redo(current: Snapshot): Snapshot? {
        val e = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(Entry(e.label, current, System.currentTimeMillis()))
        return e.snapshot
    }

    fun undo(current: String): String? = undo(Snapshot(current))?.json

    fun redo(current: String): String? = redo(Snapshot(current))?.json

    companion object {
        // Edit labels. Same label + within the window = one undo step, so these are the coalescing groups.
        const val MOVE = "move"
        const val ADD_NODE = "add node"
        const val DELETE = "delete"
        const val CONNECT = "connect"
        const val DISCONNECT = "disconnect"
        const val EDIT_VALUE = "edit value"

        // Camera labels. Separate groups, so a pan and a zoom never merge into one confusing step.
        const val PAN = "pan"
        const val ZOOM = "zoom"
        const val FRAME = "frame"

        /**
         * Coalescing window for camera moves, longer than the one for edits.
         *
         * Navigating is bursty in a way editing is not — a few wheel notches, a drag, a correcting nudge —
         * and each of those is one intention. At the edit window they came out as four or five separate
         * undo steps, so getting back to where you were meant pressing Ctrl+Z until it happened to land.
         */
        const val VIEW_COALESCE_MS = 900L
    }
}
