package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A constant that another document wrote.
 *
 * **Every side table a fold reads is keyed by AST identity**, so an expression only means anything against
 * the resolution that produced it. Folding an imported `val` against the IMPORTER's tables looks up its
 * names in the wrong document, finds nothing, and folds them to null — and a record comes out correctly
 * shaped with its function fields empty. Nothing fails at compile time; the failure arrives pages away, at
 * the first call, as "nothing to call: register N holds 'null', not a function".
 *
 * Found by running `rotation.vs` in the client, where the chain is exactly the one below: an enum column
 * holds `seaweed::Impl`, a record of function references written in a third document.
 */
class ImportedConstantTest {

    private val STRING = TypeRef(PinType.STRING)
    private val INT = TypeRef(PinType.INT)

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList())) + dev.ziggle.vscript.domain.TileFixture.NATIVES,
    )

    /** The record type, in a document of its own — as `core/activity` is. */
    private val shapes = """
        graph "shapes"
        export type Hooks {
            ready: fn() -> BOOL,
            describe: fn() -> STRING,
        }
    """.trimIndent()

    /** The implementation, in a second document — as `activities/farmrun/seaweed` is. */
    private val impl = """
        graph "impl"
        import "shapes"
        fn amReady() -> BOOL = true
        fn myName() -> STRING = "seaweed"
        export val Impl: Hooks = Hooks { ready: amReady, describe: myName }
    """.trimIndent()

    private fun run(main: String, others: Map<String, String>): List<String> {
        val said = ArrayList<String>()
        // The builtins, because reading an enum COLUMN goes through `vscript.enumField`.
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val front = TextFrontEnd(natives, imports = TextSource.of(others))
        val r = front.read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private val modules = mapOf("shapes" to shapes, "impl" to impl)

    @Test
    fun `an imported record of function references keeps its functions`() {
        assertEquals(
            listOf("seaweed", "true"),
            run(
                """
                graph "main"
                import "shapes"
                import * as other from "impl"
                on start {
                    val hooks = other::Impl
                    log(message: hooks.describe())
                    log(message: "" + hooks.ready())
                }
                """.trimIndent(),
                modules,
            ),
        )
    }

    /**
     * The same value reached through an ENUM COLUMN, which is where it actually broke.
     *
     * A column is folded to a constant at compile time, so this is the path that has to resolve names
     * against the document that wrote them rather than the one declaring the table.
     */
    @Test
    fun `an enum column holding another document's record keeps its functions`() {
        assertEquals(
            listOf("seaweed", "true"),
            run(
                """
                graph "main"
                import "shapes"
                import * as other from "impl"

                enum Roster(impl: Hooks) {
                    Seaweed(other::Impl),
                }

                on start {
                    val row = Roster.Seaweed.impl
                    log(message: row.describe())
                    log(message: "" + row.ready())
                }
                """.trimIndent(),
                modules,
            ),
        )
    }

    /** And copied field-by-field into another record, as `Roster.activity()` does. */
    @Test
    fun `a function field survives being copied into another record`() {
        assertEquals(
            listOf("seaweed"),
            run(
                """
                graph "main"
                import "shapes"
                import * as other from "impl"

                type Row { name: fn() -> STRING }

                enum Roster(impl: Hooks) {
                    Seaweed(other::Impl),
                }

                fn Roster.row(self) -> Row = Row { name: self.impl.describe }

                on start {
                    val r = Roster.Seaweed.row()
                    log(message: r.name())
                }
                """.trimIndent(),
                modules,
            ),
        )
    }

    /**
     * An enum column holding a built-in CONSTRUCTOR, rather than a literal.
     *
     * A column is folded to a constant at compile time, and the folder knew literals, names, members,
     * record literals and lists — not calls. So `tile(1620, 3988, 0)` folded to null, every corner of an
     * arena came out with no tile in it, and the first read faulted three calls away as
     * "GETFIELD on null, expected a record".
     *
     * It was invisible until `tile` became a real type with a constructor: written as a string it was a
     * literal, and a literal always folded.
     */
    @Test
    fun `an enum column may hold a tile`() {
        assertEquals(
            listOf("1620,3988,0", "1639,4025,3"),
            run(
                """
                graph "main"
                enum Corner(roots: TILE) {
                    SouthWest(tile(1620, 3988, 0)),
                    NorthEast(tile(1639, 4025, 3)),
                }
                fn show(c: Corner) -> STRING {
                    val t = c.roots
                    return "" + t.x + "," + t.y + "," + t.plane
                }
                on start {
                    log(message: show(c: Corner.SouthWest))
                    log(message: show(c: Corner.NorthEast))
                }
                """.trimIndent(),
                emptyMap(),
            ),
        )
    }

    /** The plane defaults, exactly as the emitter defaults it — two spellings that disagree is worse. */
    @Test
    fun `a tile in a column may leave its plane off`() {
        assertEquals(
            listOf("3200,3200,0"),
            run(
                """
                graph "main"
                enum Spot(at: TILE) { Home(tile(3200, 3200)) }
                on start {
                    val t = Spot.Home.at
                    log(message: "" + t.x + "," + t.y + "," + t.plane)
                }
                """.trimIndent(),
                emptyMap(),
            ),
        )
    }

    /**
     * A column holds whatever its declared type is, and a comparison against the same type works.
     *
     * This used to be written with `ITEM` and `ItemId(995)`, which were the LANGUAGE's until the id
     * conversions moved to the node pack that owns them. The property under test was never about items —
     * it is about a column carrying a value — so it is stated in a type the language actually has.
     */
    @Test
    fun `an enum column may hold a value compared against its own type`() {
        assertEquals(
            listOf("995"),
            run(
                """
                graph "main"
                enum Coin(id: INT) { Gp(995) }
                on start {
                    if Coin.Gp.id == 995 {
                        log(message: "995")
                    }
                }
                """.trimIndent(),
                emptyMap(),
            ),
        )
    }

    /**
     * A column may CALL something — the thing a folded table could never express.
     *
     * Kotlin evaluates an enum entry's arguments when the class initialises, and this is the same idea:
     * each column is a hidden document variable the prologue fills, so a cell may be any expression an
     * initialiser may be. Folded to a constant, only a literal, a record, a list or a function reference
     * could be written in one — anything else became null, silently, and faulted wherever it was first
     * read.
     */
    @Test
    fun `a column may be any expression, evaluated at run start`() {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        var lookups = 0
        hosts.register("itemByName", HostKind.INLINE, arity = 1, results = 1) { a ->
            lookups++
            if (a[0] == "Coins") 995 else 0
        }

        val front = TextFrontEnd(
            NativeTable(
                natives.all +
                    NativeFn("itemByName", listOf(NativeParam("name", STRING)), results = outs(INT)),
            ),
        )
        val read = front.read(
            """
            graph "main"
            enum Thing(id: INT) {
                Coins(itemByName(name: "Coins")),
                Nothing(itemByName(name: "Nothing")),
            }
            on start {
                log(message: "" + Thing.Coins.id)
                log(message: "" + Thing.Nothing.id)
                log(message: "" + Thing.Coins.id)
            }
            """.trimIndent(),
        )
        val chunk = read.chunk ?: fail(front.describe(read))
        drive(chunk, hosts)

        assertEquals(listOf("995", "0", "995"), said)
        // Evaluated ONCE, at run start, not on every read — that is what makes it an initialiser rather
        // than a call site.
        assertEquals(2, lookups, "a column was evaluated $lookups time(s) for two members")
    }
}
