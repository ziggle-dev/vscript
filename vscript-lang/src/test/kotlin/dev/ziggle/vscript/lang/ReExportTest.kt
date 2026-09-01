package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `export { a } from "y"`, `export * from "y"`, and the local `export { a, b }`.
 *
 * **A re-export is an import edge whose names go PAST the document rather than into it.** That one sentence
 * is the design: `Lower` records it as an ordinary [dev.ziggle.vscript.model.GraphImport] so the closure, the
 * globals layout and the document format need no new concept, and `GraphImport.bindsLocally` is what keeps
 * a barrel from being able to call what it forwards.
 *
 * **Resolution goes through one export map** — `exportsOf` — and that matters more than it sounds. Before
 * it, five separate places answered "what does this document offer" by reading its own declarations: the
 * scope's four lookups, its three `visible*` listings, and four more in `Lower`. A barrel declares nothing,
 * so every one of them would have had to learn to follow a chain, and the one that got it wrong would be a
 * name that resolves in the validator and not in the compiler.
 *
 * An [dev.ziggle.vscript.model.Export] names the OWNING document, so a barrel costs a hop at compile time
 * and none at run time: the Call resolves straight to the file that really declares the function.
 */
class ReExportTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    /** Lowered in the order given, each seeing the ones before it — which is what a barrel needs. */
    private fun libOf(vararg docs: Pair<String, String>): GraphSource {
        val built = ArrayList<dev.ziggle.vscript.model.Graph>()
        val source = GraphSource { imp -> built.firstOrNull { it.name == imp.ref } }
        for ((name, src) in docs) {
            val read = VsText(catalog, source).read(src)
            built += assertNotNull(
                read.graph,
                "$name should compile: ${read.errors.map { "${it.span.line}: ${it.message}" }}",
            )
        }
        return source
    }

    private fun read(src: String, source: GraphSource) = VsText(catalog, source).read(src)

    private fun said(src: String, source: GraphSource): List<Any?> {
        val r = read(src, source)
        val g = assertNotNull(r.graph, "should compile: ${r.errors.map { "${it.span.line}: ${it.message}" }}")
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val chunk = GraphCompiler(catalog, debug = false, source = source)
            .compile(g, g.entries(catalog).single().id)
        val out = drive(chunk, hosts, maxTicks = 20000)
        assertNull(out.fiber.error, "vm error: ${out.fiber.error}")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private val MATH = """
        graph "math"

        export fn twice(n: INT) -> INT = n * 2

        export fn half(n: INT) -> INT = n / 2

        fn secret(n: INT) -> INT = n
    """.trimIndent()

    private val SHAPES = """
        graph "shapes"

        export type Point { x: INT, y: INT }

        export fn originX() -> INT = 0
    """.trimIndent()

    // ---- passing names on ---------------------------------------------------------------------------

    @Test
    fun `a named re-export reaches through the barrel`() {
        assertEquals(
            listOf(10L),
            said(
                """
                graph "probe"

                import * as all from "barrel"

                on start {
                    say(message: all::twice(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "math" to MATH,
                    "barrel" to "graph \"barrel\"\n\nexport { twice } from \"math\"\n",
                ),
            ),
        )
    }

    @Test
    fun `a star re-export passes everything on`() {
        assertEquals(
            listOf(10L, 3L),
            said(
                """
                graph "probe"

                import * as all from "barrel"

                on start {
                    say(message: all::twice(n: 5))
                    say(message: all::half(n: 6))
                }
                """.trimIndent(),
                libOf(
                    "math" to MATH,
                    "barrel" to "graph \"barrel\"\n\nexport * from \"math\"\n",
                ),
            ),
        )
    }

    @Test
    fun `a re-export may rename on the way through`() {
        assertEquals(
            listOf(10L),
            said(
                """
                graph "probe"

                import { doubled } from "barrel"

                on start {
                    say(message: doubled(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "math" to MATH,
                    "barrel" to "graph \"barrel\"\n\nexport { twice as doubled } from \"math\"\n",
                ),
            ),
        )
    }

    /** Two hops, which is what a barrel of barrels is — and the thing a per-document walk gets wrong. */
    @Test
    fun `a re-export chains`() {
        assertEquals(
            listOf(10L),
            said(
                """
                graph "probe"

                import * as outer from "outer"

                on start {
                    say(message: outer::twice(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "math" to MATH,
                    "inner" to "graph \"inner\"\n\nexport * from \"math\"\n",
                    "outer" to "graph \"outer\"\n\nexport * from \"inner\"\n",
                ),
            ),
        )
    }

    /** A record crosses two boundaries, which is where the requalification has to be done from the OWNER. */
    @Test
    fun `a re-exported record keeps its fields`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                import * as all from "barrel"

                on start {
                    say(message: all::Point { x: 3, y: 4 }.x)
                }
                """.trimIndent(),
                libOf(
                    "shapes" to SHAPES,
                    "barrel" to "graph \"barrel\"\n\nexport * from \"shapes\"\n",
                ),
            ),
        )
    }

    @Test
    fun `a barrel may re-export a default under a name`() {
        assertEquals(
            listOf(10L),
            said(
                """
                graph "probe"

                import { doubled } from "barrel"

                on start {
                    say(message: doubled(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "method" to "graph \"method\"\n\nexport default fn run(n: INT) -> INT = n * 2\n",
                    "barrel" to "graph \"barrel\"\n\nexport { default as doubled } from \"method\"\n",
                ),
            ),
        )
    }

    // ---- what it does NOT do ------------------------------------------------------------------------

    /**
     * The line that separates a re-export from an import followed by an export.
     *
     * A barrel forwards names; it does not acquire them. Getting this wrong would let a barrel call what it
     * forwards, and then `export … from` would just be a longer way to write two lines.
     */
    @Test
    fun `a re-export does not put the name in the barrel's own scope`() {
        val r = read(
            """
            graph "barrel"

            export * from "math"

            export fn quad(n: INT) -> INT = twice(n: twice(n: n))
            """.trimIndent(),
            libOf("math" to MATH),
        )
        assertTrue(!r.ok, "the barrel must not be able to call what it forwards")
    }

    @Test
    fun `a re-export cannot pass on what the target does not export`() {
        val r = read(
            """
            graph "probe"

            import * as all from "barrel"

            on start {
                say(message: all::secret(n: 1))
            }
            """.trimIndent(),
            libOf(
                "math" to MATH,
                "barrel" to "graph \"barrel\"\n\nexport * from \"math\"\n",
            ),
        )
        assertTrue(!r.ok, "`secret` says no export, so no barrel can offer it")
    }

    /** A local declaration wins over a `*` that offers the same name — TypeScript's rule. */
    @Test
    fun `the barrel's own declaration shadows a star re-export`() {
        assertEquals(
            listOf(100L),
            said(
                """
                graph "probe"

                import * as all from "barrel"

                on start {
                    say(message: all::twice(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "math" to MATH,
                    "barrel" to """
                        graph "barrel"

                        export * from "math"

                        export fn twice(n: INT) -> INT = 100
                    """.trimIndent(),
                ),
            ),
        )
    }

    /**
     * Two stars offering one name is ambiguous, not last-wins.
     *
     * The failure this prevents is the nasty one: `export *` names nothing, so a barrel acquires a
     * collision when a library it forwards grows a symbol — and the file that breaks is not the file that
     * changed. Refused against the BARREL, which is the only document that can fix it: the importer did
     * nothing wrong and pointing at it would send somebody to the wrong file.
     */
    @Test
    fun `two star re-exports offering one name are refused`() {
        val source = libOf(
            "math" to MATH,
            "other" to "graph \"other\"\n\nexport fn twice(n: INT) -> INT = 999\n",
        )
        val r = read(
            """
            graph "barrel"

            export * from "math"
            export * from "other"
            """.trimIndent(),
            source,
        )
        assertTrue(!r.ok, "an ambiguous star re-export must be refused")
        assertTrue(
            r.errors.any { "'twice' is offered by two" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    /** ...and naming the one you meant is the cure the message points at. */
    @Test
    fun `naming the one you meant settles an ambiguous star`() {
        assertEquals(
            listOf(10L),
            said(
                """
                graph "probe"

                import * as all from "barrel"

                on start {
                    say(message: all::twice(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "math" to MATH,
                    "other" to "graph \"other\"\n\nexport fn twice(n: INT) -> INT = 999\n",
                    "barrel" to """
                        graph "barrel"

                        export * from "math"
                        export * from "other"
                        export { twice } from "math"
                    """.trimIndent(),
                ),
            ),
        )
    }

    // ---- the local list -----------------------------------------------------------------------------

    @Test
    fun `export with no from marks this document's own names`() {
        assertEquals(
            listOf(10L),
            said(
                """
                graph "probe"

                import { twice } from "listed"

                on start {
                    say(message: twice(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "listed" to """
                        graph "listed"

                        fn twice(n: INT) -> INT = n * 2

                        fn hidden(n: INT) -> INT = n

                        export { twice }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /**
     * The whole surface at the bottom, TypeScript's other idiom.
     *
     * Every kind, in one place, said about declarations that carry no modifier of their own — which is the
     * point of the form: the file reads as code and the last line reads as its API.
     */
    @Test
    fun `every kind of declaration may be exported from a list at the bottom`() {
        assertEquals(
            listOf(10L, 3L, 1L, 9L),
            said(
                """
                graph "probe"

                import { twice, Point, Seed, Cap } from "listed"

                on start {
                    say(message: twice(n: 5))
                    say(message: Point { x: 3 }.x)
                    say(message: Seed)
                    say(message: Cap)
                }
                """.trimIndent(),
                libOf(
                    "listed" to """
                        graph "listed"

                        fn twice(n: INT) -> INT = n * 2

                        type Point { x: INT }

                        var Seed: INT = 1

                        val Cap = 9

                        fn hidden() -> INT = 0

                        export { twice, Point, Seed, Cap }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** Beside the site form, not instead of it — a file may use both, and they add up. */
    @Test
    fun `a list at the bottom sits alongside declarations exported in place`() {
        assertEquals(
            listOf(10L, 15L),
            said(
                """
                graph "probe"

                import { twice, thrice } from "mixed"

                on start {
                    say(message: twice(n: 5))
                    say(message: thrice(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "mixed" to """
                        graph "mixed"

                        export fn thrice(n: INT) -> INT = n * 3

                        fn twice(n: INT) -> INT = n * 2

                        export { twice }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** Saying it twice is not an error — both spellings mean the same thing about the same declaration. */
    @Test
    fun `exporting a declaration in place and again in a list is harmless`() {
        assertEquals(
            listOf(10L),
            said(
                """
                graph "probe"

                import { twice } from "both"

                on start {
                    say(message: twice(n: 5))
                }
                """.trimIndent(),
                libOf(
                    "both" to """
                        graph "both"

                        export fn twice(n: INT) -> INT = n * 2

                        export { twice }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** A list at the bottom round-trips as itself — it is a declaration, not a rewriting of the others. */
    @Test
    fun `a bottom export list round-trips`() {
        val vs = VsText(catalog)
        val text = """
            graph "listed"

            fn twice(n: INT) -> INT = n * 2

            export { twice }
        """.trimIndent() + "\n"
        val read = vs.read(text)
        assertTrue(read.ok, "${read.errors.map { "${it.span.line}: ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph)))
    }

    @Test
    fun `an export list naming something undeclared is refused`() {
        val r = VsText(catalog).read(
            """
            graph "probe"

            fn twice(n: INT) -> INT = n * 2

            export { twice, nosuch }
            """.trimIndent(),
        )
        assertTrue(!r.ok)
        assertTrue(r.errors.any { "nosuch" in it.message }, r.errors.joinToString { it.message })
    }

    @Test
    fun `renaming in a local export list is refused, and says where to do it`() {
        val r = VsText(catalog).read(
            """
            graph "probe"

            fn twice(n: INT) -> INT = n * 2

            export { twice as doubled }
            """.trimIndent(),
        )
        assertTrue(!r.ok)
        assertTrue(
            r.errors.any { "on its way out of the document that declares it" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    // ---- it survives the round trips ------------------------------------------------------------------

    @Test
    fun `the three forms round-trip through the printer`() {
        val source = libOf("math" to MATH)
        val vs = VsText(catalog, source)
        // One block, no blank between the lines — the same shape imports print in, because they are the
        // same list. A re-export IS an import edge; only where its names go is different.
        val text = """
            graph "barrel"

            export * from "math"
            export { twice as doubled } from "math"
        """.trimIndent() + "\n"
        val read = vs.read(text)
        assertTrue(read.ok, "${read.errors.map { "${it.span.line}: ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph)))
    }

    @Test
    fun `a re-export survives the document format`() {
        val source = libOf("math" to MATH)
        val read = VsText(catalog, source).read(
            "graph \"barrel\"\n\nexport { twice as doubled } from \"math\"\n",
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        val back = GraphDoc.fromJson(GraphDoc.toJson(graph))
        val imp = back.imports.single()
        assertTrue(imp.isReExport)
        assertEquals("twice", imp.reExported.single().name)
        assertEquals("doubled", imp.reExported.single().local)
    }

    // ---- a RECORD through a barrel -------------------------------------------------------------------

    /**
     * A record re-exported through a barrel is a usable TYPE, not just a name that resolves.
     *
     * A named import spells the type BARE — `fn _listTake(p: Point)` — and the lowering rewrites that to the
     * `@1::Point` its values are stored under. Whether to rewrite was decided by asking the document the
     * import names, and a barrel declares nothing: it only passes `Point` along. So the answer was "no such
     * type here", the parameter stayed spelled `Point`, and every field read off it reported
     * `this has no field 'x'` — on a file that is written correctly, pointing at the wrong thing entirely.
     *
     * Which is the point of the export map: the name a document offers and the document that DECLARES it
     * are different questions, and every lookup that crosses an import has to ask the first one.
     */
    @Test
    fun `a record re-exported through a barrel can be used as a type`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                import { Point } from "barrel"

                fn _listTake(p: Point) -> INT = p.x

                on start {
                    say(message: _listTake(p: Point { x: 3, y: 4 }))
                }
                """.trimIndent(),
                libOf(
                    "geo" to "graph \"geo\"\n\nexport type Point { x: INT, y: INT }\n",
                    "barrel" to "graph \"barrel\"\n\nexport { Point } from \"geo\"\n",
                ),
            ),
        )
    }

    /** ...and its FIELDS keep their types, which is the hop that has to be requalified twice. */
    @Test
    fun `a re-exported record whose field is another record still reads through`() {
        assertEquals(
            listOf(1L),
            said(
                """
                graph "probe"

                import { Leg, Point } from "barrel"

                on start {
                    val l = Leg { from: Point { x: 1, y: 2 }, to: Point { x: 3, y: 4 } }
                    say(message: l.from.x)
                }
                """.trimIndent(),
                libOf(
                    "geo" to """
                        graph "geo"

                        export type Point { x: INT, y: INT }

                        export type Leg { from: Point, to: Point }
                    """.trimIndent(),
                    "barrel" to "graph \"barrel\"\n\nexport { Leg, Point } from \"geo\"\n",
                ),
            ),
        )
    }

    /**
     * Two paths to ONE declaration are two TYPES — a known limitation, pinned so it is a decision.
     *
     * `import { Point } from "geo"` and `import { Point as P2 } from "barrel"` both end at the same
     * `type Point` in `geo`, and the values are stored `@1::Point` and `@2::Point` — a name says which
     * IMPORT it came through, which is what makes two documents' `Phase` tell apart at all. Nothing walks
     * back to the owner to notice the two are the same, so they refuse to wire.
     *
     * Documented in `GAPS.md` rather than fixed: unifying them means comparing by owner everywhere a type
     * name is compared, and the workaround — import a name once — is what anybody writes anyway.
     */
    @Test
    fun `one type reached through two imports is two types`() {
        val r = read(
            """
            graph "probe"

            import { Point } from "geo"
            import { Point as P2 } from "barrel"

            fn _listTake(p: Point) -> INT = p.x

            on start {
                say(message: _listTake(p: P2 { x: 1, y: 2 }))
            }
            """.trimIndent(),
            libOf(
                "geo" to "graph \"geo\"\n\nexport type Point { x: INT, y: INT }\n",
                "barrel" to "graph \"barrel\"\n\nexport { Point } from \"geo\"\n",
            ),
        )
        assertTrue(!r.ok, "if this starts passing, the limitation is gone — delete the gap entry too")
        assertTrue(
            r.errors.any { "cannot wire" in it.message && "Point" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    // ---- what a type carries ACROSS the boundary -----------------------------------------------------

    /**
     * A record field typed `fn(Self)`, invoked by the importer.
     *
     * `qualifyThrough` descended into a LIST and nothing else, so a field typed `fn(HunterRumor)` crossed
     * an import with its PARAMETER still spelled in the declaring document's vocabulary. Invoking it then
     * reported `cannot wire @2::HunterRumor into HunterRumor` — on the only call anybody could have
     * written, since the value being passed is the very record the field hangs off.
     */
    @Test
    fun `a field typed as a function of its own record can be invoked across an import`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                import { Rumor, make } from "lib"

                on start {
                    val r = make(n: 3)
                    r.runnable(r)
                }
                """.trimIndent(),
                libOf(
                    "lib" to """
                        graph "lib"

                        export type Rumor { n: INT, runnable: fn(Rumor) }

                        fn show(r: Rumor) {
                            say(message: r.n)
                        }

                        export fn make(n: INT) -> Rumor = Rumor { n: n, runnable: show }
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** A MAP's KEY crosses too — the same gap, one kind over. */
    @Test
    fun `a map keyed by an imported enum crosses an import`() {
        assertEquals(
            listOf(1L),
            said(
                """
                graph "probe"

                import { Phase, one } from "lib"

                on start {
                    say(message: _mapCount(map: one()))
                }
                """.trimIndent(),
                libOf(
                    "lib" to """
                        graph "lib"

                        export enum Phase { Chop, Bank }

                        export fn one() -> MAP<Phase, INT> = _mapWith(map: _newMap(), key: Phase.Chop, value: 1)
                    """.trimIndent(),
                ),
            ),
        )
    }

    /** ...and an OPTIONAL record keeps its `?`, which the rename used to drop. */
    @Test
    fun `an optional record field keeps its mark across an import`() {
        val r = read(
            """
            graph "probe"

            import { Point, empty } from "lib"

            fn _listTake(p: Point) -> INT = p.n

            on start {
                say(message: _listTake(p: empty().inner))
            }
            """.trimIndent(),
            libOf(
                "lib" to """
                    graph "lib"

                    export type Point { n: INT }

                    export type Box { n: INT, inner: Point? }

                    export fn empty() -> Box = Box { n: 1, inner: null }
                """.trimIndent(),
            ),
        )
        // `inner` is a `Point?`, so feeding it to a parameter that wants a plain `Point` must be refused. If the
        // `?` were dropped on the way across, this would compile and null would reach `p.n`.
        assertTrue(!r.ok, "an optional field must stay optional across an import")
        assertTrue(
            r.errors.any { "cannot wire" in it.message },
            r.errors.joinToString { it.message },
        )
    }

    /**
     * A document may declare `enum Target` beside `fn target`, and both cross intact.
     *
     * The type lookups are case-INSENSITIVE on purpose, so a type position may write `tile` for `Tile`.
     * Deciding what KIND an exported NAME is looked the same and is a different question: the export
     * `target` matched the enum `Target`, minting a phantom enum called `@1::target` — and because the
     * lookup that reads that list is also case-insensitive, `Target.SnowyKnight` came out typed
     * `@1::target` and refused to wire into `@1::Target`. Two declarations differing only in case is an
     * ordinary thing to write; `Target` the vocabulary and `target()` the reader of it is the obvious pair.
     */
    @Test
    fun `an enum and a function differing only in case both cross an import`() {
        assertEquals(
            listOf(150L),
            said(
                """
                graph "probe"

                import { Target, Rumor, target, countOf } from "lib"

                on start {
                    val r = target()
                    say(message: countOf(t: r.target))
                }
                """.trimIndent(),
                libOf(
                    "lib" to """
                        graph "lib"

                        export enum Target(count: INT) { SnowyKnight(150) }

                        export type Rumor { target: Target }

                        export fn target() -> Rumor = Rumor { target: Target.SnowyKnight }

                        export fn countOf(t: Target) -> INT = t.count
                    """.trimIndent(),
                ),
            ),
        )
    }

    // ---- types crossing a barrel --------------------------------------------------------------------
    //
    // **A barrel declares nothing, so it is the wrong document to rename a type from.** `qualifyThrough`
    // renames a type only when the document it is handed actually declares it — so qualifying a forwarded
    // signature through the BARREL leaves its types bare while every other path (a record's fields, a
    // variable, an extension's receiver) qualifies through the owner and renames them. The two spellings
    // then refuse to wire, on code that is perfectly correct.

    private val POINTS = """
        graph "shapes"

        export type Point { x: INT, y: INT }

        export fn at(x: INT) -> Point = Point { x: x, y: 0 }

        export fn xOf(p: Point) -> INT = p.x

        export fn Point.twice(self) -> INT = self.x * 2
    """.trimIndent()

    private val POINT_BARREL = "graph \"barrel\"\n\nexport * from \"shapes\"\n"

    @Test
    fun `a record crossing a barrel wires into a function reached through it`() {
        assertEquals(
            listOf(7L),
            said(
                """
                graph "probe"

                import * as all from "barrel"

                on start {
                    val p = all::at(x: 7)
                    say(message: all::xOf(p: p))
                }
                """.trimIndent(),
                libOf("shapes" to POINTS, "barrel" to POINT_BARREL),
            ),
        )
    }

    @Test
    fun `an extension crossing a barrel keeps its receiver type`() {
        assertEquals(
            listOf(42L),
            said(
                """
                graph "probe"

                import "barrel"

                on start {
                    val p = at(x: 21)
                    say(message: p.twice())
                }
                """.trimIndent(),
                libOf("shapes" to POINTS, "barrel" to POINT_BARREL),
            ),
        )
    }

    /**
     * The shape `core/loadout` actually has: a CONSTRUCTOR on the type — `fn Want.stackAt(…) -> Want`, no
     * `self` — whose result an extension is then called on. `Want.stackAt("Fire rune", 25).must()`.
     *
     * Different resolution path from the plain function above, and worth its own test because that is where
     * it was still failing after the first fix: reported from an IDE as
     * `'must' extends '@1::Want', and this is 'Want'`.
     */
    @Test
    fun `an extension on the result of a type-level constructor crosses a barrel`() {
        assertEquals(
            listOf(42L),
            said(
                """
                graph "probe"

                import "barrel"

                on start {
                    val p = Point.of(x: 21)
                    say(message: p.twice())
                }
                """.trimIndent(),
                libOf(
                    "shapes" to """
                        graph "shapes"

                        export type Point { x: INT, y: INT }

                        export fn Point.of(x: INT) -> Point = Point { x: x, y: 0 }

                        export fn Point.twice(self) -> INT = self.x * 2
                    """.trimIndent(),
                    "barrel" to POINT_BARREL,
                ),
            ),
        )
    }

    /**
     * A barrel forwarding TWO documents, where one declares an extension on the other's type.
     *
     * **This is the shape a package barrel actually has**, and it is the one that was still broken after
     * receivers learned to cross a barrel. `core/loadout` forwards `wants` (which declares `Loadout`) and
     * `tools` (which imports `wants` and declares `fn Loadout.topUp`). An importer of the barrel builds a
     * `Loadout` through the barrel and calls `.topUp()` on it — and got
     * `'topUp' extends '@1::Loadout', and this is '@4::Loadout'`.
     *
     * One document, two aliases: the receiver was still spelled in the OWNER's alias namespace — `tools`'
     * word for `wants` — while the value was spelled in the IMPORTER's. `qualifyThrough` cannot fix that:
     * it renames what the owner DECLARES, and `tools` does not declare `Loadout`, it imports it.
     */
    @Test
    fun `an extension on a sibling's type crosses a barrel`() {
        assertEquals(
            listOf(42L),
            said(
                """
                graph "probe"

                import "barrel"

                on start {
                    val p = at(x: 21)
                    say(message: p.twice())
                }
                """.trimIndent(),
                libOf(
                    "shapes" to """
                        graph "shapes"

                        export type Point { x: INT, y: INT }

                        export fn at(x: INT) -> Point = Point { x: x, y: 0 }
                    """.trimIndent(),
                    "verbs" to """
                        graph "verbs"

                        import "shapes"

                        export fn Point.twice(self) -> INT = self.x * 2
                    """.trimIndent(),
                    "barrel" to "graph \"barrel\"\n\nexport * from \"shapes\"\nexport * from \"verbs\"\n",
                ),
            ),
        )
    }

    /** The same crossing, as an ordinary function parameter rather than a receiver. */
    @Test
    fun `a sibling's type crosses a barrel as a parameter`() {
        assertEquals(
            listOf(21L),
            said(
                """
                graph "probe"

                import "barrel"

                on start {
                    say(message: xOf(p: at(x: 21)))
                }
                """.trimIndent(),
                libOf(
                    "shapes" to """
                        graph "shapes"

                        export type Point { x: INT, y: INT }

                        export fn at(x: INT) -> Point = Point { x: x, y: 0 }
                    """.trimIndent(),
                    "verbs" to """
                        graph "verbs"

                        import "shapes"

                        export fn xOf(p: Point) -> INT = p.x
                    """.trimIndent(),
                    "barrel" to "graph \"barrel\"\n\nexport * from \"shapes\"\nexport * from \"verbs\"\n",
                ),
            ),
        )
    }

    /**
     * The importer declares its OWN function returning the barrel's type, then calls a sibling's extension
     * on the result — which is exactly how `tithe.vs` uses `core/loadout`.
     *
     * Different path from the test above: there the value came straight out of a forwarded function, so
     * both halves were spelled by the same machinery. Here the return type is written in the IMPORTER's
     * source and resolved at its declaration, while the receiver is still spelled by the owner.
     */
    @Test
    fun `a local function returning a barrelled type accepts a sibling's extension`() {
        assertEquals(
            listOf(42L),
            said(
                """
                graph "probe"

                import "barrel"

                fn mine() -> Point = at(x: 21)

                on start {
                    say(message: mine().twice())
                }
                """.trimIndent(),
                libOf(
                    "shapes" to """
                        graph "shapes"

                        export type Point { x: INT, y: INT }

                        export fn at(x: INT) -> Point = Point { x: x, y: 0 }
                    """.trimIndent(),
                    "verbs" to """
                        graph "verbs"

                        import "shapes"

                        export fn Point.twice(self) -> INT = self.x * 2
                    """.trimIndent(),
                    "barrel" to "graph \"barrel\"\n\nexport * from \"shapes\"\nexport * from \"verbs\"\n",
                ),
            ),
        )
    }

    /**
     * The same crossing, with the importer's ALIAS NUMBERING deliberately different from the owner's.
     *
     * **This is the one that actually reproduces it, and the three tests above pass by coincidence.** An
     * unqualified bare import gets a synthesised alias — `@1`, `@2`, … — numbered per document. When the
     * owner and the importer each have exactly one import, both call the shared document `@1` and a
     * receiver spelled in the OWNER's namespace happens to read correctly in the importer's. Give the
     * importer three earlier imports and its barrel becomes `@4`, while the owner still says `@1`, and the
     * mismatch surfaces:
     *
     * ```
     * 'twice' extends '@1::Point', and this is '@4::Point'
     * ```
     *
     * Reported from a real tree as `'topUp' extends '@1::Loadout', and this is '@4::Loadout'` — `tithe.vs`
     * imports `core/wait`, `core/activity` and `core/interaction` before `core/loadout`.
     */
    @Test
    fun `a sibling's extension crosses a barrel when the aliases are numbered differently`() {
        assertEquals(
            listOf(42L),
            said(
                """
                graph "probe"

                import "filler1"
                import "filler2"
                import "filler3"
                import "barrel"

                fn mine() -> Point = at(x: 21)

                on start {
                    say(message: mine().twice())
                }
                """.trimIndent(),
                libOf(
                    "filler1" to "graph \"filler1\"\n\nexport fn f1() -> INT = 1\n",
                    "filler2" to "graph \"filler2\"\n\nexport fn f2() -> INT = 2\n",
                    "filler3" to "graph \"filler3\"\n\nexport fn f3() -> INT = 3\n",
                    "shapes" to """
                        graph "shapes"

                        export type Point { x: INT, y: INT }

                        export fn at(x: INT) -> Point = Point { x: x, y: 0 }
                    """.trimIndent(),
                    "verbs" to """
                        graph "verbs"

                        import "shapes"

                        export fn Point.twice(self) -> INT = self.x * 2
                    """.trimIndent(),
                    "barrel" to "graph \"barrel\"\n\nexport * from \"shapes\"\nexport * from \"verbs\"\n",
                ),
            ),
        )
    }
}
