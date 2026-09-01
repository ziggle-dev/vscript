package dev.ziggle.imgui

/**
 * Fuzzy name matching + ranking for the picker widgets (item/NPC search), generalised from the plugin
 * picker's matcher in the client chrome. A query is split into whitespace tokens; every token must
 * match the candidate (as an exact/prefix/substring/subsequence hit) for it to be a result, and the hit
 * strengths sum into a score so the list can be ranked GE-search style (exact ▸ prefix ▸ word-start ▸
 * substring ▸ scattered subsequence), shorter names breaking ties.
 */
object FuzzySearch {

    /** True if every char of [needle] appears in [haystack] in order (the classic fuzzy-match test). */
    fun isSubsequence(haystack: String, needle: String): Boolean {
        var i = 0
        for (c in haystack) if (i < needle.length && c == needle[i]) i++
        return i == needle.length
    }

    /**
     * Rank [name] against [query]: `null` when it doesn't match (drop it), otherwise a score where higher
     * is a better match. A blank query returns `0.0` so callers keep the natural order. Case-insensitive.
     */
    fun score(name: String, query: String): Double? =
        scoreLower(name.lowercase(), query.trim().lowercase(), name.length)

    /**
     * The ranking hot path for index searches: both sides already normalised — [haystackLower] lowercased
     * and [queryLower] trimmed+lowercased once by the caller — so a per-keystroke sweep over thousands of
     * candidates doesn't re-allocate a lowercased string each comparison. [nameLength] tie-breaks toward
     * shorter names (defaults to the haystack length). Returns `null` for no match, `0.0` for a blank query.
     */
    fun scoreLower(haystackLower: String, queryLower: String, nameLength: Int = haystackLower.length): Double? {
        if (queryLower.isEmpty()) return 0.0
        var total = 0.0
        for (token in queryLower.split(WS)) {
            if (token.isEmpty()) continue
            total += tokenScore(haystackLower, token) ?: return null // one unmatched token ⇒ not a result
        }
        return total - nameLength * 0.01 // gentle tie-break toward shorter names
    }

    /** Convenience boolean matcher (blank query matches everything), matching Chrome's `pluginMatches`. */
    fun matches(name: String, query: String): Boolean = score(name, query) != null

    private fun tokenScore(haystack: String, token: String): Double? {
        if (haystack == token) return 1000.0
        if (haystack.startsWith(token)) return 600.0
        val idx = haystack.indexOf(token)
        if (idx > 0) {
            val onWordStart = !haystack[idx - 1].isLetterOrDigit()
            return (if (onWordStart) 450.0 else 300.0) - idx // earlier & word-aligned hits rank higher
        }
        return if (isSubsequence(haystack, token)) 100.0 else null
    }

    private val WS = Regex("\\s+")
}
