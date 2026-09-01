package dev.ziggle.vscript.manifest

import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.lang.Names
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.HostKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest carries a catalogue, and carries it *faithfully*.
 *
 * The assertions are about SIGNATURES rather than about JSON. A manifest that parsed cleanly and lost a
 * pin's type would produce an IDE that completes an argument list with the wrong editor and accepts a wire
 * the compiler then refuses — wrong in a way no amount of well-formed JSON would reveal.
 */
class CatalogManifestTest {

    private val query = hostNode(
        "names.nearestObjectNamed", "names.nearestObjectNamed", NodeKind.PURE,
        inputs = listOf(PinSpec("Name", PinType.STRING, default = "", doc = "Exact, case-insensitive.")),
        outputs = listOf(PinSpec("Object", TypeRef.named("EntityRef"))),
    )

    private val action = hostNode(
        "inventory.dropAny", "inventory.dropAny", NodeKind.IMPURE, hostKind = HostKind.BLOCKING,
        inputs = listOf(
            PinSpec("Exec", PinType.EXEC),
            PinSpec("Items", TypeRef.list(TypeRef.named("Item")), doc = "Ids to drop, in order."),
            PinSpec("Mode", PinType.ENUM, options = listOf("All", "First")),
        ),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )

    private val catalog = NodeCatalog(listOf(query, action))

    @Test
    fun `a catalogue survives the round trip`() {
        val read = CatalogManifest.fromJson(CatalogManifest.toJson(catalog))
        val back = read.toCatalog()

        // Every non-builtin node came back. (Builtins are re-added by NodeCatalog's own constructor, so
        // toCatalog filters them out rather than registering each one twice.)
        assertNotNull(back["names.nearestObjectNamed"])
        assertNotNull(back["inventory.dropAny"])
        assertEquals(catalog.all.size, back.all.size, "the round trip changed how many node types exist")
    }

    @Test
    fun `pin types survive, including what a list holds`() {
        val back = CatalogManifest.fromJson(CatalogManifest.toJson(catalog)).toCatalog()
        val items = back["inventory.dropAny"]!!.input("Items")!!
        // The element type is the part that goes missing silently: `canConnect` compares it, so a List that
        // came back untyped would accept a list of tiles into a list-of-items pin.
        assertEquals(PinType.LIST, items.type.builtin)
        // The element type comes back as the NAME the domain registered, not a builtin: the language has
        // no item type of its own any more.
        assertEquals(TypeRef.named("Item"), items.type.of)
    }

    @Test
    fun `kind, host kind and options survive`() {
        val back = CatalogManifest.fromJson(CatalogManifest.toJson(catalog)).toCatalog()
        val drop = back["inventory.dropAny"]!!
        // HostKind decides CALL vs ACT — an action that came back INLINE would run on the client thread
        // and trip the tick watchdog.
        assertEquals(HostKind.BLOCKING, drop.hostKind)
        assertEquals(NodeKind.IMPURE, drop.kind)
        assertEquals(listOf("All", "First"), drop.input("Mode")!!.options)
        assertEquals(NodeKind.PURE, back["names.nearestObjectNamed"]!!.kind)
    }

    @Test
    fun `pin documentation survives — it is the whole point of shipping this`() {
        val back = CatalogManifest.fromJson(CatalogManifest.toJson(catalog)).toCatalog()
        assertEquals("Exact, case-insensitive.", back["names.nearestObjectNamed"]!!.input("Name")!!.doc)
        assertEquals("Ids to drop, in order.", back["inventory.dropAny"]!!.input("Items")!!.doc)
    }

    @Test
    fun `the text spelling is written down, not left to be recomputed`() {
        val read = CatalogManifest.fromJson(CatalogManifest.toJson(catalog))
        val names = Names(catalog)
        // Every callable node's spelling matches what the parser would actually accept. A consumer that
        // derived it from the type string would offer completions the parser then rejects.
        for (d in names.callable) {
            assertEquals(names.textName(d.type), read.text[d.type], "wrong spelling for ${d.type}")
        }
        assertEquals("nearestObjectNamed", read.text["names.nearestObjectNamed"])
    }

    @Test
    fun `a syntactic node gets no spelling`() {
        val read = CatalogManifest.fromJson(CatalogManifest.toJson(catalog))
        // A Branch is `if`, and a comment box is a canvas decoration. Neither is callable, so neither
        // belongs in a completion list.
        assertNull(read.text[BuiltinNodes.BRANCH])
        assertNull(read.text[BuiltinNodes.COMMENT])
    }

    @Test
    fun `meta is carried through untouched`() {
        val json = CatalogManifest.toJson(catalog, mapOf("client" to "1.4.2", "language" to "1.0.0"))
        val read = CatalogManifest.fromJson(json)
        assertEquals("1.4.2", read.meta["client"])
        assertEquals("1.0.0", read.meta["language"])
    }

    @Test
    fun `a newer schema is refused rather than half-read`() {
        val json = CatalogManifest.toJson(catalog).replace("\"schema\": 1", "\"schema\": 99")
        val e = runCatching { CatalogManifest.fromJson(json) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "expected a refusal, got $e")
    }

    @Test
    fun `the type an extension is written on survives`() {
        // **Not recoverable from the pins.** `item.isValid` and a plain `isValid(value: Item)` have
        // identical inputs; the receiver is the only thing that makes the first callable as
        // `id.isValid()`. Dropped, every host extension came back as an ordinary function — and the
        // symptom was a manifest-loaded catalogue reporting `Item has no 'isValid'` about a script the
        // live client compiles, which reads as a missing node rather than a lost field. It took every
        // test document downstream of `core/items` with it.
        val ext = NodeDescriptor(
            type = "item.isValid",
            title = "Item Is Valid",
            category = "Items",
            kind = NodeKind.PURE,
            inputs = listOf(PinSpec("Value", TypeRef.named("Item"))),
            outputs = listOf(PinSpec("Valid", PinType.BOOL)),
            host = "item.isValid",
            receiver = TypeRef.named("Item"),
        )
        val one = NodeCatalog(listOf(ext))
        val back = CatalogManifest.fromJson(CatalogManifest.toJson(one)).toCatalog()
        assertEquals(TypeRef.named("Item"), back["item.isValid"]!!.receiver)
        // And a node that is NOT an extension must not gain one — an absent field is absent, not empty.
        val plain = CatalogManifest.fromJson(CatalogManifest.toJson(catalog)).toCatalog()
        assertNull(plain["names.nearestObjectNamed"]!!.receiver)
    }

    @Test
    fun `the builtins alone round-trip`() {
        // The commonest real case: a plugin with no client, reading a manifest that is mostly builtins.
        val plain = NodeCatalog()
        val back = CatalogManifest.fromJson(CatalogManifest.toJson(plain)).toCatalog()
        assertEquals(plain.types.size, back.types.size)
        for (t in plain.types) {
            val a = plain[t]!!
            val b = back[t]!!
            assertEquals(a.kind, b.kind, "$t changed kind")
            assertEquals(a.inputs.map { it.name }, b.inputs.map { it.name }, "$t changed inputs")
            assertEquals(a.outputs.map { it.name }, b.outputs.map { it.name }, "$t changed outputs")
        }
    }
}
