package dev.ziggle.vscript.shell

import imgui.ImGui
import dev.ziggle.vscript.editor.text.CodeSidebar
import dev.ziggle.vscript.editor.text.CodeView
import dev.ziggle.vscript.editor.text.LineIndex
import dev.ziggle.vscript.model.NodeCatalog
import dev.ziggle.vscript.text.TextSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The code view, **clicked** rather than called.
 *
 * Every other test in this repo asserts on a model: give `LineBreakpoints` a line and it arms it. That
 * leaves the half that actually breaks untested — whether clicking beside line 6 reaches line 6's
 * breakpoint, or lands on line 5 because the gutter's geometry and the paint loop's disagree by a pad.
 * Those are the bugs that survive a green suite and get reported from a running window.
 *
 * `ImGuiHarness` closes that gap: the real widget code runs in a real frame with a real pointer position,
 * and the assertion is on what it changed. What it still cannot see is whether any of it looks right —
 * see the harness note.
 */
class CodeViewInteractionTest {

    private val SRC = """
        graph "probe"

        var Count: INT = 0

        fn helper(n: INT) -> INT = n + 1

        on start {
            Count = helper(n: 1)
            log("one")
            log("two")
        }
    """.trimIndent()

    private lateinit var view: CodeView

    /** Where the view is drawn. Not the origin, so an assertion cannot pass by ignoring the offset. */
    private val vx = 40f
    private val vy = 24f
    private val vw = 900f
    private val vh = 600f

    @BeforeEach
    fun setUp() {
        ImGuiHarness.start()
        view = CodeView(NodeCatalog(), TextSource.NONE)
        view.open(SRC)
        // One frame to lay out: the gutter width and the sidebar width are decided during a draw, and a
        // click before the first frame would be measured against zeroes.
        ImGuiHarness.frame { view.draw(vx, vy, vw, vh) }
    }

    private fun draw() = view.draw(vx, vy, vw, vh)

    /** The vertical middle of [line]'s row, in screen coordinates. */
    private fun rowY(line: Int): Float {
        val lh = ImGui.getFontSize() * dev.ziggle.imgui.TextEdit.LINE_SPACING
        val barH = lh + 12f
        return vy + barH + (line - 1) * lh + lh / 2f
    }

    private fun lineOf(needle: String): Int {
        val ix = LineIndex.of(SRC)
        return (1..ix.lineCount).first { needle in ix.textOf(SRC, it) }
    }

    // ---- the gutter ---------------------------------------------------------------------------------

    @Test
    fun `clicking the gutter beside a statement arms that line`() {
        val line = lineOf("""log("one")""")
        // Just inside the sidebar's right edge, in the gutter: x is the view's left plus the sidebar.
        val gutterX = vx + (vw * 0.22f).coerceIn(150f, 260f) + 6f

        assertTrue(view.breakpoints.isEmpty, "should start clean")
        ImGuiHarness.click(gutterX, rowY(line)) { draw() }

        assertEquals(setOf(line), view.breakpoints.lines, "the click did not arm the line it was on")
    }

    @Test
    fun `clicking it again disarms it`() {
        val line = lineOf("""log("one")""")
        val gutterX = vx + (vw * 0.22f).coerceIn(150f, 260f) + 6f
        ImGuiHarness.click(gutterX, rowY(line)) { draw() }
        assertEquals(setOf(line), view.breakpoints.lines)
        ImGuiHarness.click(gutterX, rowY(line)) { draw() }
        assertTrue(view.breakpoints.isEmpty, "a second click should toggle it off")
    }

    /** A blank line compiles to nothing that can be stopped at, so the click must be refused. */
    @Test
    fun `clicking beside a line with no site is refused`() {
        val blank = (1..LineIndex.of(SRC).lineCount).first { LineIndex.of(SRC).textOf(SRC, it).isBlank() }
        val gutterX = vx + (vw * 0.22f).coerceIn(150f, 260f) + 6f
        ImGuiHarness.click(gutterX, rowY(blank)) { draw() }
        assertTrue(view.breakpoints.isEmpty, "a dot that can never fire was accepted on line $blank")
    }

    // ---- the sidebar --------------------------------------------------------------------------------

