package dev.ziggle.vscript.vm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A compiled program, as bytes — the on-disk form of what the compiler produces in memory.
 *
 * ## Why this exists
 *
 * Until now the only things this language could persist were **source** and the canvas `GraphDoc`. A
 * compiled program lived exactly as long as the process that compiled it, which is fine for an editor and
 * useless for shipping: distributing a script meant distributing its source, and its imports' source, and
 * trusting whoever received it to compile the same thing.
 *
 * **The import closure comes along for free, and that is the point.** `TextFrontEnd` compiles a document
 * *and everything it imports* into one shared function table, with each document contributing its own
 * entry handlers and its own initialiser prologue. By the time there is a program there is no such thing
 * as "the other files" — a serialized program is the whole closure, flattened, or it is nothing.
 *
 * ## What is NOT here, and what still is
 *
 * No source, no AST, no `Sites`, no diagnostics — none of those are reachable from a [Chunk].
 *
 * **But a compiled chunk is not automatically anonymous, and `debug = false` does not make it so.** That
 * flag suppresses the debugger's [Op.TRACE] markers and nothing else: `slots.variables` still maps every
 * document VARIABLE NAME to its slot, `slots.outputs` still names pins, `literalSlots` is still keyed
 * `"<nodeId>/<pin>"`, and `nodeIds` still carries a site id per instruction. A program written straight
 * from a compile therefore ships identifiers, which is a surprise worth having in writing rather than
 * discovering in a shipped artifact.
 *
 * [write] takes `strip` for exactly that. It drops the three fields nothing but the editor and the
 * debugger read — see the parameter — and keeps everything execution touches. What it CANNOT remove is
 * [Chunk.name], which is a document reference (`activities/errand/herbs#start`) and is what a runtime
 * error names; so a stripped image still discloses the shape of the import tree, and anything more than
 * that has to be arranged by the compiler, not here.
 *
 * ## The one thing that makes this portable
 *
 * Host calls are stored by NAME ([Chunk.hostNames]) and resolved at load by [HostRegistry.bind], never by
 * an index into a catalogue. So a program written today binds against a host built tomorrow, and a host
 * that has *lost* a function fails loudly at bind with the name it could not find rather than at some
 * unlucky instruction halfway through a run.
 */
class ProgramImage(
    /** The flat function table — what [Op.CALLG]'s operand indexes. */
    val functions: Array<Chunk>,
    /**
     * The entry chunks.
     *
     * Kept beside [functions] rather than inside it because [ProgramBuilder.link] keeps them apart: the
     * table is what calls index into, and a root is something a runtime *spawns*. A root may also appear in
     * the table (nothing forbids a function being an entry), so the writer de-duplicates by identity and
     * the reader hands back the same instances rather than copies.
     */
    val roots: List<Chunk>,
) {
    /**
     * Every host function this program will ask for, by name.
     *
     * **This is the compatibility question, answered exactly.** A program is portable across hosts because
     * [HostRegistry.bind] resolves by name — so "will this run here" is not a version comparison, it is
     * whether the host has these. A caller can therefore refuse a program *before* spawning anything and
     * say which verb is missing, instead of discovering it at whatever instruction happens to reach the
     * gap. Compare against [HostRegistry.names].
     *
     * Both tiers are walked: a call can be emitted in a function nothing else names, and an entry that is
     * not in the table has host names of its own.
     */
    fun requiredHosts(): Set<String> {
        val out = LinkedHashSet<String>()
        for (c in functions) out += c.hostNames
        for (c in roots) out += c.hostNames
        return out
    }
}

/**
 * Reads and writes [ProgramImage] as a self-describing byte stream.
 *
 * Deliberately hand-rolled rather than Java serialization or a schema library: the format is a dozen flat
 * fields, the value universe is closed (see [writeValue]), and a compiled artifact that ships to other
 * people should have an encoding somebody can read in an afternoon and version on purpose.
 */
object ChunkCodec {

    /** `VSBC` — big-endian, first four bytes, so a truncated or unrelated file fails immediately. */
    private const val MAGIC = 0x56534243

