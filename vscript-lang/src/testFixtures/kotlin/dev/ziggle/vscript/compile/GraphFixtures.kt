package dev.ziggle.vscript.compile

import dev.ziggle.vscript.model.FunctionPin
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphFunction
import dev.ziggle.vscript.model.GraphVariable
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.PinType
import dev.ziggle.vscript.vm.Chunk

/** Terse graph assembly for tests — ids are handed out in creation order. */
class GraphBuilder(
    private val name: String = "test",
    private val id: String = "00000000-test",
    /**
     * Does what this document declares cross an import boundary?
     *
     * True for [library] and false for [graph], which is what the two words already meant — a library is a
     * document somebody imports, and everything it declares here is meant to be reachable. Set once rather
     * than repeated on every `function`/`variable`/`type` call, so a fixture reads as the thing it is.
     */
    private val exported: Boolean = false,
) {
    var nextNode = 1
    var nextLink = 1
    private val nodes = ArrayList<Node>()
    private val links = ArrayList<Link>()
    private val variables = ArrayList<GraphVariable>()
    private val functions = ArrayList<GraphFunction>()
    private val types = ArrayList<dev.ziggle.vscript.model.StructType>()
    private val enums = ArrayList<dev.ziggle.vscript.model.EnumType>()
    private val imports = ArrayList<dev.ziggle.vscript.model.GraphImport>()

    /** `import <alias> from "<ref>"` — [ref] defaults to the alias, which is the common case. */
    fun import(alias: String, ref: String = alias, docId: String? = null) {
        imports += dev.ziggle.vscript.model.GraphImport(alias, ref, docId)
    }

    fun node(
        type: String,
        literals: Map<String, Any?> = emptyMap(),
        variable: String? = null,
        function: String? = null,
        callee: String? = null,
    ): Int {
        val id = nextNode++
        nodes += Node(
            id, type,
            literals = LinkedHashMap(literals),
            variable = variable,
            function = function,
            callee = callee,
        )
        return id
    }

    /** Add a node with an explicit id — for testing duplicate-id and dangling-link cases. */
    fun rawNode(node: Node) { nodes += node }

    fun link(fromNode: Int, fromPin: String, toNode: Int, toPin: String): Int {
        val id = nextLink++
        links += Link(id, fromNode, fromPin, toNode, toPin)
        return id
    }

    fun variable(name: String, type: PinType, default: Any? = null) {
        variables += GraphVariable(name, dev.ziggle.vscript.model.TypeRef(type), default, isExported = exported)
    }

    fun variable(name: String, type: dev.ziggle.vscript.model.TypeRef, default: Any? = null) {
        variables += GraphVariable(name, type, default, isExported = exported)
    }

    /** Declare a closed set of names — see `EnumType`. */
    fun enum(name: String, vararg members: String) {
        enums += dev.ziggle.vscript.model.EnumType(name, members.toList(), isExported = exported)
    }

    /** Declare a record. Field types are written as built-ins or as the name of another declared one. */
    fun type(name: String, vararg fields: Pair<String, Any>) {
        types += dev.ziggle.vscript.model.StructType(
            name,
            fields.map { (n, t) ->
                FunctionPin(
                    n,
                    when (t) {
                        is PinType -> dev.ziggle.vscript.model.TypeRef(t)
                        is dev.ziggle.vscript.model.TypeRef -> t
                        else -> dev.ziggle.vscript.model.TypeRef.named(t.toString())
                    },
                )
            },
            isExported = exported,
        )
    }

    fun function(
        name: String,
        params: List<Pair<String, PinType>> = emptyList(),
        results: List<Pair<String, PinType>> = emptyList(),
    ) {
        functions += GraphFunction(
            name,
            params.map { (n, t) -> FunctionPin(n, t) },
            results.map { (n, t) -> FunctionPin(n, t) },
            isExported = exported,
        )
    }

    fun build(): Graph =
        Graph(id, name, nodes, links, variables, functions, types, enums, imports)
}

fun graph(name: String = "test", block: GraphBuilder.() -> Unit): Graph =
    GraphBuilder(name).apply(block).build()

/** A named library document, with its own id so a closure can tell it apart from the root. */
fun library(name: String, block: GraphBuilder.() -> Unit): Graph =
    GraphBuilder(name, id = "id-$name", exported = true).apply(block).build()

/** Every opcode in [Chunk], in order — for asserting on what the compiler emitted. */
fun Chunk.opcodes(): List<Int> = (0 until size).map { op(it) }

/**
 * How many times opcode [op] appears with operands `a`/`b` equal to [a]/[b] (null = don't care).
 *
 * [b] matters for `TRACE`, whose `a` operand is a *node* id for `NODE_ENTER` and a *link* id for
 * `EXEC_EDGE` — the two share the slot, so counting by `a` alone conflates node 3 with link 3.
 */
fun Chunk.countOp(op: Int, a: Int? = null, b: Int? = null): Int =
    (0 until size).count {
        this.op(it) == op && (a == null || this.a(it) == a) && (b == null || this.b(it) == b)
    }

/** A descriptor for a host-backed node, spelled out so tests read as graphs rather than as catalogs. */
fun hostNode(
    type: String,
    host: String,
    kind: dev.ziggle.vscript.model.NodeKind,
    hostKind: dev.ziggle.vscript.vm.HostKind = dev.ziggle.vscript.vm.HostKind.INLINE,
    inputs: List<dev.ziggle.vscript.model.PinSpec> = emptyList(),
    outputs: List<dev.ziggle.vscript.model.PinSpec> = emptyList(),
): NodeDescriptor = NodeDescriptor(
    type = type,
    title = type,
    category = "Test",
    kind = kind,
    inputs = inputs,
    outputs = outputs,
    host = host,
    hostKind = hostKind,
)
