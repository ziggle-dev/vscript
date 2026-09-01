package dev.ziggle.vscript.editor.graph

import dev.ziggle.vscript.editor.graph.OwnCanvas

/**
 * The routing and layout numbers, adjustable while the client runs.
 *
 * Every one of these was chosen by looking at a real graph and deciding it read better, which is the only
 * way any of them CAN be chosen — there is no formula for "this wire looks like a mistake". Recompiling
 * between guesses makes that loop slow, and worse, second-hand: the person who can see the canvas is not
 * the person editing the constant. So they are live, and there is a window.
 *
 * **Not persisted.** A tuned value lasts until the client stops, deliberately: what is worth keeping should
 * end up in the source with a note saying why, not in a settings file nobody can find the reasoning for.
 * [changedAsSource] is there so that last step is not transcription by eye.
 *
 * Eight of these used to belong to mechanisms that have since been deleted — the container-crossing
 * planner and the congestion cost. A knob is only worth having where there is a judgement to make, and
 * those eight were the settings of machinery that should not have existed.
 */
object Tuning {

    class Knob(
        val name: String,
        /** Which part of the picture this one belongs to. */
        val group: String,
        val about: String,
        val get: () -> Double,
        val set: (Double) -> Unit,
        /** The band a slider spans. */
        val min: Double,
        val max: Double,
        /** How far apart two settings are worth telling apart. */
        val step: Double,
    ) {
        /** What it was built with, so a knob can be put back without restarting. */
        val default: Double = get()
    }

    /**
     * Bumped whenever anything changes, so the canvas knows its cached routes answer a different question.
     * Without it a tweak would not show until something moved.
     */
    @Volatile
    var generation: Int = 0
        private set

    private fun f(
        name: String,
        group: String,
        about: String,
        get: () -> Float,
        set: (Float) -> Unit,
        min: Double,
        max: Double,
        step: Double = 1.0,
    ) = Knob(name, group, about, { get().toDouble() }, { set(it.toFloat()) }, min, max, step)

    private fun i(
        name: String,
        group: String,
        about: String,
        get: () -> Int,
        set: (Int) -> Unit,
        min: Double,
        max: Double,
    ) = Knob(name, group, about, { get().toDouble() }, { set(it.toInt()) }, min, max, 1.0)

    private const val OBSTACLES = "What is in the way"
    private const val WIRES = "Other wires"
    private const val LAYOUT = "Layout"

    val all: List<Knob> = listOf(
        f("margin", OBSTACLES, "clearance a wire keeps around a node",
            { OrthogonalRouter.MARGIN }, { OrthogonalRouter.MARGIN = it }, 0.0, 60.0),
        f("stub", OBSTACLES, "straight run out of and into a pin, before any turn",
            { OrthogonalRouter.STUB }, { OrthogonalRouter.STUB = it }, 4.0, 80.0),
        f("swing", OBSTACLES, "how far outside its pins a backward wire may loop",
            { OrthogonalRouter.SWING }, { OrthogonalRouter.SWING = it }, 0.0, 200.0),
        f("turnCost", OBSTACLES, "what a corner costs, in units of length",
            { OrthogonalRouter.TURN_COST }, { OrthogonalRouter.TURN_COST = it }, 0.0, 2000.0, 5.0),
        f("lineGap", OBSTACLES, "closest two places a wire may turn",
            { OrthogonalRouter.MIN_LINE_GAP }, { OrthogonalRouter.MIN_LINE_GAP = it }, 1.0, 60.0),
        f("jog", OBSTACLES, "steps smaller than this are straightened away",
            { OrthogonalRouter.JOG }, { OrthogonalRouter.JOG = it }, 0.0, 40.0),
        i("maxLines", OBSTACLES, "candidate turning lines per axis — costs speed",
            { OrthogonalRouter.MAX_LINES }, { OrthogonalRouter.MAX_LINES = it }, 8.0, 160.0),
        i("maxObstacles", OBSTACLES, "how many obstacles contribute turning lines",
            { OrthogonalRouter.MAX_OBSTACLES }, { OrthogonalRouter.MAX_OBSTACLES = it }, 4.0, 120.0),

        f("containerPad", OBSTACLES, "extra clearance a comment or function box keeps, over margin",
            { OrthogonalRouter.CONTAINER_PAD }, { OrthogonalRouter.CONTAINER_PAD = it }, 0.0, 120.0),

        f("lane", WIRES, "how far apart two wires must be to read as two",
            { OrthogonalRouter.LANE }, { OrthogonalRouter.LANE = it }, 4.0, 160.0),
        f("scopeGap", LAYOUT, "space between containers",
            { OwnCanvas.SCOPE_GAP }, { OwnCanvas.SCOPE_GAP = it }, 20.0, 400.0, 5.0),
        f("fnInset", LAYOUT, "side room inside a function box",
            { OwnCanvas.FN_BODY_INSET }, { OwnCanvas.FN_BODY_INSET = it }, 10.0, 200.0, 2.0),
        f("execWeight", LAYOUT, "how much more an exec wire counts when placing a box",
            { OwnCanvas.EXEC_WEIGHT }, { OwnCanvas.EXEC_WEIGHT = it }, 1.0, 12.0, 0.25),
        Knob("rowAspect", LAYOUT, "how much wider than tall a group is aimed at",
            { OwnCanvas.ROW_ASPECT }, { OwnCanvas.ROW_ASPECT = it }, 0.1, 6.0, 0.1),
    )

    val groups: List<String> = all.map { it.group }.distinct()

    fun of(name: String): Knob? = all.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Set one by name, for the debug socket. */
    fun set(name: String, value: Double): Knob? {
        val k = of(name) ?: return null
        apply(k, value)
        return k
    }

    /** Set one, and tell the canvas its cached routes are stale. */
    fun apply(k: Knob, value: Double) {
        if (Math.abs(k.get() - value) < 1e-9) return
        k.set(value)
        generation++
    }

    /** Put everything back to what it was compiled with. */
    fun reset() {
        all.forEach { it.set(it.default) }
        generation++
    }

    /** True when anything differs from what it was compiled with. */
    fun dirty(): Boolean = all.any { changed(it) }

    fun changed(k: Knob): Boolean = Math.abs(k.get() - k.default) > 1e-9

    /**
     * The changed values, as source, ready to paste back.
     *
     * The point of tuning is to end up with numbers in the source and a note saying why, so the last step
     * should not be reading them off a panel by eye.
     */
    fun changedAsSource(): String = all.filter { changed(it) }
        .joinToString("\n") { "${it.name} = ${trim(it.get())}   // was ${trim(it.default)}" }
        .ifEmpty { "nothing changed" }

    private fun trim(v: Double): String =
        if (Math.abs(v - Math.rint(v)) < 1e-9) Math.rint(v).toLong().toString() else String.format("%.2f", v)
}
