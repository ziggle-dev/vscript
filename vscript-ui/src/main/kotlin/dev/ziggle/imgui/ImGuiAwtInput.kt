package dev.ziggle.imgui

import imgui.ImGuiIO
import imgui.flag.ImGuiKey
import java.awt.event.KeyEvent

/**
 * AWT input → ImGui IO.
 *
 * Extracted from [ImGuiManager] so the detached editor window can feed its own ImGui context through
 * exactly the same path — two windows with divergent key handling would be a slow, maddening bug.
 */
object ImGuiAwtInput {

    /**
     * Characters typed since the last drain.
     *
     * imgui-java does not bind `ImGuiIO::InputQueueCharacters` — it is an explicit TODO in the binding — so
     * a hand-drawn text field has no way to ask ImGui what was typed. We already translate every AWT key
     * event here, so the characters are teed off as they pass: ImGui still gets them for its own widgets,
     * and our canvas widgets can drain the same stream.
     *
     * Filled and drained on whichever thread is building the current frame, and both frame paths hold the
     * ImGui frame lock, so the two windows can never interleave into it.
     */
    private val typed = StringBuilder()

    /** Take and clear everything typed since the last call. */
    fun drainTyped(): String {
        if (typed.isEmpty()) return ""
        val s = typed.toString()
        typed.setLength(0)
        return s
    }

    /** Discard pending characters — call when focus changes so stale keystrokes do not land in a new field. */
    fun clearTyped() {
        typed.setLength(0)
    }

    fun apply(io: ImGuiIO, ev: ImGuiInputBridge.Ev) {
        when (ev) {
            is ImGuiInputBridge.MousePos -> io.addMousePosEvent(ev.x, ev.y)
            is ImGuiInputBridge.MouseButton -> io.addMouseButtonEvent(ev.button, ev.down)
            is ImGuiInputBridge.MouseWheel -> io.addMouseWheelEvent(ev.dx, ev.dy)
            is ImGuiInputBridge.CharTyped -> {
                io.addInputCharacter(ev.codepoint)
                if (ev.codepoint >= 32) typed.appendCodePoint(ev.codepoint)
            }
            is ImGuiInputBridge.Key -> {
                val key = awtToImGuiKey(ev.keyCode)
                if (key != ImGuiKey.None) io.addKeyEvent(key, ev.down)
                // Submit the modifier flags explicitly too (not just the L/R keys), so ImGui shortcuts
                // (ctrl+A select-all, shift+arrows select, ctrl+C/X/V) see the right modifier state.
                when (ev.keyCode) {
                    KeyEvent.VK_CONTROL -> io.addKeyEvent(ImGuiKey.ModCtrl, ev.down)
                    KeyEvent.VK_SHIFT -> io.addKeyEvent(ImGuiKey.ModShift, ev.down)
                    KeyEvent.VK_ALT -> io.addKeyEvent(ImGuiKey.ModAlt, ev.down)
                    KeyEvent.VK_META -> io.addKeyEvent(ImGuiKey.ModSuper, ev.down)
                }
            }
        }
    }

    /**
     * AWT virtual key code → ImGuiKey. Without this, editing/navigation keys (backspace, delete, arrows,
     * home/end, enter, …) and shortcuts (ctrl+A/C/V) never reach ImGui inputs — only printable characters
     * (which arrive separately as `CharTyped`) did. ImGui derives the Ctrl/Shift/Alt modifier state from
     * the left/right modifier keys mapped here.
     */
    fun awtToImGuiKey(vk: Int): Int = when (vk) {
        KeyEvent.VK_BACK_SPACE -> ImGuiKey.Backspace
        KeyEvent.VK_DELETE -> ImGuiKey.Delete
        KeyEvent.VK_LEFT -> ImGuiKey.LeftArrow
        KeyEvent.VK_RIGHT -> ImGuiKey.RightArrow
        KeyEvent.VK_UP -> ImGuiKey.UpArrow
        KeyEvent.VK_DOWN -> ImGuiKey.DownArrow
        KeyEvent.VK_HOME -> ImGuiKey.Home
        KeyEvent.VK_END -> ImGuiKey.End
        KeyEvent.VK_PAGE_UP -> ImGuiKey.PageUp
        KeyEvent.VK_PAGE_DOWN -> ImGuiKey.PageDown
        KeyEvent.VK_INSERT -> ImGuiKey.Insert
        KeyEvent.VK_ENTER -> ImGuiKey.Enter
        KeyEvent.VK_TAB -> ImGuiKey.Tab
        KeyEvent.VK_ESCAPE -> ImGuiKey.Escape
        KeyEvent.VK_SPACE -> ImGuiKey.Space
        KeyEvent.VK_CONTROL -> ImGuiKey.LeftCtrl
        KeyEvent.VK_SHIFT -> ImGuiKey.LeftShift
        KeyEvent.VK_ALT -> ImGuiKey.LeftAlt
        KeyEvent.VK_META -> ImGuiKey.LeftSuper
        in KeyEvent.VK_A..KeyEvent.VK_Z -> ImGuiKey.A + (vk - KeyEvent.VK_A)
        in KeyEvent.VK_0..KeyEvent.VK_9 -> ImGuiKey._0 + (vk - KeyEvent.VK_0)
        else -> ImGuiKey.None
    }
}

/**
 * Serialises ImGui frames between the game's render thread and the detached editor window.
 *
 * Dear ImGui keeps its "current context" in a **global**, not a thread-local, so two threads building
 * frames against two contexts will corrupt each other. Both render paths therefore take this lock around
 * an entire frame (`setCurrentContext` → `newFrame` → draw → `render` → `renderDrawData`).
 *
 * **The asymmetry is the important part.** The game thread [lockForGame] blocks — it must never skip a
 * frame. The editor uses [tryLockForEditor] and simply drops the frame if the game holds the lock, because
 * an occasional dropped editor frame is invisible while a stalled game thread is not. Priority runs one
 * way only, deliberately.
 */
object ImGuiFrameLock {
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** Run [block] as the game's frame; blocks until the lock is free. */
    inline fun forGame(block: () -> Unit) {
        acquire()
        try {
            block()
        } finally {
            release()
        }
    }

    /** Run [block] as an editor frame, or skip it entirely if the game is mid-frame. */
    inline fun tryForEditor(block: () -> Unit): Boolean {
        if (!tryAcquire()) return false
        try {
            block()
        } finally {
            release()
        }
        return true
    }

    @PublishedApi internal fun acquire() = lock.lock()
    @PublishedApi internal fun tryAcquire(): Boolean = lock.tryLock()
    @PublishedApi internal fun release() = lock.unlock()
}
