package dev.ziggle.vscript.manifest

import dev.ziggle.vscript.model.HostEnum
import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A manifest round-trips the HOST TYPES, not only the nodes.
 *
 * The manifest wrote `enums` and `records` and read back neither: `toCatalog()` rebuilt the node table and
 * nothing else, and the `installEnums()` that was supposed to do the other half had **no callers at all**.
 * So a catalogue read back from JSON had pins declared `Skill` and `ItemRef` while the registries had never
 * heard of either.
 *
 * What that looked like was not a missing enum. It was `ItemRef has no fields` reported against documents
 * that are correct, and `benchReal` — the one live consumer — loading 21 of the corpus's 79 documents and
 * counting the other 58 as "skipped", which for a benchmark is a plausible-looking line of output.
 *
 * The registries are global, so each test puts them back.
 */
class ManifestHostTypesTest {

    @BeforeTest fun reset() { HostEnums.reset(); HostRecords.reset() }
    @AfterTest fun restore() { HostEnums.reset(); HostRecords.reset() }

    private val ref = TypeRef.named("ProbeRef")

    private fun register() {
        HostEnums.register(HostEnum("ProbeKind", listOf("One", "Two"), "a probe kind"))
        HostRecords.register(
            HostRecord(
                "ProbeRef",
                listOf(
                    HostField("id", TypeRef(PinType.INT), "which one") { 7 },
                    HostField("name", TypeRef(PinType.STRING), "what it is called") { "seven" },
                ),
                "a probe reference",
                type = ref,
                widensTo = TypeRef.named("ProbeBase"),
            )
        )
    }

    /** Read back through `toCatalog`, which is the call every consumer already makes. */
    private fun roundTrip() {
        register()
        val json = CatalogManifest.toJson(NodeCatalog())
        HostEnums.reset(); HostRecords.reset()
        CatalogManifest.fromJson(json).toCatalog()
    }

    @Test
    fun `a host record comes back with its fields`() {
        roundTrip()
        val r = assertNotNull(HostRecords.of(ref), "ProbeRef should be registered after a round trip")
        assertEquals(listOf("id", "name"), r.fields.map { it.name })
        assertEquals(TypeRef(PinType.INT), r.field("id")?.type)
        assertEquals("what it is called", r.field("name")?.describe)
    }

    /** The one bit of subtyping the language has, and a completion that filters by type needs it. */
    @Test
    fun `widensTo survives the trip`() {
        roundTrip()
        assertEquals(TypeRef.named("ProbeBase"), HostRecords.of(ref)?.widensTo)
    }

    @Test
    fun `a host enum comes back with its members`() {
        roundTrip()
        val e = assertNotNull(HostEnums.of("ProbeKind"))
        assertEquals(listOf("One", "Two"), e.members)
    }

    /**
     * A field read back from a manifest TYPES and refuses to RUN.
     *
     * The shape is all a manifest carries — there is no client on the other side of a JSON file to read a
     * live scene lookup off. Answering zero would be the wrong kind of quiet.
     */
    @Test
    fun `a manifest field has no implementation and says so`() {
        roundTrip()
        val f = assertNotNull(HostRecords.of(ref)?.field("id"))
        val e = assertFailsWith<IllegalStateException> { f.get(Any()) }
        assertTrue("manifest" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("ProbeRef.id" in e.message.orEmpty(), e.message.orEmpty())
    }

    /** Reading one twice is reading it once — both registries replace by name. */
    @Test
    fun `installing twice is idempotent`() {
        register()
        val json = CatalogManifest.toJson(NodeCatalog())
        HostEnums.reset(); HostRecords.reset()
        val m = CatalogManifest.fromJson(json)
        m.install()
        m.install()
        assertEquals(1, HostRecords.all.count { it.name == "ProbeRef" })
        assertEquals(1, HostEnums.all.count { it.name == "ProbeKind" })
    }
}
