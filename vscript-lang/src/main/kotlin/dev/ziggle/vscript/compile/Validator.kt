package dev.ziggle.vscript.compile

import dev.ziggle.vscript.lang.Span
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.vm.HostKind
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.ImportClosure
import dev.ziggle.vscript.model.ImportScope
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.Types
import dev.ziggle.vscript.model.canConnect
import dev.ziggle.vscript.model.conversionFor
import dev.ziggle.vscript.model.expressionCalls
import dev.ziggle.vscript.model.effectivePinType
import dev.ziggle.vscript.model.resolveNode

/** How bad a [Issue] is. Only [ERROR] blocks compilation. */
enum class Severity { ERROR, WARNING }

/**
 * One problem with a graph, addressed to something the user can see and click.
 *
 * [nodeId] / [linkId] are what the editor highlights — a validation message with no anchor is nearly
 * useless on a canvas of eighty nodes.
 */
class Issue(
    val severity: Severity,
    val message: String,
    val nodeId: Int? = null,
    val linkId: Int? = null,
    val pin: String? = null,
    /**
     * Where in the SOURCE this is, when the graph came from text — filled in by [Validator.validate], not
     * by whatever built the issue.
     *
     * The validator works on a graph and has never seen a line, which is why every issue in this file is
     * constructed without one. That was fine as long as somebody downstream did the join, and only `VsText`
     * ever did: `GraphCompileException` and the client's script panel both reported the node id alone. A
     * migration produces validation errors in bulk — twenty-six copies of one sentence, none of them
     * naming a line, is not a diagnostic (GAPS 21) — so the join happens once, here, and everything that
     * reads an [Issue] gets it.
     */
    val span: Span = Span.NONE,
    /**
     * The DECLARATION this is about — `var:Total`, `fn:double`, `type:Point`, `import:banking`.
     *
     * For everything an issue can be about that is not a node. A graph variable typed as a record that
     * does not exist, an import nothing answers to: both are real errors with nothing to point at, so
     * they arrived with `Span.NONE` and a text editor put them on the first character of the file. That
     * reads exactly like no diagnostic at all — the file has a red mark in a place with nothing wrong
     * with it, and the actual mistake is unmarked.
     *
     * A handle rather than a span because the validator works on a graph and has never seen a line;
     * `VsText` resolves it through the table `Lower` keeps.
     */
    val declaration: String? = null,
) {
    /**
     * This issue with [span] filled in from what lowering recorded.
     *
     * A node if it has one, else the declaration it names. Without the second, a mistyped variable and an
     * unresolved import both landed on offset 0 — a red mark on the first character of the file, nowhere
     * near the mistake, which reads as the checker being broken rather than the code.
     */
    fun located(spans: Map<Int, Span>, declSpans: Map<String, Span>): Issue {
        if (span !== Span.NONE) return this
        val found = nodeId?.let { spans[it] } ?: declaration?.let { declSpans[it] } ?: return this
        return Issue(severity, message, nodeId, linkId, pin, found, declaration)
    }

    override fun toString(): String = buildString {
        append(severity).append(": ").append(message)
        // The line FIRST, because that is what a text author reads and the node id means nothing to them.
        // A canvas graph has no line and prints exactly as it always did.
        if (span !== Span.NONE) append(" (").append(span.line).append(':').append(span.col).append(')')
        nodeId?.let { append(" (node ").append(it).append(pin?.let { p -> ".$p" } ?: "").append(')') }
        linkId?.let { append(" (link ").append(it).append(')') }
    }
}

/**
 * Static checks over a graph, run before compiling and continuously in the editor.
 *
 * The goal is that *every* failure a graph can have is caught here with a node to point at, rather than
 * surfacing as a VM error at 3am — the compiler below assumes a validated graph and does not re-check.
 */
