package dev.ziggle.vscript.shell

import imgui.ImGui
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton

/**
 * Driving the editor's UI from a test: frames, clicks, keys — with no window and no GL.
 *
 * ### Why this can exist at all
 *
 * ImGui is immediate mode. There is no retained widget tree to find and poke: a widget IS the code that
 * runs during a frame, and it decides what it did from `ImGuiIO` — where the pointer is, which buttons are
 * down, what was typed. So a test can set those, run the frame, and look at what the widget changed. No
 * renderer is involved; the atlas is built for metrics and its texture is never uploaded.
 *
 * That is the whole difference from testing a browser UI, where the DOM has to be built and queried. Here
 * the assertion is on the MODEL the widget mutated — a caret offset, a breakpoint set, a search result —
 * which is both more precise than a pixel and the thing anybody actually cares about.
 *
 * ### What it cannot tell you
 *
 * **Whether it looks right.** Nothing here measures where a line was drawn or what colour it was, so an
 * underline three pixels low or text the same colour as its background passes every test in this file.
 * This closes the gap between "the model is correct" and "clicking there does the right thing"; it does
 * not close the gap to "a person can use it".
 *
 * ### A click is TWO frames
 *
 * `isMouseClicked` is true on the frame the button goes down and false after, because that is how an
 * edge is represented in an API with no events. One frame with the button down and one with it up is a
 * click; forgetting the second leaves the button held, and the next frame reads as a drag.
 */
object ImGuiHarness {

    private var started = false
    private var atlasBuilt = false

    /** One context for the whole suite: creating and destroying per test is slow and leaks native memory. */
    fun start(width: Float = 1280f, height: Float = 800f) {
        if (!started) {
            ImGui.createContext()
            started = true
        }
        val io = ImGui.getIO()
        io.setDisplaySize(width, height)
        io.setDeltaTime(1f / 60f)
        io.addConfigFlags(ImGuiConfigFlags.NoMouseCursorChange)
        if (!atlasBuilt) {
            io.fonts.addFontDefault()
            io.fonts.build()
            // A texture id ImGui will happily hand back to a renderer that does not exist.
            io.fonts.setTexID(1)
            atlasBuilt = true
        }
    }

    /**
     * Run one frame with [body] inside a window, with the pointer at [mouseX], [mouseY].
     *
     * The window matters: the code under test calls `ImGui.getWindowDrawList()`, which needs one. It is
     * positioned at the origin and sized to the display so screen coordinates and window coordinates
     * agree — otherwise every geometry assertion would need an offset nobody would remember to apply.
     */
    fun frame(
        mouseX: Float = -1f,
        mouseY: Float = -1f,
        down: Boolean = false,
        ctrl: Boolean = false,
        body: () -> Unit,
    ) {
        val io = ImGui.getIO()
        io.setMousePos(mouseX, mouseY)
        io.setMouseDown(booleanArrayOf(down, false, false, false, false))
        // **A key EVENT, not `setKeyCtrl`.** ImGui 1.90 derives `io.KeyCtrl` from the key-event queue
        // during `newFrame`, so a value written directly beforehand is overwritten and every Ctrl+click
        // test silently runs as a plain click -- which is exactly how a Ctrl+click bug survived a green
        // suite and was reported from a running window.
        io.addKeyEvent(ImGuiKey.ModCtrl, ctrl)
        ImGui.newFrame()
        ImGui.setNextWindowPos(0f, 0f)
        ImGui.setNextWindowSize(io.displaySizeX, io.displaySizeY)
        ImGui.begin("harness")
        body()
        ImGui.end()
        ImGui.render()
    }

    /** Move the pointer somewhere and let one frame observe it — a hover. */
    fun hover(x: Float, y: Float, ctrl: Boolean = false, body: () -> Unit) {
        frame(x, y, down = false, ctrl = ctrl, body = body)
    }

    /**
     * A full click at (x, y): the settling frame, the press, the release.
     *
     * The settling frame is not ceremony. `isMouseHoveringRect` compares against the position ImGui has,
     * and a press on the same frame the pointer first appears there can be seen as a click somewhere the
     * pointer has not been — which is exactly the bug a click test is for.
     *
     * **[heldFrames] defaults to more than one, and that is not padding either.** A person holds a button
     * for a tenth of a second, which is several frames, and the frames AFTER the press are where a drag
     * lives. Holding for one frame made a Ctrl+click test pass against a build where Ctrl+click navigated
     * and then dragged a selection back over the jump — the exact bug it was written for, invisible
     * because the harness released faster than any hand could.
     */
    fun click(x: Float, y: Float, ctrl: Boolean = false, heldFrames: Int = 3, body: () -> Unit) {
        frame(x, y, down = false, ctrl = ctrl, body = body)
        repeat(heldFrames.coerceAtLeast(1)) { frame(x, y, down = true, ctrl = ctrl, body = body) }
        frame(x, y, down = false, ctrl = ctrl, body = body)
    }

    /** Press and release [key] over one frame pair. */
    fun press(key: Int, body: () -> Unit) {
        val io = ImGui.getIO()
        io.addKeyEvent(key, true)
        frame(body = body)
        io.addKeyEvent(key, false)
        frame(body = body)
    }

    /** Type [text] as characters, one frame. */
    fun type(text: String, body: () -> Unit) {
        val io = ImGui.getIO()
        for (c in text) io.addInputCharacter(c.code)
        frame(body = body)
    }

    /** Both shifts released within the double-tap window — the search gesture. */
    fun doubleShift(body: () -> Unit) {
        press(ImGuiKey.LeftShift, body)
        press(ImGuiKey.LeftShift, body)
    }

    @Suppress("unused")
    fun leftButton(): Int = ImGuiMouseButton.Left
}
