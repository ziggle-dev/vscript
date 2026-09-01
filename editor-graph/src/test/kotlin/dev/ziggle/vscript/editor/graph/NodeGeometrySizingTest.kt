package dev.ziggle.vscript.editor.graph

import dev.ziggle.vscript.editor.host.EditorHost
import dev.ziggle.vscript.editor.host.ValueCatalog
import dev.ziggle.vscript.editor.host.ValueCatalogs
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Node measurement rules that do not need a font. */
class NodeGeometrySizingTest {

    @Test
    fun `editor width follows the widget the pin actually draws`() {
        // The bug this pins down: a boolean reserved a number field's width, which both inflated the node
        // and pushed its right-aligned editor onto the output label.
        assertEquals(NodeGeometry.TOGGLE_W, NodeGeometry.editorWidth(PinType.BOOL))
        assertEquals(NodeGeometry.NUMBER_W, NodeGeometry.editorWidth(PinType.INT))
        assertEquals(NodeGeometry.STRING_W, NodeGeometry.editorWidth(PinType.STRING))
        assertTrue(NodeGeometry.TOGGLE_W < NodeGeometry.NUMBER_W)
    }

    @Test
    fun `types with no inline widget reserve nothing`() {
        // Exec is control flow; a List is built by the graph; an Entity only exists while one runs. None of
        // the three is something an author can type, so none of them reserves space for a field.
        for (t in listOf(PinType.EXEC, PinType.LIST, PinType.MAP)) {
            assertEquals(0f, NodeGeometry.editorWidth(t), "$t should reserve no editor space")
        }
    }

    /**
     * A type with a CATALOGUE is sized for a name, not an id.
     *
     * "Dragon scimitar" in a 56px number box is not a value anyone can read. This used to be asserted of
     * `PinType.ITEM`, `NPC` and `OBJECT`; those are the node pack's types now, and the width comes from
     * what the host says it can search rather than from a builtin — so the rule is stated the way the
     * canvas actually decides it.
     */
    @Test
    fun `a type with a searchable catalogue is sized for a NAME`() {
        val searchable = TypeRef.named("Widget")
        EditorHost.values = ValueCatalogs { if (it == searchable) EmptyCatalog else null }
        try {
            assertEquals(
                NodeGeometry.STRING_W,
                NodeGeometry.editorWidth(searchable),
                "a type the host can search should be sized for a name",
            )
            assertEquals(
                0f,
                NodeGeometry.editorWidth(TypeRef.named("NothingSearchesThis")),
                "and one it cannot is a handle",
            )
        } finally {
            EditorHost.values = ValueCatalogs.NONE
        }
        // **A small record of numbers is three boxes, and it is asserted GENERICALLY on purpose.**
        //
        // This used to name the node pack's `Tile`, which was fine while this test lived in the client and
        // wrong the moment the canvas became a module that must open against any domain. `deriveEditor`
        // never knew about tiles anyway — it reaches the answer from the SHAPE, which is what let the
        // `PinType.TILE -> Editor.FIELDS` arm go. So the test declares a shape and checks the answer.
        val threeNumbers = dev.ziggle.vscript.model.HostRecord(
            "Spot",
            listOf(
                dev.ziggle.vscript.model.HostField("x", TypeRef(PinType.INT)),
                dev.ziggle.vscript.model.HostField("y", TypeRef(PinType.INT)),
                dev.ziggle.vscript.model.HostField("plane", TypeRef(PinType.INT)),
            ),
            isData = true,
        )
        dev.ziggle.vscript.model.HostRecords.register(threeNumbers)
        try {
            assertEquals(
                NodeGeometry.TILE_W,
                NodeGeometry.editorWidth(threeNumbers.type),
                "a record of three scalars is three boxes",
            )
        } finally {
            dev.ziggle.vscript.model.HostRecords.reset()
        }
    }

    private object EmptyCatalog : ValueCatalog {
        override fun search(query: String, limit: Int) = emptyList<ValueCatalog.Entry>()
        override fun browse(limit: Int) = emptyList<ValueCatalog.Entry>()
        override fun labelOf(value: Any?): String? = null
        override fun icon(value: Any?) = null
    }

    @Test
    fun `a wildcard gets a text field, because that is where a Literal is typed`() {
        // It has no widget of its own, but a Literal node IS its value — with nothing to type into, the
        // node was decorative. The field takes text and the value's type follows what was written.
        assertEquals(NodeGeometry.STRING_W, NodeGeometry.editorWidth(PinType.WILDCARD))
    }

    @Test
    fun `an editable output reserves room, an ordinary one does not`() {
        val literal = dev.ziggle.vscript.model.PinSpec("Value", PinType.WILDCARD, editable = true)
        val plain = dev.ziggle.vscript.model.PinSpec("Result", PinType.INT)
        assertTrue(NodeGeometry.editorWidth(literal) > 0f)
        // editorWidth is about the TYPE; whether an output actually gets a field is the `editable` flag,
        // which NodeGeometry.of consults. Both halves have to agree or a node is sized for a field it
        // never draws.
        assertFalse(plain.editable)
        assertTrue(literal.editable)
    }
}
