package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.VmError
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `try { … } catch e { … }`.
 *
 * A RANGE over the body's instructions with the handler after it — the VM's own shape, and the same one
 * the graph compiler emits. The range covers the body ONLY: an error raised inside the catch must not be
 * caught by the same handler, or a failing handler would spin forever.
 */
class TryTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("boom", results = emptyList()),
            NativeFn("fine", results = emptyList()),
            // BLOCKING — a game action. It does not run inline; it is handed to the actuator drain and
            // its result, success or failure, arrives on a later tick.
            NativeFn("depositAll", results = emptyList(), kind = HostKind.BLOCKING),
        ),
    )

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("boom", HostKind.INLINE, arity = 0, results = 0) { throw VmError("it went wrong") }
        hosts.register("fine", HostKind.INLINE, arity = 0, results = 0) { said += "fine"; null }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun script(body: String) = """
        graph "probe"

        on start {
        $body
        }
    """.trimIndent()

    @Test
    fun `a body that fails runs the catch`() {
        assertEquals(
            listOf("caught"),
            run(
                script(
                    """    try {
        boom()
    } catch e {
        log(message: "caught")
    }""",
                ),
            ),
        )
    }

    /** The message is bound, and it is what the failure said. */
    @Test
    fun `the catch is given the message`() {
        assertTrue(
            run(
                script(
                    """    try {
        boom()
    } catch e {
        log(message: e)
    }""",
                ),
            ).first().contains("it went wrong"),
        )
    }

    @Test
    fun `a body that does not fail skips the catch`() {
        assertEquals(
            listOf("fine", "after"),
            run(
                script(
                    """    try {
        fine()
    } catch e {
        log(message: "caught")
    }
    log(message: "after")""",
                ),
            ),
        )
    }

    @Test
    fun `the rest of the body after a failure is skipped`() {
        assertEquals(
            listOf("caught", "after"),
            run(
                script(
                    """    try {
        boom()
        log(message: "unreachable")
    } catch e {
        log(message: "caught")
    }
    log(message: "after")""",
                ),
            ),
        )
    }

    /** The message belongs to the handler and to nothing else. */
    @Test
    fun `the message is not in scope after the catch`() {
        assertTrue(
            read(
                script(
                    """    try {
        fine()
    } catch e {
        log(message: e)
    }
    log(message: e)""",
                ),
            ).errors.any { it.message.contains("'e'") },
        )
    }

    // ---- a BLOCKING verb ---------------------------------------------------------------------------

    /**
     * `try` around a game action.
     *
     * **The case `try` exists for, and the one it did not cover.** Every game verb is BLOCKING: it is
     * handed to the actuator drain and its result arrives on a later tick, through `tryCompleteAct` —
     * which set the fiber FAILED without ever asking whether anything was catching. So `try` worked for
     * arithmetic and for queries and silently did nothing for `depositAll`, `walkTo`, `interact`: the
     * whole reason a script has a `try` in it.
     *
     * Found in the live client, by a script whose retry loop never retried.
     */
    private fun runBlocking(src: String, fail: Int): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("boom", HostKind.INLINE, arity = 0, results = 0) { throw VmError("it went wrong") }
        hosts.register("fine", HostKind.INLINE, arity = 0, results = 0) { said += "fine"; null }
        var attempts = 0
        hosts.register("depositAll", HostKind.BLOCKING, arity = 0, results = 0) {
            attempts++
            if (attempts <= fail) throw VmError("the inventory did not empty")
            said += "deposited"
            null
        }
        val r = read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        val actuator = dev.ziggle.vscript.vm.ManualActuator()
        val clock = dev.ziggle.vscript.vm.FakeClock()
        val interpreter = dev.ziggle.vscript.vm.Interpreter(hosts, clock, actuator)
        interpreter.resetGlobals(chunk.globals)
        val scheduler = dev.ziggle.vscript.vm.Scheduler(interpreter, clock)
        val fiber = scheduler.spawn("test", chunk)
        var ticks = 0
        while (!fiber.isFinished && ticks < 200) {
            ticks++
            scheduler.tick()
            // The drain, as the client's thread would run it.
            actuator.drain()
            if (!fiber.isFinished) scheduler.nextWakeMs()?.let { clock.now += it }
        }
        if (fiber.state == dev.ziggle.vscript.vm.FiberState.FAILED) said += "FAILED: " + fiber.error?.rawMessage
        return said
    }

    @Test
    fun `a blocking verb that throws is caught by the try around it`() {
        assertEquals(
            listOf("caught: the inventory did not empty", "after"),
            runBlocking(
                script(
                    """    try {
        depositAll()
    } catch e {
        log(message: "caught: " + e)
    }
    log(message: "after")""",
                ),
                fail = 1,
            ),
        )
    }

    /** The retry loop the corpus actually writes — it has to make progress, not spin. */
    @Test
    fun `a retry loop around a blocking verb eventually succeeds`() {
        assertEquals(
            listOf("retrying", "retrying", "deposited"),
            runBlocking(
                script(
                    """    var done = false
    while !done {
        try {
            depositAll()
            done = true
        } catch e {
            log(message: "retrying")
        }
    }""",
                ),
                fail = 2,
            ),
        )
    }

    /** Uncaught, it still fails — the handler search must not swallow anything. */
    @Test
    fun `a blocking verb that throws outside a try still fails the fiber`() {
        val said = runBlocking(script("""    depositAll()"""), fail = 1)
        assertTrue(
            said.any { it.startsWith("FAILED:") && "did not empty" in it },
            "an uncaught blocking failure should still fail the fiber: $said",
        )
    }
}
