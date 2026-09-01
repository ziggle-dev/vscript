package dev.ziggle.vscript.editor.graph

import dev.ziggle.imgui.TextEdit
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.editor.graph.EditorSettings
import dev.ziggle.vscript.editor.graph.PinStyle
import dev.ziggle.vscript.model.NodeKind
import dev.ziggle.vscript.model.TypeRef
import dev.ziggle.vscript.runview.NodeColors

/**
 * Every draw call for the node canvas.
 *
 * Pure painting: it takes geometry and state and emits into an [ImDrawList]. Nothing here reads input or
 * mutates the document, so what the canvas *looks* like can be changed without touching how it *behaves* —
 * which is the whole reason for owning the renderer rather than driving a library.
 *
 * Techniques worth knowing, each chosen against a specific failure seen in the reference implementation:
 *
 *  - **Header and body are one shape, not two.** The header is a rect with only its top corners rounded,
 *    the body one with only its bottom corners, then a single border traced round the union. Drawing two
 *    fully-rounded rects (the reference's approach) leaves a pill floating proud of a capsule, and putting
 *    a border on only one of them makes the seam obvious.
 *  - **Shadowed text.** A dark, slightly offset copy under every label. Two draw calls, and it keeps text
 *    legible over any header colour without a backing plate.
 *  - **Wire control points are offset along the pin normal**, not pinned to the horizontal midpoint. The
 *    midpoint form collapses to a straight line when two pins share a Y, and *inverts through both nodes*
 *    when the target is left of the source — which is every back-edge in a loop.
 *  - **The grid has two levels and fades.** A single 1px level turns to mush when zoomed out and is sparse
 *    when zoomed in; fading each level as its spacing tightens keeps the density roughly constant.
 */
object CanvasRenderer {

    // ---- grid -------------------------------------------------------------------------------------

    private const val GRID_MINOR = 24f
    private const val GRID_MAJOR_EVERY = 5

    /**
     * Dotted-free two-level grid.
     *
     * Each level fades out as its on-screen spacing drops below [FADE_FLOOR] px, so zooming out retires the
     * fine level instead of smearing it into a grey wash.
     */
    fun grid(dl: ImDrawList, cam: ScreenCamera, x: Float, y: Float, w: Float, h: Float) {
        dl.addRectFilled(x, y, x + w, y + h, PinStyle.CANVAS_BG)
        drawGridLevel(dl, cam, x, y, w, h, GRID_MINOR, PinStyle.GRID)
        drawGridLevel(dl, cam, x, y, w, h, GRID_MINOR * GRID_MAJOR_EVERY, PinStyle.GRID_MAJOR)
    }

    private const val FADE_FLOOR = 7f

    private fun drawGridLevel(
        dl: ImDrawList,
        cam: ScreenCamera,
        x: Float, y: Float, w: Float, h: Float,
        stepGraph: Float,
        color: Int,
    ) {
        val step = cam.px(stepGraph)
        if (step < 3f) return
        val alpha = ((step - FADE_FLOOR) / FADE_FLOOR).coerceIn(0f, 1f)
        if (alpha <= 0.02f) return
        val col = Theme.withAlpha(color, alpha)

        // fmod of the origin gives the scroll offset — the standard way to keep a grid anchored while panning.
        var gx = (cam.originX - x) % step
        if (gx < 0) gx += step
        var px = x + gx
        while (px < x + w) {
            dl.addLine(px, y, px, y + h, col, 1f)
            px += step
        }
        var gy = (cam.originY - y) % step
        if (gy < 0) gy += step
        var py = y + gy
        while (py < y + h) {
            dl.addLine(x, py, x + w, py, col, 1f)
            py += step
        }
    }

    // ---- nodes ------------------------------------------------------------------------------------

    class NodeVisual(
        val selected: Boolean,
        val hovered: Boolean,
        val error: Boolean,
        val active: Boolean,
        /** The pin under the cursor on THIS node, if any — drawn with a halo. */
        val hoveredPin: NodeGeometry.Pin? = null,
        /** A comment part currently being edited: the field is drawn over it, so the static text is
         *  suppressed rather than left to show through from underneath. */
        val editingHeading: Boolean = false,
        val editingBody: Boolean = false,
        /** Worst severity this node logged during the last run, or null. Drawn as a dot on the header. */
        val logLevel: dev.ziggle.vscript.log.LogLevel? = null,
        /** A breakpoint is armed here — drawn in the gutter beside the node. */
        val breakpoint: Boolean = false,
        val breakpointEnabled: Boolean = true,
        /** Execution is stopped ON this node. */
        val paused: Boolean = false,
    )

