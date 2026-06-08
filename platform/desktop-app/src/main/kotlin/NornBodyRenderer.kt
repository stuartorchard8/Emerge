package org.emerge.desktop

import org.emerge.demo.norns.anim.AnimParams
import org.emerge.demo.norns.anim.BodyPart
import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.anim.CreatureAnimation
import org.emerge.demo.norns.anim.PartShape
import org.emerge.demo.norns.anim.PosedPart
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.QuadCurve2D
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a single **procedural** Norn (the [CreatureAnimation] skeleton) in Java2D — the canonical
 * hand-built look, decoupled from the world/camera so both the live world ([NornsImageRenderer]) and
 * the animation viewer ([NornsAnimViewer]) render it identically.
 *
 * Geometry mirrors the world renderer exactly: a part at body coords `(p.x, p.y)` lands at
 * `originX + p.x·scale·sx`, `originY − p.y·scale·sx` (origin = the creature's world-y=0 centre in
 * screen px, `scale` = the creature scale, `sx` = world-units→px). Pass an [AnimParams] to retune the
 * pose live; the default reproduces the shipped baseline.
 */
object NornBodyRenderer {

    /** Draw the posed creature centred on screen point ([originX],[originY]) (its body-y=0 line). */
    fun draw(
        g: Graphics2D, action: CreatureAction, phase: Float, facing: Int,
        r: Float, gr: Float, b: Float, eye: Color,
        originX: Float, originY: Float, scale: Float, sx: Float,
        params: AnimParams = AnimParams.DEFAULT,
    ) {
        val posed = CreatureAnimation.pose(action, phase, facing, r, gr, b, params)
        fun ecx(p: PosedPart) = originX + p.x * scale * sx
        fun ecy(p: PosedPart) = originY - p.y * scale * sx
        fun ehw(p: PosedPart) = p.halfW * scale * sx
        fun ehh(p: PosedPart) = p.halfH * scale * sx
        // a part's drawn shape in screen space (rotated ellipse, or a pointed triangle for ears)
        fun shapeOf(p: PosedPart): java.awt.Shape {
            val cx = ecx(p).toDouble(); val cy = ecy(p).toDouble(); val hw = ehw(p).toDouble(); val hh = ehh(p).toDouble()
            val base: java.awt.Shape = when (p.shape) {
                PartShape.ELLIPSE -> Ellipse2D.Double(cx - hw, cy - hh, 2 * hw, 2 * hh)
                PartShape.TRIANGLE -> Path2D.Double().apply {
                    moveTo(cx, cy - hh); lineTo(cx - hw, cy + hh * 0.7); lineTo(cx + hw, cy + hh * 0.7); closePath()
                }
            }
            return if (p.angle != 0f) AffineTransform.getRotateInstance(p.angle.toDouble(), cx, cy).createTransformedShape(base) else base
        }

        // ground shadow (grounds the creature + separates it from neighbours/background)
        val shW = (0.95f * scale * sx).roundToInt(); val shH = (0.22f * scale * sx).roundToInt()
        g.color = Color(0, 0, 0, 70)
        g.fillOval((originX - shW / 2).roundToInt(), (originY + 0.86f * scale * sx - shH / 2).roundToInt(), shW, shH)

        // one unified silhouette (union of the fur parts) → a single clean outline
        val body = Area()
        for (p in posed) if (isFur(p.part)) body.add(Area(shapeOf(p)))
        val ow = (0.14f * scale * sx).coerceIn(3.5f, 12f)
        g.stroke = BasicStroke(ow, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(42, 28, 18); g.draw(body)          // outline (outer half survives the fill)
        g.color = rgb(r, gr, b); g.fill(body)              // base coat (kills antialias seams)

        // shade the WHOLE silhouette as one form (top-down light), then soft accents — matte, not glossy
        fun firstOf(bp: BodyPart) = posed.firstOrNull { it.part == bp }
        val oldClip = g.clip
        g.clip(body)
        val bnd = body.bounds2D
        g.paint = java.awt.GradientPaint(
            0f, bnd.minY.toFloat(), rgb(r + (1f - r) * 0.22f, gr + (1f - gr) * 0.22f, b + (1f - b) * 0.22f),
            0f, bnd.maxY.toFloat(), rgb(r * 0.56f, gr * 0.56f, b * 0.56f),
        )
        g.fill(body)
        // paler muzzle + cream belly, blended softly into the form
        firstOf(BodyPart.MUZZLE)?.let { softBlob(g, ecx(it), ecy(it), ehw(it) * 1.2f, rgbA(it.r, it.g, it.b, 200)) }
        firstOf(BodyPart.BELLY)?.let { softBlob(g, ecx(it), ecy(it) + ehh(it) * 0.1f, ehh(it) * 1.15f, rgbA(it.r, it.g, it.b, 205)) }
        // pale inner ears
        for (p in posed) if (p.part == BodyPart.EAR_LEFT || p.part == BodyPart.EAR_RIGHT) {
            softBlob(g, ecx(p), ecy(p) + ehh(p) * 0.18f, ehw(p) * 0.7f, Color(228, 188, 180, 200))
        }
        // a single soft matte sheen high on the head (no plastic gloss)
        firstOf(BodyPart.HEAD)?.let { softBlob(g, ecx(it) - ehw(it) * 0.3f, ecy(it) - ehh(it) * 0.42f, ehw(it) * 0.66f, Color(255, 252, 240, 40)) }
        // contact shadows: under the head, and a gap between the legs so they read as two
        firstOf(BodyPart.HEAD)?.let { softBlob(g, ecx(it), ecy(it) + ehh(it) * 0.95f, ehw(it) * 0.68f, Color(0, 0, 0, 55)) }
        val ll = firstOf(BodyPart.LEG_LEFT); val lr = firstOf(BodyPart.LEG_RIGHT)
        if (ll != null && lr != null) softBlob(g, (ecx(ll) + ecx(lr)) / 2f, (ecy(ll) + ecy(lr)) / 2f - ehh(ll) * 0.2f, ehw(ll) * 0.7f, Color(0, 0, 0, 60))
        g.clip = oldClip

        // ---- expressive forward-facing face (on top, unclipped) ----
        // brows
        for (p in posed) if (p.part == BodyPart.BROW_LEFT || p.part == BodyPart.BROW_RIGHT) {
            g.color = rgb(p.r, p.g, p.b); g.fill(shapeOf(p))
        }
        // eye whites
        for (p in posed) if (p.part == BodyPart.EYE_LEFT || p.part == BodyPart.EYE_RIGHT) {
            g.color = Color(249, 248, 243); g.fill(shapeOf(p))
            g.color = Color(208, 202, 190); fillCircle(g, ecx(p), ecy(p) + ehh(p) * 0.4f, ehw(p) * 0.6f) // lower shadow
            g.color = Color(249, 248, 243); fillCircle(g, ecx(p), ecy(p) - ehh(p) * 0.1f, ehw(p) * 0.78f)
        }
        // iris + pupil + glint
        for (p in posed) if (p.part == BodyPart.PUPIL_LEFT || p.part == BodyPart.PUPIL_RIGHT) {
            val cx = ecx(p); val cy = ecy(p); val rr = ehw(p)
            g.color = eye; fillCircle(g, cx, cy, rr * 1.5f)                                   // big cute iris
            g.color = Color(22, 18, 26); fillCircle(g, cx, cy, rr)                            // pupil
            g.color = Color(255, 255, 255, 240); fillCircle(g, cx - rr * 0.45f, cy - rr * 0.55f, rr * 0.5f) // main catchlight
            g.color = Color(255, 255, 255, 170); fillCircle(g, cx + rr * 0.4f, cy + rr * 0.35f, rr * 0.24f) // tiny sparkle
        }
        // nose
        firstOf(BodyPart.NOSE)?.let { g.color = rgb(it.r, it.g, it.b); g.fill(shapeOf(it)) }
        // mouth: a soft smile arc just under the muzzle
        firstOf(BodyPart.MOUTH)?.let {
            val cx = ecx(it); val cy = ecy(it); val mw = ehw(it); val mh = ehh(it)
            g.stroke = BasicStroke(max(1.6f, 0.045f * sx), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = Color(60, 34, 28)
            g.draw(QuadCurve2D.Float(cx - mw, cy, cx, cy + mh * 2.9f, cx + mw, cy))
        }
        // sleepy half-lids when resting (fur lid + dark crease) — a visible doze
        if (action == CreatureAction.REST) {
            for (p in posed) if (p.part == BodyPart.EYE_LEFT || p.part == BodyPart.EYE_RIGHT) {
                val cx = ecx(p); val cy = ecy(p); val rw = ehw(p); val rh = ehh(p)
                g.color = rgb(r * 0.95f, gr * 0.95f, b * 0.95f)
                g.fillOval((cx - rw * 1.05f).roundToInt(), (cy - rh * 1.55f).roundToInt(), (rw * 2.1f).roundToInt(), (rh * 2.1f).roundToInt())
                g.stroke = BasicStroke(max(1.5f, 0.03f * sx), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = Color(58, 40, 28)
                g.drawLine((cx - rw * 0.85f).roundToInt(), (cy + rh * 0.42f).roundToInt(), (cx + rw * 0.85f).roundToInt(), (cy + rh * 0.42f).roundToInt())
            }
        }
    }

    /** Breed eye colour (researched: Norns have blue or brown eyes; amber/green add variety). */
    fun eyeColor(id: Int) = when (id % 4) {
        0 -> Color(96, 148, 206)  // blue
        1 -> Color(120, 78, 46)   // brown
        2 -> Color(196, 150, 60)  // amber
        else -> Color(96, 150, 96) // green
    }

    private fun isFaceDetail(part: BodyPart) = when (part) {
        BodyPart.EYE_LEFT, BodyPart.EYE_RIGHT, BodyPart.PUPIL_LEFT, BodyPart.PUPIL_RIGHT,
        BodyPart.NOSE, BodyPart.MOUTH, BodyPart.BROW_LEFT, BodyPart.BROW_RIGHT -> true
        else -> false
    }

    // the silhouette parts (unioned into one outlined body). Face details + belly sit on top.
    private fun isFur(part: BodyPart): Boolean = !isFaceDetail(part) && part != BodyPart.BELLY

    private fun c255(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
    private fun rgb(r: Float, g: Float, b: Float) = Color(c255(r), c255(g), c255(b))
    private fun rgbA(r: Float, g: Float, b: Float, a: Int) = Color(c255(r), c255(g), c255(b), a)

    private fun fillCircle(g: Graphics2D, cx: Float, cy: Float, rad: Float) =
        g.fillOval((cx - rad).roundToInt(), (cy - rad).roundToInt(), (2 * rad).roundToInt(), (2 * rad).roundToInt())

    /** A soft-edged patch: radial gradient from [col] at the centre fading to fully transparent. */
    private fun softBlob(g: Graphics2D, cx: Float, cy: Float, rad: Float, col: Color) {
        if (rad < 1f) return
        val edge = Color(col.red, col.green, col.blue, 0)
        g.paint = RadialGradientPaint(
            Point2D.Float(cx, cy), rad, floatArrayOf(0f, 1f), arrayOf(col, edge),
        )
        fillCircle(g, cx, cy, rad)
    }
}
