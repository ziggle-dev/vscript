package dev.ziggle.vscript.bench

import dev.ziggle.vscript.text.NativeTable
import dev.ziggle.vscript.text.TextFrontEnd

/**
 * How long it takes to LOAD a script — every phase of the front end, over import trees of every size.
 *
 * The language is fast once it is running; this measures the part before that, which nothing else did.
 * A script's first frame is behind a lex, a parse, a resolve of its whole import closure and an emit, and
 * an author who reaches for a barrel finds out what that costs only by waiting for it.
 *
 * **The four phases are measured as differences, through the public front end**, not by instrumenting the
 * compiler. `resolve` does lex + parse + resolve of the closure and stops; `compile` does that and emits.
 * So `emit = compile - resolve` and `parse = (lex+parse) - lex`, and every number is one a caller could
 * have measured themselves. Instrumenting the phases directly would measure a build that no one runs.
 *
 * **What the shake column means.** Function bodies are compiled ON DEMAND — `TextCompiler.chunkFor` goes
 * through `ProgramBuilder.indexOf`, which is only ever reached from a call site — so an unreached function
 * is never emitted. The column is `emitted/declared`: the gap is the tree shaking the compiler already
 * does, and the `emit` millisecond column is the whole of what a smarter shake could ever win back.
 *
 * Run it:
 * ```
 * gradlew.bat bench                          # the default sweep
 * gradlew.bat bench --args="--reps 40"       # steadier numbers
 * ```
 */
object LoadBench {

    /**
     * One phase, sampled.
     *
     * [best] as well as [median], because they answer different questions: the median is what an author
     * will actually wait for, and the best is what the code costs with the machine's noise taken off. A
     * regression that moves the best has moved the work; one that moves only the median has not.
     */
    class Timing(val cold: Long, val samples: LongArray) {
        val median: Double get() = samples.sorted().let { it[it.size / 2] } / 1e6
        val best: Double get() = (samples.minOrNull() ?: 0L) / 1e6
        val coldMs: Double get() = cold / 1e6
    }

    /**
     * Every phase, sampled TOGETHER — one rep runs all of them, in order.
     *
     * **Interleaved rather than a loop per phase, because the phases are read as DIFFERENCES.** Timing
     * `resolve` to completion and then timing `compile` to completion measures them in two different JIT
     * and GC states, and `compile - resolve` then carries the drift between those states rather than the
     * cost of emitting. The first version of this did exactly that and reported a fifty-module corpus as
     * taking LESS time to compile than to resolve — a negative phase, which is not a slow phase or a fast
     * one but a measurement that does not mean anything.
     *
     * The cold sample is kept apart because it is not noise: it is what a client pays on the first script
     * it ever loads, with none of this JIT-compiled yet. Averaging it in would hide both facts.
     */
    fun measureAll(warmup: Int, reps: Int, bodies: List<Pair<String, () -> Unit>>): Map<String, Timing> {
        val cold = LinkedHashMap<String, Long>()
        for ((name, body) in bodies) {
            val t = System.nanoTime()
            body()
            cold[name] = System.nanoTime() - t
        }
        repeat(warmup) { for ((_, body) in bodies) body() }
        val samples = bodies.associate { it.first to LongArray(reps) }
        for (i in 0 until reps) {
            for ((name, body) in bodies) {
                val t = System.nanoTime()
                body()
                samples.getValue(name)[i] = System.nanoTime() - t
            }
        }
        return bodies.associate { (name, _) -> name to Timing(cold.getValue(name), samples.getValue(name)) }
    }

    /** One phase on its own — for a caller with a single thing to time. */
    fun measure(warmup: Int, reps: Int, body: () -> Unit): Timing =
        measureAll(warmup, reps, listOf("it" to body)).getValue("it")

    /** What one corpus cost, in every phase. */
    class Row(
        val corpus: BenchCorpus,
        val lex: Timing,
        val lexParse: Timing,
        val resolve: Timing,
        val compile: Timing,
        val emittedChunks: Int,
    ) {
        val parseMs: Double get() = (lexParse.median - lex.median).coerceAtLeast(0.0)
        val resolveOnlyMs: Double get() = (resolve.median - lexParse.median).coerceAtLeast(0.0)
        val emitMs: Double get() = (compile.median - resolve.median).coerceAtLeast(0.0)
    }

