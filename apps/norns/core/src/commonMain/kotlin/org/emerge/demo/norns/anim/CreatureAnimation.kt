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

/** What the creature is doing, which selects an animation. Mapped from the brain's decision.
 *  PICK_UP (reaching to the ground for food) is distinct from EAT (chewing it). */
enum class CreatureAction { REST, WALK, COURT, EAT, PICK_UP }

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
 * the renderer). Hand-authored sine work, not anatomy. Every amplitude, frequency, proportion and
 * shade is a dial in [AnimParams] (default = the hand-tuned baseline), tuned live in the Swing
 * animation viewer (`runNornsAnim`) rather than by re-rendering PNGs (DESIGN.md G1/G11).
 */
object CreatureAnimation {

    fun pose(
        action: CreatureAction, phase: Float, facing: Int, r: Float, g: Float, b: Float,
        p: AnimParams = AnimParams.DEFAULT,
    ): List<PosedPart> {
        val face = if (facing < 0) -1f else 1f
        val s = sin(phase)
        val moving = action == CreatureAction.WALK   // only walking strides; eat/court/pick stay put

        val legSwing = if (moving) p["swing/legWalk"] else 0f
        val armSwing = when (action) {
            CreatureAction.WALK -> p["swing/armWalk"]
            CreatureAction.COURT -> p["swing/armCourt"]
            else -> p["swing/armIdle"]
        }
        val torsoBob = when (action) {
            CreatureAction.REST -> sin(phase) * p["bob/rest"]
            CreatureAction.WALK -> abs(s) * p["bob/walk"]
            CreatureAction.EAT -> abs(s) * p["bob/eat"]
            CreatureAction.PICK_UP -> p["bob/pickup"]
            CreatureAction.COURT -> abs(sin(phase * p["freq/courtBob"])) * p["bob/court"]
        }
        val headDip = when (action) {
            CreatureAction.EAT -> -abs(sin(phase * p["freq/eatHead"])) * p["head/eatDip"]   // chewing pecks
            CreatureAction.PICK_UP -> p["head/pickupDip"]                                    // bent right down to the ground
            CreatureAction.COURT -> p["head/courtLift"]                                      // chin up
            else -> 0f
        }
        val headY = torsoBob + headDip
        val turn = face * p["head/turn"]       // head/face turned 3/4 toward the heading
        val lean = if (moving) face * p["head/leanWalk"] else if (action == CreatureAction.PICK_UP) face * p["head/leanPickup"] else 0f
        val tailWag = sin(phase * p["freq/tail"]) * (if (moving) p["swing/tailMove"] else p["swing/tailIdle"])
        val earTwitch = sin(phase * p["freq/ear"]) * p["twitch/ear"]

        // proportions (per-part half-extents)
        val headW = p["size/headW"]; val headH = p["size/headH"]
        val torsoW = p["size/torsoW"]; val torsoH = p["size/torsoH"]
        val bellyW = p["size/bellyW"]; val bellyH = p["size/bellyH"]
        val earW = p["size/earW"]; val earH = p["size/earH"]
        val legW = p["size/legW"]; val legH = p["size/legH"]
        val armW = p["size/armW"]; val armH = p["size/armH"]
        val muzW = p["size/muzzleW"]; val muzH = p["size/muzzleH"]
        val eyeK = p["size/eyeScale"]

        // colour helpers
        fun shade(k: Float, c: Float) = c * k
        fun bl(c: Float, t: Float, k: Float) = c + (t - c) * k
        val muzBlend = p["color/muzzleBlend"]; val bellyBlend = p["color/bellyBlend"]
        val earK = p["color/earShade"]; val limbK = p["color/limbShade"]
        val browK = p["color/browShade"]; val footK = p["color/footShade"]
        val muzR = bl(r, 1f, muzBlend); val muzG = bl(g, 0.9f, muzBlend); val muzB = bl(b, 0.82f, muzBlend)
        val bellyR = bl(r, 0.96f, bellyBlend); val bellyG = bl(g, 0.9f, bellyBlend); val bellyB = bl(b, 0.78f, bellyBlend)
        val earR = shade(earK, r); val earG = shade(earK, g); val earB = shade(earK, b)
        val limbR = shade(limbK, r); val limbG = shade(limbK, g); val limbB = shade(limbK, b)
        val browR = shade(browK, r); val browG = shade(browK, g); val browB = shade(browK, b)

        val parts = ArrayList<PosedPart>(24)
        fun add(
            part: BodyPart, x: Float, y: Float, rad: Float, cr: Float, cg: Float, cb: Float,
            shape: PartShape = PartShape.ELLIPSE, halfW: Float = rad, halfH: Float = rad, angle: Float = 0f,
        ) = parts.add(PosedPart(part, x * face, y, rad, cr, cg, cb, shape, halfW, halfH, angle * face))

        // ---- back → front ----
        // tail, peeking out behind the hip on the trailing side
        add(BodyPart.TAIL, -0.46f + tailWag, -0.30f, 0.17f, earR, earG, earB, halfW = 0.13f, halfH = 0.20f, angle = 0.4f)
        // legs + feet (anti-phase swing). legs rest at y = -0.62.
        add(BodyPart.LEG_LEFT, -0.22f, -0.62f + s * legSwing, 0.20f, limbR, limbG, limbB, halfW = legW, halfH = legH)
        add(BodyPart.LEG_RIGHT, 0.22f, -0.62f - s * legSwing, 0.20f, limbR, limbG, limbB, halfW = legW, halfH = legH)
        add(BodyPart.FOOT_LEFT, -0.20f + 0.10f, -0.86f + s * legSwing, 0.14f, shade(footK, r), shade(footK, g), shade(footK, b), halfW = 0.19f, halfH = 0.11f)
        add(BodyPart.FOOT_RIGHT, 0.20f + 0.10f, -0.86f - s * legSwing, 0.14f, shade(footK, r), shade(footK, g), shade(footK, b), halfW = 0.19f, halfH = 0.11f)
        // long ape arm on the far side (behind the torso), hanging low and counter-swinging
        add(BodyPart.ARM_LEFT, -0.48f, 0.02f + s * armSwing, 0.14f, limbR, limbG, limbB, halfW = armW, halfH = armH, angle = -0.22f)
        add(BodyPart.HAND_LEFT, -0.56f, -0.44f + s * armSwing, 0.14f, shade(0.86f, r), shade(0.86f, g), shade(0.86f, b))
        // small hunched pot-bellied torso (the big head dominates) + cream belly
        add(BodyPart.TORSO, lean, -0.12f + torsoBob, 0.50f, r, g, b, halfW = torsoW, halfH = torsoH)
        add(BodyPart.BELLY, lean + 0.02f * face, -0.20f + torsoBob, 0.34f, bellyR, bellyG, bellyB, halfW = bellyW, halfH = bellyH)
        // long ape arm on the near side (in front of the torso)
        add(BodyPart.ARM_RIGHT, 0.48f, 0.02f - s * armSwing, 0.14f, shade(0.96f, r), shade(0.96f, g), shade(0.96f, b), halfW = armW, halfH = armH, angle = 0.22f)
        add(BodyPart.HAND_RIGHT, 0.56f, -0.44f - s * armSwing, 0.14f, shade(0.9f, r), shade(0.9f, g), shade(0.9f, b))
        // pointed fox-like ears (triangles) at the top of the head, tilted outward + a tiny twitch
        add(BodyPart.EAR_LEFT, -0.34f, 1.02f + headY, 0.30f, earR, earG, earB, PartShape.TRIANGLE, halfW = earW, halfH = earH, angle = 0.34f + earTwitch)
        add(BodyPart.EAR_RIGHT, 0.34f, 1.02f + headY, 0.30f, earR, earG, earB, PartShape.TRIANGLE, halfW = earW, halfH = earH, angle = -0.34f - earTwitch)
        // big head turned toward the viewer
        add(BodyPart.HEAD, lean + turn, 0.66f + headY, 0.52f, r, g, b, halfW = headW, halfH = headH)
        // muzzle pushed forward + down (toward the heading)
        add(BodyPart.MUZZLE, turn + 0.10f * face, 0.44f + headY, 0.26f, muzR, muzG, muzB, halfW = muzW, halfH = muzH)
        add(BodyPart.NOSE, turn + 0.10f * face, 0.54f + headY, 0.075f, 0.20f, 0.13f, 0.12f, halfW = 0.10f, halfH = 0.07f)
        add(BodyPart.MOUTH, turn + 0.10f * face, 0.34f + headY, 0.10f, 0.28f, 0.13f, 0.12f, halfW = 0.13f, halfH = 0.06f)
        // brows over the eyes (expression)
        add(BodyPart.BROW_LEFT, turn - 0.18f, 0.92f + headY, 0.10f, browR, browG, browB, halfW = 0.14f, halfH = 0.05f, angle = 0.18f)
        add(BodyPart.BROW_RIGHT, turn + 0.20f, 0.92f + headY, 0.10f, browR, browG, browB, halfW = 0.14f, halfH = 0.05f, angle = -0.18f)
        // two big forward-facing eyes (near eye a touch larger for the 3/4 turn)
        add(BodyPart.EYE_LEFT, turn - 0.18f, 0.74f + headY, 0.165f, 1f, 1f, 1f, halfW = 0.155f * eyeK, halfH = 0.175f * eyeK)
        add(BodyPart.EYE_RIGHT, turn + 0.20f, 0.74f + headY, 0.18f, 1f, 1f, 1f, halfW = 0.17f * eyeK, halfH = 0.19f * eyeK)
        add(BodyPart.PUPIL_LEFT, turn - 0.16f + 0.02f * face, 0.72f + headY, 0.10f * eyeK, 0.12f, 0.12f, 0.16f)
        add(BodyPart.PUPIL_RIGHT, turn + 0.22f + 0.02f * face, 0.72f + headY, 0.105f * eyeK, 0.12f, 0.12f, 0.16f)
        return parts
    }
}
