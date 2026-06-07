package org.emerge.desktop

import org.emerge.demo.norns.anim.BodyPart
import org.emerge.demo.norns.anim.CreatureAnimation
import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.anim.PartShape
import org.emerge.demo.norns.anim.PosedPart
import org.emerge.demo.norns.world.ActivityType
import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsView
import org.emerge.demo.norns.world.NornsWorld
import org.emerge.demo.norns.world.WorldCreature
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.QuadCurve2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Headless CPU (Java2D) renderer for the Norns world → PNG. Mirrors the GPU view (same camera
 * [NornsView] + the same [CreatureAnimation] poses) but on the CPU, so frames can be produced
 * with no display/GPU and *inspected as images*. This is how the visuals get iterated without a
 * live window.
 */
object NornsImageRenderer {
    fun renderFrame(world: NornsWorld, cameraCenterX: Float, followId: Int?, w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // warm Albia-ish daylight gradient: soft cream sky → earthy soil
        g.paint = java.awt.GradientPaint(0f, 0f, Color(232, 220, 188), 0f, h.toFloat(), Color(74, 58, 44))
        g.fillRect(0, 0, w, h)

        val view = NornsView(world.cfg.worldWidth, world.cfg.floors)
        val aspect = w.toFloat() / h
        val left = view.cameraLeft(cameraCenterX, aspect)
        val horiz = view.horizontalUnits(aspect)
        val sx = w / horiz
        fun px(wx: Float) = ((wx - left) / horiz) * w
        fun py(wy: Float) = h - (wy / view.verticalUnits) * h
        fun blob(wx: Float, wy: Float, rWorld: Float, c: Color) {
            val rp = rWorld * sx
            g.color = c
            g.fillOval((px(wx) - rp).roundToInt(), (py(wy) - rp).roundToInt(), (2 * rp).roundToInt(), (2 * rp).roundToInt())
        }
        fun col(r: Float, gr: Float, b: Float) = Color(
            (r * 255).roundToInt().coerceIn(0, 255), (gr * 255).roundToInt().coerceIn(0, 255), (b * 255).roundToInt().coerceIn(0, 255),
        )

        // the top room is the open surface of Albia: sky, sun, clouds, distant hills
        drawSky(g, world, view, w, sx, ::py)
        // mottled earthy back wall for depth (scrolls with the world)
        drawBackdrop(g, world, view, left, horiz, sx, ::px, ::py)
        // floors as grassy soil slabs (the surfaces creatures stand on)
        val slab = (0.5f * sx).roundToInt().coerceAtLeast(6)
        val grass = (0.14f * sx).roundToInt().coerceAtLeast(3)
        for (f in 0 until world.cfg.floors) {
            val gy = py(view.floorY(f) - view.groundOffset).roundToInt()
            g.color = Color(86, 62, 42); g.fillRect(0, gy, w, slab)               // soil
            g.color = Color(70, 46, 30); g.fillRect(0, gy + slab - 2, w, 2)         // soil shadow line
            g.color = Color(104, 140, 66); g.fillRect(0, gy, w, grass)             // grass top
            g.color = Color(124, 162, 80); g.fillRect(0, gy, w, 2)                  // grass highlight
            // irregular grass blades along the edge (scroll with the world)
            val step = 0.28f
            var wx = floor(left / step) * step
            while (wx < left + horiz + step) {
                val hb = fhash((wx * 100).roundToInt(), f * 7 + 3)
                val bx = px(wx)
                val bw = 0.10f * sx
                val bhh = (0.30f + (hb % 5) * 0.06f) * sx
                val lean = ((hb % 7) - 3) * 0.02f * sx
                g.color = Color(92 + hb % 30, 134 + hb % 26, 58 + hb % 20)
                g.fillPolygon(
                    intArrayOf((bx - bw).roundToInt(), (bx + bw).roundToInt(), (bx + lean).roundToInt()),
                    intArrayOf(gy + 2, gy + 2, (gy - bhh).roundToInt()), 3,
                )
                wx += step
            }
        }
        // soft depth: ambient shadow rising from each ground line + a shadow under each ceiling,
        // so the rooms read as enclosed spaces rather than flat colour bands
        for (f in 0 until world.cfg.floors) {
            val gy = py(view.floorY(f) - view.groundOffset).roundToInt()
            val aoH = (1.3f * sx).roundToInt().coerceAtLeast(10)
            val aoTop = (gy - aoH).coerceAtLeast(0)
            g.paint = java.awt.GradientPaint(0f, aoTop.toFloat(), Color(34, 22, 14, 0), 0f, gy.toFloat(), Color(34, 22, 14, 78))
            g.fillRect(0, aoTop, w, gy - aoTop)
            if (f == world.cfg.floors - 1) continue // top room is open sky — no ceiling shadow
            val ceilY = py(view.floorY(f + 1) - view.groundOffset).roundToInt() + slab
            val csH = (0.8f * sx).roundToInt().coerceAtLeast(8)
            g.paint = java.awt.GradientPaint(0f, ceilY.toFloat(), Color(26, 16, 10, 90), 0f, (ceilY + csH).toFloat(), Color(26, 16, 10, 0))
            g.fillRect(0, ceilY, w, csH)
        }
        // layered Albia flora (grass tufts, reeds, flowers, hanging vines) — behind the creatures
        drawFlora(g, world, view, left, horiz, sx, ::px, ::py)
        // lift shafts (subtle vertical guide) + wooden platform cars
        for (lift in world.lifts) {
            val cx = px(lift.column.toFloat())
            g.color = Color(58, 44, 32, 90); g.fillRect((cx - 2).roundToInt(), 0, 4, h)
            val cy = py(view.floorYf(lift.carPos))
            val pw = (1.7f * sx).roundToInt(); val ph = (0.42f * sx).roundToInt()
            val x0 = (cx - pw / 2).roundToInt(); val y0 = (cy - ph / 2).roundToInt()
            g.color = Color(122, 86, 52); g.fillRoundRect(x0, y0, pw, ph, 8, 8)        // wood
            g.color = Color(150, 112, 70); g.fillRect(x0, y0, pw, 3)                    // lit top edge
        }
        // food as little fruit (berry + leaf)
        for (cell in world.food) {
            val fx = world.foodX(cell).toFloat(); val fy = view.floorY(world.foodFloor(cell)) - 0.5f
            blob(fx, fy, 0.27f, Color(212, 84, 60))                  // berry
            blob(fx - 0.08f, fy + 0.07f, 0.09f, Color(240, 150, 132)) // highlight
            blob(fx + 0.13f, fy + 0.28f, 0.10f, Color(110, 150, 64))  // leaf
        }
        // creatures
        for (c in world.creatures) {
            val cy = if (c.ridingY >= 0f) view.floorYf(c.ridingY) else view.floorY(c.floor)
            drawCreature(g, c, c.x, cy, c.id == followId, ::px, ::py, sx, ::blob, ::col)
        }

        // soft vignette to focus the eye toward the centre
        g.paint = RadialGradientPaint(
            Point2D.Float(w / 2f, h * 0.46f), w * 0.62f, floatArrayOf(0f, 0.6f, 1f),
            arrayOf(Color(0, 0, 0, 0), Color(0, 0, 0, 0), Color(18, 10, 4, 120)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fillRect(0, 0, w, h)

        // HUD (dark header bar for legibility over the bright sky)
        g.color = Color(26, 20, 14, 175); g.fillRect(0, 0, w, 50)
        g.color = Color(238, 233, 220)
        g.font = Font("SansSerif", Font.PLAIN, 14)
        g.drawString(
            "pop ${world.population}   food ${world.food.size}   born ${world.births}   died ${world.deaths}   " +
                "tick ${world.ticks}   meanMetab ${(world.meanMetabolism() * 10000).roundToInt()}",
            12, 22,
        )
        followId?.let { world.creatureById(it) }?.let { c ->
            g.drawString(
                "follow #${c.id}  ${c.biology.lifeStage.name.lowercase()}  age ${c.biology.age}  " +
                    "hunger ${(c.hunger * 100).roundToInt()}  urge ${(c.matingUrge * 100).roundToInt()}  " +
                    "fatigue ${(c.fatigue * 100).roundToInt()}  ${doing(c.activity)}",
                12, 42,
            )
        }
        g.dispose()
        return img
    }

    private fun drawCreature(
        g: java.awt.Graphics2D, c: WorldCreature, worldX: Float, worldY: Float, followed: Boolean,
        px: (Float) -> Float, py: (Float) -> Float, sx: Float,
        blob: (Float, Float, Float, Color) -> Unit, col: (Float, Float, Float) -> Color,
    ) {
        val action = when (c.activity) {
            ActivityType.EATING, ActivityType.PICKING_UP -> CreatureAction.EAT
            ActivityType.COURTING -> CreatureAction.COURT
            ActivityType.RESTING, ActivityType.IDLE -> CreatureAction.REST
            ActivityType.MOVING -> CreatureAction.WALK
        }
        val scale = 1.15f
        val phase = c.ticksLived * 0.35f
        // warm, earthy fur, gene-tinted: efficient = mossy/green, inefficient = rusty/red,
        // plus a small per-creature jitter so individuals aren't identical clones of one hue.
        val frac = ((c.metabolism - 0.003f) / (0.012f - 0.003f)).coerceIn(0f, 1f)
        val jt = (c.id * -1640531527) ushr 8
        val jr = ((jt and 0xFF) / 255f - 0.5f) * 0.11f
        val jg = (((jt ushr 8) and 0xFF) / 255f - 0.5f) * 0.10f
        val jb = (((jt ushr 16) and 0xFF) / 255f - 0.5f) * 0.08f
        val r = (0.55f + 0.30f * frac + jr).coerceIn(0.18f, 0.95f)
        val gr = (0.62f - 0.16f * frac + jg).coerceIn(0.18f, 0.95f)
        val b = (0.40f - 0.06f * frac + jb).coerceIn(0.14f, 0.9f)

        NornSprites.ensure()
        if (!NornSprites.ready) {
        val posed = CreatureAnimation.pose(action, phase, c.facing, r, gr, b)
        fun ecx(p: PosedPart) = px(worldX + p.x * scale)
        fun ecy(p: PosedPart) = py(worldY + p.y * scale)
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
        g.fillOval((px(worldX) - shW / 2).roundToInt(), (py(worldY - 0.86f * scale) - shH / 2).roundToInt(), shW, shH)

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
        val eye = eyeColor(c.id)
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

        } else {
            NornSprites.draw(g, c, action, worldX, worldY, px, py, sx)
        }
        if (c.carryingFood) blob(worldX + c.facing * 0.5f * scale, worldY + 0.05f * scale, 0.2f, Color(212, 84, 60))
        if (followed) blob(worldX, worldY + 1.7f * scale, 0.13f, Color(255, 255, 255)) // subtle follow marker
    }

    private fun isFaceDetail(part: BodyPart) = when (part) {
        BodyPart.EYE_LEFT, BodyPart.EYE_RIGHT, BodyPart.PUPIL_LEFT, BodyPart.PUPIL_RIGHT,
        BodyPart.NOSE, BodyPart.MOUTH, BodyPart.BROW_LEFT, BodyPart.BROW_RIGHT -> true
        else -> false
    }

    private fun c255(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
    private fun rgb(r: Float, g: Float, b: Float) = Color(c255(r), c255(g), c255(b))
    private fun rgbA(r: Float, g: Float, b: Float, a: Int) = Color(c255(r), c255(g), c255(b), a)

    private fun fillCircle(g: java.awt.Graphics2D, cx: Float, cy: Float, rad: Float) =
        g.fillOval((cx - rad).roundToInt(), (cy - rad).roundToInt(), (2 * rad).roundToInt(), (2 * rad).roundToInt())

    /** Breed eye colour (researched: Norns have blue or brown eyes; amber/green add variety). */
    private fun eyeColor(id: Int) = when (id % 4) {
        0 -> Color(96, 148, 206)  // blue
        1 -> Color(120, 78, 46)   // brown
        2 -> Color(196, 150, 60)  // amber
        else -> Color(96, 150, 96) // green
    }

    // the silhouette parts (unioned into one outlined body). Face details + belly sit on top.
    private fun isFur(part: BodyPart): Boolean = !isFaceDetail(part) && part != BodyPart.BELLY

    // ---- layered Albia flora: deterministic per (worldX, floor) so it's stable frame-to-frame ----

    private fun fhash(a: Int, b: Int): Int {
        var h = a * 73856093 xor b * 19349663
        h = h xor (h ushr 13)
        h *= 1274126177
        return h and 0x7fffffff
    }

    /** A soft-edged patch: radial gradient from [col] at the centre fading to fully transparent. */
    private fun softBlob(g: java.awt.Graphics2D, cx: Float, cy: Float, rad: Float, col: Color) {
        if (rad < 1f) return
        val edge = Color(col.red, col.green, col.blue, 0)
        g.paint = RadialGradientPaint(
            Point2D.Float(cx, cy), rad, floatArrayOf(0f, 1f), arrayOf(col, edge),
        )
        fillCircle(g, cx, cy, rad)
    }

    /** The top room is Albia's open surface: a sky gradient with a soft sun, clouds, and distant
     *  hazy hills sitting on the horizon (the top floor's grass line). */
    private fun drawSky(g: java.awt.Graphics2D, world: NornsWorld, view: NornsView, w: Int, sx: Float, py: (Float) -> Float) {
        val horizon = py(view.floorY(world.cfg.floors - 1) - view.groundOffset)
        val hy = horizon.roundToInt().coerceIn(1, 100000)
        g.paint = java.awt.GradientPaint(0f, 0f, Color(138, 184, 214), 0f, horizon, Color(216, 226, 205))
        g.fillRect(0, 0, w, hy)
        // sun glow
        softBlob(g, w * 0.80f, horizon * 0.42f, 2.0f * sx, Color(255, 238, 198, 120))
        softBlob(g, w * 0.80f, horizon * 0.42f, 0.85f * sx, Color(255, 250, 224, 210))
        // soft clouds (fixed to screen — they're far away)
        for ((cxF, cyF, rF) in listOf(Triple(0.16f, 0.30f, 1.1f), Triple(0.45f, 0.17f, 0.8f), Triple(0.64f, 0.40f, 0.95f))) {
            val cx = w * cxF; val cyy = horizon * cyF; val rr = rF * sx
            softBlob(g, cx, cyy, rr, Color(255, 255, 255, 150))
            softBlob(g, cx + rr * 0.7f, cyy + rr * 0.15f, rr * 0.8f, Color(255, 255, 255, 130))
            softBlob(g, cx - rr * 0.7f, cyy + rr * 0.1f, rr * 0.7f, Color(255, 255, 255, 130))
        }
        // distant hazy hills sitting on the horizon (bottoms covered by the floor slab)
        val hh = 1.9f * sx
        g.color = Color(150, 178, 150, 170)
        g.fillOval((w * 0.02f).roundToInt(), (horizon - hh * 0.5f).roundToInt(), (w * 0.52f).roundToInt(), hh.roundToInt())
        g.color = Color(126, 158, 132, 200)
        g.fillOval((w * 0.44f).roundToInt(), (horizon - hh * 0.42f).roundToInt(), (w * 0.62f).roundToInt(), hh.roundToInt())
    }

    /** Mottled earthy wall texture (soft dark + warm patches) so rooms aren't flat colour bands. */
    private fun drawBackdrop(
        g: java.awt.Graphics2D, world: NornsWorld, view: NornsView,
        left: Float, horiz: Float, sx: Float, px: (Float) -> Float, py: (Float) -> Float,
    ) {
        val x0 = floor(left).toInt() - 1
        val x1 = ceil(left + horiz).toInt() + 1
        for (f in 0 until world.cfg.floors - 1) { // top room is open sky, not earthy wall
            val groundY = py(view.floorY(f) - view.groundOffset)
            val ceilY = py(view.floorYf(f + 1f) - view.groundOffset) + 0.5f * sx
            for (wx in x0..x1) {
                val hh = fhash(wx, f * 53 + 17)
                val n = 1 + hh % 2
                for (k in 0 until n) {
                    val bx = px(wx + ((hh ushr (k * 5 + 8)) % 100) / 100f)
                    val ty = ceilY + (groundY - ceilY) * (((hh ushr (k * 3 + 1)) % 100) / 100f)
                    val rr = (0.7f + ((hh ushr k) % 3) * 0.5f) * sx
                    val dark = ((hh ushr (k + 4)) and 1) == 0
                    softBlob(g, bx, ty, rr, if (dark) Color(28, 18, 10, 40) else Color(168, 140, 96, 30))
                }
                // occasional ground pebble
                if (hh % 5 == 1) {
                    val pbx = px(wx + ((hh ushr 11) % 100) / 100f)
                    val pr = (0.16f + (hh % 3) * 0.06f) * sx
                    g.color = Color(108, 100, 92); fillCircle(g, pbx, groundY - pr * 0.4f, pr)
                    g.color = Color(134, 126, 118); fillCircle(g, pbx - pr * 0.25f, groundY - pr * 0.62f, pr * 0.5f)
                }
            }
        }
    }

    private fun drawFlora(
        g: java.awt.Graphics2D, world: NornsWorld, view: NornsView,
        left: Float, horiz: Float, sx: Float, px: (Float) -> Float, py: (Float) -> Float,
    ) {
        val x0 = floor(left).toInt() - 1
        val x1 = ceil(left + horiz).toInt() + 1
        for (f in 0 until world.cfg.floors) {
            val groundY = py(view.floorY(f) - view.groundOffset) + 2f
            // hanging growth from the cavern ceiling: woody roots + green creeper vines.
            // (the open-sky top room has nothing to hang from.)
            if (f != world.cfg.floors - 1) {
                val ceil = py(view.floorYf(f + 1f) - view.groundOffset) + 0.5f * sx
                for (wx in x0..x1) {
                    val hv = fhash(wx, f * 911 + 401)
                    if (hv % 6 == 0) drawVine(g, px(wx + (hv % 50) / 100f), ceil, sx, hv, isRoot = hv % 2 == 0)
                }
            } else {
                // surface trees on the open top room (drawn behind the foreground flora + creatures)
                for (wx in x0..x1) {
                    val ht = fhash(wx, 9173)
                    if (ht % 9 == 0) drawTree(g, px(wx + (ht % 40) / 100f), groundY, sx, ht)
                }
            }
            // distant, hazy foliage masses for depth (behind the sharp foreground flora)
            for (wx in x0..x1) {
                val hb = fhash(wx, f * 271 + 59)
                if (hb % 3 == 0) {
                    val bx = px(wx + (hb % 100) / 100f)
                    val rr = (0.6f + (hb % 3) * 0.32f) * sx
                    softBlob(g, bx, groundY - rr * 0.45f, rr, Color(58, 86, 50, 120))
                }
            }
            // ground flora: grass tufts, taller reeds, occasional flowers
            for (wx in x0..x1) {
                val h = fhash(wx, f * 131 + 7)
                if (h % 3 == 0) {
                    val bx = px(wx + ((h ushr 8) % 100) / 100f)
                    when ((h ushr 3) % 5) {
                        0, 1 -> drawTuft(g, bx, groundY, sx, h, tall = false)
                        2 -> drawTuft(g, bx, groundY, sx, h, tall = true)
                        else -> drawFlower(g, bx, groundY, sx, h)
                    }
                }
            }
        }
    }

    /** A strand hanging from the cavern ceiling — a woody [isRoot] root (brown, tapering, rootlets)
     *  or a green creeper vine (with leaves). */
    private fun drawVine(g: java.awt.Graphics2D, x: Float, topY: Float, sx: Float, h: Int, isRoot: Boolean) {
        val len = (1.4f + (h % 4) * 0.3f) * sx * (if (isRoot) 1.2f else 1f)
        val segs = 10
        val strand = if (isRoot) Color(116, 86, 54) else Color(86, 120, 58)
        var prevx = x; var prevy = topY
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            val yy = topY + len * t
            val xx = x + sin(t * 6f + (h % 10)) * (if (isRoot) 0.07f else 0.12f) * sx
            g.stroke = BasicStroke(max(1f, (if (isRoot) 0.07f else 0.045f) * sx * (1f - t * 0.6f)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = strand
            g.drawLine(prevx.roundToInt(), prevy.roundToInt(), xx.roundToInt(), yy.roundToInt())
            if (!isRoot && i % 3 == 0) {
                g.color = Color(104, 150, 66)
                g.fillOval((xx - 0.09f * sx).roundToInt(), (yy - 0.05f * sx).roundToInt(), (0.18f * sx).roundToInt(), (0.12f * sx).roundToInt())
            }
            prevx = xx; prevy = yy
        }
    }

    /** A surface tree: a tapered trunk and a soft layered leafy canopy reaching into the sky. */
    private fun drawTree(g: java.awt.Graphics2D, bx: Float, groundY: Float, sx: Float, h: Int) {
        val trunkH = (2.3f + (h % 3) * 0.5f) * sx
        val trunkW = 0.34f * sx
        val topY = groundY - trunkH
        g.color = Color(92, 64, 42)
        g.fillRoundRect((bx - trunkW / 2).roundToInt(), topY.roundToInt(), trunkW.roundToInt(), trunkH.roundToInt(), (trunkW * 0.5f).roundToInt(), (trunkW * 0.5f).roundToInt())
        g.color = Color(74, 50, 32)
        g.fillRoundRect((bx - trunkW / 2).roundToInt(), topY.roundToInt(), (trunkW * 0.35f).roundToInt(), trunkH.roundToInt(), (trunkW * 0.4f).roundToInt(), (trunkW * 0.4f).roundToInt())
        val cr = (1.6f + (h % 3) * 0.3f) * sx
        softBlob(g, bx, topY, cr, Color(72, 110, 56, 240))
        softBlob(g, bx - cr * 0.62f, topY + cr * 0.26f, cr * 0.72f, Color(82, 122, 62, 235))
        softBlob(g, bx + cr * 0.62f, topY + cr * 0.2f, cr * 0.78f, Color(88, 130, 66, 235))
        softBlob(g, bx, topY - cr * 0.42f, cr * 0.72f, Color(100, 144, 76, 230))
        softBlob(g, bx - cr * 0.34f, topY - cr * 0.32f, cr * 0.5f, Color(150, 186, 110, 150)) // sun-side highlight
    }

    private fun drawTuft(g: java.awt.Graphics2D, bx: Float, by: Float, sx: Float, h: Int, tall: Boolean) {
        val blades = 3 + (h % 3) + if (tall) 1 else 0
        val baseH = (if (tall) 0.95f else 0.5f) * sx
        for (i in 0 until blades) {
            val off = (i - blades / 2f) * 0.10f * sx
            val sway = (((h ushr (i + 1)) % 7) - 3) * 0.03f * sx
            val bh = baseH * (0.7f + ((h ushr i) % 4) * 0.1f)
            val tipx = bx + off + sway
            val half = (if (tall) 0.035f else 0.05f) * sx
            val gr = (122 + ((h ushr (i * 2)) % 44) - 22).coerceIn(96, 178)
            g.color = Color((68 + (h % 26)).coerceIn(0, 255), gr, (52 + (h % 22)).coerceIn(0, 255))
            g.fillPolygon(
                intArrayOf((bx + off - half).roundToInt(), (bx + off + half).roundToInt(), tipx.roundToInt()),
                intArrayOf(by.roundToInt(), by.roundToInt(), (by - bh).roundToInt()), 3,
            )
        }
    }

    private fun drawFlower(g: java.awt.Graphics2D, bx: Float, by: Float, sx: Float, h: Int) {
        val stemH = (0.6f + (h % 4) * 0.08f) * sx
        val topx = bx + (((h ushr 5) % 5) - 2) * 0.03f * sx
        val topy = by - stemH
        g.stroke = BasicStroke(max(1f, 0.04f * sx), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(78, 120, 56)
        g.drawLine(bx.roundToInt(), by.roundToInt(), topx.roundToInt(), topy.roundToInt())
        g.fillOval((bx + (topx - bx) * 0.4f - 0.02f * sx).roundToInt(), (by - stemH * 0.5f).roundToInt(), (0.16f * sx).roundToInt(), (0.09f * sx).roundToInt())
        val pcol = flowerColor(h)
        val pr = 0.11f * sx
        for (k in 0 until 5) {
            val a = k * 1.2566f + (h % 6) * 0.1f
            val pxp = topx + cos(a) * 0.12f * sx
            val pyp = topy + sin(a) * 0.12f * sx
            g.color = pcol
            g.fillOval((pxp - pr).roundToInt(), (pyp - pr).roundToInt(), (2 * pr).roundToInt(), (2 * pr).roundToInt())
        }
        g.color = Color(250, 214, 92)
        g.fillOval((topx - 0.07f * sx).roundToInt(), (topy - 0.07f * sx).roundToInt(), (0.14f * sx).roundToInt(), (0.14f * sx).roundToInt())
    }

    private fun flowerColor(h: Int): Color = when ((h ushr 6) % 5) {
        0 -> Color(226, 110, 138)   // pink
        1 -> Color(150, 120, 210)   // lavender
        2 -> Color(240, 150, 70)    // orange
        3 -> Color(232, 96, 84)     // red
        else -> Color(238, 226, 120) // pale yellow
    }

    private fun doing(a: ActivityType) = when (a) {
        ActivityType.IDLE -> "deciding"; ActivityType.MOVING -> "moving"; ActivityType.PICKING_UP -> "picking up"
        ActivityType.EATING -> "eating"; ActivityType.COURTING -> "courting"; ActivityType.RESTING -> "resting"
    }
}

/** Renders PNG frames of a run to a directory. args: [outDir] [seed] [tick1,tick2,...]. */
fun main(args: Array<String>) {
    val outDir = File(args.getOrNull(0) ?: "build/norns-frames").apply { mkdirs() }
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 7L
    val ticksToCapture = (args.getOrNull(2)?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(250, 700, 1200)).sorted()

    val world = NornsWorld(NornsConfig(), seed)
    val maxTick = ticksToCapture.max()
    for (t in 1..maxTick) {
        world.step()
        if (t in ticksToCapture) {
            val follow = world.creatures.maxByOrNull { it.biology.age }
            val img = NornsImageRenderer.renderFrame(world, follow?.x ?: 0f, follow?.id, 1000, 620)
            val file = File(outDir, "norns_t$t.png")
            ImageIO.write(img, "png", file)
            println("wrote ${file.absolutePath}")
            // also write a 2× zoom crop centred on the followed creature, for close inspection
            follow?.let { fc ->
                val view = NornsView(world.cfg.worldWidth, world.cfg.floors)
                val cyS = 620f - ((view.floorY(fc.floor) + 0.4f) / view.verticalUnits) * 620f
                val cw = 320; val ch = 320
                val x0 = ((1000 - cw) / 2).coerceIn(0, 1000 - cw)
                val y0 = (cyS.roundToInt() - ch / 2).coerceIn(0, 620 - ch)
                val zoom = BufferedImage(cw * 2, ch * 2, BufferedImage.TYPE_INT_RGB)
                val zg = zoom.createGraphics()
                zg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                zg.drawImage(img.getSubimage(x0, y0, cw, ch), 0, 0, cw * 2, ch * 2, null)
                zg.dispose()
                ImageIO.write(zoom, "png", File(outDir, "norns_t${t}_zoom.png"))

                // surface crop: the sky / top room (to inspect trees, clouds, hills)
                val topGrass = 620f - (view.floorY(world.cfg.floors - 1) / view.verticalUnits) * 620f
                val sw = 460; val sh = 300
                val sy0 = (topGrass.roundToInt() - sh + 70).coerceIn(0, 620 - sh)
                val surf = BufferedImage(sw * 2, sh * 2, BufferedImage.TYPE_INT_RGB)
                val sg = surf.createGraphics()
                sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                sg.drawImage(img.getSubimage(270, sy0, sw, sh), 0, 0, sw * 2, sh * 2, null)
                sg.dispose()
                ImageIO.write(surf, "png", File(outDir, "norns_t${t}_surface.png"))
            }
        }
    }
}
