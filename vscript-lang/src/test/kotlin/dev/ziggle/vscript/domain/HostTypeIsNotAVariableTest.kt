package dev.ziggle.vscript.domain

import dev.ziggle.vscript.model.HostField
import dev.ziggle.vscript.model.HostRecord
import dev.ziggle.vscript.model.HostRecords
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.text.natives
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A host's type named in a receiver is a TYPE, not a type variable.
 *
 * ### The bug, and why it was invisible
 *
 * `fn List<Tile>.nearestTo(self, to: Tile)` is an extension on a list of tiles. Deciding that requires
 * knowing that `Tile` names a type — otherwise it reads as `fn List<T>.nearestTo`, generic over something
 * spelled `Tile`, which is exactly what `fn List<Element>.first(self)` means and has to keep meaning.
 *
 * The two places that answer "is this a type or a variable" listed the document's own declarations, its
 * imports' and the prelude's. That was complete only while every host type was ALSO a builtin: `Tile`,
 * `Item` and `Npc` all answered through `Prelude.type`. When a type moves to the node pack it answers
 * through `HostRecords` instead, and neither place asked.
 *
 * Nothing reported anything. `self` typed as `LIST<variable>`, the loop variable came out as that
 * variable, and a call on an unknown receiver is deliberately left alone rather than guessed at — so the
 * body's own `t.sqDistanceTo(…)` resolved to no callee, silently. It surfaced two layers away as the
 * compiler's "does not compile a call to 't.sqDistanceTo' yet", which reads as an unimplemented feature
 * and is nothing of the kind.
 *
 * ### Why it needs an import to reproduce
 *
 * It does not, strictly — the mis-typing happens wherever the extension is declared. But a library is how
 * anyone writes one, and compiling the body in the same document happens to resolve `sqDistanceTo` by a
 * path that does not care. The corpus hit it through `core/world/tile`, so the test does too.
 */
class HostTypeIsNotAVariableTest {

    private val catalog = NodeCatalog(TileFixture.DESCRIPTORS)

    private val library = """
        graph "spatial"

        export fn Tile.sqDistanceTo(self, other: Tile) -> Int {
            val dx = self.x - other.x
            val dy = self.y - other.y
            return dx * dx + dy * dy
        }

        export fn List<Tile>.nearestTo(self, to: Tile) -> Tile {
            var best: Tile = to
            var bestD = -1
            for t in self {
                val d = t.sqDistanceTo(other: to)
                if bestD < 0 || d < bestD {
                    bestD = d
                    best = t
                }
            }
            return best
        }
    """.trimIndent()

    private fun errorsFor(root: String, lib: String = library): List<String> =
        TextFrontEnd(catalog.natives(), imports = TextSource.of(mapOf("spatial" to lib)))
            .read(root).errors.map { it.message }

    private fun root(type: String) = """
        graph "root"

        import "spatial"

        var Spots: List<$type> = []
        var Got: $type = tile(0, 0, 0)

        on start {
            Got = Spots.nearestTo(to: tile(3200, 3200, 0))
        }
    """.trimIndent()

    @Test
    fun `an extension on a list of a host record compiles`() {
        assertEquals(emptyList(), errorsFor(root("Tile")))
    }

    /**
     * The same, written in the spelling half the corpus uses.
     *
     * `TILE` was the builtin's own name and a great many scripts write it. It resolves to the same type —
     * `TypeRef.named` normalises a host type's spelling the way it always normalised a builtin's — and
     * that has to hold here too, or the two halves of the corpus would disagree about what a tile is.
     */
    @Test
    fun `the builtin's old spelling still names the host's type`() {
        assertEquals(emptyList(), errorsFor(root("TILE")))
    }

    /**
     * An ACCESSOR record, which is the majority of host records and was broken identically.
     *
     * Kept separate because the fix must not depend on `isData`: whether the language can see inside a
     * value has nothing to do with whether its NAME is a type.
     */
    @Test
    fun `an accessor host record is a type too`() {
        val widget = HostRecord(
            "Widget",
            listOf(
                HostField("x", TypeRef(PinType.INT)),
                HostField("y", TypeRef(PinType.INT)),
            ),
        )
        HostRecords.register(widget)
        val lib = library.replace("Tile", "Widget").replace("self.plane", "0")
        val root = """
            graph "root"

            import "spatial"

            var Spots: List<Widget> = []
            var Got: Int = 0

            on start {
                for w in Spots { Got = Spots.nearestTo(to: w).x }
            }
        """.trimIndent()
        assertEquals(emptyList(), errorsFor(root, lib))
    }

    /**
     * And a name that genuinely names nothing is STILL a type variable.
     *
     * The fix adds host types to the "known" set; it must not empty the set. `fn List<Element>.first` is
     * how every generic extension in the corpus is written, and reading `Element` as a missing type
     * instead of a parameter would break all of them.
     */
    @Test
    fun `a name nothing declares is still a type variable`() {
        val generic = """
            graph "spatial"

            export fn List<Element>.firstOrDefault(self, fallback: Element) -> Element {
                for e in self { return e }
                return fallback
            }
        """.trimIndent()
        val root = """
            graph "root"

            import "spatial"

            var Got: Int = 0

            on start {
                Got = [1, 2].firstOrDefault(fallback: 0)
            }
        """.trimIndent()
        assertEquals(emptyList(), errorsFor(root, generic))
    }
}
