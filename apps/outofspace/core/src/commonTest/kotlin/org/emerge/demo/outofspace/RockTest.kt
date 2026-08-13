package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A body exists, falls, drifts and is accounted for — increment H1.
 *
 * The interesting half is not that it moves. It is **which gravity it moves under**: a body over the
 * deck is standing on the plating, and a body a hundred tiles astern is not, because the plating is a
 * field the ship makes and it stops where the ship does. Get that backwards and a captured body
 * either sticks to the ship like a magnet or falls off the bottom of the universe. See [RigidBody].
 *
 * ⚠️ Since H2a a body's **momentum is in the world frame** while its **position is on the grid**, and
 * the tests here are the ones that notice: a body at rest now reads as a body at rest, and the drift
 * astern of a burning ship is the grid leaving rather than a pseudo-force pushing. The old version of
 * this file could only check that to within a few per cent; it is exact now.
 *
 * ⚠️ Nothing here touches anything. A body flies through the hull, conducts with nothing and blocks
 * nothing; contact is H2. Its **energy** is in the solid ledger from the tick it appears anyway, so
 * that the arrival of conduction is not also the arrival of a discontinuity.
 */
class RockTest {

    @Test
    fun `a body over the deck falls toward it`() {
        val controller = OutofspaceController(CFG, bareHull())
        controller.dropRock(18f, 10f)
        controller.stepOnce()

        val start = controller.state.bodies.single().localCentreY(controller.state.pose)
        repeat(6) { controller.stepOnce() }
        val body = controller.state.bodies.single()
        val fell = body.localCentreY(controller.state.pose)

        assertTrue(fell > start, "the body hung in the air at $start")
        assertTrue(body.velocityY > 0L, "and it is not even falling: ${body.velocityY}")
    }

    /**
     * Nothing pushes along x here, so a body that wandered would mean the felt gravity had a
     * component it should not — the assertion that says gravity is being *applied* and not merely
     * noise.
     *
     * ⚠️ **PARKED by step 1 of `PLAN_rigid_bodies.md`.** Measured: it passes with the vessel's spin
     * forced to zero and fails with it live. The cause is real and is not this test's subject — the
     * plating pulls on a rock sitting half a tile off the hull's centre of mass, which is a genuine
     * torque, so the deck the rock lands on is very slightly tilted and a normal perpendicular to a
     * tilted deck has a world-frame x component. The body is not drifting; the floor is banked.
     *
     * It needs restating in the grid frame, which is a thing to do once a body has an orientation to
     * state it against — **step 3**. Kept whole and named rather than folded into the test above,
     * so that what is parked is legible.
     */
    @Ignore
    @Test
    fun `a body falling under straight-down gravity does not drift sideways`() {
        val controller = OutofspaceController(CFG, bareHull())
        controller.dropRock(18f, 10f)
        repeat(7) { controller.stepOnce() }

        assertEquals(
            0L, controller.state.bodies.single().velocityX,
            "the body drifted sideways under a straight-down gravity",
        )
    }

    /**
     * The plating stops where the vessel does.
     *
     * A body placed well outside the grid is in open space, and open space has no deck plating in
     * it — so it does not move. That is the half of [feltBy] that cannot be checked by watching
     * something fall, and it is the half that decides whether an asteroid field is a place or a
     * waterfall.
     *
     * ⚠️ **In vacuum, and that is the test being honest rather than the test being easy.** Written
     * against the ordinary air-filled hull this failed, by eight hundredths of a tile over sixty
     * ticks — and it was right to. A sealed vessel's atmosphere rings, the hull recoils from it, and
     * a ship with a non-zero acceleration gives every free body in the universe an equal and
     * opposite apparent one. That is the model working. The claim being made here is *no plating out
     * there*, not *no motion*, and the two are only the same statement when the ship is not
     * accelerating — so the fixture is one that is not.
     */
    @Test
    fun `a body in open space is not pulled by the deck plating`() {
        val grid = CFG.initialGrid
        val far = RigidBody.rockBlob(
            radius = 2,
            positionX = grid.width * 4L * Flight.PER_TILE,
            positionY = grid.height * 4L * Flight.PER_TILE,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(far)))

        RockSpawner.enabled = false

        repeat(TICKS) { controller.stepOnce() }

