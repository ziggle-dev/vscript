package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.PinSpec

/**
 * What things are called in text, in both directions.
 *
 * **One spelling per node.** This is the rule the whole file exists to enforce, and it follows straight from
 * the round-trip contract: if a node could be written two ways, the printer would have to pick one, and a
 * file written the other way would not survive being read and printed back. So `!x` is `logic.not` and
 * `not(x)` is not accepted; `c ? a : b` is `logic.select` and `select(…)` is not. The nodes with their own
 * syntax are listed in [SYNTACTIC] and are never spelled as calls.
 *
 * **Shortest unambiguous, and the parser accepts more than the printer emits.** A node type is dotted
 * (`game.nearestObjectNamed`); text uses the last segment where that resolves uniquely and the full type
 * where it does not. The parser additionally accepts the full form always, so a graph is readable whether or
 * not the writer knew which names were ambiguous.
 */
class Names(private val catalog: NodeCatalog) {

    /**
     * Node types that can be written as `name(…)` — everything without a syntax of its own.
     *
     * Not "everything with a host": `flow.delay`, `logic.and` and `logic.or` are lowered by the compiler
     * rather than being host calls, and all three are written as calls.
     */
    val callable: List<NodeDescriptor> = catalog.all.filter { it.type !in SYNTACTIC }

    /**
     * Every callable type's text name, assigned once in two passes.
     *
     * **Claims go first, then derived names take what is left.** The order is the whole rule and it has
     * to be explicit, because the two passes genuinely compete: `value.format` is overridden to `text`,
     * and the drawing node `draw.text` would otherwise derive the same word. Deciding both at once would
     * see a clash and make *both* qualify, which loses the override for no reason — the point of
     * overriding was that `text(…)` should mean the formatter.
     *
     * A claim is either the language's own ([OVERRIDES]) or a host node's ([NodeDescriptor.textName]),
     * and they are honoured in the same pass because they are the same kind of statement: this node is
     * written *this* way. Two claims on one word would be a real conflict; nothing here resolves it,
     * because nothing should — it means two owners each think they own the name.
     *
     * An override also *replaces* the derived name rather than joining it. If `set(…)` still resolved,
     * `list.set` would have two spellings, and the printer emitting one while the parser accepted both is
     * exactly the asymmetry that breaks a character-identical round trip.
     */
    private val textNames: Map<String, String> = buildMap {
        val taken = HashSet(OVERRIDES.values)
        // A HOST's own claim, in the same pass and for the same reason: a derived name must not take a
        // deliberate spelling out from under it. `OVERRIDES` is the language's table for its own nodes;
        // this is how a pack states one without needing an entry in it.
        taken += callable.mapNotNull { it.textName }
        for (d in callable) (d.textName ?: OVERRIDES[d.type])?.let { put(d.type, it) }

        val rest = callable.filter { it.type !in this }
        // **An extension competes only with the other extensions on its own receiver.**
        //
        // A name is ambiguous when two nodes would answer to it *in the same position*, and a receiver
        // call is a different position from a bare one. `green.isDry` on a Bench and `num.isDry` on an INT
        // are no more ambiguous than two Kotlin extensions of the same name on different types — the
        // receiver decides. Grouped globally, they collided and BOTH fell back to their qualified type, so
        // neither `b.isDry()` nor `n.isDry()` resolved and the diagnostic said the type had no such verb,
        // which points at the call rather than at the collision.
        //
        // It is not a corner: the case that found it is one game's `isValid`, declared on an item, an
        // object and an NPC. Three receivers, one honest name.
        fun bucket(d: NodeDescriptor) = d.receiver?.name.orEmpty()
        val wants = rest.groupBy({ bucket(it) to it.type.substringAfterLast('.') }, { it.type })
        for (d in rest) {
            val want = d.type.substringAfterLast('.')
            val unique = wants[bucket(d) to want]?.size == 1
            // A claimed name blocks a bare one, and cannot block a receiver call: `x.tile()` is a
            // different position from `tile(…)` and could never be read as the same thing.
            val blocked = d.receiver == null && want in taken
            put(d.type, if (unique && !blocked) want else d.type)
        }
    }

