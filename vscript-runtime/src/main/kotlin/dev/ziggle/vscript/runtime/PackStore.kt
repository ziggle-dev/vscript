package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.text.CompiledPack
import dev.ziggle.vscript.text.VsPackFile
import dev.ziggle.vscript.text.VsPackInfo
import java.io.File

/** One `.vspack` on disk, and what its header says — without its program having been decoded. */
class InstalledPack(val file: File, val info: VsPackInfo) {
    /** What a listing shows and what `pack run` is asked for. */
    val id: String get() = info.id
}

/**
 * The `.vspack` files this installation can run.
 *
 * **Headers are read; programs are not.** Listing what is installed is a thing a UI does on every refresh,
 * and decoding a program to answer it would mean reading every chunk of every pack to print a name — the
 * orchestrator alone is 380KB and nineteen hundred functions. [VsPackFile.readInfo] takes the manifest off
 * the front of the zip and stops, so a listing costs a few hundred bytes per pack and [load] is the only
 * thing that pays for the rest.
 *
 * A pack that cannot be read is REPORTED, not thrown: one corrupt file in the folder must not make the
 * other twenty invisible, and "this one is broken" is more useful than an empty list.
 */
class PackStore(val root: File = defaultRoot()) {

    /** Every readable pack, by id, newest build first. Unreadable files land in [problems]. */
    fun installed(): List<InstalledPack> = scan().first

    /** What went wrong with the files that are not in [installed] — file name to reason. */
    fun problems(): Map<String, String> = scan().second

    fun find(id: String): InstalledPack? = installed().firstOrNull { it.id == id }

    /**
     * The whole pack — header and program.
     *
     * [VsPackFile.read] re-derives the host set from the bytecode and refuses a manifest that disagrees
     * with it, so a pack whose header understates what it calls is rejected here rather than after it has
     * talked this host into running it.
     */
    fun load(id: String): Pair<VsPackInfo, CompiledPack>? {
        val found = find(id) ?: return null
        return VsPackFile.read(found.file.readBytes())
    }

    private fun scan(): Pair<List<InstalledPack>, Map<String, String>> {
        val good = ArrayList<InstalledPack>()
        val bad = LinkedHashMap<String, String>()
        val files = root.listFiles { f: File -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray()
        for (f in files.sortedBy { it.name }) {
            try {
                good += InstalledPack(f, VsPackFile.readInfo(f.readBytes()))
            } catch (e: Exception) {
                bad[f.name] = e.message ?: e.javaClass.simpleName
            }
        }
        return good.sortedByDescending { it.info.builtAtMs } to bad
    }

    companion object {
        const val EXTENSION = ".vspack"

        /**
         * Beside the documents, not inside them.
         *
         * A pack is not a document: the import root is walked by reference and a `.vspack` answers to no
         * reference, so leaving one among the `.vs` files would put a binary in a tree every index walks.
         */
        fun defaultRoot(): File = File(EditorDoc.graphsDir().parentFile ?: EditorDoc.graphsDir(), "packs")
    }
}
