package dev.ziggle.vscript.runtime

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.EnumType
import dev.ziggle.vscript.model.FunctionPin
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphFunction
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.model.StructType
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.Templates
import java.io.File

/**
 * The editor's mutable working copy of a [Graph].
 *
 * [Graph] itself is immutable — it is what the compiler and the document format consume, and keeping it
 * that way means a compile can never race a half-applied edit. The editor needs add/remove/drag, so it
 * works on this and produces a fresh [Graph] via [toGraph] whenever it saves or compiles.
 *
 * Ids are handed out monotonically and never reused within a session, because imgui-node-editor caches
 * per-id state (position, selection) — reusing the id of a just-deleted node would resurrect its state on
 * the replacement.
 */
class EditorDoc(graph: Graph, var file: File? = null) {
    var id: String = graph.id.ifEmpty { java.util.UUID.randomUUID().toString() }
    var name: String = graph.name
    val nodes = graph.nodes.toMutableList()
    val links = graph.links.toMutableList()
    val variables = graph.variables.toMutableList()
    val functions = graph.functions.toMutableList()
    val types = graph.types.toMutableList()

    /**
     * The record types a Make / Break / Get / Set node may name in THIS document: what it declares plus the
     * host's data records — the same answer `ImportScope.visibleTypes` gives the validator and the compiler,
     * so a record the canvas offers is one the program can build. A declared type shadows a host one of the
     * same name. Imported types are not listed here; the canvas has no resolver, and a library's own records
     * are reached through its functions.
     */
    fun visibleTypes(): List<dev.ziggle.vscript.model.StructType> {
        val own = types.map { it.name.lowercase() }.toSet()
        return types + dev.ziggle.vscript.model.HostRecords.dataStructs().filter { it.name.lowercase() !in own }
    }
    val enums = graph.enums.toMutableList()
    val imports = graph.imports.toMutableList()

    /** True when there are unsaved edits — drives the title's dot and the save prompt. */
    var dirty: Boolean = false
        private set

    private var nextNodeId = (nodes.maxOfOrNull { it.id } ?: 0) + 1
    private var nextLinkId = (links.maxOfOrNull { it.id } ?: 0) + 1

    val history = History()

    fun markDirty() { dirty = true }

    fun toGraph(): Graph = Graph(
        id, name, nodes.toList(), links.toList(),
        variables.toList(), functions.toList(), types.toList(), enums.toList(), imports.toList(),
    )

    /**
     * Import [ref] under [alias], replacing any existing import of that alias.
     *
     * Replacing rather than refusing: an alias names one document, and re-pointing it at another is the
     * ordinary way to swap a library. The resolved id is left null here — [DocumentSource] answers by ref
     * as readily as by id, and lowering fills the id in the moment the reference resolves.
     */
    fun addImport(alias: String, ref: String) = edit("import") {
        imports.removeAll { it.alias == alias }
        imports += dev.ziggle.vscript.model.GraphImport(alias, ref)
    }

    /**
     * Drop the import declared under [alias].
     *
     * Nodes that named it are left in place, exactly as [removeVariable] leaves an unpointed Get: the
     * wiring is worth keeping while the author re-points it, and the validator already reports a call
     * through an alias nothing is imported as.
     */
    fun removeImport(alias: String) = edit("remove import") {
        imports.removeAll { it.alias == alias }
    }

    fun node(nodeId: Int): Node? = nodes.firstOrNull { it.id == nodeId }

    /**
     * Depth of nested [edit] calls. Only the outermost records — see [edit].
     */
    private var editDepth = 0

    /**
     * Run [block] as one undoable edit.
     *
     * Every mutating path goes through here, which is what makes undo total rather than best-effort —
     * including edits made implicitly, like a link being replaced by connecting over it.
     *
     * **Nested edits collapse into the outermost one.** Bulk operations are built from single ones —
     * deleting a selection is a loop over [removeNode] — and without this each element became its own undo
     * step, so clearing four nodes took four Ctrl+Z presses to put back. Composing at the call site
     * (`edit("delete") { ids.forEach(::removeNode) }`) now does the right thing automatically, for every
     * bulk path present and future, rather than each one needing its own no-history variant.
     */
    fun <T> edit(label: String, block: () -> T): T {
        if (editDepth > 0) return block()
        val before = GraphDoc.toJson(toGraph())
        editDepth++
        try {
            return block()
        } finally {
            editDepth--
            // Recorded AFTER the fact, and only when the document actually moved. An action that changes
            // nothing — arranging an already-tidy graph, deleting an empty selection, re-committing a
            // comment with the same text — used to push an undo step all the same, so pressing it three
            // times cost three Ctrl+Z presses that each appeared to do nothing at all.
            //
            // In a `finally` so a block that throws part-way still leaves a way back to before it ran.
            if (GraphDoc.toJson(toGraph()) != before) {
                history.record(label, before)
                markDirty()
            }
        }
    }

