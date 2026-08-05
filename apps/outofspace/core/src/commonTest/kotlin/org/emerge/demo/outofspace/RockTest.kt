package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rock exists, falls, drifts and is accounted for — increment H1.
 *
 * The interesting half is not that it moves. It is **which gravity it moves under**: a rock over the
 * deck is standing on the plating, and a rock a hundred tiles astern is not, but *both* of them feel
 * the frame's acceleration, because that term is not a force at all — it is the price of writing the
 * world in the frame of something that is speeding up. Get the split backwards and a captured rock
 * either sticks to the ship like a magnet or falls off the bottom of the universe. See [Rock].
 *
 * ⚠️ Nothing here touches anything. A rock flies through the hull, conducts with nothing and blocks
 * nothing; contact is H2. Its **energy** is in the solid ledger from the tick it appears anyway, so
 * that the arrival of conduction is not also the arrival of a discontinuity.
 */
class RockTest {

    @Test
    fun `a rock over the deck falls toward it`() {
        val controller = OutofspaceController(CFG, bareHull())
        controller.dropRock(CFG.grid.index(18, 10))
        controller.stepOnce()

        val start = controller.state.rocks.single().centreY
        repeat(6) { controller.stepOnce() }
        val rock = controller.state.rocks.single()

        assertTrue(rock.centreY > start, "the rock hung in the air at $start")
        assertTrue(rock.velocityY > 0L, "and it is not even falling: ${rock.velocityY}")
        // Sideways is the assertion that says gravity is being applied and not merely noise: nothing
        // pushes along x here, and a rock that wandered would mean the felt gravity had a component
        // it should not.
        assertEquals(0L, rock.velocityX, "the rock drifted sideways under a straight-down gravity")
    }

    /**
     * The plating stops where the vessel does.
     *
     * A rock placed well outside the grid is in open space, and open space has no deck plating in
     * it — so it does not move. That is the half of [feltBy] that cannot be checked by watching
     * something fall, and it is the half that decides whether an asteroid field is a place or a
     * waterfall.
     *
     * ⚠️ **In vacuum, and that is the test being honest rather than the test being easy.** Written
     * against the ordinary air-filled hull this failed, by eight hundredths of a tile over sixty
     * ticks — and it was right to. A sealed vessel's atmosphere rings, the hull recoils from it, and
     * a ship with a non-zero acceleration gives every free rock in the universe an equal and
     * opposite apparent one. That is the model working. The claim being made here is *no plating out
     * there*, not *no motion*, and the two are only the same statement when the ship is not
     * accelerating — so the fixture is one that is not.
     */
    @Test
    fun `a rock in open space is not pulled by the deck plating`() {
        val grid = CFG.grid
        val far = Rock.blob(
            radius = 2,
            positionX = grid.width * 4L * Flight.PER_TILE,
            positionY = grid.height * 4L * Flight.PER_TILE,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        val controller = OutofspaceController(CFG, vacuumHull().copy(rocks = listOf(far)))

        repeat(TICKS) { controller.stepOnce() }

        val rock = controller.state.rocks.single()
        assertEquals(far.positionX, rock.positionX, "open space acquired a floor")
        assertEquals(far.positionY, rock.positionY, "open space acquired a floor")
        assertEquals(0L, rock.impulseX)
        assertEquals(0L, rock.impulseY)
    }

    /**
     * Under thrust and with the plating off, the rock stands still and the **ship** moves.
     *
     * This is the observation the whole increment is for, and it is stated in the rock's coordinates
     * because those are the ship's: the vessel's frame is accelerating, so a rock that is genuinely
     * at rest in the world appears to accelerate the other way. That apparent motion is the pseudo-
     * force, it is the same term the gas already gets from [experiencedGravity], and it is what will
     * make flying at an asteroid in H4 look like flying at an asteroid.
     *
     * Sign: the engine pushes the ship in +x, so everything not bolted to it falls behind — −x.
     */
    @Test
    fun `a burn leaves a free rock astern`() {
        val controller = OutofspaceController(CFG, bareHull().copy(gravity = VesselState.FREEFALL))
        controller.dropRock(CFG.grid.index(18, 16))
        controller.stepOnce()

        val start = controller.state.rocks.single().centreX
        controller.thrustX = 1
        repeat(TICKS) { controller.stepOnce() }
        controller.thrustX = 0

        val rock = controller.state.rocks.single()
        assertTrue(controller.state.positionX > 0L, "the ship never fired, so this proved nothing")
        assertTrue(
            rock.centreX < start,
            "the rock kept station with an accelerating ship: $start then ${rock.centreX}",
        )
        // And it is genuinely at rest in the world: what it gained in the ship's frame should be
        // very close to minus what the ship gained in its own. Not exact -- the ship's mass changes
        // by nothing here but its felt acceleration is a tick behind, so a tick's worth of drift is
        // expected and a run's worth is not.
        val closing = -rock.velocityX
        assertTrue(
            abs(closing - controller.state.velocityX) * 20L < controller.state.velocityX,
            "the rock is not keeping still in the world: closing $closing against ship " +
                "${controller.state.velocityX}",
        )
    }

    /**
     * The rock ledger, which exists because a rock is **new mass in a closed world**.
     *
     * `rockGrams == baselineRockGrams + capturedGrams`, checked while rocks are being created — and
     * the ore balance has to stay closed *beside* it, untouched, because the two are separate
     * ledgers on purpose. Putting a rock through [VesselState.minedGrams] would have been the easy
     * way and would have welded the extractor to the miner it exists to delete.
     */
    @Test
    fun `rock mass is booked and the ore ledger never notices`() {
        val controller = OutofspaceController(CFG, bareHull())
        val oreBefore = controller.state.minedGrams

        repeat(3) { i ->
            controller.dropRock(CFG.grid.index(6 + i * 8, 10))
            controller.stepOnce()
            val s = controller.state
            assertEquals(
                s.baselineRockGrams + s.capturedGrams, s.rockGrams,
                "tick ${s.tick}: ${s.rocks.size} rocks weighing ${s.rockGrams}g against " +
                    "${s.capturedGrams}g admitted",
            )
        }

        val s = controller.state
        assertEquals(3, s.rocks.size)
        assertTrue(s.capturedGrams > 0L, "nothing was ever captured, so this proved nothing")
        assertEquals(oreBefore, s.minedGrams, "a rock went through the miner's ledger")
        assertEquals(s.inTransitGrams + s.ventedGrams, s.minedGrams, "and the ore balance broke")
    }

    /**
     * A rock's energy is in the solid ledger from the tick it appears, through the same term a
     * freshly built wall's heat goes through.
     *
     * It conducts with nothing yet, so this looks like bookkeeping for its own sake. It is not: if
     * the energy were only counted once contact existed, then the tick H2 lands would look exactly
     * like a few megajoules arriving out of nowhere, in a ledger whose whole job is to notice that.
     */
    @Test
    fun `a rock brings its heat with it and the ledger closes`() {
        val controller = OutofspaceController(CFG, bareHull())
        repeat(4) { controller.stepOnce() }
        val before = controller.state.storedJoules

        controller.dropRock(CFG.grid.index(18, 12))
        repeat(4) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.storedJoules > before, "the rock arrived at absolute zero")
        assertEquals(
            s.baselineJoules,
            s.storedJoules + s.radiatedJoules + s.solidToAirJoules - s.generatedJoules - s.constructionJoules,
            "the solid heat ledger broke the tick a rock appeared",
        )
    }

