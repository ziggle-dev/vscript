package dev.ziggle.vscript.lang

import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.NodeCatalog

/**
 * Source text ↔ [Graph], with every complaint addressed back to the **source**.
 *
 * The four stages — lex, parse, lower, validate — each report failure in their own vocabulary, and only the
 * first two of them know what a line is. That is the whole reason this exists rather than the CLI calling
 * the stages itself: `Validator` speaks in node ids, which is exactly right for a canvas (you click the node)
 * and useless for someone who has never opened one. `Lower` hands back a span per node, so the translation is
 * a lookup — but it is a lookup somebody has to remember to do, in every caller, forever. Doing it once here
 * is what makes the text surface usable without the editor open, which was the point of having one.
 *
 * The stages are also a **funnel, not a pipeline**: a parse error means there is no program to lower, and a
 * lowering error means there is no graph worth validating. Running the next stage on the wreckage of the last
 * produces a second wave of complaints about damage the first one already explained. So each stage returns
 * what it knows and stops, and [Read.graph] is null unless the text survived all four — a caller cannot
 * install a graph that did not.
 */
class VsText(
    private val catalog: NodeCatalog,
    /**
     * Where `import` declarations are looked up.
     *
     * Threaded through all four stages rather than consulted here, because each needs a different half of
     * the answer: `Lower` needs a callee's signature to shape a Call node's pins, `Validator` needs the
     * whole closure to check names and refuse cycles, and `Print` needs the signature again to name the
     * arguments. One source, so the three cannot disagree about what `banking` is.
     */
    private val source: GraphSource = GraphSource.NONE,
) {

    /**
     * What reading a source file produced.
     *
     * [graph] is non-null only when the text is *installable*: parsed, lowered, and validated clean. Warnings
     * do not withhold it — an unrecognised annotation should be visible without being fatal (§8b) — so a
     * caller checks [ok] and reports [warnings] either way.
     */
    class Read(
        val graph: Graph?,
        /** node id → where in the source it came from. Empty when lowering never ran. */
        val spans: Map<Int, Span>,
        val errors: List<VsDiagnostic>,
        val warnings: List<VsDiagnostic>,
        /**
         * The graph as far as lowering got, whether or not it is installable — null only when there was
         * no program to lower at all.
         *
         * **For a document being IMPORTED, not for one being installed.** [graph] withholds anything that
         * did not survive all four stages, which is right for the file in front of you and wrong for its
         * dependencies: an importer needs a callee's signature to shape a Call node's pins, and
         * signatures are collected before any body is lowered. Withholding the whole graph over an error
         * in some unrelated function's body left the importer unable to shape ANY call through it — so one
         * mistake in a library lit up every file that used it with `'sqrt' takes 0 value(s)`, which is
         * exactly the second wave of complaints this class's own doc says not to produce.
         *
         * A consumer that reads this is claiming it only wants the shape. Anything that RUNS the result
         * has to check [ok] as well — see `GraphSource.problem`.
         */
        val lowered: Graph? = graph,
        /** Where this document's comments sat, for a caller that means to write it back out. */
        val comments: Comments = Comments.NONE,
    ) {
        val ok: Boolean get() = graph != null
    }

    /**
     * @param id what identifies the document this text is — its reference. See `Lower.documentId`: without
     *   one, two documents that do not name themselves are indistinguishable to the import closure.
     */
    @JvmOverloads
    fun read(source: String, id: String = ""): Read {
        // The lexer throws rather than collecting: below the level of a statement there is nothing to
        // resynchronise to, so the first bad character is the only honest thing to report.
        val tokens = try {
            Lexer(source).lex()
        } catch (e: VsSyntaxError) {
            return Read(null, emptyMap(), listOf(VsDiagnostic(e.span, e.message)), emptyList())
        }

        val parsed = Parser(tokens, source).parse()
        val warnings = ArrayList(parsed.warnings)
        if (!parsed.ok) {
            return Read(null, emptyMap(), parsed.errors.map { VsDiagnostic(it.span, it.message) }, warnings)
        }

        // `this.` because [read]'s own parameter is called `source` too — one is the TEXT, one is where
        // imports come from, and an unqualified `source` here silently picks the String.
        val lowered = Lower(catalog, Names(catalog), this.source, id).lower(parsed.program)
        warnings += lowered.warnings
        if (!lowered.ok) {
            return Read(null, lowered.spans, lowered.errors, warnings, lowered = lowered.graph)
        }

        val issues = Validator(catalog, this.source, lowered.spans, lowered.declSpans).validate(lowered.graph)
        val errors = issues.filter { it.severity == Severity.ERROR }
            .map { it.asDiagnostic(lowered.spans, lowered.declSpans) }
        warnings += issues.filter { it.severity == Severity.WARNING }
            .map { it.asDiagnostic(lowered.spans, lowered.declSpans) }
        return Read(
            lowered.graph.takeIf { errors.isEmpty() },
            lowered.spans,
            errors,
            warnings,
            lowered = lowered.graph,
            comments = plan(parsed.commentsBefore, lowered.spans, lowered.declSpans),
        )
    }

    /**
     * Turn "a comment at offset N" into "a comment above node 7" / "above `fn add`".
     *
     * Both sides are keyed by the offset the construct STARTS at, so the join is exact rather than a
     * nearest-match: the parser recorded the offset of the token following each comment, and lowering
     * recorded the span of the construct that token began. A comment introducing something that lowered to
     * nothing simply finds no key and is dropped, which is the same thing that happens to the construct.
     */
    private fun plan(
        before: Map<Int, List<String>>,
        spans: Map<Int, Span>,
        declSpans: Map<String, Span>,
    ): Comments {
        if (before.isEmpty()) return Comments.NONE
        val nodes = HashMap<Int, List<String>>()
        for ((id, span) in spans) before[span.start]?.let { nodes[id] = it }
        val decls = HashMap<String, List<String>>()
        for ((name, span) in declSpans) before[span.start]?.let { decls[name] = it }
        return Comments(nodes, decls, before[-1].orEmpty())
    }

    /**
     * [graph] as source.
     *
     * One form, not two. There is no layout mode: positions and node ids are implementation details of the
     * canvas, nobody writes them by hand, and a file carrying them changes every time somebody nudges a
     * node. Going text → graph is therefore **lossy in the visual arrangement** and exact in what runs —
     * the importer arranges headlessly, which is what auto-layout is for.
     */
    @JvmOverloads
    fun write(graph: Graph, comments: Comments = Comments.NONE): String =
        Print(catalog, Names(catalog), source, comments).print(graph)

    companion object {

    }

    /**
     * A validator [Issue] said in the source's terms.
     *
     * The join itself now happens in `Validator.validate`, which is given the same tables — doing it there
     * means everything that reads an issue is located, not only this one caller (see `Issue.span`). This
     * still passes them so a caller that built its own `Issue` by hand is not left unlocated.
     */
    private fun dev.ziggle.vscript.compile.Issue.asDiagnostic(
        spans: Map<Int, Span>,
        declSpans: Map<String, Span> = emptyMap(),
    ): VsDiagnostic {
        val where = pin?.let { " ($it)" } ?: ""
        return VsDiagnostic(located(spans, declSpans).span, message + where)
    }
}
