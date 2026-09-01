package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.compile.errors
import dev.ziggle.vscript.compile.hostNode
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinSpec
import dev.ziggle.vscript.model.PinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a `[…]` literal is allowed to become when it is handed straight to a typed slot.
 *
 * **The hole these exist for.** `retypeList` relabels a list literal with the element type of the pin it
 * feeds — which it has to, because `[1783, 1781]` is a list of ITEMs when a Drop Any is asked to drop them
 * and `canConnect(INT, ITEM)` is false. But it relabelled unconditionally, so `[1, 2]` handed to a
 * `LIST<STRING>` became a list of strings and the wire then compared the new label against itself and
 * agreed. Binding the literal to a local first WAS caught:
 *
 * ```
 * let ns = [1, 2]
 * takes(xs: ns)     // cannot wire LIST<INT> into LIST<STRING>
 * ```
 *
 * So the one place element types went unchecked was the shortest way to write the call — which is also the
 * way anyone writes it first, and why the rule looked arbitrary.
 *
 * The test is STORAGE rather than type: a destination that is a different reading of the same stored value
 * retypes, and a destination that is a different thing entirely does not. That keeps every id list working
 * while closing the hole.
 */
class ListElementCheckTest {

    private val takesItems = hostNode(
        "test.dropAny", "dropAny", NodeKind.IMPURE,
        inputs = listOf(
            PinSpec("Exec", PinType.EXEC),
            PinSpec("Items", TypeRef.list(TypeRef.named("Item"))),
        ),
        outputs = listOf(PinSpec("Exec", PinType.EXEC)),
    )
    private val catalog = NodeCatalog(listOf(takesItems) + dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)

    private fun problems(body: String): List<String> {
        val src = """
            graph "probe"
            export fn takesText(xs: LIST<STRING>) -> INT = _listCount(list: xs)
            export fn takesInts(xs: LIST<INT>) -> INT = _listCount(list: xs)
            export fn takesTiles(xs: LIST<TILE>) -> INT = _listCount(list: xs)
            on start {
                $body
            }
        """.trimIndent()
        val parsed = Parser(Lexer(src).lex()).parse()
        assertTrue(parsed.ok, "parse: ${parsed.errors.map { it.message }}")
        val low = Lower(catalog).lower(parsed.program)
        return low.errors.map { it.message } +
            Validator(catalog).validate(low.graph).errors().map { it.message }
    }

    // ---- the hole ------------------------------------------------------------------------------------

    /** GAPS #5. Accepted before the storage test existed. */
    @Test
    fun `a list of ints handed straight to a list of strings is refused`() {
        val p = problems("""log(message: "" + takesText(xs: [1, 2]))""")
        assertTrue(p.isNotEmpty(), "should be refused, but compiled clean")
        assertTrue(p.any { "LIST" in it }, "the message should be about the wire, got: $p")
    }

    @Test
    fun `a list of strings handed straight to a list of ints is refused`() {
        assertTrue(problems("""log(message: "" + takesInts(xs: ["a", "b"]))""").isNotEmpty())
    }

    /** The same mistake bound to a local first, which was always caught — the two must now agree. */
    @Test
    fun `the bound form is refused the same way`() {
        val direct = problems("""log(message: "" + takesText(xs: [1, 2]))""")
        val bound = problems(
            """
            val ns = [1, 2]
            log(message: "" + takesText(xs: ns))
            """.trimIndent(),
        )
        assertTrue(direct.isNotEmpty() && bound.isNotEmpty(), "direct=$direct bound=$bound")
    }

    // ---- what retyping is FOR, kept working ----------------------------------------------------------

    /**
     * The case the whole mechanism exists for: ids are Ints as written and ITEMs where they are used, and
     * `canConnect(INT, ITEM)` is false — so this only works because the literal is relabelled.
     */
    @Test
    /**
     * A list of bare ints is NOT a list of items, exactly as a bare int is not an item.
     *
     * It used to be, because `ITEM` was a builtin whose storage was "number" and the element check
     * compared storage. `Item` is a nominal type the node pack declares now, and the element rule is
     * simply the scalar rule applied to elements — which is the consistency worth having: a script that
     * cannot write `item: 1621` should not be able to write `items: [1621]` either.
     *
     * Eight sites in the corpus do, all of them under `scripts/old/`. The live corpus has none.
     */
    fun `a list of bare ints is not a list of items`() {
        assertEquals(
            listOf("cannot wire LIST<INT> into LIST<Item> ('List.Value' -> 'test.dropAny.Items')"
                .replace("->", "\u2192")),
            problems("dropAny(items: [1783, 1781, 1775])"),
        )
    }

    @Test
    fun `a list of ints into a list of ints is fine`() {
        assertEquals(emptyList(), problems("""log(message: "" + takesInts(xs: [1, 2]))"""))
    }

    @Test
    fun `a list of strings into a list of strings is fine`() {
        assertEquals(emptyList(), problems("""log(message: "" + takesText(xs: ["a", "b"]))"""))
    }

    /** A TILE is stored as text, so a list of tile literals reaches a `LIST<TILE>`. */
    @Test
    fun `a list of tiles is fine`() {
        assertEquals(
            emptyList(),
            problems("""log(message: "" + takesTiles(xs: [tile(1, 2, 0), tile(3, 4, 0)]))"""),
        )
    }

    /**
     * An empty or mixed literal guesses `Wildcard`, which genuinely says nothing — so the destination still
     * decides, exactly as before. Refusing these would break every empty-list initialiser.
     */
    @Test
    fun `an empty list still takes the destination's type`() {
        assertEquals(emptyList(), problems("""log(message: "" + takesText(xs: []))"""))
    }

    @Test
    fun `a list built from calls still takes the destination's type`() {
        assertEquals(
            emptyList(),
            problems("""log(message: "" + takesText(xs: [text("{n}", n: 1), text("{n}", n: 2)]))"""),
        )
    }
}
