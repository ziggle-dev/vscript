package dev.ziggle.imgui

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiKey

/** What a field will accept. Applied to typing AND pasting, by validating the whole candidate string. */
enum class TextFilter {
    ANY,
    INTEGER,
    DECIMAL;

    /** Would [candidate] be a legal complete value? Partial states like "-" and "1." must pass, or you
     *  could never type your way to a valid one. */
    fun accepts(candidate: String): Boolean = when (this) {
        ANY -> true
        INTEGER -> candidate.matches(Regex("^-?\\d*$"))
        DECIMAL -> candidate.matches(Regex("^-?\\d*\\.?\\d*$"))
    }
}

/**
 * A text editor: caret, selection, word motion, clipboard — single line or multi-line.
 *
 * Hand-written because the canvas widgets are hand-drawn — ImGui's `InputText` cannot be positioned or
 * scaled inside a zooming canvas, and half an editor (a caret that only sits at the end, no selection, no
 * arrow keys) is worse than none: it looks like a text field and then refuses to behave like one.
 *
 * Selection is an **anchor plus a caret** rather than a start/end pair. That is what makes shift-extending
 * work in both directions without special cases — the anchor stays put, the caret moves, and the selection
 * is whatever lies between them regardless of which is larger.
 *
 * The state itself is line-agnostic: it holds one string that may contain newlines, and every offset is an
 * index into that string. Everything two-dimensional — which visual row a caret is on, what `Up` means —
 * comes from a [TextLayout] supplied by whoever is drawing, because only the drawing site knows the wrap
 * width and font size. That keeps one editor serving a 62px number field and a wrapped comment body without
 * either growing a special case.
 */
class TextEditState {
    private val sb = StringBuilder()

    /** Insertion point, 0..length. */
    var caret: Int = 0
        private set

    /** The fixed end of the selection. Equal to [caret] when nothing is selected. */
    var anchor: Int = 0
        private set

    /** Horizontal scroll in pixels, so a caret past the right edge stays visible. */
    internal var scrollPx: Float = 0f

    /** Vertical scroll in pixels, for a multi-line field taller than its box. */
    /**
     * **Public, and no longer an apology.**
     *
     * It was `internal` while this widget lived in the canvas module, and splitting `:editor-text` out
     * turned that into a compile error — which the ratchet recorded as evidence that the borrowing
     * reached into state, not just type names, and that phase 6 had more to replace than four
     * constructors.
     *
     * That reading was wrong in an instructive way. The state was never the canvas's; the WIDGET was
     * never the canvas's. Now that it sits in the kit, a consumer reading the scroll offset of a text
     * field it is drawing is ordinary API use, and `internal` would be wrong for the same reason it is
     * wrong on any other field of a shared widget.
     */
    var scrollY: Float = 0f

    /**
     * Column to aim for during vertical motion, in pixels.
     *
     * Without it, walking down through a short line and out the other side would drag the caret left and
     * leave it there — every editor remembers the column you started from until you move horizontally, and
     * one that doesn't feels broken in a way people notice immediately but rarely articulate.
     */
    internal var goalX: Float? = null

    val text: String get() = sb.toString()
    val length: Int get() = sb.length
    val hasSelection: Boolean get() = caret != anchor
    val selStart: Int get() = minOf(caret, anchor)
    val selEnd: Int get() = maxOf(caret, anchor)

    fun set(s: String, selectAll: Boolean = false) {
        sb.setLength(0)
        sb.append(s)
        caret = sb.length
        anchor = if (selectAll) 0 else caret
        scrollPx = 0f
        scrollY = 0f
        goalX = null
    }

    fun selectAll() {
        anchor = 0
        caret = sb.length
        goalX = null
    }

    fun moveTo(pos: Int, extend: Boolean) {
        caret = pos.coerceIn(0, sb.length)
        if (!extend) anchor = caret
        goalX = null
    }

