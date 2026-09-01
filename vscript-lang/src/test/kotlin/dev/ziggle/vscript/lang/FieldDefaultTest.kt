package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.hostNode
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
 * A record field default that has to be worked out, evaluated at each construction.
 *
 * **The default becomes a FUNCTION**, in the document that declares the record, and a literal omitting the
 * field is wired from a Call to it — see `BuiltinNodes.FIELD_DEFAULT`. That is the whole mechanism, and
 * choosing it rather than storing an expression on the pin is what buys the four things that would
 * otherwise each need code: whether the record literal is a STEP falls out of the purity derivation
 * already in place, the default may name the declaring document's own unexported helpers because it lives
 * there, it crosses an import like any other function, and the document format already persists functions.
 *
 * **A literal default is untouched.** It folds as it always has, rides on the pin, and emits no function
 * and no call — so the common case is byte-for-byte the graph it was.
 */
class FieldDefaultTest {

    private val sayNode = hostNode(
        "test.say", "test.say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

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

    private fun said(src: String, source: GraphSource = GraphSource.NONE): List<Any?> {
        val r = VsText(catalog, source).read(src)
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

    private fun printed(src: String, source: GraphSource = GraphSource.NONE): String {
        val vs = VsText(catalog, source)
        val r = vs.read(src)
        return vs.write(assertNotNull(r.graph, "${r.errors.map { "${it.span.line}: ${it.message}" }}")).trim()
    }

    // ---- it computes ---------------------------------------------------------------------------------

    @Test
    fun `a computed default fills a field that was left out`() {
        assertEquals(
            listOf(7L),
            said(
                """
                graph "probe"

                fn seven() -> INT = 7

                type Point { x: INT, y: INT = seven() }

                on start {
                    say(message: Point { x: 1 }.y)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * **Per construction, not once.** That is the difference between a default and a shared value, and it
     * is observable: a default that has to run runs again for the next literal.
     */
    @Test
    fun `a computed default runs at every construction`() {
        assertEquals(
            listOf("made", "made", 2L),
            said(
                """
                graph "probe"

                var Made: INT = 0

                fn _listCount() -> INT {
                    Made = Made + 1
                    say(message: "made")
                    return Made
                }

                type Row { n: INT = _listCount() }

                on start {
                    val a = Row { }
                    val b = Row { }
                    say(message: b.n)
                }
                """.trimIndent(),
            ),
        )
    }

    /** A default may name the declaring document's own unexported helper — it lives there. */
    @Test
    fun `a computed default may call something the document does not export`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                import * as lib from "lib"

                on start {
                    say(message: lib::Point { }.y)
                }
                """.trimIndent(),
                libOf(
                    "lib" to """
                        graph "lib"

                        fn hidden() -> INT = 3

                        export type Point { x: INT, y: INT = hidden() }
                    """.trimIndent(),
                ),
            ),
        )
    }

    // ---- the consequence -----------------------------------------------------------------------------

    /**
     * A record literal that has to run makes its function a STEP, and the printer says so.
     *
     * Derived, not declared — the same rule every other body follows — so it cannot disagree with what the
     * literal actually needs. This is the surprise worth stating: adding a computed default to a record
     * changes how functions that build it are called.
     */
    @Test
    fun `a literal that fills a running default makes its function a step`() {
        val src = """
            graph "probe"

            fn loud() -> INT {
                say(message: "ran")
                return 1
            }

            type Row { n: INT = loud() }

            fn build() -> Row = Row { }

            on start {
                say(message: build().n)
            }
        """.trimIndent()
        assertTrue(
            "fn build() -> Row {" in printed(src),
            "building a record whose default runs is a step:\n${printed(src)}",
        )
        assertEquals(listOf("ran", 1L), said(src))
    }

    /** ...and a LITERAL default changes nothing: no function, no call, the same graph as before. */
    @Test
    fun `a literal default emits no function and keeps the literal an expression`() {
        val src = """
            graph "probe"

            type Row { n: INT = 5 }

            fn build() -> Row = Row { }

            on start {
                say(message: build().n)
            }
        """.trimIndent()
        val r = VsText(catalog).read(src)
        val g = assertNotNull(r.graph, "${r.errors.map { it.message }}")
        assertTrue(g.functions.none { it.name.startsWith("@default:") }, "a literal default needs no function")
        assertTrue("fn build() -> Row = Row {}" in printed(src), printed(src))
        assertEquals(listOf(5L), said(src))
    }

    @Test
    fun `the synthesised function is not printed`() {
        val src = """
            graph "probe"

            type Point { x: INT, y: INT = seven() }

            fn seven() -> INT = 7

            on start {
                say(message: Point { x: 1 }.y)
            }
        """.trimIndent()
        assertTrue("@default" !in printed(src), "a name nobody can type must not be written:\n${printed(src)}")
        assertEquals(src, printed(src))
    }

    // ---- `single` ------------------------------------------------------------------------------------

    /**
     * A `single` is an INSTANCE, so its fields may be worked out.
     *
     * They could not be, and the limitation was not a decision: the backing variable carried no default at
     * all and the compiler rebuilt the record from folded literals with `zeroOf`. It now gets a real
     * initialiser, which is what makes a `single` hold a function reference, another variable, or a call.
     */
    @Test
    fun `a single's fields may be worked out`() {
        assertEquals(
            listOf(12L),
            said(
                """
                graph "probe"

                fn six() -> INT = 6

                single Run { budget: INT = six() * 2 }

                on start {
                    say(message: Run.budget)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a single may hold a function reference`() {
        assertEquals(
            listOf(8L),
            said(
                """
                graph "probe"

                fn twice(n: INT) -> INT = n * 2

                single Run { step: fn(INT) -> INT = twice }

                on start {
                    say(message: Run.step(4))
                }
                """.trimIndent(),
            ),
        )
    }

    /** An all-literal single is untouched — no initialiser, the same document it always was. */
    @Test
    fun `an all-literal single still needs no initialiser`() {
        assertEquals(
            listOf(0L, "idle"),
            said(
                """
                graph "probe"

                single State { laps: INT = 0, phase: STRING = "idle" }

                on start {
                    say(message: State.laps)
                    say(message: State.phase)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a single with a computed field round-trips`() {
        // Types before functions is the printer's canonical order and always has been, so a document
        // that writes them the other way round comes back reordered. What has to hold is that the
        // computed default is on the DECLARATION, and that printing is stable.
        val src = """
            graph "probe"

            single Run { budget: INT = six() * 2 }

            fn six() -> INT = 6

            on start {
                say(message: Run.budget)
            }
        """.trimIndent()
        assertEquals(src, printed(src))
    }

    // ---- an optional function type -------------------------------------------------------------------

    /**
     * A function value can be ABSENT, and there was no way to say so.
     *
     * A table of handlers with no row for this key hands back nothing, which is an ordinary thing to
     * write — and the type system's answer was that "there is no null function", so the only way to
     * return one was to lie about the type. Two spellings, because the ambiguity is real: after a RESULT
     * a bare `?` is the result's, so an optional function that returns something needs parentheses. One
     * with no result has nothing to compete with and keeps the short form.
     */
    @Test
    fun `an action with no result may be optional without parentheses`() {
        assertEquals(
            listOf(4L),
            said(
                """
                graph "probe"

                fn shout(n: INT) {
                    say(message: n)
                }

                var Table: MAP<INT, fn(INT)> = _newMap()

                fn handler(n: INT) -> fn(INT)? = _mapAt(map: Table, key: n)

                on start {
                    Table = _mapWith(map: Table, key: 1, value: shout)
                    if val h = handler(n: 1) {
                        h(4)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a function with a result may be optional with parentheses`() {
        assertEquals(
            listOf(6L),
            said(
                """
                graph "probe"

                fn twice(n: INT) -> INT = n * 2

                var Table: MAP<INT, fn(INT) -> INT> = _newMap()

                fn chooser(n: INT) -> (fn(INT) -> INT)? = _mapAt(map: Table, key: n)

                on start {
                    Table = _mapWith(map: Table, key: 1, value: twice)
                    if val c = chooser(n: 1) {
                        say(message: c(3))
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** The other reading is unchanged: after a result, a bare `?` is still the RESULT'S. */
    @Test
    fun `a bare mark after a result still belongs to the result`() {
        val src = """
            graph "probe"

            fn pick(n: INT) -> fn(INT) -> INT? = twice

            fn twice(n: INT) -> INT? = n * 2

            on start {
                say(message: 1)
            }
        """.trimIndent()
        // `fn(INT) -> INT?` is a function RETURNING an optional, not an optional function — so it prints
        // back without parentheses and reads the same way again.
        assertTrue("fn pick(n: INT) -> fn(INT) -> INT?" in printed(src), printed(src))
    }

    /** ...and the named-results form is still told apart from a parenthesised type by one token. */
    @Test
    fun `several named results still parse beside a parenthesised type`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                fn pair() -> (low: INT, high: INT) {
                    return 1, 2
                }

                on start {
                    val (a, b) = pair()
                    say(message: a + b)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an optional function type round-trips`() {
        val src = """
            graph "probe"

            var Table: MAP<INT, fn(INT) -> INT> = _newMap()

            fn chooser(n: INT) -> (fn(INT) -> INT)? = _mapAt(map: Table, key: n)

            on start {
                say(message: 1)
            }
        """.trimIndent()
        assertEquals(src, printed(src))
    }

    /**
     * A record may hold a handler that TAKES ONE OF ITSELF — the shape a dispatch column has.
     *
     * The self-containment check walked `TypeRef.of` on every field, and `of` is `args.firstOrNull()`,
     * which for a function type is its first PARAMETER. So `runnable: fn(Self)?` read as "this record
     * contains one of its own kind" and was refused. It does not: a function value is a closure pointer,
     * one word wide whatever it is a pointer to.
     */
    @Test
    fun `a record may hold a handler that takes one of itself`() {
        assertEquals(
            listOf(3L),
            said(
                """
                graph "probe"

                type Rumor { n: INT, runnable: fn(Rumor)? }

                fn show(r: Rumor) {
                    say(message: r.n)
                }

                on start {
                    val r = Rumor { n: 3, runnable: show }
                    if val go = r.runnable {
                        go(r)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /** ...and a record that genuinely holds one of its own kind is still refused. */
    @Test
    fun `a record that holds one of itself is still refused`() {
        val r = VsText(catalog).read(
            """
            graph "probe"

            type Node { next: Node }

            on start {
                say(message: 1)
            }
            """.trimIndent(),
        )
        assertTrue(!r.ok)
        assertTrue(r.errors.any { "contains itself" in it.message }, r.errors.joinToString { it.message })
    }
    // ---- a TILE default is a RECORD, not the string the document holds -------------------------------

    /**
     * `single Entry { at: TILE = tile(1,2,0) }` then `Entry.at.x`.
     *
     * **A tile is a string in the document and a record at run time**, and an all-literal `single` is the
     * one place that conversion had nowhere to happen: `Lower` deliberately emits NO initialiser prologue
     * when every field is inline, so the starting value is built once by `zeroOf` — which took each
     * field's declared default verbatim.
     *
     * The result reached a live run. The string sailed through every node it was handed to, because a
     * TILE pin parses `"1802,3503,0"` quite happily, and only died several calls later inside a typed
     * `fn` doing `other.x` — reported as `GETFIELD on String, expected a record`, pointing at a helper
     * that was completely innocent.
     */
    @Test
    fun `a single's tile default is a record, not its literal string`() {
        assertEquals(
            listOf(1802L, 3503L, 0L),
            said(
                """
                graph "probe"

                single Entry { at: TILE = tile(1802, 3503, 0) }

                on start {
                    say(message: Entry.at.x)
                    say(message: Entry.at.y)
                    say(message: Entry.at.plane)
                }
                """.trimIndent(),
            ),
        )
    }

    /** The same for a plain `type`, whose zero is built by the same rule. */
    @Test
    fun `a record field's tile default is a record too`() {
        assertEquals(
            listOf(300L, 400L),
            said(
                """
                graph "probe"

                type Spot { at: TILE = tile(300, 400, 0) }

                on start {
                    val s = Spot { }
                    say(message: s.at.x)
                    say(message: s.at.y)
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- an OPTIONAL hook ----------------------------------------------------------------------------

    /**
     * A nullable function field defaulting to null — the shape a contract uses for a hook that most of its
     * rows do not implement.
     *
     * **This is what lets a contract grow a hook without touching a single existing literal.** The
     * alternative — a non-null `fn(T)` field — forces every row to supply something, which for a row that
     * has nothing to say means inventing an empty function purely to satisfy the shape. Here the row that
     * cares supplies one and the rest are unchanged, so the diff that adds the hook is confined to the
     * declaration and the rows that use it.
     */
    @Test
    fun `an optional function field defaults to null and is not called`() {
        assertEquals(
            listOf("nothing to draw"),
            said(
                """
                graph "probe"

                type Widget {
                    name: STRING,
                    draw: fn(Widget)? = null,
                }

                on start {
                    val w = Widget { name: "plain" }
                    if val d = w.draw {
                        d(w)
                    } else {
                        say(message: "nothing to draw")
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * And when a row does supply one, calling through the optional hands the row back to its own hook —
     * which is the only way a hook with no closure can reach the data it belongs to.
     */
    @Test
    fun `a supplied function field is called through the optional and receives its own row`() {
        assertEquals(
            listOf("drawing fancy"),
            said(
                """
                graph "probe"

                type Widget {
                    name: STRING,
                    draw: fn(Widget)? = null,
                }

                fn paint(w: Widget) {
                    say(message: "drawing " + w.name)
                }

                on start {
                    val w = Widget { name: "fancy", draw: paint }
                    if val d = w.draw {
                        d(w)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A default on a record declared in ANOTHER document — the case every contract is actually made of.
     *
     * **The same-document cases above prove less than they look.** A contract worth giving a default to is
     * one other documents build values of: the type is declared in a library, the literals are written by
     * whoever implements it, and the entire reason for the default is that those literals must not have to
     * change when the contract grows. Every test above builds its literal in the file that declares the
     * type, which is the one arrangement where the question cannot arise.
     */
    @Test
    fun `a default applies to a literal written in another document`() {
        val source = libOf(
            "contract" to """
                graph "contract"

                type Row {
                    name: STRING,
                    weight: INT = 5,
                }

                export { Row }
            """.trimIndent(),
        )
        assertEquals(
            listOf(5L),
            said(
                """
                graph "probe"

                import "contract"

                on start {
                    val r = Row { name: "unchanged" }
                    say(message: r.weight)
                }
                """.trimIndent(),
                source,
            ),
        )
    }

    /**
     * An optional hook that RETURNS something, as a field — which needs the parentheses.
     *
     * The spelling is not a detail here. `fn(Job) -> INT?` on a field reads as a function returning an
     * optional (see [a bare mark after a result still belongs to the result]), so it declares a hook that
     * must exist and must be CALLED to discover it has nothing to say. `(fn(Job) -> INT)?` declares a hook
     * that may be absent, which is the one a row can decline to supply. Two different contracts, one
     * character apart, and only the second defaults to null honestly.
     */
    @Test
    fun `an optional field whose function returns a value needs the parenthesised form`() {
        assertEquals(
            listOf("untimed", 42L),
            said(
                """
                graph "probe"

                type Job {
                    name: STRING,
                    dueAt: (fn(Job) -> INT)? = null,
                }

                fn noon(j: Job) -> INT = 42

                on start {
                    val idle = Job { name: "idle" }
                    if val d = idle.dueAt {
                        say(message: d(idle))
                    } else {
                        say(message: "untimed")
                    }

                    val timed = Job { name: "timed", dueAt: noon }
                    if val d = timed.dueAt {
                        say(message: d(timed))
                    }
                }
                """.trimIndent(),
            ),
        )
    }

}
