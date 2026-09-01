package dev.ziggle.vscript.bench

import dev.ziggle.vscript.manifest.CatalogManifest
import dev.ziggle.vscript.model.ModuleNames
import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.Resolution
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.text.natives
import java.io.File

/**
 * The REAL scripts, loaded — what an author actually waits for today.
 *
 * [LoadBench] measures the language on generated trees, which is how you learn the shape of the curve.
 * This measures the corpus, which is how you learn where on that curve anybody actually is. The two
 * answer different halves of "is loading fast enough", and only this one can be wrong about the world.
 *
 * **It needs a catalogue, and it does not build one.** This repo is engine-free, so the game verbs live
 * in the client; what makes this possible anyway is that the running client already writes its whole
 * catalogue out as JSON (`CatalogDump.writeQuietly`, at editor start-up), and `CatalogManifest` reads one
 * back into exactly the `NodeCatalog` it came from. So the language repo can measure the real corpus
 * without depending on the client — it just needs a client to have run once.
 *
 * Both inputs are OPTIONAL and their absence is not a failure. A fresh clone has neither the scripts tree
 * nor a catalogue dump, and a benchmark that failed the build for that would be a benchmark nobody keeps.
 *
 * ```
 * gradlew.bat benchReal -PoutDir=build-bench
 * ```
 */
object RealCorpusBench {

    /** Where a catalogue dump is, newest of the places one turns up. See [RealCorpus.catalogFile]. */
    fun catalogFile(): File? = dev.ziggle.vscript.test.RealCorpus.catalogFile()

    /**
     * The scripts tree, wherever this was run from.
     *
     * Several candidates because the working directory is the language repo under Gradle and the workspace
     * root under an IDE, and neither is worth hard-coding.
     */
    fun scriptsRoot(): File? = dev.ziggle.vscript.test.RealCorpus.scriptsRoot()

    /**
     * The real catalogue as signatures, or null when no client has ever dumped one.
     *
     * The manifest is read once and projected once: `natives()` walks every descriptor and builds a
     * `Names` table on the way, which is not free and is not what this benchmark is measuring.
     */
    fun natives(): NativeTable? = dev.ziggle.vscript.test.RealCorpus.natives()

    /** What one real script cost, and how much of the corpus it pulled in. */
    class Row(
        val ref: String,
        val ownLines: Int,
        val closureDocs: Int,
        val closureLines: Int,
        val declaredFunctions: Int,
        val emittedChunks: Int,
        val timing: LoadBench.Timing,
        val resolveTiming: LoadBench.Timing,
    ) {
        val emitMs: Double get() = (timing.median - resolveTiming.median).coerceAtLeast(0.0)
    }

    /** Documents that could not be measured, and the one line saying why. */
    class Skipped(val ref: String, val why: String)

    class Result(val rows: List<Row>, val skipped: List<Skipped>, val corpusFiles: Int, val corpusLines: Int)

    /**
     * Every document in the tree, timed — the ones that compile, anyway.
     *
     * A document that does not compile is SKIPPED rather than timed. An error path stops early and so
     * measures faster than the work it failed to do, and a table mixing the two would report the corpus
     * getting quicker as it got more broken.
     */
    fun run(warmup: Int = 2, reps: Int = 7): Result? {
        val root = scriptsRoot() ?: return null
        val natives = natives() ?: return null

        val files = root.walkTopDown().filter { it.extension == "vs" }.sortedBy { it.path }.toList()
        val paths = files.associateWith { f ->
            f.relativeTo(root).path.replace(File.separatorChar, '/').removeSuffix(".vs")
        }
        // **The naming rule is CALLED, not restated.** This used to key the map on the bare relative path,
        // which is the client's rule only until the corpus grows a `mod.vs` — and then
        // `import "activities/farmrun/shared"` resolves when the client runs it and reports
        // `nothing answers to …` here, which makes the benchmark measure a corpus nothing else can build.
        val byRef = LinkedHashMap<String, File>()
        for ((f, path) in paths) {
            for (name in ModuleNames.namesOf(path, declaredName(f))) byRef.putIfAbsent(name, f)
        }
        // Second pass, so an explicit sibling keeps the folder name — `DocumentSource.refresh`'s order.
        for ((f, path) in paths) ModuleNames.barrelName(path)?.let { byRef.putIfAbsent(it, f) }

        val sources = byRef.mapValues { it.value.readText() }
        val source = TextSource { sources[it] }
        /** What each file is imported BY — its folder for a `mod.vs`, else its most specific name. */
        val refOf = paths.mapValues { (f, path) ->
            ModuleNames.barrelName(path) ?: ModuleNames.namesOf(path, declaredName(f)).firstOrNull() ?: path
        }

        val rows = ArrayList<Row>()
        val skipped = ArrayList<Skipped>()
        for ((f, _) in paths) {
            val ref = refOf.getValue(f)
            val text = f.readText()
            val proof = TextFrontEnd(natives, imports = source, rootRef = ref).compile(text)
            if (!proof.ok) {
                skipped += Skipped(ref, proof.errors.first().let { "${it.span} ${it.message}" })
                continue
            }
            val closure = closureOf(proof.resolution!!)
            val emitted = TextFrontEnd(natives, imports = source, rootRef = ref)
                .also { it.compile(text) }
                .program.link().size

            val timings = LoadBench.measureAll(
                warmup, reps,
                listOf(
                    "resolve" to { TextFrontEnd(natives, imports = source, rootRef = ref).resolve(text); Unit },
                    "compile" to { TextFrontEnd(natives, imports = source, rootRef = ref).compile(text); Unit },
                ),
            )
            rows += Row(
                ref = ref,
                ownLines = text.count { it == '\n' } + 1,
                closureDocs = closure.size,
                closureLines = closure.sumOf { r -> (sources[r] ?: "").count { it == '\n' } + 1 },
                declaredFunctions = closure.sumOf { r -> countFns(sources[r] ?: "") },
                emittedChunks = emitted,
                timing = timings.getValue("compile"),
                resolveTiming = timings.getValue("resolve"),
            )
        }
        return Result(rows, skipped, files.size, files.sumOf { f -> f.readText().count { it == '\n' } + 1 })
    }

