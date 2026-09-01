package dev.ziggle.vscript.editor.graph

import imgui.ImGui
import dev.ziggle.vscript.runtime.History

/**
 * The canvas camera: a uniform scale-then-translate between **graph space** (where nodes live, and what
 * the document stores) and **screen space** (pixels).
 *
 * ```
 * screen = graph * zoom + origin
 * graph  = (screen - origin) / zoom
 * ```
 *
 * Deliberately the whole camera model — no rotation, no per-axis scale. Everything downstream (hit
 * testing, dragging, link routing) converts through here rather than keeping its own copy of the maths,
 * because two transforms that disagree by a rounding step produce hit boxes that sit a pixel off what was
 * drawn, which is maddening to diagnose.
 */
class CanvasCamera {

    /** Screen position of graph-space origin. */
    var originX: Float = 0f
        private set
    var originY: Float = 0f
        private set

    var zoom: Float = 1f
        private set

    fun toScreenX(gx: Float): Float = gx * zoom + originX
    fun toScreenY(gy: Float): Float = gy * zoom + originY
    fun toGraphX(sx: Float): Float = (sx - originX) / zoom
    fun toGraphY(sy: Float): Float = (sy - originY) / zoom

    /** Scale a graph-space length to pixels. */
    fun px(len: Float): Float = len * zoom

    // ---- animation --------------------------------------------------------------------------------
    //
    // Every camera move is eased rather than applied outright. It is not decoration: a graph that jumps to
    // a new zoom or recentres instantly costs the viewer their place, and they have to re-find what they
    // were looking at. A 150ms ease carries the eye across, and is the single biggest reason the library
    // canvas felt better than a naive hand-rolled one.

    private var fromZoom = 1f
    private var fromOx = 0f
    private var fromOy = 0f
    private var toZoom = 1f
    private var toOx = 0f
    private var toOy = 0f
    private var animElapsed = 0f
    private var animDuration = 0f

    val isAnimating: Boolean get() = animDuration > 0f

    /** Ease toward a target view over [duration] seconds. */
    private fun animateTo(z: Float, ox: Float, oy: Float, duration: Float) {
        if (duration <= 0f) {
            zoom = z; originX = ox; originY = oy
            animDuration = 0f
            return
        }
        fromZoom = zoom; fromOx = originX; fromOy = originY
        toZoom = z; toOx = ox; toOy = oy
        animElapsed = 0f
        animDuration = duration
    }

    /** Advance any in-flight camera move. Call once a frame with the frame delta in seconds. */
    fun update(dt: Float) {
        if (animDuration <= 0f) return
        animElapsed += dt
        val t = (animElapsed / animDuration).coerceIn(0f, 1f)
        // Ease-out cubic: fast departure, gentle arrival. Linear reads as mechanical, ease-in-out feels
        // sluggish to start for a move the user just asked for.
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        zoom = fromZoom + (toZoom - fromZoom) * e
        originX = fromOx + (toOx - fromOx) * e
        originY = fromOy + (toOy - fromOy) * e
        if (t >= 1f) animDuration = 0f
    }

    /** Stop any in-flight move and hold the current view — a drag must take over immediately. */
    fun cancelAnimation() {
        animDuration = 0f
    }

    /**
     * The view as the undo history should record it: the animation **target** while a move is in flight.
     *
     * Recording the instantaneous position instead would capture a frame partway through an ease, so
     * undoing a zoom would land you somewhere nobody ever chose to be.
     */
    fun view(): History.ViewState =
        if (isAnimating) History.ViewState(toZoom, toOx, toOy) else History.ViewState(zoom, originX, originY)

    /** Ease back to a recorded view. Eased, not snapped, for the same reason every other move here is. */
    fun restore(v: History.ViewState, duration: Float = FIT_EASE) =
        animateTo(v.zoom, v.originX, v.originY, duration)

    /**
     * Move the origin because the VIEWPORT changed size, not because the user navigated.
     *
     * Distinct from [pan] in two ways that matter: it does not cancel an in-flight move (the user did not
     * touch the camera, so their journey should continue), and it shifts the animation target too — without
     * that, a resize during an ease would be undone the moment the ease finished.
     */
    fun shiftViewport(dxPixels: Float, dyPixels: Float) {
        originX += dxPixels
        originY += dyPixels
        if (isAnimating) {
            fromOx += dxPixels; fromOy += dyPixels
            toOx += dxPixels; toOy += dyPixels
        }
    }

    fun pan(dxPixels: Float, dyPixels: Float) {
        cancelAnimation()
        originX += dxPixels
        originY += dyPixels
    }

