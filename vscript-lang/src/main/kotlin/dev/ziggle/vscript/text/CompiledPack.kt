package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.EntryKind
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.ChunkCodec
import dev.ziggle.vscript.vm.ProgramImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * One entry point in a [CompiledPack] — a handler, and enough about it to dispatch and to report on.
 *
 * The same shape as [TextEntry] minus the things a decoded pack cannot honestly have: there is no
 * `Resolution` behind it and no source for [site] to point into.
 */
class PackEntry(
    val chunk: Chunk,
    /** The document that declared this handler — `activities/errand/herbs`. */
    val document: String,
    /** What that document calls itself, for a run's own reporting. */
    val name: String,
    /**
     * Whether this is the document the pack was built FROM, rather than one it imported.
     *
     * Load-bearing at dispatch, not decoration: `always on wake` fires for whoever imported the document
     * that declared it, so a pack's entry list is full of handlers belonging to libraries. A runtime that
     * ignored this would have no way to tell "the script's own `on start`" from "a library's".
     */
    val isRoot: Boolean,
    /** A `test`'s own label. Always null in a pack — see [PackCodec]. */
    val label: String? = null,
    /** The authoring site id, or -1 when the pack was stripped. Meaningless without `Sites`. */
    val site: Int = -1,
)

/**
 * A compiled program plus everything needed to RUN it, with no source and no compiler.
 *
 * [ProgramImage] answers "what is the code"; this answers "and what does a runtime do with it". The
 * difference is the entry table: a program on its own is a bag of chunks, and nothing in it says which one
 * is an `on start` and which is an `on sleep`.
 *
 * **`test` entries are not here, and that is upstream of this file.** `TextFrontEnd.compile` skips
 * `EntryKind.TEST` deliberately — nothing spawns it, so emitting it would put every test's bytecode into
 * every shipped script. A pack therefore cannot leak tests even by accident.
 */
class CompiledPack(
    /** The shared function table — what `Op.CALLG` indexes. */
    val functions: Array<Chunk>,
    /** Handlers by kind, in the order the compiler emitted them (which is the order they must run in). */
    val entries: Map<EntryKind, List<PackEntry>>,
    /** The run's starting values, one per global slot — `Compilation.globals`. */
    val globals: List<Any?>,
    /** Whether the metadata the editor and debugger read was dropped. See [ChunkCodec.write]'s `strip`. */
    val stripped: Boolean,
) {
    /** Every chunk in the pack — the table and the handlers, which are not the same set. */
    val allChunks: List<Chunk> get() = functions.toList() + entries.values.flatten().map { it.chunk }

    /**
     * Every host function this pack will ask for.
     *
     * **The compatibility gate, and a precise one.** A host either has these verbs or it does not, which is
     * a question with an answer — unlike "was this built against a compatible version", which is a proxy
     * that gets both false positives and false negatives. A loader should check this before running
     * anything and name what is missing.
     */
    fun requiredHosts(): Set<String> = ProgramImage(functions, allChunks).requiredHosts()
}

/**
 * Reads and writes [CompiledPack] — the shippable artifact.
 *
 * Layered over [ChunkCodec] rather than folded into it: `EntryKind` is a language-level concept and the VM
 * has no business knowing about handlers, so the program encoding stays in `vm` and the dispatch table
 * lives here.
 */
object PackCodec {

    /** `VSPK`. Distinct from [ChunkCodec]'s magic so the two artifacts cannot be confused for each other. */
    private const val MAGIC = 0x5653504B

    const val FORMAT = 1

