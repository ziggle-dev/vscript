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
 * The two directions between an enum member and its name.
 *
 * A member IS its name at run time and always was, but there was no way to SAY so: an enum member could
 * not be logged, joined into a message or written to a file without declaring a column that duplicated
 * what the member already was. And there was no way back from a name that arrived from outside — a chat
 * line, a saved file, a config field — so anything that had to survive a round trip stayed a `String` and
 * gave up the checking.
 */
class EnumNameAndOfTest {

    private val skillEnum = dev.ziggle.vscript.model.HostEnum("Skill", listOf("Attack", "Farming", "Mining"))

    @kotlin.test.BeforeTest
    fun registerSkill() {
        dev.ziggle.vscript.model.HostEnums.register(skillEnum)
    }

    private val natives = NativeTable(
        listOf(NativeFn("log", listOf(NativeParam("message", TypeRef(PinType.STRING))), results = emptyList())),
    )

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
            .register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        val r = TextFrontEnd(natives).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    private fun errors(src: String) = TextFrontEnd(natives).read(src).errors.map { it.message }

    @Test
    fun `a member answers its own name`() {
        assertEquals(
            listOf("Chop", "phase: Chop"),
            run(
                """
                graph "p"

                enum Phase { Chop, Burn }

                on start {
                    log(message: Phase.Chop.name)
                    log(message: "phase: " + Phase.Chop.name)
                }
                """.trimIndent(),
            ),
        )
    }

    /** A declared column called `name` still wins — nothing that resolves today moves. */
    @Test
    fun `a declared name column beats the member's own name`() {
        assertEquals(
            listOf("chopping"),
            run(
                """
                graph "p"

                enum Phase(name: String) { Chop("chopping"), Burn("burning") }

                on start { log(message: Phase.Chop.name) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a name reads back as a member, and is optional`() {
        assertEquals(
            listOf("Burn", "none"),
            run(
                """
                graph "p"

                enum Phase { Chop, Burn }

                on start {
                    if val p = Phase.of("Burn") { log(message: p.name) }
                    if val q = Phase.of("Nonesuch") { log(message: q.name) } else { log(message: "none") }
                }
                """.trimIndent(),
            ),
        )
    }

    /** A name off a wire rarely carries the enum's capitalisation, and answering the declared spelling
     *  is what makes the result indistinguishable from one written by hand. */
    @Test
    fun `matching ignores case and answers the declared spelling`() {
        assertEquals(
            listOf("true"),
            run(
                """
                graph "p"

                enum Phase { Chop, Burn }

                on start {
                    if val p = Phase.of("cHoP") { log(message: "" + (p == Phase.Chop)) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `of is refused without a name`() {
        assertTrue(
            errors(
                """
                graph "p"

                enum Phase { Chop, Burn }

                on start { if val p = Phase.of() { log(message: p.name) } }
                """.trimIndent(),
            ).isNotEmpty(),
        )
    }

    /** The host `Skill` enum gets both, which is the case that motivated them. */
    @Test
    fun `a host enum has name and of as well`() {
        assertEquals(
            listOf("Farming", "true"),
            run(
                """
                graph "p"

                on start {
                    log(message: Skill.Farming.name)
                    if val s = Skill.of("farming") { log(message: "" + (s == Skill.Farming)) }
                }
                """.trimIndent(),
            ),
        )
    }
}
