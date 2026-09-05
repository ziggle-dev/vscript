package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pack is a program plus the table that says what to run — and it must survive with no source, no
 * compiler and no `Resolution` behind it.
 */
class CompiledPackTest {

    private val STRING = TypeRef(PinType.STRING)

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("beep", emptyList(), results = emptyList()),
        ),
    )

    /** A library with a handler of its own, so the pack's entry list is not just the root document's. */
    private val pace = """
        graph "pace"

        export fn twice(n: INT) -> INT {
            return n * 2
        }

        always on wake {
            log(message: "pace woke")
        }
    """.trimIndent()

    private val main = """
        graph "main"

        import * as pace from "core/pace"

        var Seen: INT = 0

        on start {
            Seen = pace::twice(n: 21)
            log(message: "start " + Seen)
        }

        on stop {
            log(message: "stop")
        }
    """.trimIndent()

    private fun compile(source: String = main) =
        TextFrontEnd(natives, imports = TextSource.of(mapOf("core/pace" to pace)))
            .compile(source)
            .also { assertTrue(it.ok, "did not compile: " + it.diagnostics.joinToString { d -> d.message }) }

    private fun pack(strip: Boolean = false) = PackCodec.read(PackCodec.write(compile(), strip))

    private fun say(sink: MutableList<String>) = HostRegistry().apply {
        register("log", HostKind.INLINE, arity = 1, results = 0) { a -> sink += a[0].toString(); null }
        register("beep", HostKind.INLINE, arity = 0, results = 0) { null }
    }

    // ---- the table -----------------------------------------------------------------------------------

    @Test
    fun `handlers come back grouped by kind, including a library's own`() {
        val p = pack()

        assertTrue(EntryKind.START in p.entries, "no start handler: ${p.entries.keys}")
        assertTrue(EntryKind.STOP in p.entries, "no stop handler: ${p.entries.keys}")
        assertTrue(EntryKind.WAKE in p.entries, "the imported document's `always on wake` is missing")

        // The wake handler belongs to the LIBRARY, not to the document the pack was built from — which is
        // the case `isRoot` exists to distinguish.
        val wake = p.entries.getValue(EntryKind.WAKE).single()
        assertTrue(wake.document.contains("pace"), "wake came from ${wake.document}")
        assertFalse(wake.isRoot, "a library's handler is not the root document's")
    }

    @Test
    fun `a decoded pack runs each handler and gets the original answers`() {
        val p = pack()

        val started = ArrayList<String>()
        drive(p.entries.getValue(EntryKind.START).single().chunk, say(started))
        assertEquals(listOf("start 42"), started)

        val stopped = ArrayList<String>()
        drive(p.entries.getValue(EntryKind.STOP).single().chunk, say(stopped))
        assertEquals(listOf("stop"), stopped)

        val woke = ArrayList<String>()
        drive(p.entries.getValue(EntryKind.WAKE).single().chunk, say(woke))
        assertEquals(listOf("pace woke"), woke)
    }

    @Test
    fun `the run's starting globals survive`() {
        val original = compile()
        val p = PackCodec.read(PackCodec.write(original))
        assertEquals(original.globals, p.globals)
    }

    // ---- the compatibility gate ----------------------------------------------------------------------

    /**
     * **This is what replaces a version check.** A host either has the verbs or it does not, and the pack
     * says which ones it needs — so a loader can refuse before spawning anything and name the gap, rather
     * than failing at whichever instruction happens to reach it.
     */
    @Test
    fun `a pack names every host verb it needs`() {
        val needed = pack().requiredHosts()
        assertTrue("log" in needed, "log is called and must be required: $needed")
        assertFalse("beep" in needed, "beep is declared but never called, so nothing requires it: $needed")
    }

    @Test
    fun `a host missing a required verb is detectable without running anything`() {
        val p = pack()
        val half = HostRegistry().apply {
            register("beep", HostKind.INLINE, arity = 0, results = 0) { null }
        }
        assertEquals(setOf("log"), p.requiredHosts() - half.names)
    }

    // ---- refusals ------------------------------------------------------------------------------------

    /** A pack of a broken compilation would look shippable and hold a subset of a program. */
    @Test
    fun `refuses to pack a compilation that did not compile`() {
        val broken = TextFrontEnd(natives).compile("graph \"x\"\n\non start { nope() }")
        assertFalse(broken.ok, "fixture should not compile")
        val e = assertFailsWith<IllegalArgumentException> { PackCodec.write(broken) }
        assertTrue(e.message!!.contains("refusing to pack"), "unhelpful message: ${e.message}")
    }

    @Test
    fun `rejects a file that is not a pack`() {
        val e = assertFailsWith<IllegalArgumentException> { PackCodec.read("nope".toByteArray()) }
        assertTrue(e.message!!.contains("not a compiled vs pack"), "unhelpful message: ${e.message}")
    }

    /** A program image is not a pack; the two magics keep them from being mistaken for one another. */
    @Test
    fun `rejects a bare program image`() {
        val original = compile()
        val chunk = original.entries.getValue(EntryKind.START).single().chunk
        val image = dev.ziggle.vscript.vm.ChunkCodec.write(
            dev.ziggle.vscript.vm.ProgramImage(chunk.program, listOf(chunk)),
        )
        assertFailsWith<IllegalArgumentException> { PackCodec.read(image) }
    }

    // ---- stripping -----------------------------------------------------------------------------------

    @Test
    fun `a stripped pack drops the names and still runs every handler`() {
        val p = pack(strip = true)
        assertTrue(p.stripped)

        for (c in p.allChunks) {
            assertTrue(c.slots.isEmpty, "chunk ${c.name} still names things")
            assertTrue(c.literalSlots.isEmpty(), "chunk ${c.name} still carries literal slots")
        }
        for (e in p.entries.values.flatten()) {
            assertEquals(-1, e.site, "a stripped entry cannot point at source it does not carry")
        }

        val said = ArrayList<String>()
        drive(p.entries.getValue(EntryKind.START).single().chunk, say(said))
        assertEquals(listOf("start 42"), said)
    }

    /**
     * `test` bytecode is never in a pack — `TextFrontEnd.compile` skips the kind entirely, so a shipped
     * script cannot carry its own test suite even by accident.
     */
    @Test
    fun `tests are not packed`() {
        val withTest = """
            graph "main"

            on start { log(message: "hi") }

            test "arithmetic still works" {
                assert 1 + 1 == 2
            }
        """.trimIndent()
        val p = PackCodec.read(PackCodec.write(compile(source = withTest)))
        assertFalse(EntryKind.TEST in p.entries, "a pack must not carry test bytecode")
    }
}