    /**
     * Bumped when the ENCODING changes, never when a program's contents do.
     *
     * A reader refuses a format it does not know rather than guessing: every field here is
     * length-prefixed, so misreading one does not stop at a bad byte, it silently produces a plausible
     * wrong program. Refusing is the only honest option.
     */
    const val FORMAT = 1

    // ---- value tags -------------------------------------------------------------------------------
    // The closed set of things a constant pool or a globals array can hold. See [writeValue].
    private const val T_NULL = 0
    private const val T_INT = 1
    private const val T_LONG = 2
    private const val T_DOUBLE = 3
    private const val T_BOOL = 4
    private const val T_STRING = 5
    private const val T_LIST = 6
    private const val T_MAP = 7
    private const val T_STRUCT = 8

    // ---- program ----------------------------------------------------------------------------------

    /**
     * @param strip drop the metadata only the editor and the debugger read — [Chunk.nodeIds],
     *   [Chunk.slots] and [Chunk.literalSlots]. Everything execution touches (code, constants, host names,
     *   globals, handlers, register counts) is kept, so a stripped image runs identically; what it loses is
     *   the ability to say which node an error came from, to show a variable in an inspector, and to retune
     *   a literal live. Off by default, because a faithful image is the safe default and losing debuggability
     *   should be something a caller asks for. See the class note for what strip cannot reach.
     */
    fun write(image: ProgramImage, strip: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream(1 shl 16)
        val out = DataOutputStream(bytes)

        // One flat table of every distinct chunk, addressed by index. Identity, not equality: two chunks
        // can be field-for-field equal and still be different functions, and collapsing them would make
        // `CALLG` land somewhere the compiler did not send it.
        val index = java.util.IdentityHashMap<Chunk, Int>()
        val all = ArrayList<Chunk>()
        fun intern(c: Chunk) {
            if (index.containsKey(c)) return
            index[c] = all.size
            all.add(c)
        }
        image.functions.forEach(::intern)
        image.roots.forEach(::intern)

        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        out.writeInt(all.size)
        out.writeInt(image.functions.size)          // the first N of `all` ARE the function table, in order
        out.writeInt(image.roots.size)
        for (r in image.roots) out.writeInt(index[r]!!)
        for (c in all) writeChunk(out, c, strip)

        out.flush()
        return bytes.toByteArray()
    }

    fun read(bytes: ByteArray): ProgramImage {
        val inp = DataInputStream(ByteArrayInputStream(bytes))

        val magic = inp.readInt()
        require(magic == MAGIC) {
            "not a compiled vs program (magic 0x${magic.toUInt().toString(16)}, expected 0x56534243)"
        }
        val format = inp.readInt()
        require(format == FORMAT) {
            "compiled program is format $format, this build reads $FORMAT — rebuild it from source"
        }

        val total = inp.readInt()
        val functionCount = inp.readInt()
        require(functionCount in 0..total) { "function count $functionCount is not within $total chunks" }
        val rootIdx = IntArray(inp.readInt()) { inp.readInt() }
        val all = Array(total) { readChunk(inp) }

        // Mirrors ProgramBuilder.link: every chunk — table member or root — points at the SAME table, so a
        // CALLG from an entry and a CALLG from deep inside a helper resolve identically.
        val functions = Array(functionCount) { all[it] }
        for (c in all) c.program = functions

        return ProgramImage(functions, rootIdx.map { all[it] })
    }

    // ---- values, for a container format ------------------------------------------------------------

    /**
     * A standalone list of values, for a format that carries some ALONGSIDE a program.
     *
     * A pack has to record the run's starting globals, which are values of exactly the kind a constant pool
     * holds; without this, a container would either re-implement the tagging (two encodings to keep in
     * step) or smuggle them through a fake chunk. Length-prefixed blob rather than a shared stream, so the
     * container owns its own framing and this owns its own.
     */
    fun encodeValues(values: List<Any?>): ByteArray {
        val bytes = ByteArrayOutputStream(64)
        val out = DataOutputStream(bytes)
        out.writeInt(values.size)
        for (v in values) writeValue(out, v)
        out.flush()
        return bytes.toByteArray()
    }

    fun decodeValues(bytes: ByteArray): List<Any?> {
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        return List(count(inp)) { readValue(inp) }
    }

