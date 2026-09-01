package dev.ziggle.vscript.shell

import imgui.ImGui
import dev.ziggle.imgui.ImGuiAwtInput
import dev.ziggle.imgui.ImGuiFrameLock
import dev.ziggle.imgui.ImGuiInputBridge
import dev.ziggle.vscript.runtime.EditorLog
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.awt.AWTGLCanvas
import org.lwjgl.opengl.awt.GLData
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

class DetachedEditorWindow(
    private val title: String,
    private val drawBody: () -> Unit,
    private val onClosed: () -> Unit,
) {
    /**
     * Every piece of ImGui work, shared with the GLFW window — see [EditorContext].
     *
     * The atlas, the frame lock, the clock and the font binding all live there. What is left in this class
     * is the part that is genuinely about AWT: making a Swing window with a GL surface, keeping frames off
     * the event thread, and translating AWT events into an `ImGuiIO`.
     */
    private val editor = EditorContext(drawBody)

    // Volatile, all four: these are assigned on the CLIENT thread (open() runs inside the game's ImGui
    // frame) and read on the render thread and the EDT. That crossing existed before; frames leaving the
    // EDT is what made it load-bearing.
    @Volatile private var frame: JFrame? = null
    @Volatile private var canvas: AWTGLCanvas? = null
    // NOT `context`: AWTGLCanvas already has a `context` field (the GL handle, a long), which
    // would shadow this one inside the anonymous subclass below.
    @Volatile private var bridge: ImGuiInputBridge? = null

    /** This canvas's GL entry points. Per thread+context — see the note in `initGL`. */
    @Volatile private var glCaps: org.lwjgl.opengl.GLCapabilities? = null

    @Volatile private var running = false

    /** Set once a frame has failed; stops the pump so a broken context is not driven repeatedly. */
    @Volatile private var failed = false


    /** The repaint loop's thread. Started on first open and kept for the life of the client — see [pump]. */
    @Volatile private var renderThread: Thread? = null

    val isOpen: Boolean get() = running

    /**
     * Show the window, creating it on first use.
     *
     * The window and its GL/ImGui contexts are built **once and kept** for the life of the client — see
     * [close] for why nothing is torn down.
     */
    fun open(): Boolean {
        if (failed) return false
        val existing = frame
        if (existing != null) {
            running = true
            editor.resetClock()
            SwingUtilities.invokeLater {
                existing.isVisible = true
                existing.toFront()
                canvas?.requestFocusInWindow()
            }
            pump()
            return true
        }
        return runCatching { create() }.onFailure {
            EditorLog.e(TAG, "could not open the detached editor window", it)
            failed = true
        }.getOrDefault(false)
    }

    private fun create(): Boolean {
        // No MSAA request: ImGui draws flat 2D geometry that gains nothing from it, and asking for a
        // multisampled pixel format is one more way context creation can fail on a given driver.
        val data = GLData().apply {
            majorVersion = 3
            minorVersion = 2
            profile = GLData.Profile.CORE
            // VSYNC OFF, and not for frame rate. `AWTGLCanvas.render()` holds the JAWT drawing-surface
            // lock for the whole frame, `swapBuffers` included — so a vsync wait sits on that lock for
            // most of every refresh, and the EDT blocks behind it the moment it needs the surface (a
            // resize, a repaint). The render loop paces itself with a sleep instead, holding nothing.
            swapInterval = 0
        }

        val c = object : AWTGLCanvas(data) {
            override fun initGL() {
                // FIRST GL CALL OF ALL. LWJGL resolves GL entry points per thread+context and keeps them
                // in a thread-local; this canvas has its own context, and this runs on the render thread
                // that will use it, so nothing is bound here until we create them. Calling any GL function
                // before this aborts the whole JVM with "No context is current or a function that is not
                // available in the current context was called" — not an exception, a process kill, taking
                // the game with it.
                glCaps = org.lwjgl.opengl.GL.createCapabilities()
                GL11.glClearColor(0.07f, 0.08f, 0.10f, 1f)
                editor.init()
            }

            override fun paintGL() {
                if (!editor.ready) return
                // Re-bind THIS context's entry points. Two GL contexts live in this process (the game's on
                // the client thread, ours here), and LWJGL's lookup is thread-local — leaving the wrong set
                // bound is the same JVM-aborting class of failure as never creating them.
                glCaps?.let { org.lwjgl.opengl.GL.setCapabilities(it) } ?: return
                val w = width.coerceAtLeast(1)
                val h = height.coerceAtLeast(1)
                // The AWT half of the seam: a queue of events, translated by hand. A GLFW backend installs
                // its own callbacks and needs none of this — which is most of why there are two backends.
                val drew = editor.frame(w, h) { gio -> bridge?.drain { ImGuiAwtInput.apply(gio, it) } }
                if (drew) swapBuffers() else dropped++
            }
        }

        val f = JFrame(title).apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            layout = BorderLayout()
            add(c, BorderLayout.CENTER)
            preferredSize = Dimension(1180, 760)
            pack()
            setLocationRelativeTo(null)
            addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(e: java.awt.event.WindowEvent?) = close()
            })
        }

        canvas = c
        frame = f
        bridge = ImGuiInputBridge(c).also { it.install() }

        // An AWT Canvas does NOT take keyboard focus when clicked — that is the container's job, and
        // nothing was doing it. Without focus the KeyListener never fires, so every text field in this
        // window (a pin's inline value, a comment's title) silently ignored typing while the mouse worked
        // fine. In the game's own window the client already holds focus, which is why this only showed up
        // once the editor was popped out.
        c.isFocusable = true
        c.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (!c.isFocusOwner) c.requestFocusInWindow()
            }
        })

        f.isVisible = true
        running = true
        SwingUtilities.invokeLater { c.requestFocusInWindow() }
        editor.resetClock()
        pump()
        EditorLog.i(TAG, "detached editor window opened")
        return true
    }

    /**
     * Drive repaints from a thread of this window's own.
     *
     * **Not the AWT event thread, and that is the entire point.** Every mouse and key event the game sees —
     * the user's, and the synthetic ones `VirtualMouse` posts through the system event queue — is dispatched
     * on the EDT. Anything rendering there is competing with the game's input for the same thread.
     *
     * The original loop re-posted itself with `invokeLater` the instant a frame finished, which is a loop
     * with no idle in it: the EDT went render → post → render for as long as the window was open. AWT
     * coalesces mouse-motion events, so everything arriving between two frames collapsed into a single
     * position update, and the game read input in visible jerks — while rendering perfectly smoothly, since
     * it renders on the client thread. Input and rendering having no apparent connection is what made that
     * hard to place.
     *
     * Throttling it helped and was still the wrong shape: it traded editor smoothness against input latency,
     * a dial with no good setting. Nothing has to be traded, because `AWTGLCanvas.render()` ends with
     * `makeCurrent(0)` and `unlock()` — the GL context is **released after every frame**, specifically so
     * that one thread of your choosing can drive it. JAWT's drawing-surface lock is designed for exactly
     * this: a non-EDT thread drawing into an AWT surface.
     *
     * So the whole frame — GL context creation, the ImGui context, the fonts, every draw — happens here, and
     * the EDT does none of it. What still protects correctness is unchanged: [ImGuiFrameLock] serialises
     * this thread's frame against the game's, and [ImGuiInputBridge] was already a concurrent queue built to
     * be drained off the EDT.
     *
     * The pacing that remains is only about power. It costs the game nothing either way now.
     */
    private fun pump() {
        val c = canvas ?: return
        // Started once and kept, like the window and its two contexts. A thread that exited on hide and
        // restarted on show has a gap in it: one that has decided to stop is still `isAlive`, so a quick
        // hide/show can skip starting the replacement and leave a visible window with nothing drawing it.
        // Idling costs a wake-up every HIDDEN_MS; the race costs a dead editor.
        if (renderThread != null) return
        val t = Thread({ loop(c) }, "vscript-editor-gl")
        t.isDaemon = true // never a reason to hold the JVM open for an editor window
        renderThread = t
        t.start()
    }

    private fun loop(c: AWTGLCanvas) {
        var wasVisible = false
        while (!failed) {
            if (!running) {
                // Hidden. Say what the session cost on the way down, then idle.
                if (wasVisible) {
                    if (frames > 0) report(System.nanoTime() - sinceNanos)
                    wasVisible = false
                }
                if (!nap(HIDDEN_MS)) return
                continue
            }
            if (!wasVisible) {
                wasVisible = true
                frames = 0; dropped = 0; renderNanos = 0; worstNanos = 0
                sinceNanos = System.nanoTime()
            }

            val began = System.nanoTime()
            if (c.isValid) {
                val failure = runCatching { c.render() }.exceptionOrNull()
                if (failure != null) {
                    // Stop driving a context that has already failed once. Repeated GL calls against a
                    // broken or lost context are how a recoverable error becomes a JVM abort, and this
                    // window must never be able to take the game down with it.
                    failed = true
                    EditorLog.e(TAG, "detached editor frame failed — hiding the window", failure)
                    SwingUtilities.invokeLater { close() }
                    return
                }
                record(System.nanoTime() - began)
            }
            // Re-read every frame rather than tracking it with a listener: activation changes for reasons
            // no listener on this window sees (the game window being raised, an alt-tab to something else).
            val period = if (frame?.isActive == true) ACTIVE_MS else IDLE_MS
            if (!nap(period - (System.nanoTime() - began) / 1_000_000L)) return
        }
    }

    /** Sleep, reporting whether the loop should carry on. */
    private fun nap(ms: Long): Boolean {
        if (ms <= 0) return true
        return try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // ---- telemetry ---------------------------------------------------------------------------------

    /**
     * What this window costs, and **on which thread**.
     *
     * Kept after the fix rather than retired with it, and the thread name is in the line on purpose: the
     * symptom was never visible in a frame rate — this window rendered beautifully the whole time it was
     * making the game unplayable — so what is worth watching is where the work lands. If a future change
     * quietly puts frames back on `AWT-EventQueue-0`, this line says so.
     *
     * `dropped` is the other half: frames skipped because the game held [ImGuiFrameLock]. That is the only
     * contention left between the two windows, and it should stay a small fraction.
     */
    private var frames = 0L
    private var dropped = 0L
    private var renderNanos = 0L
    private var worstNanos = 0L
    private var sinceNanos = System.nanoTime()

    private fun record(nanos: Long) {
        frames++
        renderNanos += nanos
        if (nanos > worstNanos) worstNanos = nanos
        val elapsed = System.nanoTime() - sinceNanos
        if (elapsed >= REPORT_NANOS) report(elapsed)
    }

    /** Written and read solely on the render thread, so the counters need no synchronisation. */
    private fun report(elapsedNanos: Long) {
        EditorLog.i(
            TAG,
            "detached editor: %.0f fps on %s (avg %.1fms, worst %.1fms, %d frames yielded to the game)"
                .format(
                    frames * 1e9 / elapsedNanos,
                    Thread.currentThread().name,
                    renderNanos / 1e6 / maxOf(frames, 1),
                    worstNanos / 1e6,
                    dropped,
                ),
        )
        frames = 0; dropped = 0; renderNanos = 0; worstNanos = 0
        sinceNanos = System.nanoTime()
    }

    /**
     * Hide the window. Safe to call twice, and from any thread.
     *
     * **Nothing is destroyed — deliberately.** Every attempt to release this window's resources on close
     * killed the whole client instead: `ImGuiImplGl3.shutdown()` issues GL deletes with no context current
     * (the canvas's context is only current *during* `render()`), and destroying the GL context and the
     * AWT frame took the process down too. Native teardown ordering across two GL contexts, two ImGui
     * contexts and AWT's window lifecycle is a lot of ways to abort a JVM, and none of them are worth it
     * for a window the user will probably reopen.
     *
     * So the window is built once and shown/hidden thereafter. It holds one GL context and one ImGui
     * context for the life of the client — a fixed, bounded cost that does not grow with toggling, and it
     * makes reopening instant. Whatever the process still holds is released when the process exits.
     *
     * The work is still deferred to the AWT thread, for two reasons: "Re-attach" calls this from *inside*
     * the game's ImGui frame, where switching contexts would corrupt the frame being built; and a
     * `render()` may be in flight on the AWT thread right now.
     */
    fun close() {
        if (!running) return
        // Puts the render loop back to idling at its next check, and it emits its final reading there.
        // Nothing is joined: [ImGuiFrameLock] already keeps the handoff below from interleaving with a
        // frame in flight, and joining here would block whichever thread asked to close — including, on
        // re-attach, the game's.
        running = false

        val f = frame

        SwingUtilities.invokeLater {
            // The ONE piece of teardown that has to happen: hand the canvas's node-editor context back so
            // the docked panel can rebuild in the main ImGui context. Pure CPU, done with this context
            // current because that is what owns it.
            runCatching {
                ImGuiFrameLock.forGame {
                    editor.withCurrent { runCatching { onClosed() } }
                }
            }
            runCatching { f?.isVisible = false }
            EditorLog.i(TAG, "detached editor window hidden")
        }
    }

    private companion object {
        const val TAG = "VScript"

        /** Repaint period while this window is the active one — 60fps, what an editor being used deserves. */
        const val ACTIVE_MS = 16L

        /**
         * Repaint period while it is not: 30fps.
         *
         * Purely about not spending a core and a GPU redrawing a window nobody is looking at. It stopped
         * being a latency question the moment frames left the EDT — which is why this is 30 and not the 15
         * the throttled version needed.
         */
        const val IDLE_MS = 33L

        /** How often the loop looks up while the window is hidden. Ten wake-ups a second, doing nothing. */
        const val HIDDEN_MS = 100L

        val REPORT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
    }
}
