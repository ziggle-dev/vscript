package dev.ziggle.vscript.editor

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import dev.ziggle.imgui.Theme

/**
 * The node editor's top bar.
 *
 * **Grouped by space, not by chrome.** The bar it replaces put file operations, execution, view operations,
 * editing, canvas identity, history and status in one undifferentiated row of bordered buttons — seven
 * different concerns reading as one. Here the concerns are separated by whitespace and hairline rules, and
 * every control loses its border: at rest a ghost button is just an icon, and a faint plate appears only
 * under the cursor. Removing chrome is what lets grouping be visible at all; with a box around everything,
 * space between the boxes reads as nothing.
 *
 * **One promoted action.** Run is the thing you press constantly, so it is the only saturated fill in the
 * bar. Everything else is a ghost. The accent is deliberately a colour the graph never uses, so a filled
 * teal control cannot be mistaken for a node category.
 *
 * **Disabled means dimmed, not absent.** Redo with nothing to redo stays a full-size icon at low contrast.
 * Rendering it as bare text (or omitting it) makes the bar reflow as history changes, and reads as a
 * rendering fault rather than a state.
 *
 * Every control is exactly [BTN] tall so the row aligns without anyone reasoning about baselines, and
 * layout is done in screen coordinates so [spring] can pin a cluster to the right edge.
 */
class Toolbar {

    private var barX = 0f
    private var barY = 0f
    private var barW = 0f

    /** Start the bar: paint its plate and place the cursor at the first control. */
    fun begin() {
        barX = ImGui.getCursorScreenPosX()
        barY = ImGui.getCursorScreenPosY()
        barW = ImGui.getContentRegionAvailX()
        val dl = ImGui.getWindowDrawList()
        // A step lighter than the canvas rather than a bordered strip: the bar reads as raised because it
        // is lighter, which needs no outline to say so.
        dl.addRectFilled(barX, barY, barX + barW, barY + H, BAR_BG)
        dl.addLine(barX, barY + H - 0.5f, barX + barW, barY + H - 0.5f, HAIRLINE, 1f)
        ImGui.setCursorScreenPos(barX + EDGE, barY + (H - BTN) * 0.5f)
    }

    /** Finish the bar and put the layout cursor underneath it. */
    fun end() {
        ImGui.setCursorScreenPos(barX, barY + H + BELOW)
    }

    /** Spacing between controls that belong to the same cluster. */
    fun gap(px: Float = CLUSTER_GAP) = ImGui.sameLine(0f, px)

    /** A hairline rule between two clusters, with its own breathing room on each side. */
    fun divider() {
        ImGui.sameLine(0f, DIV_MARGIN)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        ImGui.dummy(1f, BTN)
        ImGui.getWindowDrawList()
            .addLine(x + 0.5f, y + (BTN - RULE_H) * 0.5f, x + 0.5f, y + (BTN + RULE_H) * 0.5f, RULE, 1f)
        ImGui.sameLine(0f, DIV_MARGIN)
    }

    /**
     * Jump the cursor so that a cluster of [rightWidth] pixels ends flush with the bar's right edge.
     *
     * Without this the whole bar crams into the left third and leaves a screen-wide void — and status,
     * which is a *result*, ends up sitting in the middle of a row of *actions*.
     */
    fun spring(rightWidth: Float) {
        ImGui.sameLine(0f, 0f)
        val y = ImGui.getCursorScreenPosY()
        val used = ImGui.getCursorScreenPosX()
        ImGui.setCursorScreenPos(maxOf(used + MIN_SPRING, barX + barW - EDGE - rightWidth), y)
    }

    // ---- controls ---------------------------------------------------------------------------------