    /**
     * Every phase of [corpus], measured.
     *
     * The natives table is EMPTY on purpose — see [BenchCorpus]. A generated document calls nothing but
     * itself and its imports, so an empty catalogue is not a handicap, it is the absence of one.
     */
    fun run(corpus: BenchCorpus, warmup: Int = 3, reps: Int = 15): Row {
        val natives = NativeTable()
        val sources = corpus.modules.values.toList() + corpus.rootSource

        // Proof before measurement. A corpus that does not compile is a corpus whose timings are about
        // error paths, and the number would look FASTER for it — which is the failure mode that makes a
        // benchmark actively misleading rather than merely absent.
        val proof = TextFrontEnd(natives, imports = corpus.source).compile(corpus.rootSource)
        check(proof.ok) {
            "corpus '${corpus.name}' does not compile:\n" +
                proof.errors.take(5).joinToString("\n") { "  ${it.span} ${it.message}" }
        }
        val emitted = TextFrontEnd(natives, imports = corpus.source)
            .also { it.compile(corpus.rootSource) }
            .program.link().size

        val timings = measureAll(
            warmup, reps,
            listOf(
                "lex" to { for (s in sources) dev.ziggle.vscript.lang.Lexer(s).lex(); Unit },
                "lexParse" to {
                    for (s in sources) dev.ziggle.vscript.lang.Parser(dev.ziggle.vscript.lang.Lexer(s).lex()).parse()
                    Unit
                },
                // A FRESH front end every rep. Reusing one would share the `ProgramBuilder` and the site
                // table across reps, so the second rep would find every function already emitted and
                // measure nothing.
                "resolve" to { TextFrontEnd(natives, imports = corpus.source).resolve(corpus.rootSource); Unit },
                "compile" to { TextFrontEnd(natives, imports = corpus.source).compile(corpus.rootSource); Unit },
            ),
        )
        return Row(
            corpus,
            timings.getValue("lex"),
            timings.getValue("lexParse"),
            timings.getValue("resolve"),
            timings.getValue("compile"),
            emitted,
        )
    }

    /** The sweep: every shape at every size, plus the used/unused axis the shake question turns on. */
    fun sweep(): List<BenchCorpus> = listOf(
        BenchCorpus.flat(10, 12, 20),
        BenchCorpus.flat(50, 12, 20),
        BenchCorpus.flat(200, 12, 20),
        BenchCorpus.barrel(10, 12, 20),
        BenchCorpus.barrel(50, 12, 20),
        BenchCorpus.barrel(200, 12, 20),
        // The same tree, used to the hilt. The gap between this and `barrel-200` above is EXACTLY what
        // emitting the whole program instead of the reached part costs — the tree-shaking question, asked
        // as an experiment rather than as an opinion.
        BenchCorpus.barrel(200, 12, 2400),
        BenchCorpus.deep(50, 12, 12),
        BenchCorpus.diamond(50, 12, 20),
        BenchCorpus.diamond(200, 12, 20),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val warmup = argOf(args, "--warmup")?.toInt() ?: 3
        val reps = argOf(args, "--reps")?.toInt() ?: 15

        println("=== vs front-end load benchmark ===")
        println(
            "jvm ${System.getProperty("java.version")} on ${System.getProperty("os.name")}, " +
                "${Runtime.getRuntime().availableProcessors()} cpus, warmup=$warmup reps=$reps",
        )
        println()
        report(sweep().map { run(it, warmup, reps) })
    }

    /** The table, and the two sentences of arithmetic worth doing on it. */
    fun report(rows: List<Row>) {
        println(
            "%-14s %5s %7s %6s | %7s %7s %7s %7s | %8s %8s | %s".format(
                "shape", "docs", "lines", "used", "lex", "parse", "resolve", "emit", "TOTAL", "cold", "emitted/declared",
            ),
        )
        println("-".repeat(118))
        for (r in rows) {
            println(
                "%-14s %5d %7d %6d | %7.2f %7.2f %7.2f %7.2f | %8.2f %8.2f | %d/%d (%.0f%%)".format(
                    r.corpus.name,
                    r.corpus.documentCount,
                    r.corpus.lines,
                    r.corpus.used,
                    r.lex.median,
                    r.parseMs,
                    r.resolveOnlyMs,
                    r.emitMs,
                    r.compile.median,
                    r.compile.coldMs,
                    r.emittedChunks,
                    r.corpus.declaredFunctions,
                    100.0 * r.emittedChunks / r.corpus.declaredFunctions.coerceAtLeast(1),
                ),
            )
        }
        println()
        println("all times are MILLISECONDS, median of the reps; 'cold' is the first run, nothing JIT'd yet.")
        println("phases are differences through the public front end: parse = (lex+parse) - lex,")
        println("resolve = frontEnd.resolve - (lex+parse), emit = frontEnd.compile - frontEnd.resolve.")
        println()
        for (r in rows) {
            val perKLine = r.compile.median / r.corpus.lines * 1000
            println(
                "  %-14s %.3f ms / 1000 lines, %.3f ms / document, emit is %.0f%% of load".format(
                    r.corpus.name, perKLine, r.compile.median / r.corpus.documentCount,
                    100.0 * r.emitMs / r.compile.median.coerceAtLeast(0.001),
                ),
            )
        }
    }

    private fun argOf(args: Array<String>, flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }
}