    /**
     * [connected] is asked about a whole [NodeGeometry.Pin], not a pin NAME.
     *
     * Direction matters and a name alone cannot carry it: most nodes have an input AND an output both
     * called "Exec", so a name-keyed lookup answers for the wrong one. It was also why output pins were
     * simply hardcoded as connected — there was no way to ask.
     */
    fun node(
        dl: ImDrawList,
        cam: ScreenCamera,
        g: NodeGeometry,
        v: NodeVisual,
        connected: (NodeGeometry.Pin) -> Boolean,
    ) {
        if (g.desc.kind == NodeKind.COMMENT) {
            comment(dl, cam, g, v)
            return
        }
        if (g.desc.kind == NodeKind.FUNCTION) {
            functionBox(dl, cam, g, v, connected)
            return
        }
        if (g.desc.type == dev.ziggle.vscript.model.BuiltinNodes.REROUTE) {
            knot(dl, cam, g, v)
            return
        }
        val x = cam.toScreenX(g.rect.x)
        val y = cam.toScreenY(g.rect.y)
        val w = cam.px(g.rect.w)
        val h = cam.px(g.rect.h)
        val hh = cam.px(NodeGeometry.HEADER_H)
        val r = cam.px(NodeGeometry.CORNER)

        // Body first (bottom corners), then the header over it (top corners), then ONE border round both.
        dl.addRectFilled(x, y + hh, x + w, y + h, PinStyle.BODY, r, ImDrawFlags.RoundCornersBottom)
        val header = NodeColors.headerColor(g.desc.category, g.desc.kind)
        dl.addRectFilled(x, y, x + w, y + hh, header, r, ImDrawFlags.RoundCornersTop)
        dl.addLine(x, y + hh, x + w, y + hh, Theme.shade(header, 0.65f), 1f)

        val border = when {
            // The paused node outranks everything, selection included: while stopped, "where am I" is the
            // only question the canvas is being asked.
            v.paused -> PAUSED_RING
            v.error -> PinStyle.BORDER_ERROR
            v.selected -> PinStyle.BORDER_SELECTED
            v.active -> Theme.OK
            v.hovered -> Theme.ACCENT_HOVER
            else -> PinStyle.BORDER
        }
        val thickness = if (v.paused) 2.5f else if (v.selected || v.error || v.active) 2f else 1f
        if (v.paused) {
            // A soft halo outside the ring, so a stopped node is findable at a glance on a busy canvas.
            dl.addRect(
                x - 3f, y - 3f, x + w + 3f, y + h + 3f, Theme.withAlpha(PAUSED_RING, 0.35f),
                r + 3f, ImDrawFlags.RoundCornersAll, 3f,
            )
        }
        dl.addRect(x, y, x + w, y + h, border, r, ImDrawFlags.RoundCornersAll, thickness)

        val fontSize = cam.px(TextMeasure.lineHeight())
        if (fontSize >= 5f) {
            shadowedText(dl, x + cam.px(NodeGeometry.PAD_X), y + (hh - fontSize) * 0.5f, fontSize,
                Theme.TEXT, NodeGeometry.title(g.node, g.desc))
        }

        // The log badge: a dot on the header of any node that emitted during the last run, coloured by
        // worst severity. Zero cost visually when nothing logged, which is the point — most debugging is
        // "why did THAT node misbehave", and this answers where to look without opening anything.
        v.logLevel?.let { lvl ->
            val bx = x + w - cam.px(BADGE_INSET)
            val by = y + hh * 0.5f
            val br = cam.px(BADGE_R)
            dl.addCircleFilled(bx, by, br + maxOf(1f, cam.px(1.5f)), Theme.shade(header, 0.55f), 12)
            dl.addCircleFilled(bx, by, br, badgeColor(lvl), 12)
        }

        // The breakpoint marker sits in a GUTTER outside the node, the way an editor puts it in the margin
        // rather than in the text. Inside the header it would have to displace the title, and a title that
        // shifts sideways when you arm a breakpoint costs more than a few pixels of margin does.
        if (v.breakpoint) {
            val (bgx, bgy) = breakpointCenter(g)
            val sx = cam.toScreenX(bgx)
            val sy = cam.toScreenY(bgy)
            val rad = cam.px(BREAK_R)
            val col = if (v.breakpointEnabled) BREAK_ON else BREAK_OFF
            if (v.breakpointEnabled) dl.addCircleFilled(sx, sy, rad, col, 12)
            else dl.addCircle(sx, sy, rad, col, 12, maxOf(1f, cam.px(1.5f)))
        }

        for (p in g.inputAnchors) pin(dl, cam, p, connected(p), fontSize, p === v.hoveredPin)
        for (p in g.outputAnchors) pin(dl, cam, p, connected(p), fontSize, p === v.hoveredPin)
    }

    /** Where the breakpoint marker sits, in graph space — shared by the painter and the hit-tester. */
    fun breakpointCenter(g: NodeGeometry): Pair<Float, Float> =
        (g.rect.x - BREAK_GUTTER) to (g.rect.y + NodeGeometry.HEADER_H * 0.5f)

    fun breakpointHit(g: NodeGeometry, gx: Float, gy: Float): Boolean {
        val (bx, by) = breakpointCenter(g)
        val dx = gx - bx
        val dy = gy - by
        return dx * dx + dy * dy <= BREAK_HIT * BREAK_HIT
    }

    /**
     * A value observed on a wire, drawn as a pill at its midpoint.
     *
     * What makes a visual debugger better than a textual one: the graph is already the variables panel.
     * Every value in scope is on screen with a wire attached to it, so annotating the wire beats a tree of
     * names you then have to map back onto the picture yourself.
     */
    fun valuePill(dl: ImDrawList, cam: ScreenCamera, cx: Float, cy: Float, text: String, type: TypeRef) {
        val fontSize = cam.px(TextMeasure.lineHeight()) * 0.86f
        if (fontSize < 6f) return
        val tw = textWidth(text, fontSize)
        val padX = cam.px(6f)
        val padY = cam.px(3f)
        val col = PinStyle.color(type)
        val x0 = cx - tw * 0.5f - padX
        val y0 = cy - fontSize * 0.5f - padY
        val x1 = cx + tw * 0.5f + padX
        val y1 = cy + fontSize * 0.5f + padY
        dl.addRectFilled(x0, y0, x1, y1, PILL_BG, cam.px(3f), ImDrawFlags.RoundCornersAll)
        dl.addRect(x0, y0, x1, y1, Theme.withAlpha(col, 0.55f), cam.px(3f), ImDrawFlags.RoundCornersAll, 1f)
        shadowedText(dl, cx - tw * 0.5f, cy - fontSize * 0.5f, fontSize, Theme.shade(col, 1.35f), text)
    }

