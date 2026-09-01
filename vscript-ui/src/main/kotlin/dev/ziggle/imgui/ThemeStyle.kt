package dev.ziggle.imgui

import imgui.ImGuiStyle
import imgui.flag.ImGuiCol

/**
 * Applies the vscript palette to Dear ImGui's global style, so the few places we still use stock
 * widgets (the script-selector combo, plugin text inputs, docked window frames) match the custom
 * [DrawKit] components. Called once when the imgui context is created.
 */
object ThemeStyle {
    fun apply(style: ImGuiStyle) {
        style.windowRounding = 6f
        style.frameRounding = 5f
        style.popupRounding = 5f
        style.grabRounding = 4f
        style.scrollbarRounding = 6f
        style.tabRounding = 5f
        style.windowBorderSize = 1f
        style.setWindowPadding(10f, 10f)
        style.setFramePadding(8f, 5f)
        style.setItemSpacing(8f, 7f)

        fun c(idx: Int, r: Int, g: Int, b: Int, a: Int = 255) = style.setColor(idx, r / 255f, g / 255f, b / 255f, a / 255f)
        c(ImGuiCol.WindowBg, 0x1A, 0x1C, 0x26, 245)
        c(ImGuiCol.ChildBg, 0, 0, 0, 0)
        c(ImGuiCol.PopupBg, 0x1A, 0x1C, 0x26, 252)
        c(ImGuiCol.Border, 0x32, 0x37, 0x49)
        c(ImGuiCol.FrameBg, 0x22, 0x25, 0x32)
        c(ImGuiCol.FrameBgHovered, 0x2B, 0x2F, 0x40)
        c(ImGuiCol.FrameBgActive, 0x35, 0x3B, 0x52)
        c(ImGuiCol.Button, 0x29, 0x2D, 0x3E)
        c(ImGuiCol.ButtonHovered, 0x35, 0x3B, 0x52)
        c(ImGuiCol.ButtonActive, 0x40, 0x47, 0x63)
        c(ImGuiCol.Header, 0x2B, 0x2F, 0x40)
        c(ImGuiCol.HeaderHovered, 0x35, 0x3B, 0x52)
        c(ImGuiCol.HeaderActive, 0x5B, 0x8C, 0xFF)
        c(ImGuiCol.Text, 0xE6, 0xE9, 0xF2)
        c(ImGuiCol.TextDisabled, 0x8A, 0x90, 0xA4)
        c(ImGuiCol.TitleBg, 0x16, 0x18, 0x20)
        c(ImGuiCol.TitleBgActive, 0x20, 0x24, 0x30)
        c(ImGuiCol.Tab, 0x20, 0x24, 0x30)
        c(ImGuiCol.TabHovered, 0x35, 0x3B, 0x52)
        c(ImGuiCol.TabActive, 0x2B, 0x2F, 0x40)
        c(ImGuiCol.CheckMark, 0x5B, 0x8C, 0xFF)
        c(ImGuiCol.SliderGrab, 0x5B, 0x8C, 0xFF)
        c(ImGuiCol.SliderGrabActive, 0x76, 0x9F, 0xFF)
        c(ImGuiCol.Separator, 0x32, 0x37, 0x49)
        c(ImGuiCol.ScrollbarBg, 0, 0, 0, 0)
        c(ImGuiCol.ScrollbarGrab, 0x32, 0x37, 0x49)
    }
}
