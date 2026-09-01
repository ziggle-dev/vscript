package dev.ziggle.vscript.domain

import dev.ziggle.vscript.model.HostEnums
import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.Literals
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.canConnect
import dev.ziggle.vscript.nodes.NodeLibrary
import dev.ziggle.vscript.nodes.Vs
import dev.ziggle.vscript.nodes.library
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.natives
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NominalTypeTest {

    private class Handle(val id: Int)

    private val widget = HostRecord("Widget", listOf(
        HostField("id", TypeRef(PinType.INT)) { (it as Handle).id.toLong() },
    ))
    private val gadget = HostRecord("Gadget", listOf(
        HostField("id", TypeRef(PinType.INT)) { (it as Handle).id.toLong() },
    ))

    private val lib = library("probe") {
        record(widget); record(gadget)
        func("aWidget") { val o = result("W", Vs.record<Handle>(widget)); query { o.set(Handle(7)) } }
        func("widgetId") {
            val w = param("W", Vs.record<Handle>(widget))
            val o = result("Id", Vs.int); query { o.set((w()?.id ?: 0).toLong()) }
        }
        func("gadgetId") {
            val g = param("G", Vs.record<Handle>(gadget))
            val o = result("Id", Vs.int); query { o.set((g()?.id ?: 0).toLong()) }
        }
    }

    private lateinit var catalog: NodeCatalog

    @BeforeTest fun setUp() {
        HostRecords.reset(); HostEnums.reset()
        val c = lib
        catalog = NodeCatalog(NodeLibrary(c.defs, enums = c.enums, records = c.records).descriptors)
    }
    @AfterTest fun tearDown() { HostRecords.reset(); HostEnums.reset() }

    private fun compiles(src: String): String {
        val r = TextFrontEnd(catalog.natives()).read("graph \"p\"\n\non start {\n$src\n}\n")
        return if (r.ok) "OK" else r.errors.joinToString { it.message }
    }

    @Test
    fun `a registered type resolves by name, like a builtin`() {
        // What a persisted document and a script both do: name it. `builtin` is null because nothing in
        // the language declares it -- which is the property being demonstrated, not a shortfall.
        assertEquals("Widget", TypeRef.named("Widget").name)
        assertNull(TypeRef.named("Widget").builtin, "the language should know nothing about a Widget")
    }

    @Test
    fun `a registered type accepts itself and refuses its neighbour, exactly as the builtins do`() {
        assertTrue(canConnect(widget.type, widget.type))
        assertFalse(canConnect(gadget.type, widget.type), "a Gadget is not a Widget")
        // The builtin's own answer, for comparison. This is the cross-wiring PinType's KDoc defends
        // -- and a registered type refuses it the same way, without the language knowing what an item is.
        assertFalse(canConnect(TypeRef.named("Npc"), TypeRef.named("Item")), "an npc id is not an item id")
    }

    @Test
    fun `neither a registered type nor a builtin accepts a bare INT`() {
        assertFalse(canConnect(TypeRef(PinType.INT), widget.type))
        // Identical -- so removing ITEM from the language does not make `item: 1621` any worse than it
        // already is. It is rejected today, by the builtin, which is the IDE false positive.
        assertFalse(canConnect(TypeRef(PinType.INT), TypeRef.named("Item")))
    }

    /**
     * Does it matter whether a name is resolved BEFORE its domain registers?
     *
     * The worry was corpus-wide and silent: `TypeRef.named` resolves through `byName`, which is built
     * statically from the builtins at class-init, while host records live in a registry populated at
     * startup. If the two disagreed, a document read too early would resolve `Tile` to something that
     * merely looked like the record, and nothing would say so.
     *
     * They cannot disagree, because [TypeRef.equals] compares the NAME and not the builtin. `named("X")`
     * yields the same structural value whether or not anything has registered `X`. Registration decides
     * what a type can DO — its fields, its completion, its manifest entry — never what it IS.
     */
    /**
     * A nominal type over something simpler parses a literal as that simpler thing.
     *
     * ### Found live, not by a test
     *
     * `graph set <node> Value 995` on an item pin came back **null** after `ITEM` stopped being a
     * builtin — and null means "leave the pin alone", so the literal silently kept its default. Nothing
     * threw, nothing logged, and the wrongness would have surfaced somewhere else entirely. Every unit
     * test passed; it took driving the running client to see it.
     *
     * The fix is the same `over` the JSON reader needed, which is the tell that it was one missing
     * concept rather than two bugs: whatever a host type is represented AS is what a written form of it
     * reads as.
     */
    @Test
    fun `a nominal type over INT parses its literal as an INT`() {
        HostRecords.reset()
        val ticket = HostRecord("Ticket", emptyList(), over = TypeRef(PinType.INT))
        NodeLibrary(emptyList(), records = listOf(ticket))
        assertEquals(995, Literals.of(ticket.type, "995"), "a literal of a nominal INT is that INT")
        assertNull(Literals.of(TypeRef.named("NothingDeclaresThis"), "995"),
            "and a type nothing declares still parses to nothing")
    }

    @Test
    fun `a name resolves to the same type before and after its domain registers`() {
        HostRecords.reset()
        val beforeRegistration = TypeRef.named("Widget")
        NodeLibrary(emptyList(), records = listOf(widget))
        val afterRegistration = TypeRef.named("Widget")
        assertEquals(beforeRegistration, afterRegistration, "identity must not depend on registration order")
        assertEquals(widget.type, beforeRegistration, "and both must equal the record's own type")
    }

    @Test
    fun `what registration decides is what a type can DO, not what it is`() {
        HostRecords.reset()
        assertNull(HostRecords.of("Widget"), "unregistered: no fields to read")
        NodeLibrary(emptyList(), records = listOf(widget))
        assertEquals(listOf("id"), HostRecords.of("Widget")?.fields?.map { it.name })
    }

    @Test
    fun `scripts type-check against a registered type`() {
        assertEquals("OK", compiles("    val n = widgetId(w: aWidget())"))
        assertEquals("OK", compiles("    val w: Widget = aWidget()\n    val n = widgetId(w: w)"),
            "a script may declare a variable of a type only the domain knows")
        assertTrue("is Gadget but is being given Widget" in compiles("    val n = gadgetId(g: aWidget())"),
            "and the mistake is reported in the domain's own words")
    }
}