    private const val BREAK_R = 4.5f
    private const val BREAK_GUTTER = 13f
    private const val BREAK_HIT = 10f
    private val BREAK_ON = Theme.col(0xE0, 0x4C, 0x4C)
    private val BREAK_OFF = Theme.col(0x8A, 0x55, 0x55)
    private val PAUSED_RING = Theme.col(0xE8, 0xB4, 0x4A)
    private val PILL_BG = Theme.col(0x14, 0x17, 0x1F, 0xF0)

    /** Where a node's log badge sits, in graph space — shared by the painter and the hit-tester. */
    fun badgeCenter(g: NodeGeometry): Pair<Float, Float> =
        (g.rect.right - BADGE_INSET) to (g.rect.y + NodeGeometry.HEADER_H * 0.5f)

    fun badgeHit(g: NodeGeometry, gx: Float, gy: Float): Boolean {
        val (bx, by) = badgeCenter(g)
        val dx = gx - bx
        val dy = gy - by
        return dx * dx + dy * dy <= BADGE_HIT * BADGE_HIT
    }

    private fun badgeColor(level: dev.ziggle.vscript.log.LogLevel): Int = when (level) {
        dev.ziggle.vscript.log.LogLevel.ERROR -> Theme.BAD
        dev.ziggle.vscript.log.LogLevel.WARN -> Theme.WARN
        dev.ziggle.vscript.log.LogLevel.INFO -> Theme.col(0x8A, 0x93, 0xA6)
    }

    private const val BADGE_R = 3.5f
    /** A function box's own palette — related to the comment's, clearly not the same thing. */
    private val FN_FILL = Theme.col(0x1B, 0x24, 0x33, 0xB4)
    private val FN_HEADER = Theme.col(0x27, 0x3B, 0x55, 0xF0)
    private val FN_BORDER = Theme.col(0x49, 0x6B, 0x93)

    private const val BADGE_INSET = 12f
    private const val BADGE_HIT = 9f

    /** A comment container: a translucent wash with a solid title strip, drawn behind everything else. */
    /**
     * A user function's box.
     *
     * Deliberately NOT a comment: a comment is annotation you can ignore, while this frames code that only
     * runs when something calls it. Same shape, so it reads as a container, but its own accent so a glance
     * tells you which of the two you are looking at — and a heavier border, because its edge is meaningful
     * (what is inside is IN the function) rather than decorative.
     */
    private fun functionBox(
        dl: ImDrawList,
        cam: ScreenCamera,
        g: NodeGeometry,
        v: NodeVisual,
        connected: (NodeGeometry.Pin) -> Boolean,
    ) {
        val x = cam.toScreenX(g.rect.x)
        val y = cam.toScreenY(g.rect.y)
        val w = cam.px(g.rect.w)
        val h = cam.px(g.rect.h)
        val hh = cam.px(NodeGeometry.HEADER_H)
        val r = cam.px(NodeGeometry.CORNER)
        val folded = g.node.folded

        // Folded, the box IS its header — so the fill and the header are one rounded bar rather than a
        // bar with a sliver of body peeking out below it.
        if (!folded) dl.addRectFilled(x, y, x + w, y + h, FN_FILL, r, ImDrawFlags.RoundCornersAll)
        dl.addRectFilled(
            x, y, x + w, y + hh, FN_HEADER, r,
            if (folded) ImDrawFlags.RoundCornersAll else ImDrawFlags.RoundCornersTop,
        )
        if (!folded) dl.addLine(x, y + hh, x + w, y + hh, Theme.shade(FN_HEADER, 0.6f), 1f)

        val border = if (v.selected) PinStyle.BORDER_SELECTED else FN_BORDER
        dl.addRect(x, y, x + w, y + h, border, r, ImDrawFlags.RoundCornersAll, if (v.selected) 2f else 1.5f)

        val fontSize = cam.px(TextMeasure.lineHeight())
        if (fontSize < 5f) return

        val title = "fn: " + (g.node.function ?: "unnamed")
        val ty = y + (hh - fontSize) * 0.5f
        var tx = x + cam.px(NodeGeometry.PAD_X)
        // The title is skipped while the name is being typed — the in-place editor draws there, and two
        // strings in one spot is how a rename ends up looking like it did nothing. The PINS still draw.
        if (!v.editingHeading) shadowedText(dl, tx, ty, fontSize, Theme.TEXT, title)

        // The signature, dim and to the right of the name. It is what you read on a folded box, and what
        // you stop reading the moment the pins themselves are visible again.
        if (g.subtitle.isNotEmpty()) {
            tx += cam.px(TextMeasure.width(title)) + cam.px(10f)
            val room = x + w - cam.px(NodeGeometry.FOLD_W) - tx
            if (room > cam.px(20f)) shadowedText(dl, tx, ty, fontSize, Theme.TEXT_DIM, g.subtitle)
        }

        // The signature, on the edges. `pin` draws the glyph AND its label, and its label sits on the far
        // side of the pin from the node — which for a boundary pin puts it outside the box, matching the
        // exec pins either side of it. Drawing a second set inward, as this did at first, is just the same
        // names twice.
        if (!folded) {
            for (p in g.outputAnchors) pin(dl, cam, p, connected(p), fontSize, p === v.hoveredPin)
            for (p in g.inputAnchors) pin(dl, cam, p, connected(p), fontSize, p === v.hoveredPin)
        }

        // The fold toggle: a chevron pointing the way it will go — right means "opens to the right",
        // down means "closes downward" — drawn from the same rect the click is tested against.
        g.foldRect?.let { fr ->
            val cx = cam.toScreenX(fr.x + fr.w * 0.5f)
            val cy = cam.toScreenY(fr.y + fr.h * 0.5f)
            val s = cam.px(4.5f)
            val col = if (v.hovered) Theme.TEXT else Theme.TEXT_DIM
            if (folded) {
                dl.addTriangleFilled(cx - s * 0.6f, cy - s, cx - s * 0.6f, cy + s, cx + s * 0.9f, cy, col)
            } else {
                dl.addTriangleFilled(cx - s, cy - s * 0.6f, cx + s, cy - s * 0.6f, cx, cy + s * 0.9f, col)
            }
        }
    }

