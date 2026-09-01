package dev.ziggle.vscript.editor.graph

import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.model.NodeDescriptor
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.model.effectivePinType
import dev.ziggle.vscript.model.expressionCalls
import dev.ziggle.vscript.model.literalTypeOf
import dev.ziggle.vscript.model.resolveNode
import dev.ziggle.vscript.runtime.EditorDoc

/**
 * The canvas's answer to "what does the wire into this pin carry" — the same question the validator and
 * the compiler ask, asked here so the drawing agrees with them. Without it a loop's `Element`, a map
 * loop's `Key` / `Value` and `List At`'s `Item` were drawn as wildcards however typed the list wired in
 * was, and anything shaped by what feeds it (a Get on the element, a generic call) saw nothing.
 *
 * Resolving a node needs to know what feeds it, and knowing what feeds it needs the node on the other end
 * resolved: the two are mutually recursive, bounded by [depth] threaded through both halves.
 */
object CanvasTyping {
    private const val MAX_DEPTH = 32

    /** [node]'s pins as the canvas should draw them, with what is wired in taken into account. */
    fun descOf(doc: EditorDoc, catalog: NodeCatalog, node: Node, depth: Int = 0): NodeDescriptor? =
        catalog[node.type]?.let {
            resolveNode(
                node, it, { n -> doc.function(n) }, { doc.visibleTypes() },
                expressionCalls(catalog, doc.nodes, doc.links, doc::function),
                { doc.enums },
            ) { n, p -> if (depth > MAX_DEPTH) null else feedingIn(doc, catalog, n, p, depth + 1) }
        }

    /** What the wire into [nodeId]'s [pin] carries; with nothing wired, what was typed in, when its kind says a type. */
    fun feedingIn(doc: EditorDoc, catalog: NodeCatalog, nodeId: Int, pin: String, depth: Int = 0): TypeRef? {
        if (depth > MAX_DEPTH) return null
        val l = doc.links.firstOrNull { it.toNode == nodeId && it.toPin == pin }
            ?: return literalTypeOf(doc.node(nodeId)?.literals?.get(pin))
        val src = doc.node(l.fromNode) ?: return null
        val out = descOf(doc, catalog, src, depth + 1)?.output(l.fromPin) ?: return null
        return effectivePinType(src, out, { name -> doc.variable(name)?.type }) { n, p -> feedingIn(doc, catalog, n, p, depth + 1) }
    }

    /** The type a pin of [node] carries on the canvas — [dev.ziggle.vscript.model.effectivePinType] with the wires known. */
    fun pinType(doc: EditorDoc, catalog: NodeCatalog, node: Node, spec: dev.ziggle.vscript.model.PinSpec): TypeRef =
        effectivePinType(node, spec, { name -> doc.variable(name)?.type }) { n, p -> feedingIn(doc, catalog, n, p) }
}
