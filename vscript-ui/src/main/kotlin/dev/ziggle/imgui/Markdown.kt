package dev.ziggle.imgui

import imgui.ImFont
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.net.URI

/**
 * Renders a markdown document with Dear ImGui — used by the [Marketplace] to show a plugin's README.
 * Parses to a CommonMark AST and draws it block-by-block: headings (scaled font), word-wrapped paragraphs
 * with clickable links + inline `code`, fenced/indented code blocks (boxed, mono-ish), bullet/ordered
 * lists, block quotes, and horizontal rules. Only one font weight is bundled (Lato), so emphasis is
 * conveyed by colour rather than bold/italic glyphs. Robust: a parse failure falls back to raw wrapped text.
 */
object Markdown {
    private val parser: Parser = Parser.builder().build()

    private data class Span(val text: String, val code: Boolean, val link: String?)

    /** Draw [markdown] at the current cursor. Call inside a window/child that owns the width. */
    fun render(markdown: String) {
        val doc = runCatching { parser.parse(markdown) }.getOrNull()
        if (doc == null) { ImGui.textWrapped(markdown); return }
        var n = doc.firstChild
        while (n != null) { block(n); n = n.next }
    }

    private fun block(node: Node) {
        when (node) {
            is Heading -> {
                withFont(if (node.level <= 1) Fonts.heading else Fonts.subHeading) { inline(node) }
                ImGui.dummy(0f, 3f)
            }
            is Paragraph -> { inline(node); ImGui.dummy(0f, 4f) }
            is FencedCodeBlock -> codeBlock(node.literal)
            is IndentedCodeBlock -> codeBlock(node.literal)
            is BulletList -> listBlock(node, ordered = false)
            is OrderedList -> listBlock(node, ordered = true)
            is ThematicBreak -> DrawKit.separator()
            is BlockQuote -> {
                ImGui.indent(10f)
                var c = node.firstChild; while (c != null) { block(c); c = c.next }
                ImGui.unindent(10f)
            }
            else -> { var c = node.firstChild; while (c != null) { block(c); c = c.next } }
        }
    }

    private fun listBlock(list: Node, ordered: Boolean) {
        var item = list.firstChild
        var i = 1
        while (item != null) {
            ImGui.pushStyleColor(ImGuiCol.Text, Theme.TEXT_DIM)
            ImGui.text(if (ordered) "  $i. " else "  • ")
            ImGui.popStyleColor()
            ImGui.sameLine(0f, 0f)
            var c = item.firstChild
            while (c != null) { if (c is Paragraph) inline(c) else block(c); c = c.next }
            item = item.next; i++
        }
        ImGui.dummy(0f, 2f)
    }

    private fun codeBlock(literal: String) {
        val lines = literal.trimEnd('\n').split('\n')
        val h = lines.size.coerceIn(1, 18) * ImGui.getTextLineHeightWithSpacing() + 8f
        ImGui.pushStyleColor(ImGuiCol.ChildBg, Theme.CARD)
        ImGui.beginChild("##mdcode_${literal.hashCode()}", 0f, h, true)
        ImGui.pushStyleColor(ImGuiCol.Text, Theme.TEXT)
        for (l in lines) ImGui.text(l)
        ImGui.popStyleColor()
        ImGui.endChild()
        ImGui.popStyleColor()
        ImGui.dummy(0f, 3f)
    }

    /** Word-wrap the inline content of [parent], colouring links + code and making links clickable. */
    private fun inline(parent: Node) {
        val spans = ArrayList<Span>()
        collect(parent, code = false, link = null, spans)
        val words = ArrayList<Span>()
        for (s in spans) for (w in s.text.split(Regex("\\s+"))) if (w.isNotEmpty()) words.add(Span(w, s.code, s.link))
        if (words.isEmpty()) return

        val spaceW = ImGui.calcTextSize(" ").x
        val rightEdge = ImGui.getWindowPosX() + ImGui.getWindowContentRegionMaxX()
        for ((idx, w) in words.withIndex()) {
            drawWord(w)
            if (idx + 1 < words.size) {
                val nextW = ImGui.calcTextSize(words[idx + 1].text).x
                if (ImGui.getItemRectMaxX() + spaceW + nextW < rightEdge) ImGui.sameLine(0f, spaceW)
            }
        }
    }

    private fun drawWord(w: Span) {
        val color = when { w.link != null -> Theme.TEXT_ACCENT; w.code -> Theme.WARN; else -> Theme.TEXT }
        ImGui.pushStyleColor(ImGuiCol.Text, color)
        ImGui.text(w.text)
        ImGui.popStyleColor()
        if (w.link != null && ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
            ImGui.getWindowDrawList().addLine(
                ImGui.getItemRectMinX(), ImGui.getItemRectMaxY(),
                ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY(), Theme.TEXT_ACCENT,
            )
            if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) openInBrowser(w.link)
        }
    }

    private fun collect(node: Node, code: Boolean, link: String?, out: MutableList<Span>) {
        var c = node.firstChild
        while (c != null) {
            when (c) {
                is Text -> out.add(Span(c.literal, code, link))
                is Code -> out.add(Span(c.literal, true, link))
                is Emphasis, is StrongEmphasis -> collect(c, code, link, out)
                is Link -> collect(c, code, c.destination, out)
                is Image -> out.add(Span(dev.ziggle.imgui.Fonts.icon(dev.ziggle.imgui.Fonts.IMAGE, "[img]"), code, link))
                is SoftLineBreak, is HardLineBreak -> out.add(Span(" ", code, link))
                else -> collect(c, code, link, out)
            }
            c = c.next
        }
    }

    private inline fun withFont(font: ImFont?, body: () -> Unit) {
        if (font != null) ImGui.pushFont(font)
        body()
        if (font != null) ImGui.popFont()
    }

    private fun openInBrowser(url: String) = runCatching {
        val d = java.awt.Desktop.getDesktop()
        if (d.isSupported(java.awt.Desktop.Action.BROWSE)) d.browse(URI(url))
    }
}