    /**
     * Replace the reroute knots and the wires through them, wholesale.
     *
     * One call rather than add/remove/relink, because half of it is never a state the document should be
     * in: between removing a knot and reconnecting its wire the graph is genuinely broken, and anything
     * that observed it there — a validate, a redraw, an autosave — would see a wire hanging off nothing.
     *
     * Called only by Arrange, which owns every knot. See [WireTracks].
     */
    fun replaceReroutes(newNodes: List<Node>, newLinks: List<Link>) {
        val keptIds = newNodes.map { it.id }.toSet()
        nodes.removeAll { it.type == BuiltinNodes.REROUTE && it.id !in keptIds }
        for (n in newNodes) if (nodes.none { it.id == n.id }) nodes += n
        links.clear()
        links += newLinks
        nextNodeId = maxOf(nextNodeId, (nodes.maxOfOrNull { it.id } ?: 0) + 1)
        nextLinkId = maxOf(nextLinkId, (links.maxOfOrNull { it.id } ?: 0) + 1)
        markDirty()
    }

    /** Ids for whatever the formatter is about to create. */
    fun takeNodeId(): Int = nextNodeId++
    fun takeLinkId(): Int = nextLinkId++

    fun addNode(type: String, x: Float, y: Float, variable: String? = null): Node = edit(History.ADD_NODE) {
        val n = Node(nextNodeId++, type, x, y, variable = variable)
        nodes += n
        // A Function box that names no function is inert: it frames nothing, calls nothing, and its title
        // reads "unnamed". Declaring one HERE means every route that can make a box — the palette, a drop,
        // a paste — produces a usable function without each of them having to know that.
        if (type == BuiltinNodes.FUNCTION && n.function == null) {
            val name = freeFunctionName()
            // Exported: the canvas has no visibility control, so anything drawn here is meant to be
            // reachable. A document that wants otherwise says so in its `.vs`.
            functions += GraphFunction(name, isExported = true)
            n.function = name
        }
        n
    }

    /** Remove a node and every link touching it — a dangling link would fail validation immediately. */
    fun removeNode(nodeId: Int) {
        if (nodes.none { it.id == nodeId }) return
        edit(History.DELETE) {
            nodes.removeAll { it.id == nodeId }
            links.removeAll { it.fromNode == nodeId || it.toNode == nodeId }
            // Anything filed under it is now filed under nothing. Left pointing at a node that no longer
            // exists, those would match no container and quietly stop moving with anything at all.
            nodes.filter { it.group == nodeId }.forEach { it.group = null }
        }
    }

    /**
     * Wire [fromPin] to [toPin]. [toType] is the TARGET pin's type, and it decides whether the wire
     * replaces what was already there.
     *
     * **A data input takes exactly one wire; an exec input takes as many as arrive.** Connecting a second
     * data wire replaces the first, rather than producing a graph the validator would reject a moment later.
     * An exec input is the opposite: several wires converging on one node is how an if/else rejoins, how a
     * loop body returns to a common tail, how any two paths share a step — the most ordinary shape in the
     * language, and the compiler has always supported it (see `GraphCompiler`'s `execLabel`, which emits a
     * re-entered node once and jumps to it).
     *
     * Applying the data rule to exec inputs deleted the earlier wire every time a second path was joined, in
     * silence. A Branch whose False path rejoined the main chain kept only whichever wire was drawn last, so
     * the other path simply stopped at the Branch — the graph looked right and did nothing on that side.
     */
    fun addLink(fromNode: Int, fromPin: String, toNode: Int, toPin: String, toType: TypeRef): Link =
        edit(History.CONNECT) {
            if (!toType.isExec) links.removeAll { it.toNode == toNode && it.toPin == toPin }
            val l = Link(nextLinkId++, fromNode, fromPin, toNode, toPin)
            links += l
            l
        }

    fun removeLink(linkId: Int) {
        if (links.none { it.id == linkId }) return
        edit(History.DISCONNECT) { links.removeAll { it.id == linkId } }
    }

