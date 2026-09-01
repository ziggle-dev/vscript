package dev.ziggle.vscript.text

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Names and types, decided before anything is emitted.
 *
 * Every case here is one the graph pipeline either cannot answer or answers by accident, and several are
 * open entries in `GAPS.md`. What they have in common is that the answer needs somewhere to be WRITTEN
 * DOWN — a binding, a type on an expression — and a graph's only places to write are pins.
 */
class ResolverTest {

    private val INT = TypeRef(PinType.INT)
    private val FLOAT = TypeRef(PinType.FLOAT)
    private val STRING = TypeRef(PinType.STRING)
    private val BOOL = TypeRef(PinType.BOOL)
    /**
     * The language declares no records of its own either; a test that wants one declares it.
     *
     * A DATA record — its values are plain structs of these fields — which is what makes `t.x` an ordinary
     * field read and `Tile { … }` a thing that can be written. It used to be `PinType.TILE`, a builtin,
     * and moving it here is the point of the change rather than a consequence of it: the tests for a
     * language with no game should not be able to name one.
     */
    private val tileRecord = dev.ziggle.vscript.model.HostRecord(
        "Tile",
        listOf(
            dev.ziggle.vscript.model.HostField("x", TypeRef(PinType.INT)),
            dev.ziggle.vscript.model.HostField("y", TypeRef(PinType.INT)),
            dev.ziggle.vscript.model.HostField("plane", TypeRef(PinType.INT)),
        ),
        isData = true,
    )
    private val TILE = tileRecord.type

    /** The language declares no enums of its own; a test that wants one declares it. */
    private val skillEnum = dev.ziggle.vscript.model.HostEnum("Skill", listOf("Attack", "Mining", "Magic", "Crafting"))
    private val SKILL = skillEnum.type

