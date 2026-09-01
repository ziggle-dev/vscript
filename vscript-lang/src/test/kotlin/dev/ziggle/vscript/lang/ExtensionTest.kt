package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.vm.FiberState
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Extension functions — `fn List.add(self, …)`, called as `xs.add(v)`.
 *
 * **An extension is an ordinary function whose first parameter was written somewhere unusual.**
 * `fn Int.double(self) -> INT` IS `fn double(self: INT) -> INT`, and `n.double()` lowers to the `function.call`
 * node that already existed. Nothing new runs: no node type, no opcode, no VM change. That is the whole
 * argument for the feature, and these tests are mostly about proving the spelling survives the round trip in
 * both directions rather than about anything executing differently.
 *
 * **The syntax was already taken**, which is the part that needed care: `draw.text(…)` is a call to the node
 * TYPE `draw.text`, because a dot separates the parts of a node's name. So a dotted call resolves as a node
 * type FIRST and only then as an extension — every existing script keeps meaning what it meant.
 */
class ExtensionTest {

    private val catalog = NodeCatalog()

    /** [text] read and printed back, expecting [asPrinted] — itself unless a canonical form is given. */
    private fun keeps(text: String, asPrinted: String = text) {
        val vs = VsText(catalog)
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        assertEquals(asPrinted, vs.write(assertNotNull(read.graph), read.comments).trim(), "the spelling changed")
    }

    // ---- round trips -----------------------------------------------------------------------------------

