package org.emerge.desktop

import org.emerge.demo.norns.anim.CreatureAction
import org.emerge.demo.norns.world.WorldCreature
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Real Creatures-2 Norn sprites, baked from a ripped breed (Denali) into a transparent sheet +
 * manifest under `assets/norns/`. The parts were decoded from the breed's S16 files and composited
 * via their ATT attachment points (see `.build/norn-assets/`), so this is genuine C2 art rather
 * than primitives. The sheet is a horizontal strip of equal cells (stand / walk0 / walk1), the Norn
 * facing right; we mirror for leftward facing and anchor each cell at the creature's feet.
 *
 * Placeholder breed for now — Stu will swap in his own art and more breeds later.
 */
object NornSprites {
    private var sheet: BufferedImage? = null
    private var cellW = 0; private var cellH = 0; private var anchorX = 0; private var anchorY = 0
    private var frameNames: List<String> = emptyList()
    private var loaded = false

    val ready: Boolean get() = sheet != null

    fun ensure() {
        if (loaded) return
        loaded = true
        try { load() } catch (e: Exception) { System.err.println("[NornSprites] sprites not loaded, using primitives: ${e.message}") }
    }

    private fun load() {
        val pngBytes = resource("/assets/norns/denali.png") ?: return
        val json = resourceText("/assets/norns/denali.json") ?: return
        sheet = pngBytes.inputStream().use { ImageIO.read(it) }
        cellW = intField(json, "cellW"); cellH = intField(json, "cellH")
        anchorX = intField(json, "anchorX"); anchorY = intField(json, "anchorY")
        frameNames = Regex("\"frames\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(json)
            ?.groupValues?.get(1)?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
            ?: emptyList()
        if (frameNames.isEmpty() || cellW == 0) { sheet = null }
    }

    private fun resource(path: String): ByteArray? =
        (NornSprites::class.java.getResourceAsStream(path)?.readBytes())
            ?: File("assets$path").takeIf { it.exists() }?.readBytes()
            ?: File(System.getProperty("user.dir")).resolve("..").resolve("..").resolve("assets$path").takeIf { it.exists() }?.readBytes()

    private fun resourceText(path: String): String? = resource(path)?.toString(Charsets.UTF_8)
    private fun intField(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: 0

    private fun frameIndex(action: CreatureAction, ticksLived: Int): Int {
        val name = if (action == CreatureAction.WALK) (if ((ticksLived / 6) % 2 == 0) "walk0" else "walk1") else "stand"
        return frameNames.indexOf(name).coerceAtLeast(0)
    }

    private fun lifeScale(stage: String): Float = when (stage) {
        "BABY" -> 0.55f; "CHILD" -> 0.72f; "ADOLESCENT" -> 0.86f; "OLD" -> 0.95f; else -> 1.0f
    }

    /** Blit the Norn at the creature's ground point, mirrored by facing, scaled by life stage. */
    fun draw(
        g: java.awt.Graphics2D, c: WorldCreature, action: CreatureAction,
        worldX: Float, worldY: Float, px: (Float) -> Float, py: (Float) -> Float, sx: Float,
    ) {
        val img = sheet ?: return
        val ls = lifeScale(c.biology.lifeStage.name)
        val targetWorldH = 2.7f * ls
        val scale = (targetWorldH * sx) / cellH
        val flip = c.facing < 0
        val fi = frameIndex(action, c.ticksLived)
        val srcX = fi * cellW
        val feetX = px(worldX); val feetY = py(worldY)
        val ax = if (flip) cellW - anchorX else anchorX
        val dx1 = (feetX - ax * scale).roundToInt(); val dy1 = (feetY - anchorY * scale).roundToInt()
        val dx2 = dx1 + (cellW * scale).roundToInt(); val dy2 = dy1 + (cellH * scale).roundToInt()
        if (!flip) g.drawImage(img, dx1, dy1, dx2, dy2, srcX, 0, srcX + cellW, cellH, null)
        else g.drawImage(img, dx1, dy1, dx2, dy2, srcX + cellW, 0, srcX, cellH, null)
    }
}
