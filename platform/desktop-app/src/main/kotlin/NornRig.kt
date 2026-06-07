package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.world.WorldCreature
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Real Creatures-2 Norn renderer. Each life-stage age (0=baby .. 3=adult) has a sheet of side-
 * facing **poses** baked from the breed's actual S16 parts (see `tools/norns-sprites/bake_breed.py`):
 *   p0 = crawl / head-down, p1 = walk-lean, p2 = upright stand, p3 = reach-up.
 * This is how Creatures itself animates — by cycling whole-body poses, not by articulating one. We
 * pick a pose by life stage + action (babies/children crawl using the low poses; older Norns walk
 * upright), cycle two poses for the stride, flip horizontally for facing, and scale by age. Genuine
 * C2 art; no rotation/FK hacks. Placeholder breed (Denali) until original art replaces it.
 */
object NornRig {
    private class Age(val sheet: BufferedImage, val cellW: Int, val cellH: Int, val anchorX: Int, val anchorY: Int)
    private val ages = HashMap<Int, Age>()
    private var loaded = false
    val ready: Boolean get() = ages.isNotEmpty()

    fun ensure() { if (loaded) return; loaded = true; try { load() } catch (e: Exception) { System.err.println("[NornRig] ${e.message}") } }

    private fun load() {
        val json = res("/assets/norns/denali.json")?.toString(Charsets.UTF_8) ?: return
        for (age in 0..3) {
            val block = Regex("\"$age\"\\s*:\\s*\\{([^}]*)}").find(json)?.groupValues?.get(1) ?: continue
            fun field(k: String) = Regex("\"$k\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toInt()
            val sheetName = Regex("\"sheet\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: continue
            val cw = field("cellW") ?: continue; val chh = field("cellH") ?: continue
            val bytes = res("/assets/norns/$sheetName") ?: continue
            val img = bytes.inputStream().use { ImageIO.read(it) }
            ages[age] = Age(img, cw, chh, field("anchorX") ?: cw / 2, field("anchorY") ?: chh - 2)
        }
    }

    private fun res(path: String): ByteArray? =
        NornRig::class.java.getResourceAsStream(path)?.readBytes()
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File(System.getProperty("user.dir")).parentFile?.parentFile?.resolve("assets$path")?.takeIf { it.exists() }?.readBytes()

    private fun ageOf(stage: String) = when (stage) { "BABY" -> 0; "CHILD" -> 1; "ADOLESCENT" -> 2; else -> 3 }
    private fun targetHeight(stage: String) = when (stage) {
        "BABY" -> 1.7f; "CHILD" -> 2.1f; "ADOLESCENT" -> 2.5f; "OLD" -> 2.85f; else -> 2.95f
    }

    /** Pose index (0 crawl, 1 walk-lean, 2 stand, 3 reach) by life stage + action. Babies/children
     *  crawl using the low poses; older Norns stand/walk upright. Walk cycles two poses for stride. */
    private fun poseFor(stage: String, action: CreatureAction, ticks: Int): Int {
        val crawler = stage == "BABY" || stage == "CHILD"
        val step = (ticks / 8) % 2
        return when (action) {
            CreatureAction.WALK -> if (crawler) intArrayOf(0, 1)[step] else intArrayOf(1, 2)[step]
            CreatureAction.EAT -> if (crawler) 0 else 1
            CreatureAction.COURT -> 3
            else -> if (crawler) 0 else 2 // REST / idle
        }
    }

    fun draw(
        g: java.awt.Graphics2D, c: WorldCreature, action: CreatureAction,
        worldX: Float, worldY: Float, px: (Float) -> Float, py: (Float) -> Float, sx: Float,
    ) {
        val stage = c.biology.lifeStage.name
        val a = ages[ageOf(stage)] ?: ages[3] ?: ages.values.firstOrNull() ?: return
        val pose = poseFor(stage, action, c.ticksLived)
        val scale = (targetHeight(stage) * sx) / a.cellH
        val flip = c.facing < 0
        val w = a.cellW * scale; val h = a.cellH * scale
        // gentle bob for life: a small step-bounce while walking, slow breathing otherwise
        val phase = c.ticksLived * 0.35f
        val bobAmp = if (action == CreatureAction.WALK) 0.028f else 0.012f
        val bob = -abs(sin(phase)) * bobAmp * h
        val ax = if (flip) a.cellW - a.anchorX else a.anchorX
        val dx1 = (px(worldX) - ax * scale).roundToInt(); val dy1 = (py(worldY) + bob - a.anchorY * scale).roundToInt()
        val dx2 = dx1 + w.roundToInt(); val dy2 = dy1 + h.roundToInt()
        val srcX = pose * a.cellW
        if (!flip) g.drawImage(a.sheet, dx1, dy1, dx2, dy2, srcX, 0, srcX + a.cellW, a.cellH, null)
        else g.drawImage(a.sheet, dx1, dy1, dx2, dy2, srcX + a.cellW, 0, srcX, a.cellH, null)
    }
}
