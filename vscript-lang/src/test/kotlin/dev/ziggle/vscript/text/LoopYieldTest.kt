package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **No loop yields per iteration. A loop costs what its body costs.**
 *
 * A yield costs a whole scheduler pass — about 20ms of wall clock — so one per iteration is ruinous, and
 * this file has now caught that twice. First unconditionally, when a 246-item scan took five seconds. The
 * fix then was to NARROW it to bodies that cannot park by themselves, and this class pinned that.
 *
 * That narrowing was not enough, and the second measurement says why: **a body that only computes cannot
 * park either.** A bank organiser's 640-iteration plan loop took 12,801ms, the same loop written as a
 * `for` took 1ms, and nothing in the source, the docs or the profiler named the cause — the fiber simply
 * sat PARKED, which is what a yield does.
 *
 * So the yield is gone. It was never what kept the client safe: the interpreter preempts on the 3ms
 * instruction budget and leaves the fiber RUNNABLE, so no loop can hold a frame however long it runs, and
 * `Scheduler.runawayPreemptionLimit` still fails one that spins without ever parking. What the yield
 * actually bought was letting `while !ready() { }` with no delay poll once a tick instead of spinning —
 * and that is already an authoring error (LANGUAGE.md §14: every loop waits). Rescuing it quietly, at the
 * cost of making every correct computational loop a thousand times slower, was the wrong trade.
 */
class LoopYieldTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("things", results = outs(TypeRef.list(INT))),
            // A game verb: BLOCKING, so it parks the fiber on its own.
            NativeFn("chop", results = emptyList(), kind = HostKind.BLOCKING),
            NativeFn(
                "_listWithItemAdded",
                listOf(NativeParam("list", TypeRef.list(STRING)), NativeParam("value", STRING)),
                results = outs(TypeRef.list(STRING)),
            ),
        ),
    )

    private fun yieldsIn(body: String): Int {
        val src = """
            graph "probe"
            on start {
            $body
            }
        """.trimIndent()
        val front = TextFrontEnd(natives)
        val read = front.read(src)
        val chunk = read.chunk ?: fail(front.describe(read))
        return chunk.code.toList().chunked(4).count { it[0] == Op.YIELD }
    }

    @Test
    fun `a for loop never yields`() {
        assertEquals(0, yieldsIn("""    for n in things() { log(message: "" + n) }"""))
    }

    @Test
    fun `a nested for loop never yields`() {
        assertEquals(
            0,
            yieldsIn(
                """    for a in things() {
        for b in things() {
            log(message: "" + a + b)
        }
    }""",
            ),
        )
    }

    /**
     * The shape the yield used to exist for. It still emits none — the runaway guard is what catches this
     * now, and it does so by NAME rather than by silently slowing every other loop down.
     */
    @Test
    fun `a while true with no wait emits no yield either`() {
        assertEquals(0, yieldsIn("""    while true { log(message: "spin") }"""))
    }

    /** It already parks on a `delay`, so a second parking point per iteration is pure cost. */
    @Test
    fun `a while whose body delays does not yield`() {
        assertEquals(0, yieldsIn("""    while true { delay(ms: 100) }"""))
    }

    /** And on a game verb, which parks on the actuator drain. */
    @Test
    fun `a while whose body calls a blocking verb does not yield`() {
        assertEquals(0, yieldsIn("""    while true { chop() }"""))
    }

    /** Reached through a branch still counts — the body CAN park, which is the question being asked. */
    @Test
    fun `a wait inside a branch of the body still counts`() {
        assertEquals(
            0,
            yieldsIn(
                """    while true {
        if true {
            delay(ms: 100)
        }
    }""",
            ),
        )
    }

    /**
     * The measured regression, in miniature: a `while` wrapping a pure scan. This emitted one yield per
     * outer iteration, which is what turned a 640-item plan loop into 12.8 seconds.
     */
    @Test
    fun `a while containing only a pure for is free of yields`() {
        assertEquals(
            0,
            yieldsIn(
                """    while true {
        for n in things() {
            log(message: "" + n)
        }
    }""",
            ),
        )
    }

    /** The regression in miniature: a scan over a list, with a scan inside it, and nothing to park on. */
    @Test
    fun `a scan inside a scan is free of yields`() {
        val n = yieldsIn(
            """    for item in things() {
        for row in things() {
            log(message: "" + item + row)
        }
    }""",
        )
        assertTrue(n == 0, "a bounded nested scan emitted $n yield(s) — that is 20ms of wall clock each")
    }

    /**
     * A one-shot pass must COMPLETE, and a yield inside one guarantees it never does.
     *
     * A render or tick pass gets a few milliseconds and is all-or-nothing: unfinished, it is killed and
     * everything it wrote is discarded. So a `while` that yields in one is not slow — it never finishes,
     * ever, and the runtime reports "did not finish in 5ms — it is doing too much", which is a description
     * of a pass that parked in its first microsecond.
     *
     * This is exactly how a farm-run panel lost its rows: its tick handler sized a list with
     * `while _listCount(xs) < n { … }`, so the list was never sized, the pass never landed, and the panel had
     * nothing to draw for the rest of the run.
     */
    @Test
    fun `a while that cannot park completes inside a staged pass`() {
        val src = """
            graph "probe"
            var Rows: LIST<STRING> = []
            on start {
                while _listCount(list: Rows) < 5 {
                    Rows = _listWithItemAdded(list: Rows, value: "-")
                }
                log(message: "sized " + _listCount(list: Rows))
            }
        """.trimIndent()

        val said = ArrayList<String>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("_listWithItemAdded", HostKind.INLINE, arity = 2, results = 1) { a ->
            ArrayList((a[0] as List<*>) + a[1])
        }

        val front = TextFrontEnd(natives)
        val read = front.read(src)
        val chunk = read.chunk ?: fail(front.describe(read))
        // No yield any more — the loop simply runs, and the budget preempt is what bounds the frame.
        assertEquals(0, chunk.code.toList().chunked(4).count { it[0] == Op.YIELD })

        val clock = dev.ziggle.vscript.vm.FakeClock()
        val interp = dev.ziggle.vscript.vm.Interpreter(hosts, clock)
        interp.resetGlobals(chunk.globals)
        val sch = dev.ziggle.vscript.vm.Scheduler(interp, clock)

        // Driven the way `ScriptRuntime.gameTick` drives one: staged, one pass, all-or-nothing.
        interp.beginStaged()
        val f = sch.spawn("tick", chunk)
        sch.tick()
        val finished = f.isFinished
        if (finished) interp.commitPending() else interp.discardPending()

        assertTrue(finished, "the pass parked instead of finishing, so everything it wrote was discarded")
        assertEquals(listOf("sized 5"), said)
    }
}
