package dev.ziggle.vscript.editor.graph

/**
 * Maps document ids onto the single `long` id space imgui-node-editor uses for nodes, pins and links.
 *
 * Everything the canvas draws shares one namespace, so a node id and a link id must never collide — the
 * library would treat them as the same object. Each kind gets a distinct tag in the high bits, and pins
 * additionally pack their owning node, their index and their direction, so a pin id decodes back to
 * exactly one pin without a lookup table.
 *
 * Ids start at 1: imgui-node-editor treats 0 as "no object".
 */
object EditorIds {
    private const val KIND_SHIFT = 60
    private const val KIND_NODE = 1L shl KIND_SHIFT
    private const val KIND_PIN = 2L shl KIND_SHIFT
    private const val KIND_LINK = 3L shl KIND_SHIFT
    private const val KIND_MASK = 7L shl KIND_SHIFT

    private const val PIN_NODE_SHIFT = 16
    private const val PIN_INDEX_SHIFT = 1

    fun node(nodeId: Int): Long = KIND_NODE or (nodeId.toLong() and 0xFFFFFFFFL)

    fun link(linkId: Int): Long = KIND_LINK or (linkId.toLong() and 0xFFFFFFFFL)

    /** [index] is the pin's position within its node's input or output list. */
    fun pin(nodeId: Int, index: Int, input: Boolean): Long =
        KIND_PIN or
            (nodeId.toLong() shl PIN_NODE_SHIFT) or
            (index.toLong() shl PIN_INDEX_SHIFT) or
            (if (input) 1L else 0L)

    fun isNode(id: Long): Boolean = (id and KIND_MASK) == KIND_NODE
    fun isPin(id: Long): Boolean = (id and KIND_MASK) == KIND_PIN
    fun isLink(id: Long): Boolean = (id and KIND_MASK) == KIND_LINK

    fun nodeIdOf(id: Long): Int = (id and 0xFFFFFFFFL).toInt()
    fun linkIdOf(id: Long): Int = (id and 0xFFFFFFFFL).toInt()

    fun pinNode(id: Long): Int = ((id and (KIND_MASK.inv())) ushr PIN_NODE_SHIFT).toInt()
    fun pinIndex(id: Long): Int = (((id ushr PIN_INDEX_SHIFT) and 0x7FFFL)).toInt()
    fun pinIsInput(id: Long): Boolean = (id and 1L) == 1L
}
