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
import kotlin.test.assertTrue

/**
 * Calling a function-valued FIELD, on every shape of receiver.
 *
 * `recv.name(…)` is an extension call unless `name` is a function-valued field — and that lookup existed
 * but stopped one level short. A bare-name head went through `invokeSugar`; write the same call on a field
 * read and the parser hands `call` a RECEIVER instead, and that branch tried an extension and nothing
 * else. The error named the wrong thing: "nothing extends this with 'at'", for a field sitting right
 * there. GAPS 29.
 */
class FieldCallTest {

    private val say = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(say))

    private fun src(body: String) = """
        graph "probe"

        type Hooks { at: fn(INT) -> INT }

        type Row { impl: Hooks }

        fn twice(n: INT) -> INT = n * 2

        val Impl: Hooks = Hooks { at: twice }

        on start {
        $body
        }
    """.trimIndent()

    private fun errorsIn(body: String): List<String> {
        val parsed = Parser(Lexer(src(body)).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { "parse: ${it.message}" }
        return Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program).errors.map { it.message }
    }

    @Test
    fun `a field call works on a val holding the record`() =
        assertEquals(emptyList(), errorsIn("    say(message: Impl.at(4))"))

    @Test
    fun `a field call works on a local`() =
        assertEquals(emptyList(), errorsIn("    val h = Impl\n    say(message: h.at(4))"))

    /** The one that was refused: the receiver is itself a field read. */
    @Test
    fun `a field call works through a chain`() =
        assertEquals(
            emptyList(),
            errorsIn("    val r = Row { impl: Impl }\n    say(message: r.impl.at(4))"),
        )

    @Test
    fun `a field call works when the field is bound first`() =
        assertEquals(
            emptyList(),
            errorsIn("    val r = Row { impl: Impl }\n    val f = r.impl.at\n    say(message: f(4))"),
        )

    /** And the chained one really dispatches, rather than merely lowering. */
    @Test
    fun `a chained field call runs`() {
        val text = src("    val r = Row { impl: Impl }\n    say(message: r.impl.at(4))")
        val low = Lower(catalog, source = GraphSource.of(emptyList()))
            .lower(Parser(Lexer(text).lex()).parse().program)
        assertEquals(emptyList(), low.errors.map { it.message })
        val source = GraphSource.of(emptyList())
        assertEquals(
            emptyList(),
            Validator(catalog, source).validate(low.graph).filter { it.severity == Severity.ERROR }.map { it.message },
        )
        val entry = low.graph.entries(catalog).single { it.type == BuiltinNodes.ENTRY }
        val chunk = GraphCompiler(catalog, debug = false, source = source).compile(low.graph, entry.id)
        val out = ArrayList<Any?>()
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry()
            .register("say", HostKind.INLINE, arity = 1) { a -> out += a[0]; null }
        val r = drive(chunk, hosts, maxTicks = 800)
        assertTrue(r.fiber.isFinished, "${r.fiber.state} ${r.fiber.error?.message.orEmpty()}")
        assertEquals(listOf("8"), out.map { it.toString() })
    }

    /**
     * When a field and an extension share a name, the FIELD wins — the same way round however the receiver
     * was written.
     *
     * The tie-break is not arbitrary. An extension's name can be changed where it is imported
     * (`import { at as elsewhere }`), and a record's field name cannot be aliased at all — so the field is
     * the name with no way out and the extension is the one that can move. A bare head already resolved
     * this way, because `invokeSugar` runs ahead of all name resolution; a chained head used to answer the
     * other way, because its branch tried the extension first.
     */
    private val bothSpellings = """
        graph "probe"

        type Hooks { at: fn(INT) -> INT }

        type Row { impl: Hooks }

        fn twice(n: INT) -> INT = n * 2

        fn Hooks.at(self, n: INT) -> INT = 99

        fn Hooks.other(self, n: INT) -> INT = 7

        val Impl: Hooks = Hooks { at: twice }

        on start {
        BODY
        }
    """.trimIndent()

    private fun errorsInBoth(body: String): List<String> {
        val text = bothSpellings.replace("BODY", body)
        val parsed = Parser(Lexer(text).lex()).parse()
        if (!parsed.ok) return parsed.errors.map { "parse: ${it.message}" }
        return Lower(catalog, source = GraphSource.of(emptyList())).lower(parsed.program).errors.map { it.message }
    }

    /**
     * Named arguments are the EXTENSION spelling and a function value refuses them, so which one was
     * chosen is readable from whether the named form is an error — no need to read the graph.
     */
    @Test
    fun `the field wins on a bare head`() {
        assertTrue(
            errorsInBoth("    say(message: Impl.at(n: 4))").any { "positional" in it },
            "the field should have won",
        )
        assertEquals(emptyList(), errorsInBoth("    say(message: Impl.at(4))"))
    }

    @Test
    fun `the field wins through a chain too`() {
        assertTrue(
            errorsInBoth("    val r = Row { impl: Impl }\n    say(message: r.impl.at(n: 4))")
                .any { "positional" in it },
            "the field should have won",
        )
        assertEquals(
            emptyList(),
            errorsInBoth("    val r = Row { impl: Impl }\n    say(message: r.impl.at(4))"),
        )
    }

    /** ...and an extension the record has no field for still resolves through a chain. */
    @Test
    fun `an extension still resolves through a chain`() {
        assertEquals(
            emptyList(),
            errorsInBoth("    val r = Row { impl: Impl }\n    say(message: r.impl.other(n: 4))"),
        )
    }
}