class Validator(
    private val catalog: NodeCatalog,
    /** Where `import` declarations are looked up. [GraphSource.NONE] means "this graph imports nothing". */
    private val source: GraphSource = GraphSource.NONE,
    /**
     * node id → where in the source it came from, when the graph was lowered from text.
     *
     * Empty for a canvas graph, which has no source and never had one. Given here rather than joined by
     * each caller because "somebody remembers to do the lookup" is what produced unlocated errors
     * everywhere except `VsText` — see [Issue.span].
     */
    private val spans: Map<Int, Span> = emptyMap(),
    /** `var:Total` → where it was written, for the issues that name a declaration rather than a node. */
    private val declSpans: Map<String, Span> = emptyMap(),
    /**
     * `on tick` runs as a LOOP fiber on this host, so a pass may wait — see `GraphCompiler.tickLoop`.
     *
     * Lifts the cannot-wait rule from tick entries, and the "nothing polls Sleep Requested" warning with
     * it: the runtime ends a loop at its pass boundary when asked to sleep, so the document has no loop
     * of its own to leave. Render entries keep the rule; a frame is a frame on every host.
     */
    private val tickMayWait: Boolean = false,
) {

    /**
     * What names mean in the document being validated.
     *
     * A field rather than a parameter threaded through fifteen private methods, and safe because a
     * `Validator` is constructed fresh at every call site in this codebase — it is a function wearing a
     * class. Set at the top of [validate] before anything reads it.
     */
    private var scope: ImportScope = ImportScope.single(Graph(id = "", name = ""))

    /**
     * "It IS there, and it is not offered" — the message a missing `export` deserves.
     *
     * **The commonest mistake in the language now, by a distance**, because the surface is opt-in: every
     * declaration a library means to share has to say so, and the one that forgot looks from the outside
     * exactly like a typo. `no type named 'Point' in this graph` sends somebody hunting through a name
     * that is spelled correctly, in a file that is not the one to fix.
     *
     * One function so the four kinds cannot drift into four wordings. It used to be one message on
     * FUNCTIONS only, and the other three collapsed into "does not exist".
     */
    private fun notExported(q: dev.ziggle.vscript.model.QualName, kind: String): String =
        "'${q.name}' is declared in '${q.module}' but not exported — add 'export' to that $kind"

    /** Either "there is no such enum" or "it is there and unexported" — see [notExported]. */
    private fun missingEnum(named: String?): String {
        val q = dev.ziggle.vscript.model.QualName.parse(named.orEmpty())
        return if (scope.enumIgnoringVisibility(named) != null) notExported(q, "enum")
        else "no choice named '${named.orEmpty()}' in this graph"
    }

    /** Either "there is no such record" or "it is there and unexported" — see [notExported]. */
    private fun missingType(named: String?): String {
        val q = dev.ziggle.vscript.model.QualName.parse(named.orEmpty())
        return if (scope.structIgnoringVisibility(named) != null) notExported(q, "type")
        else "no type named '${named.orEmpty()}' in this graph"
    }

    /** Types nameable here — this document's, plus each imported one's under its alias. */
    private fun visibleTypes() = scope.visibleTypes()

    private fun visibleEnums() = scope.visibleEnums()

    /** "Is this node an expression?", answered against [graph] — see [expressionCalls]. */
    private fun pureIn(graph: Graph) =
        expressionCalls(catalog, graph.nodes, graph.links, scope::function) { scope.pureAcrossImport(catalog, it) }


    fun validate(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()

        // Imports first, and the rest is validated against whatever resolved. A document whose library is
        // missing still gets its own wiring checked — reporting only "cannot find banking" and nothing
        // else would hide every other mistake behind one unrelated fix.
        val closure = if (graph.imports.isEmpty()) ImportClosure.single(graph)
        else ImportClosure.resolve(graph, source)
        scope = ImportScope(closure, graph)
        closure.errors.forEach { issues += Issue(Severity.ERROR, it.message, declaration = it.declaration) }

        // Pins resolved against the document, so a Call node is checked against the signature it actually
        // has rather than the bare exec pins the catalog declares.
        fun descOf(node: Node): NodeDescriptor? = descOfFor(graph, node)

        // Two `export *` lines offering one name — see [starConflicts]. Reported against the document
        // doing the forwarding, which is the only file that can fix it.
        for (name in dev.ziggle.vscript.model.starConflicts(graph) { d, imp -> closure.resolve(d, imp.alias) }) {
            issues += Issue(
                Severity.ERROR,
                "'$name' is offered by two of the documents this one re-exports — say which with " +
                    "'export { $name } from \"…\"', or stop forwarding one of them",
                declaration = graph.imports.firstOrNull { it.reExportAll }?.let { "import:${it.alias}" },
            )
        }

        issues += checkFunctions(graph)
        issues += checkTypes(graph)
        issues += checkNullability(graph)

        val seen = HashSet<Int>()
        for (n in graph.nodes) {
            if (!seen.add(n.id)) issues += Issue(Severity.ERROR, "duplicate node id ${n.id}", n.id)
            val d = descOf(n)
            if (d == null) {
                issues += Issue(Severity.ERROR, "unknown node type '${n.type}'", n.id)
                continue
            }
            if (d.type == BuiltinNodes.VAR_GET || d.type == BuiltinNodes.VAR_SET) {
                val name = n.variable
                if (name == null) {
                    issues += Issue(Severity.ERROR, "${d.title} node has no variable selected", n.id)
                } else if (scope.variable(name) == null) {
                    val q = dev.ziggle.vscript.model.QualName.parse(name)
                    issues += Issue(
                        Severity.ERROR,
                        if (scope.variableIgnoringVisibility(name) != null) notExported(q, "var")
                        else "no graph variable named '$name'",
                        n.id,
                    )
                }
            }
        }

        for (l in graph.links) {
            val from = graph.node(l.fromNode)
            val to = graph.node(l.toNode)
            if (from == null || to == null) {
                issues += Issue(Severity.ERROR, "link references a node that does not exist", linkId = l.id)
                continue
            }
            val fd = descOf(from) ?: continue
            val td = descOf(to) ?: continue
            val fp = fd.output(l.fromPin)
            val tp = td.input(l.toPin)
            if (fp == null) {
                issues += Issue(Severity.ERROR, "'${fd.title}' has no output pin '${l.fromPin}'", from.id, l.id, l.fromPin)
                continue
            }
            if (tp == null) {
                issues += Issue(Severity.ERROR, "'${td.title}' has no input pin '${l.toPin}'", to.id, l.id, l.toPin)
                continue
            }
            // The EFFECTIVE types, so a variable's declared type is enforced rather than hidden behind the
            // wildcard that lets one Get node serve every variable — and so a Hold is typed like whatever
            // it holds rather than like anything at all.
            val varType = { name: String -> scope.variable(name)?.type }
            // [feedingIn] rather than a copy of it here: it is guarded against a ring of Holds, which a
            // hand-edited file can contain even though the editor cannot draw one, and it is the same walk
            // a generic Call's own pins are resolved through — one description of "what feeds this".
            val fromType = effectivePinType(from, fp, varType) { n, p -> feedingIn(graph, n, p, 0) }
            val toType = effectivePinType(to, tp, varType) { n, p -> feedingIn(graph, n, p, 0) }
            if (!canConnect(fromType, toType)) {
                // The numeric pair is worth naming the cure for: it is the one mismatch that is not a
                // mistake about what the value IS, only about how it should be converted.
                val fix = conversionFor(fromType, toType)?.let { " — put $it in between" }.orEmpty()
                issues += Issue(
                    Severity.ERROR,
                    "cannot wire $fromType into $toType ('${fd.title}.${fp.name}' → " +
                        "'${td.title}.${tp.name}')$fix",
                    to.id, l.id, tp.name,
                )
            }
            // A pin that decides the node's SHAPE is read at edit time to work out what the other pins are,
            // so a wire into it is never consulted. Left unreported that is the worst kind of bug: the wire
            // draws, the graph compiles, and the node silently keeps whatever was typed there instead.
            if (l.toPin in BuiltinNodes.SHAPE_PINS[to.type].orEmpty()) {
                issues += Issue(
                    Severity.ERROR,
                    "'${td.title}.${l.toPin}' decides this node's pins, so it has to be typed in rather " +
                        "than wired — the value on this wire would never be read",
                    to.id, l.id, l.toPin,
                )
            }
            // The same rule for a pin that configures the node without deciding its pins — see
            // [BuiltinNodes.CONFIG_PINS]. Reported separately because what is true of it is different:
            // nothing reads it at run time either, but saying it "decides this node's pins" would be a
            // lie, and a wrong explanation is worse than a vague one.
            if (l.toPin in BuiltinNodes.CONFIG_PINS[to.type].orEmpty()) {
                issues += Issue(
                    Severity.ERROR,
                    "'${td.title}.${l.toPin}' is a label, set while editing, so it has to be typed in " +
                        "rather than wired — the value on this wire would never be read",
                    to.id, l.id, l.toPin,
                )
            }
        }

        // An exec output driving two nodes has no defined order, and a data input fed twice has no defined
        // value. Both are silent-wrong-behaviour bugs if allowed through, so they are errors, not warnings.
        graph.links.groupBy { it.fromNode to it.fromPin }.forEach { (key, ls) ->
            val d = graph.node(key.first)?.let { descOf(it) } ?: return@forEach
            if (d.output(key.second)?.type?.isExec == true && ls.size > 1) {
                issues += Issue(
                    Severity.ERROR,
                    "exec output '${key.second}' drives ${ls.size} nodes — use a Sequence node to order them",
                    key.first, pin = key.second,
                )
            }
        }
        graph.links.groupBy { it.toNode to it.toPin }.forEach { (key, ls) ->
            val d = graph.node(key.first)?.let { descOf(it) } ?: return@forEach
            val pin = d.input(key.second) ?: return@forEach
            if (!pin.type.isExec && ls.size > 1) {
                issues += Issue(
                    Severity.ERROR,
                    "input '${key.second}' is fed by ${ls.size} wires",
                    key.first, pin = key.second,
                )
            }
        }

        issues += pureCycles(graph)
        issues += strayLoopJumps(graph)

        // Every KIND of entry, not just the fiber ones: a document whose only entry is `on tick` or
        // `on render` runs perfectly well, and telling it nothing will run is a warning that is wrong.
        if (graph.allEntries(catalog).isEmpty()) {
            issues += Issue(Severity.WARNING, "graph has no entry node, so nothing will run")
        }

        // The one place the join happens — see [Issue.span]. A no-op when the graph came from a canvas.
        if (spans.isEmpty() && declSpans.isEmpty()) return issues
        return issues.map { it.located(spans, declSpans) }
    }

    /**
     * Detect cycles among PURE nodes.
     *
     * A pure node is re-evaluated at each use, so a cycle is not a loop — it is unbounded recursion at
     * *compile* time. Catching it here turns a stack overflow inside the compiler into a message naming
     * the nodes involved.
     */
    private fun strayLoopJumps(graph: Graph): List<Issue> {
        val jumps = graph.nodes.filter { it.type == BuiltinNodes.BREAK || it.type == BuiltinNodes.CONTINUE }
        if (jumps.isEmpty()) return emptyList()

        val inALoop = HashSet<Int>()
        val seen = HashSet<Int>()
        fun walk(from: Int?) {
            val id = from ?: return
            if (!seen.add(id)) return
            val node = graph.node(id) ?: return
            inALoop += id
            val d = descOfFor(graph, node) ?: return
            for (pin in d.outputs.filter { it.type.isExec }) walk(graph.execTarget(id, pin.name))
        }
        for (loop in graph.nodes.filter { it.type in BuiltinNodes.LOOPS }) {
            seen.clear()
            walk(graph.execTarget(loop.id, "Body"))
        }

        return jumps.filter { it.id !in inALoop }.map {
            val word = if (it.type == BuiltinNodes.BREAK) "Break" else "Continue"
            Issue(Severity.ERROR, "$word is not inside a loop, so there is nothing for it to leave", it.id)
        }
    }

    private fun pureCycles(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        val state = HashMap<Int, Int>() // 0 = visiting, 1 = done

        fun visit(nodeId: Int) {
            when (state[nodeId]) {
                1 -> return
                0 -> {
                    issues += Issue(Severity.ERROR, "pure nodes form a cycle — a value depends on itself", nodeId)
                    return
                }
            }
            val node = graph.node(nodeId) ?: return
            val d = catalog[node.type] ?: return
            if (d.kind != NodeKind.PURE) return
            state[nodeId] = 0
            for (pin in d.dataInputs) {
                val link = graph.linkInto(nodeId, pin.name) ?: continue
                visit(link.fromNode)
            }
            state[nodeId] = 1
        }

        graph.nodes.forEach { n ->
            if (catalog[n.type]?.kind == NodeKind.PURE && state[n.id] == null) visit(n.id)
        }
        return issues
    }

    /**
     * Everything that can be wrong about a user function, reported here so the compiler can assume it isn't.
     *
     * Each of these corresponds to an `error(...)` in [GraphCompiler]: a Call with nowhere to go, a body with
     * no beginning, a cycle that would compile forever.
     */
    private fun checkFunctions(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()

        val names = HashSet<String>()
        for (f in graph.functions) {
            if (!names.add(f.name)) {
                // **The limit on Level 0 overloading, and it is a property of the MODEL rather than of the
                // resolution rule.** A Call node names its callee by name (`Node.callee`), and a function's
                // body is the nodes naming it (`Node.function`) — so two functions called `plus` in one
                // document are two things one name has to refer to, whatever their receivers say. Across
                // documents there is no such problem: the callee is `alias::plus` and each document has one,
                // which is exactly the case `core/list.vs` and `core/objects.vs` are.
                issues += Issue(
                    Severity.ERROR,
                    if (f.isExtension) {
                        "two functions here are both called '${f.name}' — a different receiver is not " +
                            "enough, because a call names its function by name. Two documents may each " +
                            "extend '${f.name}'; one document may not do it twice"
                    } else {
                        "two functions are both called '${f.name}'"
                    },
                )
            }
            val pins = HashSet<String>()
            for (p in f.params + f.results) {
                if (!pins.add(p.name)) {
                    issues += Issue(Severity.ERROR, "function '${f.name}' has two pins called '${p.name}'")
                }
            }
            if (graph.entryOf(f.name) == null) {
                issues += Issue(Severity.ERROR, "function '${f.name}' has no box — nothing to run")
            }
            // A receiver's argument list is a BINDING SITE, so it cannot be checked against what is
            // declared the way every other position can — `fn LIST<Etity>.f(self)` is a perfectly good
            // generic function over `Etity`, for the same reason `fn f(etity: INT)` is a parameter called
            // `etity`. What CAN be checked is that the name introduced is then used, which a typo never
            // is. See `Generics.kt`.
            for (v in dev.ziggle.vscript.model.unusedTypeParameters(f)) {
                issues += Issue(
                    Severity.WARNING,
                    "'${f.name}' is written on '${f.receiver}', and nothing else in it mentions '$v' — " +
                        "so '$v' stands for any type at all. Did you mean a type that exists?",
                    declaration = "fn:${f.name}",
                )
            }
        }

        for (n in graph.nodes) {
            when (n.type) {
                BuiltinNodes.CALL -> {
                    val callee = n.callee
                    when {
                        callee == null -> issues += Issue(Severity.ERROR, "Call node names no function", n.id)
                        scope.function(callee) == null -> {
                            // Two different mistakes, and telling them apart is the difference between
                            // "fix the import line" and "fix the call". An unknown ALIAS is reported as
                            // such rather than as a missing function, which is what it looks like from
                            // here if you only ask whether the whole name resolved.
                            val q = dev.ziggle.vscript.model.QualName.parse(callee)
                            issues += when {
                                q.isQualified && scope.documentOf(q) == null ->
                                    Issue(Severity.ERROR, "nothing is imported as '${q.module}'", n.id)
                                // It IS there and merely not offered. "No function named x" would be
                                // true and send someone looking for a typo in a name that is spelled
                                // correctly.
                                scope.functionIgnoringVisibility(callee) != null ->
                                    Issue(Severity.ERROR, notExported(q, "function"), n.id)
                                else -> Issue(Severity.ERROR, "no function named '$callee'", n.id)
                            }
                        }
                    }
                }

                // A function REFERENCE, which the canvas can build directly and a `.vs` file can carry
                // through a round trip — so the three things `resolveNode` quietly copes with are reported
                // here rather than silently producing an unconstrained `fn` that connects to anything.
                BuiltinNodes.FUNCTION_REF -> {
                    val named = n.literals[BuiltinNodes.FUNCTION_REF_NAME]?.toString()
                    val fn = named?.let(scope::function)
                    when {
                        named.isNullOrBlank() ->
                            issues += Issue(Severity.ERROR, "this Function value names no function", n.id)
                        fn == null ->
                            issues += Issue(Severity.ERROR, "no function named '$named'", n.id)
                        // **No purity check here.** A body of steps used to be refused outright, on the
                        // grounds that a function value is called from inside an expression. That was
                        // never a fact about the VALUE — it was a fact about the three PURE nodes that
                        // were the only things able to call one. It briefly rode in the type as `act`;
                        // now nothing checks it, and a step-bodied function is an ordinary value.
                        //
                        // A wire carries ONE thing. This is the claim `resolveNode` makes when it takes
                        // the first result, and it has to be checked somewhere or the value would quietly
                        // be typed as though the others did not exist. Zero results is fine now: that is
                        // a `fn(T)` with no result, and Invoke gives it no Result pin to read.
                        fn.results.size > 1 ->
                            issues += Issue(
                                Severity.ERROR,
                                "'$named' hands back ${fn.results.size} values, so it cannot be passed as " +
                                    "one — a wire carries a single thing",
                                n.id,
                            )
                    }
                }

                BuiltinNodes.FUNCTION -> {
                    val owner = n.function
                    when {
                        owner == null ->
                            issues += Issue(Severity.ERROR, "this Function box names no function", n.id)
                        graph.function(owner) == null ->
                            issues += Issue(Severity.ERROR, "no function named '$owner'", n.id)
                    }
                }

            }
        }

        issues += checkTypeTests(graph)
        issues += checkCasts(graph)
        issues += checkEnums(graph)
        issues += checkWhen(graph)
        issues += checkImpureReached(graph)
        issues += checkSignaturePinsFed(graph)
        return issues
    }

    /**
     * A signature pin with nothing feeding it.
     *
     * Ordinary nodes declare a default for an input you may leave alone, so an unwired pin is usually fine.
     * A function's pins cannot: the signature says a parameter's TYPE, never a value to stand in for it. So
     * an unwired, un-typed-into parameter arrives as null, and the first thing that does arithmetic on it
     * fails somewhere inside the body — a long way from the call site that forgot to supply it.
     *
     * A warning rather than an error, because null is occasionally what you mean, and because refusing to
     * run a graph you are still wiring up is its own kind of unhelpful.
     *
     * **[BuiltinNodes.FORMAT] belongs here for exactly the same reason**, which is easy to miss because it
     * is not a function: a `text("…{a}…")` node's pins come from its own template, so like a signature they
     * say a name and never a value to stand in for it. An argument with no hole was already caught (`'text'
     * has no input called 'z'`); a hole with no argument was not, and it reaches the log as the word `null`
     * in the middle of a sentence. Its `Template` pin defaults to `""`, so it skips itself below.
     */
    private fun checkSignaturePinsFed(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        for (n in graph.nodes) {
            if (n.type != BuiltinNodes.CALL && n.type != BuiltinNodes.FUNCTION &&
                n.type != BuiltinNodes.FORMAT
            ) {
                continue
            }
            val d = descOfFor(graph, n) ?: continue
            val what = when (n.type) {
                BuiltinNodes.CALL -> "Call '${n.callee}'"
                BuiltinNodes.FORMAT -> "text"
                else -> "'${n.function}' returns"
            }
            // A function whose body says `return` hands its values back through a RETURN node, not
            // through the box — deliberately, so each return can hand back its own. The box is then
            // legitimately unfed, and warning about it flagged every function written with an explicit
            // return, which is most of them. Only the implicit-return case is worth a warning.
            val returns = if (n.type == BuiltinNodes.FUNCTION) {
                graph.nodes.filter { it.type == BuiltinNodes.RETURN && it.function == n.function }
            } else {
                emptyList()
            }

            for (pin in d.dataInputs) {
                if (graph.linkInto(n.id, pin.name) != null) continue
                if (n.literals.containsKey(pin.name)) continue
                // A parameter with a DEFAULT is answered by its signature — that is what a default is.
                if (pin.default != null) continue
                if (returns.any {
                        graph.linkInto(it.id, pin.name) != null || it.literals.containsKey(pin.name)
                    }
                ) {
                    continue
                }
                issues += if (n.type == BuiltinNodes.FORMAT) {
                    // Named as the hole, in the braces the author wrote, because that is what they will
                    // search the line for.
                    Issue(
                        Severity.WARNING,
                        "this message has a hole '{${pin.name}}' and nothing fills it",
                        n.id, pin = pin.name,
                    )
                } else {
                    Issue(
                        Severity.WARNING,
                        "$what: '${pin.name}' has nothing feeding it — it will arrive as null",
                        n.id, pin = pin.name,
                    )
                }
            }
        }
        return issues
    }

    /**
     * Strict non-null: a plain `T` never holds nothing, and the two ways it used to are reported here.
     *
     * **This is the crash `salamander` documents, turned into a compile error.** Its own comment reads:
     *
     * > A bare `var MissTile: Tile` is NULL until something assigns it, and `sameTile` reads `.x` off both
     * > arguments — so the first Check that came up empty, an ordinary missed catch, crashed the graph with
     * > `GETFIELD on null, expected a record`.
     *
     * Two rules, and the wire rule is not here at all: `canConnect` refuses `T?` into `T`, so every wire
     * inherits it for free and this only has to cover what is not a wire.
     *
     * 1. **A non-optional variable must start with a value.** A written default, a computed one (which
     *    becomes an `@init` at the head of `on start`), or a record's field-by-field zero.
     * 2. **`null` is only writable into a slot that admits it.**
     */
    private fun checkNullability(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()

        // Variables assigned before anything runs — `var seed: FLOAT = now()` has nowhere to store its
        // default, so `Lower.emitInits` turns it into a marked Set at the head of the start chain.
        val initialised = graph.nodes
            .filter { it.type == BuiltinNodes.VAR_SET && it.literals.containsKey(BuiltinNodes.INIT_MARK) }
            .mapNotNullTo(HashSet()) { it.variable?.lowercase() }

        for (v in graph.variables) {
            if (v.type.optional || v.type.isWildcard || v.default != null) continue
            if (v.name.lowercase() in initialised) continue
            // A record starts as its own zero — every field at its type's zero — so it is never null and
            // needs no default. `startingGlobals` is where that happens; this must agree with it.
            if (graph.struct(v.type.name) != null) continue
            issues += Issue(
                Severity.ERROR,
                "'${v.name}' is a ${v.type} with no value, so it starts as nothing — give it one, " +
                    "or declare it '${v.type}?' if it is genuinely sometimes absent",
                declaration = "var:${v.name}",
            )
        }

        // A `null` typed into a pin. A WIRE carrying one is `canConnect`'s business and is already refused;
        // this is the literal, which has no wire to check.
        for (n in graph.nodes) {
            val resolved = runCatching { descOfFor(graph, n) }.getOrNull() ?: continue
            for (pin in resolved.dataInputs) {
                if (!n.literals.containsKey(pin.name) || n.literals[pin.name] != null) continue
                if (graph.linkInto(n.id, pin.name) != null) continue
                val t = effectivePinType(n, pin) { name -> scope.variable(name)?.type }
                if (t.optional || t.isWildcard) continue
                issues += Issue(
                    Severity.ERROR,
                    "'${pin.name}' is a $t, which is never nothing — declare it '$t?' to write null there",
                    n.id,
                    pin = pin.name,
                )
            }
        }
        return issues
    }

    /**
     * Every type NAME a document uses is one it can resolve.
     *
     * This is the check that pays for keeping an unknown name instead of widening it. Reading a type
     * through the enum defaulted anything unrecognised to a wildcard, which connects to anything — so a
     * variable typed as a struct that had since been deleted kept compiling, kept wiring to everything, and
     * quietly stopped meaning what it said. There was nothing left to report it WITH. Now the name survives
     * as far as here, and here is where it gets named.
     */
    private fun checkTypes(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        val known = HashSet<String>()
        // Every type nameable here, not just the ones declared here — an imported record is spelled
        // `banking::Account` and is every bit as resolvable as a local one.
        visibleTypes().forEach { known += it.name.lowercase() }
        // Enums too. A declared name is a declared name here: this check exists to catch a type nothing
        // declares, and it cannot tell a record from a choice — nor should it, since `var State: Phase` is
        // as legitimate as `var Where: Point`.
        visibleEnums().forEach { known += it.name.lowercase() }
        // And the types the HOST contributes, which no document declares and every document may name.
        //
        // **This is what lets a node library add a type without touching the language.** A host type that
        // needs no inline editor, no picker and no opcode — a panel Row, say — has no business being a
        // [PinType] constant: the enum is the set of KINDS the engine has machinery for, and a wire-only
        // type is exactly the case `TypeRef` exists to carry by name instead. The only thing that stopped
        // one working was this set: `var rows: LIST<Row>` reported a type the graph does not declare,
        // because "declared" was read as "declared HERE" rather than "declared anywhere it could be".
        //
        // This set answers "may this name be written", not "what does it mean" — so adding the host's
        // types here cannot shadow a document's. Which declaration a name resolves TO is decided by the
        // lookups above, where a document already wins.
        Types.all.forEach { known += it.name.lowercase() }

        fun check(t: TypeRef, where: String, nodeId: Int? = null, declaration: String? = null) {
            val target = t.of ?: t
            if (!target.declared) return
            // **A type VARIABLE is not a type nobody declared** — it is a name the receiver introduced, and
            // this check is the exact reason it has to be marked rather than recognised later. `TypeRef.named`
            // makes any unknown name a declared type on purpose, so a bare `T` arriving here unmarked reads
            // as a phantom record and `fn LIST<T>.first(self) -> T?` reports two errors on a correct
            // signature. See `Generics.kt`; the mistyped-receiver case this seems to give up is caught by
            // `unusedTypeParameters` instead.
            if (target.variable) return
            if (target.name.lowercase() in known) return
            issues += Issue(
                Severity.ERROR,
                "$where is typed '${target.name}', which this graph does not declare",
                nodeId,
                declaration = declaration,
            )
        }

        val names = HashSet<String>()
        for (t in graph.types) {
            if (!names.add(t.name.lowercase())) {
                issues += Issue(Severity.ERROR, "two types are both called '${t.name}'")
            }
            val fields = HashSet<String>()
            for (f in t.fields) {
                if (!fields.add(f.name.lowercase())) {
                    issues += Issue(Severity.ERROR, "'${t.name}' has two fields called '${f.name}'")
                }
                check(f.type, "'${t.name}.${f.name}'", declaration = "type:${t.name}")
            }
        }
        // An enum's table. Each of these is a mistake that would otherwise be silent: a short row leaves a
        // column reading null for one member only, and a name typed twice makes one of the two unreachable.
        for (e in graph.enums) {
            val fields = HashSet<String>()
            for (f in e.fields) {
                if (!fields.add(f.name.lowercase())) {
                    issues += Issue(Severity.ERROR, "'${e.name}' has two fields called '${f.name}'")
                }
                check(f.type, "'${e.name}.${f.name}'", declaration = "enum:${e.name}")
            }
            if (e.fields.isEmpty()) continue
            for (m in e.members) {
                val row = e.values[m].orEmpty()
                if (row.size > e.fields.size) {
                    issues += Issue(
                        Severity.ERROR,
                        "'${e.name}.$m' gives ${row.size} value(s) but '${e.name}' declares only " +
                            "${e.fields.size}: ${e.fields.joinToString(", ") { it.name }}",
                        declaration = "enum:${e.name}",
                    )
                    continue
                }
                // A row may stop early where every column it left off has a default — the same bargain a
                // call site makes with a parameter default. Reported per FIELD rather than as a count, so
                // the message names the one that has no answer instead of leaving it to be worked out.
                val missing = e.fields.drop(row.size).filter { it.default == null }
                if (missing.isNotEmpty()) {
                    issues += Issue(
                        Severity.ERROR,
                        "'${e.name}.$m' gives no value for " + missing.joinToString(", ") { "'${it.name}'" } +
                            ", which ${if (missing.size == 1) "has" else "have"} no default",
                        declaration = "enum:${e.name}",
                    )
                    continue
                }
                // Each cell against its column's type. `literalTypeOf` is what decides the kind of a value
                // typed into a pin, so a row is held to the same rule a literal on a wire is.
                e.fields.take(row.size).forEachIndexed { i, f ->
                    val got = dev.ziggle.vscript.model.literalTypeOf(row[i]) ?: return@forEachIndexed
                    if (!canConnect(got, f.type)) {
                        issues += Issue(
                            Severity.ERROR,
                            "'${e.name}.$m' gives a ${got} for '${f.name}', which is ${f.type}",
                            declaration = "enum:${e.name}",
                        )
                    }
                }
            }
        }
        // The node that reads a column, checked the way the struct nodes are: an enum that does not exist,
        // or a field name left over from one since renamed, would otherwise shape the node with no output
        // and read nothing at all.
        for (n in graph.nodes) {
            if (n.type != BuiltinNodes.ENUM_FIELD) continue
            val named = n.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim()
            val t = visibleEnums().firstOrNull { it.name.equals(named, true) }
            if (t == null) {
                issues += Issue(
                    Severity.ERROR, missingEnum(named),
                    n.id, pin = BuiltinNodes.ENUM_TYPE,
                )
                continue
            }
            if (t.fields.isEmpty()) {
                issues += Issue(
                    Severity.ERROR, "'${t.name}' carries no fields, so there is nothing to read off it",
                    n.id, pin = BuiltinNodes.STRUCT_FIELD,
                )
                continue
            }
            val field = n.literals[BuiltinNodes.STRUCT_FIELD]?.toString()?.trim()
            if (t.field(field) == null) {
                issues += Issue(
                    Severity.ERROR,
                    "'${t.name}' has no field '${field.orEmpty()}' — it has " +
                        t.fields.joinToString(", ") { it.name },
                    n.id, pin = BuiltinNodes.STRUCT_FIELD,
                )
            }
        }
        // And the node that reads them ALL, which needs only the enum to exist.
        for (n in graph.nodes) {
            if (n.type != BuiltinNodes.ENUM_VALUES) continue
            val named = n.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim()
            if (visibleEnums().none { it.name.equals(named, true) }) {
                issues += Issue(
                    Severity.ERROR, missingEnum(named),
                    n.id, pin = BuiltinNodes.ENUM_TYPE,
                )
            }
        }
        // A type holding itself, directly or round a ring. Refused rather than allowed, because every
        // record here is a VALUE: one containing itself has no finite size and no default anybody could
        // build, and the zero-value walk below would recurse until the stack ran out.
        for (t in graph.types) {
            if (cycles(graph, t.name, HashSet())) {
                issues += Issue(Severity.ERROR, "'${t.name}' contains itself — a record cannot hold one of its own kind")
            }
        }
        graph.variables.forEach { check(it.type, "variable '${it.name}'", declaration = "var:${it.name}") }
        graph.functions.forEach { f ->
            f.params.forEach { check(it.type, "'${f.name}.${it.name}'", declaration = "fn:${f.name}") }
            f.results.forEach { check(it.type, "'${f.name}.${it.name}'", declaration = "fn:${f.name}") }
        }
        for (n in graph.nodes) {
            if (n.type !in BuiltinNodes.STRUCT_SHAPED) continue
            val named = n.literals[BuiltinNodes.STRUCT_OF]?.toString()?.trim()
            if (named.isNullOrEmpty()) {
                issues += Issue(Severity.ERROR, "this node names no type", n.id, pin = BuiltinNodes.STRUCT_OF)
                continue
            }
            if (named.lowercase() !in known) {
                issues += Issue(Severity.ERROR, missingType(named), n.id, pin = BuiltinNodes.STRUCT_OF)
                continue
            }
            // And for the two that work on ONE field, that the field is one this record has. A name left
            // over from a field since renamed would otherwise silently read or write the first one.
            if (n.type != BuiltinNodes.STRUCT_GET && n.type != BuiltinNodes.STRUCT_SET) continue
            val t = graph.struct(named) ?: continue
            val field = n.literals[BuiltinNodes.STRUCT_FIELD]?.toString()?.trim()
            if (field.isNullOrEmpty()) {
                issues += Issue(Severity.ERROR, "this node names no field", n.id, pin = BuiltinNodes.STRUCT_FIELD)
            } else if (t.fields.none { it.name.equals(field, true) }) {
                issues += Issue(
                    Severity.ERROR, "'${t.name}' has no field '$field'", n.id, pin = BuiltinNodes.STRUCT_FIELD,
                )
            }
        }
        return issues
    }

    /** Does [name] reach itself through its fields? Depth-first, and the visited set is the guard. */
    private fun cycles(graph: Graph, name: String, seen: MutableSet<String>): Boolean {
        if (!seen.add(name.lowercase())) return true
        val t = graph.struct(name)
        if (t == null) {
            // Not declared HERE, so it is an imported record — its own fields are the declaring
            // document's business and were walked when that document was validated. Removed from `seen`
            // on the way out like every other exit: leaving it behind made a SECOND field of the same
            // imported type look like a ring, so `type Leg { from: geo::Point, to: geo::Point }` was
            // rejected as containing itself.
            seen -= name.lowercase()
            return false
        }
        for (f in t.fields) {
            // **A function NAMES its parameters, it does not hold them.** The value is a closure pointer,
            // one word wide whatever the signature says, so a record with a field of type `act(Self)` — a
            // handler that takes the very thing it hangs off, which is how a dispatch column is written —
            // is perfectly finite. Walking into the signature made it look like a ring, because
            // [TypeRef.of] is `args.firstOrNull()` and a function type's first argument is its first
            // PARAMETER. That is the right reading for a container and the wrong one here.
            if (f.type.isFunction) continue
            val target = f.type.of ?: f.type
            if (target.declared && cycles(graph, target.name, seen)) return true
        }
        seen -= name.lowercase()
        return false
    }

    /**
     * An impure node whose output is read, but which nothing ever runs.
     *
     * A PURE node is an expression: it is re-evaluated wherever it is read, so a data wire out of one needs
     * no exec wire in. An IMPURE node is a step — it runs in exec order and its outputs are cached in
     * registers — and the compiler allocates those registers for every impure node whether or not it is
     * reachable. So a Call left off the exec chain, with its result wired somewhere, compiles perfectly and
     * hands out whatever its register happened to hold: null.
     *
     * That failure surfaces as "expected number, got null" at a node a long way from the one at fault, and
     * it is precisely the shape this project keeps paying for — the graph looks right and quietly does
     * less. The question "will this ever run" is answerable here, so it is answered here.
     */
    /**
     * `x is Item` cannot mean anything, so it is refused rather than answered.
     *
     * An ITEM, an NPC and an OBJECT are all `Int` at run time, and a SKILL and an ENUM are both `String`
     * — a test against one of those would return whatever the underlying kind happened to be, and a
     * script branching on it would be branching on a coin toss dressed as a type. TILE and COLOR are fine
     * because they became records and carry their own name.
     */
    /**
     * A Choice node naming an enum that is not here, or a member that enum does not have.
     *
     * Checked rather than left to the compiler because of what the compiler would otherwise do: an unknown
     * member has to become SOME constant, and the natural fallback — the first member, the way the struct
     * nodes fall back to the first field — is a script that runs and silently takes the wrong branch. That
     * is the failure this whole design keeps refusing, so the compiler is allowed to assume a validated
     * graph only because this says so first.
     *
     * Both messages list what IS available. A closed set of names is the one case where showing the whole
     * set costs nothing and answers the question outright.
     */
    private fun checkEnums(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        val declared = visibleEnums()
        for (n in graph.nodes) {
            if (n.type != BuiltinNodes.ENUM_OF) continue
            val named = n.literals[BuiltinNodes.ENUM_TYPE]?.toString()?.trim().orEmpty()
            val t = declared.firstOrNull { it.name.equals(named, true) }
            if (t == null) {
                issues += Issue(
                    Severity.ERROR,
                    if (named.isEmpty()) "this needs a choice to take a member from"
                    else "nothing here is a choice called '$named'" +
                        if (declared.isEmpty()) "" else " — this graph has ${declared.joinToString(", ") { it.name }}",
                    n.id, pin = BuiltinNodes.ENUM_TYPE,
                )
                continue
            }
            val member = n.literals[BuiltinNodes.ENUM_MEMBER]?.toString()?.trim().orEmpty()
            if (t.member(member) == null) {
                issues += Issue(
                    Severity.ERROR,
                    if (member.isEmpty()) "'${t.name}' needs one of its members chosen"
                    else "'${t.name}' has no member '$member' — it has ${t.members.joinToString(", ")}",
                    n.id, pin = BuiltinNodes.ENUM_MEMBER,
                )
            }
        }
        return issues
    }

    /**
     * A `when` that cannot do what it looks like it does.
     *
     * **Exhaustiveness is the point of `when` over an enum**, and it is asked only where it can be answered:
     * a subject whose type is a declared enum has a KNOWN set of values, so "did you cover them all" has a
     * true answer. Against an Int or a String it does not — there is no finite set — so no exhaustiveness
     * question is asked there at all, rather than one that can only ever be answered "add an else".
     *
     * A WARNING rather than an error. Falling through a `when` is legal — it is what `if` with no `else`
     * does — so a missing member is "you probably meant to handle this", not a broken graph. Adding a member
     * to an enum and being told which `when`s no longer cover it is most of what this is for, and that must
     * not stop the script from running while it is being fixed.
     *
     * The duplicate check is separate and unconditional: arms are tested in order, so a repeated case is
     * dead code in every form, and unlike a missing member it is never what somebody meant.
     */
    private fun checkWhen(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        val enums = visibleEnums()
        for (n in graph.nodes) {
            if (n.type != BuiltinNodes.WHEN) continue
            val count = BuiltinNodes.whenCount(n)
            val cases = (1..count).map { i -> BuiltinNodes.whenCase(i) to graph.linkInto(n.id, i.let(BuiltinNodes::whenCase)) }

            // Which member each case names, for the arms that name one at all. A case wired from anything
            // else — a variable, a call — is not a member and simply does not count towards coverage.
            val named = LinkedHashMap<String, String>()   // pin -> member
            for ((pin, link) in cases) {
                val src = link?.let { graph.node(it.fromNode) } ?: continue
                if (src.type != BuiltinNodes.ENUM_OF) continue
                src.literals[BuiltinNodes.ENUM_MEMBER]?.toString()?.trim()?.let { named[pin] = it }
            }

            val seen = HashSet<String>()
            for ((pin, member) in named) {
                if (!seen.add(member.lowercase())) {
                    issues += Issue(
                        Severity.WARNING,
                        "this case is already handled above — arms are tried in order, so '$member' " +
                            "can never reach this one",
                        n.id, pin = pin,
                    )
                }
            }

            // Exhaustiveness, only with a subject, only over an enum, and only without an else.
            //
            // Read off the marker rather than the wiring, because `Else` is wired either way — see
            // [BuiltinNodes.WHEN_HAS_ELSE]. A canvas graph carries no marker and so is asked the question,
            // which is the right default: a hand-wired `when` missing a member is exactly as worth
            // mentioning as a written one.
            if (n.literals[BuiltinNodes.WHEN_HAS_ELSE] == true) continue
            val subject = graph.linkInto(n.id, BuiltinNodes.WHEN_SUBJECT) ?: continue
            val from = graph.node(subject.fromNode) ?: continue
            val type = descOfFor(graph, from)?.output(subject.fromPin)?.let {
                effectivePinType(from, it, { name -> scope.variable(name)?.type }) { id, p ->
                    graph.linkInto(id, p)?.let { l ->
                        graph.node(l.fromNode)?.let { s -> descOfFor(graph, s)?.output(l.fromPin)?.type }
                    }
                }
            } ?: continue
            val e = enums.firstOrNull { it.name.equals(type.name, true) } ?: continue

            val missing = e.members.filter { m -> named.values.none { it.equals(m, true) } }
            if (missing.isNotEmpty()) {
                issues += Issue(
                    Severity.WARNING,
                    "'${e.name}' also has ${missing.joinToString(", ")} — with no 'else', " +
                        (if (missing.size == 1) "that value" else "those values") + " falls straight through",
                    n.id, pin = BuiltinNodes.WHEN_SUBJECT,
                )
            }
        }
        return issues
    }

    /**
     * A node's pins, resolved against the DOCUMENT — a Call's signature, a record's fields, and a generic
     * call's substituted types.
     *
     * **One of these, where there used to be six near-copies.** Phase E is what forced the consolidation:
     * a generic Call's pins are substituted from whatever is wired into `self`, so resolving a node now
     * needs to ask what feeds it — and answering *that* needs to resolve the node on the other end. The
     * two are mutually recursive, which six independent local copies could not have been.
     *
     * [depth] is that recursion's only bound, and it has to be threaded through both halves rather than
     * restarted in each: a hand-edited file may contain a ring of Holds, and a guard that resets on every
     * hop is no guard at all.
     */
    private fun descOfFor(graph: Graph, node: Node, depth: Int = 0): NodeDescriptor? =
        catalog[node.type]?.let {
            resolveNode(node, it, scope::function, { visibleTypes() }, pureIn(graph), { visibleEnums() }) { n, p ->
                if (depth > 32) null else feedingIn(graph, n, p, depth + 1)
            }
        }

    /** What the wire into [nodeId]'s [pin] carries — the graph half of [effectivePinType]'s question. */
    private fun feedingIn(graph: Graph, nodeId: Int, pin: String, depth: Int): TypeRef? {
        if (depth > 32) return null
        // Nothing wired in: whatever was TYPED in, when its kind decides a type — see `literalTypeOf`.
        val l = graph.linkInto(nodeId, pin)
            ?: return dev.ziggle.vscript.model.literalTypeOf(graph.node(nodeId)?.literals?.get(pin))
        val src = graph.node(l.fromNode) ?: return null
        val out = descOfFor(graph, src, depth + 1)?.output(l.fromPin) ?: return null
        return effectivePinType(src, out, { name -> scope.variable(name)?.type }) { n, p ->
            feedingIn(graph, n, p, depth + 1)
        }
    }

    private fun checkTypeTests(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        val declared = visibleTypes().map { it.name }
        for (n in graph.nodes) {
            if (n.type != BuiltinNodes.IS_TYPE) continue
            val named = n.literals[BuiltinNodes.IS_OF]?.toString()?.trim().orEmpty()
            if (named.isEmpty()) {
                issues += Issue(Severity.ERROR, "'is' needs a type to ask about", n.id, pin = BuiltinNodes.IS_OF)
                continue
            }
            if (BuiltinNodes.IS_TESTABLE.any { it.equals(named, true) }) continue
            if (declared.any { it.equals(named, true) }) continue
            // A CHOICE is its member's name at run time — a String, exactly like the SKILL and ENUM kinds
            // this check already refuses. Declaring one does not add a tag to the value, so the test cannot
            // tell a `Phase` from a `Skill` from any string at all. Named separately from the message below
            // because the fix is different and worth saying: compare it, do not test it.
            scope.visibleEnums().firstOrNull { it.name.equals(named, true) }?.let { e ->
                issues += Issue(
                    Severity.ERROR,
                    "'$named' is a choice, and a choice is its member's NAME at run time — so this cannot " +
                        "tell it apart from any other text. Compare it instead: " +
                        "x == $named.${e.members.first()}",
                    n.id,
                    pin = BuiltinNodes.IS_OF,
                )
                continue
            }
            val known = dev.ziggle.vscript.model.Types.of(named) != null
            issues += Issue(
                Severity.ERROR,
                if (known) {
                    "'$named' is not a type the run time can tell apart — it shares its form with " +
                        "another, so the answer would mean nothing. Testable: " +
                        BuiltinNodes.IS_TESTABLE.joinToString(", ") + ", or a record"
                } else {
                    "nothing here is a type called '$named'"
                },
                n.id,
                pin = BuiltinNodes.IS_OF,
            )
        }
        return issues
    }

    /**
     * A cast has to be able to fill every field of what it is casting TO.
     *
     * Matched by name, or by a rename saying which source field a target field comes from. Nothing is
     * zero-filled, so a target field with no source is an error rather than a quiet default — a zero is
     * indistinguishable from a value that was genuinely zero, and a field you forgot would look exactly
     * like one you meant to leave empty.
     */
    private fun checkCasts(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        val declared = visibleTypes()
        fun descOf(node: Node): NodeDescriptor? = descOfFor(graph, node)
        for (n in graph.nodes) {
            if (n.type != BuiltinNodes.CAST) continue
            val named = n.literals[BuiltinNodes.CAST_OF]?.toString()?.trim().orEmpty()
            // A target with type arguments is a container, never a declared record — so the record lookup
            // is skipped and the decision about whether it is LEGAL waits until the source is known. Only
            // a decode can use arguments; see the refusal below.
            val ref = dev.ziggle.vscript.model.TypeRef.parse(named)
            val generic = named.isNotEmpty() && ref.args.isNotEmpty()
            val target = declared.firstOrNull { it.name.equals(named, true) }
            if (target == null && !generic) {
                // A choice has no fields, so there is no structure to read one as — `as` copies a record's
                // fields into another record's, and an enum has none. Said plainly rather than letting it
                // fall into "nothing here is a record called 'Phase'", which is false: it IS here, and it is
                // not a record.
                val isEnum = scope.visibleEnums().any { it.name.equals(named, true) }
                issues += Issue(
                    Severity.ERROR,
                    when {
                        named.isEmpty() -> "'as' needs a type to cast to"
                        isEnum -> "'$named' is a choice, not a record — there are no fields to read across. " +
                            "'as' reads one record as another"
                        else -> "nothing here is a record called '$named' — 'as' reads one record as another"
                    },
                    n.id, pin = BuiltinNodes.CAST_OF,
                )
                continue
            }
            val varType = { name: String -> scope.variable(name)?.type }
            val valuePin = descOf(n)?.input("Value") ?: continue
            // Recursive, like the wire check's: a cast usually reads a `let`, and a Hold's type is
            // whatever fed IT — one hop is not enough to reach the record.
            fun feeding(id: Int, pin: String, depth: Int = 0): TypeRef? {
                if (depth > 32) return null
                val l = graph.linkInto(id, pin) ?: return null
                val src = graph.node(l.fromNode) ?: return null
                val out = descOf(src)?.output(l.fromPin) ?: return null
                return effectivePinType(src, out, varType) { i, p -> feeding(i, p, depth + 1) }
            }
            val sourceType = effectivePinType(n, valuePin, varType) { id, pin -> feeding(id, pin) }
            // A `Json` on the left is a DECODE, not a field-copy: there is no source record to read fields
            // out of, only a document that either matches the target's declaration or does not. The check
            // is therefore "can this type be read from JSON at all", asked of the target — and the answer
            // is the same schema the compiler will bake, so a cast the validator accepts is one the
            // compiler can always emit.
            if (sourceType.name.equals("Json", true)) {
                issues += checkJsonCast(n, ref, target, declared)
                continue
            }
            // Arguments are only meaningful to a decode. Between records `as` copies named fields across,
            // and a container has no named fields to copy — so this is refused HERE, where the source is
            // known, rather than by the parser, which cannot tell the two forks apart.
            if (generic) {
                issues += Issue(
                    Severity.ERROR,
                    "'$named' has type arguments, and only a cast from a document can use them — between " +
                        "records 'as' copies named fields, which a list or a map does not have",
                    n.id, pin = BuiltinNodes.CAST_OF,
                )
                continue
            }
            val source = declared.firstOrNull { it.name.equals(sourceType.name, true) }
            if (source == null) {
                issues += Issue(
                    Severity.ERROR,
                    "'as' reads one record as another, and this is a ${sourceType.name}",
                    n.id, pin = "Value",
                )
                continue
            }
            // Past the generic refusal above the target IS a record, but that follows from two separate
            // conditions and Kotlin will not carry it across them on its own.
            if (target == null) continue
            val renames = BuiltinNodes.castRenames(n)
            for ((to, from) in renames) {
                if (target.fields.none { it.name.equals(to, true) }) {
                    issues += Issue(Severity.ERROR, "'${target.name}' has no field '$to'", n.id)
                }
                if (source.fields.none { it.name.equals(from, true) }) {
                    issues += Issue(Severity.ERROR, "'${source.name}' has no field '$from'", n.id)
                }
            }
            for (f in target.fields) {
                val wanted = renames[f.name] ?: f.name
                val got = source.fields.firstOrNull { it.name.equals(wanted, true) }
                if (got == null) {
                    if (renames.containsKey(f.name)) continue // already reported above
                    issues += Issue(
                        Severity.ERROR,
                        "'${source.name}' has nothing called '${f.name}' to fill '${target.name}.${f.name}' " +
                            "— name the field it comes from: as ${target.name} { ${f.name}: … }",
                        n.id,
                    )
                    continue
                }
                if (!canConnect(got.type, f.type)) {
                    issues += Issue(
                        Severity.ERROR,
                        "'${source.name}.${got.name}' is ${got.type} and '${target.name}.${f.name}' is ${f.type}",
                        n.id,
                    )
                }
            }
        }
        return issues
    }

    /**
     * `json as Doc` — every field of the target has to have a form JSON can hold.
     *
     * **The same rule as the record cast, asked of a different source.** There the question is "can this
     * record fill every field of that one"; here it is "can a document fill every field of this one", and
     * both refuse to zero-fill. The difference is only where the answer comes from: a record's fields are
     * known statically, and a document's are not known until it is read — so what is checked at compile
     * time is the SHAPE the reader will demand, and the demand itself happens at run time with the path
     * of the offending value in the message.
     *
     * Building the schema *is* the check. There is no second list of what JSON can hold that could drift
     * from what the decoder actually accepts.
     */
    private fun checkJsonCast(
        n: Node,
        ref: dev.ziggle.vscript.model.TypeRef,
        target: dev.ziggle.vscript.model.StructType?,
        declared: List<dev.ziggle.vscript.model.StructType>,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        val enums = scope.visibleEnums()
        // Straight from the parsed ref, so a container target carries its arguments into the schema. The
        // builder has always handled `LIST<Item>` and `MAP<STRING, Tally>` at any depth; it simply never
        // used to be handed one at the root.
        val built = dev.ziggle.vscript.json.JsonSchema.of(
            ref,
            types = { name -> declared.firstOrNull { it.name.equals(name, true) } },
            enums = { name -> enums.firstOrNull { it.name.equals(name, true) }?.members },
        )
        built.problem?.let {
            issues += Issue(Severity.ERROR, "reading a $ref from JSON: $it", n.id, pin = BuiltinNodes.CAST_OF)
            return issues
        }
        // A rename names a field of the target record; a container has none, so there is nothing to check
        // and nothing legal to write either.
        if (target == null) {
            for ((to, _) in BuiltinNodes.castRenames(n)) {
                issues += Issue(Severity.ERROR, "'$ref' has no field '$to' — only a record has fields to rename", n.id)
            }
            return issues
        }
        // A rename here names a JSON KEY, not a field of a source record — there is no source record. So
        // the target side is checked exactly as before and the other side is not checked at all: any text
        // is a legal JSON key, and a key the document does not have is reported by the reader, with the
        // path, at the moment it looks.
        for ((to, _) in BuiltinNodes.castRenames(n)) {
            if (target.fields.none { it.name.equals(to, true) }) {
                issues += Issue(Severity.ERROR, "'${target.name}' has no field '$to'", n.id)
            }
        }
        return issues
    }

    private fun checkImpureReached(graph: Graph): List<Issue> {
        val issues = ArrayList<Issue>()
        fun descOf(node: Node): NodeDescriptor? = descOfFor(graph, node)

        // Everything exec can start from: every entry of every kind, and every function's boundary.
        val reached = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        fun seed(id: Int) { if (reached.add(id)) queue += id }
        graph.allEntries(catalog).forEach { seed(it.id) }
        graph.nodes.filter { it.type == BuiltinNodes.FUNCTION }.forEach { seed(it.id) }

        while (queue.isNotEmpty()) {
            val n = graph.node(queue.removeFirst()) ?: continue
            val d = descOf(n) ?: continue
            for (pin in d.execOutputs) {
                graph.linksFrom(n.id, pin.name).forEach { seed(it.toNode) }
            }
        }

        issues += checkDrivenEntries(graph, ::descOf, tickMayWait)
        if (!tickMayWait) issues += checkSleepIsObserved(graph, catalog)

        for (l in graph.links) {
            val from = graph.node(l.fromNode) ?: continue
            if (from.id in reached) continue
            val d = descOf(from) ?: continue
            if (d.kind != NodeKind.IMPURE) continue
            if (d.output(l.fromPin)?.type?.isExec != false) continue
            issues += Issue(
                Severity.ERROR,
                "'${d.title}.${l.fromPin}' is read here, but nothing runs '${d.title}' — " +
                    "wire it into the exec chain, or the value read will be nothing at all",
                from.id, l.id, l.fromPin,
            )
        }
        return issues
    }

}

