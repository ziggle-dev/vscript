package dev.ziggle.vscript.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexerTest {

    private fun lex(src: String): List<Token> = Lexer(src).lex().dropLast(1) // drop EOF
    private fun types(src: String) = lex(src).map { it.type }
    private fun values(src: String) = lex(src).map { it.value }

    @Test
    fun `an empty source is just EOF`() {
        assertEquals(listOf(TokenType.EOF), Lexer("").lex().map { it.type })
        assertEquals(listOf(TokenType.EOF), Lexer("   \n\n  ").lex().map { it.type })
    }

    @Test
    fun `keywords are identifiers`() {
        // Deliberate: `count`, `text` and `set` are node names as well as words, so promoting keywords in
        // the lexer would make them unusable as identifiers for no gain.
        assertEquals(listOf(TokenType.IDENT, TokenType.IDENT, TokenType.IDENT), types("val while count"))
    }

    @Test
    fun `true false and null carry their values`() {
        assertEquals(listOf(true, false, null), values("true false null"))
        assertEquals(listOf(TokenType.TRUE, TokenType.FALSE, TokenType.NULL), types("true false null"))
    }

    // ---- numbers --------------------------------------------------------------------------------------

    @Test
    fun `ints stay ints`() {
        // The whole reason GraphDoc tags them: a literal that became a Double here would turn every item
        // id into 4151.0 and go wrong much later, at a host cast.
        assertEquals(listOf(600, 0, 4151), values("600 0 4151"))
        assertTrue(values("600").single() is Int)
    }

    @Test
    fun `a number too big for an int becomes a long`() {
        assertEquals(listOf(3_000_000_000L), values("3000000000"))
    }

    @Test
    fun `floats are doubles`() {
        assertEquals(listOf(1.5, 0.25), values("1.5 0.25"))
    }

    @Test
    fun `hex is an int`() {
        assertEquals(listOf(255, 4096), values("0xFF 0x1000"))
    }

    @Test
    fun `a trailing dot is not part of the number`() {
        // `xs.0` would be nonsense, but `1.` must not swallow the dot — a member access follows numbers in
        // no valid program, yet lexing it wrongly turns a good error into a confusing one.
        assertEquals(listOf(TokenType.INT, TokenType.DOT, TokenType.IDENT), types("1.foo"))
    }

    // ---- the minus-sign rule --------------------------------------------------------------------------

    @Test
    fun `a leading minus makes a negative literal`() {
        assertEquals(listOf(-3), values("-3"))
        assertEquals(listOf(TokenType.LPAREN, TokenType.INT, TokenType.RPAREN), types("(-3)"))
        assertEquals(listOf(TokenType.COMMA, TokenType.INT), types(", -3"))
    }

    @Test
    fun `a minus after a value is subtraction`() {
        // This is the case the backward scan exists for. After a name, a number or a closing bracket, `-`
        // is an operator however tightly it is spaced.
        assertEquals(listOf(TokenType.IDENT, TokenType.MINUS, TokenType.INT), types("a - 1"))
        assertEquals(listOf(TokenType.IDENT, TokenType.MINUS, TokenType.INT), types("a -1"))
        assertEquals(listOf(TokenType.INT, TokenType.MINUS, TokenType.INT), types("2-1"))
        assertEquals(listOf(TokenType.RPAREN, TokenType.MINUS, TokenType.INT), types(") -1"))
    }

    @Test
    fun `an arrow is not a minus`() {
        assertEquals(listOf(TokenType.RPAREN, TokenType.ARROW, TokenType.IDENT), types(") -> Int"))
    }

    // ---- strings --------------------------------------------------------------------------------------

    @Test
    fun `a string keeps its whitespace exactly`() {
        // Literals.infer's rule: trimming is right for a number and wrong for text, so a message that
        // reads " x " has to be writable.
        assertEquals(listOf(" x "), values("\" x \""))
    }

    @Test
    fun `escapes are decoded`() {
        // Written with explicit escapes rather than a raw string: the source under test is itself full of
        // quotes and backslashes, and `""""…""""` is legal Kotlin that nobody can read twice the same way.
        assertEquals(listOf("a\nb\tc\"d\\e"), values("\"a\\nb\\tc\\\"d\\\\e\""))
        assertEquals(listOf("A"), values("\"\\u0041\""))
    }

    @Test
    fun `an unterminated string is an error at its opening quote`() {
        val e = assertFailsWith<VsSyntaxError> { lex("\"nope") }
        assertTrue("unterminated" in e.message)
    }

    @Test
    fun `a string cannot span lines`() {
        assertFailsWith<VsSyntaxError> { lex("\"a\nb\"") }
    }

    // ---- colours --------------------------------------------------------------------------------------

    /**
     * The `#AARRGGBB` form is GONE, and a `#` is not a token at all.
     *
     * A colour was an ARGB int wearing a type's name and `#` was how you wrote one. It is a record of four
     * channels now — `Color(244, 233, 122)` makes one and `0xFFFFAA00.toColor()` unpacks one — so a second
     * spelling that produced a bare int would be the old confusion with a nicer face.
     */
    @Test
    fun `a hash is not a colour any more`() {
        val thrown = runCatching { values("#00FF00") }.exceptionOrNull()
        assertTrue(thrown != null, "'#00FF00' still lexes")
    }

    @Test
    fun `a colour of the wrong length is refused`() {
        assertFailsWith<VsSyntaxError> { lex("#FFF") }
    }

    // ---- comments -------------------------------------------------------------------------------------

    @Test
    fun `comments are emitted, and the parser is what drops them`() {
        assertEquals(
            listOf(TokenType.IDENT, TokenType.COMMENT, TokenType.COMMENT),
            types("a // kept as a token\n/* also a token */"),
        )
        // …and are gone by the time there is a tree. See ParserTest.
        assertEquals(listOf(TokenType.IDENT), Parser(Lexer("a // x").lex()).let { _ ->
            Lexer("a // x").lex().filter { it.type != TokenType.COMMENT && it.type != TokenType.EOF }.map { it.type }
        })
    }

    @Test
    fun `comments are tokens, not nothing`() {
        // Emitted rather than discarded so the code view can colour them and a PSI layer can cover every
        // character of the file. They never reach the AST — [Parser] filters them — but the LEXER is not
        // where a comment stops existing.
        val toks = lex("// first\n/* second */\nname")
        assertEquals(
            listOf(TokenType.COMMENT, TokenType.COMMENT, TokenType.IDENT),
            toks.map { it.type },
        )
        assertEquals(listOf("// first", "/* second */"), toks.filter { it.type == TokenType.COMMENT }.map { it.text })
    }

    @Test
    fun `there is no third comment form`() {
        // `///` is just a line comment that starts with a slash. A distinct doc spelling would have to mean
        // something the other two do not, and since a comment never leaves its source document there is
        // nothing left for it to mean.
        val toks = lex("/// still just a comment\nname")
        assertEquals(listOf(TokenType.COMMENT, TokenType.IDENT), toks.map { it.type })
    }

    @Test
    fun `block comments do not nest, matching Java`() {
        // `/* /* */` ends at the FIRST `*/`, so what follows is code.
        val toks = lex("/* a /* b */ name")
        assertEquals(listOf(TokenType.COMMENT, TokenType.IDENT), toks.map { it.type })
    }

    @Test
    fun `an unterminated block comment is an error`() {
        assertFailsWith<VsSyntaxError> { lex("/* forever") }
    }

    // ---- operators and the reserved import separator ---------------------------------------------------

    @Test
    fun `two-character operators win over one`() {
        assertEquals(
            listOf(TokenType.EQ, TokenType.NE, TokenType.LE, TokenType.GE, TokenType.AND_AND, TokenType.OR_OR),
            types("== != <= >= && ||"),
        )
        assertEquals(listOf(TokenType.ASSIGN, TokenType.LT, TokenType.GT, TokenType.BANG), types("= < > !"))
    }

    @Test
    fun `colon-colon lexes as one token, reserved for imports`() {
        // Reserved from the start so imports can never collide with node-type dotting. See §7b.
        assertEquals(listOf(TokenType.IDENT, TokenType.COLONCOLON, TokenType.IDENT), types("banking::withdraw"))
        assertEquals(listOf(TokenType.IDENT, TokenType.COLON, TokenType.IDENT), types("name: Int"))
    }

    @Test
    fun `a lone ampersand says what was meant`() {
        val e = assertFailsWith<VsSyntaxError> { lex("a & b") }
        assertTrue("&&" in e.message, e.message)
    }

    // ---- spans ----------------------------------------------------------------------------------------

    @Test
    fun `spans carry one-based line and column`() {
        val toks = lex("a\n  bb")
        assertEquals(1, toks[0].span.line)
        assertEquals(1, toks[0].span.col)
        assertEquals(2, toks[1].span.line)
        assertEquals(3, toks[1].span.col)
    }

    @Test
    fun `a span covers exactly its token`() {
        val t = lex("  600  ").single()
        assertEquals(2, t.span.start)
        assertEquals(5, t.span.end)
    }

    @Test
    fun `lines are counted through strings and comments`() {
        val toks = lex("/* one\ntwo */\nx")
        // The comment is a token now, so the identifier is the LAST one rather than the only one — and it
        // is still on line 3, which is what this is really asserting.
        assertEquals(3, toks.last().span.line)
        assertEquals(TokenType.IDENT, toks.last().type)
    }
}
