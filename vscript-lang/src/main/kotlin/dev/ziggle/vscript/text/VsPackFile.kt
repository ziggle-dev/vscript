package dev.ziggle.vscript.text

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What a `.vspack` says about itself, before anything decodes the bytecode inside it.
 *
 * **The point of a header is to answer "can I run this" without running it.** [requiredHosts] is the whole
 * compatibility check and it is here, in plain JSON, so a registry, an installer or a person with `unzip`
 * can read it — [PackCodec] would have to decode the entire program to work the same answer out, and a
 * catalogue browser has no business doing that.
 *
 * Kept deliberately thin. Anything about DELIVERY — where an artifact is stored, who is entitled to it,
 * what it costs — belongs to whatever is delivering it and not in the file. [meta] is the escape hatch for
 * a publisher that wants to stamp something (a git sha, a build number) without this schema growing a
 * field per publisher.
 */
class VsPackInfo(
    /** Stable identity across versions — `osrsx/errands`. */
    val id: String,
    /** The pack's own version. Opaque here; whoever publishes decides what it means. */
    val version: String,
    /** The document the pack was built FROM, as its import reference. */
    val entry: String,
    /**
     * Every host verb the program calls.
     *
     * Denormalised out of the bytecode on purpose: it is the one question asked most and the most
     * expensive to answer any other way. [VsPackFile.read] verifies it against the program rather than
     * trusting it, so a hand-edited manifest cannot talk a host into running something it lacks.
     */
    val requiredHosts: Set<String>,
    /** Whether editor/debugger metadata was dropped — see [ChunkCodec.write]'s `strip`. */
    val stripped: Boolean,
    /** Milliseconds since the epoch, when the pack was built. */
    val builtAtMs: Long,
    /** Publisher's own stamps — a git sha, a build number. Never read by anything here. */
    val meta: Map<String, String> = emptyMap(),
)

/**
 * The distributable file: a zip of a manifest and a compiled program.
 *
 * ```
 * manifest.json   VsPackInfo
 * program.vsb     PackCodec bytes
 * ```
 *
 * A zip rather than a bespoke container because everything downstream already handles one — the client
 * distribution is a zip, the launcher extracts zips — and because `unzip -p x.vspack manifest.json` is a
 * debugging story that costs nothing to provide.
 */
object VsPackFile {

    const val MANIFEST = "manifest.json"
    const val PROGRAM = "program.vsb"

    /** Bumped when the CONTAINER changes — not when the bytecode format does, which [PackCodec] owns. */
    const val FORMAT = 1

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * Build a `.vspack` from a compilation.
     *
     * [VsPackInfo.requiredHosts] is computed from the program rather than accepted from the caller, so the
     * manifest cannot disagree with the bytecode it ships beside.
     */
    fun write(
        compilation: TextFrontEnd.Compilation,
        id: String,
        version: String,
        entry: String,
        strip: Boolean = true,
        builtAtMs: Long = System.currentTimeMillis(),
        meta: Map<String, String> = emptyMap(),
    ): ByteArray {
        val program = PackCodec.write(compilation, strip)
        val info = VsPackInfo(
            id = id,
            version = version,
            entry = entry,
            requiredHosts = PackCodec.read(program).requiredHosts(),
            stripped = strip,
            builtAtMs = builtAtMs,
            meta = meta,
        )
        return zip(info, program)
    }

    /** Repackage an already-encoded program. For a builder that made the bytes some other way. */
    fun write(info: VsPackInfo, program: ByteArray): ByteArray = zip(info, program)

    private fun zip(info: VsPackInfo, program: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream(program.size + 2048)
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(toJson(info).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PROGRAM))
            zip.write(program)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    /**
     * Read a pack's header WITHOUT decoding its program.
     *
     * What a registry listing or an installer's compatibility check wants: it is a few hundred bytes off
     * the front of the file rather than every chunk in the program.
     */
    fun readInfo(bytes: ByteArray): VsPackInfo =
        fromJson(String(entry(bytes, MANIFEST), Charsets.UTF_8))

    /**
     * Read the whole thing.
     *
     * **The manifest is verified against the program, not trusted.** `requiredHosts` is the gate a host
     * uses to decide whether to run something, and it travels in plain text beside the bytecode — so a
     * pack whose manifest understates what it calls would be a pack that talks a host into running code
     * it cannot serve, and fails at whatever instruction reaches the missing verb. Recomputing costs one
     * decode we were about to do anyway.
     */
    fun read(bytes: ByteArray): Pair<VsPackInfo, CompiledPack> {
        val info = readInfo(bytes)
        val pack = PackCodec.read(entry(bytes, PROGRAM))

        val actual = pack.requiredHosts()
        require(actual == info.requiredHosts) {
            val missing = actual - info.requiredHosts
            val extra = info.requiredHosts - actual
            "manifest disagrees with the program about what it calls" +
                (if (missing.isNotEmpty()) "; undeclared: ${missing.sorted()}" else "") +
                (if (extra.isNotEmpty()) "; declared but never called: ${extra.sorted()}" else "")
        }
        return info to pack
    }

    private fun entry(bytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (e.name == name) return zin.readBytes()
                e = zin.nextEntry
            }
        }
        throw IllegalArgumentException("not a .vspack: no $name inside")
    }

    // ---- manifest json --------------------------------------------------------------------------------

    fun toJson(info: VsPackInfo): String {
        val o = JsonObject()
        o.addProperty("format", FORMAT)
        o.addProperty("id", info.id)
        o.addProperty("version", info.version)
        o.addProperty("entry", info.entry)
        o.addProperty("stripped", info.stripped)
        o.addProperty("builtAtMs", info.builtAtMs)
        // Sorted, so two builds of the same source produce byte-identical manifests and a diff of two
        // packs is about what changed rather than about hash ordering.
        o.add("requiredHosts", gson.toJsonTree(info.requiredHosts.sorted()))
        o.add("meta", gson.toJsonTree(info.meta.toSortedMap()))
        return gson.toJson(o)
    }

    fun fromJson(json: String): VsPackInfo {
        // `gson.fromJson`, not `JsonParser.parseString`: that static arrived in gson 2.8.6 and this
        // module pins 2.8.5 deliberately, because the client's effective gson is the copy shaded into
        // the fork jar. Reaching past the pin compiles here and dies as NoSuchMethodError there.
        val o = gson.fromJson(json, JsonObject::class.java)
        val format = o.get("format")?.asInt ?: 0
        require(format == FORMAT) {
            "pack container is format $format, this build reads $FORMAT"
        }
        // Walked by key rather than over entrySet(): gson's entry set is a raw java.util.Map.Entry, which
        // Kotlin neither destructures nor infers a lambda parameter for.
        val metaObj = o.getAsJsonObject("meta")
        val meta = LinkedHashMap<String, String>()
        metaObj?.keySet()?.forEach { k -> meta[k] = metaObj.get(k).asString }
        return VsPackInfo(
            id = o.get("id").asString,
            version = o.get("version").asString,
            entry = o.get("entry").asString,
            requiredHosts = o.getAsJsonArray("requiredHosts").map { it.asString }.toSet(),
            stripped = o.get("stripped")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            builtAtMs = o.get("builtAtMs")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            meta = meta,
        )
    }
}
