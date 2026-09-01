package dev.ziggle.vscript.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The log sink.
 *
 * Every rule here exists because of a specific way a console becomes unusable: a loop that pushes ten
 * thousand identical rows, a runaway script that eats the heap, or a run whose timestamps are wall-clock and
 * therefore say nothing about the shape of the execution.
 */
class ScriptLogTest {

    @Test
    fun `consecutive identical records collapse into one row`() {
        // The first `While` anyone writes emits the same line hundreds of times a second. Without this the
        // console is unreadable before you have finished pressing Run.
        val log = ScriptLog()
        repeat(500) { log.add(LogLevel.INFO, "tick", nodeId = 7, nowNanos = it.toLong()) }

        assertEquals(1, log.records.size)
        assertEquals(500, log.records.single().repeats)
    }

    @Test
    fun `collapsing compares the triple, not the timestamp`() {
        val log = ScriptLog()
        log.add(LogLevel.INFO, "tick", nodeId = 7, nowNanos = 0)
        log.add(LogLevel.WARN, "tick", nodeId = 7, nowNanos = 1)      // different level
        log.add(LogLevel.INFO, "tick", nodeId = 8, nowNanos = 2)      // different node
        log.add(LogLevel.INFO, "other", nodeId = 7, nowNanos = 3)     // different message

        assertEquals(4, log.records.size)
    }

    @Test
    fun `only the LAST row folds, so order is never rewritten`() {
        // Merging across an intervening record would claim the two happened together, and the order of
        // execution is the one thing a log exists to preserve.
        val log = ScriptLog()
        log.add(LogLevel.INFO, "a", nodeId = 1)
        log.add(LogLevel.INFO, "b", nodeId = 1)
        log.add(LogLevel.INFO, "a", nodeId = 1)

        assertEquals(listOf("a", "b", "a"), log.records.map { it.message })
    }

    @Test
    fun `a collapsed row keeps its first timestamp and tracks the latest`() {
        val log = ScriptLog()
        log.add(LogLevel.INFO, "tick", nodeId = 1, nowNanos = 100)
        log.add(LogLevel.INFO, "tick", nodeId = 1, nowNanos = 900)

        val r = log.records.single()
        assertEquals(100L, r.atNanos, "the row is anchored where the run first reached it")
        assertEquals(900L, r.lastAtNanos)
    }

    @Test
    fun `the ring is bounded and counts what it dropped`() {
        val log = ScriptLog(capacity = 10)
        repeat(25) { log.add(LogLevel.INFO, "m$it", nodeId = it) }

        assertEquals(10, log.records.size)
        assertEquals(15L, log.dropped)
        assertEquals("m15", log.records.first().message, "the oldest go first")
    }

    @Test
    fun `counts split by level`() {
        val log = ScriptLog()
        log.add(LogLevel.INFO, "a", nodeId = 1)
        log.add(LogLevel.WARN, "b", nodeId = 1)
        log.add(LogLevel.WARN, "c", nodeId = 1)
        log.add(LogLevel.ERROR, "d", nodeId = 1)

        val c = log.counts()
        assertEquals(1, c.info)
        assertEquals(2, c.warn)
        assertEquals(1, c.error)
        assertEquals(4, c.total)
    }

    @Test
    fun `a collapsed run counts as one entry, because it is one row`() {
        val log = ScriptLog()
        repeat(9) { log.add(LogLevel.WARN, "same", nodeId = 3) }
        assertEquals(1, log.counts().warn)
    }

    @Test
    fun `worst level wins per node`() {
        // What the badge shows: a node that warned nine times and errored once is an error.
        val log = ScriptLog()
        log.add(LogLevel.INFO, "a", nodeId = 1)
        log.add(LogLevel.ERROR, "b", nodeId = 1)
        log.add(LogLevel.WARN, "c", nodeId = 1)
        log.add(LogLevel.INFO, "d", nodeId = 2)
        log.add(LogLevel.INFO, "engine", nodeId = -1)

        val worst = log.worstByNode()
        assertEquals(LogLevel.ERROR, worst[1])
        assertEquals(LogLevel.INFO, worst[2])
        assertNull(worst[-1], "engine messages belong to no node and must not badge one")
    }

    @Test
    fun `activation distinguishes executions of the same node`() {
        val log = ScriptLog()
        log.add(LogLevel.INFO, "a", nodeId = 4, activation = 1)
        log.add(LogLevel.INFO, "b", nodeId = 4, activation = 47)

        assertEquals(listOf(1, 47), log.records.map { it.activation })
    }

    @Test
    fun `a run resets the clock but not the records`() {
        val log = ScriptLog()
        log.beginRun(nowNanos = 1_000)
        log.add(LogLevel.INFO, "a", nodeId = 1, nowNanos = 1_500)
        assertEquals(1, log.runId)
        assertTrue(log.isRunning)

        log.endRun(nowNanos = 3_000)
        assertEquals(2_000L, log.elapsedNanos())
        assertTrue(!log.isRunning)

        log.beginRun(nowNanos = 9_000)
        assertEquals(2, log.runId)
        assertEquals(1, log.records.size, "beginRun does not clear; the caller decides that")
    }

    @Test
    fun `export is relative to the run and spells out repeats`() {
        val log = ScriptLog()
        log.beginRun(nowNanos = 1_000_000_000)
        log.add(LogLevel.WARN, "cast", nodeId = 2, nowNanos = 1_500_000_000)
        log.add(LogLevel.WARN, "cast", nodeId = 2, nowNanos = 1_600_000_000)

        val text = log.exportText()
        assertTrue("+0.500s" in text, "timestamps are relative to the run start:\n$text")
        assertTrue("(x2)" in text, "repeats survive export:\n$text")
    }

    @Test
    fun `level parsing is forgiving and defaults to info`() {
        assertEquals(LogLevel.WARN, LogLevel.of("warn"))
        assertEquals(LogLevel.WARN, LogLevel.of("Warning"))
        assertEquals(LogLevel.ERROR, LogLevel.of("ERROR"))
        assertEquals(LogLevel.INFO, LogLevel.of("nonsense"))
        assertEquals(LogLevel.INFO, LogLevel.of(null))
    }
}