    /**
     * Every document [r] can reach, itself included, by the reference each was imported as.
     *
     * A SET, and that is load-bearing: the corpus is full of diamonds — half of `core/` imports
     * `core/list` — and counting a document once per path would report closures several times the size of
     * anything on disk.
     */
    private fun closureOf(r: Resolution): Set<String> {
        val out = LinkedHashSet<String>()
        fun visit(res: Resolution) {
            if (!out.add(res.ref)) return
            for (m in res.imported.unqualified) visit(m.resolution)
            for (m in res.imported.aliased.values) visit(m.resolution)
            for ((m, _) in res.imported.named.values) visit(m.resolution)
            for (m in res.reExports) visit(m.resolution)
        }
        visit(r)
        return out
    }

    /**
     * How many `fn`s a source declares, counted with a regex.
     *
     * A parse would be exact and this is a REPORT column: it exists so the shake ratio has a denominator,
     * and being a percent or two out about how many functions a barrel forwards changes no decision. What
     * would change one is the parse showing up in the timings, which is why it is not parsed.
     */
    private fun countFns(source: String): Int = FN.findAll(source).count()

    private val FN = Regex("""(?m)^\s*(export\s+)?fn\s""")

    /**
     * A document's own `graph` line, or null when it has none — which, since the header became optional, is
     * every file in the corpus. Matched rather than parsed, for [countFns]'s reason: it feeds the naming
     * rule, not the timings, and the parse it would cost would show up in them.
     */
    private fun declaredName(f: File): String? = GRAPH_LINE.find(f.readText())?.groupValues?.get(1)

    private val GRAPH_LINE = Regex("""(?m)^\s*graph\s+"([^"]*)"""")

    @JvmStatic
    fun main(args: Array<String>) {
        val result = run()
        if (result == null) {
            println("=== real corpus benchmark: SKIPPED ===")
            println("  scripts tree: ${scriptsRoot()?.absolutePath ?: "not found"}")
            println("  catalogue:    ${catalogFile()?.absolutePath ?: "not found (start the client once)"}")
            return
        }
        report(result)
    }

    fun report(r: Result) {
        println("=== real corpus load benchmark ===")
        println(
            "${r.corpusFiles} documents, ${r.corpusLines} lines; " +
                "${r.rows.size} compile clean, ${r.skipped.size} skipped",
        )
        println()
        println(
            "%-44s %6s %6s %8s | %8s %8s %8s | %s".format(
                "document", "lines", "docs", "closure", "resolve", "emit", "TOTAL", "emitted/declared",
            ),
        )
        println("-".repeat(126))
        for (row in r.rows.sortedByDescending { it.timing.median }) {
            println(
                "%-44s %6d %6d %8d | %8.2f %8.2f %8.2f | %d/%d".format(
                    row.ref, row.ownLines, row.closureDocs, row.closureLines,
                    row.resolveTiming.median, row.emitMs, row.timing.median,
                    row.emittedChunks, row.declaredFunctions,
                ),
            )
        }
        r.rows.maxByOrNull { it.timing.median }?.let {
            println()
            println(
                "worst: %s — %.1f ms warm, %.1f ms cold, over %d documents / %d lines"
                    .format(it.ref, it.timing.median, it.timing.coldMs, it.closureDocs, it.closureLines),
            )
        }
        if (r.skipped.isNotEmpty()) {
            println()
            println("-- not measured, because they do not compile --")
            r.skipped.take(12).forEach { println("  ${it.ref}: ${it.why}") }
            if (r.skipped.size > 12) println("  ...and ${r.skipped.size - 12} more")
        }
    }
}
