package dev.ziggle.vscript.editor.text

import dev.ziggle.vscript.lang.Span
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.Binding
import dev.ziggle.vscript.lang.CallExpr
import dev.ziggle.vscript.lang.MemberExpr
import dev.ziggle.vscript.text.ExtensionCallee
import dev.ziggle.vscript.text.FunctionCallee
import dev.ziggle.vscript.text.ImportedCallee
import dev.ziggle.vscript.text.NativeBinding
import dev.ziggle.vscript.text.NativeCallee
import dev.ziggle.vscript.text.Resolution

/**
 * What is under the cursor, and everything the editor wants to say about it.
 *
 * ### Four features, one question
 *
 * Go to declaration, find usages, hover documentation and hover error are all "what is at this offset,
 * and what do we know about it". Answering that once means they cannot disagree — and disagreeing here is
 * the worst kind, because the hover would describe one thing and the jump would take you to another.
 *
 * ### It indexes what the resolver already built
 *
 * `Resolution.bindingOf` is keyed by the `NameExpr` OBJECTS the parser produced, and every AST node
 * carries its span. So the offset index is a pass over a map that already exists rather than a walk of
 * the tree — and, more importantly, the answers are the compiler's own. A separate name resolver in the
 * editor is how a go-to-declaration comes to disagree with what actually runs, which is exactly the
 * duplication the IntelliJ plugin pays for by having its own PSI.
 *
 * ### Built from a best-effort resolution
 *
 * `TextFrontEnd.analyse` keeps a resolution even when the file does not compile, so navigation works while
 * the file is broken — which is when it is wanted, because a name you cannot place is a name you are
 * probably about to look up.
 */
