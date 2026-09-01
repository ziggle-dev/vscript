package dev.ziggle.vscript.text

import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef

/**
 * The calls the COMPILER emits itself, rather than handing to a host.
 *
 * Two families, for two different reasons.
 *
 * **A host function cannot call back into the VM.** It is a Kotlin lambda handed an array of arguments; it
 * has no fiber, no frame and no way to enter a chunk. So the three verbs that need to invoke a function
 * per element cannot be hosts, and the graph compiler already answers that by emitting the loop inline
 * with a `CALLV` in it. This is the same answer, and deliberately the same shape — two spellings of one
 * semantics is how the surfaces would start to differ about what `filtered` means.
 *
 * **And some things are instructions.** `delay` parks the fiber, `and` and `or` are one opcode each; all
 * three carry no host in the catalogue because the graph compiler lowers them, which is exactly why the
 * catalogue adapter drops them. `delay` is the second most-used call in the corpus, so "dropped correctly"
 * would have meant "missing" — the two families live here together because what they have in common is
 * that the compiler, not a host, is what runs them.
 *
 * Their signatures are generic, which is what makes them ordinary now: `mapped` is
 * `(LIST<T>, fn(T) -> U) -> LIST<U>`, and every part of that is inferred by the same rules any other call
 * uses. Before there were type variables at a call site, these could only have been special cases.
 */
