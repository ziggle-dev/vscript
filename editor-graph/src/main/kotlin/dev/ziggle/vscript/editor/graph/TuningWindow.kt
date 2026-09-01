package dev.ziggle.vscript.editor.graph

import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiWindowFlags
import dev.ziggle.imgui.DrawKit
import dev.ziggle.imgui.Theme

/**
 * A window over the canvas for [Tuning] — drag a slider, watch the wires move.
 *
 * Floating rather than docked into the sidebar on purpose: the whole point is to see the change, so it has
 * to sit somewhere you can put it out of the way of the part of the graph you are judging.
 *
 * Each row shows what it was compiled with beside what it is now, because "is this better?" is a comparison
 * and a slider on its own does not offer one. Reset puts a single knob back; the header's Reset All puts
 * everything back.
 */
class TuningWindow {

    var open: Boolean = false
        private set

    fun toggle() { open = !open }

    fun render() {
        if (!open) return
        ImGui.setNextWindowSize(400f, 560f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSizeConstraints(320f, 220f, Float.MAX_VALUE, Float.MAX_VALUE)
        // No collapse: a collapsed tuning window is a window that is not doing its job, and closing it is
        // one click away anyway.
        if (!ImGui.begin("Wire tuning###vs-tuning", ImGuiWindowFlags.NoCollapse)) {
            ImGui.end()
            return
        }
        header()
        ImGui.separator()

        for (group in Tuning.groups) {
            ImGui.textDisabled(group)
            ImGui.spacing()
            for (k in Tuning.all.filter { it.group == group }) row(k)
            ImGui.spacing()
        }

        ImGui.separator()
        ImGui.textDisabled("Nothing here is saved. Copy takes the changed lines to the clipboard,")
        ImGui.textDisabled("so what you settle on can go in the source with a note saying why.")
        ImGui.end()
    }

    private fun header() {
        val dirty = Tuning.dirty()
        ImGui.text(if (dirty) "Changed from the built-in values" else "As built")
        ImGui.sameLine()
        val avail = ImGui.getContentRegionAvailX()
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (avail - 190f).coerceAtLeast(0f))
        if (DrawKit.button("##vs-tune-copy", "Copy", 60f)) {
            ImGui.setClipboardText(Tuning.changedAsSource())
        }
        ImGui.sameLine()
        if (DrawKit.button("##vs-tune-reset", "Reset all", 70f)) Tuning.reset()
        ImGui.sameLine()
        if (DrawKit.button("##vs-tune-close", "Close", 50f)) open = false
    }

    private fun row(k: Tuning.Knob) {
        val changed = Tuning.changed(k)
        // The name, and what it was, so the slider is a comparison rather than just a number.
        ImGui.text(k.name)
        if (changed) {
            ImGui.sameLine()
            ImGui.textDisabled("was ${fmt(k.default, k.step)}")
            ImGui.sameLine()
            if (DrawKit.button("##vs-tune-undo-${k.name}", "↺", 24f)) Tuning.apply(k, k.default)
        }
        val decimals = if (k.step < 1.0) 2 else 0
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX())
        val v = DrawKit.sliderFloat(
            "##vs-tune-${k.name}", k.get(), k.min, k.max, decimals = decimals, step = k.step,
        )
        Tuning.apply(k, v)
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, Theme.TEXT_DIM)
        ImGui.textWrapped(k.about)
        ImGui.popStyleColor()
        ImGui.spacing()
    }

    private fun fmt(v: Double, step: Double): String =
        if (step < 1.0) String.format("%.2f", v) else Math.rint(v).toLong().toString()
}