    fun moveBy(delta: Int, extend: Boolean) {
        // Collapsing to an edge rather than moving is what an unmodified arrow does when text is selected:
        // it dismisses the selection to the side you pressed instead of nudging the caret from inside it.
        if (!extend && hasSelection) {
            moveTo(if (delta < 0) selStart else selEnd, false)
            return
        }
        moveTo(caret + delta, extend)
    }

    fun moveWord(dir: Int, extend: Boolean) = moveTo(if (dir < 0) wordLeft() else wordRight(), extend)

    /** Skip the run of separators, then the run of word characters — the usual ctrl-arrow behaviour. */
    private fun wordLeft(): Int {
        var i = caret
        while (i > 0 && !sb[i - 1].isLetterOrDigit()) i--
        while (i > 0 && sb[i - 1].isLetterOrDigit()) i--
        return i
    }

    private fun wordRight(): Int {
        var i = caret
        while (i < sb.length && !sb[i].isLetterOrDigit()) i++
        while (i < sb.length && sb[i].isLetterOrDigit()) i++
        return i
    }

    /** Insert [s], replacing any selection. Rejected wholesale if the result fails [filter]. */
    fun insert(s: String, filter: TextFilter) {
        if (s.isEmpty()) return
        val candidate = StringBuilder(sb)
        if (hasSelection) candidate.delete(selStart, selEnd)
        candidate.insert(if (hasSelection) selStart else caret, s)
        if (!filter.accepts(candidate.toString())) return
        val at = if (hasSelection) selStart else caret
        if (hasSelection) sb.delete(selStart, selEnd)
        sb.insert(at, s)
        caret = at + s.length
        anchor = caret
        goalX = null
    }

    fun deleteSelection(): Boolean {
        if (!hasSelection) return false
        val s = selStart
        sb.delete(s, selEnd)
        caret = s
        anchor = s
        goalX = null
        return true
    }

    fun backspace(word: Boolean) {
        if (deleteSelection()) return
        if (caret == 0) return
        val from = if (word) wordLeft() else caret - 1
        sb.delete(from, caret)
        caret = from
        anchor = caret
        goalX = null
    }

    fun deleteForward(word: Boolean) {
        if (deleteSelection()) return
        if (caret >= sb.length) return
        val to = if (word) wordRight() else caret + 1
        sb.delete(caret, to)
        anchor = caret
        goalX = null
    }

    fun selectedText(): String = if (hasSelection) sb.substring(selStart, selEnd) else ""
}

/**
 * Where each visual line of a wrapped string begins and ends.
 *
 * A *visual* line, not a logical one: a soft wrap and a typed newline both start a new row, and `Up` has to
 * move by what the reader sees rather than by where the newlines happen to be. Every entry carries its
 * absolute offsets into the original string, so caret arithmetic never has to reconstruct them.
 *
 * [Line.end] can be past the end of [Line.text] — trailing spaces at a wrap point are swallowed for
 * drawing but still belong to the line for caret purposes, so every offset in the string is reachable.
 */
