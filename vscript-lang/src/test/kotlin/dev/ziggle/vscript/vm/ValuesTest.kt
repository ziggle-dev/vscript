package dev.ziggle.vscript.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What arithmetic answers, and in what.
 *
 * **The promotion order is the interesting half; the width is the load-bearing one.** Double wins over
 * Long wins over Int, and two Ints used to be worked out in thirty-two bits and left to wrap. Wrapping was
 * never a decision — it is what the JVM does — and it produced the one class of failure that no amount of
 * reading the script finds: `gained * 3600000` for an experience rate turns NEGATIVE the moment a session
 * passes 597 experience, and the panel then reports a rate that wraps round again as the run goes on.
 * `fisher`, `karambwan` and `teak-dropper` all carry a `3600000.0` written purely to force the Double path
 * and get away from it.
 *
 * So two Ints are worked out in sixty-four bits and narrowed back **when the answer fits**. Both halves of
 * that are tested here: an ordinary sum has to stay an Int — anything reading a type off a value would
 * otherwise start saying Long for arithmetic that never left range — and one that does not fit has to be
 * the right number rather than a wrapped one.
 */
class ValuesTest {

    @Test
    fun `two ints that fit stay an Int`() {
        val sum = Values.arith(Op.ADD, 2, 3)
        assertEquals(5, sum)
        assertEquals("Int", Values.typeName(sum))
    }

    @Test
    fun `a product that overflows thirty-two bits is the right number, not a wrapped one`() {
        // The live case: 100 experience an hour's worth of scaling. In Int this is -1894967296.
        val scaled = Values.arith(Op.MUL, 1000, 3_600_000)
        assertEquals(3_600_000_000L, scaled)
        assertEquals("Long", Values.typeName(scaled))
    }

    @Test
    fun `addition past the top of Int does not wrap`() {
        assertEquals(2_147_483_648L, Values.arith(Op.ADD, Int.MAX_VALUE, 1))
        assertEquals(-2_147_483_649L, Values.arith(Op.SUB, Int.MIN_VALUE, 1))
    }

    /** The one division that overflows: there is no positive counterpart of `Int.MIN_VALUE`. */
    @Test
    fun `dividing the smallest Int by minus one is the right number`() {
        assertEquals(2_147_483_648L, Values.arith(Op.DIV, Int.MIN_VALUE, -1))
    }

    @Test
    fun `a Long operand still gives a Long, and a Double still wins`() {
        assertEquals(3L, Values.arith(Op.ADD, 1L, 2))
        assertEquals(3.5, Values.arith(Op.ADD, 1.5, 2))
    }

    @Test
    fun `integer division still floors, and is not quietly a Double`() {
        assertEquals(3, Values.arith(Op.DIV, 7, 2))
    }

    @Test
    fun `dividing by zero still says so rather than wrapping into one`() {
        assertFailsWith<VmError> { Values.arith(Op.DIV, 1, 0) }
        assertFailsWith<VmError> { Values.arith(Op.MOD, 1, 0) }
    }

    /** Concatenation is still decided before any of this, by either side being a String. */
    @Test
    fun `adding a string concatenates from either side`() {
        assertEquals("3200,", Values.arith(Op.ADD, 3200, ","))
        assertEquals(",3200", Values.arith(Op.ADD, ",", 3200))
    }
}
