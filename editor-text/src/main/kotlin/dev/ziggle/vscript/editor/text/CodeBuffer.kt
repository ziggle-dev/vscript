package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.TextDiagnostic
import dev.ziggle.vscript.text.TextFrontEnd
import dev.ziggle.vscript.text.TextSource
import dev.ziggle.vscript.text.natives

/**
 * The text surface's document, with no drawing in it.
 *
 * Everything the code view does that can be got wrong lives here — holding the source and saying what is
 * wrong with it — so it can be tested without an ImGui context. `CodeView` is then only a gutter, a font
 * and a caret.
 *
 * ### What it stopped being
 *
 * It used to run `VsText`: print a `Graph` out as source, re-lower what was typed, and hand a new `Graph`
 * back to the canvas on Apply. That is the interchange between the two authoring surfaces, and the two
 * surfaces are separate — they share the node implementations and the bytecode, and nothing else. So the
 * printing, the applying and the node-to-line mapping are gone, and with them the whole apparatus that
 * existed to make an edit here rearrange a canvas over there.
 *
 * What remains is the honest job: a `.vs` is a program in a text language, and this compiles it through
 * the front end that language actually has. Which also fixes a divergence — the diagnostics shown while
 * typing are now from the same compiler that runs the file, where before they came from the graph
 * lowering and could disagree with it.
 */
class CodeBuffer(
    private val catalog: NodeCatalog,
    /**
     * Where an `import` in this buffer resolves.
     *
     * [TextSource.NONE] is not "imports are broken" — it is a buffer that imports nothing, which is what
     * a test and a scratch file both mean.
     */
    private val imports: TextSource = TextSource.NONE,
) {

    /** What the buffer held when it was last loaded or saved. */
    var baseline: String = ""
        private set

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            lines = LineIndex.of(value)
            recheck()
        }

    /**
     * Where every line starts — rebuilt on edit, never on frame.
     *
     * The code view was asking `text.count { it == '\n' } + 1` once per frame to size its gutter. That is
     * a scan of the whole document at sixty hertz to answer a question that only changes when somebody
     * types, and it is the cheap half of what this replaces — see [on].
     */
    var lines: LineIndex = LineIndex.of("")
        private set

    var errors: List<TextDiagnostic> = emptyList()
        private set

    var warnings: List<TextDiagnostic> = emptyList()
        private set

    /** Has anything been typed since the buffer was loaded or last saved? */
    val dirty: Boolean get() = text != baseline

    /** True when the buffer is a program the front end accepts. */
    var compiles: Boolean = false
        private set

    /**
     * The lines this program can actually stop on — for the gutter to decide what is clickable.
     *
     * **Lines, never the site ids they came from, and the distinction is the whole point.** `Sites.idOf`
     * hands out `next++` over AST identity, so the ids in THIS compilation are not the ids in the one that
     * eventually runs; arming against them would produce a breakpoint that silently never fires. Which
     * lines have a site is a property of the source and survives recompilation, so that is what leaves
     * this class. See [LineBreakpoints].
     *
     * Free, incidentally: `sites.idOf` is called whether or not the compile is a debug one — only the
     * `TRACE` emission is gated — so a buffer that already compiles on every keystroke already knows this.
     */
    var armableLines: Set<Int> = emptySet()
        private set

    /** Take [source] as the buffer's contents and its new baseline. */
    fun load(source: String) {
        text = source
        baseline = source
    }

    /** The buffer has been written to disk: what is here is now what is there. */
    fun saved() {
        baseline = text
    }

    /**
     * Diagnostics on [line] (1-based), for the gutter.
     *
     * **Bucketed once per check, not filtered once per row.** This used to be
     * `(errors + warnings).filter { it.span.line == line }`, called by the paint loop for every visible
     * row — so a file with a hundred diagnostics did a hundred comparisons times fifty rows, every frame,
     * and allocated a joined list each time. The work is the same shape either way; the difference is that
     * it now happens when the diagnostics CHANGE.
     *
     * Empty list for a clean line, deliberately shared rather than allocated.
     */
    fun on(line: Int): List<TextDiagnostic> = byLine[line] ?: emptyList()

    private var byLine: Map<Int, List<TextDiagnostic>> = emptyMap()

    /**
     * What the file MEANS, best-effort — kept even while it does not compile.
     *
     * Everything semantic an editor wants is here: what each name binds to, the type of each expression,
     * which lines can hold a breakpoint. Go-to-declaration, hover and completion are lookups against this
     * rather than analyses of their own.
     *
     * **Null only when nothing at all could be understood.** `analyse` re-lexes up to a bad character and
     * resolves whatever the parser recovered, so an unclosed quote or an unfinished `fn` costs the broken
     * declaration and not the file — which matters because the moment somebody most wants to know what is
     * in scope is the moment they are halfway through typing a name.
     */
    var meaning: dev.ziggle.vscript.text.Resolution? = null
        private set

    /**
     * Comments, keyed by the offset of the token they introduce.
     *
     * The language has no `///` — the comment above a declaration IS its documentation — so this is where
     * a hover reads from. Kept beside [meaning] because the two are only useful together.
     */
    var comments: Map<Int, List<String>> = emptyMap()
        private set

    /**
     * What is under an offset: its declaration, its usages, its documentation.
     *
     * Rebuilt when the analysis changes rather than per query — it indexes the resolver's own maps, and
     * doing that on every mouse-move would be a pass over every name in the document per frame.
     */
    var navigation: Navigation? = null
        private set

    /**
     * Re-check on every keystroke.
     *
     * `analyse`, which is `compile` that does not throw the resolution away. Still emits — a construct the
     * resolver accepts and the emitter refuses is a file that will not run, and the author should hear
     * about it while typing rather than at Run — so the verdict is unchanged and the semantics come free
     * from the same pass rather than from a second one.
     */
    private fun recheck() {
        val front = TextFrontEnd(catalog.natives(), imports = imports, debug = false)
        val compiled = runCatching { front.analyse(text) }.getOrNull()
        if (compiled == null) {
            // The compiler is allowed to be surprised by a half-typed file; the editor is not allowed to
            // stop working because of it. Keep the last diagnostics rather than flashing them off.
            compiles = false
            return
        }
        errors = compiled.errors
        warnings = compiled.diagnostics.filter { it !in compiled.errors }
        compiles = compiled.complete
        // Kept even when the file is broken -- that is the whole point of `analyse` over `compile`.
        compiled.resolution?.let {
            meaning = it
            comments = compiled.comments
            navigation = Navigation(it, compiled.comments, catalog)
        }
        // Bucketed here, where the diagnostics change, rather than in the paint loop -- see [on].
        byLine = (errors + warnings).groupBy { it.span.line }
        armableLines = front.sites.spans().values.mapTo(HashSet()) { it.line }
    }
}
