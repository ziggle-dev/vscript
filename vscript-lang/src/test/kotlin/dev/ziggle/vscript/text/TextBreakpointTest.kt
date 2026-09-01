package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.Interpreter
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.PauseReason
import dev.ziggle.vscript.vm.Scheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A text program stopping where it was told to.
 *
 * Not a formality. Breakpoints and stepping happen at an `Op.TRACE` marker and NOWHERE else, so a compiler
 * that emits none produces a program that runs perfectly and cannot be debugged at all — and every
 * symptom of that ("my breakpoint does nothing") points at the debugger rather than at the compiler.
 */
class TextBreakpointTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private val script = """
        graph "breaks"
        on start {
            log(message: "one")
            log(message: "two")
            log(message: "three")
        }
    """.trimIndent()

    /**
     * Compile once and hand back both halves.
     *
     * ONCE, deliberately: sites are keyed by AST identity, so a second `read` of the same text parses a
     * second tree and mints a second set of ids. Arming one set and running the other is a breakpoint that
     * silently never fires — which is exactly what this test is here to catch, so it must not do it itself.
     */
    private fun compile(front: TextFrontEnd): Chunk {
        val read = front.read(script)
        return read.chunk ?: fail(front.describe(read))
    }

    /** Drive [chunk] until it finishes or pauses, with [armed] as the breakpoints. */
    private fun drive(chunk: Chunk, armed: List<Int> = emptyList()): Pair<List<String>, dev.ziggle.vscript.vm.Fiber> {
        val said = ArrayList<String>()
        val hosts = HostRegistry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val clock = FakeClock()
        val interpreter = Interpreter(hosts, clock)
        interpreter.resetGlobals(chunk.globals)
        for (id in armed) interpreter.breakpoints.add(id)
        val scheduler = Scheduler(interpreter, clock)
        val fiber = scheduler.spawn("test", chunk)
        var ticks = 0
        while (!fiber.isFinished && ticks < 1000) {
            ticks++
            scheduler.tick()
            if (fiber.state == FiberState.PAUSED) break
            if (!fiber.isFinished) scheduler.nextWakeMs()?.let { clock.now += it }
        }
        return said to fiber
    }

    @Test
    fun `a breakpoint on a line stops the program there`() {
        val front = TextFrontEnd(natives)
        val chunk = compile(front)
        // Line 4 is `log(message: "two")`.
        val site = assertNotNull(front.sites.siteAt(Resolution.ROOT, 4), "nothing to break on at line 4")

        val (said, fiber) = drive(chunk, listOf(site))
        assertEquals(FiberState.PAUSED, fiber.state)
        assertEquals(PauseReason.BREAKPOINT, fiber.pauseReason)
        // Stopped BEFORE the statement it was armed on ran.
        assertEquals(listOf("one"), said)
        // And the frame can say where it is, which is the other half of what a marker buys.
        assertEquals(site, fiber.currentNode)
        assertEquals(4, front.sites.spanOf(fiber.currentNode)?.line)
    }

    @Test
    fun `with nothing armed it runs to the end`() {
        val front = TextFrontEnd(natives)
        val (said, fiber) = drive(compile(front))
        assertTrue(fiber.isFinished, "paused with no breakpoint armed")
        assertEquals(listOf("one", "two", "three"), said)
    }

    /**
     * A breakpoint inside a LIBRARY, while a different script is what is running.
     *
     * The case a debugger is actually used for: you are running `rotation`, the fault is somewhere in
     * `core/breaks`, and you want to stop in the file that has the bug rather than at the line that called
     * into it. It works for two reasons that both had to be decided deliberately — sites are numbered once
     * across the whole closure, so a library's lines have ids of their own that cannot collide with the
     * script's, and every document of the run is compiled with the same `debug` flag, so a library's
     * chunks carry markers too.
     */
    @Test
    fun `a breakpoint inside an imported library stops the run`() {
        val library = """
            graph "lib"
            export fn shout(what: STRING) {
                log(message: "lib: " + what)
                log(message: "lib done")
            }
        """.trimIndent()
        val front = TextFrontEnd(natives, imports = TextSource.of(mapOf("lib" to library)))
        val main = """
            graph "main"
            import { shout } from "lib"
            on start {
                log(message: "before")
                shout(what: "hello")
                log(message: "after")
            }
        """.trimIndent()
        val read = front.read(main)
        val chunk = read.chunk ?: fail(front.describe(read))

        // Line 4 of the LIBRARY — `log(message: "lib done")`, inside a function the script calls.
        val site = assertNotNull(front.sites.siteAt("lib", 4), "nothing to break on at lib:4")
        assertEquals("lib", front.sites.documentOf(site))

        val (said, fiber) = drive(chunk, listOf(site))
        assertEquals(FiberState.PAUSED, fiber.state)
        assertEquals(PauseReason.BREAKPOINT, fiber.pauseReason)
        // Reached through the call, and stopped before the line it was armed on.
        assertEquals(listOf("before", "lib: hello"), said)
        // The frame can name the file, which is what lets an editor open the right one.
        assertEquals(site, fiber.currentNode)
        assertEquals("lib", front.sites.documentOf(fiber.currentNode))
        assertEquals(4, front.sites.spanOf(fiber.currentNode)?.line)
    }

    @Test
    fun `the same line number in two documents is two different sites`() {
        val library = """
            graph "lib"
            export fn helper() {
                log(message: "lib line three")
            }
        """.trimIndent()
        val front = TextFrontEnd(natives, imports = TextSource.of(mapOf("lib" to library)))
        val read = front.read(
            """
            graph "main"
            import { helper } from "lib"
            on start {
                log(message: "main line four")
                helper()
            }
            """.trimIndent(),
        )
        assertNotNull(read.chunk, front.describe(read))

        // Both files have code on line 3, and a breakpoint means one of them. A table keyed by line alone
        // could not say which — which is why a span row carries its document.
        val inLib = assertNotNull(front.sites.siteAt("lib", 3))
        val inMain = assertNotNull(front.sites.siteAt(Resolution.ROOT, 3))
        assertTrue(inLib != inMain, "one site is claiming both files' line 3")
    }

    @Test
    fun `a release build emits no markers at all`() {
        val chunk = compile(TextFrontEnd(natives, debug = false))
        assertTrue(
            chunk.code.toList().chunked(4).none { it[0] == Op.TRACE },
            "a release build still emitted trace markers",
        )
    }

    @Test
    fun `a debug build marks every statement once`() {
        val front = TextFrontEnd(natives)
        val chunk = compile(front)
        val marked = chunk.code.toList().chunked(4).filter { it[0] == Op.TRACE }.map { it[1] }
        // Three statements in the handler, each marked once, each a site with a line.
        assertEquals(3, marked.size, "markers: $marked")
        assertEquals(marked.size, marked.toSet().size, "two statements share a site")
        assertEquals(listOf(3, 4, 5), marked.map { front.sites.spanOf(it)?.line })
    }
}