    private fun comment(dl: ImDrawList, cam: ScreenCamera, g: NodeGeometry, v: NodeVisual) {
        val x = cam.toScreenX(g.rect.x)
        val y = cam.toScreenY(g.rect.y)
        val w = cam.px(g.rect.w)
        val h = cam.px(g.rect.h)
        val hh = cam.px(NodeGeometry.HEADER_H)
        val r = cam.px(NodeGeometry.CORNER)

        dl.addRectFilled(x, y, x + w, y + h, PinStyle.COMMENT_FILL, r, ImDrawFlags.RoundCornersAll)
        // The note's own band, between the heading strip and the region the nodes sit in. Square, because
        // it is a middle section of the box rather than an end of it.
        if (g.bodyLines.isNotEmpty()) {
            dl.addRectFilled(x, y + hh, x + w, cam.toScreenY(g.textBottom), PinStyle.COMMENT_TEXT_BG)
        }
        dl.addRectFilled(x, y, x + w, y + hh, PinStyle.COMMENT_HEADER, r, ImDrawFlags.RoundCornersTop)
        commentSeparator(dl, cam, x, y + hh, w)
        val border = if (v.selected) PinStyle.BORDER_SELECTED else PinStyle.COMMENT_BORDER
        dl.addRect(x, y, x + w, y + h, border, r, ImDrawFlags.RoundCornersAll, if (v.selected) 2f else 1f)

        val fontSize = cam.px(TextMeasure.lineHeight())
        if (fontSize >= 5f) {
            if (!v.editingHeading) {
                val heading = g.node.comment
                shadowedText(
                    dl, x + cam.px(NodeGeometry.PAD_X), y + (hh - fontSize) * 0.5f, fontSize,
                    if (heading.isNullOrBlank()) Theme.TEXT_DIM else Theme.TEXT,
                    heading?.takeIf { it.isNotBlank() } ?: "Comment",
                )
            }
            // Body under the strip. Dim, because it is annotation: it must be readable when you look at it
            // and recede when you are reading the graph. The lines come from the GEOMETRY, not from a
            // second wrap here — the box reserves height for exactly these lines, so drawing a different
            // set would put the rule below the body in the wrong place.
            if (!v.editingBody && g.bodyLines.isNotEmpty()) {
                val bx = cam.toScreenX(g.bodyTextX)
                var ty = cam.toScreenY(g.bodyTextY)
                val lh = cam.px(g.bodyLineH)
                val bottom = cam.toScreenY(g.rect.bottom)
                for (line in g.bodyLines) {
                    if (ty + fontSize > bottom) break // clip rather than overflow the box
                    if (line.isNotEmpty()) shadowedText(dl, bx, ty, fontSize, PinStyle.COMMENT_TEXT, line)
                    ty += lh
                }
            }
        }
        // A rule under the body too, so the note is a closed block rather than text trailing off into the
        // nodes it describes. Only when there IS a body: with none, the strip's own rule already does it.
        if (g.bodyLines.isNotEmpty()) commentSeparator(dl, cam, x, cam.toScreenY(g.textBottom), w, rule = false)
        // Resize grip, bottom-right — drawn at exactly the size that responds (NodeGeometry.GRIP), so the
        // affordance never promises a bigger or smaller target than it has.
        val grip = cam.px(NodeGeometry.GRIP)
        dl.addTriangleFilled(
            x + w - grip, y + h, x + w, y + h - grip, x + w, y + h,
            PinStyle.COMMENT_BORDER,
        )
    }

    /**
     * The division between a comment's heading and its body.
     *
     * A heading and a note are two different registers of text, and stacking them in one translucent box
     * with nothing between reads as one run of prose that happens to start bold. A single border line would
     * separate them but also look like the edge of something; a hairline plus a short gradient falling away
     * beneath it reads as the body sitting *under* the strip, which is the relationship that actually
     * holds.
     */
    private fun commentSeparator(
        dl: ImDrawList,
        cam: ScreenCamera,
        x: Float,
        y: Float,
        w: Float,
        rule: Boolean = true,
    ) {
        val inset = cam.px(NodeGeometry.PAD_X) * 0.5f
        val lx = x + inset
        val rx = x + w - inset
        if (rx - lx < 4f) return
        // The heading gets a hairline as well as the shadow, because a strip needs a defined bottom edge.
        // The body gets the shadow alone: it already sits on its own band, so a second rule of equal weight
        // just adds a line without adding information.
        if (rule) dl.addLine(lx, y, rx, y, SEP_LINE, maxOf(1f, cam.px(1f)))
        dl.addRectFilledMultiColor(lx, y, rx, y + maxOf(2f, cam.px(6f)), SEP_SHADOW, SEP_SHADOW, SEP_CLEAR, SEP_CLEAR)
    }

    private val SEP_LINE = Theme.col(0x5A, 0x65, 0x84, 0xC8)
    private val SEP_SHADOW = Theme.col(0x00, 0x00, 0x00, 0x40)
    private val SEP_CLEAR = Theme.col(0x00, 0x00, 0x00, 0x00)

