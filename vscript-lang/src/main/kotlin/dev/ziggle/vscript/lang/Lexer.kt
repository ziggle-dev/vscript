package dev.ziggle.vscript.lang

/**
 * Source text → tokens. Hand-written, one pass, no regex.
 *
 * **Newlines are not significant.** Statements are delimited by structure — a call ends at its closing
 * paren, a block at its brace — so there are no semicolons and no line rules. That is what lets the printer
 * choose its own line breaks without changing meaning, which the character-identical round trip in
 * VSCRIPT_LANG_PLAN.md §1 depends on.
 *
 * Two things are kept rather than skipped, because they are content:
 *
 *  - `/// …` doc comments, which are the body text of the `group` or `fn` they precede. A comment box's
 *    body is document data — it survives to the graph and back — so throwing it away here would make the
 *    round trip lossy for exactly the thing a comment box is for.
 *  - Nothing else. `//` and `/* */` are ordinary comments and are discarded; they correspond to nothing in
 *    the graph, so a graph printed back cannot reproduce them and pretending otherwise would be a lie.
 */
/**
 * Words after which a value may begin, so a `-` following one is a sign.
 *
 * Deliberately the four that can be followed by an expression, and no more: every other identifier keeps
 * meaning what it did, so `count -1` is still a subtraction.
 */
private val VALUE_MAY_FOLLOW = setOf("return", "while", "if", "in")

class Lexer(private val src: String) {

    private var pos = 0
    private var line = 1
    private var lineStart = 0

    private fun col() = pos - lineStart + 1

    private fun here(startPos: Int, startLine: Int, startCol: Int) =
        Span(startPos, pos, startLine, startCol)

    fun lex(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            val t = next() ?: continue
            out += t
            if (t.type == TokenType.EOF) return out
        }
    }

    /** The next token, or null when what was consumed was whitespace or an ordinary comment. */
    private fun next(): Token? {
        skipSpace()
        val startPos = pos
        val startLine = line
        val startCol = col()
        fun tok(type: TokenType, text: String, value: Any? = null) =
            Token(type, text, here(startPos, startLine, startCol), value)

        if (pos >= src.length) return tok(TokenType.EOF, "")

        val c = src[pos]

        // ---- comments, and the doc comments that are not comments -----------------------------------
        // Java's two forms, and only those. There is no `///`: a third spelling would have to mean
        // something the other two do not, and since a comment never leaves the source document there is
        // nothing left for it to mean. The comment above a declaration IS its documentation.
        if (c == '/' && peek(1) == '/') {
            while (pos < src.length && src[pos] != '\n') pos++
            return tok(TokenType.COMMENT, src.substring(startPos, pos))
        }
        if (c == '/' && peek(1) == '*') {
            pos += 2
            // Not nested, matching Java: `/* /* */` ends at the first `*/`. Nesting reads as a convenience
            // and is a trap, because commenting out a region containing a comment then behaves differently
            // depending on what is inside it.
            while (pos < src.length && !(src[pos] == '*' && peek(1) == '/')) advance()
            if (pos >= src.length) throw VsSyntaxError(here(startPos, startLine, startCol), "unterminated /* comment")
            pos += 2
            return tok(TokenType.COMMENT, src.substring(startPos, pos))
        }

        // ---- literals -------------------------------------------------------------------------------
        if (c == '"') return string(startPos, startLine, startCol)
        if (c.isDigit()) return number(startPos, startLine, startCol)
        // A '-' begins a number only where a value can start; the parser cannot help us here, so the rule
        // is lexical: `-` immediately followed by a digit is a negative literal. There is no unary minus
        // operator (VSCRIPT_LANG_PLAN.md §6.7 — its recognizer would collide with an authored `0 - x`), so
        // this cannot steal a subtraction: `a - 1` has a space, and `a -1` is not valid syntax either way.
        if (c == '-' && peek(1)?.isDigit() == true && valueMayStart(startPos)) {
            return number(startPos, startLine, startCol)
        }

        if (c.isLetter() || c == '_') {
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val text = src.substring(startPos, pos)
            return when (text) {
                "true" -> tok(TokenType.TRUE, text, true)
                "false" -> tok(TokenType.FALSE, text, false)
                "null" -> tok(TokenType.NULL, text, null)
                else -> tok(TokenType.IDENT, text)
            }
        }

        // ---- punctuation and operators ---------------------------------------------------------------
        pos++
        fun two(next: Char, type: TokenType): Token? =
            if (pos < src.length && src[pos] == next) { pos++; tok(type, src.substring(startPos, pos)) } else null

        return when (c) {
            '(' -> tok(TokenType.LPAREN, "(")
            ')' -> tok(TokenType.RPAREN, ")")
            '{' -> tok(TokenType.LBRACE, "{")
            '}' -> tok(TokenType.RBRACE, "}")
            '[' -> tok(TokenType.LBRACKET, "[")
            ']' -> tok(TokenType.RBRACKET, "]")
            ',' -> tok(TokenType.COMMA, ",")
            '.' -> tok(TokenType.DOT, ".")
            // `?:` before `?`, and ONLY when the colon is the very next character. `a ? b : c` is a
            // ternary and `a ?: b` is an elvis, so the two are told apart exactly the way Kotlin would
            // have to if it had a ternary: by adjacency. `a ? : b` is then a ternary with an empty true
            // arm, which is a parse error, which is what it is.
            '?' -> two('.', TokenType.SAFE_DOT) ?: two(':', TokenType.ELVIS) ?: tok(TokenType.QUESTION, "?")
            '@' -> tok(TokenType.AT, "@")
            '+' -> two('=', TokenType.PLUS_ASSIGN) ?: tok(TokenType.PLUS, "+")
            '*' -> two('=', TokenType.STAR_ASSIGN) ?: tok(TokenType.STAR, "*")
            // `//` and `/*` were already taken above, so a `/` reaching here can only be division or `/=`.
            '/' -> two('=', TokenType.SLASH_ASSIGN) ?: tok(TokenType.SLASH, "/")
            '%' -> two('=', TokenType.PERCENT_ASSIGN) ?: tok(TokenType.PERCENT, "%")
            ':' -> two(':', TokenType.COLONCOLON) ?: tok(TokenType.COLON, ":")
            // `->` first, then `-=`. A negative literal cannot reach here: that branch runs far earlier and
            // needs a DIGIT after the sign, so `x -= 1` and `x -1` never compete.
            '-' -> two('>', TokenType.ARROW) ?: two('=', TokenType.MINUS_ASSIGN) ?: tok(TokenType.MINUS, "-")
            '=' -> two('=', TokenType.EQ) ?: tok(TokenType.ASSIGN, "=")
            '!' -> two('=', TokenType.NE) ?: tok(TokenType.BANG, "!")
            '<' -> two('=', TokenType.LE) ?: tok(TokenType.LT, "<")
            '>' -> two('=', TokenType.GE) ?: tok(TokenType.GT, ">")
            '&' -> two('&', TokenType.AND_AND)
                ?: throw VsSyntaxError(here(startPos, startLine, startCol), "single '&' means nothing here — did you mean '&&'?")
            '|' -> two('|', TokenType.OR_OR)
                ?: throw VsSyntaxError(here(startPos, startLine, startCol), "single '|' means nothing here — did you mean '||'?")
            else -> throw VsSyntaxError(here(startPos, startLine, startCol), "unexpected character '$c'")
        }
    }

    // ---- pieces ---------------------------------------------------------------------------------------

    /**
     * The last non-whitespace character before [before], or null at the start of the file.
     *
     * Scanned rather than tracked in a field. A field has to be updated on every path that consumes a
     * character, and the moment one path forgets, the only symptom is `a -1` lexing as two tokens in some
     * contexts and one in others — a bug that would surface as a mysterious parse error a long way from
     * the lexer. Scanning backwards cannot fall out of step with anything.
     */
    private fun prevSignificant(before: Int): Char? {
        var i = before - 1
        while (i >= 0 && src[i].isWhitespace()) i--
        return if (i >= 0) src[i] else null
    }

    /** After one of these, a `-` is subtraction; anywhere else it begins a negative literal. */
    private fun endsValue(c: Char?): Boolean =
        c != null && (c.isLetterOrDigit() || c == '_' || c == ')' || c == ']' || c == '}' || c == '"')

    /**
     * Can a value begin at [at] — i.e. is a following `-` a sign rather than subtraction?
     *
     * The character before decides it, except for one case the character cannot see: a KEYWORD is spelled
     * like a name and ends in a letter, so `return -1` looked exactly like `count -1` and lexed the minus
     * as subtraction. `return` then had an operator where its value should be and the file would not
     * parse — for a line nobody would think to question.
     *
     * This is also the premise the rejection of unary minus rests on (VSCRIPT_LANG_PLAN.md §6.7): negative
     * literals are lexed as literals, "which covers the real cases". It did not cover this one. Fixing it
     * here rather than adding a prefix operator keeps that decision intact — `-x` would lower to
     * `sub(0, x)` and become indistinguishable from an authored `0 - x` on the way back out.
     */
    private fun valueMayStart(at: Int): Boolean {
        if (!endsValue(prevSignificant(at))) return true
        return wordBefore(at) in VALUE_MAY_FOLLOW
    }

    /** The identifier ending just before [at], or null when what precedes is not one. */
    private fun wordBefore(at: Int): String? {
        var i = at - 1
        while (i >= 0 && src[i].isWhitespace()) i--
        if (i < 0 || !(src[i].isLetterOrDigit() || src[i] == '_')) return null
        val end = i + 1
        while (i >= 0 && (src[i].isLetterOrDigit() || src[i] == '_')) i--
        return src.substring(i + 1, end)
    }

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) advance()
    }

    /** Consume one character, keeping the line/column counters straight. */
    private fun advance() {
        if (src[pos] == '\n') { line++; lineStart = pos + 1 }
        pos++
    }

    private fun peek(n: Int): Char? = src.getOrNull(pos + n)

    private fun string(startPos: Int, startLine: Int, startCol: Int): Token {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw VsSyntaxError(here(startPos, startLine, startCol), "unterminated string")
            val c = src[pos]
            if (c == '"') { pos++; break }
            if (c == '\n') throw VsSyntaxError(here(startPos, startLine, startCol), "unterminated string — a string cannot span lines")
            if (c != '\\') { sb.append(c); if (c == '\n') line++; pos++; continue }
            pos++
            val esc = src.getOrNull(pos) ?: throw VsSyntaxError(here(startPos, startLine, startCol), "unterminated escape")
            pos++
            sb.append(
                when (esc) {
                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                    '"' -> '"'; '\\' -> '\\'
                    'u' -> {
                        val hex = src.substring(pos, minOf(pos + 4, src.length))
                        val code = hex.toIntOrNull(16)
                            ?: throw VsSyntaxError(here(startPos, startLine, startCol), "bad \\u escape '$hex'")
                        pos += 4
                        code.toChar()
                    }
                    else -> throw VsSyntaxError(here(startPos, startLine, startCol), "unknown escape '\\$esc'")
                }
            )
        }
        return Token(TokenType.STRING, src.substring(startPos, pos), here(startPos, startLine, startCol), sb.toString())
    }

    /**
     * A number.
     *
     * Ints stay Ints. That is not fussiness — JSON's single number type is exactly why `GraphDoc` tags them
     * on the way out, and a literal that became a Double here would turn every item id into `4151.0` and
     * every Int pin into a Float, going wrong much later at a host cast.
     */
    private fun number(startPos: Int, startLine: Int, startCol: Int): Token {
        if (src[pos] == '-') pos++
        if (src[pos] == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            pos += 2
            val from = pos
            while (pos < src.length && (src[pos].isLetterOrDigit())) pos++
            val hex = src.substring(from, pos)
            val span = here(startPos, startLine, startCol)
            val v = hex.toLongOrNull(16) ?: throw VsSyntaxError(span, "'0x$hex' is not hexadecimal")
            return Token(TokenType.INT, src.substring(startPos, pos), span, narrow(v, span))
        }
        while (pos < src.length && src[pos].isDigit()) pos++
        var isFloat = false
        if (pos < src.length && src[pos] == '.' && peek(1)?.isDigit() == true) {
            isFloat = true
            pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        val text = src.substring(startPos, pos)
        val span = here(startPos, startLine, startCol)
        return if (isFloat) {
            Token(TokenType.FLOAT, text, span, text.toDoubleOrNull() ?: throw VsSyntaxError(span, "'$text' is not a number"))
        } else {
            Token(TokenType.INT, text, span, narrow(text.toLongOrNull() ?: throw VsSyntaxError(span, "'$text' is not a number"), span))
        }
    }

    /** Int where it fits, Long where it does not — the two the VM's constant pool already distinguishes. */
    private fun narrow(v: Long, span: Span): Any =
        if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else v
}
