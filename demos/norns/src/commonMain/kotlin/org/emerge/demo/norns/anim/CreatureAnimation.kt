package org.emerge.demo.norns.anim

import kotlin.math.abs
import kotlin.math.sin

/** The independently-posed parts of a creature's body — a Creatures-2-style Norn: a big head with
 *  a face (ears, snout, two eyes + pupils), a round body, stubby arms/legs, and a tail. */
enum class BodyPart {
    TAIL, LEG_LEFT, LEG_RIGHT, ARM_LEFT, ARM_RIGHT, EAR_LEFT, EAR_RIGHT,
    TORSO, HEAD, SNOUT, EYE_BACK, EYE_FRONT, PUPIL_BACK, PUPIL_FRONT,
}

/** What the creature is doing, which selects an animation. Mapped from the brain's decision. */
enum class CreatureAction { REST, WALK, COURT, EAT }

/**
 * One posed body part for a single frame: position ([x],[y]) and [radius] in *body units*
 * (torso ≈ 1 across), relative to the creature's centre, plus an RGB tint. Parts are returned
 * back-to-front (painter's order). The renderer scales to world size, translates to the
 * creature's position, and draws a blob per part.
 */
class PosedPart(
    val part: BodyPart,
    val x: Float,
    val y: Float,
    val radius: Float,
    val r: Float,
    val g: Float,
    val b: Float,
)

/**
 * Procedural skeletal animation toward the **Creatures 2 Norn** look: given a [CreatureAction], an
 * animation [phase], a [facing] (+1 right / −1 left), and a fur colour ([r],[g],[b]), produce the
 * pose of every body part. Pure + deterministic (verifiable headlessly, renderer-agnostic).
 *
 * The Norn is big-headed and cute: an oversized head with two ears, a lighter snout, and two big
 * eyes with pupils that point where it's heading; a round belly; stubby limbs that swing while
 * walking; and a tail that wags. Eating dips the head; courting bounces; resting just breathes.
 * Still hand-authored sine work to be visually tuned (DESIGN.md G1/G11), not anatomy.
 */
object CreatureAnimation {

    fun pose(action: CreatureAction, phase: Float, facing: Int, r: Float, g: Float, b: Float): List<PosedPart> {
        val face = if (facing < 0) -1f else 1f
        val s = sin(phase)
        val walking = action != CreatureAction.REST

        val legSwing = if (walking) 0.16f else 0f
        val armSwing = if (walking) 0.10f else 0f
        val torsoBob = when (action) {
            CreatureAction.REST -> sin(phase) * 0.025f
            CreatureAction.WALK -> abs(s) * 0.05f
            CreatureAction.EAT -> abs(s) * 0.03f
            CreatureAction.COURT -> abs(sin(phase * 1.5f)) * 0.09f
        }
        val headDip = if (action == CreatureAction.EAT) -abs(sin(phase * 2f)) * 0.20f else 0f
        val lean = if (walking) 0.05f else 0f
        val headY = torsoBob + headDip
        val tailWag = sin(phase * 1.3f) * (if (walking) 0.07f else 0.02f)

        // fur shades + face colours
        fun shade(k: Float, c: Float) = c * k
        val snoutR = r + (1f - r) * 0.45f; val snoutG = g + (0.85f - g) * 0.45f; val snoutB = b + (0.8f - b) * 0.45f

        val parts = ArrayList<PosedPart>(14)
        fun add(part: BodyPart, x: Float, y: Float, rad: Float, cr: Float, cg: Float, cb: Float) =
            parts.add(PosedPart(part, x * face, y, rad, cr, cg, cb))

        // back → front
        add(BodyPart.TAIL, -0.52f + tailWag, 0.02f, 0.17f, shade(0.8f, r), shade(0.8f, g), shade(0.8f, b))
        add(BodyPart.LEG_LEFT, -0.24f, -0.62f + s * legSwing, 0.23f, shade(0.9f, r), shade(0.9f, g), shade(0.9f, b))
        add(BodyPart.LEG_RIGHT, 0.24f, -0.62f - s * legSwing, 0.23f, shade(0.9f, r), shade(0.9f, g), shade(0.9f, b))
        add(BodyPart.ARM_LEFT, -0.52f, 0.06f - s * armSwing, 0.16f, shade(0.9f, r), shade(0.9f, g), shade(0.9f, b))
        add(BodyPart.ARM_RIGHT, 0.52f, 0.06f + s * armSwing, 0.16f, shade(0.9f, r), shade(0.9f, g), shade(0.9f, b))
        add(BodyPart.EAR_LEFT, -0.30f, 1.04f + headY, 0.16f, shade(0.8f, r), shade(0.8f, g), shade(0.8f, b))
        add(BodyPart.EAR_RIGHT, 0.34f, 1.04f + headY, 0.16f, shade(0.8f, r), shade(0.8f, g), shade(0.8f, b))
        add(BodyPart.TORSO, lean, -0.05f + torsoBob, 0.60f, r, g, b)
        add(BodyPart.HEAD, lean, 0.74f + headY, 0.56f, r, g, b)
        add(BodyPart.SNOUT, 0.36f + lean, 0.58f + headY, 0.24f, snoutR, snoutG, snoutB)
        add(BodyPart.EYE_BACK, 0.12f + lean, 0.88f + headY, 0.16f, 1f, 1f, 1f)
        add(BodyPart.EYE_FRONT, 0.37f + lean, 0.86f + headY, 0.16f, 1f, 1f, 1f)
        add(BodyPart.PUPIL_BACK, 0.16f + lean, 0.85f + headY, 0.075f, 0.12f, 0.12f, 0.16f)
        add(BodyPart.PUPIL_FRONT, 0.41f + lean, 0.83f + headY, 0.075f, 0.12f, 0.12f, 0.16f)
        return parts
    }
}
