package org.emerge.demo.norns.anim

import kotlin.math.abs
import kotlin.math.sin

/**
 * The independently-posed parts of a creature's body — a **Creatures-2-style Norn**: more
 * monkey/gremlin than teddy bear. A big head turned toward the viewer with a forward-facing,
 * expressive face (two big eyes, brows, a pushed-forward muzzle with nose + mouth) and pointed
 * fox-like ears; a pot-bellied torso; long ape-like arms hanging to little hands; short legs with
 * feet; and a tail.
 */
enum class BodyPart {
    TAIL, LEG_LEFT, LEG_RIGHT, FOOT_LEFT, FOOT_RIGHT,
    ARM_LEFT, ARM_RIGHT, HAND_LEFT, HAND_RIGHT, EAR_LEFT, EAR_RIGHT,
    TORSO, BELLY, HEAD, MUZZLE, NOSE, MOUTH, BROW_LEFT, BROW_RIGHT,
    EYE_LEFT, EYE_RIGHT, PUPIL_LEFT, PUPIL_RIGHT,
}

/** Drawn shape of a part. Most parts are ellipses; ears are pointed triangles. */
enum class PartShape { ELLIPSE, TRIANGLE }

/** What the creature is doing, which selects an animation. Mapped from the brain's decision. */
enum class CreatureAction { REST, WALK, COURT, EAT }

/**
 * One posed body part for a single frame, in *body units* (torso ≈ 1 across) relative to the
 * creature's centre, plus an RGB tint. [radius] is the bounding radius (kept for back-compat / hit
 * bounds); [halfW]/[halfH] give the actual half-extents and [angle] the rotation (radians) for
 * elongated limbs and pointed ears. Parts are returned back-to-front (painter's order).
 */
class PosedPart(
    val part: BodyPart,
    val x: Float,
    val y: Float,
    val radius: Float,
    val r: Float,
    val g: Float,
    val b: Float,
    val shape: PartShape = PartShape.ELLIPSE,
    val halfW: Float = radius,
    val halfH: Float = radius,
    val angle: Float = 0f,
)

/**
 * Procedural skeletal animation toward the **Creatures 2 Norn** look. Given a [CreatureAction], an
 * animation [phase], a [facing] (+1 right / −1 left), and a fur colour ([r],[g],[b]), produce the
 * pose of every body part. Pure + deterministic (verifiable headlessly, renderer-agnostic).
 *
 * The head is drawn three-quarters toward the viewer so both eyes face forward and the face is
 * expressive (the classic Norn front-facing head sprite); [facing] turns the face slightly and sets
 * the limb swing so the side-scroll heading still reads. Legs alternate when walking, arms
 * counter-swing, the head dips when eating, courting bounces, resting breathes (+ blink handled by
 * the renderer). Hand-authored sine work, tuned via PNG (DESIGN.md G1/G11), not anatomy.
 */
object CreatureAnimation {

