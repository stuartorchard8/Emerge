package org.emerge.desktop

import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.morph.MorphGenome
import org.emerge.demo.norns.morph.MorphNode
import org.emerge.demo.norns.world.ActivityType
import org.emerge.demo.norns.world.WorldCreature
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bakes Norns into lit side-profile sprites for the live world — the "genes → 3D → 2D sprite" pipeline
 * applied to actual creatures, with **no real-time 3D in the engine**: each (breed, mood) is ray-marched
 * once by [CreatureRenderer] and cached as a transparent [BufferedImage] the world renderer composites.
 *
 * Two pieces are still stopgaps until the architecture track wires them properly:
 *  - **genome per creature** is synthesized from the heritable `breed` index (baseline + a seeded
 *    mutation) — placeholder until a morphology genome is a real, inherited gene on the creature;
 *  - **mood** is read from drive chemistry (hunger/fatigue/urge + current activity) → valence/arousal,
 *    a first taste of expression-from-internal-state.
 */
object CreatureBaker {

    private const val TILE = 200

    /** A baked sprite + where the feet sit and how tall the creature is, as fractions of the tile,
     *  so the world renderer can foot-align and scale it to the creature's life-stage height. */
    class Sprite(val img: BufferedImage, val footFracY: Double, val heightFrac: Double)

    private val genomes = HashMap<Int, MorphNode>()
    private val sprites = HashMap<Long, Sprite>()

    private fun genomeFor(breed: Int): MorphNode = genomes.getOrPut(breed) {
        defaultNornGenome().also { if (breed != 0) MorphGenome.mutate(it, GeneRng(breed.toLong() * 0x9E3779B1L + 1), 0.28f, 0.04f) }
    }

    /** Per-breed fur, so breeds read as distinct (placeholder palette until fur is a gene). */
    fun furFor(breed: Int): Color {
        val h = breed * -0x61c88647
        return Color((150 + (h and 0x3F)).coerceIn(60, 235), (116 + ((h ushr 7) and 0x3F)).coerceIn(60, 220), (92 + ((h ushr 14) and 0x3F)).coerceIn(50, 200))
    }

    /** Drive chemistry + activity → a (valence, arousal) mood. */
    fun moodOf(c: WorldCreature): CreatureRenderer.Mood {
        val eating = c.activity == ActivityType.EATING
        val courting = c.activity == ActivityType.COURTING
        val valence = (0.35 - c.hunger * 0.7 - c.fatigue * 0.35 + (if (eating) 0.5 else 0.0) + (if (courting) 0.55 else 0.0)).coerceIn(-1.0, 1.0)
        val base = when (c.activity) {
            ActivityType.MOVING -> 0.4; ActivityType.EATING -> 0.3; ActivityType.PICKING_UP -> 0.45
            ActivityType.COURTING -> 0.75; ActivityType.RESTING -> -0.7; ActivityType.IDLE -> -0.2
        }
        val arousal = (base + c.matingUrge * 0.3 + c.hunger * 0.15).coerceIn(-1.0, 1.0)
        return CreatureRenderer.Mood(valence, arousal)
    }

    @Synchronized
    fun spriteFor(c: WorldCreature): Sprite {
        val mood = moodOf(c)
        val vb = (mood.v / 0.34).roundToInt(); val ab = (mood.a / 0.34).roundToInt()
        val key = (c.breed.toLong() shl 40) or ((vb + 8).toLong() shl 20) or (ab + 8).toLong()
        return sprites.getOrPut(key) { bake(c.breed, CreatureRenderer.Mood(vb * 0.34, ab * 0.34)) }
    }

    private fun bake(breed: Int, mood: CreatureRenderer.Mood): Sprite {
        val b = CreatureRenderer.Baked(genomeFor(breed), mood)
        val img = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
        CreatureRenderer.render(b, furFor(breed), img, 0, TILE, 0, transparent = true)
        val fr = CreatureRenderer.frame(b, TILE)
        val bb = b.bounds()                                   // minX, maxX, minY, maxY
        val footFracY = fr.screenY(bb[2]) / TILE              // feet = lowest world-y
        val heightFrac = max(1e-3, (bb[3] - bb[2]) / fr.span)
        return Sprite(img, footFracY, heightFrac)
    }
}
