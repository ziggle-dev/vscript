package dev.ziggle.vscript.bench

import dev.ziggle.vscript.text.TextSource

/**
 * A GENERATED import tree, and the numbers that describe it.
 *
 * Generated rather than checked in, because the question the benchmark exists to answer is a question
 * about SCALE — "what does a massive import tree cost" — and a fixed corpus can only answer it at the one
 * size somebody happened to write. A generator answers it at every size, and the shape of the curve is the
 * finding: a front end that is linear in the tree and one that is quadratic in it look identical at ten
 * modules and decide whether two hundred is a second or a minute.
 *
 * **Nothing in here is a game verb.** The generated documents use language constructs only — arithmetic,
 * `if`, `while`, records, calls across `::` — so the corpus resolves against an EMPTY [NativeTable] and
 * this repo stays engine-free. The client's own benchmark measures the real scripts against the real
 * catalogue; this one measures the language.
 */
class BenchCorpus(
    /** What this shape is called in the report. */
    val name: String,
    /** The document being run — the ROOT, which nothing imports. */
    val rootSource: String,
    /** Every other document, by the reference it is imported as. */
    val modules: Map<String, String>,
    /**
     * How many of the corpus's functions the root actually REACHES.
     *
     * Recorded by the generator rather than counted afterwards, because it is the independent variable of
     * the whole tree-shaking experiment: hold the tree fixed, vary how much of it is used, and the part of
     * the load that moves is the part shaking could ever remove.
     */
    val used: Int,
) {

    /** Where an `import` in this corpus resolves. No filesystem — the sources are already in hand. */
    val source: TextSource = TextSource.of(modules)

    /** The root plus every module it can reach. */
    val documentCount: Int get() = modules.size + 1

    val lines: Int by lazy { (modules.values + rootSource).sumOf { it.count { c -> c == '\n' } + 1 } }

    val bytes: Int by lazy { (modules.values + rootSource).sumOf { it.length } }

    /**
     * How many `fn`s the corpus DECLARES, root included.
     *
     * Counted off the generator's own arithmetic rather than by parsing, so building the corpus costs
     * nothing the benchmark then has to subtract back out.
     */
    var declaredFunctions: Int = 0
        private set

    companion object {

        /**
         * One library document: some records, some functions, and calls into whatever it imports.
         *
         * Sized to look like the real corpus rather than like a micro-benchmark. `plugins/scripts/src` is
         * ~22k lines over ~50 documents, so a few hundred lines and a dozen functions per document is the
         * middle of the distribution — small enough that a two-hundred-module tree is a plausible future
         * and not a straw man.
         */
        private fun library(name: String, fns: Int, imports: List<Pair<String, String>>): String {
            val b = StringBuilder()
            b.append("graph \"$name\"\n\n")
            for ((alias, ref) in imports) b.append("import * as $alias from \"$ref\"\n")
            if (imports.isNotEmpty()) b.append('\n')

            b.append("export type ${name.uppercase()}Point {\n    x: INT,\n    y: INT,\n}\n\n")
            b.append("export const ${name.uppercase()}_LIMIT = 64\n\n")
            b.append("var ${name.uppercase()}Seen: INT = 0\n\n")

            for (i in 0 until fns) {
                // A mixture on purpose: an expression-bodied function is PURE and is re-expanded at every
                // use site, a block-bodied one is a chunk of its own. Emitting only one kind would measure
                // half the compiler.
                if (i % 3 == 0) {
                    b.append("export fn ${name}f$i(a: INT, b: INT) -> INT = a < b ? b - a : a - b\n\n")
                } else {
                    b.append(
                        """
                        |/** Function $i of $name — a body with a branch and a loop in it. */
                        |export fn ${name}f$i(a: INT, b: INT) -> INT {
                        |    var t = a + b
                        |    if t > ${name.uppercase()}_LIMIT {
                        |        t = t - ${name.uppercase()}_LIMIT
                        |    }
                        |    var n = 0
                        |    while n < 3 {
                        |        t = t + n
                        |        n = n + 1
                        |    }
                        |    ${name.uppercase()}Seen = ${name.uppercase()}Seen + 1
                        |    return t
                        |}
                        |
                        """.trimMargin(),
                    )
                }
            }

            // One call across each import, so the document genuinely depends on what it pulled in — a
            // module nothing reaches into is a weaker test of the resolver than one that does.
            imports.forEachIndexed { i, (alias, _) ->
                b.append("export fn ${name}via$i(a: INT) -> INT = $alias::${alias}f0(a: a, b: 1)\n\n")
            }
            return b.toString()
        }

        /** The root's `on start`: [used] calls, spread over [callables]. */
        private fun root(imports: List<Pair<String, String>>, callables: List<Pair<String, String>>, used: Int): String {
            val b = StringBuilder()
            b.append("graph \"bench-root\"\n\n")
            for ((alias, ref) in imports) b.append("import * as $alias from \"$ref\"\n")
            b.append("\nvar Acc: INT = 0\n\non start {\n")
            for (i in 0 until used) {
                val (alias, fn) = callables[i % callables.size]
                b.append("    Acc = Acc + $alias::$fn(a: $i, b: ${i + 1})\n")
            }
            b.append("}\n")
            return b.toString()
        }

        /**
         * **FLAT** — the root imports every library directly, and uses [used] of their functions.
         *
         * The honest baseline: no barrel, no forwarding, one `import` line per document. Whatever a barrel
         * costs is what this shape does not pay.
         */
        fun flat(leaves: Int, fnsPer: Int, used: Int): BenchCorpus {
            val modules = LinkedHashMap<String, String>()
            val imports = ArrayList<Pair<String, String>>()
            val callables = ArrayList<Pair<String, String>>()
            for (i in 0 until leaves) {
                val name = "m$i"
                modules["lib/$name"] = library(name, fnsPer, emptyList())
                imports += name to "lib/$name"
                for (f in 0 until fnsPer) callables += name to "${name}f$f"
            }
            return BenchCorpus("flat-$leaves", root(imports, callables, used), modules, used)
                .also { it.declaredFunctions = leaves * fnsPer }
        }

        /**
         * **BARREL** — one `mod` re-exports every library, and the root imports only that.
         *
         * The shape the whole question is about. `export *` is not sugar for import-then-export: the names
         * go PAST the barrel rather than into it, so the barrel itself declares nothing and compiles to
         * nothing. What it cannot avoid is RESOLVING what it forwards — a document cannot say what it
         * offers without having read it — and measuring that against [flat] is how you find out whether a
         * barrel costs anything beyond the documents behind it.
         */
        fun barrel(leaves: Int, fnsPer: Int, used: Int): BenchCorpus {
            val modules = LinkedHashMap<String, String>()
            val callables = ArrayList<Pair<String, String>>()
            val b = StringBuilder("graph \"mod\"\n\n")
            for (i in 0 until leaves) {
                val name = "m$i"
                modules["lib/$name"] = library(name, fnsPer, emptyList())
                b.append("export * from \"lib/$name\"\n")
                for (f in 0 until fnsPer) callables += "kit" to "${name}f$f"
            }
            modules["lib/mod"] = b.toString()
            val imports = listOf("kit" to "lib/mod")
            return BenchCorpus("barrel-$leaves", root(imports, callables, used), modules, used)
                .also { it.declaredFunctions = leaves * fnsPer }
        }

        /**
         * **DEEP** — a chain, each document importing the one below it.
         *
         * Depth is the axis a memo table gets wrong differently from breadth: a chain has no diamond to
         * deduplicate, so it measures the per-hop cost with nothing amortising it.
         */
        fun deep(depth: Int, fnsPer: Int, used: Int): BenchCorpus {
            val modules = LinkedHashMap<String, String>()
            for (i in 0 until depth) {
                val name = "m$i"
                val imports = if (i == 0) emptyList() else listOf("m${i - 1}" to "lib/m${i - 1}")
                modules["lib/$name"] = library(name, fnsPer, imports)
            }
            val top = "m${depth - 1}"
            val callables = (0 until fnsPer).map { top to "${top}f$it" }
            return BenchCorpus(
                "deep-$depth",
                root(listOf(top to "lib/$top"), callables, used),
                modules,
                used,
            ).also { it.declaredFunctions = depth * fnsPer }
        }

        /**
         * **DIAMOND** — [width] libraries over one shared base, all reached through a barrel.
         *
         * The realistic shape, and the one that catches the bug the linker already paid for once: a
         * document reached by two paths must be resolved, seeded and emitted ONCE. If it is not, this is
         * the shape where the cost shows up as a multiplier rather than as a wrong answer.
         */
        fun diamond(width: Int, fnsPer: Int, used: Int): BenchCorpus {
            val modules = LinkedHashMap<String, String>()
            modules["lib/base"] = library("base", fnsPer, emptyList())
            val callables = ArrayList<Pair<String, String>>()
            val b = StringBuilder("graph \"mod\"\n\n")
            for (i in 0 until width) {
                val name = "m$i"
                modules["lib/$name"] = library(name, fnsPer, listOf("base" to "lib/base"))
                b.append("export * from \"lib/$name\"\n")
                for (f in 0 until fnsPer) callables += "kit" to "${name}f$f"
            }
            modules["lib/mod"] = b.toString()
            return BenchCorpus(
                "diamond-$width",
                root(listOf("kit" to "lib/mod"), callables, used),
                modules,
                used,
            ).also { it.declaredFunctions = (width + 1) * fnsPer }
        }
    }
}
