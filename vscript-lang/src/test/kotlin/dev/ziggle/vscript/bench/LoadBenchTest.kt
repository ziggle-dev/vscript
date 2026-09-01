package dev.ziggle.vscript.bench

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The half of the benchmark that ASSERTS — and it asserts the shape of the curve, never a millisecond.
 *
 * A timing threshold in a test suite is a coin flip on a busy machine: it fails on somebody's laptop while
 * a build server sails through, and the first thing anyone does is raise the number until it stops. What
 * does not move with the machine is the RATIO — cost per document at two hundred documents against cost
 * per document at fifty. Linear front ends hold that ratio near 1; a quadratic one doubles it, and that is
 * a regression worth failing a build for whatever the absolute numbers are.
 *
 * The other assertion is the one the whole tree-shaking question turns on: a barrel that forwards 2400
 * functions, of which the root reaches twenty, must not emit 2400 chunks.
 */
class LoadBenchTest {

    /**
     * A barrel does not drag its whole tree into the program.
     *
     * `TextCompiler.chunkFor` goes through `ProgramBuilder.indexOf` and nothing else calls it, so a
     * function no call site names is never compiled. That is tree shaking, already built, and this is the
     * test that says so — if it ever stops being true, `emit` becomes the dominant phase of every load and
     * the whole barrel idea gets expensive overnight.
     */
    @Test
    fun `an unreached function is never emitted`() {
        val corpus = BenchCorpus.barrel(leaves = 50, fnsPer = 12, used = 20)
        val row = LoadBench.run(corpus, warmup = 0, reps = 1)
        assertTrue(
            row.emittedChunks < corpus.declaredFunctions / 4,
            "a barrel over ${corpus.declaredFunctions} functions with ${corpus.used} used emitted " +
                "${row.emittedChunks} chunks — codegen has stopped being demand-driven",
        )
    }

    /**
     * Four times the documents costs about four times the time, not sixteen.
     *
     * Per-document rather than total, so the comparison is machine-independent: whatever the absolute
     * numbers, a linear front end gives the same per-document cost at both sizes. The ceiling is loose on
     * purpose — the small corpus pays fixed costs the large one amortises, so a genuinely linear front end
     * still measures a little cheaper per document at the top end, and only a real change of complexity
     * class gets anywhere near 3x.
     */
    @Test
    fun `load is linear in the size of the import tree`() {
        val small = LoadBench.run(BenchCorpus.barrel(50, 12, 20), warmup = 2, reps = 7)
        val large = LoadBench.run(BenchCorpus.barrel(200, 12, 20), warmup = 2, reps = 7)
        val smallPer = small.compile.best / small.corpus.documentCount
        val largePer = large.compile.best / large.corpus.documentCount
        assertTrue(
            largePer < smallPer * 3,
            "per-document load went from %.3f ms at %d documents to %.3f ms at %d — that is not linear"
                .format(smallPer, small.corpus.documentCount, largePer, large.corpus.documentCount),
        )
    }

    /**
     * The sweep, printed. Reported rather than asserted, for `TextCorpusTest`'s reason: the honest measure
     * is "what does it cost today", a number that should move, and a threshold would be met by shrinking
     * the corpus.
     */
    @Test
    fun `the sweep, reported`() {
        LoadBench.report(LoadBench.sweep().map { LoadBench.run(it, warmup = 2, reps = 7) })
    }
}