    // ---- chunk ------------------------------------------------------------------------------------

    private fun writeChunk(out: DataOutputStream, c: Chunk, strip: Boolean) {
        writeString(out, c.name)
        writeInts(out, c.code)
        out.writeInt(c.constants.size)
        for (v in c.constants) writeValue(out, v)
        out.writeInt(c.maxRegs)
        out.writeInt(c.paramCount)
        writeInts(out, if (strip) IntArray(0) else c.nodeIds)
        out.writeInt(c.hostNames.size)
        for (n in c.hostNames) writeString(out, n)

        // SlotMap — the inspector's format, and the one field that carries authored NAMES. Three zeroes
        // when stripped.
        val slots = if (strip) SlotMap() else c.slots
        out.writeInt(slots.outputs.size)
        for ((k, v) in slots.outputs) { out.writeInt(k.first); writeString(out, k.second); out.writeInt(v) }
        out.writeInt(slots.variables.size)
        for ((k, v) in slots.variables) { writeString(out, k); out.writeInt(v) }
        out.writeInt(slots.liveFrom.size)
        for ((k, v) in slots.liveFrom) { out.writeInt(k.first); writeString(out, k.second); out.writeInt(v) }

        out.writeInt(c.globals.size)
        for (v in c.globals) writeValue(out, v)

        val literals = if (strip) emptyMap() else c.literalSlots
        out.writeInt(literals.size)
        for ((k, v) in literals) { writeString(out, k); out.writeInt(v) }

        out.writeInt(c.handlers.size)
        for (h in c.handlers) {
            out.writeInt(h.start); out.writeInt(h.end); out.writeInt(h.catchPc); out.writeInt(h.messageReg)
        }

        out.writeBoolean(c.prologueOf != null)
        c.prologueOf?.let { writeString(out, it) }
    }

    private fun readChunk(inp: DataInputStream): Chunk {
        val name = readString(inp)
        val code = readInts(inp)
        val constants = Array<Any?>(inp.readInt()) { readValue(inp) }
        val maxRegs = inp.readInt()
        val paramCount = inp.readInt()
        val nodeIds = readInts(inp)
        val hostNames = Array(inp.readInt()) { readString(inp) }

        val outputs = LinkedHashMap<Pair<Int, String>, Int>()
        repeat(inp.readInt()) { outputs[inp.readInt() to readString(inp)] = inp.readInt() }
        val variables = LinkedHashMap<String, Int>()
        repeat(inp.readInt()) { variables[readString(inp)] = inp.readInt() }
        val liveFrom = LinkedHashMap<Pair<Int, String>, Int>()
        repeat(inp.readInt()) { liveFrom[inp.readInt() to readString(inp)] = inp.readInt() }

        val globals = List<Any?>(inp.readInt()) { readValue(inp) }

        val literalSlots = LinkedHashMap<String, Int>()
        repeat(inp.readInt()) { literalSlots[readString(inp)] = inp.readInt() }

        val handlers = List(inp.readInt()) {
            HandlerRange(inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt())
        }

        val prologueOf = if (inp.readBoolean()) readString(inp) else null

        return Chunk(
            name = name,
            code = code,
            constants = constants,
            maxRegs = maxRegs,
            paramCount = paramCount,
            nodeIds = nodeIds,
            hostNames = hostNames,
            slots = SlotMap(outputs, variables, liveFrom),
            globals = globals,
            literalSlots = literalSlots,
            handlers = handlers,
            prologueOf = prologueOf,
        )
    }

    // ---- values -----------------------------------------------------------------------------------

