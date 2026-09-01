package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.ImportItem

/**
 * Tokens → [Program]. Recursive descent, one function per grammar rule, precedence by nesting.
 *
 * **It resolves nothing.** No name is looked up, no pin type consulted, no node type decided — the tree
 * mirrors what was written and `Lower` gives it meaning. That separation is why the parser can be tested
 * without a `NodeCatalog`, and why a catalog change cannot break parsing.
 *
 * **Errors are collected, not thrown.** A parser that dies on the first mistake is one that makes you fix a
 * file one error per run, and the text surface is meant to be usable without the canvas open. So a failure
 * inside a statement is recorded and the parser resynchronises at the next statement or declaration
 * boundary. The result carries whatever was understood alongside the list of what was not.
 */
private const val MAX_TYPE_LOOKAHEAD = 64

// `@JvmOverloads` because the IntelliJ plugin is JAVA, deliberately, and Java does not see Kotlin's
// default arguments — so adding a parameter here is a source-breaking change over there without it. The
// same reason `TextFrontEnd` carries one; this is the second time it has been the answer.
class Parser @JvmOverloads constructor(
    tokens: List<Token>,
    /**
     * The source these tokens came from, when the caller has it — so `assert` can quote what was written.
     *
     * **Only [AssertStmt] reads it, and it is optional for that reason.** A failure that says
     * `assertion failed: count(list: tasks) == 3` names the check; one that says `assertion failed` sends
     * the reader to count statements. Reconstructing the text from tokens instead would drop the author's
     * own spacing and get it subtly wrong, and [Span] already carries absolute offsets, so a slice is exact
     * and costs nothing. A caller with no source (a snippet built from tokens in a test) still parses; its
     * asserts simply quote nothing.
     */
    private val source: String? = null,
) {

    /**
     * The token stream with comments taken out.
     *
     * **The one place a comment stops existing.** A comment belongs to the source document: it is not
     * lowered into the graph, not printed back out of one, and not in the bytecode. The lexer still emits
     * them, because the code view highlights from the token stream and a PSI layer needs one that covers
     * every character — but nothing above this line has to know they were ever there, which is what stops
     * `// …` turning into a case in twenty different productions.
     */
    private val tokens: List<Token> = tokens.filter { it.type != TokenType.COMMENT }

    /**
     * The comments, keyed by the source offset of the token that FOLLOWS them.
     *
     * Comments still stop existing above this line — nothing in the tree knows about them, and no
     * production gained a case. What is kept is a side table saying WHERE each one sat, which is what lets
     * a text-to-text operation (the IDE's Reformat) put them back without a comment ever entering the
     * graph. A canvas graph has no such table and prints without comments, exactly as before.
     *
     * Keyed by the following token rather than by line, because printing changes line numbers and does not
     * change which construct a comment introduces.
     */
    private val commentsBefore: Map<Int, List<String>> = buildMap {
        val pending = ArrayList<String>()
        for (t in tokens) {
            if (t.type == TokenType.COMMENT) {
                pending += t.text
                continue
            }
            if (pending.isNotEmpty()) {
                // A construct may already have comments from an earlier run of them; keep the order.
                merge(t.span.start, ArrayList(pending)) { a, b -> a + b }
                pending.clear()
            }
        }
        // Trailing comments at end of file have no following token. Keyed by -1 so the printer can put
        // them back at the bottom rather than drop them.
        if (pending.isNotEmpty()) put(-1, ArrayList(pending))
    }

    /** What a parse produced: the tree, and everything that went wrong building it. */
    class Result(
        val program: Program,
        val errors: List<VsSyntaxError>,
        val warnings: List<VsDiagnostic> = emptyList(),
        /** Comments by the source offset of the token they precede; -1 holds any at end of file. */
        val commentsBefore: Map<Int, List<String>> = emptyMap(),
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    private var i = 0
    private val errors = ArrayList<VsSyntaxError>()
    private val warnings = ArrayList<VsDiagnostic>()

    /**
     * Depth counter suppressing struct literals.
     *
     * `if ready { }` and `Spot { tile: t }` begin identically, and the expression parser meets the first one
     * while reading a condition. Every language with both shapes solves it the same way: inside a header
     * that is followed by a block, `Ident {` is the block, not a record. Set for the condition of `if` and
     * `while` and the list of `for`, and cleared inside any bracketing, so `if f(Spot { a: 1 }) { }` still
     * reads the record — the ambiguity only exists at the top level of the header.
     */
    private var noStructLit = 0

    /**
     * Whether a bare value may stand as a statement — true only while reading a lambda's block.
     *
     * A counter would be wrong: a lambda nested inside a lambda's body is still a lambda body, and a
     * BLOCK nested inside one is not. Saved and restored around each body, so `{ val n = 1  if n > 0 { n } }`
     * has the `if`'s block back under the ordinary rule.
     */
    private var inLambdaBody = false

    // ---- token helpers --------------------------------------------------------------------------------

    private fun peek(n: Int = 0): Token = tokens[minOf(i + n, tokens.size - 1)]
    private fun atEnd(): Boolean = peek().type == TokenType.EOF
    private fun check(type: TokenType): Boolean = peek().type == type
    private fun checkWord(word: String): Boolean = peek().isWord(word)

    private fun advance(): Token = tokens[i].also { if (!atEnd()) i++ }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun matchWord(word: String): Boolean {
        if (!checkWord(word)) return false
        advance()
        return true
    }

    private fun expect(type: TokenType, what: String): Token {
        if (check(type)) return advance()
        throw VsSyntaxError(peek().span, "expected $what, found '${peek().text.ifEmpty { "end of file" }}'")
    }

    private fun expectIdent(what: String): Token {
        if (check(TokenType.IDENT)) return advance()
        throw VsSyntaxError(peek().span, "expected $what, found '${peek().text.ifEmpty { "end of file" }}'")
    }

    private fun expectWord(word: String): Token {
        if (checkWord(word)) return advance()
        throw VsSyntaxError(peek().span, "expected '$word', found '${peek().text.ifEmpty { "end of file" }}'")
    }

    // ---- program --------------------------------------------------------------------------------------

    fun parse(): Result {
        val start = peek().span
        var name: String? = null
        val decls = ArrayList<Decl>()

        // **Collected, not thrown — the header is inside the contract too.**
        //
        // This used to sit outside the loop's `catch`, so a file whose header was not finished threw
        // straight out of `parse()` past every caller that had been told errors are collected. `graph`
        // with no name yet is not an exotic state: it is what a new file looks like after the first word,
        // and it took the whole document out of scope until the closing quote arrived.
        if (checkWord("graph")) {
            advance()
            try {
                name = expect(TokenType.STRING, "a name in quotes after 'graph'").value as String
            } catch (e: VsSyntaxError) {
                errors += e
            }
        }

        while (!atEnd()) {
            try {
                decls += declaration()
            } catch (e: VsSyntaxError) {
                errors += e
                if (!syncToDeclaration()) break
            }
        }
        return Result(Program(name, decls, start, defaultExport), errors, warnings, commentsBefore)
    }

    /**
     * Skip to somewhere a declaration could start, so one bad line does not cascade.
     *
     * Braces are counted rather than searched for: landing on the `fn` *inside* a broken block would report
     * a second error for something that is not wrong. Returns false when the file ran out, which ends the
     * loop rather than spinning on EOF.
     */
    private fun syncToDeclaration(): Boolean {
        var depth = 0
        while (!atEnd()) {
            when {
                check(TokenType.LBRACE) -> depth++
                check(TokenType.RBRACE) -> if (depth > 0) depth-- else { advance(); continue }
                depth == 0 && startsDeclaration() -> return true
            }
            advance()
        }
        return false
    }

    /**
     * Where the declaration currently being parsed began, or null outside one.
     *
     * A declaration's span has to cover its `@id(3)`, its `export` and its `always`, and it did not: each
     * decl started at its own keyword. Nothing noticed while the modifier was rare, and then `export`
     * became something most library declarations carry — at which point every doc comment above one
     * stopped being carried through a round trip, because `VsText.plan` joins a comment to a declaration
     * on the offset the construct STARTS at and the two no longer agreed.
     */
    private var declStart: Span? = null

    /**
     * [own] widened to include whatever annotations and modifiers preceded it.
     *
     * Consumed: the start is cleared as it is read, so the same builder called for a STATEMENT — `val` is
     * both a declaration and a statement — gets nothing left over from the last declaration.
     */
    private fun declFrom(own: Span): Span {
        val at = declStart ?: return own
        declStart = null
        return at + own
    }

    private fun startsDeclaration(): Boolean =
        checkWord("type") || checkWord("enum") || checkWord("var") || checkWord("const") ||
            checkWord("val") || checkWord("single") || checkWord("fn") || checkWord("fake") ||
            checkWord("on") ||
            checkWord("import") || checkWord("export") || checkWord("always")

    /**
     * `export default …` — the name of the declaration this document is for, or null.
     *
     * A field rather than something threaded out of [declaration], because a default is a property of the
     * DOCUMENT and only one declaration may carry it. Two of them is refused where the second is written,
     * which is the only place the mistake can be pointed at.
     */
    private var defaultExport: String? = null

    /** What `export` may go before — everything an import can NAME. */
    private fun startsExportable(): Boolean =
        checkWord("type") || checkWord("enum") || checkWord("var") || checkWord("val") ||
            checkWord("single") || checkWord("fn") || checkWord("fake") || checkWord("const") ||
            // `export inline fn` / `export op fn` — the modifier sits between the two words, so `export`
            // has to see past it.
            checkWord("inline") || checkWord("op") || checkWord("infix")

    private fun declaration(): Decl {
        // Where the whole construct begins, ANNOTATIONS AND MODIFIERS INCLUDED — see [declFrom]. Captured
        // before anything is consumed, because every one of those is part of the declaration a comment
        // above it is a comment on.
        declStart = peek().span
        val ann = annotations()
        // `export` — a document offers NOTHING unless it says so. The polarity is the whole point: what
        // crosses a boundary should be a decision somebody made, and under `private` it was the absence of
        // one. Every name kind an import can reach takes it, `const` included: a const is a literal node,
        // but `alias::LIMIT` resolves to one and so it is part of the surface like anything else.
        val isExported = if (checkWord("export")) { advance(); true } else false
        // `export default fn run(…)` — the one declaration this document IS. Recorded by name below,
        // after the declaration has been built and can say what it is called.
        val isDefault = isExported && checkWord("default") && run { advance(); true }
        // `export default { run, setup }` — a bundle rather than a declaration, told apart by the brace,
        // which no declaration can start with.
        if (isDefault && check(TokenType.LBRACE)) return defaultBundle(declStart ?: peek().span)
        // `export default OurHooks` — a declaration written ABOVE, named as the default.
        //
        // The other forms all attach `default` to a declaration being written here, which forces the one
        // thing a document is to be declared inline. That is the wrong way round for the shape the corpus
        // actually uses: a `val` is assembled from several imports and THEN offered, and having to inline
        // it means the assembly cannot be named, documented or referred to by the file that owns it.
        //
        // It is exactly `export { name }` plus the default mark — no new export machinery, and the
        // resolver's `withDefault` finds it under its own name like any other export.
        if (isDefault && !startsExportable() && check(TokenType.IDENT)) {
            val named = expectIdent("a declaration name after 'export default'")
            if (defaultExport != null) {
                throw VsSyntaxError(
                    named.span,
                    "'${defaultExport}' is already this document's default — there is one 'export " +
                        "default' per document, because an importer writing 'import x from …' names one " +
                        "thing",
                )
            }
            defaultExport = named.text
            return ExportListDecl(listOf(named.text), (declStart ?: named.span) + named.span)
        }
        // `export * from "y"`, `export { a } from "y"`, `export { a, b }` — told apart from a declaration
        // by the same one token, and from each other by whether `from` follows.
        if (isExported && !isDefault && (check(TokenType.STAR) || check(TokenType.LBRACE))) {
            return exportFrom(declStart ?: peek().span)
        }
        if (isExported && !startsExportable()) {
            // Named separately, because this is the mistake somebody migrating makes: an entry has no name
            // for anyone to say, so there is nothing for `export` to make nameable.
            if (checkWord("on")) {
                throw VsSyntaxError(
                    peek().span,
                    "an entry has no name to export — write 'always on ${peek(1).text}' if " +
                        "you mean it should run for whoever imports this document",
                )
            }
            throw VsSyntaxError(
                peek().span,
                "'export' goes before 'type', 'enum', 'var', 'val', 'single', 'const' or 'fn' — " +
                    "found '${peek().text}'",
            )
        }
        // `inline fn` / `op fn` — the third axis. Their own words rather than annotations, because an
        // annotation is metadata the compiler never reads (see CLAUDE.md) and both change what is emitted.
        //
        // Either order reads, and `op` implies `inline`: an operator that is not spliced trades a compiler
        // special case for a call frame on the hottest operation in the language.
        var isInline = if (checkWord("inline")) { advance(); true } else false
        val isOperator = if (checkWord("op")) { advance(); true } else false
        val isInfix = if (checkWord("infix")) { advance(); true } else false
        if (isOperator || isInfix) {
            // Both are syntax the language owns, and both are spliced for the same reason an operator is:
            // a word between two expressions must not cost a call frame.
            isInline = true
            if (checkWord("inline")) { advance() }
        }
        if ((isInline || isOperator || isInfix) && !checkWord("fn")) {
            val word = if (isOperator) "op" else if (isInfix) "infix" else "inline"
            throw VsSyntaxError(
                peek().span,
                "'$word' goes before 'fn' — found '${peek().text}'",
            )
        }
        // `always on tick` — the other axis, and deliberately its own word. See [EntryDecl.isAlways].
        val isAlways = if (checkWord("always")) { advance(); true } else false
        if (isAlways && !checkWord("on")) {
            throw VsSyntaxError(
                peek().span,
                "'always' goes before an entry — 'always on tick' — found '${peek().text}'",
            )
        }
        // Cleared by each decl builder once it has widened its own span, so a `val` STATEMENT parsed
        // later through the same function cannot inherit a declaration's start. See [declFrom].
        val decl = when {
            checkWord("import") -> importDecl()
            // **Only `type` and `single` may go unnamed**, and only as the default. Both are a SHAPE, and a
            // default export's shape is named by whoever imports it — `import Roster from "…"` — so a name
            // here is a name nobody ever says, and one more thing to collide with (which is exactly how a
            // `single Hooks` beside an `import { Hooks }` went wrong). A `fn`, `var`, `val`, `const` or
            // `enum` still needs one: they are reachable by name from inside the document that declares
            // them, and an anonymous one could not be called, read or written.
            checkWord("type") -> typeDecl(isExported, if (isDefault) ANONYMOUS_DEFAULT else null)
            checkWord("single") -> singleDecl(ann, isExported, if (isDefault) ANONYMOUS_DEFAULT else null)
            checkWord("enum") -> enumDecl(isExported)
            checkWord("var") -> varDecl(isExported)
            checkWord("val") -> valDecl(ann, isExported)
            checkWord("const") -> constDecl(ann, isExported)
            checkWord("fn") -> fnDecl(ann, isExported, isInline, isOperator, isInfix)
            checkWord("fake") -> fakeDecl()
            checkWord("on") -> entryDecl(ann, isAlways)
            checkWord("test") -> testDecl(ann)
            else -> throw VsSyntaxError(
                peek().span,
                "expected a declaration — 'import', 'type', 'single', 'enum', 'var', 'val', 'fn', " +
                    "'on', 'test' or 'fake' — " +
                    "found '${peek().text}'",
            )
        }
        if (isDefault) {
            val named = decl.declaredName
                ?: throw VsSyntaxError(
                    decl.span,
                    "'export default' needs a declaration with a name — only 'type' and 'single' may go " +
                        "unnamed, because an importer names those itself",
                )
            if (defaultExport != null) {
                throw VsSyntaxError(
                    decl.span,
                    "'${defaultExport}' is already this document's default — there is one 'export " +
                        "default' per document, because an importer writing 'import x from …' names one " +
                        "thing",
                )
            }
            defaultExport = named
        }
        return decl
    }

    /**
     * `import banking from "banking"`.
     *
     * The alias comes first because it is what the rest of the file will say, and the quoted reference is
     * a document NAME rather than a path: documents live in a store the language does not know the shape
     * of, so resolving it is the host's job (`GraphSource`) and a filesystem path here would bake one
     * particular store into the grammar.
     */
    /**
     * `import * as banking from "x"`, `import {abs, max as biggest} from "x"`, `import run from "x"`, and
     * the two combinations — `import run, {abs} from "x"` and `import run, * as x from "x"`.
     *
     * TypeScript's shapes, told apart by ONE token: a `*` is a namespace, a `{` is a list, an identifier is
     * the other document's default. Only the default may be followed by a comma and a second clause, which
     * is what keeps this a single-token decision with no lookahead.
     *
     * `import * from "x"` — every name unqualified — is gone. It had no TypeScript reading, nothing in the
     * corpus used it, and the one thing it was good for (an extension arriving unqualified) never needed
     * it: extensions resolve by name across every import whatever form brought the document in.
     */
    private fun importDecl(): ImportDecl {
        val start = declFrom(expectWord("import").span)
        var named = emptyList<ImportItem>()
        var alias: String? = null
        var default: String? = null
        when {
            // `import "core/list"` — everything, under its own name. Nothing is written before the quote,
            // so there is no `from` either: the line is the word and the document.
            check(TokenType.STRING) -> {
                val ref = advance()
                return ImportDecl(null, ref.value as String, start + ref.span, star = true)
            }
            check(TokenType.STAR) -> alias = namespaceClause()
            check(TokenType.LBRACE) -> named = importItems()
            else -> {
                default = expectIdent(
                    "'* as name', '{…}' or a name for the default — 'import * as bank from \"banking\"'"
                ).text
                // `d, …` — a default alongside one of the other two, which is the only place a comma may
                // appear before `from`.
                if (match(TokenType.COMMA)) {
                    if (check(TokenType.STAR)) alias = namespaceClause() else named = importItems()
                }
            }
        }
        expectWord("from")
        val ref = expect(TokenType.STRING, "the document to import, in quotes")
        return ImportDecl(alias, ref.value as String, start + ref.span, named, default)
    }

    /** `* as name` — the namespace clause, which is the only thing a `*` may be in an import. */
    private fun namespaceClause(): String {
        expect(TokenType.STAR, "'*'")
        if (!matchWord("as")) {
            throw VsSyntaxError(
                peek().span,
                "a namespace import needs a name — 'import * as bank from \"banking\"'. For everything " +
                    "under its own name, drop the star: 'import \"banking\"'",
            )
        }
        return expectIdent("a name for the namespace").text
    }

    /**
     * `export * from "y"`, `export { a, b as c } from "y"`, and the local `export { a, b }`.
     *
     * One reader for the three because they share their first token and differ only in whether a `from`
     * follows — which is exactly how TypeScript separates "pass these on" from "these of mine are public".
     */
    private fun exportFrom(start: Span): Decl {
        val all = match(TokenType.STAR)
        val items = if (all) emptyList() else importItems()
        if (!all && !checkWord("from")) {
            // `export { a, b }` — this document's own names, said in one place. No `as`: renaming
            // something on the way out of the file it is declared in would give it two names here.
            items.firstOrNull { it.isAliased }?.let {
                throw VsSyntaxError(
                    start,
                    "'${it.name} as ${it.local}' renames a name on its way out of the document that " +
                        "declares it — write 'export { ${it.name} }', or re-export it from somewhere else",
                )
            }
            return ExportListDecl(items.map { it.name }, start)
        }
        expectWord("from")
        val ref = expect(TokenType.STRING, "the document to re-export from, in quotes")
        return ReExportDecl(ref.value as String, start + ref.span, items, all)
    }

    /**
     * `export default { run, setup }` — the names this document bundles into its default.
     *
     * No renaming: an entry names a declaration in THIS file, and it already has a name here. `as` would
     * be asking for a field spelled differently from the thing it holds, which nothing needs.
     */
    private fun defaultBundle(start: Span): DefaultBundleDecl {
        expect(TokenType.LBRACE, "'{' to open the default's names")
        val names = ArrayList<String>()
        while (!check(TokenType.RBRACE) && !atEnd()) {
            val at = expectIdent("a name this document declares")
            if (at.text in names) throw VsSyntaxError(at.span, "'${at.text}' is named twice in this default")
            names += at.text
            if (!match(TokenType.COMMA)) break
        }
        val end = expect(TokenType.RBRACE, "'}' to close the default's names")
        if (names.isEmpty()) throw VsSyntaxError(start + end.span, "an 'export default { }' with no names offers nothing")
        if (defaultExport != null) {
            throw VsSyntaxError(
                start + end.span,
                "'${defaultExport}' is already this document's default — there is one 'export default' " +
                    "per document, because an importer writing 'import x from …' names one thing",
            )
        }
        defaultExport = DEFAULT_BUNDLE
        return DefaultBundleDecl(names, start + end.span)
    }

    /** `{ abs, max as biggest }` — the names taken out of a document, each optionally renamed. */
    private fun importItems(): List<ImportItem> {
        expect(TokenType.LBRACE, "'{' to open the list of names")
        val out = ArrayList<ImportItem>()
        while (!check(TokenType.RBRACE) && !atEnd()) {
            val name = expectIdent("a name to import").text
            val local = if (matchWord("as")) expectIdent("a name to call it here").text else name
            out += ImportItem(name, local)
            if (!match(TokenType.COMMA)) break
        }
        expect(TokenType.RBRACE, "'}' to close the list of names")
        return out
    }

    // ---- annotations ----------------------------------------------------------------------------------

    /**
     * `@id(7) @at(320, 140) @size(420, 260) @folded`, and anything else written the same way.
     *
     * **One path, no special cases.** Every annotation is parsed identically into a flat bag; which ones
     * mean something is a question for [AnnotationSpec] and, later, for `Lower`. The parser has no business
     * knowing that `at` means x and y — that is a binding decision, and binding happens where the `Node` is
     * built. A new intrinsic is therefore a row in the registry rather than a branch here.
     *
     * Unrecognised annotations are kept rather than refused: preserving something you do not interpret
     * cannot be got wrong, and refusing it would leave the mechanism unable to carry anything new. A typo
     * is warned about so it is visible without being fatal. See VSCRIPT_LANG_PLAN.md §8b.
     */
    private fun annotations(): Annotations {
        if (!check(TokenType.AT)) return Annotations.NONE
        val all = ArrayList<Annotation>()
        while (match(TokenType.AT)) {
            val nameTok = expectIdent("an annotation name after '@'")
            val args = annArgs()
            val spec = AnnotationSpec.of(nameTok.text)
            if (spec == null) {
                warnings += VsDiagnostic(
                    nameTok.span,
                    "'@${nameTok.text}' is not one of ${AnnotationSpec.names.joinToString(", ")} — it is " +
                        "kept as it was written, but nothing reads it",
                )
            } else if (args.size !in spec.arity) {
                throw VsSyntaxError(
                    nameTok.span,
                    "'@${nameTok.text}' takes ${spec.arity.first}" +
                        (if (spec.arity.first == spec.arity.last) "" else "..${spec.arity.last}") +
                        " value(s), found ${args.size}",
                )
            }
            all += Annotation(nameTok.text, args, nameTok.span)
        }
        return Annotations(all)
    }

    /** An annotation's arguments: literals only, since nothing runs to produce metadata. */
    private fun annArgs(): List<Any?> {
        if (!check(TokenType.LPAREN)) return emptyList()
        advance()
        val out = ArrayList<Any?>()
        while (!check(TokenType.RPAREN) && !atEnd()) {
            val t = peek()
            when (t.type) {
                TokenType.INT, TokenType.FLOAT, TokenType.STRING, TokenType.COLOR,
                TokenType.TRUE, TokenType.FALSE, TokenType.NULL,
                -> { advance(); out += t.value }
                TokenType.IDENT -> { advance(); out += t.text }
                else -> throw VsSyntaxError(t.span, "an annotation takes values written out, found '${t.text}'")
            }
            if (!match(TokenType.COMMA)) break
        }
        expect(TokenType.RPAREN, "')' to close the annotation")
        return out
    }

    // ---- declarations ---------------------------------------------------------------------------------

    /**
     * `single Values { x: INT, tag: STRING? = null }` — a record with exactly one of it.
     *
     * Shaped exactly like [typeDecl]'s body and deliberately so: a `single` IS a record, so anything that
     * can be a field of one can be a field of this, defaults included. No type parameters — there is one
     * instance, so there is nothing for a parameter to be bound to.
     */
    /**
     * @param anonymous the name to use when this declaration has none of its own — see [ANONYMOUS_DEFAULT].
     *   Non-null only for `export default single { … }`, where a name would be a name nobody says.
     */
    private fun singleDecl(ann: Annotations, isPrivate: Boolean = false, anonymous: String? = null): SingleDecl {
        val start = declFrom(expectWord("single").span)
        val name = if (anonymous != null && check(TokenType.LBRACE)) anonymous else expectIdent("a name").text
        expect(TokenType.LBRACE, "'{' to open the field list")
        val fields = ArrayList<Field>()
        while (!check(TokenType.RBRACE) && !atEnd()) {
            fields += field()
            if (!match(TokenType.COMMA)) break
        }
        val end = expect(TokenType.RBRACE, "'}' to close the field list").span
        return SingleDecl(name, fields, start + end, isPrivate, ann)
    }

    /** @param anonymous see [singleDecl] — `export default type { … }` names nothing either. */
    private fun typeDecl(isPrivate: Boolean = false, anonymous: String? = null): TypeDecl {
        val start = declFrom(expectWord("type").span)
        val name = if (anonymous != null && check(TokenType.LBRACE)) anonymous else expectIdent("a type name").text
        // `type Pair<A, B>` — bare names only, and that is not a simplification. A parameter is a name
        // being INTRODUCED; there is nothing for an argument of its own to mean, and no bounds to write
        // (a trait system is Tier 3, deliberately deferred).
        val params = ArrayList<String>()
        if (match(TokenType.LT)) {
            do {
                params += expectIdent("a type parameter name — 'type $name<A, B>'").text
            } while (match(TokenType.COMMA))
            expect(TokenType.GT, "'>' to close '$name<…>'")
        }
        expect(TokenType.LBRACE, "'{' to open the field list")
        val fields = ArrayList<Field>()
        while (!check(TokenType.RBRACE) && !atEnd()) {
            fields += field()
            if (!match(TokenType.COMMA)) break
        }
        val end = expect(TokenType.RBRACE, "'}' to close the field list").span
        return TypeDecl(name, fields, start + end, isPrivate, params)
    }

    /**
     * `enum Phase { Chop, Bank, Walk }`.
     *
     * A trailing comma is allowed, so a member list can be written one-per-line and reordered without
     * touching the neighbouring line — the same courtesy the field list already extends.
     *
     * A DUPLICATE member is refused here rather than in the validator. Everything downstream matches a
     * member by name, case-insensitively, so a second `Chop` is not a second value: it is the same value
     * declared twice, and every lookup would silently pick the first. There is nothing a later stage could
     * report that would be more useful than pointing at the second one.
     */
    private fun enumDecl(isPrivate: Boolean = false): EnumDecl {
        val start = declFrom(expectWord("enum").span)
        val name = expectIdent("an enum name").text

        // `enum Target(count: INT, anchor: TILE) { … }` — the columns every member carries. Absent is the
        // enum this language always had, which is what makes the whole feature cost existing files nothing.
        val fields = ArrayList<Field>()
        if (match(TokenType.LPAREN)) {
            while (!check(TokenType.RPAREN) && !atEnd()) {
                val f = field()
                fields.firstOrNull { it.name.equals(f.name, ignoreCase = true) }?.let {
                    throw VsSyntaxError(f.span, "'$name' already has a field called '${it.name}'")
                }
                fields += f
                if (!match(TokenType.COMMA)) break
            }
            expect(TokenType.RPAREN, "')' to close the fields of '$name'")
            if (fields.isEmpty()) {
                throw VsSyntaxError(start, "'$name()' declares no fields — leave the parentheses off")
            }
        }

        expect(TokenType.LBRACE, "'{' to open the member list")
        val members = ArrayList<EnumMember>()
        while (!check(TokenType.RBRACE) && !atEnd()) {
            val tok = expectIdent("a member name")
            members.firstOrNull { it.name.equals(tok.text, ignoreCase = true) }?.let {
                throw VsSyntaxError(tok.span, "'$name' already has a member called '${it.name}'")
            }
            // `WildKebbit(30, tile(2308, 3575, 0), "Wild kebbit")` — the member's row, positional against
            // the fields. Counted and type-checked by the validator, which can say which member and which
            // column; here the job is only to read them.
            val values = ArrayList<Expr>()
            if (check(TokenType.LPAREN)) {
                advance()
                while (!check(TokenType.RPAREN) && !atEnd()) {
                    values += expression()
                    if (!match(TokenType.COMMA)) break
                }
                expect(TokenType.RPAREN, "')' to close the values of '${tok.text}'")
            }
            members += EnumMember(tok.text, tok.span, values)
            if (!match(TokenType.COMMA)) break
        }
        val end = expect(TokenType.RBRACE, "'}' to close the member list").span
        // An empty enum is a type no value can ever have, so every use of it is unreachable. Refused at the
        // declaration, where the fix is obvious, rather than at each use where it would look like a
        // different problem.
        if (members.isEmpty()) {
            throw VsSyntaxError(start + end, "'$name' has no members — an enum needs at least one")
        }
        return EnumDecl(name, members, start + end, isPrivate, fields)
    }

    private fun field(): Field {
        val nameTok = expectIdent("a field name")
        expect(TokenType.COLON, "':' after '${nameTok.text}'")
        val type = typeExpr()
        val default = if (match(TokenType.ASSIGN)) expression() else null
        return Field(nameTok.text, type, nameTok.span + type.span, default)
    }

    private fun typeExpr(): TypeExpr {
        // `(fn(INT) -> BOOL)?` — an OPTIONAL FUNCTION. The parentheses are what make it sayable: a bare
        // trailing `?` after a result is the RESULT'S, and that reading came first, so there was no
        // spelling left for the other one. Kotlin's answer to the same ambiguity.
        //
        // Only meaningful with the `?`. A parenthesised type without one is simply the type, so this adds
        // a grouping rather than a shape anything downstream has to know about.
        if (check(TokenType.LPAREN)) {
            advance()
            val inner = typeExpr()
            val close = expect(TokenType.RPAREN, "')' to close a parenthesised type")
            if (!check(TokenType.QUESTION)) return inner
            val q = advance().span
            return TypeExpr(inner.name, inner.args, inner.span + close.span + q, inner.module, optional = true)
        }
        return typeExprFrom(expectIdent("a type"))
    }

    /**
     * The rest of a type, given its first identifier — so a caller that has already read one can go on.
     *
     * [fnDecl] is the caller, and it has to read the identifier before it can tell a receiver from a
     * function name: `fn closest(…)` and `fn List<Entity>.closest(…)` are the same two tokens until the
     * third. Splitting the production here rather than backtracking keeps one description of what a type
     * looks like, which is what stops the receiver's grammar drifting from every other type's.
     */
    private fun typeExprFrom(head: Token): TypeExpr {
        var nameTok = head
        var module: String? = null
        // `banking::Account` — an imported record named in a signature or a variable's type.
        if (check(TokenType.COLONCOLON)) {
            advance()
            module = nameTok.text
            nameTok = expectIdent("a type after '$module::'")
        }
        // `fn(TILE) -> BOOL` — the one type that is not `NAME<args>`. Recognised by the parenthesis rather
        // than by the word alone, so a record actually called `fn` (nothing stops one) still reads as a
        // plain name, and `fn` on its own stays the unconstrained function type.
        //
        // **One word, whatever the function does.** There used to be two — `act(…)` for a function with a
        // body of steps, `fn(…)` for one that only computes — and the kind was part of the TYPE so that a
        // pin wanting a pure function could refuse an acting one. That rule is gone: `fn` is every function
        // now, and the arrow is what is optional, exactly as `(A) -> Unit` and `(A) -> B` are one thing in
        // Kotlin. `act` is still READ, and only read, so a document written before this parses and comes
        // back spelled `fn`.
        if (module == null && (nameTok.text == "fn" || nameTok.text == "act") && check(TokenType.LPAREN)) {
            advance()
            val params = ArrayList<TypeExpr>()
            if (!check(TokenType.RPAREN)) {
                params += typeExpr()
                while (match(TokenType.COMMA)) params += typeExpr()
            }
            val close = expect(TokenType.RPAREN, "')' to close '${nameTok.text}(…)'")
            if (!check(TokenType.ARROW)) {
                // No arrow: it hands nothing back. A `?` here is unambiguous and it is the FUNCTION'S,
                // since there is no result it could have belonged to — `fn(T)?` is a handler that might be
                // absent. With a result the mark would be the result's, so that case needs parentheses:
                // `(fn(A) -> B)?`. See `typeExpr`.
                var end = close.span
                val optional = check(TokenType.QUESTION)
                if (optional) end = advance().span
                // The result is always LAST in `args`; "hands nothing back" is a result named NOTHING
                // rather than a shorter list, so nothing downstream needs a second shape to handle.
                return TypeExpr(
                    "fn",
                    params + TypeExpr(TypeExpr.NOTHING, span = close.span),
                    nameTok.span + end,
                    optional = optional,
                )
            }
            advance()
            // No `?` suffix on THIS form, and the reason is ambiguity rather than principle: after
            // `fn(A) -> B` a trailing `?` reads as the RESULT'S, and the recursive call below has already
            // taken it. `(fn(A) -> B)?` is how an optional one is written — see `typeExpr`.
            val result = typeExpr()
            return TypeExpr("fn", params + result, nameTok.span + result.span)
        }
        if (!match(TokenType.LT)) {
            val q = if (check(TokenType.QUESTION)) advance().span else null
            return TypeExpr(nameTok.text, emptyList(), nameTok.span + (q ?: nameTok.span), module, q != null)
        }
        // Several arguments since phase F, which is what `MAP<Tile, Job>` needed and nothing before it did.
        // A comma here is unambiguous: a type argument cannot itself contain a top-level comma, and a
        // nested `MAP<A, MAP<B, C>>` is separated by the recursion rather than by lookahead.
        val args = ArrayList<TypeExpr>()
        args += typeExpr()
        while (match(TokenType.COMMA)) args += typeExpr()
        var end = expect(TokenType.GT, "'>' to close '${nameTok.text}<…>'").span
        // Outermost, so `LIST<TILE>?` is an optional list and `LIST<TILE?>` is a list of optional tiles.
        // The inner `?` is consumed by the recursive call above, which is what keeps the two apart with
        // no lookahead and no ambiguity to resolve.
        val optional = check(TokenType.QUESTION)
        if (optional) end = advance().span
        return TypeExpr(nameTok.text, args, nameTok.span + end, module, optional)
    }

    private fun varDecl(isPrivate: Boolean = false): VarDecl {
        val start = declFrom(expectWord("var").span)
        val name = expectIdent("a variable name").text
        expect(TokenType.COLON, "':' and a type — a graph variable is always typed")
        val type = typeExpr()
        // Any expression. A LITERAL default is stored beside the declaration as document data, which is
        // what the canvas has always done; anything that has to run is lowered into an initialiser that
        // executes before `on start`. Refusing the second was a restriction of the document format
        // leaking into the language, and `var Target: ITEM = Emerald` is a perfectly ordinary thing to
        // want to write.
        val default = if (match(TokenType.ASSIGN)) expression() else null
        return VarDecl(name, type, default, start + (default?.span ?: type.span), isPrivate)
    }

    /**
     * `val Name = 12`, `val Home: TILE = nearestBank()` — a document-level name bound once.
     *
     * The type is optional HERE and may be required by [Lower]: a value that has to be worked out becomes a
     * graph variable, and a graph variable is always typed. The parser cannot tell the two apart — whether
     * `Base` folds is a question about the const table — so it accepts both shapes and lets the stage that
     * knows report it.
     *
     * Any expression, unlike [constDecl]'s [literalOnly]. That is the whole widening.
     */
    private fun valDecl(ann: Annotations, isPrivate: Boolean = false): ValDecl {
        val start = declFrom(expectWord("val").span)
        val name = expectIdent("a name").text
        val type = if (match(TokenType.COLON)) typeExpr() else null
        expect(TokenType.ASSIGN, "'=' — a val is a value")
        val value = expression()
        return ValDecl(name, type, value, start + value.span, isPrivate, ann)
    }

    private fun constDecl(ann: Annotations, isExported: Boolean = false): ConstDecl {
        val start = declFrom(expectWord("const").span)
        val name = expectIdent("a name").text
        expect(TokenType.ASSIGN, "'=' — a const is a value")
        val value = literalOnly()
        return ConstDecl(name, value, start + value.span, ann, isExported)
    }

    /** Defaults and consts are values, so anything that would have to *run* is refused here, with a reason. */
    private fun literalOnly(): Expr {
        val e = expression()
        if (e !is LiteralExpr && !(e is CallExpr && e.name == "tile") && e !is ListLitExpr) {
            throw VsSyntaxError(
                e.span,
                "this has to be a value written out, not something worked out while running — " +
                    "nothing runs to produce it",
            )
        }
        return e
    }

    /**
     * Whether the `<` at the cursor opens THIS function's type parameters rather than a receiver's.
     *
     * Told apart by the token after the matching `>`: a function's own name is followed by `(`, and a
     * receiver by `.`. Nested arguments are counted rather than assumed, and `>>` never arrives as one
     * token here — the lexer has no shift operator — so the depth count is exact.
     */
    private fun ownTypeParamsAhead(): Boolean {
        if (!check(TokenType.LT)) return false
        var depth = 0
        var n = 0
        while (n < MAX_TYPE_LOOKAHEAD) {
            when (peek(n).type) {
                TokenType.LT -> depth++
                TokenType.GT -> {
                    depth--
                    if (depth == 0) return peek(n + 1).type == TokenType.LPAREN
                }
                TokenType.EOF -> return false
                else -> Unit
            }
            n++
        }
        return false
    }

    private fun fnDecl(
        ann: Annotations,
        isPrivate: Boolean = false,
        isInline: Boolean = false,
        isOperator: Boolean = false,
        isInfix: Boolean = false,
    ): FnDecl {
        val start = declFrom(expectWord("fn").span)
        var first = expectIdent("a function name")
        // `fn List.add(…)`, `fn List<Entity>.closest(…)` — an EXTENSION, and what was read first was the
        // head of the receiver TYPE. Recognised by the punctuation rather than by looking the name up,
        // because the parser resolves nothing: `List` is a type here for the same reason `banking` is an
        // alias in `banking::x` — the separator says so.
        //
        // A `<` or a `::` in this position can only be part of a type, since a function's own name is
        // followed by `(` and nothing else. So the receiver is read as a full [typeExpr] with no lookahead
        // and no ambiguity, which is all phase E's Level 0 needs from the grammar.
        var receiver: TypeExpr? = null
        // `fn head<T>(…)` broke the assumption above — a function's own name may now be followed by `<`
        // too — so a `<` here is settled by what closes it: `>` then `(` introduces THIS function's type
        // parameters, `>` then `.` is a receiver's arguments. One token of real lookahead past the
        // brackets, rather than resolving anything.
        if ((check(TokenType.DOT) || check(TokenType.COLONCOLON) || check(TokenType.LT)) && !ownTypeParamsAhead()) {
            val t = typeExprFrom(first)
            // A receiver is the value the call is written ON, so there is nothing for a `?` to mean here:
            // `xs?.first()` is a question about the CALL, and it is spelled at the call site.
            //
            // Two spellings reach this, because two-character operators are told apart by ADJACENCY: with a
            // space `LIST<T> ?.f` the `?` is [typeExprFrom]'s and comes back as [TypeExpr.optional], and
            // without one `LIST<T>?.f` the lexer has already made a single `?.`. Both are the same mistake.
            if (t.optional || check(TokenType.SAFE_DOT)) {
                throw VsSyntaxError(
                    t.span,
                    "a receiver is what the function is called on, so it is never optional — drop the '?' " +
                        "here and write the '?.' at the call site instead",
                )
            }
            receiver = t
            expect(TokenType.DOT, "'.' and a name after '$t'")
            first = expectIdent("a name after '$t.'")
        }
        val name = first.text
        // `fn first<T>(…)` — the same spelling `type Pair<A, B>` already uses, in the same position
        // relative to the name, because they are the same thing: a place where a variable is introduced.
        val typeParams = ArrayList<String>()
        if (match(TokenType.LT)) {
            do {
                typeParams += expectIdent("a type parameter name — 'fn $name<T>(…)'").text
            } while (match(TokenType.COMMA))
            expect(TokenType.GT, "'>' to close '$name<…>'")
        }
        expect(TokenType.LPAREN, "'(' after '$name'")
        val params = ArrayList<Field>()
        // `fn Vec2.lengthSq(self)` — the receiver, written out.
        //
        // **Its ABSENCE is what makes a function type-level**, so this is not decoration: `fn Vec2.new(…)`
        // with no `self` is a function on the TYPE, called `Vec2.new(5, 3)`, and one with `self` is a
        // function on a VALUE of it, called `v.lengthSq()`. The distinction is therefore derived from the
        // parameter list rather than stored — which is exactly the objection that killed `impl` blocks,
        // answered.
        //
        // Bare, never `self: T`. The receiver type is already in the head where it cannot disagree, so a
        // second spelling could only ever be redundant or wrong.
        if (receiver != null && check(TokenType.IDENT) && peek().text == SELF) {
            val tok = advance()
            if (check(TokenType.COLON)) {
                throw VsSyntaxError(
                    tok.span + peek().span,
                    "'self' takes no type — it is already '$receiver', from the name of the function",
                )
            }
            params += Field(SELF, receiver, tok.span)
            match(TokenType.COMMA)
        }
        while (!check(TokenType.RPAREN) && !atEnd()) {
            // Caught BEFORE `field()` reads it, or a bare `self` here dies on the missing colon and reports
            // "expected ':'" — which names the punctuation rather than the actual mistake, that the
            // receiver is only the receiver when it comes first.
            if (check(TokenType.IDENT) && peek().text == SELF) {
                throw VsSyntaxError(
                    peek().span,
                    if (receiver == null) {
                        "'self' means the receiver of an extension, and '$name' extends nothing — " +
                            "write 'fn <Type>.$name(self, …)' if that is what you meant"
                    } else {
                        "'self' has to come first, and without a type: 'fn $receiver.$name(self, …)'"
                    },
                )
            }
            params += field()
            if (!match(TokenType.COMMA)) break
        }
        expect(TokenType.RPAREN, "')' to close the parameters of '$name'")

        val results = ArrayList<Field>()
        if (match(TokenType.ARROW)) {
            // `-> (` is two things: the named-results list `(low: INT, high: INT)`, and a parenthesised
            // single result `(fn(INT) -> INT)?`. Told apart by one token — a named result is `IDENT :`,
            // and nothing else in this position is — rather than by making one of them unwritable.
            val namedResults = check(TokenType.LPAREN) &&
                peek(1).type == TokenType.IDENT && peek(2).type == TokenType.COLON
            if (namedResults && match(TokenType.LPAREN)) {
                while (!check(TokenType.RPAREN) && !atEnd()) {
                    results += field()
                    if (!match(TokenType.COMMA)) break
                }
                expect(TokenType.RPAREN, "')' to close the results of '$name'")
            } else {
                // `-> Int`: one unnamed result. It still needs a pin name in the graph, and RESULT_PIN is
                // it — the printer drops the name again when it sees exactly one result called that, so
                // the short spelling round-trips rather than growing a name it was never given.
                val t = typeExpr()
                results += Field(RESULT_PIN, t, t.span)
            }
        }

        // `= e` IS `{ return e }`, desugared here and nowhere else — so no stage below this one has two
        // body forms to tell apart, and no rule can key off which was typed. See [FnDecl]: the short
        // spelling used to make the function an expression, which made a one-line wrapper illegal as soon
        // as the thing it wrapped had to run.
        //
        // `braced = false` is spelling, exactly as it is for `if x foo()`: it is what stops the printer
        // giving a body back with braces the author never wrote. Purity is decided in lowering, from what
        // the body needs.
        if (match(TokenType.ASSIGN)) {
            val e = expression()
            // **No result declared means RUN it, not return it** — `fn f() = println(…)` in Kotlin, and
            // here `fn HunterRumor.run(self) = self.runnable(self)`. Invoking a `fn(T)` produces no value,
            // so there was nothing to return and this was refused outright, in the PARSER, before anything
            // knew whether the expression yielded anything at all. Leaving the arrow off already means
            // "hands nothing back"; the short body form now means the same thing.
            //
            // Still refused when the expression is not a CALL, because then there is genuinely a value
            // going nowhere and `-> Type` is the fix. A call that produces one is caught in lowering,
            // where whether it does is actually known.
            if (results.isEmpty()) {
                if (e !is CallExpr) {
                    throw VsSyntaxError(e.span, "'$name' computes a value but says no result type — add '-> Type'")
                }
                val ran = Block(listOf(ExprStmt(e, e.span)), e.span, braced = false)
                return FnDecl(name, receiver, params, results, ran, start + e.span, ann, isPrivate, typeParams,
                    isInline = isInline, isOperator = isOperator, isInfix = isInfix)
            }
            val body = Block(listOf(ReturnStmt(listOf(e), e.span)), e.span, braced = false)
            return FnDecl(name, receiver, params, results, body, start + e.span, ann, isPrivate,
                typeParams, isInline = isInline, isOperator = isOperator, isInfix = isInfix)
        }
        val body = block()
        return FnDecl(name, receiver, params, results, body, start + body.span, ann, isPrivate,
            typeParams, isInline = isInline, isOperator = isOperator, isInfix = isInfix)
    }

    /**
     * `fake panel.amount(id: String, default: Int) -> Int = default` — stand in for a host, in a test.
     *
     * **The half of in-language testing that was designed and not built.** The runner binds the language's
     * own builtins and nothing else, so a document that reaches a game verb fails loudly with a stack —
     * which is right, and is what makes a passing test mean something. It also meant a script could only
     * be tested at all if it touched no host, and that is a narrower slice than it sounds: `pick`'s factors
     * are pure arithmetic and two of them still read a dashboard knob through `panel.amount`. Nothing about
     * those needs a CLIENT; they need a way to say "there is no dashboard".
     *
     * Parsed as an ordinary function with a dotted name, because that is what it is — parameters, a result
     * and a body, typechecked and compiled like any other. The name is the host it replaces, and a dotted
     * name is deliberately unspellable as a call: a fake is reached by standing in, never by being called.
     *
     * Parameters are matched to the host's pins BY POSITION, since that is how the VM passes them. Their
     * names are documentation, and writing them is still worth it — `(id: String, default: Int)` says which
     * pin is which where the reader is looking.
     */
    private fun fakeDecl(): FnDecl {
        val start = declFrom(expectWord("fake").span)
        val head = expectIdent("the host to fake — 'fake panel.amount(…)'")
        val sb = StringBuilder(head.text)
        // A host name is dotted — `panel.amount`, `movement.walkTo` — and every segment is an ordinary
        // identifier, so this is a name and not a path being resolved.
        while (match(TokenType.DOT)) sb.append('.').append(expectIdent("a name after '$sb.'").text)
        val name = sb.toString()

        expect(TokenType.LPAREN, "'(' after '$name'")
        val params = ArrayList<Field>()
        while (!check(TokenType.RPAREN) && !atEnd()) {
            params += field()
            if (!match(TokenType.COMMA)) break
        }
        expect(TokenType.RPAREN, "')' to close the parameters of '$name'")

        val results = ArrayList<Field>()
        if (match(TokenType.ARROW)) {
            val t = typeExpr()
            results += Field(RESULT_PIN, t, t.span)
        }

        // `= e` is `{ return e }`, the same desugaring `fn` does and for its reason — nothing below here
        // has two body forms to tell apart. A fake with no result runs its body and hands nothing back,
        // which is what faking an action wants: `fake movement.walkTo(tile: Tile) { }`.
        if (match(TokenType.ASSIGN)) {
            val e = expression()
            if (results.isEmpty()) {
                throw VsSyntaxError(e.span, "'$name' computes a value but says no result type — add '-> Type'")
            }
            val body = Block(listOf(ReturnStmt(listOf(e), e.span)), e.span, braced = false)
            return FnDecl(name, null, params, results, body, start + e.span, Annotations.NONE, false,
                emptyList(), fakes = name)
        }
        val body = block()
        return FnDecl(name, null, params, results, body, start + body.span, Annotations.NONE, false,
            emptyList(), fakes = name)
    }

    /**
     * `test "it does the thing" { … }` — one check.
     *
     * A declaration rather than a seventh `on` word, because a test HAS a name and the `on` forms have
     * nowhere to put one: there is exactly one `on start` in a document and there are as many tests as
     * somebody cares to write. Underneath it is still an [EntryDecl], so the resolver typechecks it with no
     * new case and the compiler emits it with no new shape.
     */
    private fun testDecl(ann: Annotations): EntryDecl {
        val start = declFrom(expectWord("test").span)
        val label = expect(TokenType.STRING, "a name in quotes after 'test'").value as String
        val body = block()
        return EntryDecl(EntryKind.TEST, body, start + body.span, ann, isAlways = false, label = label)
    }

    private fun entryDecl(ann: Annotations, isAlways: Boolean = false): EntryDecl {
        val start = declFrom(expectWord("on").span)
        val kindTok = expectIdent("$EVENT_WORDS after 'on'")
        val kind = when (kindTok.text) {
            "start" -> EntryKind.START
            "stop" -> EntryKind.STOP
            "render" -> EntryKind.RENDER
            "tick" -> EntryKind.TICK
            "wake" -> EntryKind.WAKE
            "sleep" -> EntryKind.SLEEP
            else -> throw VsSyntaxError(
                kindTok.span,
                "'on ${kindTok.text}' is not an event — expected $EVENT_WORDS",
            )
        }
        val body = block()
        return EntryDecl(kind, body, start + body.span, ann, isAlways)
    }

    // ---- statements -----------------------------------------------------------------------------------

    private fun block(): Block {
        val start = expect(TokenType.LBRACE, "'{'").span
        val stmts = ArrayList<Stmt>()
        while (!check(TokenType.RBRACE) && !atEnd()) {
            try {
                stmts += statement()
            } catch (e: VsSyntaxError) {
                errors += e
                if (!syncInBlock()) break
            }
        }
        val end = expect(TokenType.RBRACE, "'}' to close this block").span
        return Block(stmts, start + end)
    }

    /** Recover inside a block: stop at the block's own `}` so the caller can close it cleanly. */
    private fun syncInBlock(): Boolean {
        var depth = 0
        while (!atEnd()) {
            when {
                check(TokenType.LBRACE) -> depth++
                check(TokenType.RBRACE) -> { if (depth == 0) return true; depth-- }
                depth == 0 && startsStatement() -> return true
            }
            advance()
        }
        return false
    }

    private fun startsStatement(): Boolean =
        checkWord("val") || checkWord("let") || checkWord("var") || checkWord("const") ||
            checkWord("if") || checkWord("while") ||
            checkWord("for") || checkWord("return") || checkWord("break") || checkWord("continue") ||
            checkWord("sequence") || checkWord("when")

    /**
     * `assert <condition>` or `assert <condition>, "why"`.
     *
     * Splits a comparison into its sides so the failure can report both. Deliberately only at the TOP
     * level of the condition: `assert a == b && c == d` reports the whole thing and no sides, because
     * picking one half of an `&&` to blame would be picking wrong half the time.
     */
    private fun assertStmt(ann: Annotations): Stmt {
        val start = advance().span
        val condition = expression()
        val message = if (check(TokenType.COMMA)) { advance(); expression() } else null
        val span = start + (message ?: condition).span
        val cmp = condition as? BinaryExpr
        val sided = cmp != null && cmp.op in COMPARISON_OPS
        return AssertStmt(
            condition = condition,
            text = source?.let { it.substring(condition.span.start, condition.span.end) }.orEmpty().trim(),
            message = message,
            left = if (sided) cmp?.left else null,
            right = if (sided) cmp?.right else null,
            span = span,
            ann = ann,
        )
    }

    private fun statement(): Stmt {
        val ann = annotations()
        val start = peek().span
        return when {
            checkWord("val") || checkWord("let") -> letStmt(ann)
            checkWord("var") -> localVarStmt(ann)
            checkWord("const") -> { val d = constDecl(ann); ConstStmt(d.name, d.value, d.span, ann) }
            checkWord("if") -> ifStmt(ann)
            checkWord("while") -> whileStmt(ann)
            checkWord("for") -> forStmt(ann)
            checkWord("return") -> returnStmt(ann)
            checkWord("break") -> BreakStmt(advance().span, ann)
            checkWord("continue") -> ContinueStmt(advance().span, ann)
            checkWord("sequence") -> sequenceStmt(ann)
            checkWord("try") -> tryStmt(ann)
            checkWord("when") -> whenStmt(ann)
            checkWord("assert") -> assertStmt(ann)
            // `pace::IdleMs = 50` — a write to another document's variable. Recognised by lookahead like
            // the unqualified form, because `::` in statement position can only be an import alias.
            check(TokenType.IDENT) && peek(1).type == TokenType.COLONCOLON &&
                peek(2).type == TokenType.IDENT &&
                (peek(3).type == TokenType.ASSIGN || peek(3).type in COMPOUND) -> {
                val alias = advance().text
                advance() // '::'
                val nameTok = advance()
                val assign = advance().type
                val value = expression()
                val span = start + value.span
                if (assign == TokenType.ASSIGN) {
                    AssignStmt(nameTok.text, value, span, ann, module = alias)
                } else {
                    val op = COMPOUND.getValue(assign)
                    val read = NameExpr(nameTok.text, nameTok.span, module = alias)
                    AssignStmt(nameTok.text, BinaryExpr(op, read, value, span), span, ann, op, alias)
                }
            }
            // `n = expr` — a write to a graph variable or a mutable local. `s.f = v` is the other
            // assignment, handled below because its target has to be parsed before it can be recognised;
            // a list position has none, and is replaced with `set(…)`.
            check(TokenType.IDENT) && peek(1).type == TokenType.ASSIGN -> {
                val name = advance().text
                advance() // '='
                val value = expression()
                AssignStmt(name, value, start + value.span, ann)
            }
            // `n += 1`, desugared here to `n = n + 1`. Doing it in the parser rather than the lowering is
            // what keeps the rest of the compiler from ever hearing about the form — see [COMPOUND].
            check(TokenType.IDENT) && peek(1).type in COMPOUND -> {
                val nameTok = advance()
                val op = COMPOUND.getValue(advance().type)
                val value = expression()
                val span = start + value.span
                AssignStmt(nameTok.text, BinaryExpr(op, NameExpr(nameTok.text, nameTok.span), value, span), span, ann, op)
            }
            else -> {
                val e = expression()
                // `s.f = v` — a field rebind. Recognised AFTER parsing rather than by lookahead, because
                // the target may be any postfix chain (`a.b.c`, `xs[i].f`) and deciding up front would
                // mean a second, partial expression parser to look past it.
                if (e is MemberExpr && check(TokenType.ASSIGN)) {
                    advance()
                    val value = expression()
                    return FieldAssignStmt(e.target, e.member, value, start + value.span, ann)
                }
                // `s.f += 1`. The target is re-READ as well as written, which is sound here for the same
                // reason the whole form is: a record is a value and a field read has no effect, so naming
                // it twice cannot mean anything different from naming it once.
                if (e is MemberExpr && peek().type in COMPOUND) {
                    val op = COMPOUND.getValue(advance().type)
                    val value = expression()
                    val span = start + value.span
                    return FieldAssignStmt(
                        e.target, e.member, BinaryExpr(op, e, value, span), span, ann, op,
                    )
                }
                // `xs[i] = v`, and its compound forms. Caught here rather than left to fall into the
                // statement-shape error below, which is about statements and says nothing about lists —
                // so the author read "a statement has to be a call" and had no idea what to write next.
                //
                // There is no index assignment because a list is a VALUE: nothing can be written through
                // one, the same rule that makes `c.laps = …` through a parameter a reported mistake. The
                // cure is to rebuild the list and keep it, so the message spells that out with the name
                // already in hand.
                if (e is IndexExpr && check(TokenType.ASSIGN)) {
                    advance()
                    val v = expression()
                    return IndexAssignStmt(e.target, e.index, v, start + v.span, ann)
                }
                // The COMPOUND forms are still refused, and deliberately: `xs[i] += 1` has to read the
                // position and write it back, which is two operator calls rather than one, and a type
                // offering `set` need not offer `get`. Naming that is better than quietly expanding it.
                if (e is IndexExpr && peek().type in COMPOUND) {
                    val n = (e.target as? NameExpr)?.name ?: "xs"
                    throw VsSyntaxError(
                        e.span,
                        "'$n[…] ${peek().text}' is not written yet — read it, change it, and write it " +
                            "back: '$n[i] = $n[i] ${peek().text.removeSuffix("=")} …'",
                    )
                }
                // **Only in a lambda may a bare value stand alone**, and only as its LAST statement, where
                // it is the result. Checked after the block is parsed rather than here, because whether a
                // statement is the last one is not known while it is being read — see [lambda].
                if (e !is CallExpr && !inLambdaBody) {
                    throw VsSyntaxError(e.span, "this does nothing on its own — a statement has to be a call")
                }
                ExprStmt(e, start + e.span, ann)
            }
        }
    }

    /**
     * `val name = value` — a binding that never changes. `let` is the older spelling of the same thing.
     *
     * Both are accepted and only `val` is printed, which is the migration: a file converges the first time
     * a tool writes it back. Two permanent spellings for one construct is the thing the round-trip contract
     * exists to prevent, so `let` is a transitional courtesy rather than an alias to keep.
     */
    private fun letStmt(ann: Annotations): LetStmt {
        val start = (if (checkWord("val")) expectWord("val") else expectWord("let")).span
        val binding = binding()
        // Only a plain name may say its type: there is one type to write and destructuring produces
        // several names, so `let (a, b): INT = f()` would have to mean something and does not.
        val declared = if (binding is NameBinding && match(TokenType.COLON)) typeExpr() else null
        expect(TokenType.ASSIGN, "'=' after the name")
        val value = expression()
        return LetStmt(binding, value, start + value.span, ann, declaredType = declared)
    }

    /**
     * `var name = value` inside a body — a local that may be assigned again.
     *
     * **A plain name only.** `let` may destructure — `let (low, high) = bounds(…)`, `let {x, y} = p` — and
     * `var` deliberately may not: reassigning one name out of a destructuring is a construct nobody asked
     * for, and refusing it here costs a line where allowing it would cost a rule.
     *
     * The type is inferred from the initialiser, as `let`'s is, and may be written out when the
     * initialiser would say the wrong thing: `var total: FLOAT = 0` starts a float accumulator at zero
     * without having to spell it `0.0`.
     */
    private fun localVarStmt(ann: Annotations): LetStmt {
        val start = expectWord("var").span
        val nameTok = expectIdent("a name after 'var'")
        // `var total: FLOAT = 0` — otherwise the type comes from the initialiser, and `0` is an INT.
        val declared = if (match(TokenType.COLON)) typeExpr() else null
        expect(TokenType.ASSIGN, "'=' after the name — a local has to start with a value")
        val value = expression()
        return LetStmt(
            NameBinding(nameTok.text, nameTok.span),
            value,
            start + value.span,
            ann,
            mutable = true,
            declaredType = declared,
        )
    }

    /** Parens bind OUTPUT PINS, braces bind RECORD FIELDS — distinct syntax so neither needs a type to read. */
    private fun binding(): Binding {
        val start = peek().span
        if (match(TokenType.LPAREN)) {
            val entries = tupleEntries()
            return TupleBinding(entries, start + tokens[i - 1].span)
        }
        if (match(TokenType.LBRACE)) {
            val names = nameList(TokenType.RBRACE, "'}'")
            return RecordBinding(names, start + tokens[i - 1].span)
        }
        val t = expectIdent("a name to bind")
        return NameBinding(t.text, t.span)
    }

    /**
     * `(a, b)` — the outputs in order; `(name, ref: Entity, tile)` — the outputs by name.
     *
     * Mixing was refused at first, on the grounds that "`Id` at position 0" beside a named entry would let a
     * later insertion move what the bare one binds. That reasoning applies to a list that is HALF positional,
     * which is not what this is: one rename makes the whole list by-name, so a bare entry there is shorthand
     * for `x: x` and nothing moves when another is added. Rust reads `Point { x, y }` the same way.
     */
    private fun tupleEntries(): List<TupleEntry> {
        val entries = ArrayList<TupleEntry>()
        while (!check(TokenType.RPAREN) && !atEnd()) {
            val first = expectIdent("a name")
            val entry = if (match(TokenType.COLON)) {
                // `local: Pin` — the name being introduced first, then where it comes from.
                //
                // The other order was tried and reverted. `Pin: local` matches how a name introduces a value
                // everywhere else here (`log(message: x)`), and that is a real argument — but this is not
                // that: nothing is being SUPPLIED, a name is being taken out. Read aloud, `clickedName: Name`
                // is "clickedName, from Name", which is the direction a binding actually goes. The worry that
                // it would collide with the type annotation in `let x: INT = …` does not survive contact
                // either: there is no per-entry type form inside the parens for it to be confused with.
                TupleEntry(expectIdent("the output to bind '${first.text}' to").text, first.text, first.span)
            } else {
                TupleEntry(null, first.text, first.span)
            }
            entries += entry
            if (!match(TokenType.COMMA)) break
        }
        expect(TokenType.RPAREN, "')' to close the names")
        if (entries.isEmpty()) throw VsSyntaxError(peek().span, "nothing is being bound here")
        return entries
    }

    private fun nameList(closer: TokenType, what: String): List<String> {
        val names = ArrayList<String>()
        while (!check(closer) && !atEnd()) {
            names += expectIdent("a name").text
            if (!match(TokenType.COMMA)) break
        }
        expect(closer, "$what to close the names")
        if (names.isEmpty()) throw VsSyntaxError(peek().span, "nothing is being bound here")
        return names
    }

    /**
     * `if` — or `if val`, which is a different statement wearing the same first word.
     *
     * Told apart by one token of lookahead, and it cannot be ambiguous: `let` is a statement keyword, so
     * there is no expression that may begin with it and nothing an `if val …` could otherwise have meant.
     */
    private fun ifStmt(ann: Annotations): Stmt {
        if (peek(1).isWord("val") || peek(1).isWord("let")) return ifLetStmt(ann)
        return plainIfStmt(ann)
    }

    /**
     * `if val t = …` — take the value when it is there.
     *
     * One word of lookahead tells it from an ordinary `if`, and it cannot be ambiguous: `val` is a
     * statement keyword, so no expression can begin with one. `let` is the older spelling and is still
     * read; only `val` is printed.
     */
    private fun ifLetStmt(ann: Annotations): IfLetStmt {
        val start = expectWord("if").span
        if (checkWord("val")) expectWord("val") else expectWord("let")
        val nameTok = expectIdent("a name to bind")
        expect(TokenType.ASSIGN, "'=' — 'if val ${nameTok.text} = …' binds the value when it is there")
        val value = headerExpression()
        val then = body()
        var elseBranch: Stmt? = null
        if (matchWord("else")) {
            elseBranch = if (checkWord("if")) ifStmt(Annotations.NONE) else ExprBlockStmt(body())
        }
        return IfLetStmt(
            nameTok.text, value, then, elseBranch, start + (elseBranch?.span ?: then.span), ann,
        )
    }

    private fun plainIfStmt(ann: Annotations): IfStmt {
        val start = expectWord("if").span
        val cond = headerExpression()
        // Braces optional, here and on the `else`. Parens around the condition are optional too and always
        // were — `(x)` is just a grouped expression, so `if (x) foo()` needs nothing special. They are
        // grouping and carry no meaning, so the printer gives back whatever precedence requires rather than
        // what was typed; that is the same normalisation `a + (b)` already gets.
        val then = body()
        var elseBranch: Stmt? = null
        if (matchWord("else")) {
            // `else if` chains to a nested Branch on the False arm — the graph shape, and what the printer
            // looks for to rebuild the chain rather than printing a bare nested `if`.
            elseBranch = if (checkWord("if")) ifStmt(Annotations.NONE)
            else body().let { ExprBlockStmt(it) }
        }
        return IfStmt(cond, then, elseBranch, start + (elseBranch?.span ?: then.span), ann)
    }

    /**
     * `try { … } catch e { … }`.
     *
     * The name after `catch` takes no parentheses and no type — the language writes `if val x = …` and
     * `for i in …` the same way, and there is only one thing it could be: the message, as text. A `catch`
     * clause is mandatory, because a `try` with nothing to do about the failure is a `try` that changes
     * nothing.
     */
    private fun tryStmt(ann: Annotations): TryStmt {
        val start = expectWord("try").span
        val body = body()
        expectWord("catch")
        val name = expectIdent("a name for the message, as in `catch e { … }`").text
        val caught = body()
        return TryStmt(body, name, caught, start + caught.span, ann)
    }

    private fun whileStmt(ann: Annotations): WhileStmt {
        val start = expectWord("while").span
        if (checkWord("val") || checkWord("let")) return whileValStmt(start, ann)
        val cond = headerExpression()
        val body = body()
        return WhileStmt(cond, body, start + body.span, ann)
    }

    /**
     * `while val n = next() { … }` — take values until there are none left.
     *
     * **Desugared here, into the shape people were writing by hand**: a `while true` whose body is one
     * `if val` with a `break` for the empty case. That is the whole feature — no new node, no new lowering,
     * nothing new in the compiler or the VM — and it is admissible for the reason every piece of sugar here
     * has to be: the printer can recognise that shape and give the word back. See `Print.whileVal`.
     *
     * The option is evaluated inside the body, so it is re-asked every pass, which is what "until there are
     * none left" means. A `while true` needs no wait to be safe here, because the `break` is what ends it —
     * the runaway guard only fires on a loop that never yields AND never stops.
     */
    private fun whileValStmt(start: Span, ann: Annotations): WhileStmt {
        if (checkWord("val")) expectWord("val") else expectWord("let")
        val nameTok = expectIdent("a name to bind")
        expect(TokenType.ASSIGN, "'=' — 'while val ${nameTok.text} = …' takes values until there are none")
        val value = headerExpression()
        val body = body()
        val span = start + body.span
        val stop = Block(listOf(BreakStmt(span)), span)
        val take = IfLetStmt(nameTok.text, value, body, ExprBlockStmt(stop), span, ann)
        return WhileStmt(
            LiteralExpr(true, LiteralKind.BOOL, span),
            Block(listOf(take), span),
            span,
            ann,
        )
    }

    private fun forStmt(ann: Annotations): ForStmt {
        val start = expectWord("for").span
        var element: String
        var index: String? = null
        if (match(TokenType.LPAREN)) {
            element = expectIdent("a name for the element").text
            expect(TokenType.COMMA, "',' — 'for (element, index) in …'")
            index = expectIdent("a name for the index").text
            expect(TokenType.RPAREN, "')'")
        } else {
            element = expectIdent("a name for the element").text
        }
        expectWord("in")
        val list = headerExpression()
        val body = body()
        return ForStmt(element, index, list, body, start + body.span, ann)
    }

    /**
     * `return`, `return e`, `return a, b`.
     *
     * A bare `return` is recognised by the `}` that follows it, which is the one place newline-insensitivity
     * costs something: `return` followed by another statement reads as `return <that statement>`. The case is
     * unreachable code — anything after a return in the same block never runs — so the rule is "a bare return
     * ends its block", which is where every one of them actually appears.
     */
    private fun returnStmt(ann: Annotations): ReturnStmt {
        val start = expectWord("return").span
        val values = ArrayList<Expr>()
        if (!check(TokenType.RBRACE)) {
            do { values += expression() } while (match(TokenType.COMMA))
        }
        return ReturnStmt(values, start + (values.lastOrNull()?.span ?: start), ann)
    }

    private fun sequenceStmt(ann: Annotations): SequenceStmt {
        val start = expectWord("sequence").span
        val arms = ArrayList<Block>()
        while (check(TokenType.LBRACE)) arms += block()
        if (arms.size < 2) {
            throw VsSyntaxError(start, "a sequence needs at least two blocks — one is just the statements")
        }
        if (arms.size > SEQUENCE_MAX) {
            throw VsSyntaxError(start, "a sequence has at most $SEQUENCE_MAX blocks, found ${arms.size}")
        }
        return SequenceStmt(arms, start + arms.last().span, ann)
    }

    /**
     * `when`, in both of Kotlin's forms.
     *
     * ```
     * when State {          when {
     *     Phase.Chop -> {}      count > 10 -> {}
     *     else -> {}            ready      -> {}
     * }                     }
     * ```
     *
     * **The subject is optional and its absence is spelled by `{` arriving immediately**, which is why it is
     * read with [headerExpression]: `when State {` would otherwise parse `State { … }` as a record literal
     * and then find no arms — the same ambiguity `if ready { }` has, solved the same way.
     *
     * An arm's body is a block or a single statement. A single statement because the overwhelmingly common
     * arm is one call, and `Phase.Chop -> chop()` is the line an author wants to write; a block because
     * anything longer needs one.
     *
     * `else` LAST and at most once. Not a lexical fussiness: arms are tested in order, so an `else` in the
     * middle would make every arm after it unreachable, and silently.
     */
    private fun whenStmt(ann: Annotations): WhenStmt {
        val start = expectWord("when").span
        val subject = if (check(TokenType.LBRACE)) null else headerExpression()
        expect(TokenType.LBRACE, "'{' to open the arms of 'when'")

        val arms = ArrayList<WhenArm>()
        var elseArm: Block? = null
        var end = start
        while (!check(TokenType.RBRACE) && !atEnd()) {
            if (elseArm != null) {
                throw VsSyntaxError(peek().span, "'else' is the last arm of a 'when' — nothing after it can run")
            }
            if (matchWord("else")) {
                expect(TokenType.ARROW, "'->' after 'else'")
                elseArm = body()
                end = elseArm.span
                continue
            }
            val value = expression()
            expect(TokenType.ARROW, "'->' after the case")
            val body = body()
            arms += WhenArm(value, body, value.span + body.span)
            end = body.span
        }
        val close = expect(TokenType.RBRACE, "'}' to close 'when'").span
        if (arms.isEmpty() && elseArm == null) {
            throw VsSyntaxError(start + close, "a 'when' needs at least one arm")
        }
        if (arms.size > WHEN_MAX) {
            throw VsSyntaxError(start, "a 'when' has at most $WHEN_MAX arms, found ${arms.size}")
        }
        return WhenStmt(subject, arms, elseArm, start + close, ann)
    }

    /**
     * A body: a block, or ONE statement wrapped as one so the tree has a single shape.
     *
     * The wrapping is what keeps `braced` from spreading — every construct with a body keeps taking a
     * [Block], and the only thing that knows the difference is the printer. `braced = false` is the spelling
     * to give back; it changes nothing about what runs.
     *
     * Used by `when` arms, `if`/`else`, `while` and `for`, which is the whole set of things with a body.
     */
    private fun body(): Block =
        if (check(TokenType.LBRACE)) block()
        else statement().let { Block(listOf(it), it.span, braced = false) }

    /** A header's expression — `if`, `while`, `for … in` — where `Ident {` opens a block, not a record. */
    private fun headerExpression(): Expr {
        noStructLit++
        try { return expression() } finally { noStructLit-- }
    }

    // ---- expressions ----------------------------------------------------------------------------------

    fun expression(): Expr = ternary()

    private fun ternary(): Expr {
        val cond = orElse()
        if (!match(TokenType.QUESTION)) return cond
        // The arms are full expressions and cannot be confused with a block, so struct literals are back on.
        val saved = noStructLit
        noStructLit = 0
        try {
            val ifTrue = expression()
            expect(TokenType.COLON, "':' — a choice is 'condition ? this : that'")
            val ifFalse = expression()
            return TernaryExpr(cond, ifTrue, ifFalse, cond.span + ifFalse.span)
        } finally {
            noStructLit = saved
        }
    }

    private fun orElse(): Expr {
        var left = andThen()
        while (match(TokenType.OR_OR)) {
            val right = andThen()
            left = BinaryExpr(BinaryOp.OR_ELSE, left, right, left.span + right.span)
        }
        return left
    }

    private fun andThen(): Expr {
        var left = comparison()
        while (match(TokenType.AND_AND)) {
            val right = comparison()
            left = BinaryExpr(BinaryOp.AND_THEN, left, right, left.span + right.span)
        }
        return left
    }

    /** Non-associative on purpose: `a < b < c` is a mistake, not `(a < b) < c`. */
    private fun comparison(): Expr {
        val left = elvis()
        // `x is Int` / `x !is Point`. At comparison precedence because it produces a BOOL, and before the
        // operator table because its right side is a TYPE rather than an expression.
        if (checkWord("is") || (check(TokenType.BANG) && peek(1).isWord("is"))) {
            val negated = match(TokenType.BANG)
            val start = expectWord("is").span
            val t = typeExpr()
            if (t.args.isNotEmpty()) {
                throw VsSyntaxError(
                    t.span,
                    "'is' asks about one type — its type arguments are not part of it. A generic record " +
                        "is ERASED: '${t.name}' is a question the running program can answer and " +
                        "'$t' is not",
                )
            }
            // `x is Tile?` asks two questions at once and answers neither. `is` reports the runtime KIND,
            // and absence is not a kind — the test for that is `x == null`, or `if val`.
            if (t.optional) {
                throw VsSyntaxError(t.span, "'is' asks what something IS — write 'x == null' to ask whether it is there")
            }
            return IsExpr(left, t.name, negated, left.span + start + t.span, t.module)
        }
        val op = when (peek().type) {
            TokenType.EQ -> BinaryOp.EQ
            TokenType.NE -> BinaryOp.NE
            TokenType.LT -> BinaryOp.LT
            TokenType.LE -> BinaryOp.LE
            TokenType.GT -> BinaryOp.GT
            TokenType.GE -> BinaryOp.GE
            else -> return left
        }
        advance()
        val right = elvis()
        if (peek().type in COMPARISONS) {
            throw VsSyntaxError(peek().span, "'${peek().text}' cannot follow another comparison — use '&&'")
        }
        return BinaryExpr(op, left, right, left.span + right.span)
    }

    /**
     * `a ?: b` — tighter than a comparison, looser than arithmetic, and RIGHT-associative.
     *
     * The same three choices Kotlin makes, so `a ?: b + 1` is `a ?: (b + 1)`, `a ?: b == c` is
     * `(a ?: b) == c`, and `a ?: b ?: c` walks left to right taking the first thing that is there.
     */
    private fun elvis(): Expr {
        val left = infixCall()
        if (!match(TokenType.ELVIS)) return left
        val right = elvis()
        return ElvisExpr(left, right, left.span + right.span)
    }

    /**
     * `a to b` — an extension called by name between its two arguments.
     *
     * **Desugared here, so nothing downstream learns a new shape.** `a to b` becomes exactly the
     * `CallExpr` that `a.to(b)` produces — same receiver, same single argument — which means the resolver
     * finds it through the ordinary extension path and the compiler emits an ordinary call. The only new
     * rule anywhere is the resolver's check that the callee was declared `infix`, which is what stops
     * every two-word sequence in the language becoming a call.
     *
     * Between elvis and addition, which is where Kotlin puts it: looser than arithmetic, so `1 + 1 to x`
     * pairs `2` with `x`; tighter than `?:`, so `m[k] ?: 0 to v` is not read as a pair.
     *
     * **The same-line rule is what makes this safe, and it is not a nicety.** vs has no statement
     * terminator, so statements are told apart by grammar alone — and without this,
     *
     *     val a = 1
     *     log(message: "x")
     *
     * parses as ONE infix call, `1 log (…)`, and the file stops meaning what it says. Kotlin has the same
     * hazard and the same answer. Tokens carry their line, so the test is cheap and exact: the operator
     * and the start of its right operand must both sit on the line the left operand ended on.
     */
    private fun infixCall(): Expr {
        var left = addition()
        while (true) {
            val name = peek()
            if (name.type != TokenType.IDENT) return left
            // The line the LEFT operand ended on, taken from the last token consumed rather than from
            // `left.span.line` — a span carries where it STARTS, and a multi-line left operand would
            // otherwise compare against the wrong line and refuse a legitimate pair.
            if (i == 0 || name.span.line != tokens[i - 1].span.line) return left
            if (name.text !in INFIX_WORDS) return left
            val after = peek(1)
            if (!startsExpression(after) || after.span.line != name.span.line) return left
            advance()
            val right = addition()
            left = CallExpr(
                listOf(name.text),
                listOf(Arg(null, right, right.span)),
                left.span + right.span,
                receiver = left,
            )
        }
    }

    /**
     * The words that may be written between two expressions — **a closed set, and it has to be.**
     *
     * The general rule other languages use is "any identifier declared `infix`", and it cannot work here:
     * vs allows a bare single-statement body on the same line, so
     *
     *     if ready log(message: "yes")
     *
     * is `ready` followed by a statement, and `ready log` is indistinguishable from an infix call. Forty
     * four tests said so the first time this was written the general way. Kotlin escapes the same hazard
     * only because its `if` condition is parenthesised; vs's is not.
     *
     * So an infix WORD is syntax the language owns, and `infix fn` declares what implements it — exactly
     * the bargain `op fn` makes for `[`. That also answers the question the closed set raises: growing
     * this is a language change, deliberately, because a word any document could claim is a word every
     * document has to read carefully.
     */
    private val INFIX_WORDS = setOf("to")

    /** Could [t] begin an expression? Enough to tell `a to b` from `a` followed by the next statement. */
    private fun startsExpression(t: Token): Boolean = when (t.type) {
        TokenType.IDENT, TokenType.INT, TokenType.FLOAT, TokenType.STRING,
        TokenType.LPAREN, TokenType.LBRACKET, TokenType.MINUS, TokenType.BANG -> true
        else -> false
    }

    private fun addition(): Expr {
        var left = multiplication()
        while (true) {
            val op = when (peek().type) {
                TokenType.PLUS -> BinaryOp.ADD
                TokenType.MINUS -> BinaryOp.SUB
                else -> return left
            }
            advance()
            val right = multiplication()
            left = BinaryExpr(op, left, right, left.span + right.span)
        }
    }

    private fun multiplication(): Expr {
        var left = unary()
        while (true) {
            val op = when (peek().type) {
                TokenType.STAR -> BinaryOp.MUL
                TokenType.SLASH -> BinaryOp.DIV
                TokenType.PERCENT -> BinaryOp.MOD
                else -> return left
            }
            advance()
            val right = unary()
            left = BinaryExpr(op, left, right, left.span + right.span)
        }
    }

    /**
     * `@at(300, 140) hp()` — metadata on a value node.
     *
     * Read here, at the tightest-binding level, so an annotation attaches to the thing right after it and
     * never silently swallows an operator: `@at(…) a + b` annotates `a`, and annotating the sum is written
     * `@at(…) (a + b)`. The printer parenthesises for exactly this reason.
     */
    private fun unary(): Expr {
        if (check(TokenType.BANG)) {
            val start = advance().span
            val operand = unary()
            return NotExpr(operand, start + operand.span)
        }
        var e = postfix()
        // `as` binds tighter than every binary operator — `a + b as T` is `a + (b as T)` — and looser
        // than a postfix, so `(t as Vec2i).x` needs its parens. Chaining is allowed and harmless.
        while (checkWord("as")) {
            advance()
            val t = typeExpr()
            // Type arguments ARE allowed, because `as` is two operations wearing one word and only one of
            // them has no use for them. Reading a record as another record copies named fields across, and
            // there the arguments genuinely take no part. Reading a DOCUMENT as a type is a decode against
            // a schema, and `LIST<Item>` or `MAP<STRING, Tally>` is exactly as decodable as a record — the
            // schema builder has always handled both, but only as FIELD types, because this refused them
            // at the top. So a map or a list could be persisted only by wrapping it in a record declared
            // for the purpose. Which fork this is depends on what feeds the Value pin, and the parser
            // cannot know that; the validator refuses arguments on the record-to-record side, where it
            // can say so precisely.
            // A cast reads named fields out of a record and builds another. There is no optional record
            // to build, and `x as Tile?` reads as a checked cast, which this is not.
            if (t.optional) {
                throw VsSyntaxError(t.span, "'as' builds one record from another — it cannot name an optional")
            }
            // The rename clause is a brace, so it is suppressed wherever a brace means a block — the
            // same rule that stops `if ready { }` reading as a record. See [noStructLit].
            val renames = ArrayList<FieldInit>()
            var end = t.span
            if (noStructLit == 0 && check(TokenType.LBRACE)) {
                advance()
                while (!check(TokenType.RBRACE) && !atEnd()) {
                    val nameTok = expectIdent("a field of '${t.name}'")
                    expect(TokenType.COLON, "':' after '${nameTok.text}'")
                    // Two spellings, and they are two different things rather than one written twice. A
                    // BARE name is a field of the source record. A QUOTED one is a JSON key, for the cast
                    // whose source is a document — where there is no source record, keys are text, and
                    // plenty of them (`item_count`, `Total Items`) are nothing a field could be called.
                    // Kept apart in the AST as NameExpr vs LiteralExpr, so `Lower` can record which was
                    // written and the printer can give the same one back — the round-trip contract, and
                    // the reason this is not simply "accept both and print bare".
                    val from = if (check(TokenType.STRING)) {
                        val s = advance()
                        FieldInit(nameTok.text, LiteralExpr(s.value, LiteralKind.STRING, s.span), nameTok.span + s.span)
                    } else {
                        val fromTok = expectIdent("the field of the source it comes from, or a JSON key in quotes")
                        FieldInit(nameTok.text, NameExpr(fromTok.text, fromTok.span), nameTok.span + fromTok.span)
                    }
                    renames += from
                    if (!match(TokenType.COMMA)) break
                }
                end = expect(TokenType.RBRACE, "'}' to close the renames").span
            }
            e = AsExpr(e, t.name, renames, e.span + end, t.module, t.args)
        }
        return e
    }

    private fun postfix(): Expr = postfixFrom(primary())

    /** `.field`, `[i]`, `(args)` — applied to an expression already parsed. */
    private fun postfixFrom(start: Expr): Expr {
        var e = start
        while (true) {
            e = when {
                check(TokenType.DOT) -> {
                    advance()
                    val name = expectIdent("a field or output name after '.'")
                    // `21.double()`, `f().g()` — an extension on something that is not a bare name, so
                    // `identExpression` never saw the dot. Parens are what tell it from a field read.
                    when {
                        check(TokenType.LPAREN) -> {
                            val (args, end) = arguments()
                            trailing(CallExpr(listOf(name.text), args, e.span + end, receiver = e))
                        }
                        // `xs.filter { it > 3 }` — the parens are dropped when the lambda is the only
                        // argument, as they are in Kotlin. Free to claim: a brace can never follow a
                        // member read, so nothing else could have been meant here.
                        check(TokenType.LBRACE) && noStructLit == 0 -> {
                            val lam = lambda()
                            CallExpr(listOf(name.text), emptyList(), e.span + lam.span, receiver = e, trailing = lam)
                        }
                        else -> MemberExpr(e, name.text, e.span + name.span)
                    }
                }
                // `a?.b` and `a?.f(x)`. The access is built against a placeholder standing for the
                // receiver, so the shapes below need no safe variants of their own — and the guard covers
                // exactly ONE step, which is why `a?.b.c` reads `.c` off a value that may be nothing and
                // the validator says so. Chain the `?.` to guard each step: `a?.b?.c`.
                check(TokenType.SAFE_DOT) -> {
                    advance()
                    val name = expectIdent("a field or output name after '?.'")
                    val it = SafeItExpr(name.span)
                    val access = if (check(TokenType.LPAREN)) {
                        val (args, end) = arguments()
                        CallExpr(listOf(name.text), args, name.span + end, receiver = it)
                    } else {
                        MemberExpr(it, name.text, name.span)
                    }
                    SafeAccessExpr(e, access, e.span + access.span)
                }
                check(TokenType.LBRACKET) -> {
                    advance()
                    val saved = noStructLit
                    noStructLit = 0
                    val index = try { expression() } finally { noStructLit = saved }
                    val end = expect(TokenType.RBRACKET, "']'").span
                    IndexExpr(e, index, e.span + end)
                }
                checkWord("with") -> {
                    advance()
                    val (fields, end) = fieldInits()
                    WithExpr(e, fields, e.span + end)
                }
                else -> return e
            }
        }
    }

    private fun primary(): Expr {
        val t = peek()
        when (t.type) {
            TokenType.INT -> { advance(); return LiteralExpr(t.value, LiteralKind.INT, t.span) }
            TokenType.FLOAT -> { advance(); return LiteralExpr(t.value, LiteralKind.FLOAT, t.span) }
            TokenType.STRING -> { advance(); return LiteralExpr(t.value, LiteralKind.STRING, t.span) }
            TokenType.COLOR -> { advance(); return LiteralExpr(t.value, LiteralKind.COLOR, t.span) }
            TokenType.TRUE, TokenType.FALSE -> { advance(); return LiteralExpr(t.value, LiteralKind.BOOL, t.span) }
            TokenType.NULL -> { advance(); return LiteralExpr(null, LiteralKind.NULL, t.span) }
            TokenType.LPAREN -> {
                advance()
                val saved = noStructLit
                noStructLit = 0
                val e = try { expression() } finally { noStructLit = saved }
                expect(TokenType.RPAREN, "')'")
                return e
            }
            TokenType.LBRACKET -> return listLiteral()
            // A lambda where a value was expected. Unambiguous here — a brace can never START an
            // expression otherwise — and parsed rather than rejected so the refusal comes from `Lower`
            // with the reason, instead of from here as "expected a value, found '{'".
            TokenType.LBRACE -> return lambda()
            // A leading `::` — there is no alias in front of it to qualify anything.
            TokenType.COLONCOLON ->
                throw VsSyntaxError(t.span, "'::' must follow an import alias — 'banking::withdraw(…)'")
            TokenType.IDENT -> return identExpression()
            else -> throw VsSyntaxError(t.span, "expected a value, found '${t.text.ifEmpty { "end of file" }}'")
        }
    }

    /** A name: a call, a record literal, or a bare reference — decided by what follows it. */
    private fun identExpression(): Expr {
        val start = peek().span
        val parts = ArrayList<String>()
        parts += expectIdent("a name").text
        // `banking::withdraw` — the alias is taken FIRST, before the dotted walk, because the two
        // separators mean different things: `::` crosses a document boundary and `.` names the parts of a
        // node type within one. Reading them in the other order would make `a::b.c()` ambiguous.
        var module: String? = null
        if (check(TokenType.COLONCOLON)) {
            advance()
            module = parts.removeAt(0)
            parts += expectIdent("a name after '$module::'").text
        }
        while (check(TokenType.DOT) && peek(1).type == TokenType.IDENT && peek(2).type == TokenType.LPAREN) {
            advance()
            parts += advance().text
        }
        if (check(TokenType.LPAREN)) {
            val (args, end) = arguments()
            return trailing(CallExpr(parts, args, start + end, module))
        }
        if (parts.size > 1) {
            // `a.b` with no parens is a member access, and `postfix` handles it — so a dotted head only
            // ever gets here when it was a call, and this cannot be reached. Guarded anyway.
            throw VsSyntaxError(start, "'${parts.joinToString(".")}' is not a call")
        }
        if (check(TokenType.LBRACE) && noStructLit == 0) {
            val (fields, end) = fieldInits()
            return StructLitExpr(parts[0], fields, start + end, module)
        }
        return NameExpr(parts[0], start, module)
    }

    /**
     * A lambda written after a call's closing paren — `each(xs: [1]) { it * 2 }`.
     *
     * Suppressed wherever a brace means a block, by the same counter that stops `if ready { }` reading as
     * a record literal. So a trailing lambda in an `if`/`while`/`for` header needs its own parentheses,
     * which is the rule record literals already live under rather than a new one to learn.
     */
    private fun trailing(call: CallExpr): CallExpr {
        if (!check(TokenType.LBRACE) || noStructLit != 0) return call
        val lam = lambda()
        return CallExpr(call.target, call.args, call.span + lam.span, call.module, call.receiver, lam)
    }

    /** `{ it * 2 }`, `{ x -> x * 2 }`, `{ a, b -> a + b }`. */
    private fun lambda(): LambdaExpr {
        val start = expect(TokenType.LBRACE, "'{' to open the lambda").span
        val saved = noStructLit
        noStructLit = 0
        try {
            val (params, arrow) = lambdaParams()
            // **A BLOCK, whose last expression is what it hands back.** It used to be exactly one
            // expression, and the reason was the graph's — a function value is called from inside an
            // expression, where there is no exec chain a statement could sequence on. The text front end
            // compiles a lambda into a chunk with a real body, so that bought nothing and cost `val`
            // inside a `filter`: anything that needed to name an intermediate had to be lifted out into a
            // named function.
            //
            // `{ }` is still the do-nothing body — no statements, no value — which is the only spelling a
            // `fn(T)` has for one. Refused later unless the destination hands nothing back; see
            // `Lower.lambdaRef`, the only place that knows what it was written into.
            val stmts = ArrayList<Stmt>()
            val wasInLambda = inLambdaBody
            inLambdaBody = true
            try {
                while (!check(TokenType.RBRACE) && !atEnd()) {
                    try {
                        stmts += statement()
                    } catch (e: VsSyntaxError) {
                        errors += e
                        if (!syncInBlock()) break
                    }
                }
            } finally {
                inLambdaBody = wasInLambda
            }
            val end = expect(TokenType.RBRACE, "'}' to close the lambda").span
            // The value is the last statement, and only when that statement IS an expression. A body
            // ending in a `for` or an `if` runs and hands nothing back, which is a real thing to write and
            // not an error — the destination decides whether a result was needed.
            val last = stmts.lastOrNull() as? ExprStmt
            val body = last?.expr
            val before = if (last != null) stmts.dropLast(1) else stmts
            // Now that "last" is known: a bare value anywhere BUT the end is the ordinary mistake, and
            // gets the ordinary complaint. `{ n + 1  log(x) }` computed something and threw it away.
            for (st in before) {
                if (st is ExprStmt && st.expr !is CallExpr) {
                    errors += VsSyntaxError(
                        st.expr.span,
                        "this does nothing on its own — a statement has to be a call, and only the " +
                            "lambda's LAST expression is its result",
                    )
                }
            }
            return LambdaExpr(params, body, start + end, arrow, before)
        } finally {
            noStructLit = saved
        }
    }

    /**
     * The names before `->`, or none at all — in which case the parameter is `it`.
     *
     * Decided by LOOKAHEAD rather than by trying and backtracking: `{ a }` and `{ a -> a }` differ by one
     * token part-way in, and a parser that guesses wrong here has already consumed the body.
     */
    private fun lambdaParams(): Pair<List<String>, Boolean> {
        var i = 0
        while (peek(i).type == TokenType.IDENT) {
            i++
            if (peek(i).type != TokenType.COMMA) break
            i++
        }
        // `{ -> … }` — no names, but an arrow: an explicit ZERO-parameter lambda. It is the only spelling
        // for a pin typed `fn() -> …`, because a bare `{ … }` is the implicit `it` and therefore always
        // takes one argument. Without this a function that takes nothing had no inline form at all.
        if (i == 0 && check(TokenType.ARROW)) {
            advance()
            return emptyList<String>() to true
        }
        if (i == 0 || peek(i).type != TokenType.ARROW) return emptyList<String>() to false
        val names = ArrayList<String>()
        while (!check(TokenType.ARROW)) {
            names += expectIdent("a lambda parameter").text
            if (!match(TokenType.COMMA)) break
        }
        expect(TokenType.ARROW, "'->' after the lambda's parameters")
        return names to true
    }

    private fun arguments(): Pair<List<Arg>, Span> {
        expect(TokenType.LPAREN, "'('")
        val saved = noStructLit
        noStructLit = 0
        val args = ArrayList<Arg>()
        try {
            while (!check(TokenType.RPAREN) && !atEnd()) {
                val argStart = peek().span
                // `name: value` is a named argument; a bare `name` is a value that happens to be a name.
                val label = if (check(TokenType.IDENT) && peek(1).type == TokenType.COLON) {
                    advance().text.also { advance() }
                } else null
                // A brace opening an ARGUMENT is a lambda, never a record literal — a record literal is
                // `Name { … }` and would have consumed the name first. So `done: { … }` reads as the
                // function it obviously is, and a lambda is no longer confined to the trailing slot.
                val value = if (check(TokenType.LBRACE)) lambda() else expression()
                args += Arg(label, value, argStart + value.span)
                if (!match(TokenType.COMMA)) break
            }
        } finally {
            noStructLit = saved
        }
        val end = expect(TokenType.RPAREN, "')' to close the arguments").span
        return args to end
    }

    private fun fieldInits(): Pair<List<FieldInit>, Span> {
        expect(TokenType.LBRACE, "'{'")
        val saved = noStructLit
        noStructLit = 0
        val fields = ArrayList<FieldInit>()
        try {
            while (!check(TokenType.RBRACE) && !atEnd()) {
                val nameTok = expectIdent("a field name")
                // `Vec2 { x, y }` — shorthand for `x: x, y: y`, and the mirror of the one the language
                // already has on the other side: `let { x, y } = here` is documented as Rust's field
                // shorthand, where a bare entry means `x: x` too. Taking a field FROM a record and putting
                // one INTO a record should not need different spellings for the same idea.
                val value = if (match(TokenType.COLON)) {
                    expression()
                } else {
                    NameExpr(nameTok.text, nameTok.span)
                }
                fields += FieldInit(nameTok.text, value, nameTok.span + value.span)
                if (!match(TokenType.COMMA)) break
            }
        } finally {
            noStructLit = saved
        }
        val end = expect(TokenType.RBRACE, "'}'").span
        return fields to end
    }

    private fun listLiteral(): Expr {
        val start = expect(TokenType.LBRACKET, "'['").span
        val saved = noStructLit
        noStructLit = 0
        val items = ArrayList<Expr>()
        try {
            while (!check(TokenType.RBRACKET) && !atEnd()) {
                items += expression()
                if (!match(TokenType.COMMA)) break
            }
        } finally {
            noStructLit = saved
        }
        val end = expect(TokenType.RBRACKET, "']' to close the list").span
        return ListLitExpr(items, start + end)
    }

    companion object {
        /** The pin name given to a single unnamed result (`-> Int`). The printer drops it again. */
        const val RESULT_PIN = "Result"

        /**
         * The six words that may follow `on`, spelled once.
         *
         * Written once because it appears in two messages a token apart — the hint when the word is
         * missing entirely and the complaint when it is not one of these — and a sixth event added to one
         * of them tells half the authors who hit it the wrong thing.
         */
        const val EVENT_WORDS = "'start', 'stop', 'tick', 'render', 'wake' or 'sleep'"

        /**
         * The name the synthesised `export default { … }` record and its one instance both take.
         *
         * `@`-prefixed for the same reason a lambda's is: it marks a name the language invented, so
         * nothing mistakes it for one an author could have typed or could collide with. See
         * [DefaultBundleDecl].
         */
        const val DEFAULT_BUNDLE = "@default"

        /** `flow.sequence` exposes four `Then` pins, so four is the ceiling here too. */
        const val SEQUENCE_MAX = 4

        /** `flow.when`'s arm count is a shape pin, so the ceiling is only what a document can represent. */
        const val WHEN_MAX = dev.ziggle.vscript.model.BuiltinNodes.WHEN_MAX

        /**
         * What an extension's receiver parameter is called, in its signature and inside its body.
         *
         * Taken from the model rather than spelled again, so the word the parser accepts and the word the
         * rest of the compiler looks for cannot drift apart. Fully qualified for the same reason `WHEN_MAX`
         * is: this file imports nothing, which is what lets it be tested without a catalogue.
         */
        const val SELF = dev.ziggle.vscript.model.GraphFunction.SELF

        /** The operators an `assert` reports both sides of — see [dev.ziggle.vscript.lang.AssertStmt]. */
        val COMPARISON_OPS = setOf(
            BinaryOp.EQ, BinaryOp.NE, BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE,
        )

        private val COMPARISONS = setOf(
            TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE,
        )

        /**
         * `+=` and its four siblings, and the operator each stands for.
         *
         * **Desugared in the parser, marked for the printer.** `n += 1` produces exactly the AST `n = n + 1`
         * produces, so the lowering, the validator, the compiler and the canvas never learn the form exists
         * — which is what makes it free everywhere below here. What it costs is one literal on the Set node
         * saying which operator was written, because the two spellings are otherwise the same graph and the
         * printer would have to pick one. That is the same bargain `var` and `s.f = v` already make; see
         * `BuiltinNodes.ASSIGN_OP`.
         *
         * All five arithmetic operators rather than only `+=`: the cost is per-map-entry, and a language
         * where `+=` works and `*=` does not is a language with a rule nobody can guess.
         */
        private val COMPOUND: Map<TokenType, BinaryOp> = mapOf(
            TokenType.PLUS_ASSIGN to BinaryOp.ADD,
            TokenType.MINUS_ASSIGN to BinaryOp.SUB,
            TokenType.STAR_ASSIGN to BinaryOp.MUL,
            TokenType.SLASH_ASSIGN to BinaryOp.DIV,
            TokenType.PERCENT_ASSIGN to BinaryOp.MOD,
        )
    }
}