    /**
     * Zoom by [notches] about the point `(sx, sy)` **in screen space** — normally the cursor.
     *
     * Anchoring on the cursor rather than the viewport centre is the single change that makes zooming feel
     * like a map instead of a slider: the graph point under the pointer stays under the pointer. The step
     * is multiplicative so a notch is the same *proportion* at every zoom level; a linear step is coarse
     * when zoomed out and glacial when zoomed in.
     */
    fun zoomAt(sx: Float, sy: Float, notches: Float) {
        if (notches == 0f) return
        // Chain off the TARGET, not the current value, so spinning the wheel accumulates instead of each
        // notch restarting the ease from wherever the last one had got to.
        val base = if (isAnimating) toZoom else zoom
        val baseOx = if (isAnimating) toOx else originX
        val baseOy = if (isAnimating) toOy else originY
        val target = (base * Math.pow(ZOOM_STEP.toDouble(), notches.toDouble()).toFloat())
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (target == base) return
        // Keep the graph point under the cursor fixed: solve origin so toScreen(g) is unchanged.
        val gx = (sx - baseOx) / base
        val gy = (sy - baseOy) / base
        animateTo(target, sx - gx * target, sy - gy * target, ZOOM_EASE)
    }

    /** Centre [rect] (graph space) in a viewport of [viewW]×[viewH] pixels, with a little margin. */
    fun frame(rect: Rect?, viewW: Float, viewH: Float, duration: Float = FIT_EASE) {
        if (rect == null || rect.w <= 0f || rect.h <= 0f) {
            animateTo(1f, viewW * 0.5f, viewH * 0.5f, duration)
            return
        }
        val margin = 80f
        val fit = minOf((viewW - margin) / rect.w, (viewH - margin) / rect.h)
        val z = fit.coerceIn(MIN_ZOOM, MAX_ZOOM)
        animateTo(z, viewW * 0.5f - rect.centerX * z, viewH * 0.5f - rect.centerY * z, duration)
    }

    /**
     * Centre [rect] **without changing zoom** — the first half of the two-stage fit.
     *
     * Going straight to a zoom-fit on the first press is disorienting: the view both travels and changes
     * scale at once, so you lose your sense of how far you moved. Centring first answers "where is it?"
     * at the scale you were already reading, and only a second press answers "show me all of it".
     */
    fun centerOn(rect: Rect, viewW: Float, viewH: Float, duration: Float = FIT_EASE) {
        animateTo(zoom, viewW * 0.5f - rect.centerX * zoom, viewH * 0.5f - rect.centerY * zoom, duration)
    }

    /**
     * Is [rect] already centred? Compares against the animation TARGET while a move is in flight, so
     * pressing fit twice quickly escalates rather than re-issuing the same centring.
     */
    fun isCenteredOn(rect: Rect, viewW: Float, viewH: Float, tol: Float = 3f): Boolean {
        val z = if (isAnimating) toZoom else zoom
        val ox = if (isAnimating) toOx else originX
        val oy = if (isAnimating) toOy else originY
        return Math.abs(ox - (viewW * 0.5f - rect.centerX * z)) <= tol &&
            Math.abs(oy - (viewH * 0.5f - rect.centerY * z)) <= tol
    }

    /** Reset to the identity view. */
    fun reset(viewW: Float, viewH: Float) = frame(null, viewW, viewH)

    /** Jump with no animation — for seeding the view when a document first opens. */
    fun snapTo(rect: Rect?, viewW: Float, viewH: Float) = frame(rect, viewW, viewH, 0f)

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 2.5f

        /** Multiplicative zoom per wheel notch — see [zoomAt]. */
        const val ZOOM_STEP = 1.12f

        /** Below this, pin value editors collapse to plain text — they are unreadable smaller. */
        const val WIDGET_MIN_ZOOM = 0.55f

        /** Ease durations, seconds. Zoom is short enough to feel direct; a fit is a longer journey. */
        const val ZOOM_EASE = 0.12f
        const val FIT_EASE = 0.32f
    }
}

/** An axis-aligned rectangle in graph space. */
class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val centerX: Float get() = x + w * 0.5f
    val centerY: Float get() = y + h * 0.5f

    operator fun contains(p: Pair<Float, Float>): Boolean =
        p.first >= x && p.first <= right && p.second >= y && p.second <= bottom

    fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom

    fun intersects(o: Rect): Boolean = x < o.right && right > o.x && y < o.bottom && bottom > o.y

    fun expand(by: Float): Rect = Rect(x - by, y - by, w + by * 2, h + by * 2)

    companion object {
        /** The bounding box of [rects], or null when empty. */
        fun bounds(rects: Collection<Rect>): Rect? {
            if (rects.isEmpty()) return null
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (r in rects) {
                minX = minOf(minX, r.x); minY = minOf(minY, r.y)
                maxX = maxOf(maxX, r.right); maxY = maxOf(maxY, r.bottom)
            }
            return Rect(minX, minY, maxX - minX, maxY - minY)
        }

        /** A rect from two corners in any order — for a marquee dragged in any direction. */
        fun ofCorners(x1: Float, y1: Float, x2: Float, y2: Float): Rect =
            Rect(minOf(x1, x2), minOf(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1))
    }
}

/**
 * Text measurement at a graph-space font size, for node sizing.
 *
 * Delegates to `dev.ziggle.imgui.TextPaint`, which is where the measuring moved when the text-field widget
 * left this module. Kept as a name because seventeen sizing call sites read better for it.
 */
internal object TextMeasure {
    /** Width of [s] at the UI font's natural size, in graph units. */
    fun width(s: String): Float = dev.ziggle.imgui.TextPaint.width(s)

    fun lineHeight(): Float = dev.ziggle.imgui.TextPaint.lineHeight()
}