    init {
        // The language declares no host enums or records, so this test registers the ones its natives name.
        dev.ziggle.vscript.model.HostEnums.register(skillEnum)
        dev.ziggle.vscript.model.HostRecords.register(tileRecord)
    }

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            NativeFn("level", listOf(NativeParam("skill", SKILL)), results = outs(INT)),
            NativeFn("walkTo", listOf(NativeParam("where", TILE)), results = emptyList()),
            NativeFn("inventoryFull", results = outs(BOOL)),
            NativeFn("distance", listOf(NativeParam("a", TILE), NativeParam("b", TILE)), results = outs(FLOAT)),
        ) + dev.ziggle.vscript.domain.TileFixture.NATIVES,
    )

    private fun resolve(src: String): Resolution {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) fail("the fixture does not parse: ${parsed.errors.first().message}")
        return Resolver(natives).resolve(parsed.program)
    }

    private fun errors(src: String): List<String> = resolve(src).errors.map { it.message }

    private fun clean(src: String): Resolution {
        val r = resolve(src)
        assertTrue(r.ok, "expected no complaints, got: ${r.errors.map { it.message }}")
        return r
    }

    /** The binding a declaration introduced — see `Resolution.localOf`. */
    private fun Resolution.local(name: String): LocalBinding =
        localOf.values.firstOrNull { it.name == name } ?: fail("nothing declared '$name'")

    private fun body(stmts: String) = """
        graph "probe"

        on start {
        $stmts
        }
    """.trimIndent()

    // ---- types get decided ---------------------------------------------------------------------------

    @Test
    fun `a local takes the type of what it is given`() {
        val r = clean(body("""    val n = 3${'\n'}    log(message: "" + n)"""))
        assertEquals(INT.name, r.local("n").type.name)
    }

    /**
     * GAPS 16: `let n: FLOAT = 0` is a FLOAT that starts at zero, not an Int.
     *
     * Open against the graph pipeline since it was found, and open because there is nowhere to record the
     * annotation once the literal has been folded — the pin says FLOAT and the value in it is an `Int`.
     * Here the binding carries the declared type and the initialiser is checked against it, which is both
     * halves of the answer.
     */
    @Test
    fun `a declared type wins over the initialiser`() {
        val r = clean(body("    var n: FLOAT = 0"))
        assertEquals(FLOAT.name, r.local("n").type.name)
    }

    @Test
    fun `a declared type is enforced, not merely recorded`() {
        assertTrue(
            errors(body("""    val n: INT = "three"""")).any { it.contains("INT") && it.contains("STRING") },
            "assigning a string to an INT was accepted",
        )
    }

    // ---- names ---------------------------------------------------------------------------------------

    @Test
    fun `an unknown name is refused by name`() {
        assertTrue(errors(body("    log(message: nope)")).any { it.contains("nope") })
    }

    @Test
    fun `a nullary native reads as a value`() {
        clean(body("""    if inventoryFull() {${'\n'}        log(message: "full")${'\n'}    }"""))
    }

    @Test
    fun `writing to a val is refused`() {
        val e = errors(body("    val n = 1\n    n = 2"))
        assertTrue(e.any { it.contains("'n'") && it.contains("var") }, "got: $e")
    }

    @Test
    fun `a var may be written`() {
        clean(body("    var n = 1\n    n = 2"))
    }

    /** A name declared inside a block stops existing at its closing brace. */
    @Test
    fun `a block scope ends at its brace`() {
        val e = errors(
            body(
                """    if true {
        val inner = 1
    }
    log(message: "" + inner)""",
            ),
        )
        assertTrue(e.any { it.contains("inner") }, "the inner name escaped its block: $e")
    }

    // ---- calls ---------------------------------------------------------------------------------------

    @Test
    fun `a missing argument is refused`() {
        assertTrue(errors(body("    log()")).any { it.contains("message") })
    }

    @Test
    fun `an unknown label is refused`() {
        assertTrue(errors(body("""    log(mesage: "hi")""")).any { it.contains("mesage") })
    }

    @Test
    fun `an argument of the wrong type is refused`() {
        assertTrue(errors(body("    log(message: true)")).any { it.contains("STRING") })
    }

    @Test
    fun `the resolver records each call's arguments in parameter order`() {
        val r = clean(body("""    log(message: "hi")"""))
        assertEquals(1, r.argumentsOf.size)
        assertEquals(1, r.argumentsOf.values.first().size)
    }

    @Test
    fun `a condition has to be a boolean`() {
        assertTrue(errors(body("    if 3 {\n        log(message: \"x\")\n    }")).any { it.contains("true or false") })
    }

    // ---- records, and the two types that always had structure ----------------------------------------

    @Test
    fun `a declared record's field can be read`() {
        clean(
            """
            graph "probe"

            type Trip { bank: STRING, laps: INT }

            on start {
                val t = Trip { bank: "Varrock", laps: 3 }
                log(message: t.bank)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an unknown field names the ones there are`() {
        val e = errors(
            """
            graph "probe"

            type Trip { bank: STRING, laps: INT }

            fn describe(t: Trip) -> STRING {
                return t.bnk
            }

            on start { }
            """.trimIndent(),
        )
        assertTrue(e.any { it.contains("bnk") && it.contains("laps") }, "got: $e")
    }

    /**
     * A TILE has three fields and has never been able to say so.
     *
     * It is stored as the string `"x,y,plane"`, which is a good answer to a question about copying tiles
     * off wiki pages and no answer at all to `t.x`. The prelude gives it the structure it always had.
     */
    @Test
    fun `a tile has fields`() {
        clean(
            """
            graph "probe"

            fn eastOf(t: TILE) -> INT {
                return t.x + 1
            }

            on start { }
            """.trimIndent(),
        )
    }

    /**
     * The game has a fixed list of skills, and until now nothing consulted it.
     *
     * A mistyped skill typechecked as a string and failed in the game, as a verb that quietly did nothing.
     */
    @Test
    fun `a mistyped skill is caught`() {
        // It used to be caught by a VALUE check against the member list, because the pin was a STRING that
        // happened to be drawn as a dropdown. `Skill` is a type now, so the string never gets that far —
        // caught earlier, and for a better reason.
        val e = errors(body("""    log(message: "" + level(skill: "Attak"))"""))
        assertTrue(e.isNotEmpty(), "a bare string must not pass for a skill")
    }

    @Test
    fun `a real skill is accepted`() {
        clean(body("""    log(message: "" + level(skill: Skill.Crafting))"""))
    }

    /**
     * A string is not a tile, and there is no spelling that makes it one.
     *
     * It used to be: a tile WAS the string `"x,y,plane"`, so any string typechecked as one and a typo was
     * a runtime surprise. A tile is a record now, built by `tile(…)`, and the string form is gone from the
     * language rather than kept beside it — two spellings for one value is what this change removed.
     */
    @Test
    fun `a string is not a tile`() {
        val e = errors(body("""    walkTo(where: "3200,3200,0")"""))
        assertTrue(e.any { it.contains("Tile") }, "got: $e")
    }

    @Test
    fun `a tile is built by the tile function`() {
        clean(body("    walkTo(where: tile(3200, 3200, 0))"))
    }

    // ---- arithmetic ----------------------------------------------------------------------------------

    @Test
    fun `int plus float is a float`() {
        val r = clean(body("    val n = 1 + 2.5"))
        assertEquals(FLOAT.name, r.local("n").type.name)
    }

    @Test
    fun `a string on either side of plus makes it concatenation`() {
        clean(body("""    val s = "laps: " + 3"""))
    }

    @Test
    fun `subtracting strings is refused`() {
        assertTrue(errors(body("""    val s = "a" - "b"""")).isNotEmpty())
    }

    // ---- functions -----------------------------------------------------------------------------------

    @Test
    fun `a function body is checked against its declared results`() {
        val e = errors(
            """
            graph "probe"

            fn _listCount() -> INT {
                return "many"
            }

            on start { }
            """.trimIndent(),
        )
        assertTrue(e.any { it.contains("INT") }, "got: $e")
    }

    @Test
    fun `a function may be called before it is declared`() {
        clean(
            """
            graph "probe"

            on start {
                log(message: greeting())
            }

            fn greeting() -> STRING {
                return "hello"
            }
            """.trimIndent(),
        )
    }

    // ---- it collects, rather than stopping at the first ----------------------------------------------

    @Test
    fun `every error in the file is reported, not just the first`() {
        val e = errors(body("    log(message: nope)\n    log(message: alsoNope)"))
        assertTrue(e.size >= 2, "only ${e.size} reported: $e")
    }

    @Test
    fun `a diagnostic points at a line`() {
        val d = resolve(body("    log(message: nope)")).errors.first()
        assertTrue(d.span.line > 0, "the complaint has no line")
    }
}