    fun pose(action: CreatureAction, phase: Float, facing: Int, r: Float, g: Float, b: Float): List<PosedPart> {
        val face = if (facing < 0) -1f else 1f
        val s = sin(phase)
        val walking = action != CreatureAction.REST

        val legSwing = if (walking) 0.14f else 0f
        val armSwing = if (walking) 0.16f else 0.02f
        val torsoBob = when (action) {
            CreatureAction.REST -> sin(phase) * 0.022f
            CreatureAction.WALK -> abs(s) * 0.05f
            CreatureAction.EAT -> abs(s) * 0.03f
            CreatureAction.COURT -> abs(sin(phase * 1.5f)) * 0.10f
        }
        val headDip = if (action == CreatureAction.EAT) -abs(sin(phase * 2f)) * 0.18f else 0f
        val headY = torsoBob + headDip
        val turn = face * 0.06f       // head/face turned 3/4 toward the heading
        val lean = if (walking) face * 0.04f else 0f
        val tailWag = sin(phase * 1.3f) * (if (walking) 0.08f else 0.03f)
        val earTwitch = sin(phase * 1.7f) * 0.04f

        // colour helpers
        fun shade(k: Float, c: Float) = c * k
        fun bl(c: Float, t: Float, k: Float) = c + (t - c) * k
        val muzR = bl(r, 1f, 0.42f); val muzG = bl(g, 0.9f, 0.42f); val muzB = bl(b, 0.82f, 0.42f)
        val bellyR = bl(r, 0.96f, 0.5f); val bellyG = bl(g, 0.9f, 0.5f); val bellyB = bl(b, 0.78f, 0.5f)
        val earR = shade(0.82f, r); val earG = shade(0.82f, g); val earB = shade(0.82f, b)
        val limbR = shade(0.92f, r); val limbG = shade(0.92f, g); val limbB = shade(0.92f, b)
        val browR = shade(0.42f, r); val browG = shade(0.42f, g); val browB = shade(0.42f, b)

        val parts = ArrayList<PosedPart>(24)
        fun add(
            part: BodyPart, x: Float, y: Float, rad: Float, cr: Float, cg: Float, cb: Float,
            shape: PartShape = PartShape.ELLIPSE, halfW: Float = rad, halfH: Float = rad, angle: Float = 0f,
        ) = parts.add(PosedPart(part, x * face, y, rad, cr, cg, cb, shape, halfW, halfH, angle * face))

        // ---- back → front ----
        // tail, peeking out behind the hip on the trailing side
        add(BodyPart.TAIL, -0.46f + tailWag, -0.30f, 0.17f, earR, earG, earB, halfW = 0.13f, halfH = 0.20f, angle = 0.4f)
        // legs + feet (anti-phase swing). legs rest at y = -0.62.
        add(BodyPart.LEG_LEFT, -0.22f, -0.62f + s * legSwing, 0.20f, limbR, limbG, limbB, halfW = 0.16f, halfH = 0.26f)
        add(BodyPart.LEG_RIGHT, 0.22f, -0.62f - s * legSwing, 0.20f, limbR, limbG, limbB, halfW = 0.16f, halfH = 0.26f)
        add(BodyPart.FOOT_LEFT, -0.20f + 0.10f, -0.86f + s * legSwing, 0.14f, shade(0.8f, r), shade(0.8f, g), shade(0.8f, b), halfW = 0.19f, halfH = 0.11f)
        add(BodyPart.FOOT_RIGHT, 0.20f + 0.10f, -0.86f - s * legSwing, 0.14f, shade(0.8f, r), shade(0.8f, g), shade(0.8f, b), halfW = 0.19f, halfH = 0.11f)
        // long ape arm on the far side (behind the torso), hanging low and counter-swinging
        add(BodyPart.ARM_LEFT, -0.48f, 0.02f + s * armSwing, 0.14f, limbR, limbG, limbB, halfW = 0.13f, halfH = 0.42f, angle = -0.22f)
        add(BodyPart.HAND_LEFT, -0.56f, -0.44f + s * armSwing, 0.14f, shade(0.86f, r), shade(0.86f, g), shade(0.86f, b))
        // small hunched pot-bellied torso (the big head dominates) + cream belly
        add(BodyPart.TORSO, lean, -0.12f + torsoBob, 0.50f, r, g, b, halfW = 0.42f, halfH = 0.50f)
        add(BodyPart.BELLY, lean + 0.02f * face, -0.20f + torsoBob, 0.34f, bellyR, bellyG, bellyB, halfW = 0.31f, halfH = 0.36f)
        // long ape arm on the near side (in front of the torso)
        add(BodyPart.ARM_RIGHT, 0.48f, 0.02f - s * armSwing, 0.14f, shade(0.96f, r), shade(0.96f, g), shade(0.96f, b), halfW = 0.13f, halfH = 0.42f, angle = 0.22f)
        add(BodyPart.HAND_RIGHT, 0.56f, -0.44f - s * armSwing, 0.14f, shade(0.9f, r), shade(0.9f, g), shade(0.9f, b))
        // pointed fox-like ears (triangles) at the top of the head, tilted outward + a tiny twitch
        add(BodyPart.EAR_LEFT, -0.34f, 1.02f + headY, 0.30f, earR, earG, earB, PartShape.TRIANGLE, halfW = 0.20f, halfH = 0.42f, angle = 0.34f + earTwitch)
        add(BodyPart.EAR_RIGHT, 0.34f, 1.02f + headY, 0.30f, earR, earG, earB, PartShape.TRIANGLE, halfW = 0.20f, halfH = 0.42f, angle = -0.34f - earTwitch)
        // big head turned toward the viewer
        add(BodyPart.HEAD, lean + turn, 0.66f + headY, 0.52f, r, g, b, halfW = 0.52f, halfH = 0.50f)
        // muzzle pushed forward + down (toward the heading)
        add(BodyPart.MUZZLE, turn + 0.10f * face, 0.44f + headY, 0.26f, muzR, muzG, muzB, halfW = 0.30f, halfH = 0.22f)
        add(BodyPart.NOSE, turn + 0.10f * face, 0.54f + headY, 0.075f, 0.20f, 0.13f, 0.12f, halfW = 0.10f, halfH = 0.07f)
        add(BodyPart.MOUTH, turn + 0.10f * face, 0.34f + headY, 0.10f, 0.28f, 0.13f, 0.12f, halfW = 0.13f, halfH = 0.06f)
        // brows over the eyes (expression)
        add(BodyPart.BROW_LEFT, turn - 0.18f, 0.92f + headY, 0.10f, browR, browG, browB, halfW = 0.14f, halfH = 0.05f, angle = 0.18f)
        add(BodyPart.BROW_RIGHT, turn + 0.20f, 0.92f + headY, 0.10f, browR, browG, browB, halfW = 0.14f, halfH = 0.05f, angle = -0.18f)
        // two big forward-facing eyes (near eye a touch larger for the 3/4 turn)
        add(BodyPart.EYE_LEFT, turn - 0.18f, 0.74f + headY, 0.165f, 1f, 1f, 1f, halfW = 0.155f, halfH = 0.175f)
        add(BodyPart.EYE_RIGHT, turn + 0.20f, 0.74f + headY, 0.18f, 1f, 1f, 1f, halfW = 0.17f, halfH = 0.19f)
        add(BodyPart.PUPIL_LEFT, turn - 0.16f + 0.02f * face, 0.72f + headY, 0.08f, 0.12f, 0.12f, 0.16f)
        add(BodyPart.PUPIL_RIGHT, turn + 0.22f + 0.02f * face, 0.72f + headY, 0.085f, 0.12f, 0.12f, 0.16f)
        return parts
    }
}