class Navigation(
    private val resolution: Resolution,
    private val comments: Map<Int, List<String>>,
    private val catalog: NodeCatalog,
) {

    /** What the cursor is on. */
    class Target(
        val name: String,
        /** Where the cursor's own token is, for underlining it. */
        val at: Span,
        /** Where it is declared — null for a host node, which has no source. */
        val declaration: Span?,
        /** Every place the same binding is named, declaration included, in source order. */
        val usages: List<Span>,
        /** The signature line for a hover: `fn helper(n: INT) -> INT`. */
        val signature: String,
        /** The comment above the declaration, joined — the language has no `///`. */
        val documentation: String,
        /**
         * The document the declaration is in, when it is not this one.
         *
         * Null means "here". A name reached through an import is declared in another file, and a jump
         * that only carried a LINE would land on that line of the file you were already looking at —
         * which is worse than not jumping, because it looks like it worked.
         */
        val declarationRef: String? = null,
    ) {
        val isHostNode: Boolean get() = declaration == null

        /** True when following this means opening a different document first. */
        val isElsewhere: Boolean get() = declarationRef != null
    }

    /**
     * Every place a binding is NAMED, reads and writes alike.
     *
     * **Writes come from a different map, and missing them is the obvious way to get this wrong.**
     * `bindingOf` is keyed by `NameExpr`, which is an expression — and the left-hand side of `Count = 1`
     * is not one: `AssignStmt` carries a bare `name: String`, and the resolver files what it binds to
     * under `targetOf` instead. Index only `bindingOf` and find-usages silently returns reads, so a
     * variable assigned in five places and read in two reports two usages. That is worse than no feature,
     * because the list looks complete.
     *
     * An `AssignStmt`'s span covers the whole statement, so the name is taken as the first `length`
     * characters of it — the target is what a statement starts with.
     */
    /** The import alias a name was written through, by its offset — `util` in `util::bump`. */
    private val aliasOf = HashMap<Int, String?>()

    private val names: List<Pair<Span, Binding>> by lazy {
        val out = ArrayList<Pair<Span, Binding>>(resolution.bindingOf.size + resolution.targetOf.size)
        for ((expr, binding) in resolution.bindingOf) {
            out += expr.span to binding
            aliasOf[expr.span.start] = expr.module
        }
        for ((stmt, binding) in resolution.targetOf) {
            val s = stmt.span
            out += Span(s.start, s.start + stmt.name.length, s.line, s.col) to binding
        }
        out.sortedBy { it.first.start }
    }

    /**
     * Field accesses — `p.x` — with the span of the whole expression.
     *
     * There is no `MemberExpr -> field` map to read: the resolver records `resultPick` for multi-result
     * calls and nothing else about members, because the compiler works the field out from the target's
     * TYPE and never needs to name it again. So this reconstructs the same step — target type, record,
     * field — which keeps the answer the compiler's rather than a second opinion.
     */
    private val members: List<Pair<Span, MemberExpr>> by lazy {
        resolution.typeOf.keys.filterIsInstance<MemberExpr>()
            .map { it.span to it }
            .sortedBy { it.first.start }
    }

    private val calls: List<Pair<Span, CallExpr>> by lazy {
        resolution.calleeOf.keys.map { it.span to it }.sortedBy { it.first.start }
    }

    /**
     * The target at [offset], or null on whitespace, a keyword or a comment.
     *
     * ### A member call and its receiver share ONE span, so position decides
     *
     * `xs.second()` does not parse into a receiver node and a method node. It is one `CallExpr` whose
     * `target` is `["xs", "second"]` — and the resolver also files a `NameExpr` for `xs` **with the span
     * of the whole call**. Measured:
     *
     * ```
     * name  xs[132,143)='xs.second()'
     * call  xs.second[132,143)  ExtensionCallee
     * ```
     *
     * So the two candidates are exactly as wide as each other, "the narrowest wins" is a tie, and
     * whichever index is consulted first always answers. Names were, so clicking `second` navigated to
     * `xs` — reported as go-to-declaration doing nothing on extensions, because the caret did move, to
     * the receiver's declaration.
     *
     * The receiver is the FIRST segment of the target and the method is the last, so the boundary is the
     * end of the first segment: before it the answer is the receiver, at or after it the method.
     *
     * ### Otherwise, the narrowest span wins
     *
     * A call's span covers its whole argument list, so a cursor on an argument is inside both the
     * argument's name and the call — and answering with the call would make go-to-declaration jump to the
     * function whenever you clicked one of its arguments.
     */
    fun at(offset: Int): Target? {
        val call = calls.filter { offset >= it.first.start && offset < it.first.end }
            .minByOrNull { it.first.end - it.first.start }
        val name = names.filter { offset >= it.first.start && offset < it.first.end }
            .minByOrNull { it.first.end - it.first.start }

        // A field access shares its span with its target, exactly as a member call does — `p` and `p.x`
        // are both recorded over `p.x`. The member name sits at the END of the expression, so anything at
        // or past that boundary is the field rather than the thing it is read from.
        val member = members.filter { offset >= it.first.start && offset < it.first.end }
            .minByOrNull { it.first.end - it.first.start }
        if (member != null) {
            val name = member.second.member
            val memberStart = member.first.end - name.length
            if (offset >= memberStart) {
                fieldTarget(Span(memberStart, member.first.end, member.first.line, member.first.col), member.second)
                    ?.let { return it }
            }
        }

        // A dotted target is a member call: the first segment is the receiver, the last is the method.
        if (call != null && call.second.target.size > 1) {
            val receiverEnd = call.first.start + call.second.target.first().length
            if (offset >= receiverEnd) return fromCall(methodSpan(call.first, call.second), call.second)
        }
        if (name != null) return fromBinding(name.first, name.second)
        if (call != null) return fromCall(call.first, call.second)
        return null
    }

    /**
     * Just the method name of a member call, for underlining.
     *
     * Underlining the whole `xs.second()` would say a click anywhere in it does the same thing, and the
     * line above is the rule that it does not.
     */
    private fun methodSpan(span: Span, call: CallExpr): Span {
        val method = call.target.last()
        val start = span.start + call.target.dropLast(1).sumOf { it.length + 1 }
        return Span(start, start + method.length, span.line, span.col)
    }

    // ---- what we know about each kind ---------------------------------------------------------------

    private fun fromBinding(at: Span, binding: Binding): Target {
        val declaration = binding.span.takeIf { it != Span.NONE }
        val ref = declaringRef(aliasOf[at.start], nameOf(binding))
        val usages = names.filter { it.second === binding }.map { it.first }
            .let { found -> (listOfNotNull(declaration) + found).distinctBy { it.start }.sortedBy { it.start } }
        return Target(
            name = nameOf(binding),
            at = at,
            declaration = declaration,
            usages = usages,
            signature = signatureOf(binding),
            documentation = docAt(declaration?.start),
            declarationRef = ref,
        )
    }

    private fun fromCall(at: Span, call: CallExpr): Target {
        val callee = resolution.calleeOf[call]
        val declaration = when (callee) {
            is FunctionCallee -> callee.binding.span
            is ImportedCallee -> callee.binding.span
            is ExtensionCallee -> callee.binding.span
            else -> null
        }?.takeIf { it != Span.NONE }
        // An imported callee's span is a position in ANOTHER document. Carrying the ref is what turns
        // that from a wrong line here into a jump there.
        val ref = (callee as? ImportedCallee)?.module?.ref
            ?: declaringRef(call.module, call.target.last())
        val name = when (callee) {
            is NativeCallee -> callee.fn.name
            is FunctionCallee -> callee.binding.name
            is ImportedCallee -> callee.binding.name
            is ExtensionCallee -> callee.binding.name
            else -> ""
        }
        return Target(
            name = name,
            at = at,
            declaration = declaration,
            usages = listOfNotNull(declaration),
            signature = if (callee is NativeCallee) nativeSignature(callee) else name,
            // A host node has no source, so its documentation is the catalogue's summary rather than a
            // comment — which is the same text the palette shows, so the two cannot drift apart.
            documentation = if (callee is NativeCallee) summaryOf(callee.fn.name) else docAt(declaration?.start),
            declarationRef = ref,
        )
    }

    /**
     * Which document declares [name], written through [alias] — or null when it is declared here.
     *
     * **All THREE import maps, and checking only one is how a jump lands somewhere random.**
     * `ImportedNames` keeps them apart because the language has three forms, and the one this originally
     * consulted — `named` — is the least used of them:
     *
     * | written | lands in |
     * |---|---|
     * | `import * as util from "x"`, used `util::bump` | `aliased` |
     * | `import { bump } from "x"`, used `bump` | `named` |
     * | an unqualified star import | `unqualified` |
     *
     * Miss the form in use and the ref comes back null, the target looks locally declared, and a line
     * number belonging to ANOTHER document is applied to this one. That is not a failed jump — it is a
     * successful jump to an arbitrary line, which is what "it goes to random spots" looks like.
     */
    private fun declaringRef(alias: String?, name: String): String? {
        alias?.let { a -> resolution.imported.aliased[a]?.let { return it.ref } }
        resolution.imported.named[name]?.let { return it.first.ref }
        // An unqualified star import brings names in with no marker at all, so the only way to tell which
        // module a name came from is to ask which of them exports it.
        resolution.imported.unqualified.firstOrNull { it.resolution.exports.containsKey(name) }
            ?.let { return it.ref }
        return null
    }

    /**
     * A field read, resolved through the record its target is typed as.
     *
     * The record carries a span; a [dev.ziggle.vscript.text.Param] does not — fields are a list on the
     * declaration and have no positions of their own. So the record's own line is the floor, and the
     * field's line is found by looking for it from there. That is a search rather than a fact, and it is
     * bounded to the declaration it starts in, so the worst case is landing on the `type` line: right
     * document, right declaration, one or two lines high.
     */
    private fun fieldTarget(at: Span, member: MemberExpr): Target? {
        val targetType = resolution.typeOf[member.target] ?: return null
        val record = resolution.records[targetType.required().name] ?: return null
        val field = record.field(member.member) ?: return null
        return Target(
            name = member.member,
            at = at,
            declaration = record.span.takeIf { it != Span.NONE },
            usages = emptyList(),
            signature = "${record.name}.${field.name}: ${field.type}",
            documentation = docAt(record.span.start),
            // `RecordType.owner` is the document that DECLARED the type -- stamped on every `TypeRef` it
            // hands out, which is how a reader that never imported the declaring module still recognises
            // it. Null means it was declared here.
            declarationRef = record.owner,
        )
    }

    private fun nameOf(binding: Binding): String = when (binding) {
        is NativeBinding -> binding.fn.name
        else -> runCatching { binding.javaClass.getMethod("getName").invoke(binding) as? String }
            .getOrNull().orEmpty()
    }

    private fun signatureOf(binding: Binding): String {
        val name = nameOf(binding)
        if (binding is NativeBinding) return nativeSignature(NativeCallee(binding.fn))
        return name
    }

    private fun nativeSignature(callee: NativeCallee): String {
        val fn = callee.fn
        val params = fn.params.joinToString(", ") { "${it.name}: ${it.type}" }
        val results = fn.results.joinToString(", ") { it.type.toString() }
        return if (results.isBlank()) "${fn.name}($params)" else "${fn.name}($params) -> $results"
    }

    /** The comment run immediately above the token at [start]. */
    private fun docAt(start: Int?): String {
        if (start == null) return ""
        val lines = comments[start] ?: return ""
        // `//` stripped, blank runs collapsed — the text is prose, and showing its punctuation in a tooltip
        // is showing the reader the syntax rather than the sentence.
        return lines.joinToString("\n") { it.removePrefix("//").trim() }.trim()
    }

    private fun summaryOf(textName: String): String {
        val descriptor = catalog.all.firstOrNull { it.textName == textName || it.type.substringAfterLast('.') == textName }
        return descriptor?.summary.orEmpty()
    }
}
