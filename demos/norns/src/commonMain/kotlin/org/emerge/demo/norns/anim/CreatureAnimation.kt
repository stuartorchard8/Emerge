package org.emerge.demo.norns.anim

import kotlin.math.abs
import kotlin.math.sin

/** The independently-posed parts of a creature's body — a Creatures-2-style Norn: a big head with
 *  a face (floppy ears, a hair crest, a snout with nose + mouth, two big eyes + pupils), a round
 *  body with a lighter belly, stubby arms, legs ending in boot-like feet, and a rabbit-puff tail. */
enum class BodyPart {
    TAIL, LEG_LEFT, LEG_RIGHT, FOOT_LEFT, FOOT_RIGHT, ARM_LEFT, ARM_RIGHT,
    EAR_LEFT, EAR_RIGHT, TORSO, BELLY, HEAD, HAIR, SNOUT, NOSE, MOUTH,
    EYE_BACK, EYE_FRONT, PUPIL_BACK, PUPIL_FRONT,
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
 * The Norn is big-headed and cute (researched against the C1/C2 breeds): an oversized head with
 * two floppy ears, a hair crest, a protruding snout with a nose and mouth, and two big eyes whose
 * pupils point where it's heading; a round body with a paler belly; stubby arms; legs ending in
 * darker boot-like feet; and a rabbit-puff tail that wags. Eating dips the head; courting bounces;
 * resting breathes. Hand-authored sine work, visually tuned via PNG (DESIGN.md G1/G11).
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
        val earFlop = sin(phase * 1.3f) * (if (walking) 0.05f else 0.015f)
        val headY = torsoBob + headDip
        val tailWag = sin(phase * 1.3f) * (if (walking) 0.07f else 0.02f)

        // colour helpers: shade darkens fur; blend mixes toward a target (for paler/contrast parts).
        fun shade(k: Float, c: Float) = c * k
        fun bl(c: Float, t: Float, k: Float) = c + (t - c) * k
        // snout/muzzle: paler warm fur. belly: cream. boots: dark fur. hair crest: richer fur.
        val snoutR = bl(r, 1f, 0.45f); val snoutG = bl(g, 0.85f, 0.45f); val snoutB = bl(b, 0.8f, 0.45f)
        val bellyR = bl(r, 0.96f, 0.55f); val bellyG = bl(g, 0.9f, 0.55f); val bellyB = bl(b, 0.76f, 0.55f)
        val hairR = bl(shade(1.12f, r), 0.85f, 0.2f); val hairG = bl(shade(1.12f, g), 0.7f, 0.2f); val hairB = bl(shade(1.12f, b), 0.5f, 0.2f)
        val bootR = shade(0.62f, r); val bootG = shade(0.62f, g); val bootB = shade(0.62f, b)

        val parts = ArrayList<PosedPart>(20)
        fun add(part: BodyPart, x: Float, y: Float, rad: Float, cr: Float, cg: Float, cb: Float) =
            parts.add(PosedPart(part, x * face, y, rad, cr, cg, cb))

        // back → front
        add(BodyPart.TAIL, -0.56f + tailWag, 0.06f, 0.20f, shade(0.82f, r), shade(0.82f, g), shade(0.82f, b))
        add(BodyPart.LEG_LEFT, -0.24f, -0.62f + s * legSwing, 0.22f, shade(0.88f, r), shade(0.88f, g), shade(0.88f, b))
        add(BodyPart.LEG_RIGHT, 0.24f, -0.62f - s * legSwing, 0.22f, shade(0.88f, r), shade(0.88f, g), shade(0.88f, b))
        // boot-like feet at the leg ends, nudged in the facing direction
        add(BodyPart.FOOT_LEFT, -0.24f + 0.10f, -0.82f + s * legSwing, 0.15f, bootR, bootG, bootB)
        add(BodyPart.FOOT_RIGHT, 0.24f + 0.10f, -0.82f - s * legSwing, 0.15f, bootR, bootG, bootB)
        add(BodyPart.ARM_LEFT, -0.50f, 0.04f - s * armSwing, 0.155f, shade(0.9f, r), shade(0.9f, g), shade(0.9f, b))
        // floppy ears hanging beside the head (drawn before head so it overlaps their inner edge)
        add(BodyPart.EAR_LEFT, -0.40f, 0.86f + headY + earFlop, 0.20f, shade(0.78f, r), shade(0.78f, g), shade(0.78f, b))
        add(BodyPart.EAR_RIGHT, 0.44f, 0.84f + headY - earFlop, 0.20f, shade(0.78f, r), shade(0.78f, g), shade(0.78f, b))
        add(BodyPart.TORSO, lean, -0.05f + torsoBob, 0.60f, r, g, b)
        add(BodyPart.BELLY, lean + 0.04f * face, -0.14f + torsoBob, 0.36f, bellyR, bellyG, bellyB)
        add(BodyPart.ARM_RIGHT, 0.50f, 0.04f + s * armSwing, 0.155f, shade(0.94f, r), shade(0.94f, g), shade(0.94f, b))
        add(BodyPart.HEAD, lean, 0.74f + headY, 0.54f, r, g, b)
        // wild hair crest on top of the head (a rounded tuft of three)
        add(BodyPart.HAIR, lean - 0.20f, 1.14f + headY, 0.15f, hairR, hairG, hairB)
        add(BodyPart.HAIR, lean + 0.02f, 1.22f + headY, 0.17f, hairR, hairG, hairB)
        add(BodyPart.HAIR, lean + 0.22f, 1.13f + headY, 0.14f, hairR, hairG, hairB)
        add(BodyPart.SNOUT, 0.38f + lean, 0.54f + headY, 0.26f, snoutR, snoutG, snoutB)
        add(BodyPart.NOSE, 0.60f + lean, 0.62f + headY, 0.075f, 0.26f, 0.16f, 0.13f)
        add(BodyPart.MOUTH, 0.50f + lean, 0.40f + headY, 0.05f, 0.30f, 0.14f, 0.12f)
        add(BodyPart.EYE_BACK, 0.12f + lean, 0.90f + headY, 0.16f, 1f, 1f, 1f)
        add(BodyPart.EYE_FRONT, 0.38f + lean, 0.88f + headY, 0.16f, 1f, 1f, 1f)
        add(BodyPart.PUPIL_BACK, 0.16f + lean, 0.87f + headY, 0.075f, 0.12f, 0.12f, 0.16f)
        add(BodyPart.PUPIL_FRONT, 0.42f + lean, 0.85f + headY, 0.075f, 0.12f, 0.12f, 0.16f)
        return parts
    }
}
