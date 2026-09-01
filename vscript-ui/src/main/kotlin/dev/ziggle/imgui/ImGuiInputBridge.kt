package dev.ziggle.imgui

import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Routes REAL user input from the game canvas into ImGui. Two jobs:
 *  - It **filters out our synthetic input** ([PostedMouseEvent]/[PostedKeyEvent]/[PostedMouseWheelEvent]),
 *    so the bot's clicks/keys drive the game while only the human drives the UI.
 *  - It buffers events into a thread-safe queue that [ImGuiManager] drains on the GL/render thread
 *    (ImGui's IO event queue isn't safe to touch from the AWT EDT directly).
 *
 * [wantCaptureMouse]/[wantCaptureKeyboard] are published back by [ImGuiManager] each frame so callers
 * can decide whether a real click landed on the UI (and shouldn't also reach the game).
 */
class ImGuiInputBridge(
    private val canvas: Component,
    /**
     * Which AWT events this host posted itself.
     *
     * A host that drives its own application — a bot, a macro recorder, an integration test — puts
     * synthetic events on the AWT queue, and those must not be fed back into the UI: the pointer would
     * chase itself, and a forged click would land twice. Only the host can tell the difference, because
     * only the host knows what it posted, so it says so here.
     *
     * Defaults to trusting everything, which is correct for a host that forges nothing.
     */
    private val synthetic: SyntheticInput = SyntheticInput.NONE,
) {

    sealed class Ev
    data class MousePos(val x: Float, val y: Float) : Ev()
    data class MouseButton(val button: Int, val down: Boolean) : Ev()
    data class MouseWheel(val dx: Float, val dy: Float) : Ev()
    data class CharTyped(val codepoint: Int) : Ev()
    data class Key(val keyCode: Int, val down: Boolean) : Ev()

    private val queue = ConcurrentLinkedQueue<Ev>()

    @Volatile var wantCaptureMouse: Boolean = false
    @Volatile var wantCaptureKeyboard: Boolean = false

    private val mouse = object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) = pos(e)
        override fun mouseDragged(e: MouseEvent) = pos(e)
        override fun mousePressed(e: MouseEvent) { if (real(e)) queue.add(MouseButton(button(e), true)) }
        override fun mouseReleased(e: MouseEvent) { if (real(e)) queue.add(MouseButton(button(e), false)) }
        override fun mouseWheelMoved(e: MouseWheelEvent) { if (real(e)) queue.add(MouseWheel(0f, -e.preciseWheelRotation.toFloat())) }
        private fun pos(e: MouseEvent) { if (real(e)) queue.add(MousePos(e.x.toFloat(), e.y.toFloat())) }
    }

    private val key = object : KeyAdapter() {
        // A RELEASE is taken even without focus: dropping it would strand a modifier down forever if focus
        // moved while a key was held, and a spurious release only ever un-sticks a key.
        override fun keyPressed(e: KeyEvent) { if (real(e) && addressedToUs()) queue.add(Key(physicalKeyCode(e), true)) }
        override fun keyReleased(e: KeyEvent) { if (real(e)) queue.add(Key(physicalKeyCode(e), false)) }
        override fun keyTyped(e: KeyEvent) {
            if (real(e) && addressedToUs() && !e.keyChar.isISOControl()) queue.add(CharTyped(e.keyChar.code))
        }
    }

    fun install() {
        canvas.addMouseListener(mouse)
        canvas.addMouseMotionListener(mouse)
        canvas.addMouseWheelListener(mouse)
        canvas.addKeyListener(key)
    }

    /** Drain queued events (call on the render thread before ImGui.newFrame()). */
    fun drain(consumer: (Ev) -> Unit) {
        while (true) {
            val e = queue.poll() ?: break
            consumer(e)
        }
    }

    /** Real user input only — never events the host forged. */
    private fun real(e: AWTEvent): Boolean = !synthetic.isSynthetic(e)

    private fun addressedToUs(): Boolean = runCatching { canvas.isFocusOwner }.getOrDefault(true)

    private fun physicalKeyCode(e: KeyEvent): Int {
        val extended = e.extendedKeyCode
        return if (extended != 0 && extended != e.keyCode) extended else e.keyCode
    }

    private fun button(e: MouseEvent): Int = when (e.button) {
        MouseEvent.BUTTON1 -> 0
        MouseEvent.BUTTON3 -> 1
        MouseEvent.BUTTON2 -> 2
        else -> 0
    }

    private fun Char.isISOControl(): Boolean = Character.isISOControl(this)
}

/**
 * Tells [ImGuiInputBridge] which AWT events the host generated itself.
 *
 * Kept out of the bridge because "is this event mine?" is unanswerable here — the bridge sees an
 * `AWTEvent` and has no vocabulary for the host's own event subclasses. [NONE] is a host that posts
 * nothing, and is the right answer for an ordinary application.
 */
fun interface SyntheticInput {
    fun isSynthetic(e: java.awt.AWTEvent): Boolean

    companion object {
        val NONE: SyntheticInput = SyntheticInput { false }
    }
}
