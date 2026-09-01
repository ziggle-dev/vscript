package dev.ziggle.vscript.domain

import dev.ziggle.vscript.manifest.CatalogManifest
import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.nodes.NodeLibrary
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.vm.HostRegistry
import dev.ziggle.vscript.vm.drive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class GreenhouseDomainTest {

    private lateinit var library: NodeLibrary
    private lateinit var catalog: NodeCatalog

    @BeforeTest
    fun install() {
        HostRecords.reset()
        HostEnums.reset()
        Greenhouse.reset()
        // A Contribution is what a domain produces; the host merges any number of them into one library.
        val c = Greenhouse.library + Greenhouse.numbers
        library = NodeLibrary(c.defs, enums = c.enums, records = c.records)
        catalog = NodeCatalog(library.descriptors)
    }

    @AfterTest
    fun clear() {
        HostRecords.reset()
        HostEnums.reset()
    }

    /**
     * Compile and run [src] against the greenhouse, and return what it recorded.
     *
     * Output goes through the domain's own `say` node rather than a native registered here. The first
     * version registered a `log` and collided with the builtins' own -- which is itself a small proof
     * that the builtin surface is real and shared, and a good reason for a domain to bring its own verbs.
     */
    private fun run(src: String): List<String> {
        val hosts = HostRegistry()
        library.install(hosts)
        val r = TextFrontEnd(catalog.natives()).read(src)
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { "${it.span} ${it.message}" })
        drive(chunk, hosts)
        return Greenhouse.said.toList()
    }

    // ---- the domain reaches every layer ---------------------------------------------------------------

    @Test
    fun `a domain's nodes reach the catalogue`() {
        val types = catalog.types
        assertTrue("green.benches" in types, "expected the library's nodes in the catalogue, got $types")
        assertTrue("green.water" in types)
        assertTrue("green.waitForDry" in types)
    }

    @Test
    fun `a domain's enum and record are registered by construction, before any VM exists`() {
        // No install() has run in this test -- only the library was built. A headless manifest build does
        // exactly this, and if registration waited for install it would describe pins whose types it had
        // never heard of.
        assertTrue(HostEnums.all.any { it.name == "Season" }, "Season should be registered")
        assertTrue(HostRecords.all.any { it.name == "Bench" }, "Bench should be registered")
    }

    @Test
    fun `a script reads a host record's fields through the text front end`() {
        assertEquals(
            listOf("1", "40", "Spring"),
            run(
                """
                graph "g"

                on start {
                    val b = benches()[0]
                    say(text: "" + b.id)
                    say(text: "" + b.humidity)
                    say(text: "" + b.season)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a field read is live, not a snapshot`() {
        // The value in the register is the host's own object; a field read calls back into the host. So a
        // command that changes the world is visible through a reference taken before it ran.
        assertEquals(
            listOf("40", "55"),
            run(
                """
                graph "g"

                on start {
                    val b = benches()[0]
                    say(text: "" + b.humidity)
                    water(bench: b, amount: 15)
                    say(text: "" + b.humidity)
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(1), Greenhouse.watered)
    }

    @Test
    fun `a domain enum typechecks and round-trips`() {
        assertEquals(
            listOf("Summer"),
            run(
                """
                graph "g"

                on start {
                    say(text: seasonName(season: Season.Summer))
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A domain's own extension, declared through the authoring DSL and written on a value.
     *
     * This is the shape that replaces a language intrinsic: `n.benchAt()` is to a greenhouse what
     * `n.toItem()` is to a game, and neither belongs in a lexer.
     */
    @Test
    fun `a domain can declare a verb written on a value`() {
        assertEquals(
            listOf("40"),
            run(
                """
                graph "g"

                on start {
                    say(text: "" + 1.benchAt().humidity)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an extension name need only be unique per receiver`() {
        assertEquals(
            listOf("true", "true"),
            run(
                """
                graph "g"

                on start {
                    say(text: "" + 1.benchAt().isDry())
                    say(text: "" + 10.isDry())
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A cast costs nothing at run time — asserted on the bytecode, not assumed.
     *
     * The point of `cast()` is that a domain can own its nominal conversions without paying a host call
     * for each one. If that stopped being true it would still pass every behavioural test and simply be
     * slower, so the property is checked where it lives: in the emitted chunk.
     */
    @Test
    fun `a cast emits no call`() {
        val r = TextFrontEnd(catalog.natives()).read(
            """
            graph "g"

            on start {
                say(text: "" + 3.asBenchNumber())
            }
            """.trimIndent(),
        )
        val chunk = r.chunk ?: fail("did not compile: " + r.errors.joinToString { it.message })
        val text = dev.ziggle.vscript.vm.Disassembler.disassemble(chunk)
        assertTrue("asBenchNumber" !in text, "a cast should leave no trace in the bytecode:\n$text")
    }

    @Test
    fun `the manifest describes a domain the language has never heard of`() {
        val json = CatalogManifest.toJson(catalog, mapOf("domain" to "greenhouse"))
        val back = CatalogManifest.fromJson(json)
        val names = back.nodes.map { it.type }.toSet()
        assertTrue("green.water" in names, "the manifest should carry the domain's nodes, got $names")
        assertTrue("greenhouse" in json, "the manifest should carry its metadata")
    }

    // ---- what is still baked in --------------------------------------------------------------------

    /**
     * The game types are gone from [PinType].
     *
     * `ITEM`, `NPC` and `OBJECT` were nominal types over `INT`; the node pack declares them now, and
     * `NominalTypeTest` shows a registered type refuses the cross-wiring identically. `TILE` followed,
     * and it was the harder one — structured, with a literal form and a conversion at the graph's pins —
     * which is why it is named here rather than folded into the list: it is the case that proved a host
     * record can do everything a builtin did.
     *
     * `COLOR` is the last one left and is not a game type at all, which is a separate argument.
     */
    @Test
    fun `the language no longer ships the game types`() {
        val builtin = PinType.entries.map { it.name }.toSet()
        assertTrue(
            listOf("ITEM", "NPC", "OBJECT", "TILE").none { it in builtin },
            "these should have left PinType: $builtin",
        )
    }

    /**
     * The skill half of this is DONE — `Skill` is declared by the node pack now, and the language
     * registers no enums of its own. Asserted so it stays that way.
     */
    @Test
    fun `the language declares no host enums of its own`() {
        dev.ziggle.vscript.model.HostEnums.reset()
        assertTrue(
            dev.ziggle.vscript.model.HostEnums.all.isEmpty(),
            "a freshly reset registry should be empty: the language owns no vocabulary",
        )
    }
}
