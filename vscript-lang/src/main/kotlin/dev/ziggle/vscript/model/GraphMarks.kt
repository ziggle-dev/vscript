package dev.ziggle.vscript.model

/**
 * The annotations a document carries that are SPELLING rather than content.
 *
 * ### Why these are here and not on the lowering that writes them
 *
 * They were `Lower`'s companion constants, which was true while text and canvas were one round trip:
 * `Lower` wrote them going one way and `Print` read them coming back. The two authoring surfaces are
 * separate now, and `Lower` — text into a canvas document — has left the published jar for
 * `testFixtures`, because nothing in the product converts between them any more.
 *
 * `Print` did not go with it: reading a canvas document out as `.vs` is a one-way export and an
 * inspection aid, and the `graph text` / `graph export` commands are built on it. So the keys the two
 * halves agree on live here, in the model, where they always belonged — they are facts about what a
 * document stores, not about how one was produced.
 *
 * ### What "syntactic" means
 *
 * A literal in [SYNTACTIC_LITERALS] is one the printer turns back into SYNTAX rather than into an
 * annotation: `CONST_EXPORTED` comes back as the word `export`, `MUTABLE` as the word `var`,
 * `ASSIGN_OP` as the `+` in `n += 1`. They are also what keeps the editor's literal panel honest — a
 * pin nobody typed should not be offered as one to edit.
 */
object GraphMarks {

    /** The IMPORTED const a folded literal stands in for — `limits::Limit`. */
    const val IMPORTED_CONST = "@from"

    /** A `for` loop's element binding, by name. */
    const val LOOP_ELEMENT = "@element"

    /** A `for` loop's index binding, by name. */
    const val LOOP_INDEX = "@index"

    // Re-stated from BuiltinNodes so one import answers "what marks can a document carry".
    const val CONST_NAME = BuiltinNodes.CONST_NAME
    const val CONST_EXPORTED = BuiltinNodes.CONST_EXPORTED
    const val CONST_TYPE = BuiltinNodes.CONST_TYPE
    const val INIT_MARK = BuiltinNodes.INIT_MARK

    val SYNTACTIC_LITERALS: Set<String> = setOf(
        CONST_NAME, IMPORTED_CONST, LOOP_ELEMENT, LOOP_INDEX, INIT_MARK,
        // Spelling: it comes back as the word `export` beside the name, exactly as CONST_NAME
        // comes back as the name itself rather than as `@name("Speed")`.
        CONST_EXPORTED,
        // Spelling too: it comes back as `: TILE` beside the name rather than as `@type(TILE)`.
        CONST_TYPE,
        BuiltinNodes.MUTABLE, BuiltinNodes.LOCAL_TARGET, BuiltinNodes.HOLD_TYPE,
        // Worked out rather than written, and printed as nothing at all — see HOLD_INFERRED.
        BuiltinNodes.HOLD_INFERRED,
        // Spelling, not an annotation the author wrote: it comes back as `s.f = v` rather than as
        // `@wroteField(true)`, exactly as MUTABLE comes back as the word `var`.
        BuiltinNodes.WROTE_FIELD,
        // And this one comes back as `xs.add(3)` rather than as `@wroteReceiver(true) xs = xs.add(…)`.
        BuiltinNodes.WROTE_RECEIVER,
        // And this comes back as the `+` in `n += 1`, not as `@assignOp("math.add")`.
        BuiltinNodes.ASSIGN_OP,
        // Likewise: it comes back as the word `else`, not as `@else(true)`.
        BuiltinNodes.WHEN_HAS_ELSE,
        // And this comes back as the names inside a `let (…)`.
        BuiltinNodes.BOUND,
        // And this one comes back as the ABSENCE of braces, which is the whole of what it records.
        BuiltinNodes.BARE,
        // And this comes back as the word `private` in front of `on`.
        BuiltinNodes.ALWAYS_ENTRY,
        // And this comes back as HOW MANY arguments were written after `invoke(f, …)`, which is the
        // same thing a list literal's slot count records — a shape, not a note.
        BuiltinNodes.INVOKE_COUNT,
        // And this comes back as the choice between `f(x)` and `invoke(f, x)`.
        BuiltinNodes.INVOKE_WRITTEN,
    )
}
