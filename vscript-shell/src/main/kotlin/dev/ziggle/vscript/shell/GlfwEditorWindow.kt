package dev.ziggle.vscript.shell

import imgui.ImGui
import imgui.glfw.ImGuiImplGlfw
import dev.ziggle.vscript.runtime.EditorLog
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetFramebufferSize
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwShowWindow
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil.NULL

class GlfwEditorWindow(
    private val title: String = "vs editor",
    private val drawBody: () -> Unit,
) {

    private val editor = EditorContext(drawBody)
    private val platform = ImGuiImplGlfw()

    private var handle: Long = NULL
    private var ownsGlfw = false
    private var ownsWindow = false

    val isOpen: Boolean get() = handle != NULL && !glfwWindowShouldClose(handle)

    /**
     * Create a window and draw until it closes. Blocks.
     *
     * **Call it from the main thread.** GLFW requires that on macOS and is happier for it everywhere; a
     * standalone editor has a main thread going spare, which is exactly why this case is easy and the
     * in-client one is not.
     *
     * @param frames stop after this many frames instead of waiting to be closed. Zero — the default — is
     *   the real editor: draw until somebody closes the window.
     *
     *   Anything else is a SMOKE RUN, and it exists because "the standalone editor works" was a claim
     *   nothing could check. A loop that ends only when a person clicks the close button cannot be a
     *   test; it cannot even be run by a script without something else killing it, and a kill tells you
     *   nothing about whether the window ever drew. With a budget the same code path opens a real window,
     *   builds real frames against a real GL context, tears down and reports an exit status.
     */
    fun run(width: Int = 1280, height: Int = 800, frames: Int = 0) {
        if (!create(width, height)) return
        try {
            var drawn = 0
            while (!glfwWindowShouldClose(handle) && (frames <= 0 || drawn < frames)) {
                frame()
                drawn++
            }
        } finally {
            close()
        }
    }

    /**
     * Attach to a window the HOST already created and initialised GLFW for.
     *
     * Nothing is torn down by [close] in this mode beyond this editor's own context — destroying a window
     * this class did not create is how an embedder loses its own UI.
     */
    fun openIn(existingWindow: Long) {
        handle = existingWindow
        ownsGlfw = false
        ownsWindow = false
        initBackends()
    }

    /**
     * One frame, for a host that owns the loop. Polls, draws and swaps; safe to call when not open.
     *
     * **The poll runs with THIS editor's ImGui context current, and that is not incidental.**
     * `glfwPollEvents` does not queue anything — it DISPATCHES, calling the platform backend's callbacks
     * inline, and every one of them (`cursorEnterCallback`, `keyCallback`, `mouseButtonCallback`) reads
     * `ImGui.getIO()`. Poll outside the context and the first callback of the run dies on
     * `IM_ASSERT(GImGui != NULL)` inside the native library.
     *
     * It is the same mistake as the two in `initBackends` and `close`, and it is the worst of the three
     * because it depends on a person: the callbacks fire only when input actually arrives, so a smoke run
     * whose window the mouse never crosses passes, and the same build dies the moment somebody moves the
     * cursor over it. Found exactly that way — a 120-frame run went green and the next one did not.
     */
    fun frame() {
        if (handle == NULL) return
        glfwMakeContextCurrent(handle)
        editor.withCurrent { glfwPollEvents() }
        val (w, h) = framebuffer()
        // The platform backend reads the window itself, so the pump is its own newFrame rather than a
        // translated event queue — see the class note.
        val drew = editor.frame(w, h) { platform.newFrame() }
        // Only when a frame was actually built: the game may hold the ImGui lock, and swapping then shows
        // a stale back buffer.
        if (drew) glfwSwapBuffers(handle)
    }

    private fun create(width: Int, height: Int): Boolean = runCatching {
        GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "unable to initialise GLFW" }
        ownsGlfw = true
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        handle = glfwCreateWindow(width, height, title, NULL, NULL)
        check(handle != NULL) { "unable to create the editor window" }
        ownsWindow = true
        glfwMakeContextCurrent(handle)
        // VSYNC ON here, unlike the in-client window. Standalone there is no game thread to starve and no
        // JAWT surface lock to hold, so pacing to the display is simply the right thing.
        glfwSwapInterval(1)
        glfwShowWindow(handle)
        initBackends()
        true
    }.onFailure {
        EditorLog.e(TAG, "could not open the GLFW editor window", it)
        close()
    }.getOrDefault(false)

    private fun initBackends() {
        // GL entry points are resolved per thread AND per context, and nothing may call GL before this.
        org.lwjgl.opengl.GL.createCapabilities()
        GL11.glClearColor(0.07f, 0.08f, 0.10f, 1f)
        editor.init()
        // **Through [EditorContext.withCurrent], because the backend attaches to whatever context is
        // CURRENT and ours is deliberately not.** `EditorContext.init` restores the previous context on
        // its way out — it has to, or opening the editor would steal the frame from a host that was
        // already drawing — so at this point the current context is whatever it was before, and that is
        // the one `ImGuiImplGlfw.init` would have wired its callbacks into.
        //
        // Two failures, and only the second is loud. In a host that already has a context this attached
        // the editor's keyboard and mouse to the HOST's io, which reads as an editor that draws perfectly
        // and ignores input. Standalone there is no previous context at all, so `ImGui.getIO()` fires
        // `IM_ASSERT(GImGui != NULL)` from inside the native library and the process exits — which is how
        // it was finally found, on the first run of the standalone shell, having compiled since the day it
        // was written.
        val attached = editor.withCurrent {
            runCatching { platform.init(handle, true) }
                .onFailure { EditorLog.w(TAG, "GLFW input backend failed to attach: ${it.message}") }
                .isSuccess
        }
        if (attached != true) EditorLog.w(TAG, "the editor window has no input backend")
        editor.resetClock()
    }

    private fun framebuffer(): Pair<Int, Int> {
        val w = IntArray(1)
        val h = IntArray(1)
        glfwGetFramebufferSize(handle, w, h)
        return w[0].coerceAtLeast(1) to h[0].coerceAtLeast(1)
    }

    /**
     * Tear down only what this window created — see [openIn].
     *
     * `platform.shutdown()` goes through [EditorContext.withCurrent] for the same reason `init` does: it
     * reads `ImGui.getIO()`, so it needs a current context, and by here there may be none at all. Both
     * halves of the backend's lifecycle have this requirement and only one of them had it met.
     */
    fun close() {
        editor.withCurrent { runCatching { platform.shutdown() } }
        if (ownsWindow && handle != NULL) runCatching { glfwDestroyWindow(handle) }
        if (ownsGlfw) runCatching { glfwTerminate() }
        handle = NULL
        ownsWindow = false
        ownsGlfw = false
    }

    private companion object {
        const val TAG = "VScript"
    }
}
