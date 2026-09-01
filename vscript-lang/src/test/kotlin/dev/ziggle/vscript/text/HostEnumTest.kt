package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.nodes.BuiltinHosts
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.drive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A HOST-declared enum is an ordinary enum — `Tab.Inventory` where `Phase.Chop` would be.
 *
 * **The point of the feature is that there is nothing new to learn**, so this is mostly a test that the
 * existing enum behaviour reaches a type the client declared rather than the document: member access is
 * checked, a typo is a compile error naming the alternatives, `values()` is a list, and the value that
 * arrives at a native is the member's NAME — the same representation a document enum has always used.
 */
class HostEnumTest {

    private val STRING = TypeRef(PinType.STRING)
    private val TAB = TypeRef.named("Tab")

    /** The language declares no enums of its own, so a test that wants one declares it. */
    private val skillEnum = dev.ziggle.vscript.model.HostEnum("Skill", listOf("Attack", "Mining", "Magic"))
    private val SKILL = skillEnum.type

    private val natives = NativeTable(
        listOf(
            NativeFn("log", listOf(NativeParam("message", STRING)), results = emptyList()),
            // Typed as the enum, which is the whole claim: a pin can name a host enum and a member flows in.
            NativeFn("openTab", listOf(NativeParam("tab", TAB)), results = emptyList()),
            // A SECOND host enum, so the tests cover more than one being registered at a time.
            NativeFn("train", listOf(NativeParam("skill", SKILL)), results = emptyList()),
        ),
    )

    @BeforeTest
    fun register() {
        HostEnums.register(HostEnum("Tab", listOf("Combat", "Inventory", "Equipment"), "a sidebar tab"))
        HostEnums.register(skillEnum)
    }

    /** The registry is a singleton, so a library registered here must not leak into the next test class. */
    @AfterTest
    fun clear() = HostEnums.reset()

    private fun read(src: String) = TextFrontEnd(natives).read(src)

    private fun run(src: String): List<String> {
        val said = ArrayList<String>()
        val hosts = BuiltinHosts.registry()
        hosts.register("log", HostKind.INLINE, arity = 1, results = 0) { a -> said += a[0].toString(); null }
        hosts.register("openTab", HostKind.INLINE, arity = 1, results = 0) { a -> said += "tab:" + a[0]; null }
        hosts.register("train", HostKind.INLINE, arity = 1, results = 0) { a -> said += "skill:" + a[0]; null }
        val r = read(src)
        val chunk = r.chunk
            ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return said
    }

    /** A member reaches the native as its NAME — the representation every other enum already uses. */
    @Test
    fun `a member flows into a pin typed as the host enum`() {
        assertEquals(listOf("tab:Inventory"), run("on start { openTab(tab: Tab.Inventory) }"))
    }

    /**
     * A member is spelled EXACTLY as declared — and that is the document rule, inherited rather than
     * chosen.
     *
     * `Resolver.member` tests `members.contains(…)`, so `Phase.chop` has never resolved either. Worth
     * pinning here because the enum's NAME is matched loosely (`HostEnums.key` ignores case and
     * underscores) and the asymmetry would otherwise look like an oversight: a name has to survive being
     * written `ItemRef` for a constant called `ITEM_REF`, and a member has an author who chose its
     * spelling.
     */
    @Test
    fun `a member must be spelled as declared`() {
        val r = read("on start { openTab(tab: Tab.INVENTORY) }")
        val said = r.errors.joinToString { it.message }
        assertTrue("INVENTORY" in said, "expected the wrong-case member to be refused, got: $said")
    }

    /** `values()` needed no special case: it reads the same lookup member access does. */
    @Test
    fun `values gives every member in declaration order`() {
        assertEquals(
            listOf("Combat", "Inventory", "Equipment"),
            run("on start { for t in Tab.values() { log(message: \"\" + t) } }"),
        )
    }

    /** The error a typo gets is the enum's own, listing what it does have. */
    @Test
    fun `an unknown member is refused by name`() {
        val r = read("on start { openTab(tab: Tab.Inventry) }")
        val said = r.errors.joinToString { it.message }
        assertTrue("Tab" in said && "Inventry" in said, "expected a member error naming the enum, got: $said")
    }

    /**
     * A document that declares the name WINS.
     *
     * The precedence `Types` gives a document redefining a built-in, and the reason a host enum can be
     * added to the catalogue without breaking a script that already uses the name for something else.
     */
    @Test
    fun `a document's own enum shadows the host's`() {
        val said = run(
            """
            enum Tab { Left, Right }
            on start { for t in Tab.values() { log(message: "" + t) } }
            """.trimIndent(),
        )
        assertEquals(listOf("Left", "Right"), said)
    }

    /**
     * `Skill` is registered by the language itself, and it keeps the pin type the catalogue already uses.
     *
     * The half that would silently not work if [HostEnum.type] defaulted: a nominal `Skill` would resolve,
     * compile, and then refuse to wire into every skill pin in the catalogue.
     */
    @Test
    fun `Skill resolves and still fits a SKILL pin`() {
        assertEquals(listOf("skill:Mining"), run("on start { train(skill: Skill.Mining) }"))
    }

    /** And a string is still a string — nothing that already compiled has moved. */
    @Test
    fun `a skill pin takes the enum, and no longer takes a bare string`() {
        assertEquals(listOf("skill:Mining"), run("on start { train(skill: Skill.Mining) }"))
        // The bridge that let any STRING through is gone, which is the whole point of making `Skill` a
        // type: `train(skill: "Attak")` used to typecheck and answer nothing at run time.
        assertTrue(
            !read("on start { train(skill: \"Mining\") }").ok,
            "a bare string must no longer pass for a skill",
        )
    }
}
