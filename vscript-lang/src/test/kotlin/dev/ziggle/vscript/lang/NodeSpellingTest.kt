package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Adding a node must not change what an existing one is called.**
 *
 * [Names] gives a node the last segment of its type wherever that segment is unique, and the FULL type
 * where it is not. Both sides of a clash qualify — so a new `list.min` beside a `math.min` does not lose an
 * argument, it silently renames `min(…)` to `math.min(…)` and every script that called it stops parsing.
 * That is a change to the language made by adding to a catalogue, and the person adding the node has no
 * reason to look.
 *
 * These are the two properties that make that impossible to do by accident: nothing shares a spelling, and
 * the spellings the corpus already uses are still those spellings. The second is a pinned list on purpose —
 * a name here is a promise to files on disk, so changing one should require changing this file and saying
 * why.
 */
class NodeSpellingTest {

    private val catalog = NodeCatalog(dev.ziggle.vscript.domain.TileFixture.DESCRIPTORS)
    private val names = Names(catalog)

    @Test
    fun `no two nodes share a spelling`() {
        // `byTextName` is built with `associateBy`, which keeps the LAST of a duplicate pair and drops the
        // other without a word — so a collision here does not fail, it makes a node unreachable from text.
        val byName = names.callable.groupBy { names.textName(it.type) }
        val clashes = byName.filterValues { it.size > 1 }
            .map { (name, ds) -> "$name <- ${ds.map { it.type }}" }
        assertEquals(emptyList(), clashes, "two nodes want the same text name")
    }

    @Test
    fun `every builtin can be written`() {
        // Falling back to the type is legal — `draw.tile` does it, because `tile(…)` is a literal — but for
        // a BUILTIN it means a name got taken, which is the failure this file is about. Nothing in the
        // builtin catalogue is expected to be qualified.
        val qualified = names.callable
            .filter { it.type in BuiltinNodes.builtinTypes }
            .filter { names.textName(it.type) == it.type }
            .map { it.type }
        assertEquals(emptyList(), qualified, "a builtin lost its short name to a clash")
    }

    /**
     * The names `vscript-client/scripts/` is written against.
     *
     * Not every node — the ones a script would notice losing. Each entry is a spelling that appears in the
     * corpus today, so a diff here is a diff to files that already exist.
     */
    @Test
    fun `the spellings the scripts already use are unchanged`() {
        val pinned = mapOf(
            // **The container verbs live under `_` now.** They used to claim a bare English word each —
            // `count`, `first`, `contains`, `without` — and a builtin claiming a name TAKES it: a
            // document declaring its own `count` shadowed the builtin and every `count(list: …)` in that
            // file then resolved to the wrong one. One reserved PREFIX fixes it for every verb at once,
            // instead of a per-case rename each time something clashes.
            //
            // The four list edits are still named alike, because all four hand back a copy — as opposed
            // to `_listAdd`/`_listSet`, the intrinsics that write in place.
            BuiltinNodes.LIST_ADD to "_listWithItemAdded",
            BuiltinNodes.LIST_SET to "_listWithItemAt",
            BuiltinNodes.LIST_REMOVE to "_listWithout",
            BuiltinNodes.LIST_REMOVE_AT to "_listWithoutAt",
            BuiltinNodes.LIST_COUNT_OF to "_listCount",
            BuiltinNodes.LIST_FIRST to "_listFirst",
            BuiltinNodes.LIST_CONTAINS to "_listContains",
            BuiltinNodes.LIST_IS_EMPTY to "_listIsEmpty",
            // The formatter, which took `text` off `draw.text` deliberately — see Names.OVERRIDES.
            BuiltinNodes.FORMAT to "text",
            BuiltinNodes.TO_TEXT to "toText",
            BuiltinNodes.FLOOR to "floor",
            BuiltinNodes.CEIL to "ceil",
            BuiltinNodes.ROUND to "round",
            BuiltinNodes.TO_INT to "toInt",
            BuiltinNodes.TO_FLOAT to "toFloat",
            BuiltinNodes.NOW to "now",
            BuiltinNodes.LOG to "log",
        )
        assertEquals(pinned, pinned.keys.associateWith { names.textName(it) })
    }

    /** And the ones just added, so the intended spelling is the asserted one rather than the derived one. */
    @Test
    fun `the new verbs are spelled as intended`() {
        val added = mapOf(
            BuiltinNodes.LIST_INDEX_OF to "_listIndexOf",
            BuiltinNodes.LIST_CONCAT to "_listConcat",
            BuiltinNodes.LIST_REVERSED to "_listReversed",
            BuiltinNodes.LIST_TAKE to "_listTake",
            BuiltinNodes.LIST_DROP to "_listDrop",
            BuiltinNodes.LIST_SUM to "_listSum",
            // Named apart from math's min/max at the TYPE, so the two never compete for `min`.
            BuiltinNodes.LIST_SMALLEST to "_listSmallest",
            BuiltinNodes.LIST_LARGEST to "_listLargest",
            BuiltinNodes.LIST_SORTED_BY to "_listSortedBy",
            BuiltinNodes.RANGE to "range",
            BuiltinNodes.ABS to "abs",
            BuiltinNodes.MIN to "min",
            BuiltinNodes.MAX to "max",
            BuiltinNodes.SQRT to "sqrt",
            BuiltinNodes.POW to "pow",
            BuiltinNodes.SIN to "sin",
            BuiltinNodes.COS to "cos",
            BuiltinNodes.PI to "pi",
        )
        assertEquals(added, added.keys.associateWith { names.textName(it) })

        // And each resolves back, which is the half that makes them callable rather than merely named.
        for ((type, spelling) in added) {
            assertEquals(type, names.resolveType(spelling)?.type, "'$spelling' does not resolve back")
        }
    }

    @Test
    fun `every new verb has a host and a summary`() {
        // A descriptor with no host compiles to a call into nothing, and the failure is at RUN time in
        // whatever script reached it first. A missing summary is the IDE's hover being blank.
        val added = listOf(
            BuiltinNodes.LIST_INDEX_OF, BuiltinNodes.LIST_CONCAT, BuiltinNodes.LIST_REVERSED,
            BuiltinNodes.LIST_TAKE, BuiltinNodes.LIST_DROP, BuiltinNodes.LIST_SUM,
            BuiltinNodes.LIST_SMALLEST, BuiltinNodes.LIST_LARGEST, BuiltinNodes.LIST_SORTED_BY,
            BuiltinNodes.LIST_REMOVE, BuiltinNodes.LIST_REMOVE_AT, BuiltinNodes.RANGE,
            BuiltinNodes.ABS, BuiltinNodes.MIN, BuiltinNodes.MAX, BuiltinNodes.SQRT,
            BuiltinNodes.POW, BuiltinNodes.SIN, BuiltinNodes.COS, BuiltinNodes.PI,
        )
        val hosts = dev.ziggle.vscript.nodes.BuiltinHosts.registry().names
        for (type in added) {
            val d = assertNotNull(catalog[type], "$type is not in the catalogue")
            val host = assertNotNull(d.host, "$type has no host")
            assertTrue(host in hosts, "$type names host '$host', which nothing registers")
            assertTrue(d.summary.isNotBlank(), "$type has no summary")
        }
    }
}
