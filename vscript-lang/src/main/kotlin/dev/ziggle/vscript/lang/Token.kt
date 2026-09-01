package dev.ziggle.vscript.lang

/**
 * Where something came from in the source.
 *
 * Carried by every token and every AST node, and the reason is the debugger. Everything downstream of the
 * graph is addressed by **node id** — `Op.TRACE` markers, `Breakpoints`, `SlotMap`, the inspector, the
 * canvas's flow animation — and none of that should have to change to gain a text surface. So the text side
 * keeps its own side table (`Spans`, produced by `Lower`) mapping node id to the span that produced it, and
 * "break on line 42" becomes a lookup rather than a protocol change.
 *
 * [start] and [end] are absolute offsets into the source, half-open. [line] and [col] are 1-based, because
 * they are for humans and every editor in the world counts from one.
 */
class Span(val start: Int, val end: Int, val line: Int, val col: Int) {

    /** A span covering both, for an AST node built from several tokens. */
    operator fun plus(other: Span): Span =
        Span(minOf(start, other.start), maxOf(end, other.end), line, col)

    override fun toString(): String = "$line:$col"

    companion object {
        /** For synthesised nodes that correspond to no source — the printer's side of the round trip. */
        val NONE = Span(0, 0, 0, 0)
    }
}

enum class TokenType {
    // literals
    INT, FLOAT, STRING, COLOR, TRUE, FALSE, NULL,

    /** A bare name. Keywords are lexed as [IDENT] and recognised by the parser — see the note below. */
    IDENT,

    // grouping and separators
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, COLON, DOT, ARROW, QUESTION, ELVIS, SAFE_DOT,

    /**
     * `::` — reserved for import qualification (`banking::withdraw`) and rejected until imports exist.
     *
     * Lexed from the start on purpose. Node types already qualify with `.` (`draw.tile`), so if imports
     * later took `.` as well the parser could not tell a namespaced node type from an imported symbol
     * without knowing the import set — a lexical question turned into a semantic one, and a migration to
     * undo it. Costing one token now forecloses nothing. See VSCRIPT_LANG_PLAN.md §7b.
     */
    COLONCOLON,

    // operators
    ASSIGN, EQ, NE, LT, LE, GT, GE,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    BANG, AND_AND, OR_OR,

    /**
     * `+=` and friends — an assignment that reads the target first.
     *
     * Tokens rather than a parser that sees `+` then `=`, because the two characters are only this operator
     * when they are adjacent: `x + =y` is a syntax error and `x += y` is a statement, and a whitespace-blind
     * parser could not tell them apart. The same reason `<=` is one token rather than `<` and `=`.
     */
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN,

    /** `@` — introduces a layout annotation (`@at`, `@id`, `@size`, `@folded`). */
    AT,

    /**
     * `// …` or `/* … */`.
     *
     * **Emitted rather than discarded, and never reaching the AST.** A comment belongs to the SOURCE
     * DOCUMENT and to nothing else — it is not lowered into the graph, not printed back out of one, and not
     * in the bytecode. The token exists anyway because two consumers need to see one: the code view
     * highlights from the token stream, and a PSI layer wants a stream that covers every character of the
     * file. [Parser] drops them on the way in, which is the single place that decision lives.
     */
    COMMENT,

    EOF,
}

/**
 * One token.
 *
 * [value] carries the *decoded* payload for literals — an `Int`, `Double`, the unescaped `String`, the
 * packed ARGB of a colour — so no consumer re-parses [text]. Null everywhere else.
 *
 * **Keywords are not their own token types.** They are lexed as [IDENT] and matched by the parser against
 * the text. That is deliberate: `count`, `text`, `first` and `set` are all node names as well as words a
 * person might reach for, and a lexer that promoted every keyword would make those unusable as identifiers
 * for no gain. The parser knows from position whether it is looking at a statement head or an expression,
 * which is exactly the context needed to tell them apart.
 */
class Token(
    val type: TokenType,
    val text: String,
    val span: Span,
    val value: Any? = null,
) {
    /** Is this the identifier [word]? The parser's keyword test — see the note on why they are idents. */
    fun isWord(word: String): Boolean = type == TokenType.IDENT && text == word

    override fun toString(): String = if (value != null) "$type($text=$value)" else "$type($text)"
}

/** A parse or lex failure, addressed to a place in the source. */
class VsSyntaxError(val span: Span, override val message: String) : RuntimeException("$span: $message")

/**
 * Something worth saying that is not a failure — an unrecognised annotation, say.
 *
 * Not a [VsSyntaxError]: a warning is not thrown, and making it a Throwable would invite someone to throw
 * it. The distinction matters for the annotation rule in VSCRIPT_LANG_PLAN.md §8b, where a typo must be
 * *visible* without being fatal, since refusing an annotation the language has not been taught is how you
 * make it unable to carry anything new.
 */
class VsDiagnostic(val span: Span, val message: String) {
    override fun toString(): String = "$span: $message"
}