    /**
     * The reverse of [textName], for the names that can be WRITTEN BARE.
     *
     * **Extensions are excluded, and must be.** A verb with a receiver is never resolved by its bare name
     * — `isValid` on its own means nothing, and the resolver reaches it through the receiver instead. They
     * are also the only nodes whose short names may repeat (per receiver), so including them would make
     * this an `associateBy` that silently kept whichever of `item.isValid`, `object.isValid` and
     * `npc.isValid` happened to come last.
     */
    private val byTextName: Map<String, NodeDescriptor> =
        callable.filter { it.receiver == null }.associateBy { textNames.getValue(it.type) }

    // ---- node types -----------------------------------------------------------------------------------

    /**
     * How [type] is written in text: the shortest form that could only mean this node.
     *
     * Falls back to the fully-qualified type when the short form is ambiguous, and is whatever the node
     * CLAIMED when it claimed one — see [NodeDescriptor.textName].
     */
    fun textName(type: String): String = textNames[type] ?: type

    /**
     * The node that CONSTRUCTS a record of [typeName] positionally, when a host declared one.
     *
     * **So a value prints back the way it was written.** A record folded to a constant can always be
     * printed as `Name { field: …, … }`, and that is what the printer does when there is nothing better.
     * It is not better when the domain gave the type a constructor: `tile(3200, 3200, 0)` is what an
     * author wrote and what every existing script holds, and rewriting all of them into the long form on
     * the first round trip is churn that says nothing.
     *
     * Matched on the RESULT type rather than on a name, because the spelling is the domain's to choose
     * and this file no longer knows any of them. A node qualifies only if its parameters line up with the
     * record's fields one for one, in order — otherwise the positional form would not read back as the
     * same value.
     */
    fun constructorOf(typeName: String): NodeDescriptor? = callable.firstOrNull { d ->
        d.hostKind == dev.ziggle.vscript.vm.HostKind.CONSTRUCT &&
            d.outputs.singleOrNull()?.type?.name.equals(typeName, ignoreCase = true)
    }

    /**
     * The spelling for [type], or **null** when the node has none.
     *
     * Distinct from [textName], which falls back to the type string so a caller printing a name always has
     * one. A consumer deciding what may be OFFERED needs the difference: a Branch and a comment box are
     * syntactic — `if` and a canvas decoration — and putting them in a completion list proposes text the
     * parser will refuse.
     */
    fun textNameOrNull(type: String): String? = textNames[type]

    /**
     * What [written] names, or null when nothing does.
     *
     * Accepts exactly two things: the short name [textName] would emit, and the fully-qualified type. The
     * full form is always accepted so a file is readable whether or not its writer knew which names were
     * ambiguous — but it is only *emitted* where it is needed, so the two directions still meet.
     */
    fun resolveType(written: String): NodeDescriptor? {
        byTextName[written]?.let { return it }
        return catalog[written]?.takeIf { it.type !in SYNTACTIC }
    }

    /**
     * Every text name that resolves **as a bare call**, for "did you mean" and for the palette.
     *
     * Extensions are not here, because they do not resolve bare — `isValid` on its own names nothing, and
     * offering it as though it did produced exactly that complaint from the IDE's own round-trip check.
     * They are offered after a receiver instead; see [extensionNamesOn].
     */
    fun allTextNames(): List<String> =
        callable.filter { it.receiver == null }.map { textName(it.type) }.sorted()

    /** The extensions written on [receiverType], by the name they are written as — for completion. */
    fun extensionNamesOn(receiverType: String): List<String> =
        callable.filter { it.receiver?.name == receiverType }.map { textName(it.type) }.sorted()

    /** Every extension name, whatever it is written on — for "did you mean" after a dot. */
    fun allExtensionNames(): List<String> =
        callable.filter { it.receiver != null }.map { textName(it.type) }.distinct().sorted()

    // ---- pins -----------------------------------------------------------------------------------------

    /** The input pin [written] names on [desc], forgiving how it was spelled. */
    fun input(desc: NodeDescriptor, written: String): PinSpec? = desc.inputLoosely(written)

