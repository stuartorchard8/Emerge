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
     * ✅ **Un-parked by step 3. The bound is still exactly zero — what changed is the window.**
     *
     * ⚠️ It now runs **while the body is in freefall** rather than for a fixed seven ticks, and that
     * is a narrowing worth stating rather than slipping in. The subject was always the plating: does
     * a straight-down gravity push straight down. Seven ticks used to be a freefall because a body
     * that landed simply stopped; since step 3 it lands on **one corner of a deck that is banked**
     * — the ship is turning, because this very rock's weight is a torque on it — and comes away
     * cartwheeling. Everything after that first touch is the contact solver's business, and reading
     * it here measured the landing while claiming to measure gravity.
     *
     * The gate is [RigidBody.angImpulse], which is exact rather than a guess at when contact
     * happened: the plating acts at the centre of mass and therefore cannot twist a body, so any
     * angular momentum at all means something touched it.
     *
     * ⚠️ A cartwheeling rock **never settles**, and that is a real gap rather than a quirk of this
     * fixture — nothing takes energy out of a spin, because there is no friction until step 4 of
     * `PLAN_rigid_bodies.md`. The already-parked `RockContactTest :: a body that lands on the deck
     * settles and stays put` is where that comes due.
     */
    @Test
    fun `a body falling under straight-down gravity does not drift sideways`() {
        val controller = OutofspaceController(CFG, bareHull())
        controller.dropRock(18f, 10f)

        var freefallTicks = 0
        var lastX = 0L
        var lastY = 0L
        repeat(7) {
            // ⚠️ Asked of **one tick's pull**, in the **ship's** axes, at the pose the pull was
            // applied at. All three of those are the claim getting sharper rather than looser.
            //
            // Plating pulls toward the deck, so "straight down" is a direction in the ship — and
            // this rock's own weight banks the ship, as the note above says. Read in the world, a
            // body falling perpendicular to a banked deck *has* a sideways component, and should:
            // the floor is tilted. Read in the ship's axes it has none at any bank angle, which is
            // what the plating actually promises. Before the frame conversion landed this passed
            // for the wrong reason — the pull was booked along the grid's axes and the world's,
            // which are the same axes only while the ship is square on.
            //
            // Per tick and against the start-of-tick pose because the accumulated velocity is a sum
            // of pulls taken at different bank angles, and no single angle undoes that sum.
            val pose = controller.state.pose
            controller.stepOnce()
            val body = controller.state.bodies.singleOrNull() ?: return@repeat
            val gainedX = body.velocityX - lastX
            val gainedY = body.velocityY - lastY
            lastX = body.velocityX
            lastY = body.velocityY
            if (body.angImpulse != 0L) return@repeat
            freefallTicks++
            // Not exactly zero once the ship is off square: an impulse becomes a velocity by an
            // integer division and then goes through one rotation, and the two together leave a
            // few hundred parts in 10⁹. Measured at 394 against a fall of 1e9. The bound is
            // relative and three orders of magnitude clear of that, which still separates it from
            // the defect by three more: unturned, the same tick drifts by 1.4%.
            assertTrue(
                abs(pose.unturnedX(gainedX, gainedY)) * 100_000L < abs(gainedY),
                "the body drifted sideways under a straight-down gravity: " +
                    "${pose.unturnedX(gainedX, gainedY)} against a fall of $gainedY",
            )
        }
        assertTrue(freefallTicks >= 3, "it never fell freely at all, so nothing was measured")
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

private fun abs(v: Long): Long = if (v < 0L) -v else v