    fun addVariable(v: GraphVariable) = edit("add variable") { variables += v }

    fun variable(name: String): GraphVariable? = variables.firstOrNull { it.name == name }

    // ---- user functions ----------------------------------------------------------------------------

    fun function(name: String): GraphFunction? = functions.firstOrNull { it.name == name }

    fun freeFunctionName(base: String = "Function"): String {
        if (functions.none { it.name == base }) return base
        var i = 2
        while (functions.any { it.name == "$base$i" }) i++
        return "$base$i"
    }

    /**
     * Declare a function and give it a box, an Inputs node and an Outputs node.
     *
     * All three at once because a function without them is not a thing you can do anything with — the
     * validator rejects a body with no Inputs, so creating the parts separately would mean every new
     * function started life broken.
     */
    fun addFunction(name: String, x: Float, y: Float): GraphFunction = edit("add function") {
        // The box declares its own function (see [addNode]); this only has to give it the asked-for name.
        val box = addNode(BuiltinNodes.FUNCTION, x, y).also {
            it.w = 520f
            it.h = 280f
        }
        val auto = box.function!!
        if (auto != name) renameFunction(auto, name)
        function(box.function!!)!!
    }

    /**
     * Set a comment's note — the wrapped text under its heading.
     *
     * Separate from [renameContainer] because the two halves of a comment mean different things: the heading
     * names a region, and this says why it is there. A function box has no note, so it is left alone rather
     * than growing a field its header would never show.
     */
    fun setContainerBody(nodeId: Int, text: String) {
        val n = node(nodeId) ?: return
        if (n.type == BuiltinNodes.FUNCTION) return
        edit("edit note") { n.body = text.ifBlank { null } }
    }

    /**
     * Give a container the name typed into its header.
     *
     * A comment's heading is just text. A function box's heading IS the function's name, so typing there
     * has to rename the function and every node that refers to it — writing it to [Node.comment] left the
     * box titled "fn: unnamed" while the new name sat in a field nothing reads.
     */
    fun renameContainer(nodeId: Int, text: String) {
        val n = node(nodeId) ?: return
        if (n.type != BuiltinNodes.FUNCTION) {
            edit("edit comment") { n.comment = text.ifBlank { null } }
            return
        }
        val wanted = text.trim()
        val current = n.function
        // Blank, unchanged, or already taken: keep the name it has rather than silently inventing one.
        if (wanted.isEmpty() || wanted == current) return
        if (current == null) {
            edit("name function") {
                functions += GraphFunction(wanted, isExported = true)
                n.function = wanted
            }
            return
        }
        renameFunction(current, wanted)
    }

    /** Replace a signature. Pins that vanish take their wires with them — a wire to a pin that no longer
     *  exists is a dangling link, which the validator would reject on the next keystroke. */
    fun updateFunction(name: String, params: List<FunctionPin>, results: List<FunctionPin>) {
        val i = functions.indexOfFirst { it.name == name }
        if (i < 0) return
        edit("edit function") {
            functions[i] = GraphFunction(
                name, params, results,
                isExported = functions[i].isExported,
                receiver = functions[i].receiver,
            )
            val kept = (params + results).map { it.name }.toSet() + "Exec"
            val touched = nodes.filter {
                (it.type == BuiltinNodes.CALL && it.callee == name) ||
                    (it.type == BuiltinNodes.FUNCTION && it.function == name)
            }.map { it.id }.toSet()
            links.removeAll { (it.fromNode in touched && it.fromPin !in kept) || (it.toNode in touched && it.toPin !in kept) }
        }
    }

    fun removeFunction(name: String) = edit("remove function") {
        // Anything filed under the box loses its container along with it, or it would point at a node id
        // that no longer exists and never move with anything again.
        val boxIds = nodes.filter { it.type == BuiltinNodes.FUNCTION && it.function == name }.map { it.id }.toSet()
        nodes.filter { it.group in boxIds }.forEach { it.group = null }
        functions.removeAll { it.name == name }
        // The body goes with it — an orphaned Inputs node belongs to nothing and only reports errors.
        val doomed = nodes.filter { it.function == name }.map { it.id }
        doomed.forEach { removeNode(it) }
        // Call sites are left in place but unpointed, the same as a deleted variable: the wiring around
        // them is usually worth keeping, and the validator says plainly what is wrong.
        nodes.filter { it.callee == name }.forEach { it.callee = null }
    }

