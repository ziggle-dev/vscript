package dev.ziggle.vscript.vm

/**
 * Where the debugger wants execution to stop, and why it might not stop there yet.
 *
 * Keyed by authoring node id, because that is the only identity the user has: a breakpoint set on Divide
 * must survive the graph being recompiled, rearranged, or run from a different entry point, and instruction
 * addresses survive none of those.
 *
 * **Editor state, not document state.** Nothing here is written into the graph file — arming a breakpoint
 * must not dirty a script, and a breakpoint is a thing about *this debugging session*, not about the program.
 */
class Breakpoints {

    class Entry(
        var enabled: Boolean = true,
        /**
         * Stop only on the Nth arrival; 0 means every arrival.
         *
         * This is why activations have to be counted from the start — "break on the 47th time round the
         * loop" is unanswerable without them, and retrofitting a counter once traces are in circulation
         * means every recorded trace predates it.
         */
        var hitCount: Int = 0,
    ) {
        /** Arrivals seen this run. Reset by [Breakpoints.resetHits] when a run starts. */
        var hits: Int = 0
            internal set
    }

    private val byNode = LinkedHashMap<Int, Entry>()

    val nodeIds: Set<Int> get() = byNode.keys
    val size: Int get() = byNode.size
    val armed: Int get() = byNode.count { it.value.enabled }

    operator fun get(nodeId: Int): Entry? = byNode[nodeId]

    operator fun contains(nodeId: Int): Boolean = nodeId in byNode

    fun entries(): List<Pair<Int, Entry>> = byNode.map { it.key to it.value }

    /** @return true if the node now has a breakpoint. */
    fun toggle(nodeId: Int): Boolean {
        if (byNode.remove(nodeId) != null) return false
        byNode[nodeId] = Entry()
        return true
    }

    fun add(nodeId: Int, enabled: Boolean = true, hitCount: Int = 0) {
        byNode[nodeId] = Entry(enabled, hitCount)
    }

    fun remove(nodeId: Int) {
        byNode.remove(nodeId)
    }

    fun clear() = byNode.clear()

    fun resetHits() = byNode.values.forEach { it.hits = 0 }

    /**
     * Called at every node boundary. Counts the arrival and says whether to stop.
     *
     * The count advances even for a disabled breakpoint, so a hit condition means "the 47th time control
     * reached here" rather than "the 47th time you had this switched on" — the former is what you were
     * counting when you set it.
     */
    fun shouldBreak(nodeId: Int): Boolean {
        val e = byNode[nodeId] ?: return false
        e.hits++
        if (!e.enabled) return false
        return e.hitCount <= 0 || e.hits == e.hitCount
    }
}
