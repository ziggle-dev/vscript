package dev.ziggle.vscript.log

enum class LogLevel {
    INFO,
    WARN,
    ERROR;

    companion object {
        /** Parse a level written by an author into a pin literal. Anything unrecognised is information. */
        fun of(s: Any?): LogLevel = when (s?.toString()?.lowercase()) {
            "warn", "warning" -> WARN
            "error", "err" -> ERROR
            else -> INFO
        }
    }
}

/**
 * One thing that happened, as a **record** rather than a formatted line.
 *
 * Formatting is the render step's job. Everything the console is for — filtering by level, jumping to the
 * node that emitted, grouping repeats, exporting — needs the parts kept apart; a pre-formatted string
 * throws all of that away at the moment of writing and cannot get it back.
 *
 * [activation] is which *execution* of [nodeId] this came from, not just which node. In a loop the same
 * Divide fires five hundred times, and "the one that failed" is a question about an activation. It costs an
 * int here and is unrecoverable once records are in circulation without it.
 */
class LogRecord(
    val seq: Long,
    val atNanos: Long,
    val level: LogLevel,
    /** Authoring node that emitted this, or -1 for engine-level messages. */
    val nodeId: Int,
    val activation: Int,
    /** Which document — a record outlives the editor's idea of what is open. */
    val graphId: String,
    val runId: Int,
    val message: String,
    val data: Map<String, Any?>? = null,
) {
    /**
     * How many consecutive identical records this row stands for.
     *
     * Incremented in place so a running loop's counter ticks up live rather than pushing rows. Without
     * collapsing, the first `While` anyone writes makes the console useless within a second.
     */
    var repeats: Int = 1
        internal set

    /** When the most recent repeat arrived; equal to [atNanos] for a row that never repeated. */
    var lastAtNanos: Long = atNanos
        internal set

    /** Would [other] fold into this row? Identity is the triple, deliberately not the timestamp. */
    internal fun sameAs(level: LogLevel, nodeId: Int, message: String): Boolean =
        this.level == level && this.nodeId == nodeId && this.message == message
}

/**
 * The sink the engine writes to and the console reads from.
 *
 * **Decoupled from the UI on purpose.** The engine appends; nothing is drawn, laid out or measured on the
 * writing path. A node that emitted straight into the panel would let one tight loop drag the whole editor
 * down to the log's frame rate, and the failure would look like the VM hanging rather than like a logging
 * problem.
 *
 * **Bounded on purpose.** A fixed ring that drops the oldest and counts what it dropped. An unbounded list
 * is how an editor with a runaway loop ends up out of memory, and "N earlier entries dropped" is honest in
 * a way silently keeping the last few thousand is not.
 *
 * Single-threaded: the VM pumps on the client thread and the editor renders on it, which is what lets the
 * console read records directly with no copying or locking.
 */
class ScriptLog(private val capacity: Int = CAPACITY) {

    private val ring = ArrayDeque<LogRecord>(64)
    private var nextSeq = 0L

    /** Records in arrival order, oldest first. Read-only to callers. */
    val records: List<LogRecord> get() = ring

    /** How many records the ring has discarded, ever — shown as a marker at the top of the list. */
    var dropped: Long = 0L
        private set

    var runId: Int = 0
        private set

    /** Reference point for relative timestamps: you care about the shape of a run, not the wall clock. */
    var runStartNanos: Long = 0L
        private set

    /** Nanos when the run ended, or 0 while one is in flight. */
    var runEndNanos: Long = 0L
        private set

    val isRunning: Boolean get() = runStartNanos != 0L && runEndNanos == 0L

    /** Elapsed nanos of the current or last run. */
    fun elapsedNanos(nowNanos: Long = System.nanoTime()): Long {
        if (runStartNanos == 0L) return 0L
        return (if (runEndNanos == 0L) nowNanos else runEndNanos) - runStartNanos
    }

    fun counts(): Counts {
        var info = 0
        var warn = 0
        var error = 0
        for (r in ring) when (r.level) {
            LogLevel.INFO -> info++
            LogLevel.WARN -> warn++
            LogLevel.ERROR -> error++
        }
        return Counts(info, warn, error)
    }

    class Counts(val info: Int, val warn: Int, val error: Int) {
        val total: Int get() = info + warn + error
    }

    /** Worst level emitted by each node this run — drives the badge on a node's header. */
    fun worstByNode(): Map<Int, LogLevel> {
        val out = HashMap<Int, LogLevel>()
        for (r in ring) {
            if (r.nodeId < 0) continue
            val cur = out[r.nodeId]
            if (cur == null || r.level > cur) out[r.nodeId] = r.level
        }
        return out
    }

    fun forNode(nodeId: Int): List<LogRecord> = ring.filter { it.nodeId == nodeId }

    /** Begin a run: bumps the id, restarts the clock, and leaves previous records in place. */
    fun beginRun(nowNanos: Long = System.nanoTime()): Int {
        runId++
        runStartNanos = nowNanos
        runEndNanos = 0L
        return runId
    }

    fun endRun(nowNanos: Long = System.nanoTime()) {
        if (runStartNanos != 0L && runEndNanos == 0L) runEndNanos = nowNanos
    }

    /**
     * Append a record, folding it into the previous row when it is identical.
     *
     * Folding compares only against the LAST row, so it collapses runs rather than merging things that
     * happened either side of something else — which would misrepresent the order of execution, the one
     * thing a log exists to preserve.
     */
    fun add(
        level: LogLevel,
        message: String,
        nodeId: Int = -1,
        activation: Int = -1,
        graphId: String = "",
        data: Map<String, Any?>? = null,
        nowNanos: Long = System.nanoTime(),
    ): LogRecord {
        val last = ring.lastOrNull()
        if (last != null && last.sameAs(level, nodeId, message)) {
            last.repeats++
            last.lastAtNanos = nowNanos
            return last
        }
        val r = LogRecord(nextSeq++, nowNanos, level, nodeId, activation, graphId, runId, message, data)
        ring.addLast(r)
        while (ring.size > capacity) {
            ring.removeFirst()
            dropped++
        }
        return r
    }

    fun clear() {
        ring.clear()
        dropped = 0L
    }

    /** Plain text, for the export button. Absolute-relative times, repeats spelled out. */
    fun exportText(): String = buildString {
        append("# vscript visual script log — run ").append(runId).append('\n')
        if (dropped > 0) append("# ").append(dropped).append(" earlier entries dropped\n")
        for (r in ring) {
            append(String.format("%+.3fs ", (r.atNanos - runStartNanos) / 1e9))
            append('[').append(r.level.name).append("] ")
            if (r.nodeId >= 0) append("node ").append(r.nodeId).append('#').append(r.activation).append(' ')
            append(r.message)
            if (r.repeats > 1) append("  (x").append(r.repeats).append(')')
            append('\n')
        }
    }

    private companion object {
        /**
         * Ring size.
         *
         * Big enough that a normal run never loses anything, small enough that a runaway loop costs a few
         * hundred KB rather than the heap.
         */
        const val CAPACITY = 4000
    }
}
