package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.vm.drive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reroute knots, which must mean NOTHING.
 *
 * The formatter creates and destroys these freely — that is the whole arrangement — so the one property
 * everything else rests on is that a graph computes the same answer with them as without. If a knot could
 * change behaviour, a formatting button would be editing the program, and no amount of care elsewhere
 * would make that acceptable.
 */
class RerouteTest {

    private val catalog = NodeCatalog()

    private fun valueOf(withKnots: Int): Any? {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 42))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            // A chain of knots between the literal and the return.
            var src = lit
            var pin = "Value"
            repeat(withKnots) {
                val k = node(BuiltinNodes.REROUTE)
                link(src, pin, k, "In")
                src = k
                pin = "Out"
            }
            link(src, pin, ret, "Value")
        }
        return drive(GraphCompiler(catalog).compile(g, 1)).value()
    }

    @Test
    fun `a knot carries its value through unchanged`() {
        assertEquals(42, valueOf(0), "sanity: the same graph without one")
        assertEquals(42, valueOf(1))
    }

    /** The formatter can knot a wire more than once; each one has to be equally invisible. */
    @Test
    fun `a chain of knots is still transparent`() {
        assertEquals(42, valueOf(4))
    }

    /** A knot compiles to no instruction at all — not to a copy that happens to be correct. */
    @Test
    fun `a knot emits nothing`() {
        val plain = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 7))
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(lit, "Value", ret, "Value")
        }
        val knotted = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val lit = node(BuiltinNodes.LITERAL_INT, literals = mapOf("Value" to 7))
            val k = node(BuiltinNodes.REROUTE)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(lit, "Value", k, "In")
            link(k, "Out", ret, "Value")
        }
        val a = GraphCompiler(catalog).compile(plain, 1)
        val b = GraphCompiler(catalog).compile(knotted, 1)
        assertEquals(a.size, b.size, "knotting a wire should not add an instruction")
    }

    /** A knot with nothing feeding it reads as an unconnected pin, not as a crash. */
    @Test
    fun `a dangling knot is an empty pin`() {
        val g = graph {
            val entry = node(BuiltinNodes.ENTRY)
            val k = node(BuiltinNodes.REROUTE)
            val ret = node(BuiltinNodes.RETURN)
            link(entry, "Exec", ret, "Exec")
            link(k, "Out", ret, "Value")
        }
        assertEquals(null, drive(GraphCompiler(catalog).compile(g, 1)).value())
    }
}
