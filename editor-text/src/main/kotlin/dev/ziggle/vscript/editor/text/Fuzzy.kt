package dev.ziggle.vscript.editor.text

/**
 * Subsequence matching with camel-hump awareness — what makes a search box feel like one.
 *
 * ### Why not `contains`
 *
 * `contains` cannot find `withdrawAll` from `wa`, and that abbreviation is the whole reason people reach
 * for a search box instead of a file tree. A subsequence match finds it; the difficulty is that a
 * subsequence match finds *far too much* — `wa` is also in `Bank.wants` and `walkTo` and a hundred others
 * — so the work is not the matching, it is the **ranking**.
 *
 * ### What is rewarded, and why
 *
 * In order of weight, and each of these is a guess about intent rather than a measure of similarity:
 *
 *  - **The whole name** — if you typed it exactly, you know what you want.
 *  - **A prefix** — you are typing the name from the start, which is what people do when they know it.
 *  - **A word start** — `wa` for `withdrawAll` hits `w` and the `A` hump. This is the case abbreviations
 *    are made of, so a match landing on boundaries scores far above one landing mid-word.
 *  - **Adjacency** — consecutive characters beat scattered ones. `bank` in `bankAll` is a real match;
 *    `bank` spread across `bringAnkleWorkNow` is a coincidence.
 *
 * Case-insensitive, but an exact-case hit breaks a tie: someone who typed `Bank` more likely wants the
 * record than the local called `bank`.
 */
object Fuzzy {

    class Match(val score: Int, val positions: List<Int>)

    private const val EXACT = 1_000
    private const val PREFIX = 300

    /**
     * A word start outscores the FIRST step of a contiguous run, and that ordering is load-bearing.
     *
     * `wA` must rank `withdrawAll` above `warlock`: the first hits a hump, the second merely has its two
     * letters side by side. Make them equal and the shorter name wins on length alone, which puts the
     * coincidence above the abbreviation somebody actually typed.
     */
    private const val WORD_START = 120

    /**
     * Consecutive matches COMPOUND, which is what makes a contiguous run beat a scattered one.
     *
     * A flat per-character bonus does not: `bank` in `bringAnkleNow` picks up a word start on the `A` and
     * ends up level with `bankNow`. Growing the bonus along the run means four characters in a row are
     * worth far more than four characters that merely appear in order — which is the difference between a
     * match and a coincidence.
     */
    private const val ADJACENT = 60
    private const val ADJACENT_GROWTH = 30

    private const val PLAIN = 1
    private const val CASE_BONUS = 3

    /**
     * Where [query] matches in [name], and how well — or null when it does not match at all.
     *
     * **A small dynamic program, because greedy gets the headline case wrong.** Taking the first
     * occurrence of each query character in turn is simpler and was the first version; it matches `wa`
     * against `withdrawAll` on the `a` of "withdr*a*w" and never reaches the `A` hump, so the one case a
     * search box exists for scored as a mid-word accident. There is no local rule that fixes it: whether
     * the earlier `a` or the later `A` is right depends on what comes AFTER, which is what a DP is.
     *
     * `O(name x query)` — a running best of the previous row supplies every non-adjacent predecessor, so
     * no inner scan is needed. For names of a dozen characters this is nothing, and it is exact.
     */
    fun match(name: String, query: String): Match? {
        if (query.isEmpty()) return null
        if (query.length > name.length) return null

        if (name.equals(query, ignoreCase = true)) {
            return Match(EXACT + if (name == query) CASE_BONUS else 0, name.indices.toList())
        }

        val n = name.length
        val m = query.length
        val none = Int.MIN_VALUE / 4

        var prev = IntArray(n) { none }
        // Where the match for query[j] at name[i] came from, so the positions can be walked back out.
        val from = Array(m) { IntArray(n) { -1 } }

        for (j in 0 until m) {
            val cur = IntArray(n) { none }
            // Best score anywhere strictly left of i in the previous row, and where it was.
            var bestLeft = none
            var bestLeftAt = -1
            for (i in 0 until n) {
                // The guard has to come FIRST: reading `prev[i - 1]` to test it is an index of -1 when
                // `i` is 0, which is every row's first character.
                if (j > 0 && i > 0) {
                    val left = prev[i - 1]
                    if (left > bestLeft) { bestLeft = left; bestLeftAt = i - 1 }
                }
                if (!name[i].equals(query[j], ignoreCase = true)) continue

                val base = if (isWordStart(name, i)) WORD_START else PLAIN
                val exactCase = if (name[i] == query[j]) CASE_BONUS else 0

                if (j == 0) {
                    cur[i] = base + exactCase + if (i == 0) PREFIX else 0
                    from[j][i] = -1
                    continue
                }
                // Two ways in: adjacent to the previous match (a growing run), or from anywhere left of it.
                val adjacent = if (i > 0 && prev[i - 1] > none) {
                    prev[i - 1] + maxOf(base, ADJACENT + runLength(from, j - 1, i - 1) * ADJACENT_GROWTH)
                } else {
                    none
                }
                val scattered = if (bestLeftAt >= 0) bestLeft + base else none
                if (adjacent >= scattered && adjacent > none) {
                    cur[i] = adjacent + exactCase
                    from[j][i] = i - 1
                } else if (scattered > none) {
                    cur[i] = scattered + exactCase
                    from[j][i] = bestLeftAt
                }
            }
            prev = cur
        }

        var endAt = -1
        var best = none
        for (i in 0 until n) if (prev[i] > best) { best = prev[i]; endAt = i }
        if (endAt < 0) return null

        val positions = IntArray(m)
        var at = endAt
        for (j in m - 1 downTo 0) {
            if (at < 0) return null // the chain broke; no alignment rather than a wrong one
            positions[j] = at
            at = from[j][at]
        }
        // A short name matched whole beats a long one matched sparsely: `bank` from `bank` is a better
        // answer than `bank` from `bankTheWholeInventory`, and without this they score the same.
        val coverage = ((m * 100) / n).coerceAtMost(100)
        return Match(best + coverage, positions.toList())
    }

    /**
     * How many matches immediately precede [i] in row [j] — the length of the run ending there.
     *
     * `at > 0` is the guard that matters: row 0 stores `-1` for "no parent", which is also what `at - 1`
     * is when `at` is 0, so without it the walk reads its own sentinel as a link and indexes at -1.
     */
    private fun runLength(from: Array<IntArray>, j: Int, i: Int): Int {
        var run = 0
        var row = j
        var at = i
        while (row > 0 && at > 0 && from[row][at] == at - 1) {
            run++
            at -= 1
            row -= 1
        }
        return run
    }

    /**
     * A character that starts a word: the first, one after a separator, or a capital following lowercase.
     *
     * The last is the camel hump, and it is why `wa` finds `withdrawAll`. `A` following `w` is a word
     * start; `l` following `A` is not, or every capitalised name would score as several words.
     */
    private fun isWordStart(name: String, i: Int): Boolean {
        if (i == 0) return true
        val prev = name[i - 1]
        if (prev == '_' || prev == '.' || prev == '/' || prev == '-' || prev == ' ') return true
        return name[i].isUpperCase() && prev.isLowerCase()
    }
}