/** Thrown when compilation is asked to proceed on a graph with [Severity.ERROR] issues. */
class GraphCompileException(val issues: List<Issue>) :
    RuntimeException("graph has ${issues.count { it.severity == Severity.ERROR }} error(s):\n" +
        issues.joinToString("\n") { "  $it" })

/** Convenience: the [Severity.ERROR] subset. */
fun List<Issue>.errors(): List<Issue> = filter { it.severity == Severity.ERROR }

    /**
 * What an On Render or On Tick entry may not reach.
 *
 * Neither can wait. Anything that blocks — walking, interacting, a Delay — would either stall the client
 * for as long as the game takes to answer, or be cut off mid-way by the pass budget and read as a handler
 * that "sometimes works". Refusing it before the script runs is the only version of this that is not a
 * mystery later.
 *
 * **One check for both**, because the reason is the same one twice: each rides a clock the client owns —
 * the frame, the game tick — and runs synchronously on the client thread, so a wait there is a wait for
 * everything. Only the advice at the end differs, and that is a string.
 *
 * Followed THROUGH function calls, because a rule that stops at the call boundary is one you get past
 * by putting the walk in a function — which is exactly what anyone would do next.
 */
private fun checkDrivenEntries(graph: Graph, descOf: (Node) -> NodeDescriptor?, tickMayWait: Boolean): List<Issue> {
    val issues = ArrayList<Issue>()
    issues += checkCannotWait(
        graph, descOf, graph.renderEntries(), "On Render", "a frame",
        "Do it in the loop and let On Render draw what the loop decided.",
    )
    if (!tickMayWait) issues += checkCannotWait(
        graph, descOf, graph.tickEntries(), "On Tick", "a tick",
        "Decide it here, and let the loop do it.",
    )
    return issues
}

