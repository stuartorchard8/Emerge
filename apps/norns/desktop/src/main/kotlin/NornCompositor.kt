package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [NornRigDef] — the procedurally-animated, sprite-part Norn — to Java2D. Runs the rig's FK
 * pose, fits the whole assembly to a target height, plants the feet at the given screen point, flips
 * by facing, and applies the global per-action lean (about the feet) + courting hop, then blits each
 * sprite part back-to-front by z-order. Every anchor/pivot/rotation comes from editable rig data
 * ([NornRigDef]); this is the sole creature renderer for the live world and the editor.
 */
object NornCompositor {

    /** How (if at all) to draw a food item: NONE; HAND (held in the right hand); or PICKUP (on the
     *  ground for the first half of the cycle, then grabbed into the hand from the half-way point). */
    enum class FoodMode { NONE, HAND, PICKUP }

    /** Draw the composited creature with feet centred at ([originX],[originY]); [sx] = world→px. If
     *  [highlight] names a part, outline it + mark its anchor (the editor's current selection). */
    fun draw(
        g: Graphics2D, def: NornRigDef, sprites: Map<String, NornParts.Part>,
        action: CreatureAction, phase: Float, facing: Int,
        originX: Float, originY: Float, sx: Float, targetHeightUnits: Float,
        groundOffset: Float = 0f, food: FoodMode = FoodMode.NONE, highlight: String? = null,
        blendFrom: CreatureAction? = null, blendFromPhase: Float = 0f, blendT: Float = 1f,
    ) {
        // resolve the pose for this action+phase; if mid-transition, blend from the previous action's
        // frozen pose so the switch eases in over a few ticks instead of snapping.
        val frame = if (blendFrom != null && blendT < 1f)
            def.blendFrames(def.frameOf(blendFrom, blendFromPhase), def.frameOf(action, phase), blendT)
        else def.frameOf(action, phase)
        val placed = def.pose(sprites, frame)
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
        val rigH = (maxY - minY).coerceAtLeast(1f)   // full silhouette height drives the scale
        // Plant reference: normally the silhouette centre-X + bbox-bottom. For PICK_UP, pin the LEFT
        // foot's ground point in BOTH axes, so it stays put as the norn crouches (the bbox would
        // drift with the lean → feet slide, and an arm reaching below the foot would float the foot).
        // groundOffset still fine-tunes height on top.
        var centerX = (minX + maxX) / 2f
        var bottom = maxY
        if (action == CreatureAction.PICK_UP) {
            placed.firstOrNull { it.id == "footL" }?.let { foot ->
                // use the foot's transformed BOUNDING BOX (centre-x, lowest-y), not a fixed sprite
                // point, so an unconventional pose (e.g. the baby's soles-up foot) still anchors at
                // its true ground contact.
                var fx0 = Float.MAX_VALUE; var fx1 = -Float.MAX_VALUE; var fy1 = -Float.MAX_VALUE
                for (c in corners(foot.img.width, foot.img.height)) {
                    val p = Point2D.Float(); foot.transform.transform(Point2D.Float(c.first, c.second), p)
                    fx0 = min(fx0, p.x); fx1 = max(fx1, p.x); fy1 = max(fy1, p.y)
                }
                centerX = (fx0 + fx1) / 2f; bottom = fy1
            }
        }
        val scale = targetHeightUnits * sx / rigH

        val g0 = global(frame.lean, frame.hop, originX, plantY, scale, facing, sx, centerX, bottom)

        // food: held in the right hand, or — for a pick-up — on the ground for the first half of the
        // cycle, then "attached" to the hand from the half-way point on (the grab).
        val tHalf = (phase / (2.0 * Math.PI).toFloat()).rem(1f)      // 0..1 within the cycle (phase > 0)
        val inHand = food == FoodMode.HAND || (food == FoodMode.PICKUP && tHalf >= 0.5f)
        val onGround = food == FoodMode.PICKUP && tHalf < 0.5f
        val foodR = 0.2f * sx                                        // 1 world unit = sx px on screen
        val handFoodZ = (def.part("farmR")?.z ?: 999) - 1            // just behind the near arm
        // hand-held position: the farmR tip + an offset applied in the ARM's local frame (so it
        // rotates with the hand), then carried through the same transform. The chain scales arm-image
        // px by `scale`, so 1 world unit = sx/scale arm-px; facing/flip is handled by g0.
        val hand = placed.firstOrNull { it.id == "farmR" }
        val handSprite = sprites["farmR"]
        val handPos: Point2D.Float? = if (inHand && hand != null && handSprite != null) {
            val end = handSprite.pt("end")
            val k = sx / scale
            tp(AffineTransform(g0).apply { concatenate(hand.transform) }, end[0] + def.heldFoodX * k, end[1] - def.heldFoodY * k)
        } else null

        val oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        if (onGround) drawFood(g, originX + facing * def.pickupReachX * sx, plantY, foodR)  // on the ground, behind
        var handFoodDrawn = false
        for (p in placed.sortedBy { it.z }) {
            if (handPos != null && !handFoodDrawn && p.z > handFoodZ) { drawFood(g, handPos.x, handPos.y, foodR); handFoodDrawn = true }
            val at = AffineTransform(g0); at.concatenate(p.transform)
            g.drawImage(p.img, at, null)
        }
        if (handPos != null && !handFoodDrawn) drawFood(g, handPos.x, handPos.y, foodR)
        if (oldInterp != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp)

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
        lean: Float, hop: Float,
        originX: Float, originY: Float, scale: Float, facing: Int, sx: Float, centerX: Float, bottom: Float,
    ): AffineTransform {
        val flip = facing < 0
        val t = AffineTransform()
        t.translate(originX.toDouble(), (originY - hop * sx).toDouble())
        t.scale((if (flip) -scale else scale).toDouble(), scale.toDouble())
        if (lean != 0f) t.rotate(lean.toDouble())
        t.translate(-centerX.toDouble(), -bottom.toDouble())
        return t
    }

    private fun corners(w: Int, h: Int) = listOf(0f to 0f, w.toFloat() to 0f, w.toFloat() to h.toFloat(), 0f to h.toFloat())
}