    /** A save carries the rocks, their shapes, where they got to and how much was admitted. */
    @Test
    fun `a save remembers the rocks`() {
        val controller = OutofspaceController(CFG, bareHull())
        controller.dropRock(CFG.grid.index(12, 9))
        controller.dropRock(CFG.grid.index(24, 14))
        repeat(8) { controller.stepOnce() }

        val played = controller.state
        val loaded = Save.read(Save.write(played))
        assertEquals(2, played.rocks.size, "nothing was dropped, so this proved nothing")
        assertEquals(played.rocks, loaded.rocks)
        assertEquals(played.capturedGrams, loaded.capturedGrams)
        assertEquals(played.baselineRockGrams, loaded.baselineRockGrams)
        assertEquals(played.storedJoules, loaded.storedJoules)
        // The text, not just the state — the sharper check, because it fails on anything the format
        // forgot rather than on anything the comparison happened to look at.
        assertEquals(Save.write(played), Save.write(loaded))
    }

    private fun bareHull(): VesselState {
        val grid = CFG.grid
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in 1..33) { put(x, 6); put(x, 26) }
        for (y in 6..26) { put(1, y); put(33, y) }
        return VesselState(grid = grid, machines = machines.toList(), gravity = VesselState.PLATING_ONE_G)
    }

    /** The same box with the air taken out, so the hull does not ring and the ship does not jitter. */
    private fun vacuumHull(): VesselState =
        bareHull().let { it.copy(air = AirField.of(LongArray(it.grid.size * Species.COUNT))) }

    private fun abs(v: Long): Long = if (v < 0L) -v else v

    private companion object {
        val CFG = OutofspaceConfig()

        /** 60 ticks of a 35×33 fluid solve — enough for a burn to be unambiguous, and under a second. */
        const val TICKS = 60
    }
}