    fun renameFunction(from: String, to: String): Boolean {
        val clean = to.trim()
        if (clean.isEmpty() || clean == from) return false
        if (functions.any { it.name == clean }) return false
        val i = functions.indexOfFirst { it.name == from }
        if (i < 0) return false
        edit("rename function") {
            val old = functions[i]
            functions[i] = GraphFunction(
                clean, old.params, old.results,
                isExported = old.isExported,
                receiver = old.receiver,
            )
            nodes.filter { it.function == from }.forEach { it.function = clean }
            nodes.filter { it.callee == from }.forEach { it.callee = clean }
        }
        return true
    }

    // ---- declared types ----------------------------------------------------------------------------

    /**
     * Declare a struct, or return the one already called that.
     *
     * Names are what a type IS here — a document, a pin and a saved file all refer to one by name — so a
     * second type with the same name is not a thing that can exist. Returning the existing one rather than
     * refusing keeps `add` idempotent, which is what the WS command and the outline's + button both want.
     */
    fun addStruct(name: String): StructType {
        val clean = name.trim()
        struct(clean)?.let { return it }
        val made = StructType(clean, isExported = true)
        edit("add type") { types += made }
        return made
    }

    fun freeTypeName(base: String = "Type"): String {
        if (types.none { it.name.equals(base, true) }) return base
        var i = 2
        while (types.any { it.name.equals("$base$i", true) }) i++
        return "$base$i"
    }

