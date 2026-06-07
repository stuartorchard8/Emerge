package org.emerge.demo.norns.anim

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the procedural animation (G11 sim side): the skeleton poses
 * correctly per action — legs alternate when walking, the head dips when eating, resting is
 * gentle — independently of any renderer. (The GPU drawing of these parts can't be verified
 * headlessly; this proves the motion is right.)
 */
class CreatureAnimationTest {

    private fun part(parts: List<PosedPart>, p: BodyPart) = parts.first { it.part == p }
    private val quarter = (PI / 2).toFloat() // phase where sin = 1

    @Test
    fun everyBodyPartIsPosedEachFrame() {
        val parts = CreatureAnimation.pose(CreatureAction.WALK, phase = 0.5f, facing = 1, r = 1f, g = 1f, b = 1f)
        assertEquals(BodyPart.entries.toSet(), parts.map { it.part }.toSet(), "all body parts posed")
        assertEquals(BodyPart.entries.size, parts.size)
    }

    @Test
    fun legsAlternateWhenWalking() {
        val parts = CreatureAnimation.pose(CreatureAction.WALK, phase = quarter, facing = 1, r = 1f, g = 1f, b = 1f)
        val restLegY = -0.62f // both legs rest here; the swing is the delta from it
        val legL = part(parts, BodyPart.LEG_LEFT)
        val legR = part(parts, BodyPart.LEG_RIGHT)
        assertTrue(legL.y != legR.y, "legs should be at different heights mid-stride")
        assertTrue((legL.y - restLegY) * (legR.y - restLegY) < 0f,
            "legs should swing in anti-phase (opposite deltas from rest)")
    }

    @Test
    fun restingIsGentleAndStill() {
        val parts = CreatureAnimation.pose(CreatureAction.REST, phase = quarter, facing = 1, r = 1f, g = 1f, b = 1f)
        val (_, restLegBaseY, _) = restY(BodyPart.LEG_LEFT)
        // legs don't swing at rest
        assertEquals(restLegBaseY, part(parts, BodyPart.LEG_LEFT).y, 1e-6f)
        // torso only breathes a little
        assertTrue(kotlin.math.abs(part(parts, BodyPart.TORSO).y) < 0.05f, "rest torso bob is small")
    }

    @Test
    fun eatingDipsTheHeadBelowItsWalkingHeight() {
        // At a phase where the eat dip is near maximal, the head sits lower than when just walking.
        val phase = (PI / 4).toFloat()
        val eating = part(CreatureAnimation.pose(CreatureAction.EAT, phase, 1, 1f, 1f, 1f), BodyPart.HEAD)
        val walking = part(CreatureAnimation.pose(CreatureAction.WALK, phase, 1, 1f, 1f, 1f), BodyPart.HEAD)
        assertTrue(eating.y < walking.y, "eating should dip the head (eat=${eating.y} walk=${walking.y})")
    }

    @Test
    fun facingMirrorsTheBody() {
        val right = CreatureAnimation.pose(CreatureAction.WALK, phase = 0.3f, facing = 1, r = 1f, g = 1f, b = 1f)
        val left = CreatureAnimation.pose(CreatureAction.WALK, phase = 0.3f, facing = -1, r = 1f, g = 1f, b = 1f)
        val armR_right = part(right, BodyPart.ARM_RIGHT)
        val armR_left = part(left, BodyPart.ARM_RIGHT)
        assertEquals(armR_right.x, -armR_left.x, 1e-6f, "facing left mirrors x")
    }

    @Test
    fun poseIsDeterministicAndBounded() {
        val a = CreatureAnimation.pose(CreatureAction.COURT, phase = 1.1f, facing = 1, r = 0.6f, g = 0.4f, b = 0.2f)
        val b = CreatureAnimation.pose(CreatureAction.COURT, phase = 1.1f, facing = 1, r = 0.6f, g = 0.4f, b = 0.2f)
        for (i in a.indices) {
            assertEquals(a[i].x.toRawBits(), b[i].x.toRawBits())
            assertEquals(a[i].y.toRawBits(), b[i].y.toRawBits())
        }
        // parts stay near the body (no flying limbs) across a full phase cycle
        for (action in CreatureAction.entries) {
            var ph = 0f
            while (ph < 7f) {
                for (p in CreatureAnimation.pose(action, ph, 1, 1f, 1f, 1f)) {
                    assertTrue(kotlin.math.abs(p.x) < 1.2f && kotlin.math.abs(p.y) < 1.6f, "part ${p.part} stays attached")
                }
                ph += 0.3f
            }
        }
    }

    private fun restY(part: BodyPart): Triple<Float, Float, Float> =
        // rest layout mirror for the test (legs rest at y = -0.62)
        when (part) {
            BodyPart.LEG_LEFT, BodyPart.LEG_RIGHT -> Triple(0f, -0.62f, 0.24f)
            else -> Triple(0f, 0f, 0f)
        }
}
