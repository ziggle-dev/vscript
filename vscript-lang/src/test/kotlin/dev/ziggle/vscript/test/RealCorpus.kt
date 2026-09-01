package dev.ziggle.vscript.test

import dev.ziggle.vscript.manifest.CatalogManifest
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.text.natives
import java.io.File

/**
 * Where the REAL scripts and the REAL catalogue are, for the tools that want both.
 *
 * **This repo is engine-free, so it cannot build a game catalogue** — the verbs live in the client. What
 * makes working with the real corpus possible anyway is that the catalogue is written out as JSON, both by
 * the client at editor start-up and by the client's `vscriptManifest` build task, and `CatalogManifest`
 * reads one back into exactly the catalogue it came from. So the language repo can load, benchmark and TEST
 * the real scripts without depending on the client; it just needs a client to have run, or been built,
 * once.
 *
 * Shared by `bench/RealCorpusBench` and by `VsTestMain`, because both were about to answer "where is the
 * catalogue" and a second answer is how one of them ends up reading a year-old file.
 *
 * Everything here is OPTIONAL and its absence is not a failure. A fresh clone has neither input, and a
 * benchmark or a test task that failed the build for that is one nobody keeps.
 */
object RealCorpus {

    /**
     * Every place a catalogue dump turns up, NEWEST first.
     *
     * Newest rather than a fixed order, and that is the bug this was written for: the client writes one
     * into its home at start-up and the build writes another under `vscript-core/build`, and preferring
     * whichever was listed first meant reading a dump from before host records existed — which loads a
     * catalogue whose pins name `ItemRef` while nothing has heard of it, and reports 58 of 79 documents as
     * "skipped".
     */
    fun catalogFile(): File? = listOf(
        File(System.getProperty("user.home"), ".ziggle/vscript-catalog.json"),
        File("../vscript-client/vscript-core/build/vscript/vscript-catalog.json"),
        File("../../vscript-client/vscript-core/build/vscript/vscript-catalog.json"),
    ).filter { it.isFile }.maxByOrNull { it.lastModified() }

    /**
     * The scripts tree, wherever this was run from.
     *
     * Several candidates because the working directory is the language repo under Gradle and the workspace
     * root under an IDE, and neither is worth hard-coding.
     */
    fun scriptsRoot(): File? = listOf(
        "../plugins/scripts/src", "../../plugins/scripts/src", "plugins/scripts/src",
        "../vscript-client/scripts", "../../vscript-client/scripts",
    ).map { File(it) }.firstOrNull { it.isDirectory }

    /**
     * The real catalogue as signatures, or null when nothing has ever written one.
     *
     * `toCatalog()` also registers the host enums and records the manifest carries — a catalogue and the
     * types its pins are declared with are one fact, and separating them is what produced the 58 skips.
     */
    fun natives(): NativeTable? {
        val f = catalogFile() ?: return null
        return runCatching { CatalogManifest.fromJson(f.readText()).toCatalog().natives() }.getOrNull()
    }

    /**
     * Every `.vs` under [root], keyed the way the client keys them.
     *
     * Three keys per document, deliberately the same rule as the client's `DocumentSource`: the path it
     * sits at, the folder for a `mod` (so `import "scheduler"` finds `scheduler/mod`), and the name its own
     * `graph` line gives. An index that differed here would resolve an import to something the client would
     * not, and every tool built on it would be checking documents nobody runs.
     */
    fun documents(root: File): Map<String, String> {
        val out = HashMap<String, String>()
        root.walkTopDown().filter { it.isFile && it.extension == "vs" }.sortedBy { it.path }.forEach { f ->
            val text = f.readText()
            val rel = f.relativeTo(root).path.replace('\\', '/').removeSuffix(".vs")
            out[rel] = text
            if (rel.endsWith("/mod")) out.putIfAbsent(rel.removeSuffix("/mod"), text)
            GRAPH_LINE.find(text)?.groupValues?.get(1)?.let { out.putIfAbsent(it, text) }
        }
        return out
    }

    /** An import source over the whole tree, or null when there is no tree. */
    fun sources(): TextSource? = scriptsRoot()?.let { TextSource.of(documents(it)) }

    private val GRAPH_LINE = Regex("""^\s*graph\s+"([^"]+)"""", RegexOption.MULTILINE)
}