    fun struct(name: String?): StructType? =
        name?.let { n -> types.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

    /**
     * Replace a struct's fields.
     *
     * Wires into and out of a dropped field go with it, the same way [updateFunction] drops a call site's
     * wires when a parameter disappears — a wire to a pin that no longer exists draws nowhere and compiles
     * to nothing, so leaving it is strictly worse than removing it. One Ctrl+Z brings both back.
     */
    fun updateStruct(name: String, fields: List<FunctionPin>) {
        val i = types.indexOfFirst { it.name == name }
        if (i < 0) return
        edit("edit type") {
            types[i] = StructType(name, fields)
            val kept = fields.map { it.name }.toSet() + setOf("Value", BuiltinNodes.STRUCT_OF)
            val touched = nodes.filter { it.literals[BuiltinNodes.STRUCT_OF] == name }.map { it.id }.toSet()
            links.removeAll {
                (it.fromNode in touched && it.fromPin !in kept) || (it.toNode in touched && it.toPin !in kept)
            }
        }
    }

    /**
     * Drop a struct.
     *
     * The nodes that named it are LEFT, unpointed — the same choice [removeFunction] makes for call sites.
     * The wiring around them is usually worth keeping, and the validator says plainly what is wrong; a
     * silent cascade of deletions is much harder to undo than one error message is to read.
     */
    fun removeStruct(name: String) = edit("remove type") {
        types.removeAll { it.name == name }
    }

    fun renameStruct(from: String, to: String): Boolean {
        val clean = to.trim()
        if (clean.isEmpty() || clean == from) return false
        if (types.any { it.name.equals(clean, ignoreCase = true) }) return false
        val i = types.indexOfFirst { it.name == from }
        if (i < 0) return false
        edit("rename type") {
            types[i] = StructType(clean, types[i].fields)
            // Everything that NAMED it has to follow, or the rename silently unpoints them: the literal on
            // a Make/Break node, and any variable, parameter or result declared as one.
            nodes.filter { it.literals[BuiltinNodes.STRUCT_OF] == from }
                .forEach { it.literals[BuiltinNodes.STRUCT_OF] = clean }
            fun swap(t: TypeRef) = if (t.name == from) TypeRef.named(clean) else t
            for (v in variables.indices) {
                if (variables[v].type.name == from) {
                    variables[v] = GraphVariable(variables[v].name, TypeRef.named(clean), variables[v].default)
                }
            }
            for (f in functions.indices) {
                val fn = functions[f]
                functions[f] = GraphFunction(
                    fn.name,
                    fn.params.map { FunctionPin(it.name, swap(it.type)) },
                    fn.results.map { FunctionPin(it.name, swap(it.type)) },
                    isExported = fn.isExported,
                    receiver = fn.receiver,
                )
            }
            for (t in types.indices) {
                val old = types[t]
                // Everything ABOUT the record, not only its fields: a rename sweep that rebuilt it
                // from name and fields alone dropped its type parameters and its singleness too.
                types[t] = StructType(
                    old.name,
                    old.fields.map { FunctionPin(it.name, swap(it.type)) },
                    old.isExported,
                    old.params,
                    old.isSingle,
                )
            }
        }
        return true
    }

    // ---- enums --------------------------------------------------------------------------------------------

    fun enumType(name: String?): EnumType? =
        name?.let { n -> enums.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

    /** A name no declared type or enum has. */
    fun freeEnumName(base: String = "Enum"): String {
        fun taken(n: String) = types.any { it.name.equals(n, true) } || enums.any { it.name.equals(n, true) }
        if (!taken(base)) return base
        var i = 2
        while (taken("$base$i")) i++
        return "$base$i"
    }

    fun addEnum(name: String, members: List<String> = listOf("First", "Second")): EnumType {
        val clean = name.trim()
        enumType(clean)?.let { return it }
        val made = EnumType(clean, members, isExported = true)
        edit("add enum") { enums += made }
        return made
    }

    /** Replaces the member list. A literal that named a member no longer there is left as text for the validator to flag. */
    fun updateEnum(name: String, members: List<String>) {
        val i = enums.indexOfFirst { it.name == name }
        if (i < 0) return
        val clean = members.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        edit("edit enum") { enums[i] = EnumType(name, clean, enums[i].isExported, enums[i].fields) }
    }

    fun removeEnum(name: String) = edit("remove enum") {
        enums.removeAll { it.name == name }
    }

    fun renameEnum(from: String, to: String): Boolean {
        val clean = to.trim()
        if (clean.isEmpty() || clean == from) return false
        if (enums.any { it.name.equals(clean, ignoreCase = true) } || types.any { it.name.equals(clean, ignoreCase = true) }) return false
        val i = enums.indexOfFirst { it.name == from }
        if (i < 0) return false
        edit("rename enum") {
            val e = enums[i]
            enums[i] = EnumType(clean, e.members, e.isExported, e.fields)
            fun swap(t: TypeRef) = if (t.name == from) TypeRef.named(clean) else t
            for (v in variables.indices) {
                if (variables[v].type.name == from) {
                    variables[v] = GraphVariable(variables[v].name, TypeRef.named(clean), variables[v].default)
                }
            }
            for (f in functions.indices) {
                val fn = functions[f]
                functions[f] = GraphFunction(
                    fn.name,
                    fn.params.map { FunctionPin(it.name, swap(it.type)) },
                    fn.results.map { FunctionPin(it.name, swap(it.type)) },
                    isExported = fn.isExported,
                    receiver = fn.receiver,
                )
            }
            for (t in types.indices) {
                val old = types[t]
                types[t] = StructType(old.name, old.fields.map { FunctionPin(it.name, swap(it.type)) }, old.isExported, old.params, old.isSingle)
            }
        }
        return true
    }

    /** Put a node in a function's body, or take it out again with null. */
    fun setNodeFunction(nodeId: Int, name: String?) {
        val n = node(nodeId) ?: return
        if (n.function == name) return
        edit("move to function") { n.function = name }
    }

    /** Record which container a node sits in — see [dev.ziggle.vscript.model.Node.group]. */
    fun setNodeGroup(nodeId: Int, containerId: Int?) {
        val n = node(nodeId) ?: return
        if (n.group == containerId) return
        edit("move to group") { n.group = containerId }
    }

    /** Point a Call node at a function. */
    fun setNodeCallee(nodeId: Int, name: String?) {
        val n = node(nodeId) ?: return
        if (n.callee == name) return
        edit("set call target") { n.callee = name }
    }

    /** Fold or unfold a container. Not an [edit]: folding is a view state, not a change to the script. */
    fun setFolded(nodeId: Int, folded: Boolean) {
        val n = node(nodeId) ?: return
        if (n.folded == folded) return
        n.folded = folded
        markDirty()
    }

    /** A name nothing else is using — so "Add" always produces a usable variable rather than a clash. */
    fun freeVariableName(base: String = "var"): String {
        if (variables.none { it.name == base }) return base
        var i = 2
        while (variables.any { it.name == "$base$i" }) i++
        return "$base$i"
    }

    fun removeVariable(name: String) = edit("remove variable") {
        variables.removeAll { it.name == name }
        // Nodes that named it are left in place but unpointed: deleting them would throw away wiring the
        // author may want to re-point, and the validator already flags a Get with no variable.
        nodes.filter { it.variable == name }.forEach { it.variable = null }
    }

    /**
     * Rename a variable and every node that names it.
     *
     * Nodes hold the variable's NAME rather than a reference, which keeps the document readable and
     * diffable — the cost is exactly this: a rename is a rewrite, and forgetting it silently unbinds every
     * Get and Set in the graph.
     */
    fun renameVariable(from: String, to: String): Boolean {
        val clean = to.trim()
        if (clean.isEmpty() || clean == from) return false
        if (variables.any { it.name == clean }) return false
        val i = variables.indexOfFirst { it.name == from }
        if (i < 0) return false
        edit("rename variable") {
            val old = variables[i]
            variables[i] = GraphVariable(clean, old.type, old.default)
            nodes.filter { it.variable == from }.forEach { it.variable = clean }
        }
        return true
    }

    fun updateVariable(name: String, type: TypeRef = variable(name)?.type ?: TypeRef.WILDCARD, default: Any?) {
        val i = variables.indexOfFirst { it.name == name }
        if (i < 0) return
        if (variables[i].type == type && variables[i].default == default) return
        edit("edit variable") { variables[i] = GraphVariable(name, type, default) }
    }

    /** Point a `var.get` / `var.set` node at a different variable. */
    fun setNodeVariable(nodeId: Int, name: String?) {
        val n = node(nodeId) ?: return
        if (n.variable == name) return
        edit("set variable") { n.variable = name }
    }

    /**
     * Record a node move.
     *
     * Separate from [edit] because the canvas reports a drag as a position change *every frame*; the
     * label-based coalescing in [History] is what collapses that into one undo step.
     */
    fun recordMove() {
        if (editDepth == 0) history.record(History.MOVE, GraphDoc.toJson(toGraph()), coalesce = true)
        markDirty()
    }

    /**
     * Told about every committed literal, so a running script can be retuned without a restart.
     *
     * A callback rather than a reference to the runtime: a document is edited whether or not anything is
     * running it, and may be open in more than one place. Whoever owns both ends wires this up.
     */
    var onLiteralCommitted: ((nodeId: Int, pin: String, value: Any?) -> Unit)? = null

    /** Set an unconnected input pin's literal, as one coalesced undo step per pin. */
    fun setLiteral(nodeId: Int, pin: String, value: Any?) {
        val n = node(nodeId) ?: return
        if (n.literals[pin] == value) return
        onLiteralCommitted?.invoke(nodeId, pin, value)
        if (editDepth == 0) {
            history.record("${History.EDIT_VALUE}:$nodeId.$pin", GraphDoc.toJson(toGraph()), coalesce = true)
        }
        n.literals[pin] = value
        // Editing a template CHANGES THE NODE'S PINS, so a wire to a hole that no longer exists is now a
        // dangling link — the validator would reject the graph on the very next keystroke. Same rule as
        // reshaping a function signature: the pins that go take their wires with them.
        if (n.type == BuiltinNodes.FORMAT && pin == "Template") {
            val kept = Templates.placeholders(value?.toString().orEmpty()).toSet() + "Template"
            links.removeAll { it.toNode == n.id && it.toPin !in kept }
        }
        // Shrinking a list drops slots, and for the same reason those take their wires with them. The
        // typed-in values go too: leaving them would resurrect the old contents the moment someone widened
        // the list again, which reads as the editor remembering something the author had deleted.
        if (n.type == BuiltinNodes.LITERAL_LIST && pin == BuiltinNodes.LIST_COUNT) {
            val count = (value as? Number)?.toInt()?.coerceIn(0, BuiltinNodes.LIST_MAX) ?: 0
            val kept = (1..count).mapTo(mutableSetOf()) { it.toString() } + BuiltinNodes.SHAPE_PINS.getValue(n.type)
            links.removeAll { it.toNode == n.id && it.toPin !in kept }
            n.literals.keys.retainAll { it in kept }
        }
        // Changing what a list HOLDS invalidates every slot: an item id typed into what is now a tile pin
        // would keep its number and read as the tile 4151,0,0.
        if (n.type == BuiltinNodes.LITERAL_LIST && pin == BuiltinNodes.LIST_OF) {
            val shape = BuiltinNodes.SHAPE_PINS.getValue(n.type)
            links.removeAll { it.toNode == n.id && it.toPin !in shape }
            n.literals.keys.retainAll { it in shape }
        }
        markDirty()
    }

    /**
     * Record a camera move as an undo step.
     *
     * [view] is where the camera was **before** the move, the same convention every other record follows.
     * The document is captured lazily and only if the step is actually pushed — this runs on every frame of
     * a pan, and serialising the graph sixty times a second to discard it in the coalescing branch is the
     * kind of cost that makes a drag feel heavy.
     *
     * Ignored inside an [edit], where the camera is not what the user is undoing.
     */
    fun recordView(label: String, view: History.ViewState) {
        if (editDepth != 0) return
        history.record(label, coalesce = true, windowMs = History.VIEW_COALESCE_MS, nowMs = System.currentTimeMillis()) {
            History.Snapshot(GraphDoc.toJson(toGraph()), view)
        }
    }

    /** What one history step restored, so the caller knows whether the graph moved and where to put the camera. */
    class Step(val graphChanged: Boolean, val view: History.ViewState?)

    /** Step back. [view] is the camera right now, handed to the redo branch. Null when there is nothing to undo. */
    fun undo(view: History.ViewState? = null): Step? = step(history.undo(History.Snapshot(GraphDoc.toJson(toGraph()), view)))

    fun redo(view: History.ViewState? = null): Step? = step(history.redo(History.Snapshot(GraphDoc.toJson(toGraph()), view)))

    private fun step(s: History.Snapshot?): Step? {
        if (s == null) return null
        return Step(restoreFrom(s.json), s.view)
    }

    /**
     * Replace this document's contents in place, so anything holding a reference stays valid.
     *
     * @return whether anything actually changed — false for a camera-only step, which must not clear the
     *   canvas's selection or re-run validation.
     */
    private fun restoreFrom(json: String?): Boolean {
        if (json == null || json == GraphDoc.toJson(toGraph())) return false
        val g = GraphDoc.fromJson(json)
        nodes.clear(); nodes += g.nodes
        links.clear(); links += g.links
        variables.clear(); variables += g.variables
        functions.clear(); functions += g.functions
        types.clear(); types += g.types
        enums.clear(); enums += g.enums
        name = g.name
        // Ids are never reused, so the counters only ever move forward — otherwise undoing a delete and
        // then adding a node could mint an id the canvas still has cached state for.
        nextNodeId = maxOf(nextNodeId, (nodes.maxOfOrNull { it.id } ?: 0) + 1)
        nextLinkId = maxOf(nextLinkId, (links.maxOfOrNull { it.id } ?: 0) + 1)
        markDirty()
        return true
    }

    fun save(target: File? = null): File {
        val f = target ?: file ?: error("no file to save to")
        GraphDoc.write(toGraph(), f)
        file = f
        dirty = false
        return f
    }

    companion object {
        /**
         * The directory graph documents live in — files, so they are diffable and shareable.
         *
         * **Overridable, because the default names a host.** `~/.ziggle/graphs` is where the vscript client
         * keeps them, and for as long as the editor only ever ran inside that client, a constant was the
         * honest spelling. It is not any more: nine call sites reach this, a standalone shell has no
         * `.ziggle` home to speak of, and a test that writes into the developer's real graphs folder is a
         * test that can destroy work.
         *
         * So the host says where its workspace is — [workspace], or `-Dziggle.vscript.workspace` for a
         * host that is a `main` rather than a caller — and the default is unchanged, which is why nothing
         * in the client had to be touched.
         */
        fun graphsDir(): File = workspace
            ?: System.getProperty(WORKSPACE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".ziggle/graphs")

        /**
         * Where this process's documents live, when it is not the client.
         *
         * Set once at startup by whoever opens the editor. A `var` rather than a constructor parameter
         * because [graphsDir] is reached statically from nine places across three modules; threading a
         * path through all of them would be the larger change and would not make the answer any less
         * global than it already is.
         */
        @Volatile
        var workspace: File? = null

        const val WORKSPACE_PROPERTY = "ziggle.vscript.workspace"

        fun open(file: File): EditorDoc = EditorDoc(GraphDoc.read(file), file)

        /** A new, empty document with a single Start node so the canvas is never blank. */
        fun blank(name: String = "untitled"): EditorDoc {
            val doc = EditorDoc(Graph(java.util.UUID.randomUUID().toString(), name))
            doc.addNode(dev.ziggle.vscript.model.BuiltinNodes.ENTRY, 80f, 120f)
            doc.dirty = false
            return doc
        }
    }
}
