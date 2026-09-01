package dev.ziggle.vscript.editor.graph

enum class WireRouting {
    /** Straight from pin to pin. Fastest, and fine on a sparse graph. */
    DIRECT,

    /** Route around node bodies, so a wire is visible for its whole length. */
    AVOID_NODES,

    /** As [AVOID_NODES], and also spread wires that would share a lane so they do not hide each other. */
    AVOID_ALL,
}
