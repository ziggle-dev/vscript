package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.GraphCompiler
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A record whose fields are HOOKS, folded to a constant and then actually called.
 *
 * **This is the shape an activity roster is made of, and it faulted at run time while compiling clean.**
 * A function reference survives folding as its NAME, and the compiler turned one back into something
 * callable at exactly one depth — a function-typed enum COLUMN. Inside a folded RECORD it stayed a string,
 * so `Hooks { at: twice }` handed back `'twice'` and the first call on it faulted with "nothing to call:
 * register 3 holds 'dueAtMs', not a function".
 *
 * It could not fold at all until a `val` with a written type began to (GAPS 24), which is what turned a
 * latent hole into a live one — and nothing caught it, because the corpus test COMPILES scripts and never
 * runs them. Hence a test that drives the VM.
 */
class FoldedRecordHooksTest {

    private val say = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(say))

    private fun run(src: String): String {
        val parsed = Parser(Lexer(src).lex()).parse()
        if (!parsed.ok) return "PARSE " + parsed.errors.map { it.message }
        val low = Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program)
        if (low.errors.isNotEmpty()) return "LOWER " + low.errors.map { it.message }
        val source = GraphSource.of(emptyList())
        val bad = Validator(catalog, source).validate(low.graph).filter { it.severity == Severity.ERROR }
        if (bad.isNotEmpty()) return "VALIDATE " + bad.map { it.message }
        val entry = low.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(low.graph, entry.id)
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 800)
        if (!r.fiber.isFinished) return "FAULT " + (r.fiber.error?.message ?: r.fiber.state.toString())
        return "OK $out"
    }

    private fun runTwo(libSrc: String, rootSrc: String): String {
        val lp = Parser(Lexer(libSrc).lex()).parse()
        if (!lp.ok) return "PARSE lib " + lp.errors.map { it.message }
        val lib = Lower(catalog, source = GraphSource.of(emptyList())).lower(lp.program)
        if (lib.errors.isNotEmpty()) return "LOWER lib " + lib.errors.map { it.message }
        val rp = Parser(Lexer(rootSrc).lex()).parse()
        if (!rp.ok) return "PARSE root " + rp.errors.map { it.message }
        val source = GraphSource.of(listOf(lib.graph))
        val root = Lower(catalog, source = source).lower(rp.program)
        if (root.errors.isNotEmpty()) return "LOWER root " + root.errors.map { it.message }
        val bad = Validator(catalog, source).validate(root.graph).filter { it.severity == Severity.ERROR }
        if (bad.isNotEmpty()) return "VALIDATE " + bad.map { it.message }
        val entry = root.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(root.graph, entry.id)
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 800)
        if (!r.fiber.isFinished) return "FAULT " + (r.fiber.error?.message ?: r.fiber.state.toString())
        return "OK $out"
    }

    private fun runThree(a: String, b: String, root: String): String {
        val ap = Parser(Lexer(a).lex()).parse()
        if (!ap.ok) return "PARSE a " + ap.errors.map { it.message }
        val ga = Lower(catalog, source = GraphSource.of(emptyList())).lower(ap.program)
        if (ga.errors.isNotEmpty()) return "LOWER a " + ga.errors.map { it.message }
        val bp = Parser(Lexer(b).lex()).parse()
        if (!bp.ok) return "PARSE b " + bp.errors.map { it.message }
        val gb = Lower(catalog, source = GraphSource.of(listOf(ga.graph))).lower(bp.program)
        if (gb.errors.isNotEmpty()) return "LOWER b " + gb.errors.map { it.message }
        val rp = Parser(Lexer(root).lex()).parse()
        if (!rp.ok) return "PARSE root " + rp.errors.map { it.message }
        val source = GraphSource.of(listOf(ga.graph, gb.graph))
        val gr = Lower(catalog, source = source).lower(rp.program)
        if (gr.errors.isNotEmpty()) return "LOWER root " + gr.errors.map { it.message }
        val bad = Validator(catalog, source).validate(gr.graph).filter { it.severity == Severity.ERROR }
        if (bad.isNotEmpty()) return "VALIDATE " + bad.map { it.message }
        val entry = gr.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = try {
            GraphCompiler(catalog, debug = false, source = source).compile(gr.graph, entry.id)
        } catch (t: Throwable) {
            return "COMPILE " + t.message
        }
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { x -> out += x[0]; null }
        val r = drive(chunk, hosts, maxTicks = 800)
        if (!r.fiber.isFinished) return "FAULT " + (r.fiber.error?.message ?: r.fiber.state.toString())
        return "OK $out"
    }

    private fun runN(docs: List<String>, root: String): String {
        val built = ArrayList<dev.ziggle.vscript.model.Graph>()
        for ((i, d) in docs.withIndex()) {
            val pp = Parser(Lexer(d).lex()).parse()
            if (!pp.ok) return "PARSE $i " + pp.errors.map { it.message }
            val g = Lower(catalog, source = GraphSource.of(built.toList())).lower(pp.program)
            if (g.errors.isNotEmpty()) return "LOWER $i " + g.errors.map { it.message }
            built += g.graph
        }
        val rp = Parser(Lexer(root).lex()).parse()
        if (!rp.ok) return "PARSE root " + rp.errors.map { it.message }
        val source = GraphSource.of(built.toList())
        val gr = Lower(catalog, source = source).lower(rp.program)
        if (gr.errors.isNotEmpty()) return "LOWER root " + gr.errors.map { it.message }
        val bad = Validator(catalog, source).validate(gr.graph).filter { it.severity == Severity.ERROR }
        if (bad.isNotEmpty()) return "VALIDATE " + bad.map { it.message }
        val entry = gr.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = try {
            GraphCompiler(catalog, debug = false, source = source).compile(gr.graph, entry.id)
        } catch (t: Throwable) {
            return "COMPILE " + t.message
        }
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { x -> out += x[0]; null }
        val r = drive(chunk, hosts, maxTicks = 800)
        if (!r.fiber.isFinished) return "FAULT " + (r.fiber.error?.message ?: r.fiber.state.toString())
        return "OK $out"
    }

    /**
     * The roster EXACTLY: the record type in a third document, the activity reached through a
     * namespace-only alias, and a `registerAll` that is an expression body over a lambda.
     */
    @Test
    fun `the roster shape, faithfully`() {
        val contract = """
            graph "contract"

            export type Hooks { at: fn(INT) -> INT }

            export fn LIST<T>.each(self, f: fn(T)) {
                for item in self {
                    f(item)
                }
            }
        """.trimIndent()
        val leaf = """
            graph "leaf"

            import "contract"

            export fn twice(n: INT) -> INT = n * 2

            export val Impl: Hooks = Hooks { at: twice }
        """.trimIndent()
        val roster = """
            graph "roster"

            import "contract"

            import * as leaf from "leaf"

            enum Roster(name: STRING, impl: Hooks) {
                A("a", leaf::Impl),
            }

            fn Roster.pick(self) -> INT = self.impl.at(4)

            export fn registerAll() = Roster.values().each { say(message: it.pick()) }
        """.trimIndent()
        val main = """
            graph "main"

            import { registerAll } from "roster"

            on start {
                registerAll()
            }
        """.trimIndent()
        assertEquals("OK [8]", runN(listOf(contract, leaf, roster), main))
    }

    /**
     * `probehooks.vs`, the minimal repro left in the scripts tree: ONE import hop, no enum, no roster.
     *
     * The reader's own alias is what the message names — renaming `herbs` to `hb` moved the error with it
     * — so this is about a name baked into a copied constant, not about depth or about any one activity.
     *
     * **`ready` is PRIVATE, and that is the point of the case.** Only the record is exported; the hooks it
     * holds are the document's own. An earlier version of this test exported the hook and passed while the
     * real roster failed, because the qualified name a folded record carries was being looked up through
     * the EXPORT table — which is right for a name a person wrote and wrong for one the compiler made.
     */
    @Test
    fun `an exported record of hooks, read and called one import away`() {
        val contract = """
            graph "contract"

            export type Hooks { ready: fn(INT) -> BOOL }
        """.trimIndent()
        val leaf = """
            graph "leaf"

            import "contract"

            fn ready(n: INT) -> BOOL = n > 0

            export val Impl: Hooks = Hooks { ready: ready }
        """.trimIndent()
        val main = """
            graph "main"

            import "contract"
            import * as hb from "leaf"

            on start {
                val h = hb::Impl
                val f = h.ready
                say(message: f(1))
            }
        """.trimIndent()
        assertEquals("OK [true]", runN(listOf(contract, leaf), main))
    }

    /**
     * The roster EXACTLY as the client writes it, which is the arrangement two simpler ones missed.
     *
     * The type is declared in a third document both import BARE; the roster reaches each activity only
     * through a NAMESPACE import and never binds its names locally. That combination is what produced
     * `compile failed: no document 'herbs'` — a qualified name baked into a folded constant, read by a
     * chunk compiled somewhere the alias does not exist.
     */
    @Test
    fun `hooks reach an enum column the way the real roster arranges it`() {
        val contract = """
            graph "contract"

            export type Hooks { at: fn(INT) -> INT }
        """.trimIndent()
        val leaf = """
            graph "leaf"

            import "contract"

            export fn twice(n: INT) -> INT = n * 2

            export val Impl: Hooks = Hooks { at: twice }
        """.trimIndent()
        val roster = """
            graph "roster"

            import "contract"

            import * as leaf from "leaf"

            enum Roster(impl: Hooks) {
                A(leaf::Impl),
            }

            export fn runAll() {
                for r in Roster.values() {
                    val f = r.impl.at
                    say(message: f(4))
                }
            }
        """.trimIndent()
        val main = """
            graph "main"

            import { runAll } from "roster"

            on start {
                runAll()
            }
        """.trimIndent()
        assertEquals("OK [8]", runN(listOf(contract, leaf, roster), main))
    }

    /**
     * THREE documents, which is the depth the real roster has: hooks → roster → the script that runs it.
     *
     * The qualified name a folded record carries is relative to the document that FOLDED it, and the
     * chunk that reads it is compiled somewhere else — so two levels can pass while three fails with
     * "no document 'herbs'".
     */
    @Test
    fun `hooks reach an enum column two imports deep`() {
        val leaf = """
            graph "leaf"

            export type Hooks { at: fn(INT) -> INT }

            export fn twice(n: INT) -> INT = n * 2

            export val Impl: Hooks = Hooks { at: twice }
        """.trimIndent()
        val roster = """
            graph "roster"

            import "leaf"
            import * as leaf from "leaf"

            enum Roster(impl: Hooks) {
                A(leaf::Impl),
            }

            export fn runAll() {
                for r in Roster.values() {
                    val f = r.impl.at
                    say(message: f(4))
                }
            }
        """.trimIndent()
        val root = """
            graph "main"

            import { runAll } from "roster"

            on start {
                runAll()
            }
        """.trimIndent()
        assertEquals("OK [8]", runThree(leaf, roster, root))
    }

    /** The roster, in miniature: a lib exports its hooks, the root tables them in an enum and calls one. */
    @Test
    fun `hooks reach an enum column across an import`() {
        val lib = """
            graph "leaf"

            export type Hooks { at: fn(INT) -> INT }

            export fn twice(n: INT) -> INT = n * 2

            export val Impl: Hooks = Hooks { at: twice }
        """.trimIndent()
        val root = """
            graph "roster"

            import "leaf"
            import * as leaf from "leaf"

            enum Roster(impl: Hooks) {
                A(leaf::Impl),
            }

            on start {
                for r in Roster.values() {
                    val f = r.impl.at
                    say(message: f(4))
                }
            }
        """.trimIndent()
        assertEquals("OK [8]", runTwo(lib, root))
    }
}