enum class Intrinsic(
    /** How it is written. */
    val fnName: String,
    /** What it calls the function it takes — each has its own word, and each reads as English. */
    val fnParam: String,
) {
    MAPPED("mapped", "using"),
    FILTERED("filtered", "keeping"),
    FIRST_WHERE("firstWhere", "matching"),

    /** `delay(ms: 600)` — park the fiber. `Op.SLEEP`, and the reason a script does not spin. */
    DELAY("delay", ""),

    // `and`/`or` evaluate BOTH sides, always — which is the whole difference between them and `&&`/`||`,
    // and the reason both spellings exist rather than one being sugar for the other.
    AND("and", ""),
    OR("or", ""),



    // ---- the map primitives -------------------------------------------------------------------
    //
    // **`_`-prefixed, positional, and not meant to be written by hand.** They are reached through the
    // inlined helpers in `core/map`; see `docs/VSCRIPT_CONTAINERS_PLAN.md` §2.4 for why the primitives get a
    // reserved namespace. Two reasons, both paid for already:
    //
    //  - a bare name taken by the language is a name an author cannot use. `scheduler/activity` had to
    //    rename a method to `size` because a document-level `count` shadowed the builtin and every
    //    `count(list: …)` in the file then resolved to the wrong one;
    //  - it is what lets `withEntry` keep meaning "hand me back a copy" while `_mapPut` means "write into
    //    this one", instead of one spelling quietly changing meaning underneath the corpus.
    //
    // Each is exactly one instruction. That is the whole point: `withEntry` was a host call that copied
    // the map, measured at 20ms per insert on a map of 3000.
    // The list half. `NEW_LIST`/`LIST_ADD` are the two the accumulator loop needs; `count` already
    // lowers to `Op.LEN`, so `_listCount` exists for the namespace's sake rather than for speed.
    NEW_LIST("_newList", ""),
    LIST_ADD("_listAdd", ""),
    LIST_AT("_listAt", ""),
    LIST_SET("_listSet", ""),
    LIST_COUNT("_listCount", ""),

    NEW_MAP("_newMap", ""),
    MAP_PUT("_mapPut", ""),
    MAP_AT("_mapAt", ""),
    MAP_HAS("_mapHas", ""),
    MAP_COUNT("_mapCount", ""),
    MAP_DROP("_mapDrop", ""),

    // ---- ids ------------------------------------------------------------------------------------
    //
    // **Gone, and to the node pack.** `ItemId`, `ObjectId`, `NpcId`, `toItem`, `toObject`, `toNpc` and
    // `isValid` used to live here — seven of one game's verbs in a language that is supposed to know
    // nothing about any game. They were here because a host had no way to declare a function written ON a
    // value; it has one now (`NativeFn.receiver`), and `HostKind.CAST` keeps a conversion free at run
    // time, so the pack declares them and pays exactly what these did: nothing.

    ;

    /**
     * What it is written ON, or empty when it is called rather than written on something.
     *
     * **Empty for everything now.** `toColor` was the last receiver-form intrinsic — `0xFFAA00.toColor()`
     * — and it is a node the drawing pack declares, reached through `NativeFn.receiver` like any other
     * host verb written on a value. Kept because the CONCEPT is the language's: an intrinsic that is
     * written on something is a shape this file may want again, and the resolver asks unconditionally.
     */
    val receiverKinds: Set<PinType> get() = emptySet()

    val isReceiverForm: Boolean get() = receiverKinds.isNotEmpty()

    val signature: Signature by lazy {
        val t = TypeRef.named("T").asVariable()
        val u = TypeRef.named("U").asVariable()
        val k = TypeRef.named("K").asVariable()
        val v = TypeRef.named("V").asVariable()
        val bool = TypeRef(PinType.BOOL)
        val int = TypeRef(PinType.INT)
        when (this) {
            NEW_LIST -> Signature(fnName, emptyList(), listOf(Param(RESULT, TypeRef.list(t))))

            LIST_ADD -> Signature(
                fnName,
                listOf(Param("list", TypeRef.list(t)), Param("value", t)),
                emptyList(),
            )

            LIST_AT -> Signature(
                fnName,
                listOf(Param("list", TypeRef.list(t)), Param("index", int)),
                listOf(Param(RESULT, t)),
            )

            LIST_SET -> Signature(
                fnName,
                listOf(Param("list", TypeRef.list(t)), Param("index", int), Param("value", t)),
                emptyList(),
            )

            LIST_COUNT -> Signature(fnName, listOf(Param("list", TypeRef.list(t))), listOf(Param(RESULT, int)))

            // The map primitives. `K` and `V` are ordinary type variables bound at the call site, exactly
            // as `T` is for the list verbs — nothing here is a special case in the checker.
            NEW_MAP -> Signature(fnName, emptyList(), listOf(Param(RESULT, TypeRef.map(k, v))))

            MAP_PUT -> Signature(
                fnName,
                listOf(Param("map", TypeRef.map(k, v)), Param("key", k), Param("value", v)),
                emptyList(),
            )

            // **`V?`, because a key that was never set is the ordinary case.** Same reasoning as
            // FIRST_WHERE's optional: the absence is a state with an answer, not a failure, and typing it
            // is what makes the caller handle it rather than discover it.
            MAP_AT -> Signature(
                fnName,
                listOf(Param("map", TypeRef.map(k, v)), Param("key", k)),
                listOf(Param(RESULT, v.orNull())),
            )

            MAP_HAS -> Signature(
                fnName,
                listOf(Param("map", TypeRef.map(k, v)), Param("key", k)),
                listOf(Param(RESULT, bool)),
            )

            MAP_COUNT -> Signature(fnName, listOf(Param("map", TypeRef.map(k, v))), listOf(Param(RESULT, int)))

            MAP_DROP -> Signature(
                fnName,
                listOf(Param("map", TypeRef.map(k, v)), Param("key", k)),
                emptyList(),
            )

            // The pin names are the catalogue's, so a script written against the graph reads unchanged.
            DELAY -> Signature(fnName, listOf(Param("Ms", int, default = 600, hasDefault = true)), emptyList())

            AND, OR -> Signature(
                fnName,
                listOf(
                    Param("A", bool, default = false, hasDefault = true),
                    Param("B", bool, default = false, hasDefault = true),
                ),
                listOf(Param(RESULT, bool)),
            )

            MAPPED -> Signature(
                fnName,
                listOf(Param("list", TypeRef.list(t)), Param(fnParam, TypeRef.function(listOf(t), u))),
                listOf(Param(RESULT, TypeRef.list(u))),
            )

            FILTERED -> Signature(
                fnName,
                listOf(Param("list", TypeRef.list(t)), Param(fnParam, TypeRef.function(listOf(t), bool))),
                listOf(Param(RESULT, TypeRef.list(t))),
            )

            // **`T?`, because it may find none.** The node has always been able to answer with nothing;
            // what was missing was the `?` in the type, and an optional result is what makes the caller
            // handle the empty case rather than discover it.
            FIRST_WHERE -> Signature(
                fnName,
                listOf(Param("list", TypeRef.list(t)), Param(fnParam, TypeRef.function(listOf(t), bool))),
                listOf(Param(RESULT, t.orNull())),
            )
        }
    }

    companion object {
        // Only the ones written as a plain call. `toItem` is written on a value, so offering it here too
        // would give it a second spelling — and one spelling per thing is the rule this language keeps.
        private val byName = entries.filterNot { it.isReceiverForm }.associateBy { it.fnName }

        operator fun get(name: String): Intrinsic? = byName[name]

        /** `3.toItem()` — a built-in extension, by what it is written on and what it is called. */
        fun on(receiver: TypeRef, name: String): Intrinsic? = entries.firstOrNull {
            it.fnName == name && receiver.builtin in it.receiverKinds
        }
    }
}
