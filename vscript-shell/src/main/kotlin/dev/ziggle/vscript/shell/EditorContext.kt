package dev.ziggle.vscript.shell

import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiConfigFlags
import imgui.flag.ImGuiWindowFlags
import imgui.gl3.ImGuiImplGl3
import imgui.internal.ImGuiContext
import dev.ziggle.imgui.FontLoader
import dev.ziggle.imgui.FontSet
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.ImGuiFrameLock
import dev.ziggle.imgui.ThemeStyle
import dev.ziggle.vscript.runtime.EditorLog
import org.lwjgl.opengl.GL11

internal class EditorContext(private val drawBody: () -> Unit) {

    @Volatile
    private var ctx: ImGuiContext? = null
    private var gl: ImGuiImplGl3? = null
    private var fonts: FontSet? = null

    @Volatile
    private var lastFrameNanos: Long = System.nanoTime()

    val ready: Boolean get() = ctx != null

    /**
     * Build the context, its atlas and the GL backend.
     *
     * Must run on the thread that owns the GL context and with it current — `ImGuiImplGl3.init` issues GL
     * calls. Takes the frame lock because it touches the ImGui global while the game may be drawing.
     */
    fun init(glslVersion: String = "#version 150") {
        ImGuiFrameLock.forGame {
            val prev = ImGui.getCurrentContext()
            val made = ImGui.createContext()
            ImGui.setCurrentContext(made)
            val io = ImGui.getIO()
            io.iniFilename = null
            io.addConfigFlags(ImGuiConfigFlags.DockingEnable)
            ThemeStyle.apply(ImGui.getStyle())
            fonts = FontLoader.load(io)
            val impl = ImGuiImplGl3()
            impl.init(glslVersion)
            ctx = made
            gl = impl
            ImGui.setCurrentContext(prev)
        }
    }

    /**
     * Draw one frame at [w] x [h].
     *
     * [pumpInput] runs with this context current and its `ImGuiIO` in hand, before the frame is begun —
     * that is where a backend feeds in whatever it collected. An AWT window drains a queue of events into
     * the IO; a GLFW one calls its own backend's `newFrame`, which reads the window directly.
     *
     * @return false when the frame was skipped because the game held the lock. The caller must NOT swap
     *   buffers in that case: there is nothing new in the back buffer, and swapping shows a stale one.
     */
    fun frame(w: Int, h: Int, pumpInput: (ImGuiIO) -> Unit): Boolean {
        val context = ctx ?: return false
        val impl = gl ?: return false
        return ImGuiFrameLock.tryForEditor {
            val prev = ImGui.getCurrentContext()
            ImGui.setCurrentContext(context)
            try {
                val io = ImGui.getIO()
                io.setDisplaySize(w.toFloat(), h.toFloat())
                io.setDisplayFramebufferScale(1f, 1f)
                val now = System.nanoTime()
                io.deltaTime = ((now - lastFrameNanos) / 1e9f).coerceIn(1e-4f, 0.25f)
                lastFrameNanos = now
                pumpInput(io)

                GL11.glViewport(0, 0, w, h)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)

                impl.newFrame() // builds this context's own shaders + font texture on first use
                ImGui.newFrame()
                ImGui.setNextWindowPos(0f, 0f)
                ImGui.setNextWindowSize(w.toFloat(), h.toFloat())
                ImGui.begin(
                    "##shell-root",
                    ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or
                        ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoCollapse or
                        ImGuiWindowFlags.NoBringToFrontOnFocus,
                )
                Fonts.use(fonts ?: FontSet(null, null, null, false)) {
                    runCatching { drawBody() }.onFailure { EditorLog.w(TAG, "editor draw failed: ${it.message}") }
                }
                ImGui.end()
                ImGui.render()
                impl.renderDrawData(ImGui.getDrawData())
            } finally {
                ImGui.setCurrentContext(prev)
            }
        }
    }

    /**
     * Run [block] with THIS context current, restoring whatever was current before.
     *
     * For the work that is neither a frame nor setup: handing a docked node-editor context back at close,
     * for instance, which is pure CPU but must happen with the context that owns it bound. Returns null
     * when there is no context, which is the same answer as "there was nothing to hand back".
     */
    fun <T> withCurrent(block: () -> T): T? {
        val context = ctx ?: return null
        val prev = ImGui.getCurrentContext()
        ImGui.setCurrentContext(context)
        return try {
            block()
        } finally {
            ImGui.setCurrentContext(prev)
        }
    }

    /** Reset the clock, so a window reopened after a long pause does not report a huge first delta. */
    fun resetClock() {
        lastFrameNanos = System.nanoTime()
    }

    private companion object {
        const val TAG = "VScript"
    }
}