/**
 * An `on sleep` in a document whose own loop never asks whether a sleep was requested.
 *
 * Nothing can interrupt a `while true`, so a handoff happens only if the loop polls for it. A script
 * that declares the handler and never reads Sleep Requested looks cooperative and is not: the host asks,
 * nothing answers, and the script is killed on the timeout instead — which is the same outcome as having
 * written no handler at all, arrived at slowly and with a bank trip missing.
 *
 * **Only for a document that has an `on start` of its own**, and that exemption is the whole subtlety. A
 * library contributing `always on sleep` to save the state IT owns is behaving correctly by never
 * polling: deciding when to quiesce belongs to the document that owns the loop, and warning the library
 * would be telling it to do something it must not do.
 */
private fun checkSleepIsObserved(graph: Graph, catalog: NodeCatalog): List<Issue> {
    val sleeps = graph.sleepEntries()
    if (sleeps.isEmpty() || graph.entries(catalog).isEmpty()) return emptyList()
    if (graph.nodes.any { it.type == BuiltinNodes.SLEEP_REQUESTED }) return emptyList()
    return listOf(
        Issue(
            Severity.WARNING,
            "this document can be asked to sleep and nothing checks whether it has been — read Sleep " +
                "Requested somewhere the loop could safely hand over, and leave the loop when it is " +
                "true. Without it On Sleep is only reached once the handoff times out.",
            sleeps.first().id,
        ),
    )
}

