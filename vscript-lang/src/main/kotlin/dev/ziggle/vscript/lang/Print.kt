package dev.ziggle.vscript.lang

import dev.ziggle.vscript.model.GraphMarks
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphFunction
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.model.ImportClosure
import dev.ziggle.vscript.model.ImportScope
import dev.ziggle.vscript.model.Literals
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.QualName
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.expressionCalls
import dev.ziggle.vscript.model.isPureFunction
import dev.ziggle.vscript.model.effectivePinType
import dev.ziggle.vscript.model.resolveNode

/**
 * [Graph] → text. The inverse of [Lower], and where every recognizer lives.
 *
 * **A recognizer is a predicate over graph shape that fires on exactly what the lowering produces.** That is
 * the rule the whole language is built on: sugar is admissible only if it can be raised back out of the
 * graph, so each piece of syntax `Lower` emits has a matching test here. `&&` is a Select whose False arm is
 * the literal `false`; an `else if` is a Branch reached only from the enclosing Branch's False pin; a `let`
 * is a Hold. No recognizer, no sugar — which is why `break` had to become a node rather than a lowering.
 *
 * **Structure is recovered by finding where arms rejoin.** A graph has no `if` — it has a Branch whose two
 * exec outputs eventually reach the same node. Printing one means finding that node (the first reachable
 * from both arms), emitting each arm up to it, and continuing from there. Loops are the same question with
 * the answer already known: a body's chain simply runs out, because the compiler supplies the back jump.
 */
