package dev.ziggle.vscript.domain

import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.text.natives
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.vm.StructValue

/**
 * A structured host type, for the tests that need one.
 *
 * ### Why a fixture and not the real thing
 *
 * `Tile` was `PinType.TILE`: a builtin, always present, and a game's coordinate system sitting in the type
 * enum of a language that is supposed to know nothing about any game. It is a data [HostRecord] the node
 * pack declares now, which means the language's own tests can no longer name it — correctly, and that is
 * the change rather than a casualty of it.
 *
 * The tests that used it were not about tiles. They were about what a STRUCTURED type does: that a pin
 * carrying one refuses a value of another shape, that a field read compiles to a `GETFIELD`, that a
 * document's stored text becomes a record on the way into a pin. All of that is still worth asserting, and
 * a fixture asserts it without the language knowing a game exists.
 *
 * Kept as the name `Tile` on purpose: these tests read as the ones they replace, and the diff that moved
 * them stays about ownership rather than about renaming.
 */
object TileFixture {

    /** `"x,y,plane"` — the form a document stores, and the reason [read] exists. */
    fun parts(value: Any?): IntArray {
        val out = intArrayOf(0, 0, 0)
        val text = value?.toString() ?: return out
        val split = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        for (i in 0 until minOf(split.size, 3)) out[i] = split[i].toIntOrNull() ?: 0
        return out
    }

    /** The record, from whatever form arrived — the stored string, or one of these already. */
    fun read(value: Any?): StructValue? {
        if (value is StructValue) return value.takeIf { it.type == "Tile" }
        if (value == null) return null
        val p = parts(value)
        return of(p[0], p[1], p[2])
    }

    fun of(x: Int, y: Int, plane: Int): StructValue =
        StructValue("Tile", listOf("x", "y", "plane"), arrayOf<Any?>(x, y, plane))

    val RECORD = HostRecord(
        "Tile",
        listOf(
            HostField("x", TypeRef(PinType.INT)),
            HostField("y", TypeRef(PinType.INT)),
            HostField("plane", TypeRef(PinType.INT)),
        ),
        "a position",
        isData = true,
        read = { read(it) },
    )

    val TYPE: TypeRef get() = RECORD.type

    /**
     * Register it, for a test class that names it.
     *
     * Called from an `init` rather than once statically, because [HostRecords.reset] is how a test that
     * registers its own library cleans up — so a class that needs this needs it back afterwards.
     */
    fun register() = HostRecords.register(RECORD)

    /**
     * The positional constructor, `tile(x, y, plane)`.
     *
     * A [dev.ziggle.vscript.vm.HostKind.CONSTRUCT] node, which is what a language intrinsic became: it emits
     * the same `NEWSTRUCT` the intrinsic emitted and folds to the same constant, so a test asserting the
     * spelling, the cost or the round trip is asserting the same things it always was.
     *
     * Declared through the ordinary authoring DSL rather than hand-built, so the fixture exercises the
     * path a real pack takes — a fixture that reached past the DSL would prove the DSL nothing.
     */
    val library = dev.ziggle.vscript.nodes.library("fixture", "Fixture") {
        record(RECORD)
        func("tile") {
            title("Tile")
            spelledAs("tile")
            doc("A position — `tile(3200, 3200, 0)`. Plane defaults to the ground.")
            param("X", dev.ziggle.vscript.nodes.Vs.int)
            param("Y", dev.ziggle.vscript.nodes.Vs.int)
            param("Plane", dev.ziggle.vscript.nodes.Vs.int, default = 0)
            result("Tile", dev.ziggle.vscript.nodes.Vs.record<StructValue>(RECORD))
            construct()
        }
    }

    /**
     * What a test adds to its own catalogue so `tile(…)` resolves.
     *
     * **Registers the record on the way past**, which is not a shortcut: the constructor's RESULT type is
     * the record, so a catalogue holding one without the other describes a node that builds a type nobody
     * declared. Asking for the nodes and getting the type they need is the only coherent pairing, and it
     * saves nineteen test classes an `init` block that could be forgotten in the twentieth.
     */
    val DESCRIPTORS: List<dev.ziggle.vscript.model.NodeDescriptor>
        get() {
            register()
            return library.defs.map { it.descriptor }
        }

    /**
     * The same, for a test that builds a [dev.ziggle.vscript.text.NativeTable] directly.
     *
     * **Only the fixture's OWN.** A catalogue always carries the language's builtins, so handing back
     * everything it knows meant a test appending this to its own list registered `log` twice and every
     * case in the class died in the constructor with "duplicate native 'log'" — which reads as a broken
     * fixture rather than as a fixture that was too generous.
     */
    val NATIVES: List<dev.ziggle.vscript.text.NativeFn>
        get() {
            val builtin = dev.ziggle.vscript.model.NodeCatalog().natives().all.map { it.name }.toSet()
            return dev.ziggle.vscript.model.NodeCatalog(DESCRIPTORS).natives().all.filter { it.name !in builtin }
        }
}
