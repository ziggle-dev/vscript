package dev.ziggle.vscript.vm

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.text.NativeFn
import dev.ziggle.vscript.text.NativeParam
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A compiled program survives being written down and read back.
 *
 * **The tests that matter here RUN the decoded program**, rather than comparing fields. A codec that gets
 * every field right and one operand index wrong produces a byte-identical-looking image that executes
 * something else, and only running it says so.
 */
class ChunkCodecTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())),
    )

    /** A leaf library — imported by `core/plan`, and therefore reached only transitively from `main`. */
    private val pace = """
        graph "pace"

        export var IdleMs: INT = 700

        export fn twice(n: INT) -> INT {
            return n * 2
        }
    """.trimIndent()

    /** A middle library, so the closure under test is two deep rather than one. */
    private val plan = """
        graph "plan"

        import * as pace from "core/pace"

        export fn quadruple(n: INT) -> INT {
            return pace::twice(n: pace::twice(n: n))
        }
    """.trimIndent()

    private val documents = mapOf("core/pace" to pace, "core/plan" to plan)

    private val main = """
        graph "main"

        import * as plan from "core/plan"
        import * as pace from "core/pace"

        var Seen: INT = 0

        on start {
            Seen = plan::quadruple(n: 3)
            log(message: "quad=" + Seen)
            log(message: "idle=" + pace::IdleMs)
            log(message: "twice=" + pace::twice(n: 21))
        }
    """.trimIndent()

    private fun hosts(sink: MutableList<String>) = HostRegistry().apply {
        register("log", HostKind.INLINE, arity = 1, results = 0) { a -> sink += a[0].toString(); null }
    }

    private fun compile(): Chunk {
        val r = TextFrontEnd(natives, imports = TextSource.of(documents)).read(main)
        return r.chunk ?: fail("did not compile: " + r.diagnostics.joinToString { it.message })
    }

    private fun runIt(entry: Chunk): List<String> {
        val said = ArrayList<String>()
        drive(entry, hosts(said))
        return said
    }

    // ---- the decisive one ----------------------------------------------------------------------------

    @Test
    fun `a decoded program runs identically, imports and all`() {
        val original = compile()
        val before = runIt(original)
        assertEquals(listOf("quad=12", "idle=700", "twice=42"), before, "fixture itself must work")

        val bytes = ChunkCodec.write(ProgramImage(original.program, listOf(original)))
        val decoded = ChunkCodec.read(bytes)

        val entry = decoded.roots.single()
        assertEquals(before, runIt(entry), "the decoded program must compute exactly what the original did")
    }

    /**
     * **The import closure is IN the image, and nothing had to gather it.** `main` calls
     * `plan::quadruple`, which calls `pace::twice` — two documents deep, neither of them named by the
     * root's own chunk — and the transitive one still answers after a round-trip. Its absence would not be
     * a missing file, it would be a `CALLG` into an index that is not there.
     */
    @Test
    fun `every function of every imported document survives`() {
        val original = compile()
        val decoded = ChunkCodec.read(ChunkCodec.write(ProgramImage(original.program, listOf(original))))

        assertEquals(
            original.program.map { it.name },
            decoded.functions.map { it.name },
            "the shared function table must come back whole and in order",
        )
        val names = decoded.functions.map { it.name }
        assertTrue(names.any { it.contains("pace") }, "the leaf library's functions are missing: $names")
        assertTrue(names.any { it.contains("plan") }, "the middle library's functions are missing: $names")
    }

    /**
     * Every chunk must point at the SAME table, exactly as `ProgramBuilder.link` leaves it — a root whose
     * `program` is a different array than its callees' resolves `CALLG` against a table nobody else agrees
     * with, which is the kind of fault that only shows up on a call from deep inside a helper.
     */
    @Test
    fun `every chunk shares one function table`() {
        val original = compile()
        val decoded = ChunkCodec.read(ChunkCodec.write(ProgramImage(original.program, listOf(original))))

        val entry = decoded.roots.single()
        assertEquals(decoded.functions.size, entry.program.size)
        for (c in decoded.functions) {
            assertSame(entry.program, c.program, "chunk ${c.name} points at a different table")
        }
    }

    // ---- values --------------------------------------------------------------------------------------

    @Test
    fun `every value the pool can hold round-trips, nested`() {
        val struct = StructValue("Beat", listOf("ms", "label"), arrayOf(50, "tick"))
        val constants = arrayOf<Any?>(
            null, 7, 9_000_000_000L, 1.5, true, false, "hello",
            arrayListOf(1, "two", null, arrayListOf(3.0)),
            linkedMapOf<Any?, Any?>("a" to 1, 2 to arrayListOf("b")),
            struct,
        )
        val c = Chunk(name = "vals", code = IntArray(0), constants = constants, maxRegs = 0)
        val back = ChunkCodec.read(ChunkCodec.write(ProgramImage(arrayOf(c), listOf(c)))).functions[0]

        assertEquals(constants.size, back.constants.size)
        for (i in constants.indices) {
            if (constants[i] is StructValue) continue
            assertEquals(constants[i], back.constants[i], "constant $i")
        }
        val s = back.constants.last() as StructValue
        assertEquals("Beat", s.type)
        assertEquals(listOf("ms", "label"), s.names)
        assertEquals(50, s[0])
        assertEquals("tick", s[1])
    }

    /**
     * Containers come back MUTABLE. A `single` holding a list is mutated by the script that owns it, so an
     * immutable one turns the first `add` into a runtime failure on a path no equality check would take.
     */
    @Test
    fun `decoded containers are mutable`() {
        val c = Chunk(
            name = "m", code = IntArray(0), maxRegs = 0,
            constants = arrayOf<Any?>(arrayListOf(1), linkedMapOf<Any?, Any?>("k" to 1)),
        )
        val back = ChunkCodec.read(ChunkCodec.write(ProgramImage(arrayOf(c), listOf(c)))).functions[0]

        @Suppress("UNCHECKED_CAST") val list = back.constants[0] as MutableList<Any?>
        @Suppress("UNCHECKED_CAST") val map = back.constants[1] as MutableMap<Any?, Any?>
        list.add(2)
        map["j"] = 2
        assertEquals(listOf<Any?>(1, 2), list)
        assertEquals(2, map.size)
    }

    /**
     * A live host value in a constant pool is a COMPILER bug, and the write is where the chunk that did it
     * is still in hand. Writing null instead would ship a program with a hole that misbehaves elsewhere.
     */
    @Test
    fun `refuses to serialize a live host value`() {
        val c = Chunk(name = "bad", code = IntArray(0), constants = arrayOf<Any?>(Any()), maxRegs = 0)
        val e = assertFailsWith<IllegalArgumentException> {
            ChunkCodec.write(ProgramImage(arrayOf(c), listOf(c)))
        }
        assertTrue(e.message!!.contains("constant pool"), "unhelpful message: ${e.message}")
    }

    /**
     * **A pool is not only the values a document writes.** The compiler parks its own machinery there too
     * — a record's shape so an instruction can build one, a function reference, a JSON schema an `as` cast
     * decodes against. The first cut of this codec assumed otherwise and rejected two perfectly ordinary
     * scripts while telling them their compiler had done something wrong; every one of these was found by
     * packing a real corpus rather than by reasoning about it.
     */
    @Test
    fun `the compiler's own pool entries round-trip`() {
        val schema = dev.ziggle.vscript.json.JsonSchema(
            root = dev.ziggle.vscript.json.JsonSchema.Shape.Record(
                "Doc",
                listOf(
                    dev.ziggle.vscript.json.JsonSchema.Field(
                        "itemCount", "item_count",
                        dev.ziggle.vscript.json.JsonSchema.Shape.Scalar(dev.ziggle.vscript.json.JsonSchema.Kind.INT),
                    ),
                    dev.ziggle.vscript.json.JsonSchema.Field(
                        "tags", "tags",
                        dev.ziggle.vscript.json.JsonSchema.Shape.ListOf(
                            dev.ziggle.vscript.json.JsonSchema.Shape.Optional(
                                dev.ziggle.vscript.json.JsonSchema.Shape.Ref("Tag"),
                            ),
                        ),
                    ),
                ),
            ),
            records = mapOf(
                "Tag" to dev.ziggle.vscript.json.JsonSchema.Shape.Record("Tag", emptyList()),
            ),
        )
        val constants = arrayOf<Any?>(
            StructShape("Beat", listOf("ms", "label")),
            FunctionValue(3, "double"),
            FunctionValue(7, "closure", listOf(1, "two")),
            schema,
        )
        val c = Chunk(name = "pool", code = IntArray(0), constants = constants, maxRegs = 0)
        val back = ChunkCodec.read(ChunkCodec.write(ProgramImage(arrayOf(c), listOf(c)))).functions[0]

        val shape = back.constants[0] as StructShape
        assertEquals("Beat", shape.type)
        assertEquals(listOf("ms", "label"), shape.names)

        // FunctionValue's equals covers index and captured, which is what identity means for one.
        assertEquals(constants[1], back.constants[1])
        assertEquals(constants[2], back.constants[2])

        val s2 = back.constants[3] as dev.ziggle.vscript.json.JsonSchema
        val root = s2.root as dev.ziggle.vscript.json.JsonSchema.Shape.Record
        assertEquals("Doc", root.type)
        assertEquals(listOf("itemCount", "tags"), root.fields.map { it.name })
        assertEquals("item_count", root.fields[0].key, "a renamed JSON key must survive")
        assertEquals(setOf("Tag"), s2.records.keys)
    }

    // ---- refusing what it cannot read ----------------------------------------------------------------

    @Test
    fun `rejects a file that is not a compiled program`() {
        val e = assertFailsWith<IllegalArgumentException> { ChunkCodec.read("not bytecode".toByteArray()) }
        assertTrue(e.message!!.contains("not a compiled vs program"), "unhelpful message: ${e.message}")
    }

    @Test
    fun `rejects a format it does not know rather than guessing`() {
        val c = Chunk(name = "x", code = IntArray(0), constants = emptyArray(), maxRegs = 0)
        val bytes = ChunkCodec.write(ProgramImage(arrayOf(c), listOf(c)))
        // Byte 7 is the low byte of the format int: bump it past anything this build reads.
        bytes[7] = (ChunkCodec.FORMAT + 1).toByte()

        val e = assertFailsWith<IllegalArgumentException> { ChunkCodec.read(bytes) }
        assertTrue(e.message!!.contains("rebuild it from source"), "unhelpful message: ${e.message}")
    }

    // ---- debug info -----------------------------------------------------------------------------------

    // ---- what a shipped image discloses ---------------------------------------------------------------

    /**
     * **`debug = false` is NOT an anonymiser**, and this is here so nobody assumes it is again. It
     * suppresses the debugger's TRACE markers; the compiler still records every document variable's name in
     * `slots.variables`, still keys `literalSlots` by pin, and still emits a site id per instruction. A pack
     * built by simply compiling with debug off would ship identifiers.
     */
    @Test
    fun `debug off still leaves names in the chunk`() {
        val r = TextFrontEnd(natives, imports = TextSource.of(documents), debug = false).read(main)
        val original = assertNotNull(r.chunk, "did not compile: " + r.diagnostics.joinToString { it.message })

        assertTrue(
            original.slots.variables.keys.any { it == "Seen" },
            "expected the document's variable names to survive a debug-off compile: ${original.slots.variables.keys}",
        )
    }

    /**
     * `strip` is what actually removes them — and the program still runs, which is the only reason it is
     * safe to. Everything execution reads (code, constants, host names, globals, handlers) is untouched.
     */
    @Test
    fun `a stripped image drops the names and still runs`() {
        val original = compile()
        val expected = runIt(original)

        val decoded = ChunkCodec.read(
            ChunkCodec.write(ProgramImage(original.program, listOf(original)), strip = true),
        )
        val entry = decoded.roots.single()

        for (c in decoded.functions + entry) {
            assertTrue(c.slots.isEmpty, "chunk ${c.name} still names things")
            assertTrue(c.nodeIds.isEmpty(), "chunk ${c.name} still carries site ids")
            assertTrue(c.literalSlots.isEmpty(), "chunk ${c.name} still carries literal slots")
        }
        assertEquals(expected, runIt(entry), "a stripped program must compute exactly what the original did")
    }

    /**
     * What strip CANNOT reach, stated as a test so the limit is documented where it will be noticed: a
     * chunk's name is a document reference, and a runtime error names it. A stripped pack still discloses
     * the shape of the import tree.
     */
    @Test
    fun `strip does not hide the document names`() {
        val original = compile()
        val decoded = ChunkCodec.read(
            ChunkCodec.write(ProgramImage(original.program, listOf(original)), strip = true),
        )
        assertTrue(
            decoded.functions.any { it.name.contains("pace") },
            "document refs are still in chunk names, by design — see ChunkCodec's class note",
        )
    }
}