    /** The output pin [written] names on [desc]. */
    fun output(desc: NodeDescriptor, written: String): PinSpec? = desc.outputLoosely(written)

    companion object {

        /**
         * Node types with a syntax of their own, so never written as a call.
         *
         * The inverse of the mapping table in VSCRIPT_LANG_PLAN.md §6, kept here because it is the thing
         * that makes "one spelling per node" checkable rather than merely intended. A node added to the
         * catalogue with a syntax but not listed here would silently gain a second spelling.
         */
        val SYNTACTIC: Set<String> = buildSet {
            // declarations
            add(BuiltinNodes.ENTRY); add(BuiltinNodes.ENTRY_STOP); add(BuiltinNodes.ENTRY_RENDER)
            add(BuiltinNodes.ENTRY_TICK); add(BuiltinNodes.ENTRY_WAKE); add(BuiltinNodes.ENTRY_SLEEP)
            add(BuiltinNodes.FUNCTION); add(BuiltinNodes.CALL); add(BuiltinNodes.COMMENT)
            // statements
            add(BuiltinNodes.BRANCH); add(BuiltinNodes.IF_SOME); add(BuiltinNodes.WHILE)
            add(BuiltinNodes.FOR_EACH); add(BuiltinNodes.MAP_FOR_EACH)
            add(BuiltinNodes.RETURN); add(BuiltinNodes.SEQUENCE); add(BuiltinNodes.WHEN)
            add(BuiltinNodes.TRY)
            // Written `error(…)` like its pure twin, and reachable only by POSITION — see
            // BuiltinNodes.FAIL_STEP. Naming it would put a second `error` in the palette.
            add(BuiltinNodes.FAIL_STEP)
            // bindings and variables
            add(BuiltinNodes.HOLD); add(BuiltinNodes.VAR_GET); add(BuiltinNodes.VAR_SET)
            add(BuiltinNodes.LOCAL_SET) // written `x = …`, never called
            // values written as literals
            addAll(BuiltinNodes.LITERALS); add(BuiltinNodes.LITERAL_LIST)
            // records
            add(BuiltinNodes.STRUCT_MAKE); add(BuiltinNodes.STRUCT_SPLIT)
            add(BuiltinNodes.STRUCT_GET); add(BuiltinNodes.STRUCT_SET)
            // choices — written `Phase.Chop`, never called
            add(BuiltinNodes.ENUM_OF)
            // operators. `and`/`or` are NOT here: `&&` lowers to a Select, so `logic.and` needs a spelling
            // of its own or it would be unreachable from text.
            add(BuiltinNodes.ADD); add(BuiltinNodes.SUB); add(BuiltinNodes.MUL)
            add(BuiltinNodes.DIV); add(BuiltinNodes.MOD)
            add(BuiltinNodes.EQ); add(BuiltinNodes.NE); add(BuiltinNodes.LT)
            add(BuiltinNodes.LE); add(BuiltinNodes.GT); add(BuiltinNodes.GE)
            add(BuiltinNodes.NOT)        // `!x`
            add(BuiltinNodes.SELECT)     // `c ? a : b`
            add(BuiltinNodes.OR_ELSE)    // `a ?: b`
            add(BuiltinNodes.IF_PRESENT) // `a?.b`
            add(BuiltinNodes.LIST_AT)    // `xs[i]`
            // formatter-owned; the compiler reads through it and the printer drops it
            add(BuiltinNodes.REROUTE)
        }

        /**
         * Text names that are not the type's last segment.
         *
         * Only where the segment reads badly enough to mislead: `list.set` as `set(…)` says nothing about
         * lists and sounds like assignment, which it is not — it returns a copy. Taken from the
         * descriptors' own titles so the two cannot drift apart in spirit.
         */
        val OVERRIDES: Map<String, String> = mapOf(
            // The file and JSON verbs. `readJson`/`writeJson`/`readText`/`writeText` derive correctly
            // from their own last segment and are left alone; these four would derive to a bare `parse`,
            // `text`, `exists`, `delete` and `list`, which say nothing about what they touch — and
            // `text` is already the formatter's, which is the clash trap 3 warns about from both sides.
            BuiltinNodes.JSON_PARSE to "parseJson",
            BuiltinNodes.JSON_TEXT to "jsonText",
            BuiltinNodes.FILE_EXISTS to "fileExists",
            BuiltinNodes.FILE_DELETE to "deleteFile",
            BuiltinNodes.FILE_LIST to "listFiles",
            BuiltinNodes.FILE_FOLDERS to "listFolders",
            BuiltinNodes.LIST_SET to "_listWithItemAt",
            // The higher-order three. Past tense on the two that hand back a list, because `filter(list: …)`
            // reads as an instruction to change the list in place and every list node here hands back a
            // copy — the same reason [BuiltinNodes.LIST_REMOVE] is `without`.
            BuiltinNodes.LIST_MAP to "mapped",
            BuiltinNodes.LIST_FILTER to "filtered",
            BuiltinNodes.LIST_FIRST_WHERE to "firstWhere",
            // Never written: a function reference is spelled as the function's own name, with no call. It
            // is named here so it cannot take `function` off something else, or make something else
            // qualify by arriving — trap 3 fires on BOTH sides of a clash.
            BuiltinNodes.FUNCTION_REF to "functionValue",
        BuiltinNodes.INVOKE to "invoke",
            // `add(…)` would be the derived name and it says nothing about lists — worse, it reads as
            // arithmetic beside `math.add`. Named for its sibling instead, so the pair that edits a list
            // and the pair that grows one look alike.
            BuiltinNodes.LIST_ADD to "_listWithItemAdded",
            // The other half of that pair, and overridden for the same reason twice over: `remove(…)` says
            // nothing about lists, and worse, it reads as though it edits one. Every list node here hands
            // back a COPY, and `without` is the word that says so — so the four that add, replace and take
            // away are named alike and none of them can be mistaken for writing through.
            BuiltinNodes.LIST_REMOVE to "without",
            BuiltinNodes.LIST_REMOVE_AT to "withoutItemAt",
            BuiltinNodes.LIST_COUNT_OF to "count",
            // `format` is what the type says and `text` is what the node is called and what anyone reaches
            // for — `text("Baskets full: {n}", n: count)`. It takes the name off the drawing node, which
            // is the right way round: building a string is far commoner than drawing one. That node now
            // claims `drawText` for itself rather than merely losing, and reads better for it.
            BuiltinNodes.FORMAT to "text",
            // **The whole map family is named explicitly, and that is a decision about a hazard rather
            // than about taste.** Deriving would have each of these claim a short, generic English word —
            // `of`, `with`, `without`, `at`, `has`, `size`, `keys`, `values` — and this file exists because
            // a node claiming a name TAKES it: both sides of a clash qualify, so `size(…)` in an existing
            // script stops parsing the day something else wants `size`. That is not hypothetical here.
            // `map.size` derived to `size` and instantly renamed `test.size` in `LowerTest`; the client's
            // own catalogue is 208 nodes wide and gets new ones without this repo being consulted.
            //
            // So each says which container it is about, exactly as `withItemAdded` and `withItemAt`
            // already do for lists. A map verb and its list sibling then read alike, and neither can take
            // the other's name.
            //
            // **UPDATE: the whole family is `_`-prefixed now, and the hazard above is what settles it.**
            // Naming each verb after its container was a per-case workaround for a general problem: a
            // builtin claiming a short English word TAKES it from every author, for ever. Reserving one
            // PREFIX solves it once — nothing in `_…` can collide with a name anybody would write, so the
            // bare words go back to the people writing scripts. `scheduler/activity` had to rename a
            // method to `size` because a document-level `count` shadowed the builtin; that cannot happen
            // again.
            //
            // These are the primitives, not the surface. `core/list` and `core/map` wrap them as inlined
            // method spellings — `xs.count()`, `m[k]` — and that is what a script should read like.
            //
            // **No aliases.** The old spellings are gone rather than deprecated: two permanent names for
            // one verb is the thing `CANONICAL_FORM` exists to prevent, and a rename nobody is forced to
            // finish is a rename that never finishes. A script using the old word fails at the compiler,
            // by name, which is the whole point of preferring loud over quiet.
            BuiltinNodes.MAP_OF to "_newMap",
            BuiltinNodes.MAP_WITH to "_mapWith",
            BuiltinNodes.MAP_WITHOUT to "_mapWithout",
            BuiltinNodes.MAP_AT to "_mapAt",
            BuiltinNodes.MAP_HAS to "_mapHas",
            BuiltinNodes.MAP_SIZE to "_mapCount",
            BuiltinNodes.MAP_KEYS to "_mapKeys",
            BuiltinNodes.MAP_VALUES to "_mapValues",
            // The list family, which used to derive to bare English words and so claimed every one of
            // them.
            //
            // **Where an INTRINSIC already exists for the same operation, the node takes the same name.**
            // `_listCount` is `Op.LEN` on the text path and `list.count` on the canvas; a script says one
            // word and gets the better implementation of the two. Splitting them — `_listCount` here and
            // `_listCountOf` there — is the duplication this whole rename exists to remove.
            //
            // `list.add`/`list.set` and `map.with` keep SEPARATE names, because they are separate
            // operations: those copy, and `_listAdd`/`_listSet`/`_mapPut` write in place.
            BuiltinNodes.LIST_AT to "_listAt",
            BuiltinNodes.LIST_FIRST to "_listFirst",
            BuiltinNodes.LIST_IS_EMPTY to "_listIsEmpty",
            BuiltinNodes.LIST_CONTAINS to "_listContains",
            BuiltinNodes.LIST_INDEX_OF to "_listIndexOf",
            BuiltinNodes.LIST_CONCAT to "_listConcat",
            BuiltinNodes.LIST_REVERSED to "_listReversed",
            BuiltinNodes.LIST_TAKE to "_listTake",
            BuiltinNodes.LIST_DROP to "_listDrop",
            BuiltinNodes.LIST_SUM to "_listSum",
            BuiltinNodes.LIST_SMALLEST to "_listSmallest",
            BuiltinNodes.LIST_LARGEST to "_listLargest",
            BuiltinNodes.LIST_REMOVE to "_listWithout",
            BuiltinNodes.LIST_REMOVE_AT to "_listWithoutAt",
            BuiltinNodes.LIST_SORTED_BY to "_listSortedBy",
            BuiltinNodes.LIST_COUNT_OF to "_listCount",
        )


        // **There is no reserved-word list, and removing it was the point.**
        //
        // There was one, holding exactly `tile`: `tile(x, y, plane)` is the tile literal's syntax, and the
        // drawing verb `draw.tile` would have DERIVED the same word and taken it. Blocking the derivation
        // worked, but it put a domain's vocabulary in the language's source — this file had to know that
        // some other repo drew tiles, and every future collision would want a line here.
        //
        // [NodeDescriptor.textName] replaces it and inverts the rule. A node that wants a particular
        // spelling CLAIMS it, in the same pass as the language's own overrides and before any derivation
        // runs, so an owner beats a neighbour by construction. The drawing verb is spelled `drawTile`
        // because that is what it is, `tile` is left for whoever constructs one, and neither fact is
        // written down here.

        /**
         * A pin's canonical name as text: `In Combat` → `inCombat`, `If True` → `ifTrue`.
         *
         * The inverse is `NodeDescriptor.inputLoosely`, which already accepts every reasonable spelling —
         * so this only has to produce ONE of them, and consistency matters more than cleverness. Callers
         * must store the canonical `PinSpec.name` in the document, never this: a graph records canonical
         * pin names or a reopened document finds none.
         */
        fun pinText(canonical: String): String {
            val words = canonical.split(' ', '_', '-').filter { it.isNotEmpty() }
            if (words.isEmpty()) return canonical
            return buildString {
                append(words[0].replaceFirstChar { it.lowercaseChar() })
                for (w in words.drop(1)) append(w.replaceFirstChar { it.uppercaseChar() })
            }
        }
    }
}