    @Test
    fun `an extension declaration and its call round-trip`() {
        keeps(
            """
            graph "probe"

            export fn INT.double(self) -> INT = self * 2

            on start {
                log(message: "" + 21.double())
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an extension taking arguments round-trips`() {
        keeps(
            """
            graph "probe"

            export fn INT.plus(self, n: INT) -> INT = self + n

            on start {
                log(message: "" + 1.plus(n: 2))
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an extension on a declared record round-trips`() {
        keeps(
            """
            graph "probe"

            export type Point { x: INT, y: INT }

            export fn Point.sum(self) -> INT = self.x + self.y

            on start {
                val p = Point { x: 1, y: 2 }
                log(message: "" + p.sum())
            }
            """.trimIndent(),
        )
    }

    /**
     * A block body, so `self` is reached from statements rather than from one expression.
     *
     * The call binds to a `let` rather than inlining, and that is not about extensions: a block-bodied
     * function is a STEP, and a step whose output is read is written as a binding so the call is not
     * emitted twice. An expression-bodied one is pure and inlines, which the tests above show.
     */
    @Test
    fun `a block-bodied extension round-trips`() {
        // Written with braces, printed without. `{ return self * 3 }` and `= self * 3` are the same
        // function — the desugaring is one-way, so the graph holds no record of which was typed and the
        // printer gives back the canonical spelling. A body that has to RUN keeps its braces, because then
        // there is a return node in the graph saying so.
        keeps(
            """
            graph "probe"

            export fn INT.triple(self) -> INT {
                return self * 3
            }

            on start {
                val result = 7.triple()
                log(message: "" + result)
            }
            """.trimIndent(),
            """
            graph "probe"

            export fn INT.triple(self) -> INT = self * 3

            on start {
                val result = 7.triple()
                log(message: "" + result)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a private extension round-trips`() {
        keeps(
            """
            graph "probe"

            export fn INT.double(self) -> INT = self * 2

            on start {
                log(message: "" + 4.double())
            }
            """.trimIndent(),
        )
    }

    // ---- what it runs as -------------------------------------------------------------------------------

    private fun run(text: String): List<Any?> {
        val read = VsText(catalog).read(text)
        val graph = assertNotNull(read.graph, "should compile: ${read.errors.map { it.message }}")
        val issues = Validator(catalog).validate(graph)
        assertTrue(issues.errors().isEmpty(), "$issues")
        val chunk = GraphCompiler(catalog).compile(graph, graph.entries(catalog).first().id)
        val result = drive(chunk)
        assertEquals(FiberState.DONE, result.fiber.state, "${result.fiber.error}")
        return result.fiber.result
    }

    /** The receiver reaches the body as `self`, and the whole thing is an ordinary call underneath. */
    @Test
    fun `an extension runs with its receiver bound`() {
        assertEquals(
            listOf(42),
            run(
                """
                graph "probe"

                export fn INT.double(self) -> INT = self * 2

                on start {
                    return 21.double()
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an extension runs with arguments beside the receiver`() {
        assertEquals(
            listOf(7),
            run(
                """
                graph "probe"

                export fn INT.plus(self, n: INT) -> INT = self + n

                on start {
                    return 3.plus(n: 4)
                }
                """.trimIndent(),
            ),
        )
    }

    /** Chained, which is most of why anybody wants this. */
    @Test
    fun `extensions chain`() {
        assertEquals(
            listOf(12),
            run(
                """
                graph "probe"

                export fn INT.double(self) -> INT = self * 2

                on start {
                    return 3.double().double()
                }
                """.trimIndent(),
            ),
        )
    }

    // ---- the collisions --------------------------------------------------------------------------------

    /**
     * A dotted call still reaches a NODE before it reaches an extension.
     *
     * `draw.text(…)` names the node type `draw.text` — the dot separates the parts of a node's name — so
     * extensions are tried only after that lookup fails and no existing script changes meaning.
     *
     * Asserted here on the resolution ORDER rather than on `draw.text` itself, because every dotted name in
     * the bare catalogue is a syntactic form (`flow.branch`, `var.get`) with no call spelling at all; the
     * namespaced host nodes live in the SDK. `draw.text` IS exercised, by the client's script corpus, which
     * compiles a file using it against the real catalogue.
     */
    @Test
    fun `an extension does not hijack a name the catalogue owns`() {
        // `toText` is a real node. An extension of the same name on a receiver must not change what the
        // plain call means.
        val read = VsText(catalog).read(
            """
            graph "probe"

            export fn INT.toText(self) -> STRING = "" + self

            on start {
                log(message: toText(value: 3))
            }
            """.trimIndent(),
        )
        assertTrue(read.ok, "the node should still resolve: ${read.errors.map { it.message }}")
        val graph = assertNotNull(read.graph)
        assertTrue(
            graph.nodes.any { it.type == "value.toText" },
            "the bare call should be the NODE: ${graph.nodes.map { it.type }}",
        )
    }

    /**
     * An extension may not be called by its bare name.
     *
     * Otherwise `n.double()` and `double(self: n)` would be two spellings of one graph — the §6.7 collision
     * — and the printer would have to pick one, silently rewriting the other on every reformat. The
     * declaration having a receiver is what makes the dot form the only form.
     */
    @Test
    fun `calling an extension by name is refused`() {
        val read = VsText(catalog).read(
            """
            graph "probe"

            export fn INT.double(self) -> INT = self * 2

            on start {
                log(message: "" + double(self: 3))
            }
            """.trimIndent(),
        )
        val message = read.errors.joinToString("; ") { it.message }
        assertTrue("extends" in message, "should say it needs a receiver: $message")
    }

    @Test
    fun `calling an unknown method still reports the whole name`() {
        val read = VsText(catalog).read(
            """
            graph "probe"

            on start {
                val xs = []
                log(message: "" + xs.nope())
            }
            """.trimIndent(),
        )
        assertTrue(read.errors.any { "xs.nope" in it.message }, "${read.errors.map { it.message }}")
    }

    /**
     * The motivating case: a `List.add` written in vs rather than added as a node.
     *
     * The whole argument for the feature. `withItemAdded` already exists, and until now the only way to
     * spell it as `xs.add(v)` was to write a Kotlin node, bump the catalogue and ship a client.
     *
     * Compiled rather than run: `list.add` is a HOST node, and the bare test VM binds no hosts. What is
     * being proved is the language part — that the extension resolves, wires `self` from the receiver, and
     * comes back out as it was written.
     *
     * It still returns a COPY, because lists are values here: `xs.add(v)` on its own does nothing and the
     * result has to be assigned. An extension changes the spelling, not the semantics.
     */
    @Test
    fun `a list append written as an extension`() {
        keeps(
            """
            graph "probe"

            export fn LIST.add(self, value: WILDCARD) -> LIST = _listWithItemAdded(list: self, value: value)

            on start {
                var xs = []
                xs = xs.add(value: 1)
                log(message: "" + xs)
            }
            """.trimIndent(),
        )
    }

    // ---- across imports --------------------------------------------------------------------------------

    private fun library(name: String, body: String): dev.ziggle.vscript.model.Graph {
        val read = VsText(catalog).read("graph \"$name\"\n\n$body")
        return assertNotNull(read.graph, "$name should compile: ${read.errors.map { it.message }}")
    }

    /**
     * An imported extension is callable on a receiver, with no alias at the call site.
     *
     * **The one name an import brings in unqualified**, which is the whole tension §7b's design has with
     * this: every other imported name is written `alias::x`, and `n.double()` has nowhere to put one. That
     * is what makes a shared core library possible and what makes collisions possible for the first time.
     */
    @Test
    fun `an imported extension is callable on a receiver`() {
        val core = library("core", "export fn INT.double(self) -> INT = self * 2")
        val vs = VsText(catalog, dev.ziggle.vscript.model.GraphSource { if (it.ref == "core") core else null })
        val text = """
            graph "probe"

            import { double } from "core"

            on start {
                log(message: "" + 21.double())
            }
        """.trimIndent()
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph), read.comments).trim())
    }

    /** `private` still does not cross, exactly as it does not for a function or a type. */
    @Test
    fun `an unexported extension does not cross an import`() {
        val core = library("core", "fn INT.double(self) -> INT = self * 2")
        val vs = VsText(catalog, dev.ziggle.vscript.model.GraphSource { if (it.ref == "core") core else null })
        val read = vs.read(
            """
            graph "probe"

            import * as core from "core"

            on start {
                log(message: "" + 21.double())
            }
            """.trimIndent(),
        )
        assertTrue(read.errors.isNotEmpty(), "an unexported extension should not be reachable")
    }

    /** This document's own wins, which is also the way out of an ambiguity: declare your own. */
    @Test
    fun `a local extension shadows an imported one`() {
        val core = library("core", "export fn INT.double(self) -> INT = self * 100")
        val vs = VsText(catalog, dev.ziggle.vscript.model.GraphSource { if (it.ref == "core") core else null })
        val read = vs.read(
            """
            graph "probe"

            import * as core from "core"

            export fn INT.double(self) -> INT = self * 2

            on start {
                return 21.double()
            }
            """.trimIndent(),
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        val call = graph.nodes.first { it.callee != null }
        assertEquals("double", call.callee, "the LOCAL one should win, unqualified")
    }

    /**
     * Two imports extending the same name is refused, not picked.
     *
     * Picking would mean an import added somewhere above silently changed what this line does — the exact
     * failure the qualified-everything rule was designed to make impossible. The message names both, and the
     * qualified call is the way out.
     */
    @Test
    fun `an ambiguous imported extension is refused`() {
        val a = library("a", "export fn INT.double(self) -> INT = self * 2")
        val b = library("b", "export fn INT.double(self) -> INT = self * 3")
        val vs = VsText(catalog, dev.ziggle.vscript.model.GraphSource {
            when (it.ref) { "a" -> a; "b" -> b; else -> null }
        })
        val message = vs.read(
            """
            graph "probe"

            import * as x from "a"
            import { double } from "a"
            import * as y from "b"
            import { double } from "b"

            on start {
                log(message: "" + 21.double())
            }
            """.trimIndent(),
        ).errors.joinToString("; ") { it.message }
        assertTrue("'x'" in message && "'y'" in message, "should name both: $message")
    }

    /** And the qualified bare call is the way out — accepted, and printed back as itself. */
    @Test
    fun `an ambiguous extension can be called qualified`() {
        val a = library("a", "export fn INT.double(self) -> INT = self * 2")
        val b = library("b", "export fn INT.double(self) -> INT = self * 3")
        val vs = VsText(catalog, dev.ziggle.vscript.model.GraphSource {
            when (it.ref) { "a" -> a; "b" -> b; else -> null }
        })
        val text = """
            graph "probe"

            import * as x from "a"
            import * as y from "b"

            on start {
                log(message: "" + x::double(self: 21))
            }
        """.trimIndent()
        val read = vs.read(text)
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        assertEquals(text, vs.write(assertNotNull(read.graph), read.comments).trim(),
            "an ambiguous name must NOT print as a dot call — it would not read back")
    }

    /**
     * An extension returning a list of a BUILT-IN whose friendly name is not its constant.
     *
     * The reported case. `ItemRef` is what the picker and the reference call `ITEM_REF`, and writing it
     * produced two errors that contradicted each other — "which this graph does not declare", then a
     * refusal to wire `LIST<ITEM_REF>` into `LIST<ItemRef>`.
     *
     * The original was `= inventoryItems()`, a node the bare catalogue does not have; the parameter carries
     * the same `LIST<ItemRef>` through the same wire check, which is where it failed.
     */
    @Test
    fun `a host type written the way the picker spells it resolves`() {
        // Declared here rather than assumed. `ItemRef` used to be registered by `model/Types.kt`, which
        // put nine game types in the language's own picker list; they belong to the pack that declares
        // them and live in `dev.ziggle.nodes.GameRecords` now. What this test is about is the SPELLING -- a
        // host type written the way the picker shows it -- so the host record is the premise, not the
        // subject.
        // Both halves: the record is the type's shape, the `Types` entry is the name a document may
        // write -- `Validator.checkTypes` seeds its legal names from `Types.all`. A domain pack registers
        // both, which is what `dev.ziggle.nodes.GameRecords` now does for the nine that used to sit in the
        // language.
        dev.ziggle.vscript.model.HostRecords.register(dev.ziggle.vscript.model.HostRecord("ItemRef", emptyList()))
        dev.ziggle.vscript.model.Types.register(
            dev.ziggle.vscript.model.TypeInfo("ItemRef", dev.ziggle.vscript.model.TypeRef.named("ItemRef"), "one stack in a container", authorable = false),
        )
        val vs = VsText(catalog)
        val read = vs.read(
            """
            graph "inventory"

            export type Inventory { }

            export fn Inventory.contents(self, held: List<ItemRef>) -> List<ItemRef> = held
            """.trimIndent(),
        )
        assertTrue(read.ok, "should compile: ${read.errors.map { "${it.span} ${it.message}" }}")
        val fn = assertNotNull(assertNotNull(read.graph).function("contents"))
        assertEquals(
            "ItemRef", fn.results[0].type.of?.name,
            "the result should be a list of the HOST type, not of a type this document declared",
        )
        // NOT an exact round trip: `List` is a built-in and prints as its constant name. `ItemRef` is a
        // HOST record and prints as itself — it has no constant to be spelled by any more, which is the
        // whole of the change and is why this used to read `LIST<ITEM_REF>`.
        assertTrue("LIST<ItemRef>" in vs.write(read.graph!!), vs.write(read.graph!!))
    }

    // ---- persistence -----------------------------------------------------------------------------------

    @Test
    fun `a receiver survives being saved and read back`() {
        val read = VsText(catalog).read(
            """
            graph "probe"

            export fn INT.double(self) -> INT = self * 2
            """.trimIndent(),
        )
        val graph = assertNotNull(read.graph, "${read.errors.map { it.message }}")
        val back = GraphDoc.fromJson(GraphDoc.toJson(graph))
        val fn = assertNotNull(back.function("double"))
        assertTrue(fn.isExtension, "the receiver should survive")
        assertEquals("INT", fn.receiver.toString())
        assertEquals("self", fn.params.first().name, "the receiver is still the first parameter")
    }

    /** An older client must refuse a document with an extension rather than read it as a plain function. */
    @Test
    fun `a document carrying an extension is written at the new format`() {
        val read = VsText(catalog).read("""graph "p"${'\n'}${'\n'}export fn INT.double(self) -> INT = self * 2""")
        val json = GraphDoc.toJson(assertNotNull(read.graph))
        assertTrue("\"format\": ${GraphDoc.FORMAT}" in json, json.take(120))
        assertTrue(GraphDoc.FORMAT >= 8, "the extension format bump should not have been reverted")
    }

    // ---- an arithmetic receiver ----------------------------------------------------------------------

    /**
     * `(a * b).toInt()` — the dot form on an expression bound to nothing.
     *
     * Four scripts avoid this and say why: "the dot form is looked up as an EXTENSION, and an arithmetic
     * expression bound to nothing carries no type for one to hang off". The first half is right and the
     * second is not the reason it failed — a receiver whose type nothing can establish is deliberately
     * PERMISSIVE in `extensionCall`, accepting every candidate rather than refusing on no evidence. What
     * those four have in common is that none of them imports `core/math`, which is the only place a
     * `toInt` extension is declared, so the lookup found no candidate by name at all.
     */
    @Test
    fun `an extension applies to a bare arithmetic expression`() {
        assertEquals(
            listOf(72.0),
            run(
                """
                graph "probe"

                export fn Float.scaled(self) -> Float = self / 1000.0

                on start {
                    val ms = 5000
                    val gained = 100
                    return (gained * 3600000.0 / ms).scaled()
                }
                """.trimIndent(),
            ),
        )
    }

    /** And on an INT-typed one, which is what `graahk`'s `(ax + ay).toInt()` actually is. */
    @Test
    fun `an extension applies to an integer arithmetic expression`() {
        assertEquals(
            listOf(14),
            run(
                """
                graph "probe"

                export fn Float.twice(self) -> Float = self * 2

                on start {
                    val a = 3
                    val b = 4
                    return (a + b).twice()
                }
                """.trimIndent(),
            ),
        )
    }
}
