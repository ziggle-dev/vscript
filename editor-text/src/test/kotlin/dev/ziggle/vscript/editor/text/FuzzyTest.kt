package dev.ziggle.vscript.editor.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The matcher, tested as **ranking** rather than as matching.
 *
 * Whether `wa` matches `withdrawAll` is the easy half and a subsequence test answers it. The half that
 * decides whether a search box is usable is whether `withdrawAll` comes back *above* the hundred other
 * names that also contain a `w` before an `a`. So most of these assert an ORDER, not a boolean.
 */
class FuzzyTest {

    private fun score(name: String, q: String): Int =
        assertNotNull(Fuzzy.match(name, q), "'$q' should match '$name'").score

    private fun beats(better: String, worse: String, q: String) {
        val a = score(better, q)
        val b = Fuzzy.match(worse, q)?.score ?: -1
        assertTrue(a > b, "'$q': expected '$better' ($a) above '$worse' ($b)")
    }

    @Test
    fun `no match is null, not a zero score`() {
        assertNull(Fuzzy.match("bank", "zzz"))
        assertNull(Fuzzy.match("bank", "bankk"), "a query longer than the name cannot match")
        assertNull(Fuzzy.match("bank", ""))
    }

    @Test
    fun `it finds a camel-hump abbreviation`() {
        val m = assertNotNull(Fuzzy.match("withdrawAll", "wa"), "the case a search box exists for")
        assertEquals(listOf(0, 8), m.positions, "should hit the 'w' and the 'A' hump")
    }

    @Test
    fun `exact beats everything`() {
        beats("bank", "bankAll", "bank")
        beats("bank", "theBank", "bank")
    }

    @Test
    fun `a prefix beats a match in the middle`() {
        beats("bankAll", "theBank", "bank")
    }

    @Test
    fun `a word start beats mid-word`() {
        // `wa` on the hump of withdrawAll, versus the same letters buried in a word.
        beats("withdrawAll", "warlock", "wA")
    }

    @Test
    fun `adjacent beats scattered`() {
        beats("bankNow", "bringAnkleNow", "bank")
    }

    @Test
    fun `a short name matched whole beats a long one matched sparsely`() {
        // Otherwise `bank` ranks `bankTheWholeInventory` level with `bank`.
        beats("bank", "bankTheWholeInventory", "bank")
    }

    @Test
    fun `case is a tiebreak, not a filter`() {
        assertNotNull(Fuzzy.match("Bank", "bank"), "case must not prevent a match")
        beats("Bank", "bank", "Bank")
    }

    @Test
    fun `separators start words too`() {
        val m = assertNotNull(Fuzzy.match("core/loadout", "cl"))
        assertEquals(listOf(0, 5), m.positions, "should hit 'c' and the 'l' after the slash")
    }

    @Test
    fun `positions are where it actually matched`() {
        // The caller highlights these; being wrong shows the underline under the wrong letters.
        val m = assertNotNull(Fuzzy.match("withdrawAll", "wdA"))
        assertEquals(3, m.positions.size)
        assertTrue(m.positions.zipWithNext().all { (a, b) -> a < b }, "positions must ascend: ${m.positions}")
        assertEquals('w', "withdrawAll"[m.positions[0]])
        assertEquals('d', "withdrawAll"[m.positions[1]])
        assertEquals('A', "withdrawAll"[m.positions[2]])
    }
}
