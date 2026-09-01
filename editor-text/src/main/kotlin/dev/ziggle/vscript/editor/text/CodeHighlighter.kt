package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.TokenType
import dev.ziggle.vscript.lang.VsSyntaxError

/**
 * Source → coloured runs, using **the compiler's own lexer**.
 *
 * That is the whole design. A code view usually grows a second, approximate tokenizer — a pile of regexes
 * that agree with the real one until they do not, and then colour a string as an identifier or miss a
 * comment. Here `Lexer` is already exact, already tested, and already knows every span, so the highlighter
 * is a mapping from token type to a role and nothing else. Anything the language learns, the colours learn.
 *
 * **Keywords are not a token type** — the lexer emits them as `IDENT` on purpose, so that `count`, `text`
 * and `set` stay usable as names (see `Token`). So the one thing this adds is the keyword *set*, taken from
 * the words `Parser` actually matches on.
 *
 * Degrades rather than fails: the lexer throws on the first thing it cannot read, which while somebody is
 * mid-keystroke is most of the time. Everything up to that point is already tokenized and gets coloured;
 * the rest is left plain. Colour that flickers off for the whole file on every unclosed quote is worse than
 * colour that stops where the trouble starts.
 */
object CodeHighlighter {

    enum class Role { PLAIN, KEYWORD, NUMBER, STRING, COMMENT, ANNOTATION, TYPE, OPERATOR }

    /** One coloured stretch of source, half-open. */
    class Run(val start: Int, val end: Int, val role: Role)

    /** The words `Parser` matches. Not a token type — see the class note. */
    private val KEYWORDS = setOf(
        "graph", "type", "var", "const", "fn", "on", "comment",
        "let", "if", "else", "while", "for", "in", "return", "break", "continue", "sequence", "with",
    )

    /** Written where a value goes and lexed as an ident, but spelled like a literal. */
    private val VALUE_WORDS = setOf("true", "false", "null", "tile")

    private val OPERATORS = setOf(
        TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT,
        TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE,
        TokenType.AND_AND, TokenType.OR_OR, TokenType.BANG, TokenType.ASSIGN, TokenType.ARROW,
        TokenType.QUESTION, TokenType.COLON,
    )

    fun runs(source: String): List<Run> {
        val tokens = try {
            Lexer(source).lex()
        } catch (e: VsSyntaxError) {
            // Colour what was readable. `Lexer` reports where it stopped, so the prefix is still valid
            // source and still worth colouring — and re-lexing it cannot throw again.
            val upto = e.span.start.coerceIn(0, source.length)
            if (upto == 0) return emptyList()
            return runCatching { Lexer(source.substring(0, upto)).lex() }.getOrNull()?.let { colour(it, source) }
                ?: emptyList()
        }
        return colour(tokens, source)
    }

    private fun colour(tokens: List<dev.ziggle.vscript.lang.Token>, source: String): List<Run> {
        val out = ArrayList<Run>(tokens.size)
        var afterAt = false
        for (t in tokens) {
            if (t.type == TokenType.EOF) break
            val role = when {
                t.type == TokenType.AT -> { afterAt = true; Role.ANNOTATION }
                // The name right after an `@` belongs to the annotation, not to the code.
                afterAt && t.type == TokenType.IDENT -> Role.ANNOTATION
                t.type == TokenType.STRING -> Role.STRING
                t.type == TokenType.COMMENT -> Role.COMMENT
                t.type == TokenType.INT || t.type == TokenType.FLOAT || t.type == TokenType.COLOR -> Role.NUMBER
                t.type == TokenType.TRUE || t.type == TokenType.FALSE || t.type == TokenType.NULL -> Role.NUMBER
                t.type in OPERATORS -> Role.OPERATOR
                t.type == TokenType.IDENT && t.text in KEYWORDS -> Role.KEYWORD
                t.type == TokenType.IDENT && t.text in VALUE_WORDS -> Role.NUMBER
                // A capitalised bare word in this language is a type or a record: `Int`, `List<Item>`,
                // `FruitPack { … }`. Cheap, and wrong only for a node name somebody capitalised.
                t.type == TokenType.IDENT && t.text.firstOrNull()?.isUpperCase() == true -> Role.TYPE
                else -> Role.PLAIN
            }
            if (t.type != TokenType.AT) afterAt = false
            if (role != Role.PLAIN) out += Run(t.span.start, t.span.end.coerceAtMost(source.length), role)
        }
        return out
    }
}
