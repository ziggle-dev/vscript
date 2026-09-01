package dev.ziggle.vscript.test

import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.text.NativeFn
import dev.ziggle.vscript.text.NativeParam
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.vm.HostRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `fake` — standing in for a host, so a test can reach code that touches one.
 *
 * **The problem it solves, stated concretely.** The runner binds the language's builtins and nothing else,
 * so an unbound native fails loudly with a stack. That is right: a test that passed by reading a live
 * account would pass differently tomorrow. But it made a narrower slice testable than it sounds —
 * `scheduler/pick`'s draw factors are pure arithmetic, and two of them read a dashboard knob through
 * `panel.amount`. Nothing about those needs a CLIENT. They need a way to say there is no dashboard.
 *
 * A fake is compiled as an ordinary function and SUBSTITUTED at the call site, which is what lets it reach
 * a call two documents away — where nearly all of them are.
 */
class FakeHostTest {

    /** A catalogue with two hosts nothing binds, so a test that reaches one fails unless it fakes it. */
    private val natives: NativeTable = NativeTable(
        NodeCatalog(dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS).natives().all + listOf(
            NativeFn(
                // The CALL spelling; `host` below is what it binds to. The real catalogue registers both.
                "amount",
                listOf(
                    NativeParam("id", dev.ziggle.vscript.model.TypeRef(dev.ziggle.vscript.model.PinType.STRING)),
                    NativeParam("default", dev.ziggle.vscript.model.TypeRef(dev.ziggle.vscript.model.PinType.INT)),
                ),
                results = listOf(NativeParam("Amount", dev.ziggle.vscript.model.TypeRef(dev.ziggle.vscript.model.PinType.INT))),
                kind = HostKind.INLINE,
                host = "panel.amount",
            ),
            NativeFn(
                "walkTo",
                listOf(NativeParam("tile", dev.ziggle.vscript.domain.TileFixture.TYPE)),
                results = listOf(NativeParam("Ok", dev.ziggle.vscript.model.TypeRef(dev.ziggle.vscript.model.PinType.BOOL))),
                kind = HostKind.BLOCKING,
                host = "movement.walkTo",
            ),
        )
    )

    private fun hosts(): HostRegistry = BuiltinHosts.registry()

    private fun run(src: String, others: Map<String, String> = emptyMap(), ref: String = "probe_test"): TestReport {
        val front = TextFrontEnd(natives, imports = TextSource.of(others), rootRef = ref)
        val compiled = front.compileTests(src)
        assertTrue(compiled.ok, "did not compile: " + compiled.errors.joinToString { "${it.span} ${it.message}" })
        return VsTestRunner(::hosts).run(compiled)
    }

    /** Without a fake, the unbound host is a loud failure — the behaviour a fake is the exception to. */
    @Test
    fun `an unfaked host still fails loudly`() {
        val r = run(
            """
            test "reads a knob" { assert amount(id: "k", default: 7) == 7 }
            """.trimIndent()
        )
        val f = r.failed.single()
        assertTrue("panel.amount" in f.message.orEmpty(), f.message.orEmpty())
    }

    @Test
    fun `a fake stands in for the host and can hand back an argument`() {
        val r = run(
            """
            fake panel.amount(id: String, default: Int) -> Int = default

            test "an untouched dashboard gives the fallback" { assert amount(id: "k", default: 7) == 7 }
            test "and does it for any fallback" { assert amount(id: "j", default: 12) == 12 }
            """.trimIndent()
        )
        assertEquals(2, r.passed.size, r.toString())
    }

    /** A fake has a real body, not a constant — the whole reason it is compiled as a function. */
    @Test
    fun `a fake body may compute`() {
        val r = run(
            """
            fake panel.amount(id: String, default: Int) -> Int {
                if id == "double" {
                    return default * 2
                }
                return default
            }

            test "computed" {
                assert amount(id: "double", default: 21) == 42
                assert amount(id: "plain", default: 21) == 21
            }
            """.trimIndent()
        )
        assertTrue(r.ok, r.toString())
    }

    /**
     * **The case it exists for**: the call is in another document, which the test never edits.
     *
     * A fake that only applied where it was written would substitute nothing anywhere it was needed.
     */
    @Test
    fun `a fake reaches a call site in an imported document`() {
        val lib = """
            export fn knob(key: String, fallback: Int) -> Int = amount(id: key, default: fallback)
            export fn eased() -> Int = knob(key: "ease", fallback: 70)
            export { knob, eased }
        """.trimIndent()
        val r = run(
            """
            import "lib"
            fake panel.amount(id: String, default: Int) -> Int = default

            test "the library's own host call is faked too" { assert eased() == 70 }
            """.trimIndent(),
            mapOf("lib" to lib),
        )
        assertTrue(r.ok, r.toString())
    }

    /** A BLOCKING host is faked too, and a fake never blocks — there is nothing for it to block on. */
    @Test
    fun `an acting host can be faked`() {
        val r = run(
            """
            fake movement.walkTo(tile: Tile) -> Bool = true

            test "the walk is a stub" { assert walkTo(tile: Tile { x: 1, y: 2, plane: 0 }) }
            """.trimIndent()
        )
        assertTrue(r.ok, r.toString())
    }

    /** Each test gets its own bindings, and a fake is compiled once for all of them. */
    @Test
    fun `a fake is shared by every test in the document`() {
        val r = run(
            """
            fake panel.amount(id: String, default: Int) -> Int = 99

            test "one" { assert amount(id: "a", default: 1) == 99 }
            test "two" { assert amount(id: "b", default: 2) == 99 }
            """.trimIndent()
        )
        assertEquals(2, r.passed.size, r.toString())
    }

    /** A fake outside a test document would replace a host for everything importing it. Refused. */
    @Test
    fun `a fake is refused outside a test document`() {
        val front = TextFrontEnd(natives, rootRef = "ordinary")
        val read = front.read(
            """
            fake panel.amount(id: String, default: Int) -> Int = default
            on start { }
            """.trimIndent()
        )
        assertTrue(
            read.errors.any { "only be written in a test document" in it.message },
            read.errors.joinToString { it.message },
        )
    }

    /** And a normal compile of a test document never applies one — a fake exists while tests run. */
    @Test
    fun `an ordinary compile does not substitute a fake`() {
        val compiled = TextFrontEnd(natives, rootRef = "probe_test").read(
            """
            fake panel.amount(id: String, default: Int) -> Int = default
            on start { log(message: "" + amount(id: "k", default: 7)) }
            """.trimIndent()
        )
        assertTrue(compiled.ok, compiled.errors.joinToString { it.message })
        val hosts = BuiltinHosts.registry()
        val e = kotlin.runCatching { dev.ziggle.vscript.vm.drive(compiled.chunk!!, hosts) }.exceptionOrNull()
        assertTrue(
            "panel.amount" in (e?.message ?: ""),
            "an ordinary run should still reach the real host, got: ${e?.message}",
        )
    }
}