    /** A borderless square icon button. Nothing at rest, a faint plate on hover. */
    /**
     * A bare icon button.
     *
     * **Does not place itself.** Like every control here it draws at the layout cursor and leaves it on the
     * next LINE, so two in a row need a [gap] between them — `divider` does the same job at a cluster
     * boundary. Forgetting it does not misalign one button: the offender and *everything after it* wrap off
     * the bar and draw underneath whatever is below, which reads as the toolbar having lost its contents.
     */
    fun icon(id: String, glyph: String, tip: String = "", enabled: Boolean = true, resting: Boolean = false): Boolean {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, BTN, BTN)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        plate(dl, x, y, BTN, enabled && hovered, enabled && ImGui.isItemActive(), resting)
        centered(dl, x, y, BTN, glyph, tint(enabled, hovered))
        tooltip(hovered, tip)
        return pressed && enabled
    }

    /**
     * A borderless button with a leading icon and a label.
     *
     * [resting] keeps the plate on when the cursor is elsewhere — the secondary tier between a ghost and
     * the primary action. Check earns it because it sits beside Run and reads as part of the same control
     * group; a bare icon-and-text next to a filled button looks like a label that happens to be clickable.
     */
    fun button(
        id: String,
        glyph: String,
        label: String,
        tip: String = "",
        enabled: Boolean = true,
        resting: Boolean = false,
    ): Boolean {
        val w = buttonWidth(glyph, label)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, w, BTN)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        plate(dl, x, y, w, enabled && hovered, enabled && ImGui.isItemActive(), resting)
        labelled(dl, x, y, glyph, label, tint(enabled, hovered))
        tooltip(hovered, tip)
        return pressed && enabled
    }

    /**
     * The one coloured control in the bar.
     *
     * **A tinted plate, not a solid block.** Every other control here is a translucent wash on a dark bar,
     * so a saturated fill with near-black text reads as a web call-to-action that wandered in — the glyph
     * inside it looks punched out rather than drawn, and the whole thing sits in a different visual
     * language from its neighbours. The promotion instead comes from being the only thing in the bar with
     * a *hue*: the same wash treatment as a ghost button, at a stronger level, plus a tinted border and
     * tinted text. Ghost (nothing) → resting (faint white) → primary (tinted, bordered) is one ladder
     * rather than two idioms.
     *
     * [tint] is passed rather than fixed so Run can become Stop without becoming a different *kind* of
     * control — the primary action stays in the same place, the same size, with the same weight.
     */
    fun primary(
        id: String,
        glyph: String,
        label: String,
        tint: Int = ACCENT,
        tip: String = "",
        minWidth: Float = 0f,
    ): Boolean {
        val w = maxOf(buttonWidth(glyph, label) + PRIMARY_EXTRA, minWidth)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, w, BTN)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        val wash = when {
            ImGui.isItemActive() -> 0.40f
            hovered -> 0.30f
            else -> 0.20f
        }
        shadow(dl, x, y, w)
        dl.addRectFilled(x, y, x + w, y + BTN, Theme.withAlpha(tint, wash), ROUND, ImDrawFlags.RoundCornersAll)
        dl.addRect(
            x, y, x + w, y + BTN, Theme.withAlpha(tint, if (hovered) 0.85f else 0.6f),
            ROUND, ImDrawFlags.RoundCornersAll, 1f,
        )
        // CENTRED, not left-padded. The caller pins Run and Stop to one width so the bar cannot reflow as
        // the script starts; left-aligning inside that width would just move the shift from the button's
        // edge to its label, which is the more distracting of the two.
        //
        // Lightened rather than used raw: a mid-saturation hue that works as a fill is too dark to read as
        // text on the plate it just tinted.
        val inner = (w - PAD_X * 2 - contentWidth(glyph, label)) * 0.5f
        labelled(dl, x, y, glyph, label, Theme.shade(tint, 1.3f), inner)
        tooltip(hovered, tip)
        return pressed
    }

    /** Static text, vertically centred on the row. */
    fun text(s: String, color: Int) {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val ts = ImGui.calcTextSize(s)
        ImGui.dummy(ts.x, BTN)
        ImGui.getWindowDrawList().addText(x, y + (BTN - ts.y) * 0.5f, color, s)
    }

    /** Text that acts as a button — used for the breadcrumb's document selector. */
    fun textButton(id: String, s: String, color: Int, tip: String = ""): Boolean {
        val ts = ImGui.calcTextSize(s)
        val w = ts.x + TEXT_BTN_PAD * 2
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, w, BTN)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        plate(dl, x, y, w, hovered, ImGui.isItemActive(), false)
        dl.addText(x + TEXT_BTN_PAD, y + (BTN - ts.y) * 0.5f, if (hovered) TEXT_BRIGHT else color, s)
        tooltip(hovered, tip)
        return pressed
    }

    /** A small filled circle — the state carrier in a status pill, and the unsaved marker. */
    fun dot(color: Int, radius: Float = 3f) {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        ImGui.dummy(radius * 2f, BTN)
        ImGui.getWindowDrawList().addCircleFilled(x + radius, y + BTN * 0.5f, radius, color, 12)
    }

    /**
     * Status as a dot plus a label, sat next to the run controls so cause and effect are adjacent.
     *
     * The dot carries the state and the word merely names it, which is why the same component serves
     * stopped, running and errored without changing shape.
     */
    fun statusPill(color: Int, label: String, tip: String = "", count: String = ""): Boolean {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val w = pillWidth(label, count)
        val clicked = ImGui.invisibleButton("##vs-status", w, BTN)
        val hovered = ImGui.isItemHovered()
        val dl = ImGui.getWindowDrawList()
        // A plate only on hover, so at rest it still reads as a readout rather than a control — but it IS
        // one, and the count is the only chrome the console needs when it has nothing to say.
        if (hovered) dl.addRectFilled(x - 5f, y, x + w + 5f, y + BTN, Theme.GHOST_HOVER, ROUND, ImDrawFlags.RoundCornersAll)
        dl.addCircleFilled(x + DOT_R, y + BTN * 0.5f, DOT_R, color, 12)
        val ts = ImGui.calcTextSize(label)
        var tx = x + DOT_R * 2f + PILL_GAP
        dl.addText(tx, y + (BTN - ts.y) * 0.5f, if (hovered) TEXT_BRIGHT else MUTED, label)
        if (count.isNotEmpty()) {
            tx += ts.x + COUNT_GAP
            dl.addText(tx, y + (BTN - ts.y) * 0.5f, CRUMB, "·")
            dl.addText(tx + ImGui.calcTextSize("·").x + COUNT_GAP, y + (BTN - ts.y) * 0.5f, color, count)
        }
        tooltip(hovered, tip)
        return clicked
    }

    // ---- measurement ------------------------------------------------------------------------------
    //
    // Right-aligning a cluster means knowing its width before drawing it, so every control that can appear
    // on the right publishes one.

    /** Icon plus gap plus label — everything inside the padding. */
    private fun contentWidth(glyph: String, label: String): Float {
        val g = if (glyph.isEmpty()) 0f else ImGui.calcTextSize(glyph).x + ICON_GAP
        return g + ImGui.calcTextSize(label).x
    }

    fun buttonWidth(glyph: String, label: String): Float = PAD_X * 2 + contentWidth(glyph, label)

    fun primaryWidth(glyph: String, label: String): Float = buttonWidth(glyph, label) + PRIMARY_EXTRA

    fun pillWidth(label: String, count: String = ""): Float {
        val extra = if (count.isEmpty()) 0f else {
            COUNT_GAP * 2 + ImGui.calcTextSize("·").x + ImGui.calcTextSize(count).x
        }
        return DOT_R * 2f + PILL_GAP + ImGui.calcTextSize(label).x + extra
    }

    val iconWidth: Float get() = BTN
    val dividerWidth: Float get() = 1f + DIV_MARGIN * 2f
    val clusterGap: Float get() = CLUSTER_GAP

    // ---- painting helpers -------------------------------------------------------------------------

    /**
     * The plate behind a control.
     *
     * [resting] is a third, dimmer level rather than the same fill as hover — if a resting control already
     * showed the hover colour, hovering it would give no feedback at all.
     */
    private fun plate(dl: ImDrawList, x: Float, y: Float, w: Float, hovered: Boolean, active: Boolean, resting: Boolean) {
        val bg = when {
            active -> GHOST_ACTIVE
            hovered -> Theme.GHOST_HOVER
            resting -> Theme.GHOST_REST
            else -> return
        }
        shadow(dl, x, y, w)
        dl.addRectFilled(x, y, x + w, y + BTN, bg, ROUND, ImDrawFlags.RoundCornersAll)
    }

    /**
     * A short drop shadow under a control.
     *
     * A 5%-white plate on a bar that is itself only a step lighter than the canvas is a very small contrast
     * step, and Check was genuinely hard to find. Rather than making the fill louder — which would put it
     * in competition with the one action that is supposed to be loud — the plate is lifted off the bar:
     * a soft spread plus a tight offset copy, which reads as depth at almost no visual weight.
     */
    private fun shadow(dl: ImDrawList, x: Float, y: Float, w: Float) {
        dl.addRectFilled(x - 1.5f, y - 0.5f, x + w + 1.5f, y + BTN + 3.5f, SHADOW_SOFT, ROUND + 2f, ImDrawFlags.RoundCornersAll)
        dl.addRectFilled(x, y + 1f, x + w, y + BTN + 1.5f, SHADOW_TIGHT, ROUND, ImDrawFlags.RoundCornersAll)
    }

    private fun centered(dl: ImDrawList, x: Float, y: Float, w: Float, s: String, col: Int) {
        val ts = ImGui.calcTextSize(s)
        dl.addText(x + (w - ts.x) * 0.5f, y + (BTN - ts.y) * 0.5f, col, s)
    }

    private fun labelled(dl: ImDrawList, x: Float, y: Float, glyph: String, label: String, col: Int, extra: Float = 0f) {
        var tx = x + PAD_X + extra
        if (glyph.isNotEmpty()) {
            val gs = ImGui.calcTextSize(glyph)
            dl.addText(tx, y + (BTN - gs.y) * 0.5f, col, glyph)
            tx += gs.x + ICON_GAP
        }
        val ts = ImGui.calcTextSize(label)
        dl.addText(tx, y + (BTN - ts.y) * 0.5f, col, label)
    }

    private fun tint(enabled: Boolean, hovered: Boolean): Int = when {
        !enabled -> ICON_OFF
        hovered -> TEXT_BRIGHT
        else -> ICON
    }

    private fun tooltip(hovered: Boolean, tip: String) {
        if (hovered && tip.isNotEmpty()) ImGui.setTooltip(tip)
    }

    companion object {
        /** Bar height, and the side of every control in it. */
        const val H = 36f
        const val BTN = 28f

        /** Matches the node corner radius, so the bar looks native to the canvas below it. */
        const val ROUND = 5f

        const val EDGE = 8f
        const val BELOW = 2f
        const val CLUSTER_GAP = 2f

        /** Between two related but independently consequential controls — Check and Run. */
        const val PAIR_GAP = 10f
        const val DIV_MARGIN = 8f
        const val RULE_H = 18f
        const val PAD_X = 10f
        const val ICON_GAP = 5f
        const val TEXT_BTN_PAD = 4f
        const val PRIMARY_EXTRA = 4f
        const val DOT_R = 3f
        const val PILL_GAP = 6f
        const val COUNT_GAP = 5f

        /** Minimum space kept between the left clusters and a right-aligned one on a narrow panel. */
        const val MIN_SPRING = 12f

        // A ghost button shows ~5% white on hover and ~10% while held; nothing at rest. Borders are what
        // made the old bar read as a wall of controls, so there are none.
        val SHADOW_SOFT = Theme.col(0x00, 0x00, 0x00, 0x2E)
        val SHADOW_TIGHT = Theme.col(0x00, 0x00, 0x00, 0x40)

        val GHOST_ACTIVE = Theme.col(0xFF, 0xFF, 0xFF, 0x22)
        val HAIRLINE = Theme.col(0xFF, 0xFF, 0xFF, 0x10)
        val RULE = Theme.col(0xFF, 0xFF, 0xFF, 0x17)
        val BAR_BG = Theme.col(0x1B, 0x1F, 0x27)

        val ICON = Theme.col(0x9A, 0xA3, 0xB2)
        val ICON_OFF = Theme.col(0x4B, 0x51, 0x5D)
        val TEXT_BRIGHT = Theme.col(0xE4, 0xE7, 0xEC)
        val MUTED = Theme.col(0x7D, 0x86, 0x95)
        val CRUMB = Theme.col(0x5F, 0x67, 0x75)
        val DIRTY = Theme.col(0xE0, 0xA3, 0x39)
        val IDLE_DOT = Theme.col(0x6B, 0x72, 0x80)

        /**
         * The chrome accent — the rail's blue, so the editor reads as part of the client rather than as a
         * guest with its own palette. It is the ONE saturated fill in the bar, which is what lets a filled
         * control mean *primary action* and nothing else.
         */
        val ACCENT = Theme.ACCENT
        val STOP = Theme.col(0xE0, 0x7A, 0x6A)
    }
}
