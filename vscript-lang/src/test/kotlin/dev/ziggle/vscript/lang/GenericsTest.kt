package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.resolveNode
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Generics, phase E — a parameterized receiver (Level 0) and one type variable bound by it (Level 1).
 *
 * **The best single argument for the feature is a comment in the corpus**, which phase E deletes:
 *
 * ```vs
 * // Used because we don't have a way to cast anys to a specific type right now
 * fn distance(entity: Entity) -> Int = entityInfo(entity).distance
 * ```
 *
 * `core/objects.vs` is written over `List` and `Any` throughout, not because anything there is generic but
 * because the language had no way to say `List<Entity>` — so `closest` took `Any`, handed it to a helper
 * that re-declared the type to get its fields back, and every wire in between was unchecked.
 *
 * Two halves, and they are genuinely different features:
 *
 * - **Level 0 needs nothing from the type system.** `TypeRef.list(TypeRef(ENTITY))` was expressible before
 *   any of this; what was missing was a parser production and a resolution rule that reads the receiver.
 * - **Level 1 adds one variable**, bound at one site, substituted through the rest of the signature. No
 *   unification, no inference pass, and nothing at run time — the VM never learns that generics exist.
 */
class GenericsTest {

    /**
     * `Npc` — registered, because it is a HOST type now rather than a builtin.
     *
     * It used to be `PinType.NPC`, so the checker knew it without being told. The node pack declares it
     * these days, and an unregistered name is a type the checker has never heard of — which made two
     * receiver-mismatch tests pass for the wrong reason, by being lenient about a name nothing declared
     * rather than by refusing the wire.
     */
    @kotlin.test.BeforeTest
    fun registerNpc() {
        dev.ziggle.vscript.model.HostRecords.register(
            dev.ziggle.vscript.model.HostRecord("Npc", emptyList(), "one kind of NPC"),
        )
    }

    @kotlin.test.AfterTest
    fun forgetNpc() = dev.ziggle.vscript.model.HostRecords.reset()

    /** Two entity-shaped queries, so a `LIST<Npc>` can be produced without the game catalogue. */
    private val entitiesNode = hostNode(
        "test.entities", "test.entities", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", TypeRef.list(TypeRef.named("Npc")))),
    )
    /**
     * A `LIST<STRING>` source.
     *
     * The two receiver-specificity tests below used `LIST<Npc>`, which stopped working when `Npc` became
     * a HOST type: `Lower` — the text-to-graph front end, which is a test fixture now rather than
     * something shipped — resolves type names against the builtins and the document's own declarations,
     * and has never consulted the host-record registry. So a host type is invisible to it and the
     * receiver simply did not match.
     *
     * The property under test is "the most specific receiver wins", which has nothing to do with items or
     * NPCs. Stated with a builtin element type it says the same thing and does not depend on a gap in a
     * harness for a path the product no longer has.
     */
    private val namesNode = hostNode(
        "test.names", "test.names", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", TypeRef.list(TypeRef(PinType.STRING)))),
    )
    private val tilesNode = hostNode(
        "test.tiles", "test.tiles", NodeKind.PURE,
        outputs = listOf(PinSpec("Value", TypeRef.list(TypeRef(PinType.BOOL)))),
    )
    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(listOf(entitiesNode, namesNode, tilesNode, sayNode))
    private val text = VsText(catalog)

    // ---- harness -------------------------------------------------------------------------------------

