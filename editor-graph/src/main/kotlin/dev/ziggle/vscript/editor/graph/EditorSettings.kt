package dev.ziggle.vscript.editor.graph

import dev.ziggle.vscript.runview.DebugPanel
import dev.ziggle.vscript.runtime.EditorLog
import java.io.File
import java.util.Properties

/**
 * Node editor preferences that outlive a session.
 *
 * **A file, not the per-account `DataStore`.** These are editor preferences, not game state: which account
 * happens to be logged in has nothing to do with how you like your undo to behave, and a preference that
 * silently resets when you switch characters is worse than no preference at all. It sits beside the graphs
 * for the same reason they are files — inspectable, editable, and easy to carry between machines.
 *
 * Reads are lazy and every write is immediate, so a toggle survives a crash. Failures are logged and
 * swallowed: a preference file that cannot be read is a reason to use the defaults, never a reason to stop
 * the editor from opening.
 *
 * ### Why it lives with the CANVAS
 *
 * Five of the seven settings are the canvas's (`cameraUndo`, `orthogonalWires`, `wireRouting`,
 * `outlineOpen`, `outlineWidth`) and both of its domain imports were canvas types. It sat in the workbench
 * package, which made the canvas import its host to read its own preferences — an arrow pointing the wrong
 * way, and the last one standing between the surfaces and the module split.
 *
 * The other two are read by the workbench, which depends on this surface anyway, and reach the run views
 * and the runtime through seams (`DebugPanel.Remembered`, `ScriptRuntime.traceRequested`) rather than by
 * anyone importing this.
 *
 * **When the text surface gains settings of its own, the STORAGE should come out** — the `Properties`
 * file and the `bool`/`float`/`put` helpers are generic and the typed accessors are not. Splitting it
 * before there is a second surface to split for would be inventing a seam for one caller.
 */
object EditorSettings {

    /**
     * Whether pans and zooms take part in Ctrl+Z.
     *
     * **Off by default, deliberately.** With it on, panning around to look at something and then pressing
     * Ctrl+Z unwinds the navigation instead of the edit you meant — which is exactly why most editors keep
     * the viewport out of the undo stack. It is genuinely useful when you navigate deliberately rather than
     * constantly, so it is offered; it is just not the behaviour to hand someone who did not ask for it.
     */
    var cameraUndo: Boolean
        get() = bool(CAMERA_UNDO, false)
        set(v) = put(CAMERA_UNDO, v)

    /**
     * Log every node the run enters, not just what nodes explicitly emit.
     *
     * Off by default. It is the difference between a log and a trace: genuinely what you want when a graph
     * is doing something you cannot account for, and far too much the rest of the time — a tick routine
     * produces thousands of rows a second, and the one line you were actually reading scrolls away.
     */
    var traceExecution: Boolean
        get() = bool(TRACE, false)
        set(v) = put(TRACE, v)

    /**
     * Draw wires as right angles rather than curves.
     *
     * Off by default, because a curve says "this goes there" with one shape and never has to decide which
     * way round a corner to go. The case for right angles is a dense graph: parallel wires running along a
     * shared lane read as a bus, and it is much easier to see that two wires are going to the same place
     * when they are literally on the same line. Which of those you want depends on the graph, so it is a
     * setting rather than a decision made for you.
     */
    var orthogonalWires: Boolean
        get() = bool(ORTHO_WIRES, false)
        set(v) = put(ORTHO_WIRES, v)

    /**
     * How hard to work at keeping wires out of the way — see [WireRouting].
     *
     * Defaults to avoiding nodes but not other wires. Avoiding nodes is nearly free and fixes the thing
     * that actually loses information (a wire that vanishes behind a node for most of its length); the
     * wire-versus-wire pass costs more and is worth it mainly on a dense graph, so it is offered rather
     * than assumed. An unrecognised value reads as the default, so a hand-edited file cannot break it.
     */
    var wireRouting: WireRouting
        get() = runCatching { WireRouting.valueOf(string(WIRE_ROUTING, WireRouting.AVOID_NODES.name)) }
            .getOrDefault(WireRouting.AVOID_NODES)
        set(v) = put(WIRE_ROUTING, v.name)

    /** Drawer height, in pixels. Persisted so it stays where you dragged it. */
    var consoleHeight: Float
        get() = float(CONSOLE_HEIGHT, DebugPanel.DEFAULT_H).coerceIn(DebugPanel.MIN_H, DebugPanel.MAX_H)
        set(v) = put(CONSOLE_HEIGHT, v)

    /** Whether the outline sidebar is showing, and how wide. */
    var outlineOpen: Boolean
        get() = bool(OUTLINE_OPEN, true)
        set(v) = put(OUTLINE_OPEN, v)

    var outlineWidth: Float
        get() {
            val stored = float(OUTLINE_WIDTH, OutlinePanel.DEFAULT_W)
            // A width saved at the OLD default is not a choice, it is the absence of one — so it follows the
            // new default rather than leaving everyone who never dragged the panel stuck at a size that no
            // longer fits its contents. A width anybody actually set is left alone.
            val effective = if (stored == LEGACY_OUTLINE_W) OutlinePanel.DEFAULT_W else stored
            return effective.coerceIn(OutlinePanel.MIN_W, OutlinePanel.MAX_W)
        }
        set(v) = put(OUTLINE_WIDTH, v)

    private const val TAG = "VScript"
    private const val OUTLINE_OPEN = "outline.open"
    private const val OUTLINE_WIDTH = "outline.width"

    /** The default before the expanded row needed room for three actions. */
    private const val LEGACY_OUTLINE_W = 240f
    private const val CAMERA_UNDO = "camera.undo"
    private const val TRACE = "console.trace"
    private const val ORTHO_WIRES = "canvas.orthogonalWires"
    private const val WIRE_ROUTING = "canvas.wireRouting"
    private const val CONSOLE_HEIGHT = "console.height"

    private val props = Properties()
    private var loaded = false

    /** Where the properties file lives; a host that is not the vscript client sets this before the first read. */
    @Volatile
    var home: File? = null

    private val file: File get() = home?.let { File(it, "vscript-editor.properties") } ?: File(System.getProperty("user.home"), ".ziggle/vscript-editor.properties")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file
            if (f.isFile) f.inputStream().use { props.load(it) }
        }.onFailure { EditorLog.w(TAG, "could not read editor settings: ${it.message}") }
    }

    private fun bool(key: String, default: Boolean): Boolean {
        ensureLoaded()
        return props.getProperty(key)?.equals("true", ignoreCase = true) ?: default
    }

    private fun string(key: String, default: String): String {
        ensureLoaded()
        return props.getProperty(key) ?: default
    }

    private fun float(key: String, default: Float): Float {
        ensureLoaded()
        return props.getProperty(key)?.toFloatOrNull() ?: default
    }

    private fun put(key: String, value: Any) {
        ensureLoaded()
        props.setProperty(key, value.toString())
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            f.outputStream().use { props.store(it, "vscript node editor preferences") }
        }.onFailure { EditorLog.w(TAG, "could not save editor settings: ${it.message}") }
    }
}
