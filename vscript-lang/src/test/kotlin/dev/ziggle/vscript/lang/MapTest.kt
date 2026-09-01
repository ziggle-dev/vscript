package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.countOp
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.resolveNode
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.Chunk
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.Op
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MAP<K, V>` — phase F's first piece.
 *
 * **A map is a VALUE**, like a list and like a record: `withEntry` hands back a copy and nothing writes
 * through one another wire is holding. The alternative — a reference map — would make aliasing observable
 * for the first time in this language, and there would be no rule anywhere else to reason about it by.
 *
 * The obvious objection to a value-map is that it copies on every write, which is the O(n²) trap the
 * accumulator loop already demonstrated for lists. The answer is the one lists got in phase B, and it was
 * built then on the explicit argument that maps would inherit it: `AppendPass` proves nobody else holds
 * the old map and the compiler emits a single in-place `Op.SETKEY`. So the tests here come in the same two
 * halves `AppendPassTest` uses — what the script computes, checked against copy semantics, and what was
 * emitted, which is the only way to notice an optimisation that stopped happening.
 */
class MapTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    /** Keeps the reference the way a game verb would — the pass has no way to know it does not. */
    private val keepNode = hostNode(
        "test.keep", "test.keep", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Map", PinType.MAP)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(listOf(sayNode, keepNode) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)
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

    private fun errors(src: String): List<String> {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { it.message }
        val low = Lower(catalog).lower(parsed.program)
        if (low.errors.isNotEmpty()) return low.errors.map { it.message }
        return Validator(catalog).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message }
    }

    private fun compile(src: String): Chunk {
        val g = graphOf(src)
        return GraphCompiler(catalog, debug = false).compile(g, g.entries(catalog).single().id)
    }

    private fun run(chunk: Chunk): List<Any?> {
        val said = ArrayList<Any?>()
        val hosts = BuiltinHosts.registry()
        hosts.register("test.say", HostKind.INLINE, arity = 1) { a -> said += a[0]; null }
        val kept = ArrayList<Any?>()
        hosts.register("test.keep", HostKind.INLINE, arity = 1) { a -> kept += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 4000)
        assertNull(r.fiber.error, "vm error")
        assertTrue(r.fiber.isFinished, "did not finish")
        return said.map { if (it is Number && it !is Double) it.toLong() else it }
    }

    private fun said(src: String): List<Any?> = run(compile(src))

    private fun Chunk.everywhere(): List<Chunk> = listOf(this) + program.toList()

    // `this@everywhere` rather than a bare `it`: `Chunk` has a `program` of its own, so an unqualified
    // receiver inside these sums resolves ambiguously.
    private fun Chunk.setKeys(): Int = everywhere().sumOf { it.countOp(Op.SETKEY) }
    private fun Chunk.copies(): Int = everywhere().sumOf { c ->
        val i = c.hostNames.indexOf("vscript.mapWith")
        if (i < 0) 0 else c.countOp(Op.CALL, a = i)
    }

    // ---- the type ------------------------------------------------------------------------------------

    /**
     * `MAP<STRING, INT>` parses at all — which it did not before phase F.
     *
     * `TypeRef` had held several arguments since phase D; the AST's `TypeExpr` still held ONE, so the
     * second argument had nowhere to go and the parser stopped at the comma.
     */
    @Test
    fun `a two-argument type parses, lowers and prints back`() {
        roundTrip(
            """
            graph "t"

            export var Seen: MAP<STRING, INT> = _newMap()

            export fn _listCount(m: MAP<STRING, INT>) -> INT = _mapCount(map: m)

            on start {
                say(message: _listCount(m: Seen))
            }
            """.trimIndent(),
        )
    }

    // ---- `for (k, v) in m` — GAPS #10 -----------------------------------------------------------------
    //
    // A node of its own rather than sugar over `keysOf` + `valueAt`: that pairing looks each key up twice,
    // once to hand it out and once to find its value, and a map lookup here is a scan. It compiles to the
    // list iterator that already exists, over the entry pairs, so it costs one host and no opcode.

    @Test
    fun `a map loop binds key and value`() {
        assertEquals(
            listOf("a", 1L, "b", 2L),
            said(
                """
                graph "probe"
                on start {
                    val m = _mapWith(map: _mapWith(map: _newMap(), key: "a", value: 1), key: "b", value: 2)
                    for (k, v) in m {
                        say(k)
                        say(v)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** Insertion order, which is what `LinkedHashMap` is for and what makes two runs agree. */
    @Test
    fun `entries come in the order they were added`() {
        assertEquals(
            listOf("z", "a", "m"),
            said(
                """
                graph "probe"
                on start {
                    var m = _newMap()
                    m = _mapWith(map: m, key: "z", value: 1)
                    m = _mapWith(map: m, key: "a", value: 2)
                    m = _mapWith(map: m, key: "m", value: 3)
                    for (k, v) in m {
                        say(k)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an empty map runs the body not at all`() {
        assertEquals(
            listOf("done"),
            said(
                """
                graph "probe"
                on start {
                    for (k, v) in _newMap() {
                        say(k)
                    }
                    say("done")
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `break and continue work in a map loop`() {
        assertEquals(
            listOf("a", "c"),
            said(
                """
                graph "probe"
                on start {
                    var m = _newMap()
                    m = _mapWith(map: m, key: "a", value: 1)
                    m = _mapWith(map: m, key: "b", value: 2)
                    m = _mapWith(map: m, key: "c", value: 3)
                    m = _mapWith(map: m, key: "d", value: 4)
                    for (k, v) in m {
                        if v == 2 {
                            continue
                        }
                        if v == 4 {
                            break
                        }
                        say(k)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** The map's own argument types reach the loop, so a record value can have its fields read. */
    @Test
    fun `key and value carry the map's declared types`() {
        assertEquals(
            listOf(7L),
            said(
                """
                graph "probe"
                export type Spot { x: INT }
                export var M: MAP<STRING, Spot> = _newMap()
                on start {
                    M = _mapWith(map: M, key: "home", value: Spot { x: 7 })
                    for (k, v) in M {
                        say(v.x)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a map loop round-trips`() {
        roundTrip(
            """
            graph "probe"

            export var M: MAP<STRING, INT> = _newMap()

            on start {
                for (k, v) in M {
                    say(message: k)
                    say(message: v)
                }
            }
            """.trimIndent(),
        )
    }

    /** A LIST with two names is still element-and-index — the container decides, and nothing changed. */
    @Test
    fun `a list with two names is still element and index`() {
        assertEquals(
            listOf("a", 0L, "b", 1L),
            said(
                """
                graph "probe"
                on start {
                    for (x, i) in ["a", "b"] {
                        say(x)
                        say(i)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    private fun roundTrip(src: String) {
        assertEquals(src.trim(), text.write(graphOf(src)).trim(), "round trip")
    }

    @Test
    fun `type arguments nest and survive the round trip`() {
        assertEquals("MAP<STRING, LIST<INT>>", TypeRef.parse("MAP<STRING, LIST<INT>>").toString())
        assertEquals("MAP<MAP<INT, INT>, STRING>", TypeRef.parse("MAP<MAP<INT, INT>, STRING>").toString())
        assertEquals(TypeRef(PinType.STRING), TypeRef.parse("MAP<STRING, INT>").keyOf)
        assertEquals(TypeRef(PinType.INT), TypeRef.parse("MAP<STRING, INT>").valueOf)
    }

    @Test
    fun `a map of the wrong shape does not wire`() {
        val e = errors(
            """
            graph "t"

            export var Seen: MAP<STRING, INT> = _newMap()
            export var Other: MAP<STRING, STRING> = _newMap()

            on start {
                Other = Seen
            }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("MAP<STRING, INT>" in e[0] && "MAP<STRING, STRING>" in e[0], e[0])
    }

    /** An unconstrained map accepts and is accepted, exactly as an unconstrained list does. */
    @Test
    fun `an unconstrained map connects both ways`() {
        assertEquals(
            emptyList(),
            errors(
                """
                graph "t"

                export var Seen: MAP<STRING, INT> = _newMap()

                export fn size(m: MAP) -> INT = _mapCount(map: m)

                on start {
                    say(message: size(m: Seen))
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- what the pins say ---------------------------------------------------------------------------

    /**
     * **The whole reason the map nodes are reshaped in `resolveNode`.** A `valueAt` on a
     * `MAP<STRING, INT>` hands back an `INT?` — the key type decides the Key pin, the value type decides
     * the answer, and the answer is OPTIONAL because a key that was never set is the ordinary case.
     *
     * It cannot live in `effectivePinType`: that opens with `if (!spec.type.isWildcard) return spec.type`
     * and most of these pins are not wildcards. Trap 11.
     */
    @Test
    fun `the map's types reach the pins of the nodes reading it`() {
        val g = graphOf(
            """
            graph "t"

            export var Seen: MAP<STRING, INT> = _newMap()

            on start {
                say(message: _mapAt(map: Seen, key: tile(1, 2, 0)))
                say(message: _listCount(list: _mapKeys(map: Seen)))
                say(message: _listCount(list: _mapValues(map: Seen)))
            }
            """.trimIndent(),
        )
        fun pinsOf(type: String) = resolveNode(
            g.nodes.single { it.type == type }, catalog[type]!!, g::function, { g.types }, { _, _ -> true },
            { g.enums },
        ) { n, p ->
            g.linkInto(n, p)?.let { l ->
                g.node(l.fromNode)?.let { s ->
                    dev.ziggle.vscript.model.effectivePinType(
                        s,
                        resolveNode(s, catalog[s.type]!!, g::function, { g.types }, { _, _ -> true }, { g.enums })
                            .output(l.fromPin) ?: return@let null,
                    ) { name -> g.variables.firstOrNull { it.name == name }?.type }
                }
            }
        }
        assertEquals(
            TypeRef.parse("INT?"),
            assertNotNull(pinsOf(BuiltinNodes.MAP_AT).output(BuiltinNodes.MAP_VALUE_PIN)).type,
            "the answer is the value type, and optional",
        )
        assertEquals(
            TypeRef(PinType.STRING),
            assertNotNull(pinsOf(BuiltinNodes.MAP_AT).input(BuiltinNodes.MAP_KEY_PIN)).type,
            "the key pin is the key type",
        )
        assertEquals(
            TypeRef.parse("LIST<STRING>"),
            assertNotNull(pinsOf(BuiltinNodes.MAP_KEYS).output("Keys")).type,
        )
        assertEquals(
            TypeRef.parse("LIST<INT>"),
            assertNotNull(pinsOf(BuiltinNodes.MAP_VALUES).output("Values")).type,
        )
    }

    /** The optional answer composes with phase D's narrowing, which knows nothing about maps. */
    @Test
    fun `a lookup narrows through if val and or-else`() {
        assertEquals(
            listOf(7L, 99L),
            said(
                """
                graph "t"

                export var Seen: MAP<INT, INT> = _newMap()

                on start {
                    Seen = _mapWith(map: Seen, key: 1, value: 7)
                    if val n = _mapAt(map: Seen, key: 1) {
                        say(message: n)
                    }
                    say(message: _mapAt(map: Seen, key: 2) ?: 99)
                }
                """.trimIndent(),
            ),
        )
    }

    /** A key wired into a typed Key pin is checked, which is the point of carrying the type at all. */
    @Test
    fun `a key of the wrong type is refused`() {
        val e = errors(
            """
            graph "t"

            export var Seen: MAP<STRING, INT> = _newMap()

            on start {
                say(message: _mapAt(map: Seen, key: 3))
            }
            """.trimIndent(),
        )
        assertEquals(1, e.size, "$e")
        assertTrue("STRING" in e[0], e[0])
    }

    // ---- what it does --------------------------------------------------------------------------------

    @Test
    fun `the verbs do what they say`() {
        assertEquals(
            listOf(2L, 7L, 1L, false, true, 5L),
            said(
                """
                graph "t"

                on start {
                    var m: MAP<INT, INT> = _newMap()
                    m = _mapWith(map: m, key: 1, value: 7)
                    m = _mapWith(map: m, key: 2, value: 5)
                    say(message: _mapCount(map: m))
                    say(message: _mapAt(map: m, key: 1) ?: 0)

                    var less = _mapWithout(map: m, key: 1)
                    say(message: _mapCount(map: less))
                    say(message: _mapHas(map: less, key: 1))
                    say(message: _mapHas(map: less, key: 2))
                    say(message: _listFirst(list: _mapValues(map: less)))
                }
                """.trimIndent(),
            ),
        )
    }

    /** Insertion order, so a map can be walked with an ordinary `for` and two runs agree. */
    @Test
    fun `keys come back in insertion order`() {
        assertEquals(
            listOf(30L, 10L, 20L),
            said(
                """
                graph "t"

                on start {
                    var m: MAP<INT, INT> = _newMap()
                    m = _mapWith(map: m, key: 30, value: 1)
                    m = _mapWith(map: m, key: 10, value: 2)
                    m = _mapWith(map: m, key: 20, value: 3)
                    for k in _mapKeys(map: m) {
                        say(message: k)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** Setting a key that is already there replaces it rather than growing the map. */
    @Test
    fun `an existing key is replaced`() {
        assertEquals(
            listOf(1L, 9L),
            said(
                """
                graph "t"

                on start {
                    var m: MAP<INT, INT> = _newMap()
                    m = _mapWith(map: m, key: 1, value: 7)
                    m = _mapWith(map: m, key: 1, value: 9)
                    say(message: _mapCount(map: m))
                    say(message: _mapAt(map: m, key: 1) ?: 0)
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the accumulator, now that the pass is gone --------------------------------------------------

    /**
     * **The accumulator COPIES on the graph path, and that is the accepted trade.**
     *
     * `AppendPass` used to rewrite this loop into one `Op.SETKEY`. It is deleted: it only ever ran on the
     * canvas front end, so the idiom it existed to make affordable was quietly O(n²) on the text path that
     * every script is written for — and containers are references there now, so there is no copy left for
     * a pass to elide. The canvas keeps working and pays the host call.
     *
     * The behaviour is unchanged, which is what this still pins; only the instruction count moved.
     */
    @Test
    fun `the accumulator loop still computes the right answer, by copying`() {
        val chunk = compile(
            """
            graph "t"

            on start {
                var m: MAP<INT, INT> = _newMap()
                for i in range(from: 0, to: 4) {
                    m = _mapWith(map: m, key: i, value: i * 2)
                }
                say(message: _mapCount(map: m))
                say(message: _mapAt(map: m, key: 3) ?: 0)
            }
            """.trimIndent(),
        )
        assertEquals(listOf<Any?>(4L, 6L), run(chunk))
        assertEquals(0, chunk.setKeys(), "no pass rewrites this any more")
        assertEquals(1, chunk.copies(), "the host verb is what does the work now")
    }

    /**
     * And the refusal, which is the half that matters.
     *
     * A map handed to something that keeps the reference cannot be grown in place afterwards — the holder
     * would see entries appear in a map it was given before they existed. Value semantics say it does not,
     * so the compiler has to copy.
     */
    @Test
    fun `a map somebody else is holding is copied, not written`() {
        val chunk = compile(
            """
            graph "t"

            on start {
                var m: MAP<INT, INT> = _newMap()
                m = _mapWith(map: m, key: 1, value: 1)
                keep(map: m)
                m = _mapWith(map: m, key: 2, value: 2)
                say(message: _mapCount(map: m))
            }
            """.trimIndent(),
        )
        assertEquals(listOf<Any?>(2L), run(chunk))
        // Both writes copy now — there is no pass deciding which of them may not. What this still pins is
        // the ANSWER: handing the map to `keep` and then writing must not change what `keep` was given.
        assertEquals(2, chunk.copies(), "every map write is a copy on the graph path now")
    }

    /** A map put inside itself is the aliasing the pass refuses on the list side too. */
    @Test
    fun `a map used as its own value is copied`() {
        val chunk = compile(
            """
            graph "t"

            on start {
                var m: MAP<INT, MAP> = _newMap()
                m = _mapWith(map: m, key: 1, value: m)
                say(message: _mapCount(map: m))
            }
            """.trimIndent(),
        )
        assertEquals(listOf<Any?>(1L), run(chunk))
        assertEquals(1, chunk.copies(), "a self-reference must not be written in place")
    }

}