    private fun graphOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { "${it.span} ${it.message}" }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(
            emptyList(),
            Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message },
            "did not validate",
        )
        return low.graph
    }

    /** Every ERROR a document produces, at whichever stage produced it. */
    private fun errors(src: String, source: GraphSource? = null): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { it.message }
        val low = Lower(catalog, source = source ?: GraphSource { null }).lower(parsed.program)
        if (low.errors.isNotEmpty()) return low.errors.map { it.message }
        return Validator(catalog, source ?: GraphSource { null })
            .validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message }
    }

    private fun warnings(src: String): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        return Validator(catalog).validate(low.graph).filter { it.severity == Severity.WARNING }.map { it.message }
    }

    private fun roundTrip(src: String) {
        assertEquals(src.trim(), text.write(graphOf(src)).trim(), "round trip")
    }

    private fun said(src: String): List<Any?> {
        val g = graphOf(src)
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        hosts.register("test.entities", HostKind.INLINE, arity = 0, results = 1) { mutableListOf<Any?>("a", "b") }
        hosts.register("test.tiles", HostKind.INLINE, arity = 0, results = 1) { mutableListOf<Any?>("t") }
        hosts.register("test.names", HostKind.INLINE, arity = 0, results = 1) { mutableListOf<Any?>("n") }
        val r = drive(
            GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 600,
        )
        assertNull(r.fiber.error, "vm error")
        return out.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    /**
     * The declared type of one pin on the single Call node in a document.
     *
     * Resolved the way every consumer resolves it — through `resolveNode` with a `feeding` probe — because
     * that IS the substitution. Reading `graph.function(…)` instead would only ever see the declaration,
     * which is exactly the thing phase E leaves alone.
     */
    private fun callPin(src: String, pin: String, output: Boolean = true): TypeRef {
        val g = graphOf(src)
        val call = g.nodes.single { it.type == BuiltinNodes.CALL }
        fun descOf(n: dev.ziggle.vscript.model.Node) =
            resolveNode(n, catalog[n.type]!!, g::function, { g.types }, { _, _ -> true }, { g.enums })
        val d = resolveNode(
            call, catalog[BuiltinNodes.CALL]!!, g::function, { g.types }, { _, _ -> true }, { g.enums },
        ) { n, p ->
            // The literal fallback matters here as much as the wire: a generic parameter is very often
            // given a value rather than a wire, and that value is the only constraint on the variable.
            g.linkInto(n, p)?.let { l -> g.node(l.fromNode)?.let { descOf(it).output(l.fromPin)?.type } }
                ?: dev.ziggle.vscript.model.literalTypeOf(g.node(n)?.literals?.get(p))
        }
        return assertNotNull(if (output) d.output(pin) else d.input(pin), "no pin '$pin'").type
    }

    // ---- Level 0: the receiver is a type ---------------------------------------------------------------

    @Test
    fun `a parameterized receiver parses, lowers and prints back`() {
        roundTrip(
            """
            graph "t"

            export fn LIST<Npc>.closest(self) -> Npc? = self[0]

            on start {
                say(message: entities().closest())
            }
            """.trimIndent(),
        )
    }

    /**
     * **The one that was impossible before**, and the reason the phase exists.
     *
     * Two functions, one name, told apart by what they are called on. Under the old rule — `firstOrNull`
     * over the name — the second declaration was simply unreachable, and the call was reported as an
     * ambiguity with no way out.
     *
     * Two documents rather than one, because that is the limit: see [two extensions of one name in ONE
     * document are still refused].
     */
    @Test
    fun `two extensions of one name are told apart by the receiver`() {
        val ent = docOf("""graph "ent"${'\n'}${'\n'}export fn LIST<Npc>.kind(self) -> STRING = "entity"""")
        val til = docOf("""graph "til"${'\n'}${'\n'}export fn LIST<BOOL>.kind(self) -> STRING = "tile"""")
        assertEquals(
            listOf("entity", "tile"),
            saidWith(
                """
                graph "t"

                import { kind } from "ent"
                import { kind } from "til"

                on start {
                    say(message: names().kind())
                    say(message: tiles().kind())
                }
                """.trimIndent(),
                GraphSource.of(listOf(ent, til)),
            ),
        )
    }

    @Test
    fun `the most specific receiver wins over an unconstrained one`() {
        val one = docOf("""graph "one"${'\n'}${'\n'}export fn LIST<STRING>.kind(self) -> STRING = "entities"""")
        val any = docOf("""graph "any"${'\n'}${'\n'}export fn LIST.kind(self) -> STRING = "anything"""")
        assertEquals(
            listOf("entities", "anything"),
            saidWith(
                """
                graph "t"

                import { kind } from "one"
                import { kind } from "any"

                on start {
                    say(message: names().kind())
                    say(message: tiles().kind())
                }
                """.trimIndent(),
                GraphSource.of(listOf(one, any)),
            ),
        )
    }

    /**
     * The limit, stated: overloading is across documents, never inside one.
     *
     * A Call names its callee by NAME and a body is the nodes naming it, so two `kind`s in one document
     * are two things one name would have to mean. That is a property of the graph model rather than of the
     * resolution rule, and phase E deliberately changes no part of the model.
     */
    @Test
    fun `two extensions of one name in ONE document are still refused`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<Npc>.kind(self) -> STRING = "entity"
            export fn LIST<BOOL>.kind(self) -> STRING = "tile"

            on start {
                say(message: entities().kind())
            }
            """.trimIndent(),
        )
        assertTrue(e.any { "both called 'kind'" in it && "Two documents may each extend" in it }, "$e")
    }

    @Test
    fun `a receiver nothing extends is refused, and the message names what is on offer`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<STRING>.closest(self) -> STRING? = self[0]

            on start {
                say(message: tiles().closest())
            }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("'LIST<STRING>'" in e[0] && "LIST<BOOL>" in e[0], e[0])
    }

    /**
     * The latent collision Level 0 fixes.
     *
     * `List.plus` is declared in BOTH `core/list.vs` (`-> List<Any>`) and `core/objects.vs`
     * (`-> List<Entity>`) in the client's corpus. Nothing imports both today, so it never fired — but the
     * old rule matched on name alone, so a document that imported both got "'plus' is extended by 'list'
     * and 'objects'" and had no way out. With the receivers written down there is no ambiguity left to
     * report.
     */
    @Test
    fun `two documents may extend the same name on different types`() {
        val ints = docOf(
            """
            graph "ints"

            export fn LIST<INT>.plus(self, n: INT) -> LIST<INT> = _listWithItemAdded(list: self, value: n)
            """.trimIndent(),
        )
        val ents = docOf(
            """
            graph "ents"

            export fn LIST<Npc>.plus(self, e: Npc) -> LIST<Npc> = _listWithItemAdded(list: self, value: e)
            """.trimIndent(),
        )
        val source = GraphSource.of(listOf(ints, ents))
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                import { plus } from "ints"
                import { plus } from "ents"

                on start {
                    say(message: _listCount(list: entities().plus(e: entities()[0])))
                }
                """.trimIndent(),
                source,
            ),
        )
    }

    @Test
    fun `an ambiguity between two imports is still refused when the receivers agree`() {
        val a = docOf("""graph "a"${'\n'}${'\n'}export fn LIST<INT>.f(self) -> INT = _listCount(list: self)""")
        val b = docOf("""graph "b"${'\n'}${'\n'}export fn LIST<INT>.f(self) -> INT = 0""")
        val e = errors(
            """
            graph "t"

            import * as a from "a"
            import { f } from "a"
            import * as b from "b"
            import { f } from "b"

            on start {
                say(message: [1, 2].f())
            }
            """.trimIndent(),
            GraphSource.of(listOf(a, b)),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("extended by" in e[0] && "'a'" in e[0] && "'b'" in e[0], e[0])
    }

    @Test
    fun `a local declaration still wins over an import at the same specificity`() {
        val lib = docOf("""graph "lib"${'\n'}${'\n'}export fn LIST<INT>.f(self) -> STRING = "library"""")
        assertEquals(
            listOf("mine"),
            saidWith(
                """
                graph "t"

                import { f } from "lib"

                export fn LIST<INT>.f(self) -> STRING = "mine"

                on start {
                    say(message: [1, 2].f())
                }
                """.trimIndent(),
                GraphSource.of(listOf(lib)),
            ),
        )
    }

    @Test
    fun `a receiver may not be optional`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<Npc>?.closest(self) -> Npc? = self[0]

            on start { }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("never optional" in e[0], e[0])
    }

    // ---- Level 1: one variable, bound by the receiver --------------------------------------------------

    @Test
    fun `a type variable parses, lowers and prints back`() {
        roundTrip(
            """
            graph "t"

            export fn LIST<T>.head(self) -> T? = self[0]

            on start {
                say(message: entities().head())
            }
            """.trimIndent(),
        )
    }

    /** **The whole of Level 1**: the result pin is the receiver's element type, not `T`. */
    @Test
    fun `the result is substituted from what is wired into self`() {
        assertEquals(
            TypeRef.parse("Npc?"),
            callPin(
                """
                graph "t"

                export fn LIST<T>.head(self) -> T? = self[0]

                on start {
                    say(message: entities().head())
                }
                """.trimIndent(),
                Parser.RESULT_PIN,
            ),
        )
    }

    @Test
    fun `the same declaration substitutes differently at a different call site`() {
        assertEquals(
            TypeRef.parse("BOOL?"),
            callPin(
                """
                graph "t"

                export fn LIST<T>.head(self) -> T? = self[0]

                on start {
                    say(message: tiles().head())
                }
                """.trimIndent(),
                Parser.RESULT_PIN,
            ),
        )
    }

    /** A parameter typed `T` binds too, which is what makes `_listIndexOf(value: T)` check its argument. */
    @Test
    fun `a parameter typed as the variable is substituted as well`() {
        assertEquals(
            TypeRef.named("Npc"),
            callPin(
                """
                graph "t"

                export fn LIST<T>.has(self, value: T) -> BOOL = _listContains(list: self, value: value)

                on start {
                    say(message: entities().has(value: entities()[0]))
                }
                """.trimIndent(),
                "value",
                output = false,
            ),
        )
    }

    /**
     * The reason the substitution is worth having: the wrong argument is now a compile error.
     *
     * The argument is a whole `LIST<BOOL>` rather than one element out of it, and that is not laziness —
     * `xs[i]` hands back a WILDCARD today (`list.at` is declared that way and nothing narrows it), so an
     * element would have proved nothing about the binding. Element-type propagation through `[i]` is the
     * obvious next narrowing and is deliberately not in this phase; see `docs/LANGUAGE.md` §13.
     */
    @Test
    fun `an argument of the wrong type is refused once the variable is bound`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<T>.has(self, value: T) -> BOOL = _listContains(list: self, value: value)

            on start {
                say(message: entities().has(value: tiles()))
            }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("LIST<BOOL>" in e[0] && "Npc" in e[0], e[0])
    }

    /**
     * Inside the body there is nothing that could say what `T` is, so it accepts — exactly as a wildcard
     * does. A rule that refused here would make every generic function unwritable.
     */
    @Test
    fun `an unbound variable behaves as a wildcard inside the function's own body`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export fn LIST<T>.second(self) -> T? {
                    val v = self[1]
                    return v
                }

                on start {
                    say(message: entities().second())
                }
                """.trimIndent(),
            ),
        )
    }

    /** Erased: the VM never learns generics exist, so a generic call runs like any other. */
    @Test
    fun `a generic call runs`() {
        assertEquals(
            listOf("a", "t"),
            said(
                """
                graph "t"

                export fn LIST<T>.head(self) -> T? = self[0]

                on start {
                    say(message: entities().head())
                    say(message: tiles().head())
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * `T?` substituted with `Npc` is `Npc?`, so `if val` narrows a generic result the ordinary way.
     * That is the composition worth checking: phase D's narrowing has no idea phase E exists.
     */
    @Test
    fun `a substituted optional narrows through if val`() {
        assertEquals(
            listOf("a"),
            said(
                """
                graph "t"

                export fn LIST<T>.head(self) -> T? = self[0]

                on start {
                    if val e = entities().head() {
                        say(message: e)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the binding site, and what it costs -----------------------------------------------------------

    /**
     * A receiver argument that names a DECLARED type is that type, not a variable.
     *
     * The rule that keeps `typeParametersOf` safe: without it a record called `Target` in the receiver
     * would quietly become a variable accepting anything, and the signature would stop meaning what it
     * said — the same silent widening `TypeRef.named` exists to prevent.
     */
    @Test
    fun `a receiver argument naming a declared record is not a variable`() {
        val e = errors(
            """
            graph "t"

            export type Spot { x: INT }

            export fn LIST<Spot>.f(self) -> INT = _listCount(list: self)

            on start {
                say(message: tiles().f())
            }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("LIST<Spot>" in e[0], e[0])
    }

    /**
     * The one check that catches a mistyped receiver argument.
     *
     * A binding site cannot be typo-checked — `fn LIST<Etity>.f(self)` is a generic function over `Etity`
     * for exactly the reason `fn f(etity: INT)` is a parameter called `etity`. What CAN be checked is that
     * the name introduced is then used, which a typo never is.
     */
    @Test
    fun `a type variable nothing uses is reported`() {
        val w = warnings(
            """
            graph "t"

            export fn LIST<Etity>.f(self) -> INT = _listCount(list: self)

            on start {
                say(message: entities().f())
            }
            """.trimIndent(),
        )
        assertEquals(1, w.size, "$w")
        assertTrue("'Etity'" in w[0], w[0])
    }

    @Test
    fun `a variable the signature does use is not reported`() {
        assertEquals(
            emptyList(),
            warnings(
                """
                graph "t"

                export fn LIST<T>.head(self) -> T? = self[0]

                on start {
                    say(message: entities().head())
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The marking survives JSON, which it has to: the canvas loads a document rather than parsing one, and
     * a signature whose variables came back as phantom records would report "typed 'T', which this graph
     * does not declare" on a file that had just been saved.
     */
    @Test
    fun `type variables are re-derived when a document is read back`() {
        val g = graphOf(
            """
            graph "t"

            export fn LIST<T>.head(self) -> T? = self[0]

            on start {
                say(message: entities().head())
            }
            """.trimIndent(),
        )
        val back = GraphDoc.fromJson(GraphDoc.toJson(g))
        val fn = assertNotNull(back.function("head"))
        assertTrue(assertNotNull(fn.receiver).of!!.variable, "the receiver's argument lost its marking")
        assertTrue(fn.results.single().type.variable, "the result lost its marking")
        assertEquals("LIST<T>", fn.receiver.toString(), "the stored spelling is the one that was written")
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private fun docOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        return low.graph
    }

    private fun saidWith(src: String, source: GraphSource): List<Any?> {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog, source = source).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(
            emptyList(),
            Validator(catalog, source).validate(low.graph).filter { it.severity == Severity.ERROR }
                .map { it.message },
            "did not validate",
        )
        val g = low.graph
        val out = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        hosts.register("test.entities", HostKind.INLINE, arity = 0, results = 1) { mutableListOf<Any?>("a") }
        hosts.register("test.tiles", HostKind.INLINE, arity = 0, results = 1) { mutableListOf<Any?>("t") }
        hosts.register("test.names", HostKind.INLINE, arity = 0, results = 1) { mutableListOf<Any?>("n") }
        val r = drive(
            GraphCompiler(catalog, debug = false, source = source).compile(g, g.entries(catalog).single().id),
            hosts, maxTicks = 600,
        )
        assertNull(r.fiber.error, "vm error")
        return out
    }

    // ---- the empty-list-literal limit, which turned out to be already gone ------------------------------

    /**
     * `var xs: LIST<Npc> = []` inside a body.
     *
     * **`docs/LANGUAGE.md` listed this as a limit and it had already been fixed**, silently, by phase D:
     * `HOLD_TYPE` used to be read with `TypeRef.named`, which takes the whole string `LIST<Npc>` for the
     * name of a type nobody declares, so `retypeList` asked "is the destination a list?" and was told no.
     * Reading it with `parse` — done for optionals, not for this — made the destination a real list, and
     * the element type has flowed into the literal ever since.
     *
     * Pinned here rather than left to be rediscovered. It is `retypeList`'s rule ("the destination decides
     * what a `[]` holds") reaching one more destination, and phase E was scheduled to implement it.
     */
    @Test
    fun `an empty list literal takes its element type from the local's declared type`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                on start {
                    var xs: LIST<Npc> = []
                    xs = _listWithItemAdded(list: xs, value: entities()[0])
                    say(message: _listCount(list: xs))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * And the element type is really ON the literal, not merely tolerated by a wildcard on the way past.
     *
     * The difference is invisible from the outside and is the whole of it: a `[]` that stayed
     * `LIST<WILDCARD>` connects to everything, so the version of this that only checked for the absence of
     * an error would pass either way.
     */
    @Test
    fun `the declared element type is written onto the literal itself`() {
        val g = graphOf(
            """
            graph "t"

            on start {
                var xs: LIST<BOOL> = []
                say(message: _listCount(list: xs))
            }
            """.trimIndent(),
        )
        val list = g.nodes.single { it.type == BuiltinNodes.LITERAL_LIST }
        assertEquals("Bool", list.literals[BuiltinNodes.LIST_OF], "the literal kept its guessed element type")
    }

    /**
     * The counterpart, and it is why the check consults the CONVENTION: a receiver may be generic while
     * the answer is not.
     *
     * `core/list.vs` ships exactly this — `fn List<T>.isEmpty(self) -> Bool` mentions `T` nowhere but the
     * receiver and is perfectly correct. Warning on it is noise on shipped code, which is how a warning
     * stops being read.
     */
    @Test
    fun `a conventionally named variable is never reported, used or not`() {
        assertEquals(
            emptyList(),
            warnings(
                """
                graph "t"

                export fn LIST<T>.isEmpty2(self) -> BOOL = _listIsEmpty(list: self)

                on start {
                    say(message: entities().isEmpty2())
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- phase F: inference across arguments ------------------------------------------------------------

    /**
     * Level 1 asked the receiver and nothing else. Phase F asks every parameter, which is the difference
     * between substitution from one binding site and inference.
     */
    @Test
    fun `a variable binds from an argument when the receiver does not say`() {
        assertEquals(
            TypeRef(PinType.INT),
            callPin(
                """
                graph "t"

                export fn LIST<T>.pick(self, a: T) -> T = a

                on start {
                    say(message: [].pick(a: 3))
                }
                """.trimIndent(),
                Parser.RESULT_PIN,
            ),
        )
    }

    /**
     * **The case that makes first-sight binding wrong**, and the reason `join` exists.
     *
     * vs has no subtyping, which normally makes unification trivial — but `INT` widens to `FLOAT`, so a
     * left-to-right binder commits `T = INT` on the first argument and then refuses the second, which is a
     * correct call. The answer is the type that accepts both.
     */
    @Test
    fun `two arguments for one variable resolve to the type that accepts both`() {
        assertEquals(
            TypeRef(PinType.FLOAT),
            callPin(
                """
                graph "t"

                export fn LIST<T>.pick(self, a: T, b: T) -> T = a

                on start {
                    say(message: [].pick(a: 3, b: 2.5))
                }
                """.trimIndent(),
                Parser.RESULT_PIN,
            ),
        )
    }

    /** And the same in the other order, which is what a left-to-right binder would get right by luck. */
    @Test
    fun `the widening join does not depend on the order the arguments were written`() {
        assertEquals(
            TypeRef(PinType.FLOAT),
            callPin(
                """
                graph "t"

                export fn LIST<T>.pick(self, a: T, b: T) -> T = a

                on start {
                    say(message: [].pick(a: 2.5, b: 3))
                }
                """.trimIndent(),
                Parser.RESULT_PIN,
            ),
        )
    }

    /**
     * Incompatible constraints keep the FIRST, so what gets reported is the offending wire rather than a
     * failure to infer. `[].pick(a: 3, b: "x")` should complain about `"x"`, at the argument the author
     * wrote — not about `T`, which the author never wrote.
     */
    @Test
    fun `an argument that contradicts the binding is reported at that argument`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<T>.pick(self, a: T, b: T) -> T = a

            on start {
                say(message: [].pick(a: 3, b: "x"))
            }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("INT" in e[0] && "String" in e[0], e[0])
    }

    /** The receiver still wins where it says anything — it is where the variables are declared. */
    @Test
    fun `the receiver's binding beats an argument's`() {
        val e = errors(
            """
            graph "t"

            export fn LIST<T>.has(self, value: T) -> BOOL = _listContains(list: self, value: value)

            on start {
                say(message: entities().has(value: tiles()))
            }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("LIST<BOOL>" in e[0] && "Npc" in e[0], e[0])
    }

    /** Inside the body a variable is still unbound, whatever the parameters are — nothing there can say. */
    @Test
    fun `binding from arguments does not reach inside the body`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export fn LIST<T>.pick(self, a: T, b: T) -> T {
                    val x = a
                    return x
                }

                on start {
                    say(message: [].pick(a: 3, b: 2.5))
                }
                """.trimIndent(),
            ),
        )
    }
}
