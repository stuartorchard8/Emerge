package org.emerge.desktop

import org.emerge.demo.norns.world.NornsConfig
import org.emerge.demo.norns.world.NornsWorld
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Headless performance probe for the Norns world — isolates the three cost centres so we know what to
 * optimise: the **sim** tick, a single cold **SDF bake**, and a full **frame** (baked-warm vs the
 * Java2D-only path). Run: `./gradlew :apps:norns:desktop:benchNorns`.
 */
fun main() {
    System.setProperty("java.awt.headless", "true")
    val cfg = NornsConfig()
    val base = CreatureBaker.baselineGenome()
    val world = NornsWorld(cfg, 7L, base)
    repeat(400) { world.step() }                       // warm up + grow the colony
    val pop = world.population

    fun ms(iters: Int, block: () -> Unit): Double {
        repeat(maxOf(1, iters / 10)) { block() }       // JIT warmup
        val t = System.nanoTime(); repeat(iters) { block() }; return (System.nanoTime() - t) / 1e6 / iters
    }

    // 1) sim
    val simMs = ms(3000) { world.step() }

    // 2) one cold SDF bake at the world's bake resolution (200px)
    val genome = base ?: defaultNornGenome()
    val mood = CreatureRenderer.Mood(0.2, 0.3, 0.1)
    val tile = CreatureBaker.TILE
    val bakeMs = ms(30) {
        val img = BufferedImage(tile, tile, BufferedImage.TYPE_INT_ARGB)
        CreatureRenderer.render(CreatureRenderer.Baked(genome, mood), Color(176, 142, 104), img, 0, tile, 0, transparent = true)
    }

    // 3) full frame — warm the bake cache first so we measure steady-state, not warmup
    val cx = world.creatures.firstOrNull()?.x ?: 0f
    for (c in world.creatures) CreatureBaker.spriteFor(c)
    val frameFlat = ms(60) { NornsImageRenderer.renderFrame(world, cx, null, 1000, 620, baked = false) }
    val frameBaked = ms(60) { NornsImageRenderer.renderFrame(world, cx, null, 1000, 620, baked = true) }

    println("pop=$pop")
    println("sim          : %.3f ms/tick".format(simMs))
    println("bake (${tile}px): %.2f ms each   (cold cost per creature·mood-bucket)".format(bakeMs))
    println("frame flat   : %.2f ms  (%.0f fps)".format(frameFlat, 1000 / frameFlat))
    println("frame baked  : %.2f ms  (%.0f fps)  [cache warm]".format(frameBaked, 1000 / frameBaked))

    // per-section breakdown of a baked frame (only if -Dnorns.prof)
    NornsImageRenderer.profNs.clear()
    val n = 60
    repeat(n) { NornsImageRenderer.renderFrame(world, cx, null, 1000, 620, baked = true) }
    if (NornsImageRenderer.profNs.isNotEmpty()) {
        println("frame sections (ms/frame):")
        NornsImageRenderer.profNs.entries.sortedByDescending { it.value }.forEach { println("  %-10s %.2f".format(it.key, it.value / 1e6 / n)) }
    }
}
