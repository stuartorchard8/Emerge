package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAnimation
import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.world.ActivityType
import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsView
import org.emerge.demo.norns.world.NornsWorld
import org.emerge.demo.norns.world.WorldCreature
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

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

        // floors as grassy soil slabs (the surfaces creatures stand on)
        val slab = (0.5f * sx).roundToInt().coerceAtLeast(6)
        val grass = (0.14f * sx).roundToInt().coerceAtLeast(3)
        for (f in 0 until world.cfg.floors) {
            val gy = py(view.floorY(f) - view.groundOffset).roundToInt()
            g.color = Color(86, 62, 42); g.fillRect(0, gy, w, slab)               // soil
            g.color = Color(70, 46, 30); g.fillRect(0, gy + slab - 2, w, 2)         // soil shadow line
            g.color = Color(104, 140, 66); g.fillRect(0, gy, w, grass)             // grass top
            g.color = Color(124, 162, 80); g.fillRect(0, gy, w, 2)                  // grass highlight
        }
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
        val scale = 1.1f
        val phase = c.ticksLived * 0.35f
        // warm, earthy fur, gene-tinted: efficient = mossy/green, inefficient = rusty/red
        val frac = ((c.metabolism - 0.003f) / (0.012f - 0.003f)).coerceIn(0f, 1f)
        val r = 0.55f + 0.30f * frac; val gr = 0.62f - 0.16f * frac; val b = 0.40f - 0.06f * frac
        // ground shadow (grounds the creature + separates it from neighbours/background)
        val shW = (0.95f * scale * sx).roundToInt(); val shH = (0.22f * scale * sx).roundToInt()
        g.color = Color(0, 0, 0, 70)
        g.fillOval((px(worldX) - shW / 2).roundToInt(), (py(worldY - 0.82f * scale) - shH / 2).roundToInt(), shW, shH)

        val posed = CreatureAnimation.pose(action, phase, c.facing, r, gr, b)
        // pass 1: soft dark silhouette outline (fur parts enlarged) so the creature pops
        val outline = Color(46, 32, 22)
        for (p in posed) if (isFur(p.part)) blob(worldX + p.x * scale, worldY + p.y * scale, p.radius * scale * 1.15f + 0.03f, outline)
        // pass 2: the parts themselves
        for (p in posed) blob(worldX + p.x * scale, worldY + p.y * scale, p.radius * scale, col(p.r, p.g, p.b))
        // eye glints — a little life
        for (p in posed) if (p.part == org.emerge.demo.norns.anim.BodyPart.PUPIL_BACK || p.part == org.emerge.demo.norns.anim.BodyPart.PUPIL_FRONT) {
            blob(worldX + (p.x + 0.02f) * scale, worldY + (p.y + 0.05f) * scale, 0.032f * scale, Color(255, 255, 255))
        }

        if (c.carryingFood) blob(worldX + c.facing * 0.5f * scale, worldY + 0.05f * scale, 0.2f, Color(212, 84, 60))
        if (followed) blob(worldX, worldY + 1.7f * scale, 0.13f, Color(255, 255, 255)) // subtle follow marker
    }

    private fun isFur(part: org.emerge.demo.norns.anim.BodyPart): Boolean = when (part) {
        org.emerge.demo.norns.anim.BodyPart.EYE_BACK, org.emerge.demo.norns.anim.BodyPart.EYE_FRONT,
        org.emerge.demo.norns.anim.BodyPart.PUPIL_BACK, org.emerge.demo.norns.anim.BodyPart.PUPIL_FRONT -> false
        else -> true
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
        }
    }
}