    /**
     * A pin glyph and its label.
     *
     * Shape carries exec-vs-data (triangle vs circle) because that reads without focus; colour carries the
     * type; and a data pin is filled when connected, hollow when not — the cheapest possible "is this wired
     * up?" signal, and the one thing worth taking from the reference implementation's pins unchanged.
     */
    private fun pin(
        dl: ImDrawList,
        cam: ScreenCamera,
        p: NodeGeometry.Pin,
        connected: Boolean,
        fontSize: Float,
        hovered: Boolean,
    ) {
        val cx = cam.toScreenX(p.x)
        val cy = cam.toScreenY(p.y)
        val rad = cam.px(NodeGeometry.PIN_RADIUS)
        val base = PinStyle.color(p.type)

        // Hover darkens the pin and lifts it off the node with a shadow, rather than ringing it.
        //
        // The pin keeps its exact silhouette and size, so nothing shifts under the cursor as you approach —
        // a halo appearing at a different radius than the dot reads as the target moving just as you go to
        // click it. Darker rather than brighter because the body behind is dark: pressing INTO the surface
        // is the affordance that matches something you are about to grab.
        val col = if (hovered) Theme.shade(base, 0.72f) else base

        // A hollow pin is a HOLE, so clear the node out from under it first.
        //
        // Pins sit on the node's edge, which means a ring straddles it: without this you see node body
        // through one half of the ring and canvas through the other, and the node's border (or its selected
        // outline) cuts straight across the middle. Filling the footprint with the canvas colour before
        // stroking the ring makes the interior uniform, so "hollow" actually reads as empty.
        //
        // A rectangular clip cannot express a circular hole, so this is a fill rather than a scissor. The
        // cost is that the background grid does not show through the hole — at pin size that reads as
        // deliberate, and the alternative (re-drawing the grid inside each pin) is not worth the passes.
        if (!connected) {
            val punch = rad + maxOf(1f, cam.px(1.4f)) * 0.5f
            pinShape(dl, cam, p, cx, cy, punch, connected = true, col = PinStyle.CANVAS_BG)
        }

        if (hovered) {
            val off = maxOf(1f, cam.px(1.8f))
            pinShape(dl, cam, p, cx + off, cy + off, rad, connected, PIN_SHADOW)
        }
        pinShape(dl, cam, p, cx, cy, rad, connected, col)

        if (fontSize < 6f) return
        val label = p.spec.name
        val gap = rad + cam.px(6f)
        val ty = cy - fontSize * 0.5f
        if (p.input) {
            shadowedText(dl, cx + gap, ty, fontSize, Theme.TEXT_DIM, label)
        } else {
            val tw = textWidth(label, fontSize)
            shadowedText(dl, cx - gap - tw, ty, fontSize, Theme.TEXT_DIM, label)
        }
    }

    /**
     * One pin glyph at an arbitrary position and colour.
     *
     * Factored out so the hover shadow is drawn from the SAME geometry as the pin itself — a shadow that
     * derives its shape independently drifts out of alignment the moment either changes.
     */
    private fun pinShape(
        dl: ImDrawList,
        cam: ScreenCamera,
        p: NodeGeometry.Pin,
        cx: Float,
        cy: Float,
        rad: Float,
        connected: Boolean,
        col: Int,
    ) {
        // Filled = connected, hollow = not — for BOTH shapes. Exec pins used to draw filled regardless,
        // so the one signal that says "this is wired up" was missing from exactly the pins that define the
        // control spine, and an unfinished chain looked complete.
        val thickness = maxOf(1f, cam.px(1.4f))
        if (p.type.isExec) {
            val hgt = rad * 1.25f
            val wid = rad * 1.15f
            if (connected) {
                dl.addTriangleFilled(cx - wid, cy - hgt, cx - wid, cy + hgt, cx + wid, cy, col)
            } else {
                dl.addTriangle(cx - wid, cy - hgt, cx - wid, cy + hgt, cx + wid, cy, col, thickness)
            }
        } else if (connected) {
            dl.addCircleFilled(cx, cy, rad, col)
        } else {
            dl.addCircle(cx, cy, rad, col, 12, thickness)
        }
    }

    /** Drop shadow under a hovered pin. */
    private val PIN_SHADOW = Theme.col(0x05, 0x07, 0x0C, 0xC0)

    // Word wrapping lives in TextEdit.layout, and the comment body reads it from there rather than
    // keeping its own copy. Two wrap implementations that must agree is precisely the arrangement that puts
    // a caret one word away from the glyph it belongs to.

    // ---- links ------------------------------------------------------------------------------------

    /**
     * Control points offset along the pin normal.
     *
     * `strength` grows with horizontal separation but never falls below a floor, so a wire always leaves
     * its source rightwards and enters its target leftwards. That is what keeps a back-edge (target left of
     * source) looping cleanly outside the nodes instead of cutting straight through them.
     */
    private fun controlOffset(x1: Float, x2: Float): Float =
        maxOf(Math.abs(x2 - x1) * 0.5f, 60f)

