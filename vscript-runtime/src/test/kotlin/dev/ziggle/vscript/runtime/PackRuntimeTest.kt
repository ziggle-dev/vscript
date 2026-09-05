package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.text.NativeFn
import dev.ziggle.vscript.text.NativeParam
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.PackCodec
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.vm.FakeClock
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A script that arrived as BYTECODE, run by the real runtime.
 *
 * `ChunkCodecTest` and `CompiledPackTest` prove a pack decodes and that its chunks execute under the test
 * fixture's `drive`. This is the other half: the same pack through [ScriptRuntime.runPack] — the phase
 * machine, the handler groups and the fiber scheduler a live client actually uses — with no source, no
 * compiler and no `Sites` anywhere in the process.
 */
class PackRuntimeTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(
            NativeFn("say", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("rare", emptyList(), results = emptyList()),
        ),
    )

    private val said = ArrayList<String>()

    /** Everything the compiler may lower to, plus our two — a run needs the builtins as much as the verbs. */
    private fun hosts(withRare: Boolean = true): HostRegistry = BuiltinHosts.registry().also { r ->
        r.register("say", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        if (withRare) r.register("rare", HostKind.INLINE, arity = 0, results = 0) { null }
    }

    private val library = """
        graph "pace"

        export fn twice(n: INT) -> INT {
            return n * 2
        }

        always on wake {
            say(message: "library woke")
        }
    """.trimIndent()

    private fun source(callRare: Boolean) = """
        graph "main"

        import * as pace from "core/pace"

        var Seen: INT = 0

        on start {
            Seen = pace::twice(n: 21)
            say(message: "start " + Seen)
            ${if (callRare) "rare()" else ""}
        }
    """.trimIndent()

    private fun packOf(callRare: Boolean = false, strip: Boolean = true): ByteArray {
        val compilation = TextFrontEnd(natives, imports = TextSource.of(mapOf("core/pace" to library)))
            .compile(source(callRare))
        assertTrue(compilation.ok, "fixture: " + compilation.diagnostics.joinToString { it.message })
        return PackCodec.write(compilation, strip)
    }

    private fun runtime(withRare: Boolean = true) =
        ScriptRuntime(NodeCatalog(emptyList()), hosts(withRare), clock = FakeClock())

    private fun ScriptRuntime.runToCompletion(limit: Int = 200) {
        var n = 0
        while (isRunning && n++ < limit) tick()
    }

    // ---- running something that was shipped -----------------------------------------------------------

    @Test
    fun `a stripped pack runs through the real runtime`() {
        val pack = PackCodec.read(packOf())
        val rt = runtime()

        assertNull(rt.runPack(pack, id = "shipped", name = "main"), "runPack reported an error")
        rt.runToCompletion()

        // Both, in this order, because the runtime holds start work behind the wake — see below.
        assertEquals(listOf("library woke", "start 42"), said, "the shipped script did not do its work")
    }

    /**
     * **The wake-before-work rule survives being shipped**, which is the sharpest evidence that nothing
     * downstream can tell a pack from a script compiled here: the runtime holds start work behind the wake
     * handlers, and the handler doing the holding belongs to an IMPORTED library that the pack carried.
     *
     * Get the entry table wrong — drop the library's handlers, or mislabel their kind — and this ordering
     * is the first thing to break, silently, in favour of the root document just running.
     */
    @Test
    fun `an imported library's wake handler runs, and before the root's work`() {
        val pack = PackCodec.read(packOf())
        assertEquals(1, pack.entries.getValue(dev.ziggle.vscript.lang.EntryKind.WAKE).size)
        assertFalse(
            pack.entries.getValue(dev.ziggle.vscript.lang.EntryKind.WAKE).single().isRoot,
            "the wake handler belongs to the imported library, not to main",
        )

        val rt = runtime()
        rt.runPack(pack, id = "shipped", name = "main")
        rt.runToCompletion()

        assertEquals(
            listOf("library woke", "start 42"), said,
            "start work must be held behind the imported library's wake handler",
        )
    }

    // ---- the compatibility gate, where it actually bites -----------------------------------------------

    /**
     * **Refused before anything spawns, and the message names the gap.** Left to `HostRegistry.bind` this
     * would raise at the moment a chunk is spawned — by which point the run has started, other handlers may
     * already be going, and the complaint names one function instead of the set.
     */
    @Test
    fun `a pack calling a verb this host lacks is refused up front`() {
        val pack = PackCodec.read(packOf(callRare = true))
        val rt = runtime(withRare = false)

        val err = rt.runPack(pack, id = "shipped", name = "main")

        assertTrue(err != null && err.contains("rare"), "expected a refusal naming 'rare', got: $err")
        assertFalse(rt.isRunning, "nothing should have been spawned")
        assertTrue(said.isEmpty(), "no handler should have run: $said")
    }

    @Test
    fun `the same pack runs once the host provides the verb`() {
        val pack = PackCodec.read(packOf(callRare = true))
        val rt = runtime(withRare = true)

        assertNull(rt.runPack(pack, id = "shipped", name = "main"))
        rt.runToCompletion()
        assertEquals(listOf("library woke", "start 42"), said)
    }

    // ---- what a pack cannot pretend to be --------------------------------------------------------------

    /**
     * `Sites` is what maps a site id to a line, and a pack never carries one — so a pack run publishes no
     * spans rather than leaving the previous run's, which would anchor this run's complaints to another
     * document's source.
     */
    @Test
    fun `a pack run publishes no source spans`() {
        val rt = runtime()
        rt.runPack(PackCodec.read(packOf()), id = "shipped", name = "main")

        assertNull(rt.textSpans, "a pack has no spans to publish")
        assertNull(rt.textSites, "a pack has no sites to arm breakpoints against")
    }
}