class TextLayout internal constructor(
    val lines: List<Line>,
    val fontSize: Float,
    val lineHeight: Float,
    private val measure: (String) -> Float,
) {
    class Line(val start: Int, val end: Int, val text: String) {
        /** Last offset that sits on this line's drawn text. */
        val textEnd: Int get() = start + text.length
    }

    val height: Float get() = lines.size * lineHeight

    /**
     * Which line [caret] sits on.
     *
     * Searched backwards so a caret exactly on a boundary lands on the LATER line — which is what you want
     * after a soft wrap (the caret appears at the start of the row you are about to type into) and is
     * harmless after a hard newline, where the two offsets differ by the newline character itself.
     */
    fun lineIndexOf(caret: Int): Int {
        for (i in lines.indices.reversed()) if (caret >= lines[i].start) return i
        return 0
    }

    /** Pixel offset of [caret] from its line's left edge. */
    fun caretX(caret: Int): Float {
        val l = lines[lineIndexOf(caret)]
        val k = (caret - l.start).coerceIn(0, l.text.length)
        return measure(l.text.substring(0, k))
    }

    /** The offset on line [lineIdx] nearest the pixel column [x]. */
    fun caretAt(lineIdx: Int, x: Float): Int {
        val l = lines[lineIdx.coerceIn(0, lines.size - 1)]
        var best = 0
        var bestD = Float.MAX_VALUE
        for (k in 0..l.text.length) {
            val d = Math.abs(measure(l.text.substring(0, k)) - x)
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return l.start + best
    }

    fun lineStartOf(caret: Int): Int = lines[lineIndexOf(caret)].start

    fun lineEndOf(caret: Int): Int = lines[lineIndexOf(caret)].textEnd
}

/**
 * Key handling, mouse handling and painting for a focused [TextEditState].
 *
 * Split from the widgets so every field — a pin value, a comment heading, a comment body, the palette's
 * search box — gets identical editing behaviour rather than each re-implementing a subset of it.
 */
/**
 * A text FIELD: a caret, a selection, key handling and a wrapped layout.
 *
 * ### It was `TextEdit`, in the canvas
 *
 * Written for the node canvas's own inline fields, then reused whole by the code view — which is how
 * `:editor-text` came to declare `implementation project(':editor-graph')` and depend on the other
 * authoring surface in order to draw a line of text. The split plan called that dependency a debt with a
 * name, and `RatchetTest` pinned exactly what was borrowed so it could only shrink.
 *
 * Measured, it was not a canvas widget at all. The whole file imported ImGui and `Theme` and nothing
 * else; the ONLY thing tying it to the canvas was one **default argument** — `measure` fell back to
 * `TextPaint.width` — and the code view was already passing its own, because the canvas measures
 * a different font at a different size. So this is the move `PanelBits`, `EditorKeyboard` and
 * `PanelField` already made: a widget with no opinion about what is being edited belongs in the kit,
 * below both surfaces, rather than inside one of them.
 */
object TextEdit {

    private val SELECTION = Theme.col(0x3A, 0x62, 0xB8, 0xB0)

    /** Row pitch as a multiple of the font size. Shared by the layout and everything that draws it. */
    const val LINE_SPACING = 1.28f

    // ---- layout -----------------------------------------------------------------------------------

    /**
     * Greedy word-wrap [text] to [maxWidth], keeping absolute offsets.
     *
     * [measure] is injected so this is testable without a live ImGui font, and so the caller can guarantee
     * it is the *same* measurement the drawing uses — a layout that wraps at a slightly different width
     * than the painter puts the caret in the wrong place, which looks like a caret bug rather than a
     * measurement one.
     */
    fun layout(
        text: String,
        maxWidth: Float,
        fontSize: Float,
        // **Required, not defaulted.** It fell back to the canvas's own `TextPaint.width`, which
        // was this widget's one and only reason to live in `:editor-graph` — and a default that measures
        // the wrong font produces a subtly wrong layout rather than a compile error.
        measure: (String) -> Float,
    ): TextLayout {
        val lines = ArrayList<TextLayout.Line>()
        var paraStart = 0
        while (true) {
            val nl = text.indexOf('\n', paraStart)
            val paraEnd = if (nl < 0) text.length else nl
            wrapParagraph(text, paraStart, paraEnd, maxWidth, measure, lines)
            if (nl < 0) break
            paraStart = nl + 1
        }
        if (lines.isEmpty()) lines.add(TextLayout.Line(0, 0, ""))
        return TextLayout(lines, fontSize, fontSize * LINE_SPACING, measure)
    }

    private fun wrapParagraph(
        text: String,
        start: Int,
        end: Int,
        maxWidth: Float,
        measure: (String) -> Float,
        out: MutableList<TextLayout.Line>,
    ) {
        if (start >= end || maxWidth <= 0f) {
            out.add(TextLayout.Line(start, end, text.substring(start, end)))
            return
        }
        var lineStart = start
        var fits = -1 // exclusive end of the longest word-aligned prefix that fits
        var i = start
        while (i < end) {
            var j = i
            while (j < end && text[j] == ' ') j++
            while (j < end && text[j] != ' ') j++
            if (fits < 0 || measure(text.substring(lineStart, j)) <= maxWidth) {
                // `fits < 0` force-accepts the first word even when it is wider than the box: an unbreakable
                // token should overflow visibly, not wrap into an infinite loop of empty lines.
                fits = j
                i = j
            } else {
                var next = fits
                while (next < end && text[next] == ' ') next++
                out.add(TextLayout.Line(lineStart, next, text.substring(lineStart, fits)))
                lineStart = next
                fits = -1
                i = next
            }
        }
        out.add(TextLayout.Line(lineStart, end, text.substring(lineStart, end)))
    }

    // ---- keys -------------------------------------------------------------------------------------

    /**
     * Apply this frame's keyboard to [st].
     *
     * Pass [layout] to get multi-line behaviour: Up/Down walk visual rows, Home/End bound the row rather
     * than the whole value, and Enter inserts a newline (Ctrl+Enter commits instead). Leave it null and the
     * field behaves as one line, where Enter is the only sensible meaning for "done".
     *
     * @return true when the edit should be committed. Escape is reported separately by [cancelled].
     */
    fun handleKeys(
        st: TextEditState,
        filter: TextFilter,
        typed: String,
        layout: TextLayout? = null,
        /** Off when a composite widget has taken this frame's Ctrl+C / Ctrl+V — see [WidgetContext]. */
        clipboard: Boolean = true,
    ): Boolean {
        val io = ImGui.getIO()
        val ctrl = io.keyCtrl
        val shift = io.keyShift

        if (ctrl && ImGui.isKeyPressed(ImGuiKey.A)) {
            st.selectAll()
            return false
        }
        if (clipboard && ctrl && ImGui.isKeyPressed(ImGuiKey.C)) {
            if (st.hasSelection) ImGui.setClipboardText(st.selectedText())
            return false
        }
        if (clipboard && ctrl && ImGui.isKeyPressed(ImGuiKey.X)) {
            if (st.hasSelection) {
                ImGui.setClipboardText(st.selectedText())
                st.deleteSelection()
            }
            return false
        }
        if (clipboard && ctrl && ImGui.isKeyPressed(ImGuiKey.V)) {
            // Newlines survive only where they can be seen. In a one-line field a pasted newline would be
            // invisible and silently corrupt the value.
            runCatching { ImGui.getClipboardText() }.getOrNull()
                ?.replace("\r", "")
                ?.let { st.insert(if (layout == null) it.replace("\n", " ") else it, filter) }
            return false
        }

        // repeat = true so held keys keep moving, which is what makes navigation feel normal.
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow, true)) {
            if (ctrl) st.moveWord(-1, shift) else st.moveBy(-1, shift)
        }
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow, true)) {
            if (ctrl) st.moveWord(1, shift) else st.moveBy(1, shift)
        }
        if (layout != null) {
            if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) moveVertical(st, layout, -1, shift)
            if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) moveVertical(st, layout, 1, shift)
            if (ImGui.isKeyPressed(ImGuiKey.Home, true)) {
                st.moveTo(if (ctrl) 0 else layout.lineStartOf(st.caret), shift)
            }
            if (ImGui.isKeyPressed(ImGuiKey.End, true)) {
                st.moveTo(if (ctrl) st.length else layout.lineEndOf(st.caret), shift)
            }
        } else {
            if (ImGui.isKeyPressed(ImGuiKey.Home, true)) st.moveTo(0, shift)
            if (ImGui.isKeyPressed(ImGuiKey.End, true)) st.moveTo(st.length, shift)
        }
        if (ImGui.isKeyPressed(ImGuiKey.Backspace, true)) st.backspace(ctrl)
        if (ImGui.isKeyPressed(ImGuiKey.Delete, true)) st.deleteForward(ctrl)

        // Typed characters last, so a ctrl-shortcut in the same frame is not also inserted as text.
        if (typed.isNotEmpty() && !ctrl) st.insert(typed, filter)

        val enter = ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)
        if (!enter) return false
        // Multi-line: SHIFT+Enter adds a row, plain Enter finishes. Most notes are one line, so the common
        // case must not need a modifier to end — and a field that swallows Enter looks stuck.
        if (layout != null && shift) {
            st.insert("\n", filter)
            return false
        }
        return true
    }

    private fun moveVertical(st: TextEditState, layout: TextLayout, dir: Int, extend: Boolean) {
        val li = layout.lineIndexOf(st.caret)
        val x = st.goalX ?: layout.caretX(st.caret)
        val target = li + dir
        if (target < 0) st.moveTo(0, extend)
        else if (target >= layout.lines.size) st.moveTo(st.length, extend)
        else st.moveTo(layout.caretAt(target, x), extend)
        // Re-set AFTER moveTo, which clears it: every other motion should forget the column, this one must
        // keep it across an arbitrarily long run of Up/Down presses.
        st.goalX = x
    }

    fun cancelled(): Boolean = ImGui.isKeyPressed(ImGuiKey.Escape)

    // ---- mouse ------------------------------------------------------------------------------------

    /** The offset in a one-line string nearest pixel column [x] from its left edge. */
    fun caretAtX(s: String, x: Float, fontSize: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (k in 0..s.length) {
            val d = Math.abs(TextPaint.width(s.substring(0, k), fontSize) - x)
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return best
    }

    /**
     * Place (or, with [extend], drag) the caret from a mouse position inside a multi-line field.
     *
     * [x]/[y] are the field's top-left in screen space and [pad] its inset, so this undoes exactly the
     * transform [drawMultiline] applies — the two are written together on purpose.
     */
    fun caretFromMouse(
        st: TextEditState,
        layout: TextLayout,
        mouseX: Float, mouseY: Float,
        x: Float, y: Float,
        pad: Float,
        extend: Boolean,
    ) {
        val row = ((mouseY - (y + pad) + st.scrollY) / layout.lineHeight).toInt()
            .coerceIn(0, layout.lines.size - 1)
        st.moveTo(layout.caretAt(row, mouseX - (x + pad)), extend)
    }

    // ---- painting ---------------------------------------------------------------------------------

    /**
     * Draw the text, its selection and its caret inside a one-line field.
     *
     * Scrolls horizontally to keep the caret in view: without it a value longer than its field becomes
     * uneditable at the end, which is exactly where you type.
     */
    fun draw(
        dl: ImDrawList,
        st: TextEditState,
        x: Float, y: Float, w: Float, h: Float,
        pad: Float,
        fontSize: Float,
    ) {
        val avail = (w - pad * 2).coerceAtLeast(1f)
        val s = st.text
        val caretX = TextPaint.width(s.substring(0, st.caret.coerceIn(0, s.length)), fontSize)

        if (caretX - st.scrollPx > avail) st.scrollPx = caretX - avail
        if (caretX - st.scrollPx < 0f) st.scrollPx = caretX
        val total = TextPaint.width(s, fontSize)
        if (total - st.scrollPx < avail) st.scrollPx = (total - avail).coerceAtLeast(0f)

        val tx = x + pad - st.scrollPx
        val ty = y + (h - fontSize) * 0.5f

        dl.pushClipRect(x + pad, y, x + w - pad, y + h, true)
        if (st.hasSelection) {
            val a = TextPaint.width(s.substring(0, st.selStart), fontSize)
            val b = TextPaint.width(s.substring(0, st.selEnd), fontSize)
            dl.addRectFilled(tx + a, y + 2f, tx + b, y + h - 2f, SELECTION, 1f, ImDrawFlags.RoundCornersAll)
        }
        TextPaint.shadowed(dl, tx, ty, fontSize, Theme.TEXT, s)
        caret(dl, st, tx + caretX, y + 2f, y + h - 2f, fontSize)
        dl.popClipRect()
    }

    /** Where in a one-line field the caret sits, for a caller that needs to hit-test it. */
    fun singleLineTextX(st: TextEditState, x: Float, pad: Float): Float = x + pad - st.scrollPx

    /**
     * Draw a wrapped, scrolling, multi-line field.
     *
     * [layout] must be the one the caller also fed to [handleKeys] and [caretFromMouse] this frame, so what
     * is drawn, what the arrows walk and what the mouse hits are all the same geometry.
     */
    fun drawMultiline(
        dl: ImDrawList,
        st: TextEditState,
        layout: TextLayout,
        x: Float, y: Float, w: Float, h: Float,
        pad: Float,
        color: Int = Theme.TEXT,
    ) {
        val fontSize = layout.fontSize
        val lh = layout.lineHeight
        val viewH = (h - pad * 2).coerceAtLeast(lh)

        // Keep the caret's row inside the box. Clamped after, so deleting a long body cannot leave the view
        // scrolled past the end with nothing on screen.
        val caretRow = layout.lineIndexOf(st.caret)
        val caretTop = caretRow * lh
        if (caretTop < st.scrollY) st.scrollY = caretTop
        if (caretTop + lh > st.scrollY + viewH) st.scrollY = caretTop + lh - viewH
        st.scrollY = st.scrollY.coerceIn(0f, (layout.height - viewH).coerceAtLeast(0f))

        dl.pushClipRect(x + pad * 0.5f, y, x + w - pad * 0.5f, y + h, true)
        val left = x + pad
        var ly = y + pad - st.scrollY
        for (line in layout.lines) {
            if (ly + lh >= y && ly <= y + h) {
                if (st.hasSelection) {
                    val a = maxOf(st.selStart, line.start)
                    val b = minOf(st.selEnd, line.end)
                    if (b > a) {
                        val ax = TextPaint.width(
                            line.text.substring(0, (a - line.start).coerceIn(0, line.text.length)), fontSize,
                        )
                        val bx = TextPaint.width(
                            line.text.substring(0, (b - line.start).coerceIn(0, line.text.length)), fontSize,
                        )
                        // A selection that runs through a line break is shown reaching a little past the
                        // last glyph, which is how every editor signals "the newline is selected too".
                        val end = if (b > line.textEnd) bx + fontSize * 0.35f else bx
                        dl.addRectFilled(
                            left + ax, ly - 1f, left + maxOf(end, ax + 1f), ly + fontSize + 1f,
                            SELECTION, 1f, ImDrawFlags.RoundCornersAll,
                        )
                    }
                }
                if (line.text.isNotEmpty()) TextPaint.shadowed(dl, left, ly, fontSize, color, line.text)
            }
            ly += lh
        }
        val cRow = layout.lines[caretRow]
        val cx = left + TextPaint.width(
            cRow.text.substring(0, (st.caret - cRow.start).coerceIn(0, cRow.text.length)), fontSize,
        )
        val cy = y + pad - st.scrollY + caretRow * lh
        caret(dl, st, cx, cy - 1f, cy + fontSize + 1f, fontSize)
        dl.popClipRect()
    }

    /**
     * The blinking caret.
     *
     * Blinks on ImGui's own clock so it matches every other cursor on screen, and holds solid while a
     * selection is being extended — a blinking caret during a drag reads as a glitch.
     */
    private fun caret(dl: ImDrawList, st: TextEditState, cx: Float, top: Float, bottom: Float, fontSize: Float) {
        if (st.hasSelection) return
        if ((ImGui.getTime() * 2).toInt() % 2 != 0) return
        dl.addLine(cx, top, cx, bottom, Theme.TEXT, maxOf(1f, fontSize * 0.07f))
    }
}
