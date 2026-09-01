package dev.ziggle.vscript.test

import dev.ziggle.vscript.host.Clock
import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.text.TextEntry
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.Interpreter
import dev.ziggle.vscript.vm.Scheduler

/**
 * Running a document's `test` declarations.
 *
 * **The VM was built for this and nothing used it.** `HostRegistry` binds a chunk's host names to
 * implementations at LOAD time, and its own doc has said so since the beginning: *"the same bytecode can be
 * run against the real node catalog in the client and against fakes in a test"*. So a test needs no special
 * compilation and no second interpreter — it needs a registry with fakes in it and a clock nobody has to
 * wait for.
 *
 * What was being done instead: the scheduler's only real test asserted on **log lines matched with regular
 * expressions**, against two probe documents that are not in the tree, so it could not run at all. And
 * `docs/GAPS.md` records the lesson from the other direction — *"The corpus test COMPILES every script and
 * never runs one, so nothing caught it"*.
 */

/** A clock the runner advances by hand, so a `delay(ms: 600)` inside a test costs nothing. */
class TestClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

/** What one `test "…"` did. */
class TestOutcome(
    /** The document that declared it. */
    val document: String,
    /** The test's own name, as written. */
    val name: String,
    val passed: Boolean,
    /**
     * Why it failed — an assertion's report, a fault from a verb, or the runner's own give-up.
     *
     * Null exactly when [passed]. A failure with no message is a failure nobody can act on, so the runner
     * supplies one for every path it can end on, including the ones that are its own fault.
     */
    val message: String? = null,
    /** Scheduler passes it took — a runaway test is the one that hits the cap. */
    val ticks: Int = 0,
) {
    // ASCII only. This goes to a Windows console as often as not, and a Windows console in the default
    // code page turns an em dash into a replacement character — in the middle of the line whose whole job
    // is to be read.
    override fun toString(): String =
        (if (passed) "PASS  " else "FAIL  ") + document + " - " + name + (message?.let { "\n        $it" } ?: "")
}

/** Every outcome of one run. */
class TestReport(val outcomes: List<TestOutcome>) {
    val passed: List<TestOutcome> get() = outcomes.filter { it.passed }
    val failed: List<TestOutcome> get() = outcomes.filterNot { it.passed }
    val ok: Boolean get() = failed.isEmpty()

    /** One line, the shape every test runner ends with. */
    val summary: String
        get() = "${outcomes.size} test${if (outcomes.size == 1) "" else "s"}, " +
            "${passed.size} passed, ${failed.size} failed"

    override fun toString(): String =
        (outcomes.joinToString("\n") + "\n").ifBlank { "" } + summary
}

/**
 * Drives compiled tests.
 *
 * [hosts] is a FACTORY rather than a registry, and that is the isolation guarantee: each test gets its own
 * bindings, so one that records calls or holds state cannot be read by the next. Globals are re-seeded per
 * test for the same reason — a document variable a test wrote would otherwise be whatever the previous test
 * left in it, and the suite's result would depend on the order it happened to run in.
 */
class VsTestRunner(
    private val hosts: () -> HostRegistry,
    /**
     * The cap on scheduler passes for ONE test.
     *
     * A test that loops forever has to end as a failure rather than as a hung build, and it has to say so —
     * "gave up" naming the test is actionable and a build that never finishes is not.
     */
    private val maxTicks: Int = 100_000,
) {

    fun run(compilation: TextFrontEnd.Compilation, filter: (TextEntry) -> Boolean = { true }): TestReport {
        val tests = compilation.entries[EntryKind.TEST].orEmpty().filter(filter)
        return TestReport(tests.map { one(it, compilation.globals) })
    }

    /**
     * One test, and it NEVER throws.
     *
     * **A runner that sometimes throws instead of reporting is a runner whose output you cannot read.** An
     * unbound host is the case: reached from an imported chunk it faults the fiber and is reported, and
     * named in the test's OWN body it throws out of `spawn`, where the chunk is bound — so the same
     * mistake surfaced either as a `FAIL` line naming the test or as a stack trace naming nothing, purely
     * by where it was written. Every path now ends in a [TestOutcome].
     */
    private fun one(entry: TextEntry, globals: List<Any?>): TestOutcome =
        runCatching { drive(entry, globals) }.getOrElse { e ->
            TestOutcome(entry.document, entry.label ?: entry.chunk.name, false, e.message ?: e.toString())
        }

    private fun drive(entry: TextEntry, globals: List<Any?>): TestOutcome {
        val name = entry.label ?: entry.chunk.name
        val clock = TestClock()
        val interpreter = Interpreter(hosts(), clock, actuator = null)
        // Seeded from the RUN's globals, not the chunk's, for the reason `compileEntries` gives about
        // prefixes — and freshly per test, so one test cannot see what another wrote.
        interpreter.resetGlobals(globals.ifEmpty { entry.chunk.globals })
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn(name, entry.chunk)
        var ticks = 0
        while (!fiber.isFinished && ticks < maxTicks) {
            ticks++
            scheduler.tick()
            if (fiber.state == FiberState.PAUSED) break
            // Every fiber parked: jump the clock to whatever it is waiting for rather than waiting.
            if (!fiber.isFinished) scheduler.nextWakeMs()?.let { clock.now += it }
        }
        return when {
            fiber.state == FiberState.FAILED ->
                TestOutcome(entry.document, name, false, fiber.error?.message ?: "failed with no message", ticks)
            fiber.state == FiberState.DONE -> TestOutcome(entry.document, name, true, null, ticks)
            else -> TestOutcome(
                entry.document, name, false,
                "gave up after $maxTicks scheduler passes — the test never finished",
                ticks,
            )
        }
    }
}
