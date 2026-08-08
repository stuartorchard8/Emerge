package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rock exists, falls, drifts and is accounted for — increment H1.
 *
 * The interesting half is not that it moves. It is **which gravity it moves under**: a rock over the
 * deck is standing on the plating, and a rock a hundred tiles astern is not, because the plating is a
 * field the ship makes and it stops where the ship does. Get that backwards and a captured rock
 * either sticks to the ship like a magnet or falls off the bottom of the universe. See [Rock].
 *
 * ⚠️ Since H2a a rock's **momentum is in the world frame** while its **position is on the grid**, and
 * the tests here are the ones that notice: a rock at rest now reads as a rock at rest, and the drift
 * astern of a burning ship is the grid leaving rather than a pseudo-force pushing. The old version of
 * this file could only check that to within a few per cent; it is exact now.
 *
 * ⚠️ Nothing here touches anything. A rock flies through the hull, conducts with nothing and blocks
 * nothing; contact is H2. Its **energy** is in the solid ledger from the tick it appears anyway, so
 * that the arrival of conduction is not also the arrival of a discontinuity.
 */
class RockTest {

    @Test
    fun `a rock over the deck falls toward it`() {
        val controller = OutofspaceController(CFG, bareHull())
        controller.dropRock(18f, 10f)
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
        val grid = CFG.initialGrid
        val far = Rock.blob(
            radius = 2,
            positionX = grid.width * 4L * Flight.PER_TILE,
            positionY = grid.height * 4L * Flight.PER_TILE,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        val controller = OutofspaceController(CFG, vacuumHull().copy(rocks = listOf(far)))

        RockSpawner.enabled = false

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
     * This is the observation the whole increment is for, and H2a is what made it *cheap*. In the
     * vessel's frame it took a pseudo-force to explain — the frame accelerates, so a rock genuinely
     * at rest has to be given an equal and opposite apparent acceleration, and the old version of
     * this test could only check that within a few per cent because the term was a tick behind. With
     * the momentum written in the world frame there is nothing to explain and nothing to approximate:
     * the rock's velocity is **exactly zero**, forever, and the drift astern is the grid leaving.
     *
     * Sign: the engine pushes the ship in +x, so everything not bolted to it falls behind — −x.
     *
     * ⚠️ The rock is **outside the hull**, below the keel, and since H2 it has to be. Left amidships
     * it drifts astern until the port bulkhead arrives and hits it, which is the right answer to a
     * different question: the claim here is about a rock nothing is touching, so the fixture has to
     * be one where nothing touches it.
     */
    @Test
    fun `a burn leaves a free rock astern`() {
        val controller = OutofspaceController(CFG, bareHull().copy(gravity = VesselState.FREEFALL))
        controller.dropRock(18f, 30f)
        controller.stepOnce()

        val start = controller.state.rocks.single().centreX
        val from = controller.state.positionX
        controller.thrustX = 1
        repeat(TICKS) { controller.stepOnce() }
        controller.thrustX = 0

        val rock = controller.state.rocks.single()
        val travelled = controller.state.positionX - from
        assertTrue(travelled > 0L, "the ship never fired, so this proved nothing")
        assertTrue(
            rock.centreX < start,
            "the rock kept station with an accelerating ship: $start then ${rock.centreX}",
        )
        assertEquals(0L, rock.impulseX, "something pushed a rock nothing was touching")
        assertEquals(0L, rock.impulseY)
        // And the drift is the ship's own travel, exactly: same number, same tick, opposite sign,
        // because it is one grid moving. Nothing here is approximate any more.
        assertEquals(
            travelled, start - rock.centreX,
            "the rock and the grid disagree about how far the ship went",
        )
    }

    /**
     * The rock ledger, which exists because a rock is **new mass in a closed world**.
     *
     * `rockGrams == baselineRockGrams + capturedGrams − extractedGrams`, checked while rocks are
     * being created — and nothing has been extracted here, so the third term is zero and this is H1's
     * identity unchanged. What must stay true beside it is that **arriving** is not **extracting**: a
     * rock appearing in the world moves the rock ledger and leaves the ore ledger alone, because only
     * an extractor may turn one into the other. See §5i.
     */
    @Test
    fun `rock mass is booked and the ore ledger never notices`() {
        val controller = OutofspaceController(CFG, bareHull())
        val oreBefore = controller.state.extractedGrams

        repeat(3) { i ->
            controller.dropRock(6f + i * 8f, 10f)
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
        assertEquals(oreBefore, s.extractedGrams, "a rock arriving counted as ore extracted")
        assertEquals(s.inTransitGrams + s.ventedGrams, s.extractedGrams, "and the ore balance broke")
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

        controller.dropRock(18f, 12f)
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
        controller.dropRock(12f, 9f)
        controller.dropRock(24f, 14f)
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