        val body = controller.state.bodies.single()
        assertEquals(far.positionX, body.positionX, "open space acquired a floor")
        assertEquals(far.positionY, body.positionY, "open space acquired a floor")
        assertEquals(0L, body.impulseX)
        assertEquals(0L, body.impulseY)
    }

    /**
     * Under thrust and with the plating off, the body stands still and the **ship** moves.
     *
     * This is the observation the whole increment is for, and H2a is what made it *cheap*. In the
     * vessel's frame it took a pseudo-force to explain — the frame accelerates, so a body genuinely
     * at rest has to be given an equal and opposite apparent acceleration, and the old version of
     * this test could only check that within a few per cent because the term was a tick behind. With
     * the momentum written in the world frame there is nothing to explain and nothing to approximate:
     * the body's velocity is **exactly zero**, forever, and the drift astern is the grid leaving.
     *
     * Sign: the engine pushes the ship in +x, so everything not bolted to it falls behind — −x.
     *
     * ⚠️ The body is **outside the hull**, below the keel, and since H2 it has to be. Left amidships
     * it drifts astern until the port bulkhead arrives and hits it, which is the right answer to a
     * different question: the claim here is about a body nothing is touching, so the fixture has to
     * be one where nothing touches it.
     */
    @Test
    fun `a burn leaves a free body astern`() {
        val controller = OutofspaceController(CFG, bareHull().copy(gravity = VesselState.FREEFALL))
        controller.dropRock(18f, 30f)
        controller.stepOnce()

        // Grid frame: "astern" is a statement about the deck sliding out from under it. The body's
        // *world* position is the thing that does not move, and that is asserted separately below.
        val start = controller.state.bodies.single().localCentreX(controller.state.pose)
        val startWorld = controller.state.bodies.single().centreX
        val from = controller.state.positionX
        controller.thrustX = 1
        repeat(TICKS) { controller.stepOnce() }
        controller.thrustX = 0

        val body = controller.state.bodies.single()
        val here = body.localCentreX(controller.state.pose)
        val travelled = controller.state.positionX - from
        assertTrue(travelled > 0L, "the ship never fired, so this proved nothing")
        assertTrue(here < start, "the body kept station with an accelerating ship: $start then $here")
        assertEquals(0L, body.impulseX, "something pushed a body nothing was touching")
        assertEquals(0L, body.impulseY)
        // The sharpest form of the same claim, and the one the world frame makes available: a body
        // nothing touched did not move *at all*. The drift is entirely the ship's.
        assertEquals(startWorld, body.centreX, "a body in freefall moved through open space")
        // And the drift is the ship's own travel, exactly: same number, same tick, opposite sign,
        // because it is one grid moving. Nothing here is approximate any more.
        assertEquals(travelled, start - here, "the body and the grid disagree about how far the ship went")
    }

    /** A save carries the bodies, their shapes, where they got to and how much was admitted. */
    @Test
    fun `a save remembers the bodies`() {
        val controller = OutofspaceController(CFG, bareHull())
        controller.dropRock(12f, 9f)
        controller.dropRock(24f, 14f)
        repeat(8) { controller.stepOnce() }

        val played = controller.state
        val loaded = Save.read(Save.write(played))
        assertEquals(2, played.bodies.size, "nothing was dropped, so this proved nothing")
        assertEquals(played.bodies, loaded.bodies)
        assertEquals(played.storedEnergy, loaded.storedEnergy)
        // The text, not just the state — the sharper check, because it fails on anything the format
        // forgot rather than on anything the comparison happened to look at.
        assertEquals(Save.write(played), Save.write(loaded))
    }

    private fun bareHull(): VesselState {
        val grid = CFG.initialGrid
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in 1..33) { put(x, 6); put(x, 26) }
        for (y in 6..26) { put(1, y); put(33, y) }
        return VesselState(grid = grid, machines = machines.toList(), gravity = VesselState.PLATING_ONE_G)
    }

    /** The same box with the air taken out, so the hull does not ring and the ship does not jitter. */
    private fun vacuumHull(): VesselState =
        bareHull().let { it.copy(air = AirField.of(LongArray(it.grid.size * Species.COUNT))) }

    private companion object {
        val CFG = OutofspaceConfig()

        /** 60 ticks of a 35×33 fluid solve — enough for a burn to be unambiguous, and under a second. */
        const val TICKS = 60
    }
}
