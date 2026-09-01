package dev.ziggle.imgui

/**
 * A tiny most-recently-used list of entry ids, capped at [capacity]. The pickers record each selection
 * here and show it (newest first) when the search box is empty, so re-picking a recent item/NPC is a
 * click instead of a re-search. In-memory and process-lifetime — recents reset when the client restarts.
 */
class RecentIds(private val capacity: Int = 10) {

    private val ids = ArrayDeque<Int>()

    /** Move [id] to the front (dedup), dropping the oldest past [capacity]. Ignores negative (= none). */
    @Synchronized
    fun record(id: Int) {
        if (id < 0) return
        ids.remove(id)
        ids.addFirst(id)
        while (ids.size > capacity) ids.removeLast()
    }

    /** The recent ids, newest first. */
    @Synchronized
    fun list(): List<Int> = ids.toList()
}