    /**
     * One constant or one global.
     *
     * The value universe is closed and small — `Values.typeName` enumerates it — so this is a tag byte and
     * the obvious encoding, with containers recursing.
     *
     * **An unknown type is an error, never a silently written null.** The only values that reach a
     * constant pool are authored literals and defaults, so anything else here means the compiler put a live
     * host handle (a game object, a fiber, a listener) into a pool — a bug worth failing on at write time,
     * where the chunk that did it is still in hand, rather than shipping a program with a hole in it that
     * reads back as null and misbehaves on somebody else's machine.
     */
    private fun writeValue(out: DataOutputStream, v: Any?) {
        when (v) {
            null -> out.writeByte(T_NULL)
            is Int -> { out.writeByte(T_INT); out.writeInt(v) }
            is Long -> { out.writeByte(T_LONG); out.writeLong(v) }
            is Double -> { out.writeByte(T_DOUBLE); out.writeDouble(v) }
            is Boolean -> { out.writeByte(T_BOOL); out.writeBoolean(v) }
            is String -> { out.writeByte(T_STRING); writeString(out, v) }
            is List<*> -> {
                out.writeByte(T_LIST); out.writeInt(v.size)
                for (e in v) writeValue(out, e)
            }
            is Map<*, *> -> {
                out.writeByte(T_MAP); out.writeInt(v.size)
                for ((k, e) in v) { writeValue(out, k); writeValue(out, e) }
            }
            is StructValue -> {
                out.writeByte(T_STRUCT)
                writeString(out, v.type)
                out.writeInt(v.names.size)
                for (n in v.names) writeString(out, n)
                // `size` is the value count, which a well-formed struct shares with `names` — written
                // separately anyway so a malformed one is caught here rather than misaligning the stream.
                out.writeInt(v.size)
                for (i in 0 until v.size) writeValue(out, v[i])
            }
            else -> throw IllegalArgumentException(
                "cannot serialize a ${Values.typeName(v)} in a compiled program — only literals and " +
                    "defaults belong in a constant pool, so this is a live host value that should never " +
                    "have been put there"
            )
        }
    }

    /**
     * Containers come back MUTABLE, which is not incidental.
     *
     * A script mutates the list a `single` holds; handing it `listOf()` would turn the first `add` into an
     * UnsupportedOperationException at run time, on a path a round-trip test that only compared contents
     * would never take.
     */
    private fun readValue(inp: DataInputStream): Any? = when (val tag = inp.readByte().toInt()) {
        T_NULL -> null
        T_INT -> inp.readInt()
        T_LONG -> inp.readLong()
        T_DOUBLE -> inp.readDouble()
        T_BOOL -> inp.readBoolean()
        T_STRING -> readString(inp)
        T_LIST -> {
            val n = count(inp)
            // Capacity is CLAMPED, not trusted: `n` comes off the wire, and sizing an ArrayList straight
            // from it turns a corrupt four bytes into an OutOfMemoryError before a single element is read.
            val list = ArrayList<Any?>(minOf(n, 64))
            repeat(n) { list.add(readValue(inp)) }
            list
        }
        T_MAP -> {
            val n = count(inp)
            val map = LinkedHashMap<Any?, Any?>()
            repeat(n) {
                val k = readValue(inp)
                map[k] = readValue(inp)
            }
            map
        }
        T_STRUCT -> {
            val type = readString(inp)
            val names = List(count(inp)) { readString(inp) }
            StructValue(type, names, Array(count(inp)) { readValue(inp) })
        }
        else -> throw IllegalStateException("unknown value tag $tag in a compiled program")
    }

    /** A non-negative element count off the wire. */
    private fun count(inp: DataInputStream): Int {
        val n = inp.readInt()
        require(n >= 0) { "negative element count $n — the stream is corrupt" }
        return n
    }

    // ---- primitives -------------------------------------------------------------------------------

    /**
     * Length-prefixed UTF-8, rather than [DataOutputStream.writeUTF].
     *
     * `writeUTF` caps a string at 64 KB of *modified* UTF-8 and throws past it. A script's constant pool
     * can hold a long string, and a chunk name is a document reference that grows with the import path —
     * neither is near the cap today, and neither is something to discover at the cap.
     */
    private fun writeString(out: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    private fun readString(inp: DataInputStream): String {
        val n = inp.readInt()
        require(n >= 0) { "negative string length $n — the stream is corrupt" }
        val b = ByteArray(n)
        inp.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun writeInts(out: DataOutputStream, a: IntArray) {
        out.writeInt(a.size)
        for (i in a) out.writeInt(i)
    }

    private fun readInts(inp: DataInputStream): IntArray {
        val n = inp.readInt()
        require(n >= 0) { "negative array length $n — the stream is corrupt" }
        return IntArray(n) { inp.readInt() }
    }
}
