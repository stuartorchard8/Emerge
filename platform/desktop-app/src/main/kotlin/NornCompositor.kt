package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a [NornRigDef] — the procedurally-animated, sprite-part Norn — to Java2D. Runs the rig's FK
 * pose, fits the whole assembly to a target height, plants the feet at the given screen point, flips
 * by facing, and applies the global per-action lean (about the feet) + courting hop, then blits each
 * sprite part back-to-front by z-order. Every anchor/pivot/rotation comes from editable rig data
 * ([NornRigDef]); this is the sole creature renderer for the live world and the editor.
 */
object NornCompositor {

    /** Draw the composited creature with feet centred at ([originX],[originY]); [sx] = world→px. If
     *  [highlight] names a part, outline it + mark its anchor (the editor's current selection). */
    fun draw(
        g: Graphics2D, def: NornRigDef, sprites: Map<String, NornParts.Part>,
        action: CreatureAction, phase: Float, facing: Int,
        originX: Float, originY: Float, sx: Float, targetHeightUnits: Float = 2.95f,
        groundOffset: Float = 0f, holdFoodInHand: Boolean = false, highlight: String? = null,
    ) {
        val placed = def.pose(sprites, action, phase)
        if (placed.isEmpty()) return
        val plantY = originY + groundOffset * sx   // seat the rig on the floor (+ = lower)

        // overall bounds (transform each part image's corners into body-local space)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in placed) {
            for (c in corners(p.img.width, p.img.height)) {
                val pt = Point2D.Float(c.first, c.second); p.transform.transform(pt, pt)
                minX = min(minX, pt.x); minY = min(minY, pt.y); maxX = max(maxX, pt.x); maxY = max(maxY, pt.y)
            }
        }
        val rigH = (maxY - minY).coerceAtLeast(1f)
        val bottom = maxY
        // Horizontal anchor: normally the silhouette centre. For PICK_UP, pin the LEFT foot's
        // ground point instead, so it stays put as the norn crouches (the bbox centre would drift
        // with the lean and slide the feet). Vertical still uses bbox-bottom — seating handles height.
        var centerX = (minX + maxX) / 2f
        if (action == CreatureAction.PICK_UP) {
            placed.firstOrNull { it.id == "footL" }?.let { foot ->
                val p = Point2D.Float()
                foot.transform.transform(Point2D.Float(foot.img.width / 2f, foot.img.height.toFloat()), p)
                centerX = p.x
            }
        }
        val scale = targetHeightUnits * sx / rigH

        val g0 = global(def, action, phase, originX, plantY, scale, facing, sx, centerX, bottom)

        val oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        for (p in placed.sortedBy { it.z }) {
            val at = AffineTransform(g0); at.concatenate(p.transform)
            g.drawImage(p.img, at, null)
        }
        if (oldInterp != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp)

        // a held morsel in the right hand (the gesturing/near arm) — tracks the eat reach
        if (holdFoodInHand) {
            val hand = placed.firstOrNull { it.id == "farmR" }
            val sprite = sprites["farmR"]
            if (hand != null && sprite != null) {
                val end = sprite.pt("end")
                val at = AffineTransform(g0); at.concatenate(hand.transform)
                val p = tp(at, end[0], end[1])
                drawFood(g, p.x, p.y, 0.2f * sx)   // 1 world unit = sx px on screen
            }
        }

        // selection overlay: outline the part + a dot at its anchor (where it joins its parent)
        highlight?.let { hid ->
            val sel = placed.firstOrNull { it.id == hid } ?: return@let
            val at = AffineTransform(g0); at.concatenate(sel.transform)
            val poly = corners(sel.img.width, sel.img.height).map { tp(at, it.first, it.second) }
            g.color = Color(255, 210, 60, 230)
            g.stroke = BasicStroke(2f)
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[(i + 1) % poly.size]
                g.drawLine(a.x.roundToInt(), a.y.roundToInt(), b.x.roundToInt(), b.y.roundToInt())
            }
            def.part(hid)?.let { dp ->
                val anchorPt = tp(at, dp.pivotU * sel.img.width, dp.pivotV * sel.img.height)  // normalized pivot → image px
                g.color = Color(255, 80, 80)
                g.fillOval((anchorPt.x - 4).roundToInt(), (anchorPt.y - 4).roundToInt(), 8, 8)
            }
        }
    }

    private fun tp(at: AffineTransform, x: Float, y: Float): Point2D.Float {
        val d = Point2D.Float(); at.transform(Point2D.Float(x, y), d); return d
    }

    /** A little fruit (berry + highlight + leaf), radius [r] px — held food and ground-food guides. */
    fun drawFood(g: Graphics2D, cx: Float, cy: Float, r: Float) {
        g.color = Color(212, 84, 60); fillCircle(g, cx, cy, r)
        g.color = Color(240, 150, 132); fillCircle(g, cx - r * 0.3f, cy - r * 0.3f, r * 0.42f)
        g.color = Color(110, 150, 64); fillCircle(g, cx + r * 0.55f, cy - r * 0.6f, r * 0.45f)
    }

    private fun fillCircle(g: Graphics2D, cx: Float, cy: Float, r: Float) =
        g.fillOval((cx - r).roundToInt(), (cy - r).roundToInt(), (2 * r).roundToInt(), (2 * r).roundToInt())

    /** The screen transform: feet at (originX, originY−hop), scaled, flipped, leaned about the feet. */
    private fun global(
        def: NornRigDef, action: CreatureAction, phase: Float,
        originX: Float, originY: Float, scale: Float, facing: Int, sx: Float, centerX: Float, bottom: Float,
    ): AffineTransform {
        val gA = def.global[action] ?: GlobalAnim()
        val hop = abs(sin(phase * gA.hopFreq)) * gA.hopAmp * sx
        val flip = facing < 0
        val t = AffineTransform()
        t.translate(originX.toDouble(), (originY - hop).toDouble())
        t.scale((if (flip) -scale else scale).toDouble(), scale.toDouble())
        if (gA.lean != 0f) t.rotate(gA.lean.toDouble())
        t.translate(-centerX.toDouble(), -bottom.toDouble())
        return t
    }

    private fun corners(w: Int, h: Int) = listOf(0f to 0f, w.toFloat() to 0f, w.toFloat() to h.toFloat(), 0f to h.toFloat())
}
