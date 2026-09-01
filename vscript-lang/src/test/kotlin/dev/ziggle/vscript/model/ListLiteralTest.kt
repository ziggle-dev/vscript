package dev.ziggle.vscript.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The List node's pins, which come from the document rather than the catalog.
 *
 * Worth testing at this level because every surface reads them through [resolveNode] — the canvas draws
 * them, the validator checks wires against them, and the compiler emits one `APPEND` per slot. Get the
 * derivation wrong and all three go wrong together, in three different-looking ways.
 */
class ListLiteralTest {

    private val catalog = NodeCatalog()
    private val desc = catalog[BuiltinNodes.LITERAL_LIST]!!

    private fun resolve(literals: Map<String, Any?>): NodeDescriptor =
        resolveNode(Node(1, BuiltinNodes.LITERAL_LIST, literals = LinkedHashMap(literals)), desc, { null })

    @Test
    fun `count decides how many slots there are`() {
        val d = resolve(mapOf(BuiltinNodes.LIST_COUNT to 3))
        assertEquals(listOf("Of", "Count", "1", "2", "3"), d.inputs.map { it.name })
    }

    @Test
    fun `a fresh node has the two config pins and nothing else`() {
        assertEquals(listOf("Of", "Count"), resolve(emptyMap()).inputs.map { it.name })
    }

    @Test
    fun `every slot carries the chosen element type`() {
        for (info in BuiltinNodes.elementTypes) {
            val d = resolve(mapOf(BuiltinNodes.LIST_OF to info.name, BuiltinNodes.LIST_COUNT to 2))
            assertEquals(listOf(info.type, info.type), d.inputs.drop(2).map { it.type }, "slots for ${info.name}")
            assertEquals(info.type, d.output("Value")?.elementType, "element type for ${info.name}")
        }
    }

    /**
     * Every offered element type must be one the editor can actually draw a field for — a slot you can
     * neither type into nor recognise is worse than not offering the type at all.
     *
     * This is the registry's contract with the editor: `authorable` is the model's claim that a value can
     * be written by hand, and [ValueEditors] is where the claim is honoured. The two are separate files, so
     * a type registered with the flag set and no editor behind it would produce a slot that silently cannot
     * be filled — which is exactly the quiet kind of wrong this catches.
     */
    @Test
    fun `every authorable type has an editor`() {
        // The BUILT-IN types with an editor. A domain's own types are not here and cannot be: the editor
        // asks the host what it has a catalogue for (`ValueCatalogs`), and the language has no list of
        // them to check against. `Item`, `Npc` and `Object` used to be in this set as PinTypes; they are
        // the node pack's now.
        val editable = setOf(
            PinType.INT, PinType.FLOAT, PinType.STRING, PinType.BOOL,
            // COLOR was here, and it is not a builtin any more — a colour is a data `HostRecord` a
            // drawing host declares. The branch below already skips a named type as "the host's business
            // rather than this set's", which is now the whole answer for it.
        )
        for (info in Types.authorable) {
            // A named type has no builtin, and is the host's business rather than this set's.
            val builtin = info.type.builtin ?: continue
            assertTrue(builtin in editable, "${info.name} is authorable but has no editor")
        }
    }

    /** A type is offered by being registered, so a new one reaches the list node without this file changing. */
    @Test
    fun `element types come from the registry`() {
        assertEquals(Types.declarable.map { it.name }, BuiltinNodes.elementTypes.map { it.name })
    }

    /**
     * Naming a type and writing a value of it are different questions: a host's wire-only record may be a
     * parameter, a field or a list element without ever having an inline editor. `declarable` defaults to
     * `authorable`, so a type you can write you can always name.
     */
    @Test
    fun `a type can be declarable without being authorable`() {
        val written = TypeInfo("Written", TypeRef.named("Written"), "typed in", authorable = true)
        val wired = TypeInfo("Wired", TypeRef.named("Wired"), "built by the graph", authorable = false, declarable = true)
        val hidden = TypeInfo("Hidden", TypeRef.named("Hidden"), "never named", authorable = false)
        assertTrue(written.declarable, "an authorable type is declarable by default")
        assertTrue(wired.declarable && !wired.authorable)
        assertTrue(!hidden.declarable)
        // The variable list is the declarable list plus List, once.
        assertEquals(1, Types.forVariables.count { it.name == "List" })
        assertTrue(Types.forVariables.map { it.name }.containsAll(Types.declarable.map { it.name }))
    }

    /** Names round-trip, which is what lets a document store one and mean it. */
    @Test
    fun `every registered type is found by its own name`() {
        for (info in Types.all) {
            assertEquals(info.type, Types.of(info.name)?.type, info.name)
            assertEquals(info.type, Types.of(info.name.uppercase())?.type, "${info.name} should not be case sensitive")
        }
    }

    /** A slipped keypress must not generate ten thousand pins and take the canvas with it. */
    @Test
    fun `slot count is capped`() {
        val d = resolve(mapOf(BuiltinNodes.LIST_COUNT to 100_000))
        assertEquals(BuiltinNodes.LIST_MAX + 2, d.inputs.size)
    }

    @Test
    fun `a negative count is no slots rather than a crash`() {
        assertEquals(listOf("Of", "Count"), resolve(mapOf(BuiltinNodes.LIST_COUNT to -5)).inputs.map { it.name })
    }

    /**
     * A name the built-ins do not know is a DECLARED type, and survives as itself.
     *
     * It used to read as Int, on the reasoning that a hand-edited document should still open. That was
     * right before documents could declare records and wrong afterwards: a `LIST<Step>` typed every one
     * of its element pins INT and then refused the very records being put in them. Surviving as itself
     * is also what `TypeRef.named` does and says to do — the validator reports a name that resolves to
     * nothing, which is a better answer than a list of ints nobody asked for.
     *
     * Nothing at all still reads as Int: an empty pin has no name to keep.
     */
    @Test
    fun `an unrecognised element type is kept as a declared type`() {
        assertEquals(TypeRef.named("Sandwich"), BuiltinNodes.listElementType("Sandwich"))
        assertEquals(TypeRef(PinType.INT), BuiltinNodes.listElementType(null))
        assertEquals(TypeRef(PinType.INT), BuiltinNodes.listElementType("  "))
        // A built-in still resolves through Types, so its editor and picker are unchanged.
        assertEquals(TypeRef.named("Item"), BuiltinNodes.listElementType("Item"))
    }
}
