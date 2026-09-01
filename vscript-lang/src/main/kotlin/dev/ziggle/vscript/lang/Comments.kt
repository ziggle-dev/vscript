package dev.ziggle.vscript.lang

/**
 * Comments recovered from a `.vs`, addressed to the things they sat above.
 *
 * Kept in `main` while `VsText` itself moved to `testFixtures`: [Print] takes one, and printing a canvas
 * document out as `.vs` is a one-way export that survives the two authoring surfaces being separated.
 * The recovery side — reading them back OUT of source — went with the lowering, because that only ever
 * mattered for a round trip.
 */
class Comments(
    val nodes: Map<Int, List<String>> = emptyMap(),
    val decls: Map<String, List<String>> = emptyMap(),
    val trailing: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = nodes.isEmpty() && decls.isEmpty() && trailing.isEmpty()

    companion object {
        /** No comments to place — what a canvas graph has, and the default. */
        val NONE = Comments()

        /**
         * How many comment LINES [source] holds.
         *
         * A caller about to overwrite a file with a printed version wants to know what that would
         * destroy, and the honest unit is lines rather than comments: a block comment is one comment and
         * ten lines of prose. Counted with the compiler's own lexer, which is why the lexer emits comment
         * tokens instead of skipping them. A source too broken to lex reports zero: it cannot be
         * overwritten in a way that loses more than it already has.
         */
        @JvmStatic
        fun commentLines(source: String): Int = try {
            Lexer(source).lex()
                .filter { it.type == TokenType.COMMENT }
                .sumOf { it.text.count { c -> c == '\n' } + 1 }
        } catch (e: Exception) {
            0
        }
    }
}