    /**
     * The four screen-space control points of a wire, as `[x1,y1,x2,y2,x3,y3,x4,y4]`.
     *
     * Drawing and hit-testing both go through this, for the same reason node geometry is computed once: a
     * curve you can see but not click, because the two derived their control points slightly differently,
     * is a bug nobody can see the cause of.
     */
    /**
     * A wire's path in SCREEN space, as a flattened polyline: `x0, y0, x1, y1, …`.
     *
     * **One representation for both styles.** A curved wire is a cubic and an orthogonal one is a run of
     * right angles, and they could each have their own sampling, hit-testing and arrow-placement code —
     * which is how one of them ends up with a value pill in the wrong place and arrowheads pointing the
     * wrong way. Flattening both to points means everything downstream is written once, and a cubic drawn
     * as 32 segments is indistinguishable from one ImGui tessellated itself.
     */
    fun wirePath(cam: ScreenCamera, fromX: Float, fromY: Float, toX: Float, toY: Float): FloatArray {
        val x1 = cam.toScreenX(fromX); val y1 = cam.toScreenY(fromY)
        val x4 = cam.toScreenX(toX); val y4 = cam.toScreenY(toY)
        return if (EditorSettings.orthogonalWires) orthogonal(cam, x1, y1, x4, y4)
        else flattenCubic(x1, y1, x1 + cam.px(controlOffset(fromX, toX)), y1,
            x4 - cam.px(controlOffset(fromX, toX)), y4, x4, y4)
    }

    /**
     * The screen path for a ROUTED wire — graph-space waypoints in, screen polyline out.
     *
     * Two waypoints means the router found nothing in the way, so the wire keeps its usual shape and this
     * is exactly [wirePath]. More than two are corners it chose to avoid something, and the only question
     * left is what joins them: right angles trace the corners exactly, while a curve rounds them, because
     * a "curved" wire that turned square corners would look like the style setting had failed.
     */
    fun routedPath(cam: ScreenCamera, waypoints: FloatArray): FloatArray {
        if (waypoints.size <= 4) {
            return wirePath(cam, waypoints[0], waypoints[1], waypoints[2], waypoints[3])
        }
        val n = waypoints.size / 2
        val sx = FloatArray(n) { cam.toScreenX(waypoints[it * 2]) }
        val sy = FloatArray(n) { cam.toScreenY(waypoints[it * 2 + 1]) }
        if (EditorSettings.orthogonalWires) {
            val out = FloatArray(n * 2)
            for (i in 0 until n) { out[i * 2] = sx[i]; out[i * 2 + 1] = sy[i] }
            return out
        }
        return roundCorners(sx, sy, cam.px(ROUTE_FILLET))
    }