    @Test
    fun `clicking the Structure tab switches the sidebar`() {
        assertEquals(CodeSidebar.View.FILES, view.sidebar.view)
        val lh = ImGui.getFontSize() * dev.ziggle.imgui.TextEdit.LINE_SPACING
        val sideW = (vw * 0.22f).coerceIn(150f, 260f)
        // The right half of the tab strip, which is Structure.
        ImGuiHarness.click(vx + sideW * 0.75f, vy + lh + 12f + (lh + 8f) / 2f) { draw() }
        assertEquals(CodeSidebar.View.STRUCTURE, view.sidebar.view, "the Structure tab did not take")
    }

    @Test
    fun `clicking a structure row moves the caret to that declaration`() {
        val lh = ImGui.getFontSize() * dev.ziggle.imgui.TextEdit.LINE_SPACING
        val sideW = (vw * 0.22f).coerceIn(150f, 260f)
        val tabH = lh + 8f
        val listTop = vy + lh + 12f + tabH

        ImGuiHarness.click(vx + sideW * 0.75f, vy + lh + 12f + tabH / 2f) { draw() }
        assertEquals(CodeSidebar.View.STRUCTURE, view.sidebar.view)

        // The rows are the outline in source order; find which one is `helper`.
        val outline = dev.ziggle.vscript.editor.text.Outline.of(
            dev.ziggle.vscript.lang.Parser(dev.ziggle.vscript.lang.Lexer(SRC).lex(), SRC).parse().program,
            LineIndex.of(SRC),
        )
        val row = outline.symbols.indexOfFirst { it.name == "helper" }
        assertTrue(row >= 0, "the fixture should declare `helper`")

        ImGuiHarness.click(vx + sideW / 2f, listTop + row * lh + lh / 2f) { draw() }

        val caretLine = view.buffer.lines.lineAt(view.caretOffset)
        assertEquals(
            lineOf("fn helper"), caretLine,
            "clicking `helper` in the structure view put the caret on line $caretLine",
        )
    }

    // ---- Ctrl+click, which is what actually gets used ------------------------------------------------

    /** Where the text column starts: past the sidebar, past the gutter, past the pad. */
    private fun textX(column: Int): Float {
        val sideW = (vw * 0.22f).coerceIn(150f, 260f)
        val gutterW = ImGui.calcTextSize("${LineIndex.of(SRC).lineCount}").x + 18f
        return vx + sideW + gutterW + 4f + ImGui.calcTextSize(" ".repeat(column)).x
    }

    /**
     * **Ctrl+click on a use jumps to the declaration — and does NOT select.**
     *
     * The bug this pins was not "nothing happens". The jump worked, and then the same click, still held,
     * dragged a selection from the declaration back to the pointer on the next frame — so the file
     * appeared selected from somewhere above down to where you clicked and the jump was invisible
     * underneath it. Both halves are asserted, because fixing only the visible half would leave a click
     * that navigates and then quietly ruins your selection.
     */
    @Test
    fun `ctrl-clicking a use jumps to the declaration without selecting`() {
        val useLine = lineOf("Count = helper")
        val ix = LineIndex.of(SRC)
        val col = ix.textOf(SRC, useLine).indexOf("helper")
        assertTrue(col > 0, "the fixture should call `helper`")

        ImGuiHarness.click(textX(col + 1), rowY(useLine), ctrl = true) { draw() }

        val landed = view.buffer.lines.lineAt(view.caretOffset)
        assertEquals(lineOf("fn helper"), landed, "Ctrl+click did not reach the declaration")
        assertTrue(!view.hasSelection, "the click left a selection behind — the drag was not suppressed")
    }

    @Test
    fun `a plain click still places the caret`() {
        // The rule Ctrl is carved out of: an ordinary click in a text area must keep doing what it does.
        val line = lineOf("""log("two")""")
        ImGuiHarness.click(textX(6), rowY(line)) { draw() }
        assertEquals(line, view.buffer.lines.lineAt(view.caretOffset), "a plain click stopped placing the caret")
    }

    @Test
    fun `ctrl-clicking whitespace does nothing at all`() {
        val before = view.caretOffset
        val blank = (1..LineIndex.of(SRC).lineCount).first { LineIndex.of(SRC).textOf(SRC, it).isBlank() }
        ImGuiHarness.click(textX(2), rowY(blank), ctrl = true) { draw() }
        assertEquals(before, view.caretOffset, "Ctrl+click on nothing moved the caret")
        assertTrue(!view.hasSelection)
    }
}
