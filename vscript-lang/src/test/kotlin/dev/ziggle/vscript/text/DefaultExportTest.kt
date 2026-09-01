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
 * `export default`, and the default import that reads it.
 *
 * **None of this worked, and it failed in the quietest possible way.** The import side registered a
 * default import under the name `@default` and nothing ever wrote that key — so
 * `import Roster from "activities/farmrun"` resolved its document, reported nothing on the import line,
 * and then failed at every USE with `nothing here is called 'Roster'`. That reads like a typo in the file
 * that is right. There was not one test of it on this side of the front end.
 */
class DefaultExportTest {

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(main: String, others: Map<String, String>): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives, imports = TextSource.of(others)).read(main)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(main: String, others: Map<String, String> = emptyMap()): List<String> =
        TextFrontEnd(natives, imports = TextSource.of(others)).resolve(main).errors.map { it.message }

    @Test
    fun `a default import binds a function`() {
        assertEquals(
            listOf("7"),
            run(
                """
                import ans from "lib"
                on start { log(message: "" + ans()) }
                """.trimIndent(),
                mapOf("lib" to "export default fn answer() -> INT { return 7 }"),
            ),
        )
    }

    /**
     * A `single` is a record AND its one instance, so a default import of one has to carry both.
     *
     * The value bound and the type did not: a binding carries its type as a SPELLING rather than an
     * origin, and a default import brings exactly ONE name across by construction — so the record was
     * unreachable and the field access answered `Bag has no fields`, which reads as a broken declaration
     * in the file that declares it perfectly well. See `Resolver.importedRecord`.
     */
    @Test
    fun `a default import binds a single, fields and all`() {
        assertEquals(
            listOf("3"),
            run(
                """
                import Box from "lib"
                on start { log(message: "" + Box.A) }
                """.trimIndent(),
                mapOf("lib" to "export default single Bag { A: INT = 3 }"),
            ),
        )
    }

    /**
     * **A NAMED default is still reachable by its own name.** `export default` adds a second way to say a
     * declaration; it does not take the first one away, so an unqualified import sees `Bag` as `Bag`.
     */
    @Test
    fun `a named default is also reachable by its own name`() {
        assertEquals(
            listOf("3"),
            run(
                """
                import "lib"
                on start { log(message: "" + Bag.A) }
                """.trimIndent(),
                mapOf("lib" to "export default single Bag { A: INT = 3 }"),
            ),
        )
    }

    /**
     * **...and it may have no name at all.** `export default single { … }` — a barrel whose whole job is to
     * be one thing an importer names has nothing to call it, and a name it never says is one more thing to
     * collide with, which is exactly how a `single Hooks` beside an `import { Hooks }` went wrong.
     */
    @Test
    fun `a default single may be anonymous`() {
        assertEquals(
            listOf("5"),
            run(
                """
                import Kit from "lib"
                on start { log(message: "" + Kit.A) }
                """.trimIndent(),
                mapOf("lib" to "export default single { A: INT = 5 }"),
            ),
        )
    }

    @Test
    fun `a default type may be anonymous`() {
        assertEquals(
            listOf("9"),
            run(
                """
                import Point from "lib"
                on start {
                    val p = Point { x: 9 }
                    log(message: "" + p.x)
                }
                """.trimIndent(),
                mapOf("lib" to "export default type { x: INT }"),
            ),
        )
    }

    /**
     * **Two documents with anonymous defaults, read in one file.**
     *
     * `@default` is one shared string, and [dev.ziggle.vscript.lang.ANONYMOUS_DEFAULT] used to argue that
     * this was the point — the declaration registers under the name a default import already looks up, so
     * the anonymous case needs no machinery. True for ONE such document and wrong the moment there are
     * two: a `TypeRef` carries a spelling and not an origin, so both bindings are typed `@default` and
     * `importedRecord` can only look the spelling up again, taking whichever import it meets first.
     *
     * The symptom is the worst kind, because the file that breaks is not the file that is wrong:
     * `Minigame.Wintertodt` reported `@default has no field 'Wintertodt' — it has HerbRun`, naming the
     * OTHER document's fields, in a file where both documents are written perfectly well.
     */
    @Test
    fun `two anonymous defaults do not collide`() {
        assertEquals(
            listOf("5", "7"),
            run(
                """
                import Errand from "errand"
                import Minigame from "minigame"
                on start {
                    log(message: "" + Errand.HerbRun)
                    log(message: "" + Minigame.Wintertodt)
                }
                """.trimIndent(),
                mapOf(
                    "errand" to "export default single { HerbRun: INT = 5 }",
                    "minigame" to "export default single { Wintertodt: INT = 7 }",
                ),
            ),
        )
    }

    /**
     * **`export default OurHooks`** — a declaration written above, named as the default.
     *
     * Every other form attaches `default` to a declaration being written there, which forces the one thing
     * a document IS to be declared inline. That is the wrong way round for the shape the corpus uses: a
     * value assembled from several imports, then offered — and inlining it means the assembly cannot be
     * named, documented, or referred to by the file that owns it.
     */
    @Test
    fun `a default may name a declaration written above it`() {
        assertEquals(
            listOf("11"),
            run(
                """
                import Kit from "lib"
                on start { log(message: "" + Kit.size) }
                """.trimIndent(),
                mapOf(
                    "lib" to """
                        single Bag { size: INT = 11 }

                        export default Bag
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** ...and it is still one default per document. */
    @Test
    fun `naming a second default is refused`() {
        val bad = errors(
            """
            fn one() -> INT = 1
            fn two() -> INT = 2

            export default one
            export default two
            """.trimIndent(),
        )
        assertTrue(bad.any { it.contains("already this document's default") }, "got $bad")
    }

    /** An anonymous default has no local name, so the document that declares it cannot say it either. */
    @Test
    fun `an anonymous default cannot be named where it is declared`() {
        val bad = errors(
            """
            export default single { A: INT = 5 }
            fn probe() -> INT = default.A
            """.trimIndent(),
        )
        assertTrue(bad.isNotEmpty(), "'default' is not a way to name it")
    }

    /**
     * Only a shape may go unnamed. A `fn` is reachable by name from inside its own document, and an
     * anonymous one could not be called there at all.
     */
    @Test
    fun `a function still needs a name`() {
        val bad = errors("export default fn () -> INT = 1")
        assertTrue(
            bad.any { it.contains("name") },
            "expected a complaint about the missing name, got $bad",
        )
    }

    /**
     * **Two explicit claims on one name is an error, at the declaration.**
     *
     * The case the unqualified forms already refuse, for the same reason: a name here does not quietly
     * mean two things. A local declaration still wins over a bare `import "x"` or an `export *` without
     * complaint — that is the bargain that makes a barrel safe to add to — but where somebody wrote BOTH
     * names down, neither has a better claim.
     */
    @Test
    fun `a declaration may not take a name an import already claims`() {
        val bad = errors(
            """
            import { Hooks } from "lib"
            export default single Hooks { A: INT = 1 }
            """.trimIndent(),
            mapOf("lib" to "export type Hooks { n: INT }"),
        )
        assertTrue(
            bad.any { it.contains("'Hooks' is already imported from \"lib\"") },
            "expected the collision to be reported at the declaration, got $bad",
        )
    }

    /** A local declaration still wins over an unqualified import, silently — the barrel bargain. */
    @Test
    fun `a local declaration still shadows an unqualified import`() {
        assertEquals(
            listOf("2"),
            run(
                """
                import "lib"
                type Point { n: INT }
                on start {
                    val p = Point { n: 2 }
                    log(message: "" + p.n)
                }
                """.trimIndent(),
                mapOf("lib" to "export type Point { other: INT }"),
            ),
        )
    }
}
