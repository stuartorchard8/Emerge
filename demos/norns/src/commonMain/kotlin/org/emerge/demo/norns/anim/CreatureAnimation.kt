package org.emerge.demo.norns.anim

import kotlin.math.abs
import kotlin.math.sin

/** The independently-posed parts of a creature's body (abstract blobs, for now). */
enum class BodyPart { TORSO, HEAD, ARM_LEFT, ARM_RIGHT, LEG_LEFT, LEG_RIGHT }

/** What the creature is doing, which selects an animation. Mapped from the brain's decision. */
enum class CreatureAction { REST, WALK, COURT, EAT }

/**
 * One posed body part for a single frame: position ([x],[y]) and [radius] in *body units*
 * (torso ≈ 1 across), relative to the creature's centre, plus an RGB tint. The renderer places it
 * by scaling to the creature's world size, translating to its world position, and drawing a blob.
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
 * Procedural skeletal animation: given an [CreatureAction], an animation [phase] (radians,
 * advancing with the creature's own clock), and a [facing] (+1 right / −1 left), produce the pose
 * of all six body parts. Pure + deterministic so it's verifiable headlessly and renderer-agnostic
 * — the GPU host and any other view consume the same [PosedPart]s.
 *
 * The motion is hand-authored sine work layered on a fixed rest layout: legs swing in
 * anti-phase while walking, arms counter-swing, the torso bobs, and eating dips the head. It's a
 * starting point to be tuned visually (DESIGN.md G1/G11), not anatomy.
 */
object CreatureAnimation {

    // Rest layout (body units): (baseX, baseY, radius). +y is up.
    private val rest = mapOf(
        BodyPart.TORSO to Triple(0f, 0f, 0.55f),
        BodyPart.HEAD to Triple(0f, 0.72f, 0.42f),
        BodyPart.ARM_LEFT to Triple(-0.55f, 0.10f, 0.20f),
        BodyPart.ARM_RIGHT to Triple(0.55f, 0.10f, 0.20f),
        BodyPart.LEG_LEFT to Triple(-0.28f, -0.62f, 0.24f),
        BodyPart.LEG_RIGHT to Triple(0.28f, -0.62f, 0.24f),
    )

    fun pose(action: CreatureAction, phase: Float, facing: Int, r: Float, g: Float, b: Float): List<PosedPart> {
        val face = if (facing < 0) -1f else 1f
        val s = sin(phase)
        val walking = action != CreatureAction.REST

        val legSwing = if (walking) 0.18f else 0f
        val armSwing = if (walking) 0.12f else 0f
        val legL = s * legSwing
        val legR = -s * legSwing            // opposite phase → legs alternate
        val armL = -s * armSwing            // arms counter-swing the legs
        val armR = s * armSwing

        val torsoBob = when (action) {
            CreatureAction.REST -> sin(phase) * 0.03f      // gentle breathing
            CreatureAction.WALK -> abs(s) * 0.06f
            CreatureAction.EAT -> abs(s) * 0.04f
            CreatureAction.COURT -> abs(sin(phase * 1.5f)) * 0.10f // excited bounce
        }
        val headDip = if (action == CreatureAction.EAT) -abs(sin(phase * 2f)) * 0.22f else 0f
        val lean = if (walking) 0.04f else 0f // lean into the direction of travel

        fun posed(part: BodyPart, dx: Float, dy: Float, shade: Float): PosedPart {
            val (bx, by, rad) = rest.getValue(part)
            return PosedPart(part, (bx + dx) * face, by + dy, rad, r * shade, g * shade, b * shade)
        }

        return listOf(
            posed(BodyPart.LEG_LEFT, 0f, legL, 0.85f),   // limbs slightly darker than the body
            posed(BodyPart.LEG_RIGHT, 0f, legR, 0.85f),
            posed(BodyPart.ARM_LEFT, 0f, armL, 0.85f),
            posed(BodyPart.ARM_RIGHT, 0f, armR, 0.85f),
            posed(BodyPart.TORSO, lean, torsoBob, 1f),
            posed(BodyPart.HEAD, lean, torsoBob + headDip, 1f),
        )
    }
}
