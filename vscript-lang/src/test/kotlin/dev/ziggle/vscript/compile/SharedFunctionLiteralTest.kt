package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Lexer
import dev.ziggle.vscript.lang.Lower
import dev.ziggle.vscript.lang.Parser
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.Chunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One function, called from two entries, retuned once.
 *
 * **`subChunkFor` memoises per calling context, not per program.** A helper called from `on start` and from
 * `on tick` is therefore compiled twice, into two chunks, with two constant pools — so an authored literal
 * inside it gets a slot in each. The document says one thing and the run holds two copies of it.
 *
 * That is invisible until something edits the literal while the script is running, which is exactly what
 * the editor does. `ScriptRuntime.setLiteral` is:
 *
 *     liveChunks.any { it.setLiteral(nodeId, pin, value) }
 *
 * and `any` **short-circuits**, so the first chunk that owns the pin is retuned and every other copy keeps
 * the old value. From the outside that is a number you changed that only half took effect — on a per-tick
 * handler, against a per-start one that disagrees with it.
 *
 * This test asserts the property the LINKER makes true: a literal edited once reaches every copy, because
 * after linking there is only one. It is expected to FAIL against the pre-linker compiler, and that failure
 * is the point — see `docs/LINKER_PLAN.md` §2.2.
 */
class SharedFunctionLiteralTest {

    private val sayNode = hostNode(
        "test.say", "say", NodeKind.IMPURE,
        inputs = listOf(PinSpec("Exec", PinType.EXEC), PinSpec("Message", PinType.WILDCARD)),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(sayNode))

    private fun graphOf(src: String): Graph {
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        assertTrue(low.ok, "lower: ${low.errors}")
        assertEquals(emptyList(), Validator(catalog).validate(low.graph).errors(), "did not validate")
        return low.graph
    }

    /**
     * Every DISTINCT chunk reachable from [roots] — the entry chunks, plus the program they call into.
     *
     * Distinct by identity, and that is what makes the assertion below mean the same thing before and after
     * the linker. Afterwards every entry shares one program, so counting per entry would read the same slot
     * twice and report two happy copies where there is one — a test that passes for the wrong reason.
     * `Chunk` declares no `equals`, so a `LinkedHashSet` already dedupes by identity.
     */
    private fun distinct(roots: List<Chunk>): List<Chunk> {
        val out = LinkedHashSet<Chunk>()
        for (r in roots) {
            out += r
            out += r.program
        }
        return out.toList()
    }

    /** The value [chunk] holds for [key], or null when it does not own that pin. */
    private fun valueOf(chunk: Chunk, key: String): Any? =
        chunk.literalSlots[key]?.let { chunk.constants[it] }

    @Test
    fun `a literal inside a shared function is retuned in every copy`() {
        val g = graphOf(
            """
            graph "shared"

            export fn helper() {
                say(1500)
            }

            on start {
                helper()
            }

            on tick {
                helper()
            }
            """.trimIndent()
        )

        val compiler = GraphCompiler(catalog, debug = true)
        // Exactly how ScriptRuntime assembles `liveChunks`: every group's entries, in one list — and, as
        // it must, sharing ONE program across the groups so a helper called from two of them is one
        // function. Compiled with separate tables this is two copies and the assertion below fails.
        val table = dev.ziggle.vscript.vm.ProgramBuilder()
        val live = compiler.compileEntries(g, EntryGroup.START, table).entries.map { it.chunk } +
            compiler.compileEntries(g, EntryGroup.TICK, table).entries.map { it.chunk }

        val say = g.nodes.single { it.type == "test.say" }
        val key = "${say.id}/Message"

        // Every chunk that owns a copy of this pin. One after the linker, two before it — the assertion
        // below is deliberately indifferent to which, because what is being pinned is that no copy is left
        // behind, not how many there are.
        val holders = distinct(live).filter { key in it.literalSlots }
        assertTrue(holders.isNotEmpty(), "the literal has to live somewhere or this test proves nothing")
        assertTrue(
            holders.all { valueOf(it, key) == 1500 },
            "premise: every copy starts at the authored value",
        )

        // One edit, made exactly the way ScriptRuntime.setLiteral makes it — note the short-circuiting
        // `any`, which is the half of the bug that hides the other half.
        live.any { it.setLiteral(say.id, "Message", 99) }

        val stale = holders.filter { valueOf(it, key) != 99 }
        assertEquals(
            emptyList(), stale.map { it.name },
            "a literal edited once must leave no copy holding the old value",
        )
    }
}