    /**
     * Write [compilation] as a pack.
     *
     * Refuses a compilation that did not compile: a pack built from a failed run would be a file that
     * looks shippable and contains a subset of a program, which is worse than no file.
     *
     * @param strip drop editor/debugger metadata — see [ChunkCodec.write]. `label` and `site` go with it,
     *   since both exist only to point back at source the pack does not carry.
     */
    fun write(compilation: TextFrontEnd.Compilation, strip: Boolean = false): ByteArray {
        require(compilation.ok) {
            "refusing to pack a compilation with errors: " +
                compilation.errors.joinToString { "${it.span} ${it.message}" }
        }

        // Flattened in a fixed order, and the ProgramImage's roots are built in exactly the same order —
        // so entry i is roots[i] on the way out and on the way back.
        val flat = ArrayList<Pair<EntryKind, TextEntry>>()
        for ((kind, list) in compilation.entries) for (e in list) flat.add(kind to e)

        val functions = flat.firstOrNull()?.second?.chunk?.program ?: emptyArray()
        val program = ChunkCodec.write(ProgramImage(functions, flat.map { it.second.chunk }), strip)
        val globals = ChunkCodec.encodeValues(compilation.globals)

        val bytes = ByteArrayOutputStream(program.size + 512)
        val out = DataOutputStream(bytes)
        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        out.writeBoolean(strip)

        out.writeInt(program.size); out.write(program)
        out.writeInt(globals.size); out.write(globals)

        out.writeInt(flat.size)
        flat.forEachIndexed { i, (kind, e) ->
            // By NAME, never by ordinal: EntryKind gains members over time, and an ordinal written today
            // would silently mean a different handler in a pack read tomorrow.
            writeString(out, kind.name)
            out.writeInt(i)                        // its index among the program's roots — self-checking
            writeString(out, e.document)
            writeString(out, e.name)
            out.writeBoolean(e.isRoot)
            out.writeInt(if (strip) -1 else e.site)
            val label = if (strip) null else e.label
            out.writeBoolean(label != null)
            label?.let { writeString(out, it) }
        }

        out.flush()
        return bytes.toByteArray()
    }

    fun read(bytes: ByteArray): CompiledPack {
        val inp = DataInputStream(ByteArrayInputStream(bytes))

        val magic = inp.readInt()
        require(magic == MAGIC) {
            "not a compiled vs pack (magic 0x${magic.toUInt().toString(16)}, expected 0x5653504b)"
        }
        val format = inp.readInt()
        require(format == FORMAT) {
            "pack is format $format, this build reads $FORMAT — rebuild it from source"
        }
        val stripped = inp.readBoolean()

        val image = ChunkCodec.read(readBlob(inp))
        val globals = ChunkCodec.decodeValues(readBlob(inp))

        val entries = LinkedHashMap<EntryKind, MutableList<PackEntry>>()
        repeat(readCount(inp)) {
            val kindName = readString(inp)
            val kind = EntryKind.values().firstOrNull { it.name == kindName }
                ?: throw IllegalArgumentException(
                    "pack declares an entry kind this build does not know: '$kindName'"
                )
            val rootIndex = inp.readInt()
            require(rootIndex in image.roots.indices) {
                "entry points at root $rootIndex, but the program has ${image.roots.size}"
            }
            val document = readString(inp)
            val name = readString(inp)
            val isRoot = inp.readBoolean()
            val site = inp.readInt()
            val label = if (inp.readBoolean()) readString(inp) else null

            entries.getOrPut(kind) { ArrayList() }
                .add(PackEntry(image.roots[rootIndex], document, name, isRoot, label, site))
        }

        return CompiledPack(image.functions, entries, globals, stripped)
    }

    // ---- primitives (the same framing ChunkCodec uses; see its note on writeUTF) --------------------

    private fun writeString(out: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    private fun readString(inp: DataInputStream): String = String(readBlob(inp), Charsets.UTF_8)

    private fun readBlob(inp: DataInputStream): ByteArray {
        val b = ByteArray(readCount(inp))
        inp.readFully(b)
        return b
    }

    private fun readCount(inp: DataInputStream): Int {
        val n = inp.readInt()
        require(n >= 0) { "negative length $n — the stream is corrupt" }
        return n
    }
}
