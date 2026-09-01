package dev.ziggle.vscript.test

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.text.NativeFn
import dev.ziggle.vscript.text.NativeParam
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.vm.HostKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `test "…" { assert … }`, end to end: source in, pass or fail out.
 *
 * The report is as much the feature as the running is. A failure that says only "assert returned false"
 * sends the reader back to count statements, so the parser keeps the written text and the compiler
 * evaluates both sides of a comparison into registers — and the two together are what make the message
 * name the check and say what each side held.
 */
class VsTestRunnerTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    private fun hosts() = BuiltinHosts.registry()
        .register("log", HostKind.INLINE, arity = 1, results = 0) { _ -> null }

    private fun run(src: String, others: Map<String, String> = emptyMap()): TestReport {
        val front = TextFrontEnd(natives, imports = TextSource.of(others))
        val compiled = front.compileTests(src)
        assertTrue(compiled.ok, "did not compile: " + compiled.errors.joinToString { "${it.span} ${it.message}" })
        return VsTestRunner(::hosts).run(compiled)
    }

    @Test
    fun `a passing test passes and a failing one fails`() {
        val r = run(
            """
            graph "p"

            fn double(n: Int) -> Int = n * 2

            test "doubling two is four" {
                assert double(n: 2) == 4
            }

            test "doubling two is five" {
                assert double(n: 2) == 5
            }
            """.trimIndent(),
        )
        assertEquals(2, r.outcomes.size)
        assertEquals(listOf("doubling two is four"), r.passed.map { it.name })
        assertEquals(listOf("doubling two is five"), r.failed.map { it.name })
        assertEquals("2 tests, 1 passed, 1 failed", r.summary)
    }

    @Test
    fun `a failure quotes what was written and what each side held`() {
        val r = run(
            """
            graph "p"

            fn double(n: Int) -> Int = n * 2

            test "the message is useful" {
                assert double(n: 2) == 5
            }
            """.trimIndent(),
        )
        val m = r.failed.single().message.orEmpty()
        assertTrue("double(n: 2) == 5" in m, "the check should be quoted as written: $m")
        assertTrue("left:  4" in m, "the left side's value should be reported: $m")
        assertTrue("right: 5" in m, "the right side's value should be reported: $m")
    }

    @Test
    fun `an author's own message replaces the quoted text`() {
        val r = run(
            """
            graph "p"

            test "named" {
                assert 1 == 2, "one is not two"
            }
            """.trimIndent(),
        )
        assertTrue("one is not two" in r.failed.single().message.orEmpty())
    }

    /** A condition with no sides still reports; there is simply nothing to say about operands. */
    @Test
    fun `a non-comparison condition reports the text alone`() {
        val r = run(
            """
            graph "p"

            fn ready() -> Bool = false

            test "not ready" {
                assert ready()
            }
            """.trimIndent(),
        )
        val m = r.failed.single().message.orEmpty()
        assertTrue("ready()" in m, m)
        assertTrue("left:" !in m, "there are no sides to report: $m")
    }

    /** A `delay` inside a test must not cost real time — the clock is advanced, not waited on. */
    @Test
    fun `a test that delays still finishes instantly`() {
        val started = System.currentTimeMillis()
        val r = run(
            """
            graph "p"

            test "waits a minute" {
                delay(ms: 60000)
                assert 1 == 1
            }
            """.trimIndent(),
        )
        assertTrue(r.ok, r.toString())
        assertTrue(System.currentTimeMillis() - started < 5_000, "the clock should be advanced, not awaited")
    }

    /** Each test gets its own globals, so the suite's result cannot depend on the order it ran in. */
    @Test
    fun `one test cannot see what another wrote`() {
        val r = run(
            """
            graph "p"

            var Count: Int = 0

            test "a bumps the count" {
                Count = Count + 1
                assert Count == 1
            }

            test "b sees a fresh count" {
                Count = Count + 1
                assert Count == 1
            }
            """.trimIndent(),
        )
        assertTrue(r.ok, r.toString())
    }

    /** A test may exercise something it imported, which is the whole point of having them. */
    @Test
    fun `a test can exercise an imported function`() {
        val r = run(
            """
            graph "p"

            import { triple } from "lib"

            test "tripling two is six" {
                assert triple(n: 2) == 6
            }
            """.trimIndent(),
            mapOf("lib" to "graph \"lib\"\n\nexport fn triple(n: Int) -> Int = n * 3\n"),
        )
        assertTrue(r.ok, r.toString())
    }

    /** A runaway test ends as a failure that names itself, never as a hung build. */
    @Test
    fun `a test that never finishes gives up and says so`() {
        val compiled = TextFrontEnd(natives).compileTests(
            """
            graph "p"

            test "spins" {
                while true { }
            }
            """.trimIndent(),
        )
        assertTrue(compiled.ok, compiled.errors.joinToString { it.message })
        val f = VsTestRunner(::hosts, maxTicks = 50).run(compiled).failed.single()
        assertEquals("spins", f.name)
        assertTrue("gave up" in f.message.orEmpty(), f.message.orEmpty())
    }

    /** Tests are typechecked on the ordinary path, and NOT emitted there. */
    @Test
    fun `a normal compile checks tests but does not emit them`() {
        val normal = TextFrontEnd(natives).compile(
            """
            graph "p"

            test "checked" {
                assert 1 == 1
            }

            on start { log(message: "hi") }
            """.trimIndent(),
        )
        assertTrue(normal.ok, normal.errors.joinToString { it.message })
        assertTrue(
            normal.entries.keys.none { it.name == "TEST" },
            "a run carries no test bytecode: " + normal.entries.keys,
        )

        val broken = TextFrontEnd(natives).compile(
            """
            graph "p"

            test "broken" {
                assert nosuchthing() == 1
            }

            on start { log(message: "hi") }
            """.trimIndent(),
        )
        assertTrue(!broken.ok, "a test that stopped compiling must fail an ordinary check too")
    }
}