class Print(
    private val catalog: NodeCatalog,
    private val names: Names = Names(catalog),
    /**
     * Where imports are looked up — needed to print an imported call's ARGUMENTS, whose pin names come
     * from the callee's signature. Without it such a call still prints, with whatever pins the node
     * carries.
     */
    private val source: GraphSource = GraphSource.NONE,
    /**
     * Where this document's comments sat, when it came from text.
     *
     * Empty for a graph off the canvas, which has no comments to place — so the printer behaves exactly as
     * it always has unless a caller hands it a plan built by [VsText.read]. That is what keeps the rule
     * intact: a comment still never enters the graph, it rides alongside, and only a text-to-text caller
     * has one to ride.
     */
    private val comments: Comments = Comments.NONE,
) {

    private lateinit var graph: Graph

    /** What names mean in [graph] — see [ImportScope]. Rebuilt per [print] call. */
    private var scope: ImportScope = ImportScope.single(Graph(id = "", name = ""))
    private val sb = StringBuilder()
    private var indent = 0

    /** Hold node → the name it binds, so a reader of its output prints as that name. */
    private val holdNames = HashMap<Int, String>()

    /** Literal node → its `const` name, from [GraphMarks.CONST_NAME]. */
    private val constNames = HashMap<Int, String>()

    /**
     * Literal node → the IMPORTED const it stands in for, from [GraphMarks.IMPORTED_CONST].
     *
     * Kept apart from [constNames] because the two say opposite things: that one is a const this document
     * DECLARES, and gets a `const x = …` line written for it; this one is a reference to another
     * document's, and must produce no declaration at all — only the qualified name at each use.
     */
    private val importedConsts = HashMap<Int, String>()

    /**
     * variable name → the `@init` Set that computes its default.
     *
     * A default that has to run lives as a node at the head of `on start` rather than as a value on the
     * declaration — see `Lower.emitInits`. Both halves of printing it read this: the declaration prints
     * the expression, and the statement walker skips the node so one line does not become two.
     */
    private val initSets = HashMap<String, dev.ziggle.vscript.model.Node>()

    /** ForEach node → the names its Element and Index were given. */
    private val loopNames = HashMap<Int, Pair<String, String?>>()

    /**
     * Nodes currently being printed as values, so a cycle reports instead of overflowing the stack.
     *
     * The editor cannot draw a data cycle and the validator refuses one, but a hand-edited file can contain
     * it — and the failure without this is a StackOverflowError with a thousand identical frames, which says
     * nothing about which node is at fault.
     */
    private val printing = HashSet<Int>()

    /**
     * Branches whose rejoin point is currently being worked out.
     *
     * [rejoin] and [walk] call each other, and [rejoin] starts fresh visited sets every time — so `walk`'s
     * own guard, which only grows within a single walk, cannot stop the pair re-descending the same Branch
     * once exec flow loops back to it. This is the guard that spans the hop: a Branch already being
     * resolved has no knowable continuation, so it reports none rather than asking again.
     */
    private val resolving = HashSet<Int>()

    /**
     * "Is this Call an expression?" — without it, a call to an expression-bodied function resolves with exec
     * pins it does not have, and gets printed as a step as well as read as a value.
     */
    private var pure: (Node, dev.ziggle.vscript.model.GraphFunction) -> Boolean = { _, _ -> false }

    /**
     * `(node, output pin)` → the name a statement binds it to.
     *
     * An impure node is a step AND a value: it runs where it sits, and something later reads what it
     * produced. Printed naively that is the call written twice — once as the statement, once inlined into
     * its reader — which is not merely ugly, it is a different graph. So a step whose output is read prints
     * as `let name = call(...)` and readers use the name, which is the inverse of the normalisation in
     * VSCRIPT_LANG_PLAN.md §6.2: a `let` named after the pin it already has needs no Hold.
     */
    private val boundNames = HashMap<Pair<Int, String>, String>()

    /**
     * Steps written where they are READ rather than as a statement of their own.
     *
     * A binding is only needed when the value has to outlive the statement that made it. When a step's one
     * output has one reader and that reader is the very next step, `say(describe(3))` says everything and a
     * name says nothing — and naming it is actively harmful, because a second call in the same block cannot
     * reuse the name, and a renamed binding no longer matches its pin so re-reading it grows a Hold. That
     * is not merely noisy, it never settles: each pass adds another.
     */
    private val inlined = HashSet<Int>()

    /**
     * Nodes an `@id` has been written for — and therefore the record of what a `$id` may refer to.
     *
     * One set, not two, and that is the point: a reference is only readable if the definition it names was
     * actually emitted. A node whose value prints as a NAME — a `let`, a variable, a loop variable, a
     * parameter — writes no `@id`, so it must not be referred to either. Tracking "have I printed this"
     * separately from "did I give it an id" is exactly how the first attempt produced `$8` for a node the
     * file never defined.
     */
    private val annotated = HashSet<Int>()

    /**
     * Node id → the 1-based line its statement was written on.
     *
     * The channel the editor links the two views on: click a node, land on its line; click a gutter, arm a
     * breakpoint on the node that line belongs to. Line granularity on purpose — a breakpoint is armed on a
     * *step*, never on a literal buried in an expression — which is also why this works where finer schemes
     * did not. Character offsets fail because a line is assembled as a string and appended whole, so the
     * buffer length while building it is not where the text lands; a line NUMBER is known exactly at the
     * moment the line is appended.
     *
     * Only meaningful while the text is the printed form of the graph. `CodeBuffer` drops it the moment
     * anything is typed, because after that the mapping describes a document that no longer exists.
     */
    val lineOf: Map<Int, Int> get() = lineNumbers
    private val lineNumbers = HashMap<Int, Int>()
    private var lineCount = 1
    private var statementNode: Int? = null

    /** The function box being printed, so a body's `@in` is not restated on every statement. */
    private var currentBox: Int? = null

    fun print(graph: Graph): String {
        this.graph = graph
        sb.setLength(0)
        indent = 0
        holdNames.clear(); constNames.clear(); importedConsts.clear(); loopNames.clear()
        printing.clear(); resolving.clear(); boundNames.clear(); inlined.clear(); annotated.clear()
        currentBox = null
        lineNumbers.clear(); lineCount = 1; statementNode = null
        scope = ImportScope(
            if (graph.imports.isEmpty()) ImportClosure.single(graph) else ImportClosure.resolve(graph, source),
            graph,
        )
        pure = expressionCalls(catalog, graph.nodes, graph.links, scope::function) { scope.pureAcrossImport(catalog, it) }

        collectNames()
        collectBindings()

        graph.name.takeIf { it.isNotBlank() && it != "untitled" }?.let { line("graph ${quote(it)}"); blank() }

        // Above everything, because a type below may be declared through one and a body may call through
        // one. The REF is printed, never the resolved id: the id is storage, and a person reading a diff
        // wants the name they wrote.
        for (i in graph.imports) {
            commentsForDecl("import:${i.alias}")
            // A pure `export … from` is an import EDGE that binds nothing here, so it goes back as the
            // re-export it was written as. A line that does both — which nothing can write today, but
            // which the document format can carry — prints both halves rather than losing one.
            if (i.isReExport) line("export ${i.reExportSpelling()} from ${quote(i.ref)}")
            // `spelling()` and not the alias: an unqualified import's alias is synthesised and unwritable,
            // so what goes back is the `* as x` / `{…}` that was typed. See GraphImport.anonymousAlias.
            // `import "core/list"` — nothing before the quote, so no `from` either.
            if (i.star) line("import ${quote(i.ref)}")
            else if (i.bindsLocally) line("import ${i.spelling()} from ${quote(i.ref)}")
        }
        if (graph.imports.isNotEmpty()) blank()

        for (t in graph.types) {
            // `export default { … }` is a record and one instance of it, both named `@default` — printed
            // as the one line that was written, exactly as a `single` is. The variable is skipped below,
            // and its initialiser is skipped in the prologue, or the round trip would give back the three
            // pieces it became instead of the declaration it is.
            if (t.name == Parser.DEFAULT_BUNDLE) {
                line("export default { ${t.fields.joinToString(", ") { it.name }} }")
                blank()
                continue
            }
            commentsForDecl("type:${t.name}")
            val vis = exportWord(t.name, t.isExported)
            // `<A, B>` when the record is generic. A parameter prints as its bare name and reads back as
            // one because the DECLARATION says which names those are — the same bargain a function's
            // variables make, without the spelling convention, since nothing here has to be inferred.
            val params = if (t.params.isEmpty()) "" else t.params.joinToString(", ", "<", ">")
            // A field's default prints exactly as an enum column's does — same shape, same reason: it is
            // stored on the pin, so writing it back is what makes the declaration round-trip.
            val fields = t.fields.joinToString(", ") {
                val default = it.default?.let { d -> " = " + literal(d, it.type) }
                    // A computed default lives in a function rather than on the pin — printed back onto
                    // the declaration, because that is where it was written.
                    ?: fieldDefaultText(t.name, it.name)?.let { e -> " = $e" }
                    ?: ""
                "${it.name}: ${it.type}$default"
            }
            // A `single` is a record AND the one variable of it — see StructType.isSingle. Printed as
            // the one declaration that was written; the variable is skipped below, or the round trip
            // would give back the two pieces it became.
            val kw = if (t.isSingle) "single" else "type"
            // **An anonymous default prints with no name**, because that is how it was written and how it
            // has to read back — `single @default` is not something the parser accepts, so emitting the
            // synthesised name would be a round trip that does not survive its own output.
            val named = if (t.name == ANONYMOUS_DEFAULT) "" else " ${t.name}"
            line("${vis}$kw$named$params { $fields }")
        }
        if (graph.types.isNotEmpty()) blank()

        // Beside the records, and above the variables for the same reason: one may be typed as an enum.
        for (e in graph.enums) {
            commentsForDecl("enum:${e.name}")
            val vis = exportWord(e.name, e.isExported)
            if (e.fields.isEmpty()) {
                line("${vis}enum ${e.name} { ${e.members.joinToString(", ")} }")
                continue
            }
            // A table, so one member per line: the whole point of the form is that the rows line up to be
            // read down, and a comma-joined one-liner would be unreadable at nine members of four columns.
            val cols = e.fields.joinToString(", ") {
                "${it.name}: ${it.type}" +
                    (it.default?.let { d -> " = " + (functionSlotText(d, it.type) ?: literal(d, it.type)) } ?: "")
            }
            line("${vis}enum ${e.name}($cols) {")
            for (m in e.members) {
                // What the member actually GAVE, not one entry per column: a row that stopped early
                // because the rest have defaults has to print back the same way, or the round trip would
                // write out the defaults it was allowed to leave off.
                val values = e.values[m].orEmpty()
                val row = values.indices.joinToString(", ") { i ->
                    val t = e.fields.getOrNull(i)?.type
                    // A handler column holds a function's NAME. Quoting it would print a string where a
                    // name was written, and the string would not read back as the same thing.
                    val v = values[i]
                    functionSlotText(v, t) ?: literal(v, t)
                }
                line(if (values.isEmpty()) "    $m," else "    $m($row),")
            }
            line("}")
        }
        if (graph.enums.isNotEmpty()) blank()

        // A `single`'s instance is printed as part of its declaration above, not here.
        val singles = graph.types.filter { it.isSingle }.map { it.name }.toSet()
        // ...and the default bundle's one instance, for the same reason: the declaration was one line.
        val vars = graph.variables.filterNot { it.name in singles || it.name == Parser.DEFAULT_BUNDLE }
        for (v in vars) {
            commentsForDecl("var:${v.name}")
            // A default that had to RUN is an `@init` Set at the head of `on start`, not a stored value —
            // see Lower.emitInits. Printed back here as the declaration it was written as, and skipped
            // where it sits in the chain, or the round trip would turn one line into two.
            val init = initSets[v.name]
            // A stored default whose type is an ENUM is the member's name, and it has to be given back
            // qualified — `= Phase.Chop`, not `= "Chop"`. The type is what says so: nothing about the
            // stored string distinguishes it from any other string, which is the same reason `is` cannot
            // work on one.
            val enum = scope.visibleEnums().firstOrNull { it.name.equals(v.type.name, true) }
            val default = when {
                init != null -> " = ${valueInto(init.id, "Value")}"
                v.default != null && enum != null ->
                    " = ${enum.name}.${enum.member(v.default.toString()) ?: v.default}"
                v.default != null -> " = ${literal(v.default, v.type)}"
                else -> ""
            }
            // `val` is the same variable with one thing forbidden, so it prints as the declaration it was
            // written as rather than as a `var` plus the `@init` that sets it — which is two lines for one,
            // and two lines that no longer say the value cannot change.
            val kw = if (v.isImmutable) "val" else "var"
            line("${exportWord(v.name, v.isExported)}$kw ${v.name}: ${v.type}$default")
        }
        if (vars.isNotEmpty()) blank()

        // `val`, not `const`: a value written out is what BOTH words made, so there is one construct here
        // and the printer picks the one spelling. `const` is still read — see `Parser.constDecl` — and this
        // is what converges a file that used it the first time a tool writes it back.
        for (n in graph.nodes.filter { it.type in BuiltinNodes.LITERALS && constNames.containsKey(it.id) }) {
            // The NODE's own type, not null. A tile is stored as the text "3200,3200,0" and there is a
            // `literal.tile` node type saying so, but passing null asked `literal` to guess from the value
            // — and a tile is indistinguishable from any other string once written down, so `val Home =
            // tile(…)` came back as `val Home = "3200,3200,0"`. Same failure as an inline tile on a
            // wildcard pin, one declaration up.
            val t = resolved(n).output("Value")?.type
            val vis = exportWord(constNames.getValue(n.id), n.literals[GraphMarks.CONST_EXPORTED] == true)
            // The type the author wrote, when they wrote one — see [GraphMarks.CONST_TYPE]. A folded `val`
            // keeps its annotation now, so leaving it off here would round-trip the declaration into a
            // different, if equivalent, one.
            val declared = n.literals[GraphMarks.CONST_TYPE]?.toString()?.let { ": $it" }.orEmpty()
            line(
                "${ann(n)}${vis}val ${constNames.getValue(n.id)}$declared = " +
                    literal(n.literals["Value"], t),
            )
        }
        if (constNames.isNotEmpty()) blank()

        // Entries in ID order, which is the order they were WRITTEN — not grouped by kind. Concatenating
        // the four kinds instead would print `on tick` after `on render` however the file had them, and a
        // reordered file does not round-trip character-identically.
        // A lambda's synthesised function is NOT a declaration — it prints where it was written, inside
        // the call that takes it. Left in, every `xs.filter { … }` would also emit an `fn @lambda1(…)`
        // nobody typed and nothing could read back.
        val decls = graph.functions.filterNot { BuiltinNodes.isAnonymous(it.name) }
            .mapNotNull { graph.entryOf(it.name) } + graph.allEntries(catalog)
        for (d in decls) {
            if (d.type == BuiltinNodes.FUNCTION) d.function?.let { function(it) } else entry(d)
        }

        // `export { a, b }` — the surface said in one place, at the bottom, which is where it was written.
        // See Graph.exportList: the declarations it names printed without their own `export`.
        if (graph.exportList.isNotEmpty()) {
            blank()
            line(graph.exportList.joinToString(", ", "export { ", " }"))
        }

        // Anything written after the last construct. Dropped otherwise, and a file whose closing remark
        // vanished is exactly the loss this whole side table exists to stop.
        if (comments.trailing.isNotEmpty()) {
            blank()
            for (c in comments.trailing) for (l in c.split("\n")) line(l.trim())
        }

        return sb.toString().trimEnd() + "\n"
    }

    // ---- names -------------------------------------------------------------------------------------------

    private fun collectNames() {
        for (n in graph.nodes) {
            when {
                // An `if val` binds a name to a PIN exactly as a Hold binds one to a node, and every reader
                // of that pin has to print the name rather than re-expanding what produced it. Sharing the
                // table is not a shortcut: it is also what puts the name in `collectBindings`' taken set,
                // so an invented binding cannot land on top of one the author wrote.
                // ...and a `catch` binds its message the same way, off the Try node's own Error pin.
                n.type == BuiltinNodes.HOLD || n.type == BuiltinNodes.IF_SOME || n.type == BuiltinNodes.TRY ->
                    holdNames[n.id] = n.literals[BuiltinNodes.HOLD_NAME]?.toString().orEmpty().ifEmpty { "v${n.id}" }
                n.type in BuiltinNodes.LITERALS -> {
                    (n.literals[GraphMarks.CONST_NAME] as? String)?.let { constNames[n.id] = it }
                    (n.literals[GraphMarks.IMPORTED_CONST] as? String)?.let { importedConsts[n.id] = it }
                }
                n.type == BuiltinNodes.VAR_SET && n.literals.containsKey(GraphMarks.INIT_MARK) ->
                    n.variable?.let { initSets[it] = n }
                // The map loop keeps its two names the same way, and its VALUE half is always written:
                // `for (k, v)` is the only spelling — a one-name form would be a loop over the keys, which
                // is what `keysOf` is for.
                n.type == BuiltinNodes.MAP_FOR_EACH -> {
                    val k = (n.literals[GraphMarks.LOOP_ELEMENT] as? String)?.takeIf { it.isNotBlank() }
                    val v = (n.literals[GraphMarks.LOOP_INDEX] as? String)?.takeIf { it.isNotBlank() }
                    loopNames[n.id] = (k ?: "k${n.id}") to (v ?: "v${n.id}")
                }
                n.type == BuiltinNodes.FOR_EACH -> {
                    val used = graph.links.any { it.fromNode == n.id && it.fromPin == "Index" }
                    // The names an author wrote, when the node carries them. Inventing one from the node id
                    // is the fallback for a loop built on the canvas, which has no names to carry — and it
                    // is only *stable* because the invented name is written into the text and stored on the
                    // way back in, so the second printing agrees with the first.
                    val element = (n.literals[GraphMarks.LOOP_ELEMENT] as? String)?.takeIf { it.isNotBlank() }
                    val index = (n.literals[GraphMarks.LOOP_INDEX] as? String)?.takeIf { it.isNotBlank() }
                    loopNames[n.id] = (element ?: "x${n.id}") to (if (used) index ?: "i${n.id}" else null)
                }
            }
        }
    }

    /**
     * Name every step-output that something reads, so it can be bound once and referred to.
     *
     * **Only nodes printed as a plain call.** A ForEach is IMPURE and its Element is read, so it looked like
     * a step needing a binding — but it prints as `for x in …`, which already binds its outputs, and no
     * `let` is ever emitted for it. The readers then referred to a name that did not exist. Anything with a
     * statement form of its own is excluded for the same reason.
     */
    private fun collectBindings() {
        val used = HashSet(holdNames.values) + constNames.values + loopNames.values.flatMap { listOfNotNull(it.first, it.second) }
        val taken = HashSet(used)
        val condition = conditionSteps()
        for (n in graph.nodes) {
            if (n.type in STATEMENT_FORMS || n.type in BuiltinNodes.LITERALS) continue
            // A step on a While's Check chain IS part of the condition, and prints inside it. Naming one
            // would emit a reference with no binding to match, because nothing walks that chain looking
            // for statements to write.
            if (n.id in condition) { inlined += n.id; continue }
            val d = resolved(n)
            if (d.kind != NodeKind.IMPURE) continue
            val outs = d.dataOutputs
            val readers = graph.links.filter { l -> l.fromNode == n.id && outs.any { it.name == l.fromPin } }
            if (readers.isEmpty()) continue
            // Read once, by whatever runs next: write it there instead of naming it.
            if (readers.size == 1 && target(n.id, "Exec") == readers[0].toNode) {
                inlined += n.id
                continue
            }
            // What the author actually bound, when the document says — see [BuiltinNodes.BOUND]. Their names,
            // and only their pins.
            val written = BuiltinNodes.boundMap(n)
            if (written.isNotEmpty()) {
                for (p in outs) {
                    val local = written.entries.firstOrNull { it.key.equals(p.name, true) }?.value ?: continue
                    var name = local
                    if (!taken.add(name)) { name = "$name${n.id}"; taken.add(name) }
                    boundNames[n.id to p.name] = name
                }
                continue
            }
            // Nothing written — a canvas graph. Then ALL of them, not just the ones read: with no record of
            // which pins a binding took, positional is the only spelling available, and it is positional over
            // the node's outputs, so naming a subset would re-read as the wrong pins.
            val names = if (outs.size == 1) listOf(outs[0]) else outs
            for (p in names) {
                var name = Names.pinText(p.name)
                if (!taken.add(name)) { name = "$name${n.id}"; taken.add(name) }
                boundNames[n.id to p.name] = name
            }
        }
    }

    /**
     * Every node on a `While`'s Check chain — the steps its condition needs, re-run before each test.
     *
     * They belong to the condition EXPRESSION rather than to the block around it, which is the whole point
     * of the pin: `while overTen(i) == 0` used to lower the call into the enclosing chain, where it ran
     * once, and printed back as `let result = overTen(i)` above a loop testing `result` — a different
     * program from the one written.
     */
    private fun conditionSteps(): Set<Int> {
        val out = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        for (n in graph.nodes.filter { it.type == BuiltinNodes.WHILE }) {
            graph.linksFrom(n.id, "Check").forEach { if (out.add(it.toNode)) queue += it.toNode }
        }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val node = graph.node(id) ?: continue
            for (pin in resolved(node).execOutputs) {
                graph.linksFrom(id, pin.name).forEach { if (out.add(it.toNode)) queue += it.toNode }
            }
        }
        return out
    }

    /** The names bound by [nodeId], in pin order, or empty when nothing reads it. */
    private fun boundOf(nodeId: Int): List<String> {
        val node = graph.node(nodeId) ?: return emptyList()
        return resolved(node).dataOutputs.mapNotNull { boundNames[nodeId to it.name] }
    }

    /**
     * The inside of a `let (…)`, positional where that says the same thing and BY NAME where it does not.
     *
     * A positional binding takes a leading PREFIX of the outputs — that is all the spelling can express — so
     * printing one for any other selection is not a formatting choice, it is a different program. Binding a
     * node's second and sixth outputs printed as `let (a, b) = …`, which reads back as its first and second:
     * the names survived and the wires moved. Only text could not produce that before, so it sat latent; the
     * named form makes it expressible, which is exactly why the printer has to answer it now.
     *
     * Positional is kept where it IS the prefix, because that is what most bindings are and `let (id, name)`
     * reads better than naming both pins. The rule is a property of the graph, so it needs no marker: the
     * same wires always print the same way.
     */
    /** Exactly one pin bound, and it is the node's first output — the only shape `let x = f()` can say. */
    private fun bindsFirstOnly(nodeId: Int): Boolean {
        val node = graph.node(nodeId) ?: return false
        val outs = resolved(node).dataOutputs
        if (outs.isEmpty()) return false
        val bound = outs.filter { boundNames[nodeId to it.name] != null }
        return bound.size == 1 && bound[0].name == outs[0].name
    }

    private fun bindingText(nodeId: Int): String {
        val node = graph.node(nodeId) ?: return ""
        val outs = resolved(node).dataOutputs
        val bound = outs.map { boundNames[nodeId to it.name] }
        // In the order the author WROTE them, which `@bind` records, falling back to pin order for a graph
        // that carries no marker. Pin order would be a defensible canonical form and it is the wrong one:
        // a by-name list has no significant order, so reordering it changes the text every time somebody
        // reformats a list they wrote in the order they think about it.
        val written = BuiltinNodes.boundMap(node).keys
            .mapNotNull { pin -> outs.indexOfFirst { it.name.equals(pin, true) }.takeIf { it >= 0 } }
            .filter { bound[it] != null }
        val indices = written.ifEmpty { outs.indices.filter { bound[it] != null } }
        // Would a bare list read back as this same binding? Two ways it can, and they are not the same
        // question since a list of all-output names now binds BY NAME rather than by position:
        //
        //  - positionally, when the bound pins are the leading prefix; but only while the names are not all
        //    outputs, or the by-name reading would take over and could land elsewhere — `let (kind, id)`
        //    over pins Id, Kind is a prefix AND all names, and the two readings disagree.
        //  - by name, when every local IS its own pin's name.
        // Positional says "the first N outputs, in order", so it can only be written when the entries ARE
        // that — in the written order, not merely as a set.
        val isPrefix = indices == indices.indices.toList()
        val allNamesArePins = indices.all { i -> outs.any { Names.pinText(it.name) == bound[i] } }
        val eachIsItsOwn = indices.all { bound[it] == Names.pinText(outs[it].name) }
        if (isPrefix && (!allNamesArePins || eachIsItsOwn)) {
            return indices.joinToString(", ") { bound[it]!! }
        }
        // Shorthand where the local is already the pin's name, spelled out where it is not.
        return indices.joinToString(", ") {
            val pin = Names.pinText(outs[it].name)
            if (bound[it] == pin) pin else "${bound[it]}: $pin"
        }
    }

    // ---- declarations -------------------------------------------------------------------------------------

    private fun entry(node: Node) {
        // A MAP rather than a `when` with an `else`, and that is the whole point of it. The `else` used to
        // say "start", so an entry kind added to the catalogue and forgotten here printed back as `on
        // start` — a round trip that changes what the program does, reported by nothing. Missing from the
        // map is a crash naming the type, which is a bad afternoon instead of a bad script.
        val word = BuiltinNodes.ENTRY_WORDS[node.type]
            ?: error("no spelling for entry type '${node.type}' — add it to BuiltinNodes.ENTRY_WORDS")
        val visibility = if (node.literals[BuiltinNodes.ALWAYS_ENTRY] == true) "always " else ""
        line("${ann(node)}$visibility" + "on $word {")
        indent++
        chain(target(node.id, "Exec"), stopAt = null)
        indent--
        line("}")
        blank()
    }

    private fun function(name: String) {
        val fn = graph.function(name) ?: return
        val box = graph.entryOf(name) ?: return

        // A default is part of the signature, so it prints back into it — dropping one would turn a
        // callable function into one that reports a missing argument at every existing call site.
        // An extension's receiver is params[0] and its TYPE was written before the name, so the type does
        // not repeat in the list — but the parameter itself is written, as a bare `self`. Its absence is
        // what makes a function type-level, so a printer that dropped it would turn `fn Vec2.lengthSq(self)`
        // into `fn Vec2.lengthSq()`, which is a different function: one on the type rather than on a value.
        val params = fn.params.joinToString(", ") {
            if (it === fn.self) GraphFunction.SELF
            else "${it.name}: ${it.type}" +
                (it.default?.let { d -> " = " + (functionSlotText(d, it.type) ?: literal(d, it.type)) } ?: "")
        }
        val results = when {
            fn.results.isEmpty() -> ""
            // A mutating extension's `@self` is IMPLICIT — the author declared no results, and printing
            // the one the lowering added would emit a signature the parser reads back as a different
            // function. The body's `self = …` is what says this, and it is still there.
            dev.ziggle.vscript.model.isMutating(fn) -> ""
            fn.results.size == 1 && fn.results[0].name == Parser.RESULT_PIN -> " -> ${fn.results[0].type}"
            else -> " -> (${fn.results.joinToString(", ") { "${it.name}: ${it.type}" }})"
        }
        // The modifier goes AFTER the annotations, matching what the parser accepts: `@id(3) private fn`.
        commentsForDecl("fn:$name")
        val on = fn.receiver?.let { "$it." } ?: ""
        val head = "${ann(box)}${exportWord(name, fn.isExported)}fn $on$name($params)$results"

        // Ask the authority, not a lookalike. Whether a function is an expression is exactly
        // `isPureFunction`, and hand-rolling "are the box's exec pins wired" agreed with it only until
        // returns became real nodes — a body whose every path ends in a Return leaves the box's exec input
        // unwired, and the lookalike would have called an ordinary block-bodied function an expression,
        // which changes its purity and so what the graph MEANS.
        // `across` matters, and leaving it out was silent data loss. A body that is one call into an
        // IMPORTED function has its callee looked for among THIS document's nodes, is not found, and the
        // function is judged impure — so the expression form is refused and the block form is printed
        // instead. But a pure function has no exec chain for the block form to walk, so what came out was
        // `fn spanOf(a: INT, b: INT) -> INT { }`: the head intact and the body gone.
        //
        // The parameter exists for exactly this and every other caller already passes it; see
        // ImportScope.pureAcrossImport.
        val isExpression = isPureFunction(name, catalog, graph.nodes, graph::function, graph.links) {
            scope.pureAcrossImport(catalog, it)
        }
        // ONE result, because `= …` has room for exactly one value. A pure function with several — which a
        // canvas can draw and lowering now refuses to make — printed as `= <first>` and silently lost the
        // rest, so the block form is used for those and says what the graph actually contains.
        if (isExpression && fn.results.size == 1) {
            line("$head = ${valueInto(box.id, fn.results[0].name)}")
            blank()
            return
        }
        // `fn f() = call(…)` — the short form on a function that hands nothing back, which RUNS the call
        // rather than returning it. One statement and no result, so there is nothing the `=` could hide;
        // the marker says the author wrote it that way. See Lower.function.
        if (fn.results.isEmpty() && BuiltinNodes.isBare(box, "body") &&
            bareBody("$head = ", target(box.id, "Exec"), box.id, true)
        ) {
            blank()
            return
        }
        line("$head {")
        indent++
        val prevBox = currentBox
        currentBox = box.id
        // NOT `stopAt = box.id`. Control arriving back at the box IS the return, and [statement] prints it
        // as one — stopping the chain there instead silently dropped every `return` in the body, and the
        // body of a function that returns nothing is a fixed point only by accident.
        chain(target(box.id, "Exec"), stopAt = null)
        currentBox = prevBox
        indent--
        line("}")
        blank()
    }

    // ---- statements ---------------------------------------------------------------------------------------

    /** Whatever was written above the construct that became [nodeId], at the current indent. */
    private fun commentsFor(nodeId: Int) = emit(comments.nodes[nodeId].orEmpty())

    /** Whatever was written above the declaration called [name]. */
    private fun commentsForDecl(name: String) = emit(comments.decls[name].orEmpty())

    /**
     * A comment at the current indent, keeping a block comment's star column.
     *
     * Re-indented rather than reproduced verbatim, because the whole point of formatting is that the
     * construct may now sit at a different depth. The continuation lines of a `/** … */` are aligned by a
     * single space so the stars line up under the opening slash — the shape every doc comment in these
     * repos already has, and the one the IDE's own doc-comment support expects.
     */
    private fun emit(blocks: List<String>) {
        for (c in blocks) {
            val ls = c.split("\n")
            for ((n, l) in ls.withIndex()) {
                val t = l.trim()
                line(if (n > 0 && t.startsWith("*")) " $t" else t)
            }
        }
    }

    /**
     * A list or record literal on one line, or across several when one line would be too long.
     *
     * **A canonical form has to be readable or nobody will keep it.** Six obstacles joined with commas is
     * a six-hundred-character line that no reviewer can diff and no author would have written; the same
     * six one per line is the shape people actually type. So the printer wraps rather than always
     * flattening, and the threshold is what decides — a short record stays inline, where breaking it would
     * be the noisier answer.
     *
     * The trailing comma is deliberate: it is what makes adding a seventh obstacle a one-line diff.
     */
    private fun wrap(parts: List<String>, open: String, close: String, padFlat: Boolean = false): String {
        if (parts.isEmpty()) return "$open$close"
        val flat = if (padFlat) parts.joinToString(", ", "$open ", " $close")
        else parts.joinToString(", ", open, close)
        // Already-multi-line parts force the break too: a record holding a wrapped list cannot sit inline
        // however short its own text is, or the closing brace lands after somebody else's last line.
        if (flat.length <= WRAP_AT && parts.none { it.contains('\n') }) return flat
        val inner = INDENT.repeat(indent + 1)
        val outer = INDENT.repeat(indent)
        return parts.joinToString(",\n$inner", "$open\n$inner", ",\n$outer$close")
    }

    /** Emit the chain beginning at [from], stopping when it reaches [stopAt] or runs out. */
    /**
     * Write a body WITHOUT braces, when that is both asked for and safe — `if x foo()`.
     *
     * Two conditions, and the second is not paranoia. The marker says what was WRITTEN; the graph may have
     * changed since, because the canvas can add a step to a body the text spelled bare. Printing two
     * statements where one can go produces text that reparses as something else entirely — the second
     * statement would land outside the `if` — so the marker is honoured only when the body is still exactly
     * one plain statement.
     *
     * "Plain" excludes the forms that carry a body of their own. `if x if y { … }` is legal and reads
     * appallingly, and more to the point the inner one spans lines, so the compact form buys nothing.
     *
     * @return true when it was written bare; false means the caller should print braces as usual.
     */
    private fun canBeBare(from: Int?, join: Int?, wanted: Boolean): Boolean {
        if (!wanted) return false
        val node = from?.let { graph.node(it) } ?: return false
        if (node.type in BODY_FORMS) return false
        // Exactly one statement: either it ends the path (a `return`, a `break`), or what follows it is
        // already the point every path reconverges on.
        if (node.type in TERMINAL_FORMS) return true
        val next = target(node.id, "Exec")
        // Null counts as "nothing follows it". A loop body is the reason: its last statement is left
        // dangling rather than wired back, because a While's label and its loop top are the same address
        // and an explicit back edge would only duplicate the jump the compiler already emits. So the body
        // of `while c foo()` has no successor at all, not one equal to the join.
        return next == null || next == join
    }

    /** Write [head] and then the one statement at [from], all on the line [head] starts. */
    private fun emitBare(head: String, from: Int, join: Int?, pad: Boolean = true) {
        if (pad) pad()
        sb.append(head)
        skipPad = true
        graph.node(from)?.let { statement(it, join) }
    }

    /** [canBeBare] and [emitBare] together, for the callers with nothing else to decide. */
    private fun bareBody(head: String, from: Int?, join: Int?, wanted: Boolean): Boolean {
        if (!canBeBare(from, join, wanted)) return false
        emitBare(head, from!!, join)
        return true
    }

    private fun chain(from: Int?, stopAt: Int?) {
        var cur = from
        val seen = HashSet<Int>()
        while (cur != null && cur != stopAt) {
            if (!seen.add(cur)) break   // a back edge into a loop we are already inside
            val node = graph.node(cur) ?: break
            // Control arriving somewhere already written. Structured text has no way to say it, so before
            // references this printed a second copy of the whole tail — `kill-collect` doubled from 70 nodes
            // to 141. `$18` says it in one token.
            cur = statement(node, stopAt)
        }
    }

    /**
     * The container the surrounding text already implies, so `@in` can say only what it does not.
     *
     * Only a function box can be implied now. Comment boxes are a CANVAS construct and have no spelling in
     * text at all — they are not printed, not parsed, and not lowered. A script's comments live in the
     * document that was authored, and the two documents do not trade them.
     */
    private fun impliedContainer(): Int? = currentBox

    /** Emit one statement; returns the next node in the chain, or null when the chain ends here. */
    private fun statement(node: Node, stopAt: Int?): Int? {
        // Written inside its reader, so it contributes no statement of its own — just move on.
        if (node.id in inlined) return target(node.id, "Exec")
        commentsFor(node.id)
        val was = statementNode
        statementNode = node.id
        try {
            return statementBody(node, stopAt)
        } finally {
            statementNode = was
        }
    }

    private fun statementBody(node: Node, stopAt: Int?): Int? {
        val a = ann(node)
        return when (node.type) {
            BuiltinNodes.BRANCH -> branch(node, stopAt, a)
            BuiltinNodes.IF_SOME -> ifSome(node, stopAt, a)
            BuiltinNodes.WHILE -> {
                val some = whileVal(node)
                if (some != null) {
                    val name = holdNames.getValue(some.id)
                    line("${a}while val $name = ${valueInto(some.id, BuiltinNodes.IF_SOME_OPTION)} {")
                    indent++
                    chain(target(some.id, BuiltinNodes.IF_SOME_THEN), stopAt = node.id)
                    indent--
                    line("}")
                    return target(node.id, "Completed")
                }
                // The loop's own id is where the body rejoins, so it doubles as the "one statement" test.
                val head = "${a}while ${valueInto(node.id, "Condition")} "
                val bare = BuiltinNodes.isBare(node, "body")
                if (!bareBody(head, target(node.id, "Body"), node.id, bare)) {
                    line("$head{")
                    indent++; chain(target(node.id, "Body"), stopAt = node.id); indent--
                    line("}")
                }
                target(node.id, "Completed")
            }
            // The map loop reads exactly like the list one — same word, same binder — because it IS the
            // same idea: the two names each pass gives you, with the container saying what they are.
            BuiltinNodes.MAP_FOR_EACH -> {
                val (k, v) = loopNames.getValue(node.id)
                val binder = if (v == null) k else "($k, $v)"
                val head = "${a}for $binder in ${valueInto(node.id, BuiltinNodes.MAP_PIN)} "
                val bare = BuiltinNodes.isBare(node, "body")
                if (!bareBody(head, target(node.id, "Body"), node.id, bare)) {
                    line("$head{")
                    indent++; chain(target(node.id, "Body"), stopAt = node.id); indent--
                    line("}")
                }
                target(node.id, "Completed")
            }
            BuiltinNodes.FOR_EACH -> {
                val (el, ix) = loopNames.getValue(node.id)
                val binder = if (ix == null) el else "($el, $ix)"
                val head = "${a}for $binder in ${valueInto(node.id, "List")} "
                val bare = BuiltinNodes.isBare(node, "body")
                if (!bareBody(head, target(node.id, "Body"), node.id, bare)) {
                    line("$head{")
                    indent++; chain(target(node.id, "Body"), stopAt = node.id); indent--
                    line("}")
                }
                target(node.id, "Completed")
            }
            BuiltinNodes.WHEN -> whenNode(node, stopAt, a)
            BuiltinNodes.SEQUENCE -> {
                // The ARMS, not `execOutputs` — Completed is an exec output too, and taking it for a
                // fifth arm would print the continuation as one more block.
                val arms = BuiltinNodes.SEQUENCE_ARMS.filter { target(node.id, it) != null }
                val done = target(node.id, BuiltinNodes.SEQUENCE_DONE)
                line("${a}sequence {")
                indent++; chain(target(node.id, arms[0]), stopAt = done); indent--
                for (pin in arms.drop(1)) {
                    line("} {")
                    indent++; chain(target(node.id, pin), stopAt = done); indent--
                }
                line("}")
                // Where the statement after the `sequence` picks up. Returning null here is what used to
                // drop it — it was never printed, because the arms had swallowed it.
                done
            }
            // Terminal: they end the path, so there is no next node to return.
            BuiltinNodes.BREAK -> { line("${a}break"); null }
            BuiltinNodes.CONTINUE -> { line("${a}continue"); null }
            BuiltinNodes.HOLD -> {
                // The `self` seed of a mutating extension is machinery, not a statement: `self` is a
                // parameter and a parameter is read-only, so the lowering makes a mutable local of it.
                // Printing `var self = self` would emit a line nobody typed — and one the parser would
                // refuse, since `self` is not a name a `var` may take.
                if (node.literals[BuiltinNodes.HOLD_NAME] == GraphFunction.SELF) return target(node.id, "Exec")
                // `var` when something assigns to it later, `let` otherwise — the same distinction the
                // author wrote, read back off the node rather than guessed at.
                val kw = if (node.literals.containsKey(BuiltinNodes.MUTABLE)) "var" else "val"
                // The declared type, when the author wrote one — it changes what the binding MEANS, so
                // dropping it would print back a document that no longer says the same thing.
                val declared = (node.literals[BuiltinNodes.HOLD_TYPE] as? String)
                    ?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                line("$a$kw ${holdNames.getValue(node.id)}$declared = ${valueInto(node.id, "Value")}")
                target(node.id, "Exec")
            }
            BuiltinNodes.LOCAL_SET -> {
                line("$a" + (fieldAssignText(node) ?: plainAssignText(node)))
                target(node.id, "Exec")
            }
            BuiltinNodes.VAR_SET -> {
                // An initialiser prologue Set was written as the variable's default and has already been
                // printed up there; emitting it again would read as a statement nobody typed.
                if (!node.literals.containsKey(GraphMarks.INIT_MARK)) {
                    line("$a" + (fieldAssignText(node) ?: plainAssignText(node)))
                }
                target(node.id, "Exec")
            }
            BuiltinNodes.RETURN -> {
                // Inside a body its pins are the function's results, so print every one that is supplied.
                val vs = resolved(node).dataInputs
                    .filter { fed(node, it.name) && it.name != GraphFunction.SELF_RESULT }
                line("${a}return" + if (vs.isEmpty()) "" else " " + vs.joinToString(", ") { valueInto(node.id, it.name) })
                null
            }
            // `try { … } catch e { … }` — two arms that both continue, so it prints like an `if`/`else`
            // and rejoins the same way.
            BuiltinNodes.TRY -> {
                val body = target(node.id, BuiltinNodes.TRY_BODY)
                val caught = target(node.id, BuiltinNodes.TRY_CATCH)
                val join = rejoin(body, caught, stopAt)
                line("${a}try {")
                indent++; chain(body, stopAt = join); indent--
                line("} catch ${holdNames.getValue(node.id)} {")
                indent++; chain(caught, stopAt = join); indent--
                line("}")
                join
            }
            // `error(…)` as a statement. Its own node, and the one thing to get right is that it prints
            // as the SAME word its pure twin does — the author wrote one `error`, not two.
            BuiltinNodes.FAIL_STEP -> {
                line("${a}${names.textName(BuiltinNodes.FAIL)}(message: ${valueInto(node.id, "Message")})")
                // No Exec out: nothing follows, as with a `return`.
                null
            }
            BuiltinNodes.FUNCTION -> {
                // Control back at the box is the return, and its inputs are the results.
                val fn = graph.function(node.function.orEmpty())
                // `@self` is the implicit write-back, not something the author returned.
                val vs = fn?.results.orEmpty()
                    .filter { fed(node, it.name) && it.name != GraphFunction.SELF_RESULT }
                // **Nothing to return is nothing to write.** Control arriving at the box is how a body
                // ENDS, not a statement anybody typed — so with no results fed there is no line to emit.
                // An author who really did write a bare `return` made a `flow.return` node, which is the
                // branch above and still prints one; that is what keeps the two spellings distinct.
                //
                // This was a special case for mutating extensions, whose implicit `@self` made the bare
                // `return` show up on every one of them. The rule was always more general: a void function
                // — most of the corpus — had the same line added to it on every round trip.
                if (vs.isEmpty()) return null
                line("${a}return " + vs.joinToString(", ") { valueInto(node.id, it.name) })
                null
            }
            else -> {
                val bound = boundOf(node.id)
                when {
                    bound.isEmpty() -> line(a + callText(node))
                    // `let x = f()` binds the node's FIRST output, so it is only the same statement when
                    // that is the pin that was bound. One name for any other pin reads back as pin zero —
                    // the name survives and the wire moves, which is the failure this whole rule is about.
                    bindsFirstOnly(node.id) -> line("${a}val ${bound[0]} = ${callText(node)}")
                    else -> line("${a}val (${bindingText(node.id)}) = ${callText(node)}")
                }
                target(node.id, "Exec")
            }
        }
    }

    /**
     * `if` / `else` / `else if`.
     *
     * The arms are printed up to where they rejoin. Finding that node is the whole job: a graph has no `if`,
     * only a Branch whose outputs eventually meet, and "eventually meet" is the first node reachable from
     * both. An `else if` is recognised by the False arm leading to a Branch that nothing else reaches.
     */
    /**
     * `when` — the node back as one statement.
     *
     * The subject is printed only when there IS one, which is the same question the compiler asks and asked
     * the same way: is anything feeding the pin. That is what keeps the two forms from needing a flag that
     * could disagree with the wiring.
     *
     * **Arms are always braced**, even the one-statement ones an author is likely to have written as
     * `Phase.Chop -> chop()`. The parser accepts both spellings and they lower to the SAME graph, so the
     * printer has to pick one — the §6.7 rule — and the braced form is the one that is always right, since an
     * arm containing an `if` cannot be written on the arrow line. Making the compact form survive would need
     * a per-arm marker, the way `@wroteField` makes `s.f = v` survive; it is worth doing and it is not free,
     * so it is left out rather than half-done.
     */
    private fun whenNode(node: Node, stopAt: Int?, a: String): Int? {
        val n = BuiltinNodes.whenCount(node)
        val arms = (1..n).map { target(node.id, BuiltinNodes.whenThen(it)) }
        val elseTo = target(node.id, BuiltinNodes.WHEN_ELSE)
        val join = rejoinAll(arms + elseTo, stopAt)

        val hasSubject = graph.linkInto(node.id, BuiltinNodes.WHEN_SUBJECT) != null ||
            node.literals.containsKey(BuiltinNodes.WHEN_SUBJECT)
        val subject = if (hasSubject) " " + valueInto(node.id, BuiltinNodes.WHEN_SUBJECT) else ""

        line("${a}when$subject {")
        indent++
        for (i in 1..n) {
            val case = valueInto(node.id, BuiltinNodes.whenCase(i))
            if (bareBody("$case -> ", arms[i - 1], join, BuiltinNodes.isBare(node, i.toString()))) continue
            line("$case -> {")
            indent++; chain(arms[i - 1], stopAt = join); indent--
            line("}")
        }
        // No `else` written means the pin goes straight to the continuation, so it IS the join and there is
        // nothing to print — the same test the `if` printer makes for its False arm.
        if (elseTo != null && elseTo != join) {
            if (!bareBody("else -> ", elseTo, join, BuiltinNodes.isBare(node, "else"))) {
                line("else -> {")
                indent++; chain(elseTo, stopAt = join); indent--
                line("}")
            }
        }
        indent--
        line("}")
        return join
    }

    /**
     * `if val t = … { … } else { … }`
     *
     * Simpler than [branch] because there is no `&&` chain to reconstruct: an `if val` binds one thing, so
     * the node it prints from is always the only node involved. The bound NAME is the one that was typed —
     * carried on the node as configuration, exactly as a Hold carries its own.
     */
    private fun ifSome(node: Node, stopAt: Int?, a: String, inline: Boolean = false): Int? {
        val some = target(node.id, BuiltinNodes.IF_SOME_THEN)
        val none = target(node.id, BuiltinNodes.IF_SOME_ELSE)
        val join = rejoin(some, none, stopAt)
        // The same table every reader of the bound pin looks in, so the two cannot disagree about the name.
        val name = holdNames.getValue(node.id)
        val head = "if val $name = ${valueInto(node.id, BuiltinNodes.IF_SOME_OPTION)} "

        val bareThen = canBeBare(some, join, BuiltinNodes.isBare(node, "then"))
        if (bareThen) {
            if (inline) emitBare(head, some!!, join, pad = false) else emitBare("$a$head", some!!, join)
        } else {
            val open = "$a$head{"
            if (inline) { sb.append(open); newline() } else line(open)
            indent++; chain(some, stopAt = join); indent--
        }

        val elseHead = if (bareThen) "else " else "} else "
        if (none != null && none != join) {
            // The same `else if` recognizer [branch] has, from this side — so `if val … else if val …` and
            // `if val … else if …` both stay one statement rather than nesting a block per arm.
            val elseNode = graph.node(none)
            if (elseNode != null && reachedOnlyFrom(none, node.id)) {
                if (elseNode.type == BuiltinNodes.IF_SOME) {
                    pad(); sb.append(elseHead)
                    return ifSome(elseNode, stopAt, "", inline = true) ?: join
                }
                if (elseNode.type == BuiltinNodes.BRANCH) {
                    pad(); sb.append(elseHead)
                    return branch(elseNode, stopAt, "", inline = true) ?: join
                }
            }
            if (canBeBare(none, join, BuiltinNodes.isBare(node, "else"))) {
                emitBare(elseHead, none, join)
                return join
            }
            line("$elseHead{")
            indent++; chain(none, stopAt = join); indent--
            line("}")
            return join
        }
        if (!bareThen) line("}")
        return join
    }

    private fun branch(node: Node, stopAt: Int?, a: String, inline: Boolean = false): Int? {
        // **Short-circuit `&&` written as control flow.**
        //
        //     #14 branch True->17 False->8
        //     #17 branch True->18 False->8      (17 reached only from 14's True)
        //
        // is `if c14 && c17 { … } else { <8> }`. Exactly, not approximately: the inner condition is
        // evaluated only when the outer one holds, which is what `&&` means, so this is faithful even when
        // evaluating it has side effects.
        //
        // Without collapsing, the two False arms are two separate paths to the same node and the printer
        // writes everything after it TWICE — `kill-collect` went from 70 nodes to 141 and from 205
        // instructions to 304. `rejoin` cannot see it because [walk] follows a Branch's single
        // *continuation*, and a Branch whose arms never reconverge has none, so node 8 never enters the
        // reachable set of the arm that gets to it through #17.
        val conds = ArrayList<Int>().apply { add(node.id) }
        val f = target(node.id, "False")
        var last = node
        while (true) {
            val inner = target(last.id, "True")?.let { graph.node(it) } ?: break
            if (inner.type != BuiltinNodes.BRANCH || !reachedOnlyFrom(inner.id, last.id)) break
            if (target(inner.id, "False") != f) break
            conds += inner.id
            last = inner
        }

        val t = target(last.id, "True")
        val join = rejoin(t, f, stopAt)

        val condition = conds.joinToString(" && ") { valueInto(it, "Condition", PREC_AND) }

        // Braces only where they were written — see [BuiltinNodes.BARE]. The marker rides on the OUTERMOST
        // branch, which is `node`: an `a && b` chain is several Branch nodes and only the first bears the
        // statement's own spelling.
        val bareThen = canBeBare(t, join, BuiltinNodes.isBare(node, "then"))
        // `inline` continues a line the caller already began with `} else ` — an `else if` is one statement
        // in the text and a chain of Branches in the graph, and the two have to look like each other.
        if (bareThen) {
            if (inline) emitBare("if $condition ", t!!, join, pad = false)
            else emitBare("${a}if $condition ", t!!, join)
        } else {
            val head = "${a}if $condition {"
            if (inline) { sb.append(head); newline() } else line(head)
            indent++; chain(t, stopAt = join); indent--
        }

        // What opens the `else`: a brace to close first, unless the `then` never opened one.
        val elseHead = if (bareThen) "else " else "} else "

        if (f != null && f != join) {
            val elseNode = graph.node(f)
            // Reached only from THESE branches — every one that was collapsed above, since after collapsing
            // they all send their False here. Asking about the outermost alone reported "no" the moment a
            // collapse happened, and an `else if` chain silently became a nested block.
            if (elseNode != null && reachedOnlyFrom(f, conds)) {
                if (elseNode.type == BuiltinNodes.BRANCH) {
                    pad(); sb.append(elseHead)
                    return branch(elseNode, stopAt, "", inline = true) ?: join
                }
                // `else if val y = …`. Without this the `if val` had no recognizer in the else position and
                // came back as `else { if val y = … }` — correct, and one level of indentation deeper every
                // time, so a chain of three drifted right across the page.
                if (elseNode.type == BuiltinNodes.IF_SOME) {
                    pad(); sb.append(elseHead)
                    return ifSome(elseNode, stopAt, "", inline = true) ?: join
                }
            }
            if (canBeBare(f, join, BuiltinNodes.isBare(node, "else"))) {
                emitBare(elseHead, f, join)
                return join
            }
            line("$elseHead{")
            indent++; chain(f, stopAt = join); indent--
            line("}")
            return join
        }
        if (!bareThen) line("}")
        return join
    }

    /**
     * Is [pin] supplied at all — by a wire OR by a value typed into it?
     *
     * Asking only about links is the mistake that made `return "one"` print as a bare `return`: a literal
     * result is written into the node's own literals, not wired, so there is no link to find. Both prints
     * then agreed on the bare form and the fixed point held while the returned value quietly disappeared —
     * which is exactly why the round trip is checked for behaviour as well as for text.
     */
    private fun fed(node: Node, pin: String): Boolean =
        graph.linkInto(node.id, pin) != null || node.literals.containsKey(pin)

    /** The first node reachable from both arms — where an `if` stops being an `if`. */
    /**
     * Where SEVERAL paths reconverge — [rejoin] for a `when`, which has an arm per case.
     *
     * The first node reachable from every one of them, in the first path's order, so the answer is the
     * EARLIEST common point rather than any of them. Unwired arms contribute nothing: an empty arm goes
     * straight to the continuation, so counting it would make the continuation itself the join and print the
     * rest of the block inside the `when`.
     */
    private fun rejoinAll(paths: List<Int?>, stopAt: Int?): Int? {
        val sets = paths.filterNotNull().map { p -> LinkedHashSet<Int>().also { walk(p, it, stopAt) } }
        if (sets.isEmpty()) return null
        return sets[0].firstOrNull { c -> sets.all { c in it } }
    }

    private fun rejoin(a: Int?, b: Int?, stopAt: Int?): Int? {
        if (a == null || b == null) return null
        val fromA = LinkedHashSet<Int>().also { walk(a, it, stopAt) }
        val fromB = HashSet<Int>().also { walk(b, it, stopAt) }
        return fromA.firstOrNull { it in fromB }
    }

    private fun walk(from: Int, into: MutableSet<Int>, stopAt: Int?) {
        var cur: Int? = from
        while (true) {
            val id = cur ?: return
            if (id == stopAt || !into.add(id)) return
            val node = graph.node(id) ?: return
            // Follow the single onward pin. A Branch has two, so where IT continues is its own rejoin —
            // the same question one level down. `into` alone does NOT make that terminate: [rejoin] starts
            // fresh sets, so the guard has to be [resolving], which spans the hop.
            cur = when (node.type) {
                BuiltinNodes.BRANCH ->
                    if (!resolving.add(id)) null
                    else try { rejoin(target(id, "True"), target(id, "False"), stopAt) } finally { resolving.remove(id) }
                BuiltinNodes.WHILE, BuiltinNodes.FOR_EACH, BuiltinNodes.MAP_FOR_EACH ->
                    target(id, "Completed")
                else -> resolved(node).execOutputs.firstOrNull()?.let { target(id, it.name) }
            }
        }
    }

    /** Is [nodeId] reached by exec only from [only]? What tells an `else if` from a shared tail. */
    /**
     * Is this WHILE the shape `while val n = … { … }` desugars to?
     *
     * A loop whose condition is the literal `true`, whose body is ONE `if let` reached only from it, and
     * whose empty case is exactly a `break`. Answers the `if let` node when it is, so the caller can print
     * the one word; null otherwise, and the loop prints as an ordinary `while`.
     *
     * Deliberately strict about "one statement" and "exactly a break": a body with anything after the
     * `if let`, or an else arm that does more than leave, is a different program and has to print as what
     * it is. A hand-written loop that happens to match reads back as `while val`, which is correct — it IS
     * that loop, and the round trip is about meaning rather than about who typed it.
     */
    private fun whileVal(node: Node): Node? {
        if (node.literals["Condition"] != true || graph.linkInto(node.id, "Condition") != null) return null
        if (target(node.id, "Check") != null) return null
        val bodyId = target(node.id, "Body") ?: return null
        val some = graph.node(bodyId)?.takeIf { it.type == BuiltinNodes.IF_SOME } ?: return null
        if (!reachedOnlyFrom(bodyId, node.id)) return null
        // The empty case leaves, and leaves immediately.
        val none = target(some.id, BuiltinNodes.IF_SOME_ELSE) ?: return null
        if (graph.node(none)?.type != BuiltinNodes.BREAK) return null
        // ...and nothing follows the `if let` inside the body: the SOME arm loops straight back.
        if (target(none, "Exec") != null) return null
        return some
    }

    /**
     * How a stored qualified name is WRITTEN here.
     *
     * The graph always holds `alias::name`, including for a name that came in through `import { … }` or as
     * a default — that is what keeps the compiler, the validator and the document format ignorant of
     * unqualified imports. This is the other half: if the alias belongs to an unqualified import, the name
     * is written bare, under whatever local spelling that import gave it.
     *
     * A recognizer, in the sense every piece of sugar here needs one. Without it `import { abs } from "m"`
     * would come back as `@1::abs` — a name nobody can type.
     */
    private fun written(qualified: String): String {
        val q = QualName.parse(qualified)
        val alias = q.module ?: return qualified
        val imp = graph.imports.firstOrNull { it.alias == alias } ?: return qualified
        if (!imp.isUnqualified) return qualified
        // A named item may have renamed it, and a DEFAULT always does: the importer chose the local name
        // and the graph stores the other document's. Reading only `named` printed the callee's own name,
        // which is a name this file does not have.
        imp.named.firstOrNull { it.name == q.name }?.let { return it.local }
        val target = graph.import(alias)?.let { i -> source.load(i) }
        if (imp.default != null && target?.defaultExport == q.name) return imp.default
        return q.name
    }

    private fun reachedOnlyFrom(nodeId: Int, only: Int): Boolean = reachedOnlyFrom(nodeId, listOf(only))

    private fun reachedOnlyFrom(nodeId: Int, only: Collection<Int>): Boolean =
        graph.links.none { l ->
            l.toNode == nodeId && l.fromNode !in only &&
                graph.node(l.fromNode)?.let { resolved(it).output(l.fromPin)?.type?.isExec } == true
        }

    // ---- expressions ----------------------------------------------------------------------------------------

    /** The text of whatever feeds [node]'s input [pin] — a wire followed back, or the literal typed in. */
    /**
     * `course.laps = course.laps + 1`, when that is how it was written.
     *
     * Null unless the set carries [BuiltinNodes.WROTE_FIELD], so a graph that never saw text — a Set Field
     * wired on the canvas — prints as `with` instead. That is the honest default: nobody wrote an
     * assignment there, and inventing one would put words in an author's mouth on every reopen.
     *
     * The set's value is a `struct.set`, possibly nested: `a.b.c = v` lowered to
     * `a = a with { b: a.b with { c: v } }`, so the field path is recovered by walking IN through each
     * replacement and the final value is whatever the innermost one replaces with.
     */
    private fun fieldAssignText(node: Node): String? = assignParts(node)?.let { it.text() }

    /**
     * What a Set assigns TO, and where the value it assigns comes from.
     *
     * One walk serving both spellings. A plain `n = v` takes the value straight off the Set's own pin; a
     * field rebind has to descend through the nest of `struct.set`s the desugaring built to find the value
     * at the bottom and the dotted path on the way down. Both then face the same question — was this
     * written as a compound assignment — so both go through [text].
     */
    private inner class Assign(
        /** What is written to the left of the `=`: a name, or a dotted path. */
        val target: String,
        /** The Set itself — where a compound mark lives, however deep the path went. */
        val setId: Int,
        /** Where the assigned value hangs: the Set's own Value pin, or the innermost replacement's field. */
        val node: Int,
        val pin: String,
    ) {
        /** `n = v`, or `n += v` when the mark says so and the graph still agrees with it. */
        fun text(): String = compound() ?: "$target = ${valueInto(node, pin)}"

        /**
         * `n += 1`, when that is how it was written AND the graph still says so.
         *
         * Both halves are load-bearing. The mark alone is not enough — a canvas edit can rewire the value of
         * a Set that once held `n + 1` into something that no longer mentions `n`, and printing `+=` for
         * that would silently change what the statement does. So the shape is re-derived: the value must be
         * the marked operator, applied to something that PRINTS as the assignment target.
         *
         * Comparing the printed text rather than walking the graph a second time is deliberate. The target
         * may be `n` or `s.a.b`, and printing is already the one canonical rendering of a subgraph — two
         * expressions that print alike are the same expression, which is exactly the question being asked.
         */
        private fun compound(): String? {
            val op = graph.node(setId)?.literals?.get(BuiltinNodes.ASSIGN_OP)?.toString() ?: return null
            val binary = graph.linkInto(node, pin)?.fromNode ?: return null
            if (graph.node(binary)?.type != op) return null
            if (valueInto(binary, "A") != target) return null
            val symbol = BINARY_TEXT[op]?.first ?: return null
            return "$target $symbol= ${valueInto(binary, "B")}"
        }
    }

    private fun assignParts(node: Node): Assign? {
        if (node.literals[BuiltinNodes.WROTE_FIELD] != true) return null
        var current = graph.linkInto(node.id, "Value")?.fromNode ?: return null
        val path = ArrayList<String>()
        while (true) {
            val n = graph.node(current) ?: return null
            if (n.type != BuiltinNodes.STRUCT_SET) return null
            val field = n.literals[BuiltinNodes.STRUCT_FIELD]?.toString() ?: return null
            path += Names.pinText(field)
            val inner = graph.linkInto(current, field)?.fromNode
            val innerNode = inner?.let { graph.node(it) }
            // Keep descending only while the replacement is ITSELF a replacement — that is the nesting the
            // desugaring produces. Anything else is the value being assigned.
            if (innerNode == null || innerNode.type != BuiltinNodes.STRUCT_SET) {
                return Assign("${node.variable}." + path.joinToString("."), node.id, current, field)
            }
            current = inner
        }
    }

    /** A plain `n = v` Set, which is the same question with no path to walk. */
    private fun plainAssignText(node: Node): String {
        // `xs.add(value: 1)` was written, not `xs = xs.add(value: 1)`. The two make the SAME graph — the
        // write-back is the whole mechanism — so only this marker can say which the author typed. The
        // right-hand side already prints in method spelling; all that is dropped is the `xs = `.
        if (node.literals.containsKey(BuiltinNodes.WROTE_RECEIVER)) {
            return valueInto(node.id, "Value")
        }
        return Assign(node.variable.orEmpty(), node.id, node.id, "Value").text()
    }

    /** Is this pin fed by the Call lowering makes for a field's computed default, and nothing else? */
    private fun filledByDefault(node: Node, type: String, pin: String): Boolean {
        val want = BuiltinNodes.fieldDefaultName(QualName.parse(type).name, pin)
        val from = graph.links.singleOrNull { it.toNode == node.id && it.toPin == pin } ?: return false
        val callee = graph.node(from.fromNode)?.takeIf { it.type == BuiltinNodes.CALL }?.callee ?: return false
        return QualName.parse(callee).name == want
    }

    /**
     * `= six() * 2` — a field's computed default, read back off the function it became.
     *
     * The declaration is where it was written, so that is where it has to print; the function is an
     * implementation detail no author asked for. Its body is one expression by construction, so the same
     * reader an expression-bodied function uses answers it.
     */
    private fun fieldDefaultText(type: String, field: String): String? {
        val name = BuiltinNodes.fieldDefaultName(type, field)
        val box = graph.entryOf(name) ?: return null
        return valueInto(box.id, Parser.RESULT_PIN)
    }

    private fun valueInto(node: Int, pin: String, outerPrec: Int = 0): String {
        val link = graph.linkInto(node, pin)
        if (link == null) {
            val d = graph.node(node)?.let { resolved(it) }
            val spec = d?.input(pin)
            val v = if (graph.node(node)?.literals?.containsKey(pin) == true) graph.node(node)?.literals?.get(pin) else spec?.default
            // The EFFECTIVE type, not the pin's declared one. A Hold declares both its Value pins WILDCARD
            // so one node can hold anything, so a tile typed straight into one — `let spot = tile(…)`, which
            // stores the text `"3200,3210,1"` and no wire — printed back as a quoted string and stopped
            // being a tile. Nothing fed it, which is why this branch runs at all, so the null `feeding`
            // below is the whole truth rather than a shortcut: the answer comes from what the node itself
            // records. Falls back to the declared type exactly as before when there is nothing better.
            val n = graph.node(node)
            val eff = if (n != null && spec != null) {
                effectivePinType(n, spec, { name -> graph.variables.firstOrNull { it.name == name }?.type })
            } else {
                null
            }
            // A WILDCARD pin says nothing, and for a tile or a colour the stored value says nothing either
            // — both are ordinary text and numbers underneath. The lowering recorded what the SOURCE said,
            // which is the only place the two were ever distinguishable, so an inline `tile(1, 2, 0)` comes
            // back as itself rather than as the string `"1,2,0"`. See [BuiltinNodes.pinInferred].
            val type = (eff ?: spec?.type)?.takeIf { !it.isWildcard }
                ?: (n?.literals?.get(BuiltinNodes.pinInferred(pin)) as? String)?.let { TypeRef.parse(it) }
                ?: (eff ?: spec?.type)
            return literal(v, type)
        }
        return value(link.fromNode, link.fromPin, outerPrec)
    }

    /** The text of a value produced by [nodeId]'s output [pin]. */
    private fun value(nodeId: Int, pin: String, outerPrec: Int = 0): String {
        val node = graph.node(nodeId) ?: return "null"
        // Before the cycle guard, because a `?.` legitimately re-enters its own node: the access under the
        // guard reads the receiver off the very node that is printing it. That is not a cycle — the `It`
        // pin prints as nothing and asks for nothing — but the guard keys on the node id and cannot tell.
        if (node.type == BuiltinNodes.IF_PRESENT && pin == BuiltinNodes.IF_PRESENT_IT) return ""
        if (!printing.add(nodeId)) return "/* cycle at node $nodeId */"
        try {
            return valueOf(node, nodeId, pin, outerPrec)
        } finally {
            printing.remove(nodeId)
        }
    }

    private fun valueOf(node: Node, nodeId: Int, pin: String, outerPrec: Int): String {
        // The receiver under a `?.`, which is spelled by the guard that binds it rather than here. Empty
        // rather than a name: `a?` + `.b` is the text, so the placeholder contributes nothing of its own.
        if (node.type == BuiltinNodes.IF_PRESENT && pin == BuiltinNodes.IF_PRESENT_IT) return ""
        holdNames[nodeId]?.let { return it }
        if (node.type == BuiltinNodes.CAST) {
            val named = node.literals[BuiltinNodes.CAST_OF]?.toString().orEmpty()
            val renames = BuiltinNodes.castRenames(node)
            // A quoted rename is a JSON KEY and a bare one is a field of the source record. Which was
            // written is metadata the lowering kept — see [BuiltinNodes.CAST_QUOTED] — because it cannot
            // be derived: `item_count` is a legal bare name, so quoting only what *needs* quotes would
            // rewrite the author's spelling and lose the character-identical round trip.
            val clause = if (renames.isEmpty()) ""
            else " { " + renames.entries.joinToString(", ") {
                val from = if (BuiltinNodes.castKeyQuoted(node, it.key)) quote(it.value) else it.value
                "${it.key}: $from"
            } + " }"
            return "${valueInto(nodeId, "Value")} as $named$clause"
        }
        if (node.type == BuiltinNodes.IS_TYPE) {
            // The flag, not a wrapping Not — so `x !is T` and `!(x is T)` each print as they were
            // written rather than collapsing onto one spelling.
            val word = if (node.literals[BuiltinNodes.IS_NOT] == true) "!is" else "is"
            val named = node.literals[BuiltinNodes.IS_OF]?.toString().orEmpty()
            return "${valueInto(nodeId, "Value")} $word $named"
        }
        constNames[nodeId]?.let { return it }
        // An imported const prints as the qualified name it was written as, never as the value it folded
        // to — which is the whole reason the fold is allowed to exist. See [GraphMarks.IMPORTED_CONST].
        importedConsts[nodeId]?.let { return it }
        boundNames[nodeId to pin]?.let { return it }
        when (node.type) {
            // A parameter. The box is the boundary seen from inside, so the body READS its outputs — and
            // what it reads is the parameter's name. Falling through to callText here printed the box as a
            // call, whose inputs are fed by the body, which reads the box again: the recursion had no
            // bottom because a function's two halves genuinely point at each other.
            BuiltinNodes.FUNCTION -> return pin
            // A function reference is spelled as the function's own name, with no call — which is exactly
            // what the literal holds, so raising it back out of the graph is a lookup. Unless the name is
            // one no author could have typed, in which case it was written inline: see [lambdaText].
            BuiltinNodes.FUNCTION_REF -> {
                val named = node.literals[BuiltinNodes.FUNCTION_REF_NAME]?.toString().orEmpty()
                return if (BuiltinNodes.isAnonymous(named)) lambdaText(node) else named
            }
            BuiltinNodes.VAR_GET -> return node.variable.orEmpty()
            BuiltinNodes.FOR_EACH -> {
                val (el, ix) = loopNames.getValue(nodeId)
                return if (pin == "Index") ix ?: el else el
            }
            BuiltinNodes.MAP_FOR_EACH -> {
                val (k, v) = loopNames.getValue(nodeId)
                return if (pin == BuiltinNodes.MAP_VALUE_PIN) v ?: k else k
            }
            in BuiltinNodes.LITERALS -> return literal(node.literals["Value"], resolved(node).output("Value")?.type)
            BuiltinNodes.LITERAL_LIST -> {
                val shape = BuiltinNodes.SHAPE_PINS.getValue(BuiltinNodes.LITERAL_LIST)
                val items = resolved(node).dataInputs.filter { it.name !in shape }
                    .map { valueInto(nodeId, it.name) }
                return wrap(items, "[", "]")
            }
            BuiltinNodes.SELECT -> return select(node, outerPrec)
            // `a?.b`. The access under the guard is written against the `It` pin, and a read of that pin
            // prints as NOTHING (see [valueOf]) — so printing the receiver, a `?`, and then the access
            // subtree glues `a?` to `.b` and gives back what was typed, whatever kind of access it is.
            BuiltinNodes.IF_PRESENT -> return valueInto(nodeId, BuiltinNodes.IF_PRESENT_VALUE, PREC_POSTFIX) +
                "?" + valueInto(nodeId, BuiltinNodes.IF_PRESENT_THEN, PREC_POSTFIX)
            // Right-associative, so the fallback prints at the SAME level and `a ?: b ?: c` needs no
            // parentheses; the value is one tighter, which is what puts them back around `(a ?: b) ?: c`.
            BuiltinNodes.OR_ELSE -> return paren(
                outerPrec, PREC_ELVIS,
                valueInto(nodeId, BuiltinNodes.OR_ELSE_VALUE, PREC_ELVIS + 1) + " ?: " +
                    valueInto(nodeId, BuiltinNodes.OR_ELSE_FALLBACK, PREC_ELVIS),
            )
            BuiltinNodes.NOT -> return paren(outerPrec, PREC_UNARY, "!" + valueInto(nodeId, "A", PREC_UNARY))
            BuiltinNodes.LIST_AT -> return "${valueInto(nodeId, "List", PREC_POSTFIX)}[${valueInto(nodeId, "Index")}]"
            BuiltinNodes.STRUCT_MAKE -> {
                val t = node.literals[BuiltinNodes.STRUCT_OF]?.toString().orEmpty()
                // Only the fields actually SUPPLIED. A field pin now carries its declared default,
                // so printing every pin gave back `Point { x: 0 }` for a literal written `Point { }`
                // — a round trip that adds words the author did not write, and hides the fact that
                // the default is what is answering.
                val fields = resolved(node).dataInputs
                    .filter { it.name != BuiltinNodes.STRUCT_OF && fed(node, it.name) }
                    // ...and not a field filled by its own COMPUTED default. Lowering wires those from a
                    // call at each construction site, which is what "evaluated per construction" means —
                    // but the author wrote nothing there, so writing it back would put a name nobody can
                    // type into the file. Same rule as the literal default one line up, different
                    // mechanism. See BuiltinNodes.FIELD_DEFAULT.
                    .filterNot { filledByDefault(node, t, it.name) }
                // `Vec2 { x }` where the value is already a name spelled like the field, `x: …` where it is
                // not — the exact rule `bindingText` applies on the destructuring side, and canonical for
                // the same reason: it is a property of the graph, so the same wires always print the same
                // way and no marker is needed to remember which was typed. An author who wrote `x: x` gets
                // `x` back, as one who wrote `let { x: x }` already does.
                val parts = fields.map {
                    val name = Names.pinText(it.name)
                    val value = valueInto(nodeId, it.name)
                    if (value == name) name else "$name: $value"
                }
                return "$t " + wrap(parts, "{", "}", padFlat = true)
            }
            // `Phase.Chop`. Both halves come off the node's own pins, so there is no marker to consult and
            // nothing to guess: the spelling is recoverable because the node records which enum and which
            // member. Written verbatim rather than through `Names.pinText`, which lower-cases a pin name for
            // text — a member is a declared NAME, and `Phase.chop` would be a different spelling of it.
            BuiltinNodes.ENUM_OF -> {
                val t = node.literals[BuiltinNodes.ENUM_TYPE]?.toString().orEmpty()
                val member = node.literals[BuiltinNodes.ENUM_MEMBER]?.toString().orEmpty()
                return "$t.$member"
            }
            // `Phase.values()`. The enum's name is on the node, and there is nothing else to write — which
            // is what makes this recoverable without a marker, exactly as Choice above is.
            // Transparent: a narrowing has no spelling, so it prints as the value it narrows. See NARROW.
            BuiltinNodes.NARROW -> return valueInto(nodeId, "Value", outerPrec)
            BuiltinNodes.ENUM_VALUES ->
                return "${node.literals[BuiltinNodes.ENUM_TYPE]?.toString().orEmpty()}.values()"
            // Both read one named thing off a value, and both print the same way. The pin BEING READ is the
            // field, which is what makes this need no marker: the node records which, on itself.
            BuiltinNodes.STRUCT_GET, BuiltinNodes.ENUM_FIELD ->
                return "${valueInto(nodeId, "Value", PREC_POSTFIX)}.${Names.pinText(pin)}"
            BuiltinNodes.STRUCT_SET -> {
                val field = node.literals[BuiltinNodes.STRUCT_FIELD]?.toString().orEmpty()
                return "${valueInto(nodeId, "Value", PREC_POSTFIX)} with { ${Names.pinText(field)}: ${valueInto(nodeId, field)} }"
            }
        }
        BINARY_TEXT[node.type]?.let { (op, prec) ->
            val text = "${valueInto(nodeId, "A", prec)} $op ${valueInto(nodeId, "B", prec + 1)}"
            return paren(outerPrec, prec, text)
        }
        // Which output pin is being read.
        //
        // A bare call means the node's FIRST data output — that is what `Lower.call` returns — so anything
        // else has to name itself, and this is the exact inverse of that rule rather than an approximation
        // of it. It used to be asked only of IMPURE nodes, which is why every real script broke: the nodes
        // that actually have several outputs are the pure query ones. `Entity Info` has six. A graph reading
        // its `Exists` printed as `entityInfo(…)`, read back as `entityInfo(…).id`, and turned a condition
        // into an int — a different program that still parsed, still lowered, and was caught only because
        // the validator will not wire INT into BOOL.
        val outs = resolved(node).dataOutputs
        return if (outs.size > 1 && outs.first().name != pin) {
            "${callText(node)}.${Names.pinText(pin)}"
        } else {
            callText(node)
        }
    }

    /** `&&`, `||` and the ternary are all one node; which it is depends on the arms. */
    private fun select(node: Node, outerPrec: Int): String {
        val falseLit = graph.linkInto(node.id, "If False") == null && node.literals["If False"] == false
        val trueLit = graph.linkInto(node.id, "If True") == null && node.literals["If True"] == true
        return when {
            falseLit -> paren(
                outerPrec, PREC_AND,
                "${valueInto(node.id, "Condition", PREC_AND)} && ${valueInto(node.id, "If True", PREC_AND + 1)}",
            )
            trueLit -> paren(
                outerPrec, PREC_OR,
                "${valueInto(node.id, "Condition", PREC_OR)} || ${valueInto(node.id, "If False", PREC_OR + 1)}",
            )
            else -> paren(
                outerPrec, PREC_TERNARY,
                "${valueInto(node.id, "Condition", PREC_TERNARY + 1)} ? " +
                    "${valueInto(node.id, "If True")} : ${valueInto(node.id, "If False")}",
            )
        }
    }

    private fun callText(node: Node): String {
        val d = resolved(node)
        val name = if (node.type == BuiltinNodes.CALL) node.callee.orEmpty() else names.textName(node.type)
        val shape = BuiltinNodes.SHAPE_PINS[node.type].orEmpty()

        // `invoke(handler, rumor)` — POSITIONAL, because a function value carries the shape of its
        // parameters and not their names, so `Arg1:` would be a label the author never chose and could not
        // improve. The function comes first because it is what is being called.
        if (node.type == BuiltinNodes.INVOKE) {
            val args = d.dataInputs.filter { it.name != BuiltinNodes.INVOKE_FN }
                .map { valueInto(node.id, it.name) }
            val f = valueInto(node.id, BuiltinNodes.INVOKE_FN)
            // `f(x)` when a NAME holds the function, `invoke(expr, x)` otherwise — and the marker is what
            // says which was typed where both would read. Unmarked, the shorter form wins wherever it can
            // be written at all, which is what a node drawn on a canvas should print as.
            // "Can be written" is decided on the printed FORM rather than on the node behind it,
            // because that is the actual question: only a dotted name may sit in front of `(`.
            // `m.run` may; `handlerFor(k: 0)` may not, and `handlerFor(k: 0)(3)` is a spelling the
            // parser does not accept.
            val named = f.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)?(\\.[A-Za-z0-9_]+)*"))
            if (node.literals[BuiltinNodes.INVOKE_WRITTEN] != true && named) {
                return "$f(" + args.joinToString(", ") + ")"
            }
            return "$name(" + (listOf(f) + args).joinToString(", ") + ")"
        }
        if (node.type == BuiltinNodes.FORMAT) {
            val tpl = literal(node.literals["Template"], TypeRef(PinType.STRING))
            val holes = d.dataInputs.filter { it.name != "Template" }
            return "$name($tpl" + holes.joinToString("") { ", ${Names.pinText(it.name)}: ${valueInto(node.id, it.name)}" } + ")"
        }
        // An EXTENSION is written on its receiver and only there, so `self` leaves the argument list and
        // becomes what the call hangs off. The declaration is what says so — nothing about the node
        // distinguishes it, which is exactly why the receiver is recorded on the function.
        val fn = if (node.type == BuiltinNodes.CALL) scope.function(name) else null
        // Dot form UNLESS the short name is ambiguous. `xs.add(v)` has nowhere to put an alias, so when two
        // imports both extend with `add` the dot form no longer reads back as this call — the reader would
        // refuse it. The qualified bare call is the escape hatch, and the printer takes it in exactly the
        // case a reader would need it, so the choice needs no marker: the same graph always prints the same
        // way, and it is the import set that decides.
        val short = QualName.parse(name).name
        val ambiguous = fn?.isExtension == true &&
            scope.visibleExtensions().count { it.second.name == short } > 1
        val self = if (fn?.self != null && !ambiguous) GraphFunction.SELF else null
        // A type-level function hangs off its TYPE — `Vec2.new(5, 3)` — because there is no receiver value
        // to hang off. The name is read from the declaration, which is the only place it exists: nothing
        // about the Call node distinguishes this from an ordinary call, exactly as with `self`.
        val receiver = when {
            self != null -> valueInto(node.id, self, PREC_POSTFIX) + "."
            fn?.isTypeLevel == true && !ambiguous -> "${fn.receiver}."
            else -> ""
        }
        val dotted = self != null || fn?.isTypeLevel == true && !ambiguous


        // A lambda goes after the call — UNLESS it was written in the argument list, which is the one
        // thing this cannot work out for itself: both spellings build the same graph. `Lower` records
        // which pins were written there ([BuiltinNodes.INLINE_ARGS]) precisely so that the trailing form
        // is not silently substituted for what the author typed. Everything else is found the way `Lower`
        // binds a trailing lambda: the LAST function-typed input, fed by something written inline.
        val inlineArgs = BuiltinNodes.inlineArgsOf(node)
        val lambdaPin = d.dataInputs
            .lastOrNull { it.type.isFunction && it.name !in shape && it.name != self && it.name !in inlineArgs }
            ?.takeIf { pin ->
                graph.linkInto(node.id, pin.name)
                    ?.let { graph.node(it.fromNode) }
                    ?.takeIf { it.type == BuiltinNodes.FUNCTION_REF }
                    ?.let { BuiltinNodes.isAnonymous(it.literals[BuiltinNodes.FUNCTION_REF_NAME]?.toString()) } == true
            }

        // Named arguments for everything that is fed, in pin order. Positional would be shorter and would
        // stop being readable the moment a node has six pins and four are defaults.
        val args = d.dataInputs.filter { it.name !in shape && it.name != self && it.name != lambdaPin?.name }
            .filter { graph.linkInto(node.id, it.name) != null || node.literals.containsKey(it.name) }
            .joinToString(", ") { "${Names.pinText(it.name)}: ${valueInto(node.id, it.name)}" }
        // In dot form the alias is gone — `21.double()`, not `21.core::double()`. That is what makes the
        // form worth having, and it reads back because an unambiguous name needs no qualifier to find it.
        // `written` and not `name`: a callee reached through `import *` is stored qualified and has to
        // go back bare, or the file comes out naming an alias nobody typed.
        val head = "$receiver${if (dotted) short else written(name)}"
        if (lambdaPin == null) return "$head($args)"
        // `xs.filter { … }` — the parens go when the lambda is the only argument, as they do in Kotlin
        // and as the parser accepts. They stay when anything else was passed.
        val lambda = valueInto(node.id, lambdaPin.name)
        return if (args.isEmpty() && dotted) "$head $lambda" else "$head($args) $lambda"
    }

    /**
     * `{ it * 2 }` — a reference to a synthesised function, written back as the lambda it was.
     *
     * The whole of it is a name test: only [Lower] makes a function whose name begins with `@`, and no
     * author can type one, so a reference to one was written inline. Nothing is stored to say so.
     *
     * The captured parameters are dropped from the list — they are wires on this node, not names the
     * author wrote — and `it` is dropped when it is the only one, because that is the spelling that
     * produced it. The body is the expression feeding the box's result, printed exactly as any other
     * expression is; a read of a parameter is a read of the box, which already prints as its pin name.
     */
    /**
     * A value stored for a FUNCTION-typed slot, written back as it was typed.
     *
     * A handler column, a column default and a parameter default all hold a function's NAME. Quoting it
     * would print a string where a name was written; and a SYNTHESISED name — `@lambda1`, from a lambda
     * folded into the declaration — is not a name anybody can type, so it is raised back into the lambda
     * it came from. Null when the slot is not function-typed, so callers fall back to [literal].
     */
    private fun functionSlotText(v: Any?, type: TypeRef?): String? =
        if (type?.isFunction == true && v is String) {
            if (BuiltinNodes.isAnonymous(v)) lambdaText(v, 0) else v
        } else {
            null
        }

    private fun lambdaText(ref: Node): String = lambdaText(
        ref.literals[BuiltinNodes.FUNCTION_REF_NAME]?.toString().orEmpty(),
        BuiltinNodes.capturesOf(ref).size,
    )

    /**
     * The same, from a NAME alone — for a lambda that was folded into a declaration.
     *
     * **A folded one has no reference node to read captures off, and needs none.** It was written into an
     * enum's table or a default, where there are no enclosing locals to capture, so the count is zero and
     * every parameter in the signature is one the author wrote or `it`. See `Lower.foldLambda`.
     */
    private fun lambdaText(name: String, captures: Int): String {
        val box = graph.entryOf(name) ?: return "{ }"
        val fn = scope.function(name)
        val params = fn?.params.orEmpty().let { if (captures == 0) it else it.dropLast(captures) }
        val head = when {
            params.size == 1 && params[0].name == LambdaExpr.IT -> ""
            // No parameters, no arrow — `{ f() }`. The arity is read back off the pin exactly as it was
            // read off it going in, so there is nothing here to write down; an arrow would not even parse.
            params.isEmpty() -> ""
            else -> params.joinToString(", ") { it.name } + " -> "
        }
        // The do-nothing body — `{ }`, exactly as it was written. Nothing WIRED and nothing TYPED IN: a
        // literal body rides on the pin rather than arriving down a link, so a link-only test reads `{ 5 }`
        // as empty. Asking `valueInto` here instead would print the pin's default, which is `null` — a
        // value that is not in the graph and that nobody wrote.
        val fed = graph.linkInto(box.id, Parser.RESULT_PIN) != null ||
            graph.node(box.id)?.literals?.containsKey(Parser.RESULT_PIN) == true
        if (!fed) {
            // **An ACTING body is on the box's exec chain, not behind its Result.** A `fn(T)` lambda has
            // no result to read back through, so reproducing it means printing what the box RUNS. Without
            // this `xs.each { register(it) }` came back as `xs.each { }` — the body silently dropped,
            // which is the worst way for a round trip to fail.
            val ran = target(box.id, "Exec")?.let { graph.node(it) }
            if (ran != null) return "{ $head${callText(ran)} }"
            return if (head.isEmpty()) "{ }" else "{ $head}"
        }
        return "{ $head${valueInto(box.id, Parser.RESULT_PIN)} }"
    }

    // ---- bits ----------------------------------------------------------------------------------------------

    /**
     * [v] as the RECORD it stands for, when [type] is a host record with a stored form — else null.
     *
     * Null both when the type has no reader and when the reader declines the value, which is one answer
     * on purpose: either way there is nothing better to print than what is already in hand.
     */
    /** How [type] says a value of it is written, when it says — see [dev.ziggle.vscript.model.HostRecord.write]. */
    private fun hostWritten(type: TypeRef, v: Any?): String? =
        dev.ziggle.vscript.model.HostRecords.of(type)?.write?.invoke(v)

    private fun hostStorage(type: TypeRef, v: Any?): dev.ziggle.vscript.vm.StructValue? =
        dev.ziggle.vscript.model.HostRecords.of(type)?.read?.invoke(v) as? dev.ziggle.vscript.vm.StructValue

    /**
     * A stored value, written the way the language reads it back.
     *
     * **Takes the whole [TypeRef], not just its [PinType].** It used to take the pin type, which was
     * enough while every type with a written form was a builtin. A tile is a host record now, so the
     * builtin is null and the pin type says nothing — dropping the type here printed a tile's stored
     * `"3200,3200,0"` as a quoted STRING, which the parser then refused in a Tile pin. The record knows
     * how to read its own storage; this asks it.
     */
    private fun literal(v: Any?, type: TypeRef?): String = when {
        v == null -> "null"
        // A type that says how its own values are WRITTEN — a colour as `0xAARRGGBB`. First, because it
        // is the most specific answer available and the only one the language could not have derived.
        type != null && hostWritten(type, v) != null -> hostWritten(type, v)!!
        // A host record's stored form — `"3200,3200,0"` for a tile — printed as the record literal it
        // means. Only when it is not ALREADY a record: a folded value takes the branch below, which is
        // the same spelling reached from the other direction.
        v !is dev.ziggle.vscript.vm.StructValue && type != null && hostStorage(type, v) != null ->
            literal(hostStorage(type, v), type)
        // **Hex, not `#AARRGGBB`.** That spelling is gone: a colour is a record now, and the only reason
        // the `#` form existed was that a COLOR pin held a packed int. The packed int is still what the
        // pin holds, so printing it as hex round-trips exactly — and `0x…` is an INT literal that the
        // graph's own `accepts` has always allowed in a COLOR pin.
        v is String -> quote(v)
        v is Boolean -> v.toString()
        v is Float || v is Double -> v.toString()
        v is List<*> -> v.joinToString(", ", "[", "]") { literal(it, null) }
        // A record that was folded to a VALUE rather than built by a node — an enum's column. Written back
        // as the literal it was, which is what lets a table carrying records round-trip at all. The field
        // types are not carried into the recursion, the same limitation the list branch above has: a
        // nested COLOR prints as its number.
        // A record the DOMAIN gave a positional constructor prints through it — `tile(3200, 3200, 0)`
        // rather than `Tile { x: 3200, y: 3200, plane: 0 }`. Both read back as the same value; only one
        // is what the author wrote, and rewriting a corpus into the other on its first round trip is
        // churn. See [Names.constructorOf].
        v is dev.ziggle.vscript.vm.StructValue && names.constructorOf(v.type) != null ->
            names.textName(names.constructorOf(v.type)!!.type) +
                v.names.indices.joinToString(", ", "(", ")") { literal(v[it], null) }
        v is dev.ziggle.vscript.vm.StructValue ->
            "${v.type} { " + v.names.mapIndexed { i, n -> "${Names.pinText(n)}: ${literal(v[i], null)}" }
                .joinToString(", ") + " }"
        else -> v.toString()
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\t' -> append("\\t"); '\r' -> append("\\r")
            else -> append(c)
        }
        append('"')
    }

    /**
     * The author's own metadata, written back out.
     *
     * **No ids and no positions.** Nobody writes either by hand, so nothing prints either: they are
     * implementation details of a node graph, and this language is meant to be readable and writable as
     * text. What survives is what a person actually wrote — `@note`, and anything the graph invented, which
     * is carried through untouched because preserving something you do not interpret cannot be got wrong.
     *
     * Fires once per node. One node can legitimately be written more than once — a chain reached from both
     * arms of an `if`, or a function box written as both its `fn` header and the `return` control arrives at
     * — and saying the same thing twice about it is at best noise.
     */
    private fun ann(node: Node): String {
        if (!annotated.add(node.id)) return ""
        val parts = ArrayList<String>()
        for ((k, v) in node.literals) {
            if (!k.startsWith("@") || k in GraphMarks.SYNTACTIC_LITERALS) continue
            val name = k.removePrefix("@")
            parts += if (v == null) "@$name" else "@$name(${literal(v, null)})"
        }
        return if (parts.isEmpty()) "" else parts.joinToString(" ") + " "
    }

    /**
     * `export ` / `export default ` / nothing, for the declaration called [name].
     *
     * The default is a property of the DOCUMENT — one name on the graph — so it is read here rather than
     * stored on each declaration, where it could name a second one.
     */
    private fun exportWord(name: String, isExported: Boolean): String = when {
        graph.defaultExport == name -> "export default "
        // Written at the bottom instead — see Graph.exportList. Saying it here too would say it twice.
        name in graph.exportList -> ""
        isExported -> "export "
        else -> ""
    }

    private fun resolved(node: Node): NodeDescriptor {
        val base = catalog[node.type] ?: error("unknown node type '${node.type}'")
        return resolveNode(node, base, scope::function, { scope.visibleTypes() }, pure, { scope.visibleEnums() })
    }

    private fun target(nodeId: Int, pin: String): Int? = graph.execTarget(nodeId, pin)

    private fun paren(outer: Int, own: Int, text: String) = if (own < outer) "($text)" else text

    /**
     * Skip the padding on the NEXT line written, so it continues one already begun.
     *
     * How a braceless body is printed: the head (`if x `, `Phase.Chop -> `) is appended without a newline,
     * this is set, and the body's single statement is then written by the ordinary statement printer with no
     * indent of its own. Doing it this way rather than by capturing and splicing the buffer is what keeps
     * [lineNumbers] honest — a statement is still recorded on the line it is actually written on, which is
     * what breakpoints and node↔line navigation read.
     */
    private var skipPad = false

    private fun pad() {
        if (skipPad) { skipPad = false; return }
        repeat(indent) { sb.append(INDENT) }
    }

    private fun line(text: String) {
        pad(); sb.append(text)
        // The line this statement occupies, claimed by whichever node is being written. FIRST line only:
        // an `if` spans its whole body, and the one worth pointing at is the one bearing the condition.
        statementNode?.let { lineNumbers.putIfAbsent(it, lineCount) }
        newline()
    }

    /**
     * The ONE place a newline is written, so the line count cannot drift from the text.
     *
     * It did: `blank()` and the inline `} else if` both appended their own, so every statement after the
     * first blank line was reported one line early — the mapping pointed at `on start {` and claimed it was
     * a `delay`. Counting in three places is how an off-by-one becomes an off-by-however-many.
     */
    private fun newline() { sb.append('\n'); lineCount++ }
    private fun blank() { if (sb.isNotEmpty() && !sb.endsWith("\n\n")) newline() }

    private companion object {

        const val INDENT = "    "

        /**
         * How long a one-line literal may be before it is broken across lines.
         *
         * Not a hard line limit — an expression can still exceed it on its own. It is the point at which a
         * comma-joined literal stops being readable, which is what this is really about.
         */
        const val WRAP_AT = 96

        /**
         * Node types [statement] prints with a syntax of their own.
         *
         * The complement of the `else ->` branch, and the set that may NOT be given a `let` binding: each of
         * these either binds its own outputs (a loop) or produces none (a branch, an assignment, a return).
         */
        val STATEMENT_FORMS: Set<String> = setOf(
            BuiltinNodes.BRANCH, BuiltinNodes.IF_SOME, BuiltinNodes.WHILE, BuiltinNodes.FOR_EACH,
            BuiltinNodes.MAP_FOR_EACH,
            BuiltinNodes.SEQUENCE, BuiltinNodes.WHEN,
            BuiltinNodes.HOLD, BuiltinNodes.VAR_SET, BuiltinNodes.RETURN, BuiltinNodes.FUNCTION,
            BuiltinNodes.COMMENT, BuiltinNodes.REROUTE, BuiltinNodes.BREAK, BuiltinNodes.CONTINUE,
        ) + BuiltinNodes.ENTRY_WORDS.keys

        /**
         * Statements that END their path, so nothing can follow them in a body.
         *
         * Which makes one of them a complete body on its own — the case [bareBody]'s "is this exactly one
         * statement" test would otherwise get wrong, since there is no next node to compare against the
         * join.
         */
        val TERMINAL_FORMS: Set<String> =
            setOf(BuiltinNodes.RETURN, BuiltinNodes.BREAK, BuiltinNodes.CONTINUE)

        /**
         * Statements that carry a BODY of their own, and so can never be written without braces.
         *
         * Deliberately not [STATEMENT_FORMS], which was the first thing reached for and is a different
         * question — that set is "has a syntax of its own", and it contains `var.set` and `value.hold`.
         * Those are exactly the one-liners worth writing bare (`while c Count = Count + 1`), so using it
         * here silently refused the compact form for every assignment.
         */
        val BODY_FORMS: Set<String> = setOf(
            BuiltinNodes.BRANCH, BuiltinNodes.IF_SOME, BuiltinNodes.WHILE, BuiltinNodes.FOR_EACH,
            BuiltinNodes.MAP_FOR_EACH,
            BuiltinNodes.SEQUENCE, BuiltinNodes.WHEN, BuiltinNodes.FUNCTION, BuiltinNodes.COMMENT,
        ) + BuiltinNodes.ENTRY_WORDS.keys

        const val PREC_TERNARY = 1
        const val PREC_OR = 2
        const val PREC_AND = 3
        const val PREC_CMP = 4

        /**
         * `?:` — tighter than a comparison, looser than arithmetic. Kotlin's placement, so
         * `a ?: b + 1` is `a ?: (b + 1)` and `a ?: b == c` is `(a ?: b) == c`.
         */
        const val PREC_ELVIS = 5
        const val PREC_ADD = 6
        const val PREC_MUL = 7
        const val PREC_UNARY = 8
        const val PREC_POSTFIX = 9

        /** Operator text and precedence, the mirror of `Lower.BINARY`. */
        val BINARY_TEXT: Map<String, Pair<String, Int>> = mapOf(
            BuiltinNodes.ADD to ("+" to PREC_ADD),
            BuiltinNodes.SUB to ("-" to PREC_ADD),
            BuiltinNodes.MUL to ("*" to PREC_MUL),
            BuiltinNodes.DIV to ("/" to PREC_MUL),
            BuiltinNodes.MOD to ("%" to PREC_MUL),
            BuiltinNodes.EQ to ("==" to PREC_CMP),
            BuiltinNodes.NE to ("!=" to PREC_CMP),
            BuiltinNodes.LT to ("<" to PREC_CMP),
            BuiltinNodes.LE to ("<=" to PREC_CMP),
            BuiltinNodes.GT to (">" to PREC_CMP),
            BuiltinNodes.GE to (">=" to PREC_CMP),
        )
    }
}
