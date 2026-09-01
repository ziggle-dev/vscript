package dev.ziggle.vscript.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    private fun parse(src: String): Parser.Result = Parser(Lexer(src).lex()).parse()

    /** Parse something that should be clean, failing the test with the diagnostics if it is not. */
    private fun ok(src: String): Program {
        val r = parse(src)
        assertTrue(r.ok, "unexpected errors: ${r.errors.map { it.message }}")
        return r.program
    }

    private fun expr(src: String): Expr = Parser(Lexer(src).lex()).expression()

    /** The single statement of a single `on start` body — most tests only care about one construct. */
    private fun stmt(src: String): Stmt {
        val prog = ok("on start { $src }")
        val entry = prog.decls.single() as EntryDecl
        return entry.body.stmts.single()
    }

    // ---- program shape --------------------------------------------------------------------------------

    @Test
    fun `an empty program parses`() {
        val p = ok("")
        assertNull(p.name)
        assertTrue(p.decls.isEmpty())
    }

    @Test
    fun `a graph header names the document`() {
        assertEquals("Iron miner", ok("""graph "Iron miner" """).name)
    }

    @Test
    fun `the three entry kinds`() {
        val p = ok("on start { } on stop { } on render { }")
        assertEquals(
            listOf(EntryKind.START, EntryKind.STOP, EntryKind.RENDER),
            p.decls.map { (it as EntryDecl).kind },
        )
    }

    @Test
    fun `start may appear more than once — each is its own fiber`() {
        assertEquals(2, ok("on start { } on start { }").decls.size)
    }

    // ---- declarations ---------------------------------------------------------------------------------

    @Test
    fun `a type declares fields`() {
        val t = ok("type Spot { tile: Tile, name: String }").decls.single() as TypeDecl
        assertEquals("Spot", t.name)
        assertEquals(listOf("tile", "name"), t.fields.map { it.name })
        assertEquals(listOf("Tile", "String"), t.fields.map { it.type.name })
    }

    @Test
    fun `a list type carries its element type`() {
        val t = ok("type Bag { items: List<Item> }").decls.single() as TypeDecl
        val ty = t.fields.single().type
        assertEquals("List", ty.name)
        assertEquals("Item", ty.of?.name)
        // The persisted spelling, so this round-trips through TypeRef.parse.
        assertEquals("List<Item>", ty.toString())
    }

    @Test
    fun `a variable is always typed and may have a default`() {
        val v = ok("var trips: Int = 0").decls.single() as VarDecl
        assertEquals("trips", v.name)
        assertEquals("Int", v.type.name)
        assertEquals(0, (v.default as LiteralExpr).value)
        assertNull((ok("var n: Int").decls.single() as VarDecl).default)
    }

    @Test
    fun `a default that has to run is accepted, and lowered into a prologue`() {
        // It used to be refused, with "nothing runs to produce it" — true of the DOCUMENT FORMAT, where a
        // default is a stored value, and not a rule the language needed. `Lower` turns one that has to
        // run into an initialiser at the head of `on start`; the parser's job is only to read it.
        val v = ok("var n: Int = playerTile()").decls.single() as VarDecl
        assertIs<CallExpr>(v.default)
    }

    @Test
    fun `a const still has to be written out`() {
        // A `const` IS a literal node in the graph — there is no node for it to be otherwise — so this
        // one keeps the restriction that `var` has lost. Still read, still refused the same way.
        val r = parse("const n = playerTile()")
        assertTrue(r.errors.any { "nothing runs to produce it" in it.message }, "${r.errors.map { it.message }}")
    }

    /**
     * A `val` is the widening: any expression, at either scope.
     *
     * The PARSER takes it either way — whether `playerTile()` can fold is a question about the const and
     * enum tables, which is `Lower`'s knowledge, so the refusal for an untyped computed one lives there.
     */
    @Test
    fun `a val takes any expression`() {
        val d = ok("val n: Tile = playerTile()").decls.single() as ValDecl
        assertEquals("n", d.name)
        assertEquals("Tile", d.type?.name)
        assertIs<CallExpr>(d.value)
    }

    @Test
    fun `a val may leave its type off`() {
        val d = ok("val n = 12").decls.single() as ValDecl
        assertNull(d.type)
        assertEquals(12, (d.value as LiteralExpr).value)
    }

    @Test
    fun `a val may be private`() {
        assertTrue((ok("export val n = 12").decls.single() as ValDecl).isExported)
    }

    @Test
    fun `a tile default is a value, not a call`() {
        val v = ok("var home: Tile = tile(3200, 3200, 0)").decls.single() as VarDecl
        assertIs<CallExpr>(v.default)
    }

    // ---- functions ------------------------------------------------------------------------------------

    @Test
    fun `a block body is read as it was written`() {
        val f = ok("fn go(n: Int) -> (ok: Bool) { return true }").decls.single() as FnDecl
        assertIs<ReturnStmt>(f.body.stmts.single())
        assertTrue(f.body.braced)
        assertEquals(listOf("n"), f.params.map { it.name })
        assertEquals(listOf("ok"), f.results.map { it.name })
    }

    @Test
    fun `an expression body is the same function as a block that returns it`() {
        // `= e` IS `{ return e }`, desugared here so nothing below the parser has two forms to tell apart
        // — and so a one-line wrapper is legal whatever it calls (GAPS 19). `braced` is the only trace
        // left, and it is spelling: it is what the printer needs to give back what was typed.
        val f = ok("fn double(x: Int) -> Int = x * 2").decls.single() as FnDecl
        val ret = assertIs<ReturnStmt>(f.body.stmts.single())
        assertIs<BinaryExpr>(ret.values.single())
        assertTrue(!f.body.braced)
    }

    @Test
    fun `one unnamed result gets the default pin name`() {
        val f = ok("fn double(x: Int) -> Int = x * 2").decls.single() as FnDecl
        assertEquals(listOf(Parser.RESULT_PIN), f.results.map { it.name })
    }

    @Test
    fun `an expression body with no result type is refused`() {
        val r = parse("fn f(x: Int) = x * 2")
        assertTrue(r.errors.any { "says no result type" in it.message }, "${r.errors.map { it.message }}")
    }

    // ---- comments -------------------------------------------------------------------------------------

    @Test
    fun `comments never reach the tree`() {
        // A comment belongs to the source document and to nothing else: not lowered into the graph, not
        // printed back out of one, not in the bytecode. The parser is where it stops existing.
        val p = ok(
            """
            // above
            fn f() {
                /* inside */
                openBank() // trailing
            }
            """.trimIndent()
        )
        val f = p.decls.single() as FnDecl
        assertEquals("f", f.name)
        assertEquals(1, f.body!!.stmts.size)
        assertTrue(f.body!!.stmts.single().ann.isEmpty)
    }

    @Test
    fun `a comment does not break the construct it sits inside`() {
        // The filter runs before parsing, so a comment between any two tokens is invisible rather than
        // being a case each production has to handle.
        val s = stmt("openBank( /* here */ )")
        assertIs<ExprStmt>(s)
    }

    @Test
    fun `there is no comment box syntax`() {
        // Comment boxes are a CANVAS construct. They group nodes visually, they are not printed and not
        // parsed, and the two document formats do not trade their comments.
        val r = parse("""comment "Bank trip" { }""")
        assertTrue(!r.ok, "expected 'comment' to be rejected as a declaration")
    }

    @Test
    fun `a note is graph metadata, unlike a comment`() {
        // The one way to put a remark INTO the graph from text — and deliberately not spelled like a
        // comment, because it is the opposite thing: it survives the boundary.
        assertEquals("careful here", stmt("""@note("careful here") openBank()""").ann.first("note")?.args?.single())
        assertTrue(stmt("// dropped\nopenBank()").ann.isEmpty)
    }

    // ---- statements -----------------------------------------------------------------------------------

    @Test
    fun `let binds one name`() {
        val s = stmt("val ore = nearestObjectNamed(\"Iron rocks\")")
        assertIs<LetStmt>(s)
        assertEquals("ore", (s.binding as NameBinding).name)
    }

    @Test
    fun `parens bind output pins and braces bind record fields`() {
        // Distinct syntax so neither needs a type to be read — the one place `(` and `{` mean different
        // things, and deliberately so.
        assertIs<TupleBinding>((stmt("val (rock, ok) = pick()") as LetStmt).binding)
        assertIs<RecordBinding>((stmt("val {tile, name} = s") as LetStmt).binding)
    }

    @Test
    fun `assignment writes a graph variable`() {
        val s = stmt("trips = trips + 1")
        assertIs<AssignStmt>(s)
        assertEquals("trips", s.name)
    }

    @Test
    fun `if else and else-if chains`() {
        val s = stmt("if a { } else if b { } else { }") as IfStmt
        val second = s.elseBranch
        assertIs<IfStmt>(second)          // chained, which is the graph shape
        assertIs<ExprBlockStmt>(second.elseBranch)
    }

    @Test
    fun `a bare if has no else`() {
        assertNull((stmt("if a { }") as IfStmt).elseBranch)
    }

    @Test
    fun `for binds the element, and optionally the index`() {
        assertNull((stmt("for x in xs { }") as ForStmt).index)
        val both = stmt("for (x, i) in xs { }") as ForStmt
        assertEquals("x", both.element)
        assertEquals("i", both.index)
    }

    @Test
    fun `return takes nothing, one value, or several`() {
        assertEquals(0, (stmt("return") as ReturnStmt).values.size)
        assertEquals(1, (stmt("return 1") as ReturnStmt).values.size)
        assertEquals(2, (stmt("return found, true") as ReturnStmt).values.size)
    }

    @Test
    fun `break and continue are statements`() {
        assertIs<BreakStmt>(stmt("break"))
        assertIs<ContinueStmt>(stmt("continue"))
    }

    @Test
    fun `a sequence needs at least two arms and at most four`() {
        assertEquals(2, (stmt("sequence { } { }") as SequenceStmt).arms.size)
        assertTrue(parse("on start { sequence { } }").errors.any { "at least two" in it.message })
        assertTrue(parse("on start { sequence { } { } { } { } { } }").errors.any { "at most" in it.message })
    }

    @Test
    fun `a statement that does nothing is refused`() {
        val r = parse("on start { 1 + 1 }")
        assertTrue(r.errors.any { "does nothing on its own" in it.message }, "${r.errors.map { it.message }}")
    }

    // ---- expressions ----------------------------------------------------------------------------------

    @Test
    fun `arithmetic binds tighter than comparison`() {
        val e = expr("a + b * c < d") as BinaryExpr
        assertEquals(BinaryOp.LT, e.op)
        val left = e.left as BinaryExpr
        assertEquals(BinaryOp.ADD, left.op)
        assertEquals(BinaryOp.MUL, (left.right as BinaryExpr).op)
    }

    @Test
    fun `and binds tighter than or`() {
        val e = expr("a || b && c") as BinaryExpr
        assertEquals(BinaryOp.OR_ELSE, e.op)
        assertEquals(BinaryOp.AND_THEN, (e.right as BinaryExpr).op)
    }

    @Test
    fun `comparisons do not chain`() {
        // `a < b < c` is a mistake in every language that allows it, so it is refused rather than read as
        // `(a < b) < c`.
        val r = Parser(Lexer("a < b < c").lex())
        try {
            r.expression()
            error("expected a syntax error")
        } catch (e: VsSyntaxError) {
            assertTrue("cannot follow another comparison" in e.message, e.message)
        }
    }

    @Test
    fun `short-circuit operators are distinct from the and or calls`() {
        // Two genuinely different nodes: logic.and evaluates both sides always, `&&` lowers to a Select
        // that does not. Collapsing them would make the language lie about which it built.
        assertIs<BinaryExpr>(expr("a && b"))
        assertIs<CallExpr>(expr("and(a, b)"))
    }

    @Test
    fun `a ternary is a choice`() {
        val e = expr("c ? 1 : 2") as TernaryExpr
        assertEquals(1, (e.ifTrue as LiteralExpr).value)
    }

    @Test
    fun `not is the only prefix operator`() {
        assertIs<NotExpr>(expr("!ready"))
    }

    @Test
    fun `calls take positional and named arguments`() {
        val c = expr("""interactObject(rock, action: "Mine")""") as CallExpr
        assertEquals(listOf(null, "action"), c.args.map { it.name })
    }

    @Test
    fun `a qualified call keeps its namespace`() {
        // `tile(…)` is the reserved literal constructor, so the draw node needs its namespace — the one
        // type of the 194 that must be qualified.
        assertEquals("draw.tile", (expr("draw.tile(t)") as CallExpr).name)
        assertEquals("tile", (expr("tile(1, 2, 0)") as CallExpr).name)
    }

    @Test
    fun `member access reads a field or an output pin`() {
        assertEquals("Tile", (expr("entityInfo(e).Tile") as MemberExpr).member)
        assertIs<NameExpr>((expr("s.tile") as MemberExpr).target)
    }

    @Test
    fun `indexing a list`() {
        assertIs<IndexExpr>(expr("xs[0]"))
    }

    @Test
    fun `records are made and copied`() {
        val made = expr("Spot { tile: t, name: \"x\" }") as StructLitExpr
        assertEquals("Spot", made.type)
        assertEquals(listOf("tile", "name"), made.fields.map { it.name })
        assertIs<WithExpr>(expr("s with { tile: t }"))
    }

    @Test
    fun `list literals`() {
        assertEquals(3, (expr("[1, 2, 3]") as ListLitExpr).items.size)
        assertEquals(0, (expr("[]") as ListLitExpr).items.size)
    }

    // ---- the struct-literal ambiguity -----------------------------------------------------------------

    @Test
    fun `a block after an if condition is a block, not a record`() {
        // `if ready { }` and `Spot { … }` begin identically. Inside a header the brace opens the block.
        val s = stmt("if ready { openBank() }") as IfStmt
        assertIs<NameExpr>(s.condition)
        assertEquals(1, s.then.stmts.size)
    }

    @Test
    fun `a record inside a header call still reads as a record`() {
        // The ambiguity only exists at the top level of the header — inside brackets it is gone.
        val s = stmt("if near(Spot { tile: t }) { }") as IfStmt
        val call = s.condition as CallExpr
        assertIs<StructLitExpr>(call.args.single().value)
    }

    @Test
    fun `while and for headers behave the same way`() {
        assertIs<NameExpr>((stmt("while going { }") as WhileStmt).condition)
        assertIs<NameExpr>((stmt("for x in xs { }") as ForStmt).list)
    }

    // ---- annotations ----------------------------------------------------------------------------------

    @Test
    fun `an id is optional and parsed onto the statement`() {
        // The one intrinsic left. Never written by the printer — positions and ids are canvas details, not
        // things a person types — but accepted, so a file can pin an id where that matters.
        val s = stmt("@id(7) openBank()")
        assertEquals(7, s.ann.id)
        assertTrue(stmt("openBank()").ann.isEmpty)
    }

    @Test
    fun `an unrecognised annotation is kept, not refused`() {
        // Metadata the language has not been taught still round-trips: preserving something you do not
        // interpret cannot be got wrong, and refusing it would make the mechanism unable to carry anything
        // new. See VSCRIPT_LANG_PLAN.md §8b.
        val r = parse("""on start { @author("jw") @todo openBank() }""")
        assertTrue(r.ok, "should not be an error: ${r.errors.map { it.message }}")
        val entry = r.program.decls.single() as EntryDecl
        val ann = entry.body.stmts.single().ann
        assertEquals(listOf("author", "todo"), ann.extras.map { it.name })
        assertEquals(listOf("jw"), ann.extras[0].args)
        assertEquals(emptyList(), ann.extras[1].args)
    }

    @Test
    fun `an unrecognised annotation warns so a typo is visible`() {
        // Not fatal, but not silent either — `@retires` sitting there doing nothing is the failure mode.
        val r = parse("on start { @retires(3) openBank() }")
        assertTrue(r.ok)
        assertTrue(r.warnings.any { "is not one of" in it.message }, "${r.warnings}")
    }

    @Test
    fun `an intrinsic does not land in the general bag`() {
        assertTrue(stmt("@id(3) openBank()").ann.extras.isEmpty())
        assertEquals(listOf("author"), stmt("""@author("jw") openBank()""").ann.extras.map { it.name })
    }

    // ---- imports ----------------------------------------------------------------------------------

    @Test
    fun `an import declaration binds an alias to a document`() {
        val r = parse("""import * as banking from "bank ops"  on start { }""")
        assertTrue(r.ok, "${r.errors.map { it.message }}")
        val imp = r.program.decls.filterIsInstance<ImportDecl>().single()
        assertEquals("banking", imp.alias)
        // The ref is the STRING's decoded value, so a document whose name has a space is nameable.
        assertEquals("bank ops", imp.ref)
    }

    @Test
    fun `a qualified call carries its module apart from the dotted type path`() {
        val call = expr("banking::withdraw(50)") as CallExpr
        assertEquals("banking", call.module)
        assertEquals("withdraw", call.name)
        assertEquals("banking::withdraw", call.qualified)
    }

    @Test
    fun `a qualified bare name is a module's variable, not a local one`() {
        val e = expr("log(banking::trips)") as CallExpr
        val arg = e.args.single().value as NameExpr
        assertEquals("banking", arg.module)
        assertEquals("trips", arg.name)
    }

    @Test
    fun `a leading colon-colon has no alias in front of it and says so`() {
        val r = parse("on start { ::withdraw() }")
        assertTrue(r.errors.any { "must follow an import alias" in it.message }, "${r.errors.map { it.message }}")
    }

    // ---- error recovery -------------------------------------------------------------------------------

    @Test
    fun `one bad statement does not cascade`() {
        // The point of collecting rather than throwing: a file should report its mistakes, not its first.
        val r = parse(
            """
            on start {
                openBank()
                let = 5
                depositAll()
            }
            fn later() { }
            """.trimIndent()
        )
        assertEquals(1, r.errors.size, "expected exactly one error, got ${r.errors.map { it.message }}")
        // And the parser kept going: the declaration after the broken one is still understood.
        assertTrue(r.program.decls.any { it is FnDecl && it.name == "later" })
    }

    @Test
    fun `errors carry a place to point at`() {
        val r = parse("on start {\n    val = 5\n}")
        assertEquals(2, r.errors.single().span.line)
    }

    @Test
    fun `a broken declaration does not swallow the next one`() {
        val r = parse("type { } \n fn after() { }")
        assertTrue(r.errors.isNotEmpty())
        assertTrue(r.program.decls.any { it is FnDecl && it.name == "after" })
    }

    // ---- writing into a list position -----------------------------------------------------------------

    /**
     * GAPS #11. `xs[0] = 9` used to report *"this does nothing on its own — a statement has to be a call"*.
     *
     * True of the statement shape and useless to the author: it is about statements, says nothing about
     * lists, and names no cure. There is no index assignment because a list is a VALUE — the same rule that
     * makes writing a field through a parameter a reported mistake — so the message has to say that and then
     * say what to write instead.
     */
    @Test
    fun `writing into a list position parses`() {
        // It used to be refused with a message about lists being values. It is a statement now — sugar
        // for `xs.set(index: 0, value: 9)` — because a container is a reference and one position CAN be
        // written into. See [IndexAssignStmt].
        ok("on start {\n    xs[0] = 9\n}")
    }

    @Test
    fun `an index assignment reads as one statement, not two`() {
        val prog = parse("on start {\n    spots[i] = t\n}").program
        val entry = prog.decls.filterIsInstance<EntryDecl>().single()
        assertTrue(
            entry.body.stmts.single() is IndexAssignStmt,
            "expected one IndexAssignStmt, got ${entry.body.stmts.map { it::class.simpleName }}",
        )
    }

    /**
     * The COMPOUND forms are still refused, and the message says why rather than talking about values.
     *
     * `xs[i] += 1` has to read the position and write it back — two operator calls, not one — and a type
     * offering `set` need not offer `get`. Expanding it quietly would invent the read.
     */
    @Test
    fun `a compound write into a list position is still refused, naming what to write`() {
        val m = parse("on start {\n    counts[0] += 1\n}").errors.single().message
        assertTrue("counts[i] = counts[i]" in m, "should spell the long form, got: $m")
    }

    /** Reading one is untouched — only assignment is refused. */
    @Test
    fun `reading a list position is still fine`() {
        ok("on start {\n    val x = xs[0]\n}")
    }

    /** A field write through an index still works: it is the RECORD that is being rebuilt, not the list. */
    @Test
    fun `a field assignment through an index is not caught by this`() {
        val r = parse("on start {\n    xs[0].name = \"a\"\n}")
        assertTrue(r.ok, "unexpected errors: ${r.errors.map { it.message }}")
    }
}
