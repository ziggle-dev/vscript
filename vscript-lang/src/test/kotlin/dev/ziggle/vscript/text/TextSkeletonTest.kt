package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.ProgramBuilder
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Text → bytecode → the VM, with no graph anywhere in it.
 *
 * **The walking skeleton of `docs/TEXT_FRONTEND.md`, and its point is the seam rather than the subset.**
 * Everything here is small on purpose; what is being proved is that a front end which never builds a
 * `Graph` can produce chunks this VM runs, that the threading classification survives the trip, and that
 * the three invariants hold structurally rather than by anyone remembering them.
 */
class TextSkeletonTest {

    private val STRING = TypeRef(PinType.STRING)
    private val BOOL = TypeRef(PinType.BOOL)

    /** What the script said, in order — the same trick `DifferentialTest` uses to compare two programs. */
    private class Recorder {
        val said = ArrayList<String>()
        val hosts = HostRegistry()

        init {
            hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a ->
                said += a[0].toString(); null
            }
        }
    }

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("boom", results = outs(BOOL)),
            NativeFn("walkTo", listOf(NativeParam("where", STRING)), results = emptyList(), kind = HostKind.BLOCKING),
        ),
    )

    private fun compile(
        src: String,
        program: ProgramBuilder = ProgramBuilder(),
        /** What the ROOT is called — see `TextFrontEnd.rootRef`. Its default is what a test wants. */
        rootRef: String = Resolution.ROOT,
    ): Chunk {
        val read = TextFrontEnd(natives, program, rootRef = rootRef).read(src)
        return read.chunk ?: fail("did not compile: " + read.errors.joinToString { "${it.span} ${it.message}" })
    }

    /** The diagnostics a source produced, for the cases where refusing IS the behaviour under test. */
    private fun refusals(src: String): List<String> =
        TextFrontEnd(natives).read(src).errors.map { it.message }

    // ---- it runs -------------------------------------------------------------------------------------

    @Test
    fun `locals and arithmetic reach the vm`() {
        val r = Recorder()
        val chunk = compile(
            """
            graph "probe"

            on start {
                val a = 2
                var b = a * 3
                b += 1
                log(message: "b=" + b)
            }
            """.trimIndent(),
        )
        drive(chunk, r.hosts)
        assertEquals(listOf("b=7"), r.said)
    }

    @Test
    fun `if and else pick one arm`() {
        val r = Recorder()
        drive(
            compile(
                """
                graph "probe"

                on start {
                    val n = 4
                    if n > 3 {
                        log(message: "big")
                    } else {
                        log(message: "small")
                    }
                }
                """.trimIndent(),
            ),
            r.hosts,
        )
        assertEquals(listOf("big"), r.said)
    }

    @Test
    fun `a while loop counts, and break leaves it`() {
        val r = Recorder()
        drive(
            compile(
                """
                graph "probe"

                on start {
                    var i = 0
                    while true {
                        if i >= 3 {
                            break
                        }
                        log(message: "i=" + i)
                        i += 1
                    }
                    log(message: "done")
                }
                """.trimIndent(),
            ),
            r.hosts,
        )
        assertEquals(listOf("i=0", "i=1", "i=2", "done"), r.said)
    }

    /**
     * `&&` must not evaluate its right side when the left already decided.
     *
     * The one behaviour a naive emitter gets wrong, and it fails silently: the wrong version calls a host
     * function it was never supposed to call, which on a real script means a click nobody asked for.
     */
    @Test
    fun `and-then short-circuits`() {
        val r = Recorder()
        r.hosts.register("boom", HostKind.INLINE, arity = 0, results = 1) {
            fail("the right side of '&&' was evaluated")
        }
        drive(
            compile(
                """
                graph "probe"

                on start {
                    val no = false
                    if no && boom() {
                        log(message: "unreachable")
                    }
                    log(message: "survived")
                }
                """.trimIndent(),
            ),
            r.hosts,
        )
        assertEquals(listOf("survived"), r.said)
    }

    /**
     * A BLOCKING native compiles to `ACT` and goes to the actuator, not to the client thread.
     *
     * This is the half of the threading model an author never chooses, so it is the half that has to be
     * proved rather than trusted: emitting `CALL` here is exactly the mistake that trips the 16ms
     * client-thread watchdog, and it is invisible in a test that only checks the output.
     */
    @Test
    fun `a blocking native is offered to the actuator`() {
        val r = Recorder()
        r.hosts.register("walkTo", HostKind.BLOCKING, arity = 1, results = 0) { a ->
            r.said += "walked to ${a[0]}"; null
        }
        // Runs the work at once, as the drain would, and counts what it was handed. An INLINE call never
        // reaches here at all — which is exactly what this is checking.
        var offers = 0
        val sink = object : dev.ziggle.vscript.vm.ActuatorSink {
            override fun offer(label: String, work: () -> Unit) {
                offers++
                work()
            }
        }
        drive(
            compile(
                """
                graph "probe"

                on start {
                    walkTo(where: "bank")
                    log(message: "arrived")
                }
                """.trimIndent(),
            ),
            r.hosts,
            actuator = sink,
        )
        assertEquals(1, offers, "the blocking verb did not go through the actuator")
        assertEquals(listOf("walked to bank", "arrived"), r.said)
    }

    // ---- the invariants ------------------------------------------------------------------------------

    /**
     * INVARIANT 1: two entries compiled through one `ProgramBuilder` share one linked program.
     *
     * `FunctionValue.index` indexes that array and `CALLG`/`CALLV` resolve through it, so this is the
     * whole of what keeps cross-surface calling a later decision instead of a foreclosed one. Asserted
     * structurally because it is the kind of thing a refactor breaks silently.
     */
    @Test
    fun `entries compiled together share one program table`() {
        val shared = ProgramBuilder()
        val a = compile("""graph "a"${'\n'}${'\n'}on start { log(message: "a") }""", shared)
        val b = compile("""graph "b"${'\n'}${'\n'}on start { log(message: "b") }""", shared)
        assertSame(a.program, b.program, "the two entries did not land in the same linked program")
    }

    /**
     * INVARIANT 3: a chunk carries its document, so a text module cannot collide with a canvas one.
     *
     * **The document is its REFERENCE, not its `graph` line.** The header used to supply this and was
     * the wrong thing to ask. It is optional, so every file without one WAS the same document — a
     * second library's `helper` resolved to the first's chunk, silently, and the call typechecked and
     * returned the other one's answer. And it is a free string, so two libraries both saying
     * `graph "util"` collided in exactly the same way, which needed no new feature to reach.
     *
     * A reference is unique by construction — a `ModuleSet` is keyed by it — and it is also the more
     * useful answer to what a debugger is really asking, which is *which file is this frame in*.
     *
     * The root arrives as bare text and so has no reference of its own; `rootRef` is how a caller that
     * knows where it came from says so.
     */
    @Test
    fun `a chunk is named for its document`() {
        // No `rootRef` given: the root is the one document nothing names.
        assertEquals("<root>#start", compile("""graph "probe"${'\n'}${'\n'}on start { }""").name)
        // ...and the `graph` line does not name it either — only the caller can.
        assertEquals(
            "scripts/probe#start",
            compile("""on start { }""", rootRef = "scripts/probe").name,
        )
    }

    // ---- it refuses honestly -------------------------------------------------------------------------

    /**
     * What is not built yet says so, and says WHERE.
     *
     * **This fixture is expected to need repointing as features land** — it has already been moved off a
     * list literal, a lambda, a destructuring binding, an import and a `when`, because all five became
     * things the front end compiles. Generic RECORDS are the current target and are genuinely not next. That is the test
     * doing its job: what is under test is the CONTRACT (refuse by name, at a span) rather than the
     * particular construct, and the day nothing is left to point it at is the day it can go.
     *
     * A skeleton that mis-compiles the parts it does not understand is worse than one that refuses them:
     * the failure would surface as a wrong answer at run time, in a script whose author had no reason to
     * suspect the compiler.
     */
    @Test
    fun `an unsupported construct is refused at its own span`() {
        // **This used to be a generic record**, which now compiles — see `GenericRecordTest`. The property
        // under test is not which construct is missing but that a missing one is refused WHERE IT IS
        // WRITTEN, so it moves to whatever is still outstanding rather than being deleted. destructuring by field name is
        // the current example; when it lands, move this again. Note `sequence` is NOT a candidate — it is
        // a canvas construct with nothing to mean in text, and it is refused saying so.
        val read = TextFrontEnd(natives).read(
            """
            graph "probe"

            type Point { x: Int = 0, y: Int = 0 }

            on start {
                val { x, y } = Point { x: 1, y: 2 }
                log(message: "" + x + y)
            }
            """.trimIndent(),
        )
        assertTrue(!read.ok, "a construct nothing here compiles was accepted")
        assertTrue(read.errors.first().message.contains("does not understand"), "unhelpful: ${read.errors}")
        assertTrue(read.errors.first().span.line > 0, "the refusal has no line to point at")
    }

    @Test
    fun `an unknown function is named in the error`() {
        assertTrue(refusals("""graph "probe"${'\n'}${'\n'}on start { nope() }""").any { it.contains("nope") })
    }
}