private fun checkCannotWait(
    graph: Graph,
    descOf: (Node) -> NodeDescriptor?,
    roots: List<Node>,
    what: String,
    inside: String,
    advice: String,
): List<Issue> {
    if (roots.isEmpty()) return emptyList()
    val issues = ArrayList<Issue>()
    val seen = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    fun visit(id: Int) { if (seen.add(id)) queue += id }
    roots.forEach { visit(it.id) }
    while (queue.isNotEmpty()) {
        val n = graph.node(queue.removeFirst()) ?: continue
        val d = descOf(n) ?: continue
        when {
            d.hostKind == HostKind.BLOCKING -> issues += Issue(
                Severity.ERROR,
                "'${d.title}' waits for the game, and $what runs inside $inside — $inside cannot wait. " +
                    advice,
                n.id,
            )
            n.type == BuiltinNodes.DELAY -> issues += Issue(
                Severity.ERROR,
                "Delay inside $what: $inside has nothing to wait for, and the pass is abandoned if it " +
                    "does not finish.",
                n.id,
            )
        }
        for (pin in d.execOutputs) graph.linksFrom(n.id, pin.name).forEach { visit(it.toNode) }
        // Into the body of anything it calls.
        if (n.type == BuiltinNodes.CALL) n.callee?.let { name -> graph.entryOf(name)?.let { visit(it.id) } }
        // ...and into anything it calls through a VALUE, which is a wire rather than a name.
        //
        // The gap this closes was real and silent: a function value reaches its body through a
        // `value.function` node, and this walk only followed `callee`. So a lambda that blocks — passed to
        // Invoke, or to `filtered` inside an `on render` — went straight past the one check that exists to
        // refuse it, and the frame pass would park where it cannot. Following the wire is the whole fix,
        // and it costs nothing on a graph that passes no functions.
        for (pin in d.dataInputs) {
            if (!pin.type.isFunction) continue
            val src = graph.linkInto(n.id, pin.name)?.let { graph.node(it.fromNode) } ?: continue
            if (src.type != BuiltinNodes.FUNCTION_REF) continue
            src.literals[BuiltinNodes.FUNCTION_REF_NAME]?.toString()
                ?.let { graph.entryOf(it) }
                ?.let { visit(it.id) }
        }
    }
    return issues
}