    /**
     * Replace each interior corner with a quadratic fillet, so a routed wire curves.
     *
     * The radius is clamped to half the shorter of the two segments meeting at the corner: a fixed radius
     * on a short segment overshoots past the next corner and ties the wire in a knot.
     */
    private fun roundCorners(sx: FloatArray, sy: FloatArray, radius: Float): FloatArray {
        val pts = ArrayList<Float>((sx.size + 4) * 2)
        pts += sx[0]; pts += sy[0]
        for (i in 1 until sx.size - 1) {
            val inLen = dist(sx[i - 1], sy[i - 1], sx[i], sy[i])
            val outLen = dist(sx[i], sy[i], sx[i + 1], sy[i + 1])
            val r = minOf(radius, inLen * 0.5f, outLen * 0.5f)
            if (r < 1f) { pts += sx[i]; pts += sy[i]; continue }
            val ax = sx[i] + (sx[i - 1] - sx[i]) / inLen * r
            val ay = sy[i] + (sy[i - 1] - sy[i]) / inLen * r
            val bx = sx[i] + (sx[i + 1] - sx[i]) / outLen * r
            val by = sy[i] + (sy[i + 1] - sy[i]) / outLen * r
            pts += ax; pts += ay
            for (s in 1 until FILLET_SEGMENTS) {
                val t = s.toFloat() / FILLET_SEGMENTS
                val u = 1f - t
                pts += u * u * ax + 2f * u * t * sx[i] + t * t * bx
                pts += u * u * ay + 2f * u * t * sy[i] + t * t * by
            }
            pts += bx; pts += by
        }
        pts += sx[sx.size - 1]; pts += sy[sy.size - 1]
        return pts.toFloatArray()
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        Math.sqrt(((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)).toDouble()).toFloat().coerceAtLeast(1e-4f)

    private const val ROUTE_FILLET = 16f
    private const val FILLET_SEGMENTS = 6

    /**
     * Right-angled routing: out of the pin, across, and in.
     *
     * Two shapes, because a wire that runs backwards cannot be an S. Forwards, it leaves the output, turns
     * once at the midpoint and arrives level — one vertical segment, which is what makes a column of these
     * read as a bus. Backwards (a loop's return, a value read by something to its left) there is no room
     * between the pins, so it steps out, runs along a lane between the two rows, and comes back in.
     *
     * The stubs matter more than they look: leaving the pin horizontally for a few pixels before turning is
     * what stops the corner landing ON the pin, where it reads as a wire attached to the node's edge rather
     * than to a specific pin.
     */
    private fun orthogonal(cam: ScreenCamera, x1: Float, y1: Float, x4: Float, y4: Float): FloatArray {
        val stub = maxOf(8f, cam.px(18f))
        if (Math.abs(y1 - y4) < 0.5f) return floatArrayOf(x1, y1, x4, y4) // already level: one segment
        if (x4 - x1 > stub * 2f) {
            val mx = (x1 + x4) * 0.5f
            return floatArrayOf(x1, y1, mx, y1, mx, y4, x4, y4)
        }
        val out = x1 + stub
        val inn = x4 - stub
        val my = (y1 + y4) * 0.5f
        return floatArrayOf(x1, y1, out, y1, out, my, inn, my, inn, y4, x4, y4)
    }

    private fun flattenCubic(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float,
        segments: Int = 32,
    ): FloatArray {
        val out = FloatArray((segments + 1) * 2)
        for (i in 0..segments) {
            val (px, py) = bezier(x1, y1, x2, y2, x3, y3, x4, y4, i.toFloat() / segments)
            out[i * 2] = px
            out[i * 2 + 1] = py
        }
        return out
    }

    /** Sample [p] at [t] by ARC LENGTH — used by hit-testing, the marquee, arrows and value pills. */
    fun curvePoint(p: FloatArray, t: Float): Pair<Float, Float> {
        val target = totalLength(p) * t.coerceIn(0f, 1f)
        var walked = 0f
        for (i in 0 until p.size / 2 - 1) {
            val len = segLength(p, i)
            if (walked + len >= target || i == p.size / 2 - 2) {
                val f = if (len <= 1e-4f) 0f else (target - walked) / len
                return (p[i * 2] + (p[i * 2 + 2] - p[i * 2]) * f) to
                    (p[i * 2 + 1] + (p[i * 2 + 3] - p[i * 2 + 1]) * f)
            }
            walked += len
        }
        return p[0] to p[1]
    }

    /** The direction the wire is heading at [t], for orienting a flow arrow. */
    fun curveTangent(p: FloatArray, t: Float): Pair<Float, Float> {
        val target = totalLength(p) * t.coerceIn(0f, 1f)
        var walked = 0f
        for (i in 0 until p.size / 2 - 1) {
            val len = segLength(p, i)
            if (walked + len >= target || i == p.size / 2 - 2) {
                return (p[i * 2 + 2] - p[i * 2]) to (p[i * 2 + 3] - p[i * 2 + 1])
            }
            walked += len
        }
        return 1f to 0f
    }

    private fun segLength(p: FloatArray, i: Int): Float {
        val dx = p[i * 2 + 2] - p[i * 2]
        val dy = p[i * 2 + 3] - p[i * 2 + 1]
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun totalLength(p: FloatArray): Float {
        var sum = 0f
        for (i in 0 until p.size / 2 - 1) sum += segLength(p, i)
        return sum.coerceAtLeast(1e-4f)
    }

    /** Draw a wire from a path already computed — see [routedPath]. */
    fun link(
        dl: ImDrawList,
        cam: ScreenCamera,
        p: FloatArray,
        type: TypeRef,
        highlighted: Boolean,
        flowPhase: Float?,
    ) {

        val col = PinStyle.color(type)
        val base = if (type.isExec) 2.4f else 1.8f
        // A selected wire keeps its type colour and gains a halo behind it, rather than being recoloured:
        // recolouring would hide what the wire carries exactly when you are inspecting it.
        if (highlighted) polyline(dl, p, Theme.withAlpha(Theme.ACCENT, 0.55f), maxOf(3f, cam.px(base) * 3f))
        polyline(dl, p, col, maxOf(1f, cam.px(base)))

        // Marching arrowheads along the wire while control is flowing through it. One filled triangle
        // each, oriented by the tangent — by far the best effect-per-line available here.
        if (flowPhase != null) {
            for (i in 0 until FLOW_ARROWS) {
                val t = ((flowPhase + i.toFloat() / FLOW_ARROWS) % 1f)
                val (px, py) = curvePoint(p, t)
                val (tx, ty) = curveTangent(p, t)
                val len = Math.sqrt((tx * tx + ty * ty).toDouble()).toFloat().coerceAtLeast(1e-4f)
                val ux = tx / len; val uy = ty / len
                val size = maxOf(3f, cam.px(5f + 2.5f * Math.sin(t * Math.PI).toFloat()))
                arrow(dl, px, py, ux, uy, size, col)
            }
        }
    }

    /**
     * A reroute: a bead on the wire, in the colour of what it carries.
     *
     * Deliberately small and unlabelled. A knot is punctuation — it says "the wire continues here" — and
     * anything that reads as a node invites you to wonder what it does, which is nothing.
     */
    private fun knot(dl: ImDrawList, cam: ScreenCamera, g: NodeGeometry, v: NodeVisual) {
        val pin = g.inputAnchors.firstOrNull() ?: return
        val cx = cam.toScreenX(pin.x)
        val cy = cam.toScreenY(pin.y)
        val r = maxOf(3f, cam.px(NodeGeometry.KNOT * 0.34f))
        val col = PinStyle.color(pin.type)
        if (v.selected || v.hovered) {
            dl.addCircleFilled(cx, cy, r + maxOf(2f, cam.px(3f)), Theme.withAlpha(Theme.ACCENT, 0.5f), 16)
        }
        // A dark rim so the bead stays visible where it sits on top of its own wire.
        dl.addCircleFilled(cx, cy, r + maxOf(1f, cam.px(1.5f)), PinStyle.CANVAS_BG, 16)
        dl.addCircleFilled(cx, cy, r, col, 16)
    }

    /** Draw a flattened path. Segment by segment rather than `addPolyline`, whose imgui-java signature
     *  wants a native array we would have to marshal every frame for every wire. */
    private fun polyline(dl: ImDrawList, p: FloatArray, col: Int, thickness: Float) {
        for (i in 0 until p.size / 2 - 1) {
            dl.addLine(p[i * 2], p[i * 2 + 1], p[i * 2 + 2], p[i * 2 + 3], col, thickness)
        }
        // Round the joints so a thick right angle does not show a notch on its outside corner.
        if (thickness > 2f) {
            for (i in 1 until p.size / 2 - 1) {
                dl.addCircleFilled(p[i * 2], p[i * 2 + 1], thickness * 0.5f, col, 8)
            }
        }
    }

    /**
     * A link being dragged — tinted by whether it would be accepted, which the reference never showed.
     *
     * [fromInput] mirrors the curve. A finished link always runs output (right edge) to input (left edge),
     * so its control points push right then pull left; but a drag can START at either end. Dragging from an
     * INPUT pin with the output-shaped curve sends the wire back across its own node before turning round,
     * which looks like it is attached to the wrong side. Flipping both offsets makes a drag from an input
     * leave leftwards and arrive as if the cursor were the output — the exact mirror of the other case.
     */
    fun pendingLink(
        dl: ImDrawList,
        cam: ScreenCamera,
        fromX: Float, fromY: Float, toScreenX: Float, toScreenY: Float,
        fromInput: Boolean,
        valid: Boolean?,
    ) {
        val x1 = cam.toScreenX(fromX); val y1 = cam.toScreenY(fromY)
        val d = cam.px(controlOffset(fromX, cam.toGraphX(toScreenX))) * (if (fromInput) -1f else 1f)
        val col = when (valid) {
            true -> Theme.OK
            false -> PinStyle.BORDER_ERROR
            null -> Theme.TEXT
        }
        dl.addBezierCubic(
            x1, y1, x1 + d, y1, toScreenX - d, toScreenY, toScreenX, toScreenY,
            col, maxOf(1.5f, cam.px(2f)), 24,
        )
    }

    private const val FLOW_ARROWS = 4

    private fun arrow(dl: ImDrawList, px: Float, py: Float, ux: Float, uy: Float, size: Float, col: Int) {
        val nx = -uy; val ny = ux
        dl.addTriangleFilled(
            px + ux * size, py + uy * size,
            px - ux * size * 0.4f + nx * size * 0.55f, py - uy * size * 0.4f + ny * size * 0.55f,
            px - ux * size * 0.4f - nx * size * 0.55f, py - uy * size * 0.4f - ny * size * 0.55f,
            col,
        )
    }

    private fun bezier(
        x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, t: Float,
    ): Pair<Float, Float> {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        return (a * x1 + b * x2 + c * x3 + d * x4) to (a * y1 + b * y2 + c * y3 + d * y4)
    }

    private fun bezierTangent(
        x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, t: Float,
    ): Pair<Float, Float> {
        val u = 1 - t
        val a = 3 * u * u; val b = 6 * u * t; val c = 3 * t * t
        return (a * (x2 - x1) + b * (x3 - x2) + c * (x4 - x3)) to (a * (y2 - y1) + b * (y3 - y2) + c * (y4 - y3))
    }

    /** Distance from a screen point to [c], for link hit-testing. */
    /**
     * Distance from a point to a wire — for hit-testing a click, in either style.
     *
     * Walks the path's OWN segments. It used to read the first eight floats as bezier control points, which
     * was right when a path *was* four control points and became quietly wrong once both styles started
     * flattening to a polyline: for a curve it interpolated the first four sampled points (near enough to
     * pass unnoticed), and for a right-angled wire it described a curve through the corners that went
     * nowhere near the drawn line. Which made those wires unclickable — and worse, made clicks on empty
     * canvas land on the phantom curve instead of starting a marquee.
     *
     * Segment distance rather than point distance, because a path's points can be far apart: an orthogonal
     * wire is four points and several hundred pixels, so sampling only the corners would leave the whole
     * length of it untouchable.
     */
    fun distanceToCurve(sx: Float, sy: Float, path: FloatArray): Float {
        if (path.size < 4) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        for (i in 0 until path.size / 2 - 1) {
            best = minOf(
                best,
                segmentDistance(sx, sy, path[i * 2], path[i * 2 + 1], path[i * 2 + 2], path[i * 2 + 3]),
            )
        }
        return best
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
        val cx = ax + t * dx; val cy = ay + t * dy
        return Math.sqrt(((px - cx) * (px - cx) + (py - cy) * (py - cy)).toDouble()).toFloat()
    }

    /** Screen-space marquee — not zoom-scaled, because it is a gesture rather than part of the graph. */
    fun marquee(dl: ImDrawList, x1: Float, y1: Float, x2: Float, y2: Float) {
        val l = minOf(x1, x2); val t = minOf(y1, y2)
        val r = maxOf(x1, x2); val b = maxOf(y1, y2)
        dl.addRectFilled(l, t, r, b, Theme.withAlpha(Theme.ACCENT, 0.14f))
        dl.addRect(l, t, r, b, Theme.ACCENT, 0f, ImDrawFlags.RoundCornersNone, 1.5f)
    }

    // ---- text -------------------------------------------------------------------------------------

    /**
     * Text with a dark copy beneath it.
     *
     * Two draw calls, and it makes a label readable over an arbitrary header colour without reserving a
     * backing plate or constraining the palette.
     */
    /**
     * As [shadowedText], but in a supplied face — an emphasis face from [Fonts.emphasis].
     *
     * [face] null falls back to the baked canvas ladder, so a caller can pass the result of a lookup that
     * may have missed without branching at every call site.
     */
    // ---- text ---------------------------------------------------------------------------------
    //
    // **Delegating to `dev.ziggle.imgui.TextPaint`, which is where these live now.** They were the last
    // thing tying the text-field widget to this module, and through it the last thing tying
    // `:editor-text` to `:editor-graph`. Nothing about measuring a string or drawing it with a shadow
    // was ever about a canvas.
    //
    // Kept under their old names because sixteen call sites in here use them and had every right to go
    // on doing so; renaming would have made the move look bigger than it is.

    fun shadowedText(dl: ImDrawList, x: Float, y: Float, size: Float, col: Int, s: String, face: imgui.ImFont?) =
        dev.ziggle.imgui.TextPaint.shadowed(dl, x, y, size, col, s, face)

    fun shadowedText(dl: ImDrawList, x: Float, y: Float, size: Float, col: Int, s: String) =
        dev.ziggle.imgui.TextPaint.shadowed(dl, x, y, size, col, s)

    fun textWidth(s: String, size: Float): Float = dev.ziggle.imgui.TextPaint.width(s, size)


    // ---- colour helpers ---------------------------------------------------------------------------

}
