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
        g.color = Color(18, 20, 30); g.fillRect(0, 0, w, h)

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

        // floors
        g.color = Color(55, 58, 72)
        for (f in 0 until world.cfg.floors) {
            val y = py(view.floorY(f) - view.groundOffset).roundToInt()
            g.fillRect(0, y, w, 3)
        }
        // lift shafts + cars
        for (lift in world.lifts) {
            val lx = px(lift.column.toFloat()).roundToInt()
            g.color = Color(38, 40, 54); g.fillRect(lx - 3, 0, 6, h)
            blob(lift.column.toFloat(), view.floorYf(lift.carPos), 0.7f, Color(120, 122, 150))
        }
        // food
        for (cell in world.food) {
            blob(world.foodX(cell).toFloat(), view.floorY(world.foodFloor(cell)) - 0.7f, 0.32f, Color(240, 210, 70))
        }
        // creatures
        for (c in world.creatures) {
            val cy = if (c.ridingY >= 0f) view.floorYf(c.ridingY) else view.floorY(c.floor)
            drawCreature(g, c, c.x, cy, c.id == followId, ::px, ::py, sx, ::blob, ::col)
        }

        // HUD
        g.color = Color(220, 224, 235)
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
        val frac = ((c.metabolism - 0.003f) / (0.012f - 0.003f)).coerceIn(0f, 1f)
        val r = 0.25f + 0.6f * frac; val gr = 0.25f + 0.6f * (1f - frac); val b = 0.35f
        if (followed) blob(worldX, worldY + 0.6f, 1.0f, Color(255, 255, 255, 40)) // halo
        for (p in CreatureAnimation.pose(action, phase, c.facing, r, gr, b)) {
            blob(worldX + p.x * scale, worldY + p.y * scale, p.radius * scale, col(p.r, p.g, p.b))
        }
        if (c.carryingFood) blob(worldX + c.facing * 0.5f * scale, worldY + 0.05f * scale, 0.22f, Color(240, 210, 70))
        val (ar, ag, ab) = when (action) {
            CreatureAction.WALK -> Triple(90, 230, 100); CreatureAction.EAT -> Triple(240, 225, 80)
            CreatureAction.COURT -> Triple(240, 115, 190); CreatureAction.REST -> Triple(150, 150, 150)
        }
        blob(worldX, worldY + 1.3f * scale, 0.2f, Color(ar, ag, ab))
        if (followed) blob(worldX, worldY + 1.85f * scale, 0.16f, Color.WHITE)
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
